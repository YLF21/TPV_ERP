package com.tpverp.backend.verifactu;

import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerifactuAdminReviewReadService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final EnumSet<FiscalSubmissionStatus> DEFECTIVE_STATUSES = EnumSet.of(
            FiscalSubmissionStatus.RECHAZADO,
            FiscalSubmissionStatus.DEFECTUOSO,
            FiscalSubmissionStatus.ACEPTADO_CON_ERRORES);

    private final CurrentOrganization organization;
    private final InstallationRepository installations;
    private final LicenseRepository licenses;
    private final VerifactuAdminReviewReadRepository reads;
    private final VerifactuSubmissionPropertiesFactory properties;
    private final VerifactuClockMonitor clockMonitor;
    private final Environment environment;
    private final Clock clock;

    public VerifactuAdminReviewReadService(
            CurrentOrganization organization,
            VerifactuAdminReviewReadRepository reads,
            VerifactuSubmissionPropertiesFactory properties,
            VerifactuClockMonitor clockMonitor,
            Environment environment,
            Clock clock) {
        this(organization, null, null, reads, properties, clockMonitor, environment, clock);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public VerifactuAdminReviewReadService(
            CurrentOrganization organization,
            InstallationRepository installations,
            LicenseRepository licenses,
            VerifactuAdminReviewReadRepository reads,
            VerifactuSubmissionPropertiesFactory properties,
            VerifactuClockMonitor clockMonitor,
            Environment environment,
            Clock clock) {
        this.organization = organization;
        this.installations = installations;
        this.licenses = licenses;
        this.reads = reads;
        this.properties = properties;
        this.clockMonitor = clockMonitor;
        this.environment = environment;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public VerifactuAdminDefectiveRecordPage defectiveRecords(
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
        validateDefectiveStatus(status);
        var normalizedNumber = normalizeDocumentNumber(documentNumber);
        var store = organization.currentStore();
        var zone = ZoneId.of(store.getTimezone());
        var scope = scope(store);
        if (scope.installationId() == null) {
            return reads.findDefectiveRecords(scope.companyId(), scope.storeId(),
                    startOfDay(dateFrom, zone), startOfNextDay(dateTo, zone), status,
                    documentType, operation, normalizedNumber, page, size);
        }
        return reads.findDefectiveRecords(scope.companyId(), scope.storeId(), scope.installationId(),
                startOfDay(dateFrom, zone), startOfNextDay(dateTo, zone), status,
                documentType, operation, normalizedNumber, page, size);
    }

    @Transactional(readOnly = true)
    public VerifactuAdminDefectiveRecordPage defectiveRecords(
            LocalDate dateFrom,
            LocalDate dateTo,
            FiscalSubmissionStatus status,
            FiscalDocumentType documentType,
            FiscalRecordOperation operation,
            String documentNumber,
            int page,
            int size,
            String sortBy,
            String sortDirection) {
        validatePage(page, size);
        validateRange(dateFrom, dateTo);
        validateDefectiveStatus(status);
        var normalizedNumber = normalizeDocumentNumber(documentNumber);
        var store = organization.currentStore();
        var zone = ZoneId.of(store.getTimezone());
        var scope = scope(store);
        if (scope.installationId() == null) {
            return reads.findDefectiveRecords(scope.companyId(), scope.storeId(),
                    startOfDay(dateFrom, zone), startOfNextDay(dateTo, zone), status,
                    documentType, operation, normalizedNumber, page, size, sortBy,
                    sortDirection);
        }
        return reads.findDefectiveRecords(scope.companyId(), scope.storeId(), scope.installationId(),
                startOfDay(dateFrom, zone), startOfNextDay(dateTo, zone), status,
                documentType, operation, normalizedNumber, page, size, sortBy,
                sortDirection);
    }

    @Transactional(readOnly = true)
    public VerifactuAdminAttemptPage attempts(UUID recordId, int page, int size) {
        validatePage(page, size);
        var store = organization.currentStore();
        var scope = scope(store);
        var exists = scope.installationId() == null
                ? reads.recordExists(scope.companyId(), scope.storeId(), recordId)
                : reads.recordExists(scope.companyId(), scope.storeId(), scope.installationId(), recordId);
        if (!exists) {
            throw new NoSuchElementException("Registro fiscal no encontrado");
        }
        return scope.installationId() == null
                ? reads.findAttempts(scope.companyId(), scope.storeId(), recordId, page, size)
                : reads.findAttempts(scope.companyId(), scope.storeId(), scope.installationId(), recordId, page, size);
    }

    @Transactional(readOnly = true)
    public VerifactuAdminDiagnosticView diagnostics() {
        var store = organization.currentStore();
        var scope = scope(store);
        var endpointMode = endpointMode();
        return new VerifactuAdminDiagnosticView(
                endpointMode != null,
                endpointMode,
                Boolean.TRUE.equals(environment.getProperty(
                        "tpv.verifactu.worker-enabled", Boolean.class, false)),
                clockSummary(),
                scope.installationId() == null
                        ? reads.findLastAttempt(scope.companyId(), scope.storeId())
                        : reads.findLastAttempt(scope.companyId(), scope.storeId(), scope.installationId()),
                clock.instant());
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
        } catch (RuntimeException exception) {
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

    private static void validateDefectiveStatus(FiscalSubmissionStatus status) {
        if (status != null && !DEFECTIVE_STATUSES.contains(status)) {
            throw new IllegalArgumentException("status no corresponde a un registro defectuoso");
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

    private Scope scope(com.tpverp.backend.organization.Store store) {
        var company = store.getEmpresa();
        if (company == null) {
            throw new IllegalStateException("La empresa de la tienda no esta inicializada");
        }
        var installationId = installations == null || licenses == null
                ? null
                : FiscalInstallationResolver.resolveCurrent(organization, installations, licenses).getId();
        return new Scope(company.getId(), store.getId(), installationId);
    }

    private record Scope(UUID companyId, UUID storeId, UUID installationId) {
    }
}
