package com.tpverp.backend.party.loyalty.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRate;
import com.tpverp.backend.party.DocumentType;
import com.tpverp.backend.party.Member;
import com.tpverp.backend.party.MemberBalanceLot;
import com.tpverp.backend.party.MemberBalanceLotConsumption;
import com.tpverp.backend.party.MemberBalanceLotConsumptionRepository;
import com.tpverp.backend.party.MemberMovement;
import com.tpverp.backend.party.MemberMovementRepository;
import com.tpverp.backend.party.MemberMovementType;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxEvent;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.sync.SyncOutboxIncidentService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemberReturnBalanceRecoveryRepairServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");

    @Mock CommercialDocumentRepository documents;
    @Mock MemberMovementRepository movements;
    @Mock MemberBalanceLotConsumptionRepository consumptions;
    @Mock CurrentOrganization organization;
    @Mock SyncOutboxService outbox;
    @Mock SyncOutboxIncidentService incidents;
    @Mock AuditService audit;

    private Fixture fixture;
    private MemberReturnBalanceRecoveryOutboxPublisher publisher;
    private MemberReturnBalanceRecoveryRepairService service;

    @BeforeEach
    void setUp() {
        fixture = fixture();
        publisher = new MemberReturnBalanceRecoveryOutboxPublisher(outbox);
        service = new MemberReturnBalanceRecoveryRepairService(
                documents, movements, consumptions, organization, outbox, incidents,
                publisher, audit, new ObjectMapper());
        when(organization.currentStore()).thenReturn(fixture.store());
        when(organization.currentCompany()).thenReturn(fixture.company());
        when(documents.findByReturnRequestIdAndTiendaId(fixture.returnRequestId(), fixture.store().getId()))
                .thenReturn(List.of(fixture.returnDocument()));
        when(documents.findLockedByReturnRequestIdAndTiendaId(fixture.returnRequestId(), fixture.store().getId()))
                .thenReturn(List.of(fixture.returnDocument()));
        when(movements.findByDocumentIdOrderByCreatedAtAsc(fixture.returnDocument().getId()))
                .thenReturn(List.of(fixture.reversal()));
        when(consumptions.findByMovement_Id(fixture.reversal().getId()))
                .thenReturn(List.of(fixture.consumption()));
        when(documents.findById(fixture.sourceDocument().getId()))
                .thenReturn(Optional.of(fixture.sourceDocument()));
    }

    @Test
    void previewReconstruyeHistoricoExactoSinEscrituras() {
        when(outbox.latest(fixture.company().getId(), fixture.store().getId(),
                MemberReturnBalanceRecoveryOutboxPublisher.ENTITY_TYPE, fixture.returnRequestId()))
                .thenReturn(Optional.empty());

        var view = service.preview(fixture.returnRequestId());

        assertThat(view.action()).isEqualTo("ENQUEUE");
        assertThat(view.amount()).isEqualByComparingTo("0.22");
        assertThat(view.movementId()).isEqualTo(fixture.reversal().getId());
        assertThat(view.sourceDocumentId()).isEqualTo(fixture.sourceDocument().getId());
        assertThat(view.sourceDocumentNumber()).isEqualTo("001-260829-00001");
        assertThat(view.returnDocumentNumber()).isEqualTo("001-260829-00003");
        assertThat(view.claims()).singleElement().satisfies(claim -> {
            assertThat(claim.lotId()).isEqualTo(fixture.lot().getId());
            assertThat(claim.amountOriginal()).isEqualByComparingTo("1.00");
            assertThat(claim.amount()).isEqualByComparingTo("0.22");
        });
        assertThat(view.fingerprint()).isEqualTo(fixture.fingerprint());
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void payloadCanonicoEquivalenteEsNoOp() {
        var event = fixture.event(publisher);
        when(outbox.latest(any(), any(), any(), any())).thenReturn(Optional.of(event));

        var view = service.preview(fixture.returnRequestId());

        assertThat(view.action()).isEqualTo("NO_OP");
        assertThat(view.eventId()).isNotNull();
        assertThat(view.eventStatus()).isEqualTo(com.tpverp.backend.sync.SyncOutboxStatus.PENDIENTE);
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void payloadCanonicoTrasRoundTripNumericoEsNoOp() throws Exception {
        var canonical = fixture.event(publisher);
        var mapper = new ObjectMapper();
        var roundTripPayload = mapper.readValue(
                mapper.writeValueAsBytes(canonical.getPayload()), Map.class);
        assertThat(roundTripPayload.get("attributedAmount")).isInstanceOf(Double.class);
        var roundTripClaims = (List<?>) roundTripPayload.get("claims");
        assertThat(((Map<?, ?>) roundTripClaims.getFirst()).get("amount"))
                .isInstanceOf(Double.class);
        var roundTrip = new SyncOutboxEvent(
                fixture.company().getId(), fixture.store().getId(), null,
                MemberReturnBalanceRecoveryOutboxPublisher.ENTITY_TYPE,
                fixture.returnRequestId(), SyncOperation.CONFIRMAR, roundTripPayload, NOW);
        when(outbox.latest(any(), any(), any(), any())).thenReturn(Optional.of(roundTrip));

        var view = service.preview(fixture.returnRequestId());

        assertThat(view.action()).isEqualTo("NO_OP");
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void payloadCanonicoDiferenteEsConflictSinEscrituras() {
        var different = new SyncOutboxEvent(
                fixture.company().getId(), fixture.store().getId(), null,
                MemberReturnBalanceRecoveryOutboxPublisher.ENTITY_TYPE,
                fixture.returnRequestId(), SyncOperation.CONFIRMAR,
                Map.of("schemaVersion", 999), NOW);
        when(outbox.latest(any(), any(), any(), any())).thenReturn(Optional.of(different));

        var view = service.preview(fixture.returnRequestId());

        assertThat(view.action()).isEqualTo("CONFLICT");
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void replayAusenteEncolaUnaVezYAuditaSinPayload() {
        var queued = fixture.event(publisher);
        when(outbox.latest(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(outbox.enqueue(any(SyncOutboundEventCommand.class))).thenReturn(queued);

        var view = service.replay(fixture.returnRequestId(), fixture.request(), "recovery-1234");

        assertThat(view.action()).isEqualTo("ENQUEUE");
        verify(outbox).enqueue(any(SyncOutboundEventCommand.class));
        verify(documents).findLockedByReturnRequestIdAndTiendaId(
                fixture.returnRequestId(), fixture.store().getId());
        verify(documents, never()).findByReturnRequestIdAndTiendaId(any(), any());
        verify(audit).record(org.mockito.ArgumentMatchers.eq(
                        "MEMBER_RETURN_BALANCE_RECOVERY_REPLAY"),
                org.mockito.ArgumentMatchers.eq(AuditResult.EXITO),
                org.mockito.ArgumentMatchers.argThat(details ->
                        details.size() == 14
                                && "ENQUEUE".equals(details.get("action"))
                                && "revisado".equals(details.get("reason"))
                                && "recovery-1234".equals(details.get("requestId"))
                                && fixture.returnRequestId().toString()
                                        .equals(details.get("returnRequestId"))
                                && fixture.reversal().getId().toString()
                                        .equals(details.get("movementId"))
                                && fixture.reversal().getMember().getId().toString()
                                        .equals(details.get("memberId"))
                                && fixture.sourceDocument().getId().toString()
                                        .equals(details.get("sourceDocumentId"))
                                && "001-260829-00001".equals(details.get("sourceDocumentNumber"))
                                && fixture.returnDocument().getId().toString()
                                        .equals(details.get("returnDocumentId"))
                                && "001-260829-00003".equals(details.get("returnDocumentNumber"))
                                && new BigDecimal("0.22").equals(details.get("amount"))
                                && fixture.fingerprint().equals(details.get("fingerprint"))
                                && queued.getEventId().toString().equals(details.get("eventId"))
                                && "PENDIENTE".equals(details.get("eventStatus"))
                                && !details.containsKey("payload")
                                && !details.containsKey("token")
                                && !details.containsKey("exception")));
    }

    @Test
    void replaySecuencialEncolaUnaVezYConservaElMismoEvento() {
        var queued = fixture.event(publisher);
        when(outbox.latest(any(), any(), any(), any()))
                .thenReturn(Optional.empty(), Optional.of(queued));
        when(outbox.enqueue(any(SyncOutboundEventCommand.class))).thenReturn(queued);

        var first = service.replay(fixture.returnRequestId(), fixture.request(), "recovery-1234");
        var second = service.replay(fixture.returnRequestId(), fixture.request(), "recovery-1234");

        assertThat(first.action()).isEqualTo("ENQUEUE");
        assertThat(second.action()).isEqualTo("NO_OP");
        assertThat(second.eventId()).isEqualTo(first.eventId());
        verify(outbox, times(1)).enqueue(any(SyncOutboundEventCommand.class));
        verify(audit, times(2)).record(any(), org.mockito.ArgumentMatchers.eq(AuditResult.EXITO), any());
    }

    @Test
    void replayEquivalenteNoOpEnLosCuatroEstadosEntregables() {
        for (var status : List.of(com.tpverp.backend.sync.SyncOutboxStatus.PENDIENTE,
                com.tpverp.backend.sync.SyncOutboxStatus.ERROR,
                com.tpverp.backend.sync.SyncOutboxStatus.ENVIANDO,
                com.tpverp.backend.sync.SyncOutboxStatus.ENVIADO)) {
            var event = fixture.event(publisher);
            var token = UUID.randomUUID();
            if (status == com.tpverp.backend.sync.SyncOutboxStatus.ERROR) {
                event.claim(token, NOW.minusSeconds(30));
                event.markRetry(token, "temporary", NOW, NOW);
            } else if (status == com.tpverp.backend.sync.SyncOutboxStatus.ENVIANDO) {
                event.claim(token, NOW.minusSeconds(30));
            } else if (status == com.tpverp.backend.sync.SyncOutboxStatus.ENVIADO) {
                event.claim(token, NOW.minusSeconds(30));
                event.markSent(token, NOW);
            }
            when(outbox.latest(any(), any(), any(), any())).thenReturn(Optional.of(event));

            assertThat(service.replay(fixture.returnRequestId(), fixture.request(), "recovery-1234")
                    .action()).isEqualTo("NO_OP");
        }
        verify(incidents, never()).retry(any(), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(outbox, never()).enqueue(any());
        verify(audit, times(4)).record(any(), org.mockito.ArgumentMatchers.eq(AuditResult.EXITO), any());
    }

    @Test
    void replayEquivalenteEsNoOpSinEncolar() {
        var event = fixture.event(publisher);
        when(outbox.latest(any(), any(), any(), any())).thenReturn(Optional.of(event));

        var view = service.replay(fixture.returnRequestId(), fixture.request(), "recovery-1234");

        assertThat(view.action()).isEqualTo("NO_OP");
        verify(outbox, never()).enqueue(any());
        verify(audit).record(any(), org.mockito.ArgumentMatchers.eq(AuditResult.EXITO), any());
    }

    @Test
    void replayDeadLetterUsaVersionEsperadaYReabre() {
        var event = fixture.event(publisher);
        var token = UUID.randomUUID();
        event.claim(token, NOW.minusSeconds(30));
        event.markDeadLetter(token, "No entregado", NOW.minusSeconds(20));
        long expectedVersion = event.getVersion();
        when(outbox.latest(any(), any(), any(), any())).thenReturn(Optional.of(event));
        org.mockito.Mockito.doAnswer(invocation -> {
            event.reopenForManualRetry(NOW);
            return null;
        }).when(incidents).retry(event.getEventId(), expectedVersion, "revisado");

        var view = service.replay(fixture.returnRequestId(), fixture.request(), "recovery-1234");

        assertThat(view.action()).isEqualTo("REOPEN_DEAD_LETTER");
        assertThat(view.eventStatus()).isEqualTo(com.tpverp.backend.sync.SyncOutboxStatus.PENDIENTE);
        verify(incidents).retry(event.getEventId(), expectedVersion, "revisado");
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void replayPayloadDiferenteEsConflictYNoEscribe() {
        var different = new SyncOutboxEvent(
                fixture.company().getId(), fixture.store().getId(), null,
                MemberReturnBalanceRecoveryOutboxPublisher.ENTITY_TYPE,
                fixture.returnRequestId(), SyncOperation.CONFIRMAR,
                Map.of("schemaVersion", 999), NOW);
        when(outbox.latest(any(), any(), any(), any())).thenReturn(Optional.of(different));

        assertThatThrownBy(() -> service.replay(
                fixture.returnRequestId(), fixture.request(), "recovery-1234"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode.value").isEqualTo(409);
        verify(outbox, never()).enqueue(any());
        verify(incidents, never()).retry(any(), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(audit, never()).record(any(), any(), any());
    }

    @Test
    void replayCasMovementAmountYFingerprintParanAntesDeConsultarOutbox() {
        var wrongMovement = new MemberReturnBalanceRecoveryRequest(
                UUID.randomUUID(), new BigDecimal("0.22"), fixture.fingerprint(), "revisado");
        var wrongAmount = new MemberReturnBalanceRecoveryRequest(
                fixture.reversal().getId(), new BigDecimal("0.23"), fixture.fingerprint(), "revisado");
        var wrongFingerprint = new MemberReturnBalanceRecoveryRequest(
                fixture.reversal().getId(), new BigDecimal("0.22"), "different", "revisado");

        for (var request : List.of(wrongMovement, wrongAmount, wrongFingerprint)) {
            assertThatThrownBy(() -> service.replay(
                    fixture.returnRequestId(), request, "recovery-1234"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting("statusCode.value").isEqualTo(409);
        }
        verify(outbox, never()).latest(any(), any(), any(), any());
        verify(outbox, never()).enqueue(any());
        verify(audit, never()).record(any(), any(), any());
    }

    @Test
    void deadLetterEquivalenteProponeReaperturaSinEscrituras() {
        var event = fixture.event(publisher);
        var token = UUID.randomUUID();
        event.claim(token, NOW.minusSeconds(30));
        event.markDeadLetter(token, "No entregado", NOW.minusSeconds(20));
        when(outbox.latest(any(), any(), any(), any())).thenReturn(Optional.of(event));

        var view = service.preview(fixture.returnRequestId());

        assertThat(view.action()).isEqualTo("REOPEN_DEAD_LETTER");
        assertThat(view.eventVersion()).isEqualTo(event.getVersion());
        verify(outbox, never()).enqueue(any());
    }

    @Test
    void documentoAusenteEsNotFound() {
        when(documents.findByReturnRequestIdAndTiendaId(
                fixture.returnRequestId(), fixture.store().getId())).thenReturn(List.of());

        assertThatThrownBy(() -> service.preview(fixture.returnRequestId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode.value").isEqualTo(404);
    }

    @Test
    void evidenciaAmbiguaEsConflict() {
        when(documents.findByReturnRequestIdAndTiendaId(
                fixture.returnRequestId(), fixture.store().getId()))
                .thenReturn(List.of(fixture.returnDocument(), fixture.returnDocument()));

        assertThatThrownBy(() -> service.preview(fixture.returnRequestId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode.value").isEqualTo(409);
    }

    @Test
    void loteInconsistenteEsConflict() {
        var invalidLot = new MemberBalanceLot(
                fixture.reversal().getMember(), fixture.sourceMovement(),
                com.tpverp.backend.party.MemberBalanceLotType.RETURN_CREDIT,
                new BigDecimal("1.00"), NOW, null);
        var invalidConsumption = new MemberBalanceLotConsumption(
                fixture.reversal(), invalidLot, new BigDecimal("0.22"));
        when(consumptions.findByMovement_Id(fixture.reversal().getId())).thenReturn(List.of(invalidConsumption));

        assertThatThrownBy(() -> service.preview(fixture.returnRequestId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode.value").isEqualTo(409);
    }

    @Test
    void movimientoDevolucionFueraDeTiendaEsConflict() {
        var foreignStore = new Store(fixture.company(), "002", "Other", address(),
                UUID.randomUUID().toString(), "Atlantic/Canary", "EUR", "es-ES");
        var movement = org.mockito.Mockito.mock(MemberMovement.class);
        var movementId = fixture.reversal().getId();
        var returnDocumentId = fixture.returnDocument().getId();
        when(movement.getId()).thenReturn(movementId);
        when(movement.getDocumentId()).thenReturn(returnDocumentId);
        when(movement.getType()).thenReturn(MemberMovementType.DEVOLUCION_ACUMULACION_SALDO);
        when(movement.getBalanceAmount()).thenReturn(new BigDecimal("-0.22"));
        when(movement.getMember()).thenReturn(fixture.reversal().getMember());
        when(movement.getStore()).thenReturn(foreignStore);
        when(movements.findByDocumentIdOrderByCreatedAtAsc(returnDocumentId))
                .thenReturn(List.of(movement));

        assertThatThrownBy(() -> service.preview(fixture.returnRequestId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode.value").isEqualTo(409);
    }

    @Test
    void devolucionDeClienteAjenoEsConflict() {
        when(fixture.returnDocument().getClienteId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.preview(fixture.returnRequestId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode.value").isEqualTo(409);
    }

    @Test
    void documentoFuenteDeClienteAjenoEsConflict() {
        when(fixture.sourceDocument().getClienteId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.preview(fixture.returnRequestId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode.value").isEqualTo(409);
    }

    @Test
    void fuenteDelLoteFueraDeTiendaEsConflict() {
        var foreignStore = new Store(fixture.company(), "002", "Other", address(),
                UUID.randomUUID().toString(), "Atlantic/Canary", "EUR", "es-ES");
        var foreignSource = new MemberMovement(
                fixture.reversal().getMember(), foreignStore, null, fixture.sourceDocument().getId(),
                MemberMovementType.ACUMULACION_SALDO, new BigDecimal("1.00"), 0,
                null, null, "acumulacion", NOW);
        var lot = new MemberBalanceLot(fixture.reversal().getMember(), foreignSource,
                new BigDecimal("1.00"), NOW, null);
        var consumption = new MemberBalanceLotConsumption(
                fixture.reversal(), lot, new BigDecimal("0.22"));
        when(consumptions.findByMovement_Id(fixture.reversal().getId())).thenReturn(List.of(consumption));

        assertThatThrownBy(() -> service.preview(fixture.returnRequestId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode.value").isEqualTo(409);
    }

    @Test
    void importeNuloEsConflictControlado() {
        var movement = org.mockito.Mockito.mock(MemberMovement.class);
        var movementId = fixture.reversal().getId();
        var returnDocumentId = fixture.returnDocument().getId();
        when(movement.getId()).thenReturn(movementId);
        when(movement.getDocumentId()).thenReturn(returnDocumentId);
        when(movement.getType()).thenReturn(MemberMovementType.DEVOLUCION_ACUMULACION_SALDO);
        when(movement.getBalanceAmount()).thenReturn(null);
        when(movements.findByDocumentIdOrderByCreatedAtAsc(returnDocumentId))
                .thenReturn(List.of(movement));

        assertThatThrownBy(() -> service.preview(fixture.returnRequestId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode.value").isEqualTo(409);
    }

    @Test
    void consumoSuperiorAlImporteOriginalEsConflictControlado() {
        var shortLot = new MemberBalanceLot(fixture.reversal().getMember(), fixture.sourceMovement(),
                new BigDecimal("0.10"), NOW, null);
        var oversizedConsumption = new MemberBalanceLotConsumption(
                fixture.reversal(), shortLot, new BigDecimal("0.22"));
        when(consumptions.findByMovement_Id(fixture.reversal().getId()))
                .thenReturn(List.of(oversizedConsumption));

        assertThatThrownBy(() -> service.preview(fixture.returnRequestId()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode.value").isEqualTo(409);
    }

    @Test
    void controllerExponeSoloPreviewAdmin() throws NoSuchMethodException {
        var mapping = MemberReturnBalanceRecoveryController.class
                .getMethod("preview", UUID.class).getAnnotation(GetMapping.class);
        assertThat(mapping.value()).containsExactly("/{returnRequestId}/preview");
        assertThat(MemberReturnBalanceRecoveryController.class
                .getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('ADMIN')");
        assertThat(MemberReturnBalanceRecoveryController.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("replay")
                        && method.getAnnotation(PostMapping.class) != null
                        && method.getAnnotation(PostMapping.class).value()[0]
                                .equals("/{returnRequestId}/replay"));
    }

    @Test
    void requestReplayDeclaraValidacionesPublicas() {
        var parameters = MemberReturnBalanceRecoveryRequest.class.getDeclaredConstructors()[0]
                .getParameterAnnotations();
        assertThat(parameters[0]).anyMatch(annotation -> annotation.annotationType().equals(NotNull.class));
        assertThat(parameters[1]).anyMatch(annotation -> annotation.annotationType().equals(NotNull.class));
        assertThat(parameters[2]).anyMatch(annotation -> annotation.annotationType().equals(NotBlank.class));
        var fingerprintSize = Arrays.stream(parameters[2])
                .filter(annotation -> annotation instanceof Size).map(annotation -> (Size) annotation)
                .findFirst().orElseThrow();
        assertThat(fingerprintSize.max()).isEqualTo(128);
        assertThat(parameters[3]).anyMatch(annotation -> annotation.annotationType().equals(NotBlank.class));
        var reasonSize = Arrays.stream(parameters[3])
                .filter(annotation -> annotation instanceof Size).map(annotation -> (Size) annotation)
                .findFirst().orElseThrow();
        assertThat(reasonSize.max()).isEqualTo(500);
    }

    @Test
    void requestIdInseguroFallaAntesDeConsultarEvidencia() {
        assertThatThrownBy(() -> service.replay(
                fixture.returnRequestId(), fixture.request(), "bad request id"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode.value").isEqualTo(400);
        verify(documents, never()).findLockedByReturnRequestIdAndTiendaId(any(), any());
    }

    private Fixture fixture() {
        var company = new Company("B00000000", "Company", Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas", "codigoPostal", "35001",
                "provincia", "Las Palmas", "pais", "ES"));
        var store = new Store(company, "001", "Store", Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas", "codigoPostal", "35001",
                "provincia", "Las Palmas", "pais", "ES"), UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
        var customer = new Customer(company, "Cliente", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        var member = new Member(customer, "M-001-000001", java.time.LocalDate.of(2026, 8, 1));
        var user = new com.tpverp.backend.security.domain.UserAccount(
                store, "ADMIN", "hash", new com.tpverp.backend.security.domain.Role(store, "ADMIN"));
        var sourceDocument = org.mockito.Mockito.mock(CommercialDocument.class);
        var returnDocument = org.mockito.Mockito.mock(CommercialDocument.class);
        var sourceDocumentId = UUID.randomUUID();
        var returnDocumentId = UUID.randomUUID();
        var returnRequestId = UUID.randomUUID();
        when(sourceDocument.getId()).thenReturn(sourceDocumentId);
        when(sourceDocument.getTiendaId()).thenReturn(store.getId());
        when(sourceDocument.getClienteId()).thenReturn(customer.getId());
        when(sourceDocument.getEstado()).thenReturn(DocumentStatus.CONFIRMADO);
        when(sourceDocument.getNumero()).thenReturn("001-260829-00001");
        when(returnDocument.getId()).thenReturn(returnDocumentId);
        when(returnDocument.getTiendaId()).thenReturn(store.getId());
        when(returnDocument.getClienteId()).thenReturn(customer.getId());
        when(returnDocument.getEstado()).thenReturn(DocumentStatus.CONFIRMADO);
        when(returnDocument.getNumero()).thenReturn("001-260829-00003");
        var sourceMovement = new MemberMovement(
                member, store, user, sourceDocumentId, MemberMovementType.ACUMULACION_SALDO,
                new BigDecimal("1.00"), 0, null, null, "acumulacion", NOW);
        var reversal = new MemberMovement(
                member, store, user, returnDocumentId, MemberMovementType.DEVOLUCION_ACUMULACION_SALDO,
                new BigDecimal("-0.22"), 0, null, null, "devolucion", NOW);
        var lot = new MemberBalanceLot(member, sourceMovement, new BigDecimal("1.00"), NOW, null);
        var consumption = new MemberBalanceLotConsumption(reversal, lot, new BigDecimal("0.22"));
        var claim = new com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.RetentionClaim(
                lot.getId(), sourceMovement.getId(), sourceDocumentId,
                new BigDecimal("1.00"), new BigDecimal("0.22"));
        var fingerprint = com.tpverp.backend.party.loyalty.central.MemberReturnBalanceRetentionPlanner
                .fingerprint(sourceDocumentId, new BigDecimal("0.22"), List.of(claim));
        return new Fixture(company, store, sourceDocument, returnDocument, sourceMovement,
                reversal, lot, consumption, claim, returnRequestId, fingerprint);
    }

    private static Map<String, String> address() {
        return Map.of("linea1", "Calle 1", "ciudad", "Las Palmas", "codigoPostal", "35001",
                "provincia", "Las Palmas", "pais", "ES");
    }

    private record Fixture(
            Company company,
            Store store,
            CommercialDocument sourceDocument,
            CommercialDocument returnDocument,
            MemberMovement sourceMovement,
            MemberMovement reversal,
            MemberBalanceLot lot,
            MemberBalanceLotConsumption consumption,
            com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.RetentionClaim claim,
            UUID returnRequestId,
            String fingerprint) {

        MemberReturnBalanceRecoveryRequest request() {
            return new MemberReturnBalanceRecoveryRequest(
                    reversal.getId(), new BigDecimal("0.22"), fingerprint, " revisado ");
        }

        SyncOutboxEvent event(MemberReturnBalanceRecoveryOutboxPublisher publisher) {
            var command = new MemberReturnBalanceRecoveryCommand(
                    returnRequestId, company.getId(), store.getId(), null,
                    reversal.getMember().getId(), sourceDocument.getId(), returnDocument.getId(),
                    new BigDecimal("0.22"), fingerprint, List.of(claim), null);
            return new SyncOutboxEvent(
                    company.getId(), store.getId(), null,
                    MemberReturnBalanceRecoveryOutboxPublisher.ENTITY_TYPE,
                    returnRequestId, SyncOperation.CONFIRMAR,
                    publisher.canonicalPayload(command), NOW);
        }
    }
}
