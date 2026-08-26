package com.tpverp.backend.verifactu;

import com.tpverp.backend.licensing.LicenseRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import com.tpverp.backend.licensing.License;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FiscalMandatoryActivationScheduler {

    private static final Logger log = LoggerFactory.getLogger(FiscalMandatoryActivationScheduler.class);

    private final LicenseRepository licenses;
    private final FiscalMandatoryActivationService activation;
    private final Clock clock;

    public FiscalMandatoryActivationScheduler(
            LicenseRepository licenses,
            FiscalMandatoryActivationService activation,
            Clock clock) {
        this.licenses = licenses;
        this.activation = activation;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tpv.verifactu.mandatory-activation-delay-ms:60000}")
    public void tick() {
        applyDue(clock.instant());
    }

    public int applyDue(Instant now) {
        int applied = 0;
        List<License> activeLicenses;
        try {
            activeLicenses = licenses.findByActivaTrueOrderByValidaDesdeDesc();
        } catch (RuntimeException exception) {
            log.error("No se pudieron consultar las licencias para la activacion VERI*FACTU",
                    exception);
            return 0;
        }
        for (var license : activeLicenses) {
            try {
                if (activation.activateIfDue(license.getId(), now)) {
                    applied++;
                }
            } catch (RuntimeException exception) {
                log.error("No se pudo aplicar la activacion VERI*FACTU de la licencia {}",
                        license.getReferencia(), exception);
            }
        }
        return applied;
    }
}
