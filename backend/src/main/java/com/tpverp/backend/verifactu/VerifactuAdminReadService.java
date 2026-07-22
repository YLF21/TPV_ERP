package com.tpverp.backend.verifactu;

import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerifactuAdminReadService {

    private static final int MAX_PAGE_SIZE = 100;

    private final CurrentOrganization organization;
    private final VerifactuAdminReadRepository reads;
    private final VerifactuConfigurationRepository configurations;
    private final LicenseRepository licenses;
    private final VerifactuActivationService activation;
    private final ManagedVerifactuCertificateRepository certificates;
    private final VerifactuSubmissionPropertiesFactory properties;
    private final VerifactuClockMonitor clockMonitor;
    private final Environment environment;
    private final Clock clock;

    public VerifactuAdminReadService(
            CurrentOrganization organization,
            VerifactuAdminReadRepository reads,
            VerifactuConfigurationRepository configurations,
            LicenseRepository licenses,
            VerifactuActivationService activation,
            ManagedVerifactuCertificateRepository certificates,
            VerifactuSubmissionPropertiesFactory properties,
            VerifactuClockMonitor clockMonitor,
            Environment environment,
            Clock clock) {
        this.organization = organization;
        this.reads = reads;
        this.configurations = configurations;
        this.licenses = licenses;
        this.activation = activation;
        this.certificates = certificates;
        this.properties = properties;
        this.clockMonitor = clockMonitor;
        this.environment = environment;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public VerifactuAdminSummaryView summary() {
        var store = organization.currentStore();
        var companyId = store.getEmpresa().getId();
        var storeId = store.getId();
        var now = clock.instant();
        var activationSummary = activationSummary(store.getTimezone(), companyId, storeId, now);
        var counts = new EnumMap<FiscalSubmissionStatus, Long>(FiscalSubmissionStatus.class);
        for (var status : FiscalSubmissionStatus.values()) {
            counts.put(status, 0L);
        }
        counts.putAll(reads.countByStatus(companyId, storeId));
        return new VerifactuAdminSummaryView(
                activationSummary.active(),
                activationSummary.mode(),
                activationSummary.effectiveActivationAt(),
                activationSummary.firstSubmissionAt(),
                endpointMode(),
                Boolean.TRUE.equals(environment.getProperty(
                        "tpv.verifactu.worker-enabled", Boolean.class, false)),
                counts,
                reads.findOldestPendingAt(companyId, storeId),
                certificateSummary(companyId, now),
                clockSummary());
    }

    @Transactional(readOnly = true)
    public VerifactuAdminSubmissionPage submissions(
            LocalDate dateFrom,
            LocalDate dateTo,
            FiscalSubmissionStatus status,
            FiscalDocumentType documentType,
            FiscalRecordOperation operation,
            String documentNumber,
            int page,
            int size) {
        validatePage(page, size);
        validateRange(dateFrom, dateTo);
        var normalizedNumber = normalizeDocumentNumber(documentNumber);
        var store = organization.currentStore();
        var zone = ZoneId.of(store.getTimezone());
        var companyId = store.getEmpresa().getId();
        return reads.findSubmissions(
                companyId,
                store.getId(),
                startOfDay(dateFrom, zone),
                startOfNextDay(dateTo, zone),
                status,
                documentType,
                operation,
                normalizedNumber,
                page,
                size);
    }

    private ActivationSummary activationSummary(
            String timezone,
            java.util.UUID companyId,
            java.util.UUID storeId,
            Instant now) {
        var configuration = configurations.findByCompanyId(companyId);
        if (configuration.filter(VerifactuConfiguration::isVoluntarilyActive).isPresent()) {
            var current = configuration.orElseThrow();
            return new ActivationSummary(
                    true, "VOLUNTARY", current.getActivatedAt(), current.getFirstSubmissionAt());
        }
        if (configuration.map(VerifactuConfiguration::getFirstSubmissionAt).isPresent()) {
            var current = configuration.orElseThrow();
            return new ActivationSummary(
                    true, "LOCKED", current.getActivatedAt(), current.getFirstSubmissionAt());
        }
        var license = licenses.findByTiendaIdOrderByValidaDesdeDesc(storeId).stream()
                .filter(License::isActiva)
                .findFirst();
        if (license.isEmpty()) {
            return new ActivationSummary(
                    false, "UNAVAILABLE", null,
                    configuration.map(VerifactuConfiguration::getFirstSubmissionAt).orElse(null));
        }
        var zone = ZoneId.of(timezone);
        var activeLicense = license.orElseThrow();
        if (activation.isAutomaticallyRequired(
                activeLicense.getTaxpayerType(),
                activeLicense.getVerifactuActivationDate(),
                now,
                zone)) {
            return new ActivationSummary(
                    true,
                    activeLicense.getVerifactuActivationDate() == null
                            ? "LEGAL_FALLBACK" : "LICENSE_POLICY",
                    activation.activationInstant(
                            activeLicense.getTaxpayerType(),
                            activeLicense.getVerifactuActivationDate(),
                            zone),
                    configuration.map(VerifactuConfiguration::getFirstSubmissionAt).orElse(null));
        }
        return new ActivationSummary(
                false, "INACTIVE", null,
                configuration.map(VerifactuConfiguration::getFirstSubmissionAt).orElse(null));
    }

    private VerifactuAdminCertificateSummary certificateSummary(
            java.util.UUID companyId,
            Instant now) {
        var certificate = certificates.findByCompanyIdAndStatus(
                companyId, ManagedCertificateStatus.ACTIVO);
        if (certificate.isEmpty()) {
            return new VerifactuAdminCertificateSummary(
                    false, false, "CERTIFICATE_NOT_CONFIGURED", null);
        }
        var current = certificate.orElseThrow();
        if (now.isBefore(current.getValidFrom())) {
            return new VerifactuAdminCertificateSummary(
                    true, false, "CERTIFICATE_NOT_YET_VALID", current.getValidUntil());
        }
        if (now.isAfter(current.getValidUntil())) {
            return new VerifactuAdminCertificateSummary(
                    true, false, "CERTIFICATE_EXPIRED", current.getValidUntil());
        }
        return new VerifactuAdminCertificateSummary(true, true, null, current.getValidUntil());
    }

    private VerifactuAdminClockSummary clockSummary() {
        try {
            var status = clockMonitor.current();
            return new VerifactuAdminClockSummary(
                    true,
                    status.warning(),
                    status.warningCode(),
                    status.driftSeconds(),
                    status.thresholdSeconds(),
                    status.checkedAt());
        } catch (RuntimeException exception) {
            return new VerifactuAdminClockSummary(
                    false, true, "CLOCK_STATUS_UNAVAILABLE", null, null, null);
        }
    }

    private VerifactuEndpointMode endpointMode() {
        try {
            var current = properties.current();
            return current == null ? null : current.mode();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static void validatePage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page no puede ser negativo");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size debe estar entre 1 y " + MAX_PAGE_SIZE);
        }
    }

    private static void validateRange(LocalDate dateFrom, LocalDate dateTo) {
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new IllegalArgumentException("dateFrom no puede ser posterior a dateTo");
        }
    }

    private static String normalizeDocumentNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = value.trim();
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("documentNumber no puede superar 64 caracteres");
        }
        return normalized;
    }

    private static Instant startOfDay(LocalDate date, ZoneId zone) {
        return date == null ? null : date.atStartOfDay(zone).toInstant();
    }

    private static Instant startOfNextDay(LocalDate date, ZoneId zone) {
        if (date == null) {
            return null;
        }
        try {
            return date.plusDays(1).atStartOfDay(zone).toInstant();
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("dateTo no es valida", exception);
        }
    }

    private record ActivationSummary(
            boolean active,
            String mode,
            Instant effectiveActivationAt,
            Instant firstSubmissionAt) {
    }
}
