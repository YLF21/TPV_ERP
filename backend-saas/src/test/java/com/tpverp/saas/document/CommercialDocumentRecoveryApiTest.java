package com.tpverp.saas.document;

import static com.tpverp.saas.document.CommercialDocumentRecoveryApi.*;
import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class CommercialDocumentRecoveryApiTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID company = UUID.randomUUID();
    private final UUID store = UUID.randomUUID();
    private final UUID document = UUID.randomUUID();
    private final UUID event = UUID.randomUUID();

    @Test void acceptsPreflightAndExactIntegralRevisionIncludingLongMaximum() {
        Request preflight = mapper.readValue(json(""), Request.class);
        assertThat(preflight.documents()).containsExactly(new Document(document, null, null));
        assertThat(mapper.readValue(json(",\"eventId\":\"" + event + "\",\"sourceRevision\":0"), Request.class)
                .documents().getFirst().sourceRevision()).isZero();
        assertThat(mapper.readValue(json(",\"eventId\":\"" + event + "\",\"sourceRevision\":9223372036854775807"), Request.class)
                .documents().getFirst().sourceRevision()).isEqualTo(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "1e0", "1.0000000000000001", "\"1\"", "true", "{}", "[]", "-1", "9223372036854775808"})
    void rejectsRevisionCoercionRoundingNegativesAndOverflow(String revision) {
        assertThatThrownBy(() -> mapper.readValue(json(",\"eventId\":\"" + event + "\",\"sourceRevision\":" + revision), Request.class))
                .isInstanceOf(RuntimeException.class);
    }

    @Test void requiresEventAndRevisionTogetherAndRejectsMissingContextOrDocument() {
        assertThatThrownBy(() -> mapper.readValue(json(",\"eventId\":\"" + event + "\""), Request.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> mapper.readValue(json(",\"sourceRevision\":1"), Request.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> mapper.readValue(json(",\"eventId\":\"" + event + "\",\"sourceRevision\":null"), Request.class))
                .isInstanceOf(RuntimeException.class);
        invalid(() -> new Document(null, null, null));
        invalid(() -> new Request(null, store, List.of(new Document(document, null, null))));
        invalid(() -> new Request(company, null, List.of(new Document(document, null, null))));
        invalid(() -> new Request(company, store, null));
    }

    @Test void boundsBatchRejectsDuplicatesAndDefensivelyCopiesInput() {
        var documents = new ArrayList<>(IntStream.range(0, 100)
                .mapToObj(index -> new Document(UUID.randomUUID(), null, null)).toList());
        Request request = new Request(company, store, documents);
        documents.clear();
        assertThat(request.documents()).hasSize(100);
        invalid(() -> new Request(company, store, documents));
        invalid(() -> new Request(company, store, IntStream.range(0, 101)
                .mapToObj(index -> new Document(UUID.randomUUID(), null, null)).toList()));
        invalid(() -> new Request(company, store, List.of(new Document(document, null, null), new Document(document, event, 1L))));
        documents.add(null);
        invalid(() -> new Request(company, store, documents));
    }

    private String json(String optional) {
        return "{\"companyId\":\"" + company + "\",\"storeId\":\"" + store
                + "\",\"documents\":[{\"documentId\":\"" + document + "\"" + optional + "}]}";
    }
    private static void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
