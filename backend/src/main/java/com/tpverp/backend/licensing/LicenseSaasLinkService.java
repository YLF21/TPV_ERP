package com.tpverp.backend.licensing;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.application.LicenseValidationException;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.SpanishTaxId;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class LicenseSaasLinkService {

    private final InstallationRepository installations;
    private final StoreRepository stores;
    private final LicenseRepository licenses;
    private final LicenseSaasLinkClient client;
    private final LicenseSaasCredentialStore credentials;
    private final Clock clock;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    public LicenseSaasLinkService(
            InstallationRepository installations,
            StoreRepository stores,
            LicenseRepository licenses,
            LicenseSaasLinkClient client,
            LicenseSaasCredentialStore credentials,
            Clock clock,
            AuditService auditService,
            JdbcTemplate jdbc) {
        this.installations = installations;
        this.stores = stores;
        this.licenses = licenses;
        this.client = client;
        this.credentials = credentials;
        this.clock = clock;
        this.auditService = auditService;
        this.jdbc = jdbc;
    }

    @Transactional
    public LicenseSaasLinkResponse link(String pairingCode) {
        Installation installation = currentInstallation();
        Store store = currentStore();
        String normalizedCode = required(pairingCode, "pairingCode");
        LicenseSaasLinkResponse response = client.link(new LicenseSaasLinkRequest(
                normalizedCode,
                installation.getId(),
                installation.getReferencia(),
                installation.getPublicKey(),
                store.getId(),
                store.getCodigoTienda(),
                store.getEmpresa().getTaxId(),
                store.getEmpresa().getRazonSocial()));
        credentials.writeToken(response.installationToken());
        activateLinkedLicense(installation, store, response);
        auditService.record(
                "LICENSE_SAAS_LINKED",
                AuditResult.EXITO,
                Map.of("reference", response.licenseReference(), "saasStoreId", response.storeId()));
        return response;
    }

    private void activateLinkedLicense(
            Installation installation,
            Store store,
            LicenseSaasLinkResponse response) {
        validateResponse(store.getEmpresa(), response);
        if (licenses.findByReferencia(response.licenseReference()).isPresent()) {
            throw new LicenseValidationException("Esta licencia ya fue importada");
        }
        licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(store.getId(), installation.getId())
                .ifPresent(License::deactivate);
        Instant now = Instant.now(clock);
        var license = new License(
                store,
                installation,
                response.licenseReference(),
                now,
                response.validUntil(),
                response.maxWindows(),
                response.maxPda(),
                SpanishTaxId.normalize(response.taxId()),
                response.taxpayerType(),
                response.impuestos(),
                "SAAS_LINK:" + response.licenseReference(),
                hash(response),
                4,
                now,
                metadata(response),
                ImportResult.ACEPTADA,
                null,
                true);
        if (response.status() == LicenseSaasStatus.BLOQUEADA_MANUAL) {
            license.markSaasBlocked(now);
        } else {
            license.markSaasValidated(now, response.validUntil());
        }
        licenses.save(license);
        updateDefaultTax(store.getId(), response.impuestos());
    }

    private void validateResponse(Company company, LicenseSaasLinkResponse response) {
        Objects.requireNonNull(response, "response");
        required(response.licenseReference(), "licenseReference");
        Objects.requireNonNull(response.companyId(), "companyId");
        Objects.requireNonNull(response.storeId(), "storeId");
        Objects.requireNonNull(response.validUntil(), "validUntil");
        Objects.requireNonNull(response.status(), "status");
        if (response.maxWindows() < 1 || response.maxPda() < 0) {
            throw new LicenseValidationException("Los cupos de la licencia no son validos");
        }
        String normalized = SpanishTaxId.normalize(response.taxId());
        if (Company.DEMO_TAX_ID.equals(company.getTaxId())) {
            company.adoptLicensedTaxId(normalized);
            return;
        }
        if (!SpanishTaxId.normalize(company.getTaxId()).equals(normalized)) {
            throw new LicenseValidationException("El NIF de la licencia no coincide con la empresa");
        }
    }

    private Map<String, Object> metadata(LicenseSaasLinkResponse response) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("source", "SAAS_LINK");
        metadata.put("saasCompanyId", response.companyId().toString());
        metadata.put("saasStoreId", response.storeId().toString());
        return metadata;
    }

    private String hash(LicenseSaasLinkResponse response) {
        try {
            String value = response.licenseReference()
                    + "|" + response.companyId()
                    + "|" + response.storeId()
                    + "|" + response.validUntil()
                    + "|" + response.installationToken();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo calcular hash de licencia SaaS", exception);
        }
    }

    private void updateDefaultTax(UUID storeId, TaxRegime regime) {
        BigDecimal percentage = regime == TaxRegime.IGIC
                ? new BigDecimal("7.00")
                : new BigDecimal("21.00");
        jdbc.update("update impuesto_tienda set predeterminado = false where tienda_id = ?", storeId);
        int updated = jdbc.update(
                "update impuesto_tienda set activo = true, predeterminado = true "
                        + "where tienda_id = ? and porcentaje = ?",
                storeId,
                percentage);
        if (updated == 0) {
            jdbc.update(
                    "insert into impuesto_tienda "
                            + "(id, tienda_id, porcentaje, activo, predeterminado) "
                            + "values (?, ?, ?, true, true)",
                    UUID.randomUUID(),
                    storeId,
                    percentage);
        }
    }

    private Installation currentInstallation() {
        return installations.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("La instalacion no esta inicializada"));
    }

    private Store currentStore() {
        return stores.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("La tienda no esta inicializada"));
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new LicenseValidationException("Falta " + field);
        }
        return value.trim();
    }
}
