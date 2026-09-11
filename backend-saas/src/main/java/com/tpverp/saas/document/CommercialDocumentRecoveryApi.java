package com.tpverp.saas.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Technical receipt inspection, not a resend command or a company-wide document query. */
public final class CommercialDocumentRecoveryApi {
    public static final int MAX_DOCUMENTS = 100;
    private CommercialDocumentRecoveryApi() { }

    public record Request(@NotNull UUID companyId, @NotNull UUID storeId,
            @NotNull @Size(min = 1, max = MAX_DOCUMENTS) List<@NotNull @Valid Document> documents) {
        public Request {
            if (companyId == null || storeId == null || documents == null || documents.isEmpty()
                    || documents.size() > MAX_DOCUMENTS || documents.stream().anyMatch(java.util.Objects::isNull)
                    || new HashSet<>(documents.stream().map(Document::documentId).toList()).size() != documents.size()) {
                throw invalid();
            }
            documents = List.copyOf(documents);
        }
    }

    public record Document(@NotNull UUID documentId, UUID eventId,
            @tools.jackson.databind.annotation.JsonDeserialize(using = RevisionDeserializer.class) Long sourceRevision) {
        public Document {
            if (documentId == null || (eventId == null) != (sourceRevision == null)
                    || sourceRevision != null && sourceRevision < 0) throw invalid();
        }
    }

    public enum Status { MISSING, PROJECTED, OTHER_INSTALLATION }
    public enum EventStatus { NOT_REQUESTED, MISSING, RECEIVED, PROJECTED, IGNORED, ERROR }

    public record Response(UUID companyId, UUID storeId, UUID installationId, int schemaVersion,
            List<DocumentStatus> documents) {
        public Response { documents = List.copyOf(documents); }
    }

    public record DocumentStatus(UUID documentId, Status status, Long currentRevision, UUID currentEventId,
            EventStatus requestedEventStatus, boolean requestedRevisionRecorded, Boolean customerLinked,
            String total, String currency) { }

    public static final class RevisionDeserializer extends tools.jackson.databind.deser.std.StdDeserializer<Long> {
        public RevisionDeserializer() { super(Long.class); }
        @Override public Long deserialize(tools.jackson.core.JsonParser parser,
                tools.jackson.databind.DeserializationContext context) {
            if (!parser.hasToken(tools.jackson.core.JsonToken.VALUE_NUMBER_INT)) {
                return (Long) context.handleUnexpectedToken(Long.class, parser);
            }
            return parser.getLongValue();
        }
    }

    static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Consulta de recuperacion documental no valida");
    }
}
