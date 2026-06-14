package com.tpverp.backend.verifactu;

import com.tpverp.backend.licensing.application.TaxpayerType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class VerifactuActivationService {

    private static final LocalDate COMPANY_DEADLINE = LocalDate.of(2027, 1, 1);
    private static final LocalDate SELF_EMPLOYED_DEADLINE = LocalDate.of(2027, 7, 1);

    // Indica si la fecha legal obliga a usar VERI*FACTU según el titular.
    public boolean isLegallyRequired(TaxpayerType type, Instant now) {
        var date = Objects.requireNonNull(now, "now")
                .atZone(ZoneOffset.UTC)
                .toLocalDate();
        return !date.isBefore(switch (Objects.requireNonNull(type, "type")) {
            case SOCIEDAD -> COMPANY_DEADLINE;
            case AUTONOMO -> SELF_EMPLOYED_DEADLINE;
        });
    }

    // Combina la activación voluntaria con la obligación legal automática.
    public boolean isActive(
            VerifactuConfiguration configuration,
            TaxpayerType type,
            Instant now) {
        return Objects.requireNonNull(configuration, "configuration").isVoluntarilyActive()
                || isLegallyRequired(type, now);
    }

    // Impide volver atrás tras la fecha legal o después de la primera remisión.
    public void deactivateVoluntarily(
            VerifactuConfiguration configuration,
            TaxpayerType type,
            Instant now) {
        if (isLegallyRequired(type, now)) {
            throw new IllegalStateException("La activación legal de VERI*FACTU es irreversible");
        }
        Objects.requireNonNull(configuration, "configuration").deactivateVoluntarily();
    }
}
