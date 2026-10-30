package com.tpverp.backend.party.loyalty.points.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.party.MemberPointsOperationRepository.MemberPointsBootstrapOperationProjection;
import com.tpverp.backend.party.MemberPointsOperationType;
import com.tpverp.backend.party.Member;
import com.tpverp.backend.party.MemberPointsOperation;
import com.tpverp.backend.party.loyalty.sync.MemberPointsContractCanonicalizer;
import com.tpverp.backend.sync.SyncOutboxEventRepository;
import com.tpverp.backend.sync.SyncOutboxEventRepository.PointsContractOriginal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MemberPointsBootstrapContractResolverTest {
    static final UUID COMPANY = UUID.randomUUID(), STORE = UUID.randomUUID(), OPERATION = UUID.randomUUID(),
            MEMBER = UUID.randomUUID(), DOCUMENT = UUID.randomUUID();
    static final Instant ORIGINAL = Instant.parse("2026-08-26T13:37:50.975587801Z");
    static final Instant STORED = Instant.parse("2026-08-26T13:37:50.975588Z");
    final SyncOutboxEventRepository outbox = mock(SyncOutboxEventRepository.class);
    final MemberPointsBootstrapContractResolver resolver = new MemberPointsBootstrapContractResolver(outbox);
    final MemberPointsBootstrapOperationProjection row = row();
    final PointsContractOriginal event = mock(PointsContractOriginal.class);
    final ObjectMapper json = new ObjectMapper();
    Map<String, Object> payload;

    @BeforeEach
    void setUp() throws Exception {
        when(event.getEventId()).thenReturn(UUID.randomUUID());
        when(event.getCompanyId()).thenReturn(COMPANY);
        when(event.getStoreId()).thenReturn(STORE);
        when(event.getStoreSequence()).thenReturn(23L);
        when(event.getEntityType()).thenReturn("MEMBER_POINTS_OPERATION");
        when(event.getEntityId()).thenReturn(OPERATION);
        when(event.getOperation()).thenReturn("CREAR");
        payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("operationId", OPERATION.toString());
        payload.put("memberId", MEMBER.toString());
        payload.put("operationType", "SALE_EARN");
        payload.put("amount", 10);
        payload.put("sourceDocumentId", DOCUMENT.toString());
        payload.put("originalDocumentId", null);
        payload.put("occurredAt", new BigDecimal("1787751470.975587801"));
        payload.put("localPointsDelta", 10);
        payload.put("localDebtDelta", 0);
        originalPayload();
        when(outbox.findPointsContractOriginals(anyList(), anyList())).thenReturn(List.of(event));
    }

    @Test
    void restauraHashDelEventoEpochExactoSinCambiarFilaNiOriginal() {
        String originalJson = event.getPayload();
        assertThat(resolve()).isEqualTo(hash(ORIGINAL)).isNotEqualTo(hash(STORED));
        assertThat(row.getOccurredAt()).isEqualTo(STORED);
        assertThat(event.getPayload()).isEqualTo(originalJson);
        verify(outbox).findPointsContractOriginals(List.of(OPERATION), List.of(OPERATION.toString()));
    }

    @Test
    void fechaIsoOriginalProduceElMismoContrato() throws Exception {
        payload.put("occurredAt", ORIGINAL.toString());
        originalPayload();
        assertThat(resolve()).isEqualTo(hash(ORIGINAL));
    }

    @ParameterizedTest
    @CsvSource({"0.000000499,1970-01-01T00:00:00Z",
            "0.000000500,1970-01-01T00:00:00.000001Z",
            "0.999999999,1970-01-01T00:00:01Z",
            "-0.000000500,1970-01-01T00:00:00Z"})
    void cotejaRedondeoPostgreSqlIncluidoCambioDeSegundo(String epoch, String stored) throws Exception {
        payload.put("occurredAt", new BigDecimal(epoch));
        originalPayload();
        when(row.getOccurredAt()).thenReturn(Instant.parse(stored));
        BigDecimal decimal = new BigDecimal(epoch);
        long nanos = decimal.movePointRight(9).longValueExact();
        assertThat(resolve()).isEqualTo(hash(Instant.ofEpochSecond(0, nanos)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"schemaVersion", "operationId", "memberId", "operationType", "amount",
            "sourceDocumentId", "originalDocumentId", "localPointsDelta", "localDebtDelta", "occurredAt", "extra"})
    void rechazaCampoIncompatibleEnLugarDeUsarFallback(String field) throws Exception {
        Object replacement = switch (field) {
            case "operationId", "memberId", "sourceDocumentId", "originalDocumentId" -> UUID.randomUUID().toString();
            case "operationType" -> "MANUAL_ADJUSTMENT";
            case "occurredAt" -> ORIGINAL.plusNanos(1000).toString();
            default -> 99;
        };
        payload.put(field, replacement);
        originalPayload();
        assertThatThrownBy(this::resolve).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No se puede reconstruir el contrato de puntos");
    }

    @ParameterizedTest
    @ValueSource(strings = {"company", "store", "sequence", "entity", "type", "operation", "event"})
    void rechazaProcedenciaIncompatible(String field) {
        switch (field) {
            case "company" -> when(event.getCompanyId()).thenReturn(UUID.randomUUID());
            case "store" -> when(event.getStoreId()).thenReturn(UUID.randomUUID());
            case "sequence" -> when(event.getStoreSequence()).thenReturn(24L);
            case "entity" -> when(event.getEntityId()).thenReturn(UUID.randomUUID());
            case "type" -> when(event.getEntityType()).thenReturn("DOCUMENTO");
            case "operation" -> when(event.getOperation()).thenReturn("ACTUALIZAR");
            case "event" -> when(event.getEventId()).thenReturn(null);
            default -> throw new AssertionError(field);
        }
        assertThatThrownBy(this::resolve).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Procedencia original incompatible");
    }

    @Test
    void rechazaOriginalAmbiguoInclusoSiElContenidoCoincide() {
        when(outbox.findPointsContractOriginals(anyList(), anyList())).thenReturn(List.of(event, event));
        assertThatThrownBy(this::resolve).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("varios eventos originales");
    }

    @Test
    void conservaContratoHistoricoSoloCuandoNoExisteOutbox() {
        when(outbox.findPointsContractOriginals(anyList(), anyList())).thenReturn(List.of());
        assertThat(resolve()).isEqualTo(hash(STORED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0000000001", "1E100000", "1E-100000"})
    void rechazaFechaNumericaFueraDePrecisionORango(String epoch) throws Exception {
        payload.put("occurredAt", new BigDecimal(epoch));
        originalPayload();
        assertThatThrownBy(this::resolve).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pruebaPerdidaDoublePorHashLocalUnicoYConservaContratoWire() throws Exception {
        Instant source = Instant.parse("2026-09-26T17:17:25.275168500Z");
        prepareDoubleLegacy(source);
        Instant wire = Instant.parse("2026-09-26T17:17:25.275168400Z");
        assertThat(resolve()).isEqualTo(hash(wire)).isNotEqualTo(hash(source));
    }

    @Test
    void noAceptaDesfaseConHashLocalCorrupto() throws Exception {
        prepareDoubleLegacy(Instant.parse("2026-09-26T17:17:25.275168500Z"));
        when(row.getPayloadHash()).thenReturn("0".repeat(64));
        assertThatThrownBy(this::resolve).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Fecha original no coincide");
    }

    @Test
    void hashLocalNoAutorizaOtroWireCercanoQueNoReproduceDouble() throws Exception {
        prepareDoubleLegacy(Instant.parse("2026-09-26T17:17:25.275168500Z"));
        payload.put("occurredAt", new BigDecimal("1790443045.2751683"));
        originalPayload();
        assertThatThrownBy(this::resolve).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Fecha original no coincide");
    }

    @Test
    void fechaIsoIncompatibleNoUsaCompatibilidadDouble() throws Exception {
        prepareDoubleLegacy(Instant.parse("2026-09-26T17:17:25.275168500Z"));
        payload.put("occurredAt", "2026-09-26T17:17:25.275168400Z");
        originalPayload();
        assertThatThrownBy(this::resolve).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Fecha original no coincide");
    }

    @Test
    void hashLocalVinculaContenidoAunqueFilaYPayloadAlteradosCoincidan() throws Exception {
        prepareDoubleLegacy(Instant.parse("2026-09-26T17:17:25.275168500Z"));
        when(row.getAmount()).thenReturn(20L);
        payload.put("amount", 20);
        originalPayload();
        assertThatThrownBy(this::resolve).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Fecha original no coincide");
    }

    @Test
    void hashDeInstanteFueraDelIntervaloPostgresNoAutorizaDesfase() throws Exception {
        // This original would persist as .275168, not the claimed database .275169.
        prepareDoubleLegacy(Instant.parse("2026-09-26T17:17:25.275168499Z"));
        assertThatThrownBy(this::resolve).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Fecha original no coincide");
    }

    private void prepareDoubleLegacy(Instant originalInstant) throws Exception {
        var member = mock(Member.class);
        when(member.getId()).thenReturn(MEMBER);
        var operation = new MemberPointsOperation(OPERATION, member, COMPANY, STORE, 23L,
                MemberPointsOperationType.SALE_EARN, 10L, DOCUMENT, null,
                originalInstant, 10L, 0L, "original-checkpoint");
        when(row.getOccurredAt()).thenReturn(Instant.parse("2026-09-26T17:17:25.275169Z"));
        when(row.getPayloadHash()).thenReturn(operation.getPayloadHash());
        payload.put("occurredAt", new BigDecimal("1790443045.2751684"));
        originalPayload();
    }

    @Test
    void rechazaIdentificadorOriginalMalformadoConDiagnosticoExplicito() throws Exception {
        payload.put("operationId", "not-a-uuid");
        originalPayload();
        assertThatThrownBy(this::resolve).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("operationId original invalido");
    }

    @Test
    void rechazaJsonIlegibleYNoConsultaSiNoHayOperaciones() {
        when(event.getPayload()).thenReturn("{broken");
        assertThatThrownBy(this::resolve).isInstanceOf(IllegalStateException.class).hasMessageContaining("JSON original ilegible");
        clearInvocations(outbox);
        assertThat(resolver.resolve(COMPANY, STORE, List.of())).isEmpty();
        verifyNoInteractions(outbox);
    }

    private String resolve() { return resolver.resolve(COMPANY, STORE, List.of(row)).get(OPERATION); }
    private void originalPayload() throws Exception {
        when(event.getPayload()).thenReturn(json.writeValueAsString(payload));
    }
    static String hash(Instant occurred) {
        return MemberPointsContractCanonicalizer.contractHash(OPERATION, MEMBER,
                MemberPointsOperationType.SALE_EARN, 10, DOCUMENT, null, occurred, 10, 0);
    }
    static MemberPointsBootstrapOperationProjection row() {
        var row = mock(MemberPointsBootstrapOperationProjection.class);
        when(row.getOperationId()).thenReturn(OPERATION);
        when(row.getMemberId()).thenReturn(MEMBER);
        when(row.getOperationType()).thenReturn(MemberPointsOperationType.SALE_EARN);
        when(row.getAmount()).thenReturn(10L);
        when(row.getSourceDocumentId()).thenReturn(DOCUMENT);
        when(row.getOccurredAt()).thenReturn(STORED);
        when(row.getLocalPointsDelta()).thenReturn(10L);
        when(row.getStoreSequence()).thenReturn(23L);
        return row;
    }
}
