package com.tpverp.saas.mail;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class Communications {
    private Communications() {}
    enum Mode { ALL, SELECTED }
    record Draft(@NotNull @Size(max=200) String subject, @NotNull @Size(max=100000) String html,
                 @NotNull Mode audienceMode, @NotNull @Size(max=10000) List<@NotNull UUID> recipientIds,
                 @PositiveOrZero Long version, UUID senderAccountId) {}
    record Revision(@NotNull @PositiveOrZero Long version) {}
    record Confirmation(@NotNull @PositiveOrZero Long version, @NotNull UUID reviewId, @Positive long recipientCount) {}
    record Retry(@NotNull @PositiveOrZero Integer attempts, boolean confirmUncertain) {}
    record Html(@NotNull @Size(max=100000) String html) {}
    record TestMail(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max=254) String recipient) {}
    record Body(String html, String text) {}
    record Page<T>(List<T> items, long total) {}
    record Counts(long pending, long sending, long sent, long failed, long uncertain, long cancelled) {}
    record Summary(UUID id, String subject, String status, long version, Instant createdAt, Instant updatedAt,
                   String createdBy, Counts counts) {}
    record Detail(@JsonUnwrapped Summary summary, String html, String text, Mode audienceMode, List<UUID> recipientIds, UUID senderAccountId,
                  String fromAddress, String fromName, String replyTo) {}
    record AudienceEntry(UUID id, String label, String email, boolean eligible, String reason,
                         List<String> companyNames, List<String> sources) {}
    record Review(UUID reviewId, long version, String subject, String html, String text,
                  long recipientCount, long omittedCount, long duplicateCount, boolean smtpReady,
                  String fromAddress, String fromName, String replyTo, long smtpVersion, UUID senderAccountId) {}
    record Recipient(UUID id, String label, String email, String status, int attempts, String lastError, Instant sentAt) {}

    static long offset(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw invalid();
        return (long) page * size;
    }
    static ResponseStatusException invalid() {
        return SmtpSettings.problem(HttpStatus.BAD_REQUEST, "COMMUNICATION_INVALID", "Datos de comunicación no válidos");
    }
    static ResponseStatusException missing() {
        return SmtpSettings.problem(HttpStatus.NOT_FOUND, "COMMUNICATION_NOT_FOUND", "Comunicación o destinatario no encontrado");
    }
    static ResponseStatusException changed() {
        return SmtpSettings.conflict("COMMUNICATION_VERSION_CONFLICT", "La comunicación ha cambiado");
    }
    static ResponseStatusException staleReview() {
        return SmtpSettings.conflict("COMMUNICATION_REVIEW_CHANGED", "Vuelva a revisar los destinatarios");
    }
    static ResponseStatusException wrongState() {
        return SmtpSettings.conflict("COMMUNICATION_STATE_CONFLICT", "La operación no corresponde al estado actual");
    }
    static void requireContent(String subject, String text) {
        if (subject.isBlank() || text.isBlank())
            throw SmtpSettings.conflict("COMMUNICATION_EMPTY", "Introduzca un asunto y un mensaje");
    }
}
