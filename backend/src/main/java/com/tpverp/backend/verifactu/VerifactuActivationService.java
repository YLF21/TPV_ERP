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

    // Indicates whether the legal date requires VERI*FACTU for the taxpayer type.
    public boolean isLegallyRequired(TaxpayerType type, Instant now, ZoneId zoneId) {
        return !Objects.requireNonNull(now, "now").isBefore(
                legalActivationAt(type, zoneId));
    }

    public Instant legalActivationInstant(TaxpayerType type, ZoneId zoneId) {
        return legalActivationAt(type, zoneId);
    }
    // Expone el inicio legal efectivo para informar al administrador.

    // Combines voluntary activation with the automatic legal obligation.
    public boolean isActive(
            VerifactuConfiguration configuration,
            TaxpayerType type,
            Instant now,
            ZoneId zoneId) {
        return Objects.requireNonNull(configuration, "configuration").isVoluntarilyActive()
                || isLegallyRequired(type, now, zoneId);
    }

    // Records the first submission applying voluntary or legal activation.
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

    // Prevents rollback after the legal date or after the first submission.
    public void deactivateVoluntarily(
            VerifactuConfiguration configuration,
            TaxpayerType type,
            Instant now,
            ZoneId zoneId) {
        if (isLegallyRequired(type, now, zoneId)) {
            throw new IllegalStateException("message.verifactu.legal_activation_irreversible");
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
