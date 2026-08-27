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

    /**
     * Indicates whether the statutory SIF adaptation date has elapsed.
     * This does not select VERI*FACTU: NO VERI*FACTU remains a valid mode
     * after that date when its own requirements are met.
     */
    @Deprecated(forRemoval = false)
    public boolean isLegallyRequired(TaxpayerType type, Instant now, ZoneId zoneId) {
        return !Objects.requireNonNull(now, "now").isBefore(
                sifAdaptationDeadlineInstant(type, zoneId));
    }

    public boolean isSifAdaptationRequired(TaxpayerType type, Instant now, ZoneId zoneId) {
        return isLegallyRequired(type, now, zoneId);
    }

    public boolean isAutomaticallyRequired(
            TaxpayerType type,
            LocalDate licensedActivationDate,
            Instant now,
            ZoneId zoneId) {
        Objects.requireNonNull(type, "type");
        if (licensedActivationDate == null) {
            return false;
        }
        return !Objects.requireNonNull(now, "now").isBefore(
                activationAt(licensedActivationDate, zoneId));
    }

    @Deprecated(forRemoval = false)
    public Instant legalActivationInstant(TaxpayerType type, ZoneId zoneId) {
        return sifAdaptationDeadlineInstant(type, zoneId);
    }

    public Instant sifAdaptationDeadlineInstant(TaxpayerType type, ZoneId zoneId) {
        var deadline = switch (Objects.requireNonNull(type, "type")) {
            case SOCIEDAD -> COMPANY_DEADLINE;
            case AUTONOMO -> SELF_EMPLOYED_DEADLINE;
        };
        return deadline.atStartOfDay(Objects.requireNonNull(zoneId, "zoneId")).toInstant();
    }
    // Expone el inicio legal de adaptación del SIF, no una activación de modalidad.

    // Combines voluntary activation with the explicit licence policy.
    public boolean isActive(
            VerifactuConfiguration configuration,
            TaxpayerType type,
            Instant now,
            ZoneId zoneId) {
        return isActive(configuration, type, null, now, zoneId);
    }

    public boolean isActive(
            VerifactuConfiguration configuration,
            TaxpayerType type,
            LocalDate licensedActivationDate,
            Instant now,
            ZoneId zoneId) {
        return Objects.requireNonNull(configuration, "configuration").isVoluntarilyActive()
                || configuration.getFirstSubmissionAt() != null
                || isAutomaticallyRequired(type, licensedActivationDate, now, zoneId);
    }

    // Records the first submission applying voluntary or licensed activation.
    public void markFirstSubmission(
            VerifactuConfiguration configuration,
            TaxpayerType type,
            Instant submittedAt,
            ZoneId zoneId) {
        markFirstSubmission(configuration, type, null, submittedAt, zoneId);
    }

    public void markFirstSubmission(
            VerifactuConfiguration configuration,
            TaxpayerType type,
            LocalDate licensedActivationDate,
            Instant submittedAt,
            ZoneId zoneId) {
        Objects.requireNonNull(type, "type");
        var automaticActivationAt = licensedActivationDate == null
                ? null : activationAt(licensedActivationDate, zoneId);
        Objects.requireNonNull(configuration, "configuration").markFirstSubmission(
                submittedAt,
                automaticActivationAt != null && submittedAt.isBefore(automaticActivationAt)
                        ? null : automaticActivationAt);
    }

    // Prevents rollback after the licensed date or after the first submission.
    public void deactivateVoluntarily(
            VerifactuConfiguration configuration,
            TaxpayerType type,
            Instant now,
            ZoneId zoneId) {
        deactivateVoluntarily(configuration, type, null, now, zoneId);
    }

    public void deactivateVoluntarily(
            VerifactuConfiguration configuration,
            TaxpayerType type,
            LocalDate licensedActivationDate,
            Instant now,
            ZoneId zoneId) {
        Objects.requireNonNull(type, "type");
        if (isAutomaticallyRequired(type, licensedActivationDate, now, zoneId)) {
            throw new IllegalStateException("message.verifactu.license_activation_irreversible");
        }
        Objects.requireNonNull(configuration, "configuration").deactivateVoluntarily();
    }

    public Instant activationInstant(
            TaxpayerType type,
            LocalDate licensedActivationDate,
            ZoneId zoneId) {
        Objects.requireNonNull(type, "type");
        return activationAt(licensedActivationDate, zoneId);
    }

    private static Instant activationAt(LocalDate licensedActivationDate, ZoneId zoneId) {
        return Objects.requireNonNull(licensedActivationDate, "licensedActivationDate")
                .atStartOfDay(Objects.requireNonNull(zoneId, "zoneId")).toInstant();
    }
}
