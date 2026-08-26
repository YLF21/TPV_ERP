package com.tpverp.backend.verifactu;

import com.tpverp.backend.licensing.LicenseRepository;
import java.time.ZoneId;
import java.time.LocalDate;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerifactuFirstSubmissionMarker {

    private final VerifactuConfigurationRepository configurations;
    private final LicenseRepository licenses;
    private final VerifactuActivationService activation;
    private final Clock clock;
    private FiscalRuntimeProperties runtime;

    @Autowired
    public VerifactuFirstSubmissionMarker(
            VerifactuConfigurationRepository configurations,
            LicenseRepository licenses,
            VerifactuActivationService activation,
            Clock clock) {
        this.configurations = configurations;
        this.licenses = licenses;
        this.activation = activation;
        this.clock = clock;
    }

    /** Compatibility constructor for focused embedders. */
    public VerifactuFirstSubmissionMarker(
            VerifactuConfigurationRepository configurations,
            LicenseRepository licenses,
            VerifactuActivationService activation) {
        this(configurations, licenses, activation, Clock.systemUTC());
    }

    @Autowired(required = false)
    void setRuntimeProperties(FiscalRuntimeProperties runtime) {
        this.runtime = runtime;
    }

    @Transactional
    public void mark(FiscalRecord record) {
        var configuration = configuration(record);
        var license = licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(
                        record.getStoreId(), record.getInstallationId())
                .orElseThrow(() -> new IllegalStateException(
                        "No existe una licencia activa para la tienda e instalacion"));
        var acknowledgedAt = clock.instant();
        var fiscalZone = ZoneId.of(record.getTimezone());
        var firstSubmissionWasMissing = configuration.getFirstSubmissionAt() == null;
        if (firstSubmissionWasMissing) {
            activation.markFirstSubmission(
                    configuration,
                    license.getTaxpayerType(),
                    license.getVerifactuActivationDate(),
                    acknowledgedAt,
                    fiscalZone);
        }
        var blockedUntilBefore = configuration.getVerifactuBlockedUntil();
        if (runtime != null && runtime.runtimeClass() == FiscalRuntimeClass.REAL
                && activation.isSifAdaptationRequired(
                        license.getTaxpayerType(), acknowledgedAt, fiscalZone)) {
            var localSubmissionDate = acknowledgedAt.atZone(fiscalZone).toLocalDate();
            configuration.lockVerifactuUntil(LocalDate.of(localSubmissionDate.getYear(), 12, 31));
        }
        if (firstSubmissionWasMissing
                || !java.util.Objects.equals(blockedUntilBefore,
                        configuration.getVerifactuBlockedUntil())) {
            configurations.save(configuration);
        }
    }
    // Bloquea la reversibilidad tras la primera remision aceptada por AEAT.

    private VerifactuConfiguration configuration(FiscalRecord record) {
        configurations.insertIfMissing(UUID.randomUUID(), record.getCompanyId());
        return configurations.findForUpdateByCompanyId(record.getCompanyId())
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo inicializar la configuracion VERI*FACTU"));
    }
}
