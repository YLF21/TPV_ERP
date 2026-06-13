package com.tpverp.backend.licensing.application;

import com.tpverp.backend.installation.Instalacion;
import com.tpverp.backend.installation.InstalacionRepository;
import com.tpverp.backend.licensing.Licencia;
import com.tpverp.backend.licensing.LicenciaRepository;
import com.tpverp.backend.licensing.ResultadoImportacion;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.organization.TiendaRepository;
import com.tpverp.backend.shared.crypto.InstallationIdentityStore;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.audit.ResultadoAuditoria;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.UUID;

public class LicenseService {

    private final InstalacionRepository instalacionRepository;
    private final TiendaRepository tiendaRepository;
    private final LicenciaRepository licenciaRepository;
    private final InstallationIdentityStore identityStore;
    private final TrustedIssuerKeyProvider issuerKeyProvider;
    private final LicenseEnvelopeDecoder decoder;
    private final Clock clock;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    public LicenseService(
            InstalacionRepository instalacionRepository,
            TiendaRepository tiendaRepository,
            LicenciaRepository licenciaRepository,
            InstallationIdentityStore identityStore,
            TrustedIssuerKeyProvider issuerKeyProvider,
            LicenseEnvelopeDecoder decoder,
            Clock clock,
            AuditService auditService,
            JdbcTemplate jdbc) {
        this.instalacionRepository = instalacionRepository;
        this.tiendaRepository = tiendaRepository;
        this.licenciaRepository = licenciaRepository;
        this.identityStore = identityStore;
        this.issuerKeyProvider = issuerKeyProvider;
        this.decoder = decoder;
        this.clock = clock;
        this.auditService = auditService;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public LicensePreview preview(String licenseFile) {
        Instalacion installation = currentInstallation();
        var identity = identityStore.loadOrCreate();
        return decoder.decode(
                licenseFile,
                installation.getId().toString(),
                installation.getReferencia(),
                identity.privateKey(),
                issuerKeyProvider.load());
    }

    @Transactional
    public LicensePreview activate(String licenseFile, String confirmationHash) {
        LicensePreview preview = preview(licenseFile);
        if (!preview.fileHash().equalsIgnoreCase(required(confirmationHash, "confirmationHash"))) {
            throw new LicenseValidationException("La confirmacion no corresponde a esta licencia");
        }
        if (licenciaRepository.findByReferencia(preview.reference()).isPresent()) {
            throw new LicenseValidationException("Esta licencia ya fue importada");
        }

        Instalacion installation = currentInstallation();
        Tienda store = currentStore();
        licenciaRepository.findByTiendaIdAndInstalacionIdAndActivaTrue(
                        store.getId(), installation.getId())
                .ifPresent(Licencia::desactivar);
        licenciaRepository.save(new Licencia(
                store,
                installation,
                preview.reference(),
                preview.validFrom(),
                preview.validUntil(),
                preview.maxWindows(),
                preview.maxPda(),
                preview.impuestos(),
                licenseFile,
                preview.fileHash(),
                2,
                Instant.now(clock),
                Map.of("issuerKeyId", preview.issuerKeyId()),
                ResultadoImportacion.ACEPTADA,
                null,
                true));
        updateDefaultTax(store.getId(), preview.impuestos());
        auditService.record(
                "LICENSE_ACTIVATED",
                ResultadoAuditoria.EXITO,
                Map.of("reference", preview.reference(), "hash", preview.fileHash()));
        return preview;
    }

    @Transactional(readOnly = true)
    public List<LicenseHistoryItem> history() {
        return licenciaRepository.findByTiendaIdOrderByValidaDesdeDesc(currentStore().getId())
                .stream()
                .map(LicenseHistoryItem::from)
                .toList();
    }

    private Instalacion currentInstallation() {
        return instalacionRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("La instalacion no esta inicializada"));
    }

    private Tienda currentStore() {
        return tiendaRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("La tienda no esta inicializada"));
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new LicenseValidationException("Falta " + field);
        }
        return value.trim();
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

    public record LicenseHistoryItem(
            String reference,
            Instant validFrom,
            Instant validUntil,
            int maxWindows,
            int maxPda,
            TaxRegime impuestos,
            boolean active) {

        static LicenseHistoryItem from(Licencia license) {
            return new LicenseHistoryItem(
                    license.getReferencia(),
                    license.getValidaDesde(),
                    license.getValidaHasta(),
                    license.getMaxWindows(),
                    license.getMaxPda(),
                    license.getRegimenImpuesto(),
                    license.isActiva());
        }
    }
}
