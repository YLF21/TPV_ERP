package com.tpverp.backend.verifactu;

import com.tpverp.backend.licensing.application.TaxpayerType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class VerifactuActivationService {

    private static final LocalDate COMPANY_DEADLINE = LocalDate.of(2027, 1, 1);
    private static final LocalDate SELF_EMPLOYED_DEADLINE = LocalDate.of(2027, 7, 1);

    // Indica si la fecha legal obliga a usar VERI*FACTU según el titular.
    public boolean isLegallyRequired(TaxpayerType type, Instant now, ZoneId zoneId) {
        return !Objects.requireNonNull(now, "now").isBefore(
                legalActivationAt(type, zoneId));
    }

    // Combina la activación voluntaria con la obligación legal automática.
    public boolean isActive(
            VerifactuConfiguration configuration,
            TaxpayerType type,
            Instant now,
            ZoneId zoneId) {
        return Objects.requireNonNull(configuration, "configuration").isVoluntarilyActive()
                || isLegallyRequired(type, now, zoneId);
    }

    // Registra la primera remisión aplicando la activación voluntaria o legal vigente.
    public void markFirstSubmission(
            VerifactuConfiguration configuration,
            TaxpayerType type,
            Instant submittedAt,
            ZoneId zoneId) {
        var legalActivationAt = legalActivationAt(type, zoneId);
        Objects.requireNonNull(configuration, "configuration").markFirstSubmission(
                submittedAt,
                submittedAt.isBefore(legalActivationAt) ? null : legalActivationAt);
    }

    // Impide volver atrás tras la fecha legal o después de la primera remisión.
    public void deactivateVoluntarily(
            VerifactuConfiguration configuration,
            TaxpayerType type,
            Instant now,
            ZoneId zoneId) {
        if (isLegallyRequired(type, now, zoneId)) {
            throw new IllegalStateException("La activación legal de VERI*FACTU es irreversible");
        }
        Objects.requireNonNull(configuration, "configuration").deactivateVoluntarily();
    }

    private static Instant legalActivationAt(TaxpayerType type, ZoneId zoneId) {
        var deadline = switch (Objects.requireNonNull(type, "type")) {
            case SOCIEDAD -> COMPANY_DEADLINE;
            case AUTONOMO -> SELF_EMPLOYED_DEADLINE;
        };
        return deadline.atStartOfDay(Objects.requireNonNull(zoneId, "zoneId")).toInstant();
    }
}
