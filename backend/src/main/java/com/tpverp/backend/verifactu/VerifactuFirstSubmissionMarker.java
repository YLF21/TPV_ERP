package com.tpverp.backend.verifactu;

import com.tpverp.backend.licensing.LicenseRepository;
import java.time.ZoneId;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerifactuFirstSubmissionMarker {

    private final VerifactuConfigurationRepository configurations;
    private final LicenseRepository licenses;
    private final VerifactuActivationService activation;
    private FiscalRuntimeProperties runtime;

    public VerifactuFirstSubmissionMarker(
            VerifactuConfigurationRepository configurations,
            LicenseRepository licenses,
            VerifactuActivationService activation) {
        this.configurations = configurations;
        this.licenses = licenses;
        this.activation = activation;
    }

    @Autowired(required = false)
    void setRuntimeProperties(FiscalRuntimeProperties runtime) {
        this.runtime = runtime;
    }

    @Transactional
    public void mark(FiscalRecord record) {
        var configuration = configuration(record);
        if (configuration.getFirstSubmissionAt() != null) {
            return;
        }
        var license = licenses.findByTiendaIdAndInstalacionIdAndActivaTrue(
                        record.getStoreId(), record.getInstallationId())
                .orElseThrow(() -> new IllegalStateException(
                        "No existe una licencia activa para la tienda e instalacion"));
        activation.markFirstSubmission(
                configuration,
                license.getTaxpayerType(),
                license.getVerifactuActivationDate(),
                record.getGeneratedAt(),
                ZoneId.of(record.getTimezone()));
        if (runtime != null && runtime.runtimeClass() == FiscalRuntimeClass.REAL) {
            var localSubmissionDate = record.getGeneratedAt()
                    .atZone(ZoneId.of(record.getTimezone())).toLocalDate();
            configuration.lockVerifactuUntil(localSubmissionDate.plusYears(1));
        }
        configurations.save(configuration);
    }
    // Bloquea la reversibilidad tras la primera remision aceptada por AEAT.

    private VerifactuConfiguration configuration(FiscalRecord record) {
        configurations.insertIfMissing(UUID.randomUUID(), record.getCompanyId());
        return configurations.findForUpdateByCompanyId(record.getCompanyId())
                .orElseThrow(() -> new IllegalStateException(
                        "No se pudo inicializar la configuracion VERI*FACTU"));
    }
}
