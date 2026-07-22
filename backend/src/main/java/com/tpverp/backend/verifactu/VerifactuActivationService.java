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
        return isAutomaticallyRequired(type, null, now, zoneId);
    }

    public boolean isAutomaticallyRequired(
            TaxpayerType type,
            LocalDate licensedActivationDate,
            Instant now,
            ZoneId zoneId) {
        return !Objects.requireNonNull(now, "now").isBefore(
                activationAt(type, licensedActivationDate, zoneId));
    }

    public Instant legalActivationInstant(TaxpayerType type, ZoneId zoneId) {
        return activationAt(type, null, zoneId);
    }
    // Expone el inicio legal efectivo para informar al administrador.

    // Combines voluntary activation with the automatic legal obligation.
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

    // Records the first submission applying voluntary or legal activation.
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
        var automaticActivationAt = activationAt(type, licensedActivationDate, zoneId);
        Objects.requireNonNull(configuration, "configuration").markFirstSubmission(
                submittedAt,
                submittedAt.isBefore(automaticActivationAt) ? null : automaticActivationAt);
    }

    // Prevents rollback after the legal date or after the first submission.
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
        if (isAutomaticallyRequired(type, licensedActivationDate, now, zoneId)) {
            throw new IllegalStateException("message.verifactu.legal_activation_irreversible");
        }
        Objects.requireNonNull(configuration, "configuration").deactivateVoluntarily();
    }

    public Instant activationInstant(
            TaxpayerType type,
            LocalDate licensedActivationDate,
            ZoneId zoneId) {
        return activationAt(type, licensedActivationDate, zoneId);
    }

    private static Instant activationAt(
            TaxpayerType type,
            LocalDate licensedActivationDate,
            ZoneId zoneId) {
        var deadline = licensedActivationDate != null
                ? licensedActivationDate
                : switch (Objects.requireNonNull(type, "type")) {
            case SOCIEDAD -> COMPANY_DEADLINE;
            case AUTONOMO -> SELF_EMPLOYED_DEADLINE;
        };
        return deadline.atStartOfDay(Objects.requireNonNull(zoneId, "zoneId")).toInstant();
    }
}
