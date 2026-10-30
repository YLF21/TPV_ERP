package com.tpverp.saas.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.license.SaasStore;
import com.tpverp.saas.loyalty.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class MemberPointsSyncProjectorTest {
    private static final Instant RECEIVED = Instant.parse("2026-08-26T13:37:51Z");
    private static final Instant OCCURRED = Instant.parse("2026-08-26T13:37:50.975587801Z");
    private final UUID companyId = UUID.randomUUID();
    private final UUID storeId = UUID.randomUUID();
    private final UUID operationId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final ObjectMapper json = new ObjectMapper();
    private final SaasMemberPointsOperationRepository operations = mock(SaasMemberPointsOperationRepository.class);
    private final SaasMemberBalanceAccountRepository accounts = mock(SaasMemberBalanceAccountRepository.class);
    private final SaasMemberPointsAuthorityRepository authorities = mock(SaasMemberPointsAuthorityRepository.class);
    private final SaasMemberPointsDebtLotRepository debts = mock(SaasMemberPointsDebtLotRepository.class);
    private final SaasMemberPointsSettlementRepository settlements = mock(SaasMemberPointsSettlementRepository.class);
    private final SaasCompany company = mock(SaasCompany.class);
    private final SaasStore store = mock(SaasStore.class);
    private MemberPointsSyncProjector projector;

    @BeforeEach
    void setUp() {
        var entityManager = mock(EntityManager.class);
        when(company.getId()).thenReturn(companyId);
        when(store.getId()).thenReturn(storeId);
        when(entityManager.find(SaasCompany.class, companyId, LockModeType.PESSIMISTIC_WRITE)).thenReturn(company);
        when(operations.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(authorities.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        projector = new MemberPointsSyncProjector(entityManager, authorities, accounts, operations,
                debts, mock(SaasMemberPointsDebtAllocationRepository.class), settlements);
        projector.setAbsorbedOperations(mock(SaasMemberPointsBootstrapAbsorbedOperationRepository.class));
    }

    @Test
    void aceptaIsoSinActivarAutoridadNiModificarSaldos() throws Exception {
        Map<String, Object> payload = payload(OCCURRED.toString());
        SaasSyncEvent event = event(payload);
        String originalPayload = event.getPayload();
        projector.project(event, payload, RECEIVED);
        var saved = capturedOperation();
        assertThat(saved).extracting("occurredAt", "receivedAt").containsExactly(OCCURRED, RECEIVED);
        assertThat(saved.getStatus()).isEqualTo(MemberPointsOperationStatus.PENDING_BOOTSTRAP);
        var authority = ArgumentCaptor.forClass(SaasMemberPointsAuthority.class);
        verify(authorities).saveAndFlush(authority.capture());
        assertThat(authority.getValue().isActive()).isFalse();
        verifyNoInteractions(accounts, debts, settlements);
        assertThat(event.getPayload()).isEqualTo(originalPayload);
        assertThat(event.getPayloadHash()).isEqualTo("original-event-hash");
    }

    @Test
    void legadoLeeNanosegundosExactosDelEventoAunqueElMapaContengaDouble() throws Exception {
        var exactPayload = payload(new BigDecimal("1787751470.975587801"));
        var requestPayload = payload(1787751470.975587801d);
        projector.project(event(exactPayload), requestPayload, RECEIVED);
        assertThat(capturedOperation()).extracting("occurredAt").isEqualTo(OCCURRED);
    }

    @Test
    void reintentoIsoYLegadoEquivalentesNoDuplicanOperacion() throws Exception {
        var legacy = payload(new BigDecimal("1787751470.975587801"));
        projector.project(event(legacy), legacy, RECEIVED);
        var saved = capturedOperation();
        when(operations.findForUpdateByOperationId(companyId, operationId)).thenReturn(Optional.of(saved));
        var iso = payload(OCCURRED.toString());
        projector.project(event(iso), iso, RECEIVED);
        verify(operations, times(1)).saveAndFlush(any());
        verifyNoInteractions(accounts, debts, settlements);
    }

    @Test
    void mismaOperacionConNanosegundoDistintoConservaConflicto() throws Exception {
        var initial = payload(new BigDecimal("1787751470.975587801"));
        projector.project(event(initial), initial, RECEIVED);
        var saved = capturedOperation();
        when(operations.findForUpdateByOperationId(companyId, operationId))
                .thenReturn(Optional.of(saved));
        var altered = payload(new BigDecimal("1787751470.975587802"));
        var alteredEvent = event(altered);
        assertThatThrownBy(() -> projector.project(alteredEvent, altered, RECEIVED))
                .isInstanceOf(MemberPointsSyncProjector.ProjectionException.class)
                .hasMessageContaining("operationId reutilizado con contenido distinto");
        verify(operations, times(1)).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1787751470.0000000001", "9999999999999999999999999", "-9999999999999999999999999", "1E100000", "1E-100000"})
    void rechazaEpochFueraDeRangoOPrecisionSinPersistir(String number) throws Exception {
        var payload = payload(new BigDecimal(number));
        var event = event(payload);
        assertThatThrownBy(() -> projector.project(event, payload, RECEIVED))
                .isInstanceOf(MemberPointsSyncProjector.ProjectionException.class);
        verify(operations, never()).saveAndFlush(any());
        verifyNoInteractions(accounts, authorities, debts, settlements);
    }

    @Test
    void rechazaFechaSinZonaYTipoNoTemporal() throws Exception {
        for (Object invalid : new Object[] {"2026-08-26T13:37:50", "1787751470.975587801", true, Map.of()}) {
            var payload = payload(invalid);
            var event = event(payload);
            assertThatThrownBy(() -> projector.project(event, payload, RECEIVED))
                    .isInstanceOf(MemberPointsSyncProjector.ProjectionException.class);
        }
        verify(operations, never()).saveAndFlush(any());
    }

    @Test
    void aceptaSegundosEnterosYEpochNegativoSinTruncarFraccion() throws Exception {
        for (BigDecimal seconds : new BigDecimal[] {BigDecimal.ZERO, new BigDecimal("-0.123456789")}) {
            reset(operations);
            when(operations.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
            var payload = payload(seconds);
            projector.project(event(payload), payload, RECEIVED);
            var expected = seconds.signum() == 0 ? Instant.EPOCH : Instant.ofEpochSecond(-1, 876543211);
            assertThat(capturedOperation()).extracting("occurredAt").isEqualTo(expected);
        }
    }

    private SaasMemberPointsOperation capturedOperation() {
        var captured = ArgumentCaptor.forClass(SaasMemberPointsOperation.class);
        verify(operations).saveAndFlush(captured.capture());
        return captured.getValue();
    }

    private Map<String, Object> payload(Object occurredAt) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("schemaVersion", 1);
        payload.put("operationId", operationId.toString());
        payload.put("memberId", memberId.toString());
        payload.put("operationType", "SALE_EARN");
        payload.put("amount", 10);
        payload.put("sourceDocumentId", documentId.toString());
        payload.put("occurredAt", occurredAt);
        payload.put("localPointsDelta", 10);
        payload.put("localDebtDelta", 0);
        return payload;
    }

    private SaasSyncEvent event(Map<String, Object> payload) throws Exception {
        return new SaasSyncEvent(UUID.randomUUID(), company, store, null, 1L,
                MemberPointsSyncProjector.ENTITY_TYPE, operationId, SyncOperation.CREAR,
                json.writeValueAsString(payload), "original-event-hash", 1, RECEIVED);
    }
}
