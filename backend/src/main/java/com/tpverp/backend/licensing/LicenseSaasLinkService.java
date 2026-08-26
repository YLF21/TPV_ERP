package com.tpverp.backend.licensing;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.installation.CommercialBootstrapService;
import com.tpverp.backend.licensing.application.CommercialProfile;
import com.tpverp.backend.licensing.application.LicenseValidationException;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CompanyRepository;
import com.tpverp.backend.organization.CurrentOrganization;
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
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class LicenseSaasLinkService {

    private final InstallationRepository installations;
    private final CompanyRepository companies;
    private final StoreRepository stores;
    private final CurrentOrganization organization;
    private final LicenseRepository licenses;
    private final LicenseSaasLinkClient client;
    private final LicenseSaasCredentialStore credentials;
    private final LicenseSaasCacheAuthenticator cacheAuthenticator;
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
            CurrentOrganization organization,
            LicenseRepository licenses,
            LicenseSaasLinkClient client,
            LicenseSaasCredentialStore credentials,
            LicenseSaasCacheAuthenticator cacheAuthenticator,
            TerminalRepository terminals,
            PasswordEncoder passwordEncoder,
            Clock clock,
            AuditService auditService,
            JdbcTemplate jdbc,
            CommercialBootstrapService commercialBootstrap) {
        this.installations = installations;
        this.companies = companies;
        this.stores = stores;
        this.organization = organization;
        this.licenses = licenses;
        this.client = client;
        this.credentials = credentials;
        this.cacheAuthenticator = cacheAuthenticator;
        this.terminals = terminals;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.auditService = auditService;
        this.jdbc = jdbc;
        this.commercialBootstrap = commercialBootstrap;
    }

    @Transactional
    public synchronized LicenseSaasLinkResult link(String pairingCode, UUID localStoreId) {
        if (stores.count() == 0) {
            throw new LicenseValidationException(
                    "La base esta vacia; use la ruta de aprovisionamiento inicial SaaS");
        }
        Store authenticatedStore = organization.currentStore();
        if (localStoreId != null && !localStoreId.equals(authenticatedStore.getId())) {
            throw new LicenseValidationException(
                    "localStoreId no coincide con la tienda de la sesion autenticada");
        }
        return link(pairingCode, Optional.of(authenticatedStore));
    }

    @Transactional
    public synchronized LicenseSaasLinkResult bootstrapEmptyDatabase(String pairingCode) {
        if (!businessDatabaseIsEmpty()) {
            throw new LicenseValidationException(
                    "El aprovisionamiento inicial solo esta permitido con la base vacia");
        }
        return link(pairingCode, Optional.empty());
    }

    private boolean businessDatabaseIsEmpty() {
        return companies.count() == 0
                && stores.count() == 0
                && licenses.count() == 0
                && terminals.count() == 0;
    }

    private LicenseSaasLinkResult link(String pairingCode, Optional<Store> existingStore) {
        Installation installation = currentInstallation();
        String normalizedCode = required(pairingCode, "pairingCode");
        validateSingleSaasStoreBinding(installation, existingStore);
        // This credential must be durable before SaaS can consume the one-time
        // pairing code. If the HTTP response is lost, the same local attempt can
        // prove ownership without ever knowing the first installation token.
        String recoveryToken = credentials.getOrCreateLinkRecoveryToken();
        LicenseSaasLinkResponse response = client.link(existingStore
                .map(store -> requestWithLocalStore(normalizedCode, installation, store))
                .orElseGet(() -> requestWithoutLocalStore(normalizedCode, installation)),
                recoveryToken);
        LicenseSaasLinkResponseContract.requireCurrent(response);
        Store store = resolveStore(response, existingStore);
        Terminal server = ensureServerTerminal(store);
        // La nueva credencial queda protegida antes de usarla como clave HMAC.
        // Si la transaccion local falla, el secreto de recuperacion se conserva.
        credentials.writeToken(response.installationToken());
        activateLinkedLicense(installation, store, response);
        auditService.record(
                "LICENSE_SAAS_LINKED",
                AuditResult.EXITO,
                Map.of("reference", response.licenseReference(), "saasStoreId", response.storeId()));
        clearLinkRecoveryTokenAfterCommit();
        return new LicenseSaasLinkResult(
                response,
                store.getEmpresa().getId(),
                store.getId(),
                server.getId());
    }

    private void validateSingleSaasStoreBinding(
            Installation installation,
            Optional<Store> requestedStore) {
        var linkedLicenses = licenses.findByInstalacion_IdAndActivaTrue(installation.getId()).stream()
                .filter(license -> license.isSaasLinked()
                        || license.getSaasCompanyId() != null
                        || license.getSaasStoreId() != null)
                .toList();
        if (linkedLicenses.isEmpty()) {
            return;
        }
        if (linkedLicenses.stream().anyMatch(license ->
                license.getSaasCompanyId() == null || license.getSaasStoreId() == null)) {
            throw new LicenseValidationException(
                    "La licencia SaaS activa no identifica de forma segura su empresa y tienda; "
                            + "corrija la vinculacion antes de consumir otro codigo de enlace");
        }
        boolean sameLocalStore = requestedStore.isPresent()
                && linkedLicenses.stream().allMatch(license ->
                        license.getTiendaId().equals(requestedStore.get().getId()));
        long saasStores = linkedLicenses.stream()
                .map(License::getSaasStoreId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        long references = linkedLicenses.stream()
                .map(License::getReferencia)
                .distinct()
                .count();
        if (!sameLocalStore || saasStores > 1 || references > 1) {
            throw new LicenseValidationException(
                    "Una instalacion local solo puede vincularse a una tienda SaaS; "
                            + "desvincule o sustituya la instalacion antes de enlazar otra tienda");
        }
    }

    private void clearLinkRecoveryTokenAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            credentials.clearLinkRecoveryToken();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                credentials.clearLinkRecoveryToken();
            }
        });
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
                store.getEmpresa().getRazonSocial(),
                store.getEmpresa().getDomicilioFiscal(),
                store.getDireccion(),
                store.getTimezone());
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
            validateResponse(existingStore.get(), response);
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
                required(response.timeZoneId(), "timeZoneId"),
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
            license.updateCommercialProfile(response.commercialProfile());
            markSaasStatus(license, store, response, Instant.now(clock));
            cacheAuthenticator.seal(license);
            licenses.save(license);
            updateDefaultTax(
                    store.getId(), response.impuestos(), response.commercialProfile());
            commercialBootstrap.ensureOpenPriceProduct(store.getId());
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
                response.commercialProfile(),
                "SAAS_LINK:" + response.licenseReference(),
                hash(response),
                4,
                now,
                metadata(response),
                ImportResult.ACEPTADA,
                null,
                true);
        markSaasStatus(license, store, response, now);
        cacheAuthenticator.seal(license);
        licenses.save(license);
        updateDefaultTax(
                store.getId(), response.impuestos(), response.commercialProfile());
        commercialBootstrap.ensureOpenPriceProduct(store.getId());
    }

    private void markSaasStatus(
            License license,
            Store store,
            LicenseSaasLinkResponse response,
            Instant now) {
        LocalDate currentDate = license.getVerifactuActivationDate();
        LocalDate receivedDate = Objects.requireNonNull(
                response.verifactuActivationDate(), "verifactuActivationDate");
        LocalDate today = now.atZone(java.time.ZoneId.of(store.getTimezone())).toLocalDate();
        boolean wouldDeactivateReachedPolicy = currentDate != null
                && !today.isBefore(currentDate)
                && receivedDate.isAfter(currentDate);
        if (!wouldDeactivateReachedPolicy) {
            license.applyVerifactuPolicy(
                    receivedDate,
                    response.verifactuPolicyVersion(),
                    Objects.requireNonNull(response.verifactuPolicyUpdatedAt(), "verifactuPolicyUpdatedAt"));
        }
        license.applySaasLicenseSnapshot(
                now,
                response.status(),
                response.validUntil(),
                response.maxWindows(),
                response.maxPda(),
                response.licenseVersion());
    }

    private void validateResponse(Store store, LicenseSaasLinkResponse response) {
        Objects.requireNonNull(response, "response");
        required(response.licenseReference(), "licenseReference");
        Objects.requireNonNull(response.companyId(), "companyId");
        Objects.requireNonNull(response.storeId(), "storeId");
        Objects.requireNonNull(response.validUntil(), "validUntil");
        Objects.requireNonNull(response.status(), "status");
        Objects.requireNonNull(response.verifactuActivationDate(), "verifactuActivationDate");
        Objects.requireNonNull(response.verifactuPolicyUpdatedAt(), "verifactuPolicyUpdatedAt");
        if (response.verifactuPolicyVersion() < 0) {
            throw new LicenseValidationException("La version de politica VERI*FACTU no es valida");
        }
        if (response.maxWindows() < 1 || response.maxPda() < 0) {
            throw new LicenseValidationException("Los cupos de la licencia no son validos");
        }
        if (response.licenseVersion() < 1) {
            throw new LicenseValidationException("La version de licencia SaaS no es valida");
        }
        String responseStoreCode = required(response.storeCode(), "storeCode");
        if (!store.getCodigoTienda().equals(responseStoreCode)) {
            throw new LicenseValidationException(
                    "El codigo de tienda de la licencia no coincide con la tienda local");
        }
        String responseTimeZone = required(response.timeZoneId(), "timeZoneId");
        try {
            responseTimeZone = com.tpverp.backend.organization.StoreFiscalIdentity.timezone(
                    responseTimeZone);
        } catch (IllegalArgumentException exception) {
            throw new LicenseValidationException("La zona horaria de la licencia no es valida");
        }
        if (!store.getTimezone().equals(responseTimeZone)) {
            throw new LicenseValidationException(
                    "La zona horaria de la licencia no coincide con la tienda local");
        }
        Company company = store.getEmpresa();
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
        Objects.requireNonNull(response, "response");
        required(response.licenseReference(), "licenseReference");
        Objects.requireNonNull(response.companyId(), "companyId");
        Objects.requireNonNull(response.storeId(), "storeId");
        if (response.maxWindows() < 1 || response.maxPda() < 0 || response.licenseVersion() < 1) {
            throw new LicenseValidationException("Los cupos o la version de licencia no son validos");
        }
        SpanishTaxId.validate(taxId(response));
        com.tpverp.backend.organization.StoreFiscalIdentity.code(
                required(response.storeCode(), "storeCode"));
        com.tpverp.backend.organization.StoreFiscalIdentity.timezone(
                required(response.timeZoneId(), "timeZoneId"));
        required(response.companyName(), "companyName");
        required(response.storeName(), "storeName");
        Objects.requireNonNull(response.companyAddress(), "companyAddress");
        Objects.requireNonNull(response.storeAddress(), "storeAddress");
    }

    private Map<String, Object> metadata(LicenseSaasLinkResponse response) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("source", "SAAS_LINK");
        metadata.put("saasCompanyId", response.companyId().toString());
        metadata.put("saasStoreId", response.storeId().toString());
        metadata.put("verifactuActivationDate", response.verifactuActivationDate().toString());
        metadata.put("verifactuPolicyVersion", response.verifactuPolicyVersion());
        metadata.put("saasLicenseVersion", response.licenseVersion());
        metadata.put("timeZoneId", response.timeZoneId());
        return metadata;
    }

    private String hash(LicenseSaasLinkResponse response) {
        try {
            String value = response.licenseReference()
                    + "|" + response.companyId()
                    + "|" + response.storeId()
                    + "|" + response.validUntil()
                    + "|" + response.licenseVersion()
                    + "|" + response.verifactuActivationDate()
                    + "|" + response.verifactuPolicyVersion()
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

    private void updateDefaultTax(
            UUID storeId, TaxRegime regime, CommercialProfile commercialProfile) {
        BigDecimal percentage = regime == TaxRegime.IGIC
                        && commercialProfile == CommercialProfile.MINORISTA
                ? new BigDecimal("0.00")
                : regime == TaxRegime.IGIC
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
