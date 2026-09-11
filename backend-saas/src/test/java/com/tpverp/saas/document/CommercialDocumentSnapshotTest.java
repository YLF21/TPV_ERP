package com.tpverp.saas.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.saas.sync.SyncEventRequest;
import com.tpverp.saas.sync.SyncOperation;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CommercialDocumentSnapshotTest {

    @Test
    void readsSignedHistoricalAmountsAndExplicitLocalAttributionWithoutDerivingOtherData() {
        Map<String, Object> payload = payload();
        UUID customer = UUID.randomUUID();
        UUID createdBy = UUID.randomUUID();
        UUID confirmedBy = UUID.randomUUID();
        UUID originTerminal = UUID.randomUUID();
        payload.put("tipo", "RECTIFICATIVA_VENTA");
        payload.put("fecha", "2024-02-29");
        payload.put("subtotal", "-10.00");
        payload.put("impuestos", new BigDecimal("-0.70"));
        payload.put("total", "-10.70");
        payload.put("clienteId", customer.toString());
        payload.put("creadoPor", createdBy.toString());
        payload.put("confirmadoPor", confirmedBy.toString());
        payload.put("terminalOrigenId", originTerminal.toString());
        payload.put("creadoEn", "2024-02-29T22:30:00.123456Z");
        payload.put("confirmadoEn", "2024-02-29T22:31:00Z");

        CommercialDocumentSnapshot snapshot = CommercialDocumentSnapshot.parse(request(payload));

        assertThat(snapshot.type()).isEqualTo("RECTIFICATIVA_VENTA");
        assertThat(snapshot.businessDate()).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(snapshot.subtotal()).isEqualTo(new BigDecimal("-10.00"));
        assertThat(snapshot.taxTotal()).isEqualTo(new BigDecimal("-0.70"));
        assertThat(snapshot.total()).isEqualTo(new BigDecimal("-10.70"));
        assertThat(snapshot.customerLocalId()).isEqualTo(customer);
        assertThat(snapshot.createdByLocalId()).isEqualTo(createdBy);
        assertThat(snapshot.confirmedByLocalId()).isEqualTo(confirmedBy);
        assertThat(snapshot.originTerminalLocalId()).isEqualTo(originTerminal);
        assertThat(snapshot.sourceCreatedAt()).isEqualTo(Instant.parse("2024-02-29T22:30:00.123456Z"));
        assertThat(snapshot.sourceConfirmedAt()).isEqualTo(Instant.parse("2024-02-29T22:31:00Z"));
    }

    @Test
    void acceptsAnonymousAndMissingHistoricalAttributionWithoutUsingRequestTerminal() {
        Map<String, Object> payload = payload();
        payload.put("clienteId", null);
        payload.put("confirmadoPor", null);

        CommercialDocumentSnapshot snapshot = CommercialDocumentSnapshot.parse(request(payload));

        assertThat(snapshot.customerLocalId()).isNull();
        assertThat(snapshot.createdByLocalId()).isNull();
        assertThat(snapshot.confirmedByLocalId()).isNull();
        assertThat(snapshot.originTerminalLocalId()).isNull();
        assertThat(snapshot.sourceCreatedAt()).isNull();
        assertThat(snapshot.sourceConfirmedAt()).isNull();
    }

    @Test
    void acceptsZeroAndMaximumRevisionWithoutUsingSchemaAsEntityVersion() {
        Map<String, Object> payload = payload();
        payload.put("sourceRevision", 0);
        assertThat(CommercialDocumentSnapshot.parse(request(payload)).sourceRevision()).isZero();
        payload.put("sourceRevision", Long.MAX_VALUE);
        assertThat(CommercialDocumentSnapshot.parse(request(payload)).sourceRevision()).isEqualTo(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TICKET", "ALBARAN_VENTA", "FACTURA_VENTA", "RECTIFICATIVA_VENTA"})
    void acceptsOnlyTheCurrentCommercialDocumentTypes(String type) {
        Map<String, Object> payload = payload();
        payload.put("tipo", type);
        assertThat(CommercialDocumentSnapshot.parse(request(payload)).type()).isEqualTo(type);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMADO", "PENDIENTE", "PARCIAL", "PAGADO"})
    void acceptsNonCancelledCommittedStates(String status) {
        Map<String, Object> payload = payload();
        payload.put("estado", status);
        assertThat(CommercialDocumentSnapshot.parse(request(payload)).status()).isEqualTo(status);
    }

    @Test
    void requiresCancellationOperationAndStateTogether() {
        Map<String, Object> payload = payload();
        assertInvalid(request(payload, SyncOperation.ANULAR), "estado");
        payload.put("estado", "ANULADO");
        assertInvalid(request(payload), "estado");
        assertInvalid(request(payload, SyncOperation.ACTUALIZAR), "estado");
        assertThat(CommercialDocumentSnapshot.parse(request(payload, SyncOperation.ANULAR)).status())
                .isEqualTo("ANULADO");
    }

    @Test
    void acceptsMaximumPrecisionAndExactAdditionalZeroesWithoutRounding() {
        Map<String, Object> payload = payload();
        payload.put("subtotal", "99999999999999999.99");
        payload.put("impuestos", 0);
        payload.put("total", "-99999999999999999.990");
        payload.put("numero", "N".repeat(32));

        CommercialDocumentSnapshot snapshot = CommercialDocumentSnapshot.parse(request(payload));

        assertThat(snapshot.subtotal()).isEqualTo(new BigDecimal("99999999999999999.99"));
        assertThat(snapshot.taxTotal()).isEqualTo(new BigDecimal("0.00"));
        assertThat(snapshot.total()).isEqualTo(new BigDecimal("-99999999999999999.99"));
        assertThat(snapshot.number()).hasSize(32);
    }

    @Test
    void rejectsBinaryFloatingPointBeforeItCanHideLostMoneyOrRevisionPrecision() {
        Map<String, Object> payload = payload();
        payload.put("total", 9007199254740993.01d);
        assertInvalid(request(payload), "total");
        payload.put("total", "9007199254740993.01");
        assertThat(CommercialDocumentSnapshot.parse(request(payload)).total())
                .isEqualTo(new BigDecimal("9007199254740993.01"));
        payload.put("sourceRevision", 9007199254740993d);
        assertInvalid(request(payload), "sourceRevision");
        payload.put("sourceRevision", new BigInteger("9007199254740993"));
        assertThat(CommercialDocumentSnapshot.parse(request(payload)).sourceRevision())
                .isEqualTo(9007199254740993L);
    }

    @ParameterizedTest
    @MethodSource("invalidFields")
    void rejectsMalformedFieldsWithOnlyASanitizedFieldName(String field, Object value) {
        Map<String, Object> payload = payload();
        payload.put(field, value);
        assertInvalid(request(payload), field);
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("schemaVersion", null),
                Arguments.of("schemaVersion", 1),
                Arguments.of("schemaVersion", 3),
                Arguments.of("schemaVersion", "2"),
                Arguments.of("schemaVersion", new BigDecimal("2.1")),
                Arguments.of("schemaVersion", true),
                Arguments.of("schemaVersion", 2.0d),
                Arguments.of("schemaVersion", Double.NaN),
                Arguments.of("sourceRevision", null),
                Arguments.of("sourceRevision", -1),
                Arguments.of("sourceRevision", "1"),
                Arguments.of("sourceRevision", new BigDecimal("0.01")),
                Arguments.of("sourceRevision", 1.0d),
                Arguments.of("sourceRevision", 1.0f),
                Arguments.of("sourceRevision", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)),
                Arguments.of("sourceRevision", Double.POSITIVE_INFINITY),
                Arguments.of("tipo", "FACTURA_COMPRA"),
                Arguments.of("tipo", "DESCONOCIDO"),
                Arguments.of("tipo", "ticket"),
                Arguments.of("estado", "BORRADOR"),
                Arguments.of("estado", "CONFIRMADA"),
                Arguments.of("numero", null),
                Arguments.of("numero", ""),
                Arguments.of("numero", " "),
                Arguments.of("numero", " T1"),
                Arguments.of("numero", "T\n1"),
                Arguments.of("numero", "N".repeat(33)),
                Arguments.of("numero", 123),
                Arguments.of("fecha", "2025-02-29"),
                Arguments.of("fecha", "2026-04-31"),
                Arguments.of("fecha", "2026-13-01"),
                Arguments.of("fecha", "2026-9-10"),
                Arguments.of("fecha", "0000-01-01"),
                Arguments.of("fecha", "2026-09-10T00:00:00Z"),
                Arguments.of("fecha", LocalDate.of(2026, 9, 10)),
                Arguments.of("moneda", "eur"),
                Arguments.of("moneda", " EU"),
                Arguments.of("moneda", "EURO"),
                Arguments.of("moneda", "12A"),
                Arguments.of("subtotal", "0.001"),
                Arguments.of("subtotal", 0.01d),
                Arguments.of("impuestos", 0.01f),
                Arguments.of("impuestos", "-0.001"),
                Arguments.of("total", "100000000000000000.00"),
                Arguments.of("total", "-100000000000000000.00"),
                Arguments.of("total", "10,70"),
                Arguments.of("total", "1e100000000"),
                Arguments.of("total", Double.NaN),
                Arguments.of("total", "1 "),
                Arguments.of("total", null),
                Arguments.of("total", true),
                Arguments.of("clienteId", "1-1-1-1-1"),
                Arguments.of("clienteId", ""),
                Arguments.of("clienteId", UUID.randomUUID()),
                Arguments.of("creadoPor", "sensitive-invalid-identity"),
                Arguments.of("confirmadoPor", 123),
                Arguments.of("terminalOrigenId", "invalid"),
                Arguments.of("creadoEn", "2026-09-10T12:00:00"),
                Arguments.of("creadoEn", "2026-02-30T12:00:00Z"),
                Arguments.of("creadoEn", "0000-01-01T00:00:00Z"),
                Arguments.of("confirmadoEn", "+10000-01-01T00:00:00Z"),
                Arguments.of("confirmadoEn", ""),
                Arguments.of("confirmadoEn", 12345));
    }

    @Test
    void rejectsMissingRequiredFieldsAndUnsupportedOperations() {
        for (String field : new String[] {"schemaVersion", "sourceRevision", "tipo", "numero", "estado",
                "fecha", "moneda", "subtotal", "impuestos", "total"}) {
            Map<String, Object> payload = payload();
            payload.remove(field);
            assertInvalid(request(payload), field);
        }
        for (SyncOperation operation : new SyncOperation[] {
                SyncOperation.CREAR, SyncOperation.BORRAR, SyncOperation.CERRAR, null}) {
            assertInvalid(request(payload(), operation), "operation");
        }
    }

    private static void assertInvalid(SyncEventRequest request, String field) {
        assertThatThrownBy(() -> CommercialDocumentSnapshot.parse(request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).isEqualTo("Campo documental invalido: " + field);
                    assertThat(exception.getCause()).isNull();
                });
    }

    static Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 2);
        payload.put("sourceRevision", 7L);
        payload.put("tipo", "TICKET");
        payload.put("numero", "T-20260910-1");
        payload.put("estado", "CONFIRMADO");
        payload.put("fecha", "2026-09-10");
        payload.put("moneda", "EUR");
        payload.put("subtotal", "10.00");
        payload.put("impuestos", "0.70");
        payload.put("total", "10.70");
        return payload;
    }

    static SyncEventRequest request(Map<String, Object> payload) {
        return request(payload, SyncOperation.CONFIRMAR);
    }

    static SyncEventRequest request(Map<String, Object> payload, SyncOperation operation) {
        return new SyncEventRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "DOCUMENTO", UUID.randomUUID(), operation, payload);
    }
}
