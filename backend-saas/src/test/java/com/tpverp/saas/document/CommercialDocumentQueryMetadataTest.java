package com.tpverp.saas.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.saas.sync.SyncEventRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CommercialDocumentQueryMetadataTest {

    @Test
    void optionalLabelsAreSourceLabelsOnlyAndBlankLabelsRemainUnknown() {
        var metadata = parse(Map.of("usuarioNombre", "  Vendedor  ", "terminalOrigenNombre", " Caja 2 "));
        assertThat(metadata.userName()).isEqualTo("Vendedor");
        assertThat(metadata.terminalName()).isEqualTo("Caja 2");
        assertThat(parse(Map.of("usuarioNombre", "   ", "terminalOrigenNombre", "")).userName()).isNull();
        assertThat(parse(Map.of()).terminalName()).isNull();
    }

    @Test
    void absentAndExplicitNullMetadataRemainUnknownRatherThanFalseOrComplete() {
        Map<String, Object> data = new LinkedHashMap<>();
        CommercialDocumentQueryMetadata absent = parse(data);
        for (String field : List.of("anuladoPor", "anuladoEn", "fechaVencimiento", "settledByOrigin", "relaciones")) {
            data.put(field, null);
        }
        assertThat(parse(data)).isEqualTo(absent);
        assertThat(absent.cancelledByLocalId()).isNull();
        assertThat(absent.sourceCancelledAt()).isNull();
        assertThat(absent.dueDate()).isNull();
        assertThat(absent.settledByOrigin()).isNull();
        assertThat(absent.relationshipsComplete()).isFalse();
        assertThat(absent.relationships()).isEmpty();
    }

    @Test
    void emptyRelationsAreExplicitlyCompleteAndFalseIsNotUnknown() {
        CommercialDocumentQueryMetadata metadata = parse(Map.of("relaciones", List.of(), "settledByOrigin", false));
        assertThat(metadata.relationshipsComplete()).isTrue();
        assertThat(metadata.relationships()).isEmpty();
        assertThat(metadata.settledByOrigin()).isFalse();
    }

    @Test
    void copiesAttributionDatesAndEverySupportedRelationWithoutResolvingOriginIds() {
        UUID actor = UUID.randomUUID();
        UUID origin = UUID.randomUUID();
        var relations = new ArrayList<Map<String, Object>>();
        for (String type : List.of("FACTURA_DE", "RECTIFICA", "COMPENSA")) {
            relations.add(Map.of("tipo", type, "origenId", origin.toString().toUpperCase(Locale.ROOT)));
        }
        CommercialDocumentQueryMetadata metadata = parse(Map.of(
                "anuladoPor", actor.toString(), "anuladoEn", "2026-09-10T12:45:01.123456Z",
                "fechaVencimiento", "2028-02-29", "settledByOrigin", true, "relaciones", relations));

        assertThat(metadata.cancelledByLocalId()).isEqualTo(actor);
        assertThat(metadata.sourceCancelledAt()).isEqualTo(Instant.parse("2026-09-10T12:45:01.123456Z"));
        assertThat(metadata.dueDate()).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(metadata.settledByOrigin()).isTrue();
        assertThat(metadata.relationshipsComplete()).isTrue();
        assertThat(metadata.relationships()).containsExactly(
                new CommercialDocumentQueryMetadata.Relation("FACTURA_DE", origin),
                new CommercialDocumentQueryMetadata.Relation("RECTIFICA", origin),
                new CommercialDocumentQueryMetadata.Relation("COMPENSA", origin));
        relations.clear();
        assertThat(metadata.relationships()).hasSize(3);
        assertThatThrownBy(() -> metadata.relationships().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @MethodSource("malformedFields")
    void rejectsMalformedOptionalFieldsWithASanitizedFieldName(String field, Object value) {
        assertInvalid(CommercialDocumentSnapshotTest.request(Map.of(field, value)), field);
    }

    static Stream<Arguments> malformedFields() {
        return Stream.of(
                Arguments.of("anuladoPor", UUID.randomUUID()), Arguments.of("anuladoPor", "1-1-1-1-1"),
                Arguments.of("anuladoPor", ""), Arguments.of("anuladoPor", true),
                Arguments.of("anuladoEn", 123), Arguments.of("anuladoEn", "2026-09-10"),
                Arguments.of("anuladoEn", "0000-12-31T23:59:59Z"),
                Arguments.of("anuladoEn", "+10000-01-01T00:00:00Z"),
                Arguments.of("anuladoEn", " 2026-09-10T12:00:00Z"),
                Arguments.of("fechaVencimiento", LocalDate.of(2026, 9, 10)),
                Arguments.of("fechaVencimiento", "2026-02-29"),
                Arguments.of("fechaVencimiento", "2026-9-10"),
                Arguments.of("fechaVencimiento", "0000-01-01"),
                Arguments.of("fechaVencimiento", "+10000-01-01"),
                Arguments.of("settledByOrigin", "false"), Arguments.of("settledByOrigin", 0),
                Arguments.of("settledByOrigin", List.of()),
                Arguments.of("usuarioNombre", 12), Arguments.of("usuarioNombre", "x".repeat(256)),
                Arguments.of("terminalOrigenNombre", "Caja\n1"),
                Arguments.of("relaciones", "[]"), Arguments.of("relaciones", Map.of()),
                Arguments.of("relaciones", List.of("FACTURA_DE")),
                Arguments.of("relaciones", Arrays.asList((Object) null)));
    }

    @ParameterizedTest
    @MethodSource("malformedRelations")
    void rejectsMalformedRelationFields(Map<String, Object> relation, String field) {
        assertInvalid(CommercialDocumentSnapshotTest.request(Map.of("relaciones", List.of(relation))), field);
    }

    static Stream<Arguments> malformedRelations() {
        String origin = UUID.randomUUID().toString();
        return Stream.of(
                Arguments.of(Map.of("origenId", origin), "relaciones.tipo"),
                Arguments.of(Map.of("tipo", "OTRA", "origenId", origin), "relaciones.tipo"),
                Arguments.of(Map.of("tipo", 1, "origenId", origin), "relaciones.tipo"),
                Arguments.of(Map.of("tipo", " FACTURA_DE", "origenId", origin), "relaciones.tipo"),
                Arguments.of(Map.of("tipo", "FACTURA_DE"), "relaciones.origenId"),
                Arguments.of(Map.of("tipo", "FACTURA_DE", "origenId", 1), "relaciones.origenId"),
                Arguments.of(Map.of("tipo", "FACTURA_DE", "origenId", "1-1-1-1-1"), "relaciones.origenId"));
    }

    @Test
    void rejectsSelfRelationsAndCanonicalUuidDuplicatesInsteadOfSilentlyDiscardingThem() {
        var data = new LinkedHashMap<String, Object>();
        SyncEventRequest request = CommercialDocumentSnapshotTest.request(data);
        data.put("relaciones", List.of(Map.of("tipo", "FACTURA_DE", "origenId", request.entityId().toString())));
        assertInvalid(request, "relaciones.origenId");

        String origin = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        data.put("relaciones", List.of(
                Map.of("tipo", "RECTIFICA", "origenId", origin),
                Map.of("tipo", "RECTIFICA", "origenId", origin.toUpperCase(Locale.ROOT))));
        assertInvalid(request, "relaciones");
    }

    private static CommercialDocumentQueryMetadata parse(Map<String, Object> data) {
        return CommercialDocumentQueryMetadata.parse(CommercialDocumentSnapshotTest.request(data));
    }

    private static void assertInvalid(SyncEventRequest request, String field) {
        assertThatThrownBy(() -> CommercialDocumentQueryMetadata.parse(request))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getReason()).isEqualTo("Campo documental invalido: " + field);
                });
    }
}
