package com.tpverp.backend.licensing;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.installation.CommercialBootstrapService;
import com.tpverp.backend.licensing.application.LicenseValidationException;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CompanyRepository;
import com.tpverp.backend.organization.SpanishTaxId;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import com.tpverp.backend.terminal.TerminalType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

public class LicenseSaasLinkService {

    private final InstallationRepository installations;
    private final CompanyRepository companies;
    private final StoreRepository stores;
    private final LicenseRepository licenses;
    private final LicenseSaasLinkClient client;
    private final LicenseSaasCredentialStore credentials;
    private final TerminalRepository terminals;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;
    private final CommercialBootstrapService commercialBootstrap;

    public LicenseSaasLinkService(
            InstallationRepository installations,
            CompanyRepository companies,
            StoreRepository stores,
            LicenseRepository licenses,
            LicenseSaasLinkClient client,
            LicenseSaasCredentialStore credentials,
            TerminalRepository terminals,
            PasswordEncoder passwordEncoder,
            Clock clock,
            AuditService auditService,
            JdbcTemplate jdbc,
            CommercialBootstrapService commercialBootstrap) {
        this.installations = installations;
        this.companies = companies;
        this.stores = stores;
        this.licenses = licenses;
        this.client = client;
        this.credentials = credentials;
        this.terminals = terminals;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.auditService = auditService;
        this.jdbc = jdbc;
        this.commercialBootstrap = commercialBootstrap;
    }

    @Transactional
    public LicenseSaasLinkResult link(String pairingCode) {
        Installation installation = currentInstallation();
        Optional<Store> existingStore = stores.findAll().stream().findFirst();
        String normalizedCode = required(pairingCode, "pairingCode");
        LicenseSaasLinkResponse response = client.link(existingStore
                .map(store -> requestWithLocalStore(normalizedCode, installation, store))
                .orElseGet(() -> requestWithoutLocalStore(normalizedCode, installation)));
        Store store = resolveStore(response, existingStore);
        Terminal server = ensureServerTerminal(store);
        activateLinkedLicense(installation, store, response);
        credentials.writeToken(response.installationToken());
        auditService.record(
                "LICENSE_SAAS_LINKED",
                AuditResult.EXITO,
                Map.of("reference", response.licenseReference(), "saasStoreId", response.storeId()));
        return new LicenseSaasLinkResult(
                response,
                store.getEmpresa().getId(),
                store.getId(),
                server.getId());
    }

    private Terminal ensureServerTerminal(Store store) {
        return terminals.findByTiendaIdAndTipo(store.getId(), TerminalType.SERVIDOR)
                .map(terminal -> {
                    if (!terminal.isAprobada() || !terminal.isActiva()) {
                        terminal.approve();
                    }
                    return terminal;
                })
                .orElseGet(() -> terminals.save(new Terminal(
                        store,
                        "SERVIDOR",
                        TerminalType.SERVIDOR,
                        passwordEncoder.encode(UUID.randomUUID().toString()))));
    }

    private LicenseSaasLinkRequest requestWithLocalStore(
            String pairingCode,
            Installation installation,
            Store store) {
        return new LicenseSaasLinkRequest(
                pairingCode,
                installation.getId(),
                installation.getReferencia(),
                installation.getPublicKey(),
                store.getId(),
                store.getCodigoTienda(),
                store.getEmpresa().getTaxId(),
                store.getEmpresa().getRazonSocial());
    }

    private LicenseSaasLinkRequest requestWithoutLocalStore(String pairingCode, Installation installation) {
        return new LicenseSaasLinkRequest(
                pairingCode,
                installation.getId(),
                installation.getReferencia(),
                installation.getPublicKey(),
                null,
                null,
                null,
                null);
    }

    private Store resolveStore(LicenseSaasLinkResponse response, Optional<Store> existingStore) {
        if (existingStore.isPresent()) {
            validateResponse(existingStore.get().getEmpresa(), response);
            return existingStore.get();
        }
        validateOfficialOrganization(response);
        Company company = companies.save(new Company(
                SpanishTaxId.normalize(response.companyTaxId()),
                response.companyName(),
                response.companyAddress()));
        var store = stores.save(new Store(
                company,
                response.storeCode(),
                response.storeName(),
                response.storeAddress(),
                addressHash(response.storeAddress()),
                "Atlantic/Canary",
                "EUR",
                "es-ES"));
        commercialBootstrap.initializeStore(store.getId(), company.getId());
        return store;
    }

    private void activateLinkedLicense(
            Installation installation,
            Store store,
            LicenseSaasLinkResponse response) {
        var existing = licenses.findByReferencia(response.licenseReference());
        if (existing.isPresent()) {
            License license = existing.get();
            if (!license.getTiendaId().equals(store.getId())
                    || !license.getInstalacionId().equals(installation.getId())) {
                throw new LicenseValidationException("Esta licencia ya fue importada");
            }
            markSaasStatus(license, response, Instant.now(clock));
            licenses.save(license);
            updateDefaultTax(store.getId(), response.impuestos());
            return;
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
                SpanishTaxId.normalize(taxId(response)),
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
        markSaasStatus(license, response, now);
        licenses.save(license);
        updateDefaultTax(store.getId(), response.impuestos());
    }

    private void markSaasStatus(License license, LicenseSaasLinkResponse response, Instant now) {
        if (response.status() == LicenseSaasStatus.VALIDA) {
            license.markSaasValidated(now, response.validUntil());
        } else {
            license.markSaasRejected(now, response.status(), response.validUntil());
        }
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
        String normalized = SpanishTaxId.normalize(taxId(response));
        if (Company.DEMO_TAX_ID.equals(company.getTaxId())) {
            company.adoptLicensedTaxId(normalized);
            return;
        }
        if (!SpanishTaxId.normalize(company.getTaxId()).equals(normalized)) {
            throw new LicenseValidationException("El NIF de la licencia no coincide con la empresa");
        }
    }

    private void validateOfficialOrganization(LicenseSaasLinkResponse response) {
        validateResponse(new Company(taxId(response), response.companyName(), response.companyAddress()), response);
        required(response.storeCode(), "storeCode");
        required(response.storeName(), "storeName");
        Objects.requireNonNull(response.storeAddress(), "storeAddress");
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

    private String addressHash(Map<String, String> address) {
        try {
            String normalized = String.join("|",
                    required(address.get("linea1"), "linea1"),
                    required(address.get("ciudad"), "ciudad"),
                    required(address.get("codigoPostal"), "codigoPostal"),
                    required(address.get("provincia"), "provincia"),
                    required(address.get("pais"), "pais")).toUpperCase(java.util.Locale.ROOT);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (LicenseValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo normalizar direccion de tienda SaaS", exception);
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

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new LicenseValidationException("Falta " + field);
        }
        return value.trim();
    }

    private String taxId(LicenseSaasLinkResponse response) {
        return response.companyTaxId() == null || response.companyTaxId().isBlank()
                ? response.taxId()
                : response.companyTaxId();
    }
}
