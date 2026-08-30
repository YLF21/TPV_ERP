package com.tpverp.backend.party.loyalty.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.Member;
import com.tpverp.backend.party.MemberBalanceLotConsumption;
import com.tpverp.backend.party.MemberBalanceLotConsumptionRepository;
import com.tpverp.backend.party.MemberBalanceLotType;
import com.tpverp.backend.party.MemberMovement;
import com.tpverp.backend.party.MemberMovementRepository;
import com.tpverp.backend.party.MemberMovementType;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.sync.SyncOutboxEvent;
import com.tpverp.backend.sync.SyncOutboxIncidentService;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.sync.SyncOutboxStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Reconstructs recovery evidence for an explicitly read-only preview. */
@Service
public class MemberReturnBalanceRecoveryRepairService {

    static final String ACTION_ENQUEUE = "ENQUEUE";
    private static final String ACTION_NO_OP = "NO_OP";
    private static final String ACTION_REOPEN_DEAD_LETTER = "REOPEN_DEAD_LETTER";
    private static final String ACTION_CONFLICT = "CONFLICT";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{8,128}");

    private final CommercialDocumentRepository documents;
    private final MemberMovementRepository movements;
    private final MemberBalanceLotConsumptionRepository consumptions;
    private final CurrentOrganization organization;
    private final SyncOutboxService outbox;
    private final SyncOutboxIncidentService incidents;
    private final MemberReturnBalanceRecoveryOutboxPublisher publisher;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public MemberReturnBalanceRecoveryRepairService(
            CommercialDocumentRepository documents,
            MemberMovementRepository movements,
            MemberBalanceLotConsumptionRepository consumptions,
            CurrentOrganization organization,
            SyncOutboxService outbox,
            SyncOutboxIncidentService incidents,
            MemberReturnBalanceRecoveryOutboxPublisher publisher,
            AuditService audit,
            ObjectMapper objectMapper) {
        this.documents = documents;
        this.movements = movements;
        this.consumptions = consumptions;
        this.organization = organization;
        this.outbox = outbox;
        this.incidents = incidents;
        this.publisher = publisher;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public MemberReturnBalanceRecoveryView preview(UUID returnRequestId) {
        var evidence = evidence(returnRequestId, false);
        var existing = outbox.latest(evidence.companyId(), evidence.storeId(),
                MemberReturnBalanceRecoveryOutboxPublisher.ENTITY_TYPE, returnRequestId);
        return view(evidence, existing.orElse(null), proposedAction(existing, evidence.command()));
    }

    @Transactional
    public MemberReturnBalanceRecoveryView replay(UUID returnRequestId,
            MemberReturnBalanceRecoveryRequest request, String requestId) {
        Objects.requireNonNull(request, "request");
        var normalizedReason = normalizedReason(request.reason());
        var normalizedRequestId = normalizedRequestId(requestId);
        var evidence = evidence(returnRequestId, true);
        verifyExpected(evidence, request);
        var existing = outbox.latest(evidence.companyId(), evidence.storeId(),
                MemberReturnBalanceRecoveryOutboxPublisher.ENTITY_TYPE, returnRequestId);

        SyncOutboxEvent event;
        String action;
        if (existing.isEmpty()) {
            event = publisher.publish(evidence.command());
            action = ACTION_ENQUEUE;
        } else {
            event = existing.get();
            if (!equivalent(event, evidence.command())) {
                throw conflict("Ya existe un recovery diferente para esta devolucion");
            }
            if (event.getStatus() == SyncOutboxStatus.DEAD_LETTER) {
                incidents.retry(event.getEventId(), event.getVersion(), normalizedReason);
                action = ACTION_REOPEN_DEAD_LETTER;
            } else {
                action = ACTION_NO_OP;
            }
        }
        auditReplay(evidence, event, action, normalizedReason, normalizedRequestId);
        return view(evidence, event, action);
    }

    private Evidence evidence(UUID returnRequestId, boolean lock) {
        Objects.requireNonNull(returnRequestId, "returnRequestId");
        var store = organization.currentStore();
        var company = organization.currentCompany();
        var candidates = lock
                ? documents.findLockedByReturnRequestIdAndTiendaId(returnRequestId, store.getId())
                : documents.findByReturnRequestIdAndTiendaId(returnRequestId, store.getId());
        if (candidates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Devolucion no encontrada");
        }
        if (candidates.size() != 1) {
            throw conflict("La devolucion tiene evidencia ambigua");
        }
        var returnDocument = candidates.getFirst();
        if (returnDocument == null || invalidDocumentStatus(returnDocument.getEstado())
                || !store.getId().equals(returnDocument.getTiendaId())) {
            throw conflict("La devolucion no admite recovery");
        }

        var recoveryMovements = movements.findByDocumentIdOrderByCreatedAtAsc(returnDocument.getId())
                .stream()
                .filter(movement -> movement != null
                        && movement.getType() == MemberMovementType.DEVOLUCION_ACUMULACION_SALDO)
                .toList();
        if (recoveryMovements.size() != 1) {
            throw conflict("La devolucion debe tener un unico movimiento de saldo recuperable");
        }
        var movement = recoveryMovements.getFirst();
        var movementAmount = money(movement.getBalanceAmount());
        var member = movement.getMember();
        if (!returnDocument.getId().equals(movement.getDocumentId())
                || movementAmount.signum() >= 0
                || movement.getStore() == null
                || !store.getId().equals(movement.getStore().getId())
                || member == null || member.getCompany() == null
                || !company.getId().equals(member.getCompany().getId())
                || member.getCustomer() == null
                || !member.getCustomer().getId().equals(returnDocument.getClienteId())) {
            throw conflict("El movimiento de devolucion no pertenece al contexto actual");
        }

        var claimEntities = consumptions.findByMovement_Id(movement.getId());
        if (claimEntities == null || claimEntities.isEmpty()) {
            throw conflict("El movimiento no tiene consumos de lotes");
        }
        var claims = new ArrayList<com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.RetentionClaim>();
        BigDecimal total = money(BigDecimal.ZERO);
        UUID sourceDocumentId = null;
        for (var consumption : claimEntities) {
            var claim = validateConsumption(consumption, member, store, company);
            if (sourceDocumentId == null) {
                sourceDocumentId = claim.sourceDocumentId();
            } else if (!sourceDocumentId.equals(claim.sourceDocumentId())) {
                throw conflict("Los lotes apuntan a documentos de origen distintos");
            }
            total = total.add(claim.amount());
            claims.add(claim);
        }
        var amount = money(movementAmount.abs());
        if (total.compareTo(amount) != 0 || sourceDocumentId == null) {
            throw conflict("Los consumos no cuadran con el movimiento de devolucion");
        }
        var sourceDocument = documents.findById(sourceDocumentId)
                .orElseThrow(() -> conflict("El documento de origen no existe"));
        if (sourceDocument.getTiendaId() == null
                || !store.getId().equals(sourceDocument.getTiendaId())
                || invalidDocumentStatus(sourceDocument.getEstado())
                || !member.getCustomer().getId().equals(sourceDocument.getClienteId())) {
            throw conflict("El documento de origen no pertenece a la tienda actual");
        }

        var fingerprint = com.tpverp.backend.party.loyalty.central.MemberReturnBalanceRetentionPlanner
                .fingerprint(sourceDocumentId, amount, claims);
        MemberReturnBalanceRecoveryCommand command;
        try {
            command = new MemberReturnBalanceRecoveryCommand(
                    returnRequestId, company.getId(), store.getId(), null,
                    member.getId(), sourceDocumentId, returnDocument.getId(), amount,
                    fingerprint, claims, null);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw conflict("La evidencia de recovery no es valida");
        }
        return new Evidence(company.getId(), store.getId(), movement, sourceDocument,
                returnDocument, command);
    }

    private com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.RetentionClaim
            validateConsumption(MemberBalanceLotConsumption consumption, Member member,
                    Store store, Company company) {
        if (consumption == null || consumption.getAmount() == null
                || consumption.getAmount().signum() <= 0) {
            throw conflict("El consumo del lote no es positivo");
        }
        var lot = consumption.getLot();
        var sourceMovement = lot == null ? null : lot.getSourceMovement();
        var sourceMember = sourceMovement == null ? null : sourceMovement.getMember();
        var sourceCompany = sourceMember == null ? null : sourceMember.getCompany();
        if (lot == null || lot.getBalanceType() != MemberBalanceLotType.LOYALTY
                || lot.getMember() == null || !member.getId().equals(lot.getMember().getId())
                || sourceMovement == null
                || sourceMovement.getType() != MemberMovementType.ACUMULACION_SALDO
                || sourceMovement.getDocumentId() == null
                || !sourceMovement.getDocumentId().equals(lot.getDocumentId())
                || sourceMember == null || !member.getId().equals(sourceMember.getId())
                || sourceCompany == null || !company.getId().equals(sourceCompany.getId())
                || sourceMovement.getStore() == null
                || !store.getId().equals(sourceMovement.getStore().getId())) {
            throw conflict("El lote no es una fuente de saldo valida");
        }
        var amount = money(consumption.getAmount());
        var amountOriginal = money(lot.getAmountOriginal());
        if (lot.getId() == null || sourceMovement.getId() == null
                || amount.compareTo(amountOriginal) > 0) {
            throw conflict("El importe del consumo supera el lote de origen");
        }
        try {
            return new com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway.RetentionClaim(
                    lot.getId(), sourceMovement.getId(), sourceMovement.getDocumentId(),
                    amountOriginal, amount);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw conflict("La evidencia del lote no es valida");
        }
    }

    private String proposedAction(java.util.Optional<SyncOutboxEvent> existing,
            MemberReturnBalanceRecoveryCommand command) {
        if (existing.isEmpty()) {
            return ACTION_ENQUEUE;
        }
        var event = existing.get();
        if (!equivalent(event, command)) {
            return ACTION_CONFLICT;
        }
        return event.getStatus() == SyncOutboxStatus.DEAD_LETTER
                ? ACTION_REOPEN_DEAD_LETTER : ACTION_NO_OP;
    }

    private void verifyExpected(Evidence evidence, MemberReturnBalanceRecoveryRequest request) {
        var expectedAmount = exactPositive(request.expectedAmount());
        if (!evidence.movement().getId().equals(request.expectedMovementId())
                || evidence.command().attributedAmount().compareTo(expectedAmount) != 0
                || !evidence.command().claimsFingerprint().equals(request.expectedFingerprint().trim())) {
            throw conflict("La evidencia de recovery ha cambiado");
        }
    }

    private boolean equivalent(SyncOutboxEvent event, MemberReturnBalanceRecoveryCommand command) {
        JsonNode expected = objectMapper.valueToTree(publisher.canonicalPayload(command));
        JsonNode actual = objectMapper.valueToTree(event.getPayload());
        return semanticallyEqual(expected, actual);
    }

    private static boolean semanticallyEqual(JsonNode left, JsonNode right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.getNodeType() != right.getNodeType()) {
            return left != null && right != null && left.isNumber() && right.isNumber()
                    && left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        if (left.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        if (left.isObject()) {
            if (left.size() != right.size()) {
                return false;
            }
            Iterator<String> names = left.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!right.has(name) || !semanticallyEqual(left.get(name), right.get(name))) {
                    return false;
                }
            }
            return true;
        }
        if (left.isArray()) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int index = 0; index < left.size(); index++) {
                if (!semanticallyEqual(left.get(index), right.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }

    private MemberReturnBalanceRecoveryView view(Evidence evidence, SyncOutboxEvent event,
            String action) {
        return new MemberReturnBalanceRecoveryView(
                evidence.command().operationId(), action,
                event == null ? null : event.getEventId(),
                event == null ? null : event.getVersion(),
                event == null ? null : event.getStatus(),
                evidence.movement().getId(), evidence.command().memberId(),
                evidence.command().sourceDocumentId(), evidence.sourceDocument().getNumero(),
                evidence.command().returnDocumentId(), evidence.returnDocument().getNumero(),
                evidence.command().attributedAmount(), evidence.command().claimsFingerprint(),
                evidence.command().claims().stream()
                        .map(claim -> new MemberReturnBalanceRecoveryView.ClaimView(
                                claim.lotId(), claim.sourceMovementId(), claim.sourceDocumentId(),
                                claim.amountOriginal(), claim.amount()))
                        .toList());
    }

    private void auditReplay(Evidence evidence, SyncOutboxEvent event, String action,
            String reason, String requestId) {
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("action", action);
        details.put("requestId", requestId);
        details.put("returnRequestId", evidence.command().operationId().toString());
        details.put("movementId", evidence.movement().getId().toString());
        details.put("memberId", evidence.command().memberId().toString());
        details.put("sourceDocumentId", evidence.sourceDocument().getId().toString());
        details.put("sourceDocumentNumber", evidence.sourceDocument().getNumero());
        details.put("returnDocumentId", evidence.returnDocument().getId().toString());
        details.put("returnDocumentNumber", evidence.returnDocument().getNumero());
        details.put("amount", evidence.command().attributedAmount());
        details.put("fingerprint", evidence.command().claimsFingerprint());
        details.put("eventId", event.getEventId().toString());
        details.put("eventStatus", event.getStatus().name());
        details.put("reason", normalizedReason(reason));
        audit.record("MEMBER_RETURN_BALANCE_RECOVERY_REPLAY", AuditResult.EXITO, details);
    }

    private static String normalizedReason(String reason) {
        var normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El motivo es obligatorio y no puede superar 500 caracteres");
        }
        return normalized;
    }

    private static String normalizedRequestId(String requestId) {
        var normalized = requestId == null ? "" : requestId.trim();
        if (!SAFE_REQUEST_ID.matcher(normalized).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El requestId no es valido");
        }
        return normalized;
    }

    private static BigDecimal exactPositive(BigDecimal value) {
        if (value == null || value.scale() != 2 || value.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El importe esperado debe tener exactamente dos decimales y ser positivo");
        }
        return value;
    }

    private static BigDecimal money(BigDecimal value) {
        try {
            return Objects.requireNonNull(value, "amount").setScale(2, RoundingMode.UNNECESSARY);
        } catch (NullPointerException | ArithmeticException exception) {
            throw conflict("La evidencia contiene importes invalidos");
        }
    }

    private static boolean invalidDocumentStatus(DocumentStatus status) {
        return status == null || status == DocumentStatus.BORRADOR || status == DocumentStatus.ANULADO;
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record Evidence(UUID companyId, UUID storeId, MemberMovement movement,
            CommercialDocument sourceDocument, CommercialDocument returnDocument,
            MemberReturnBalanceRecoveryCommand command) {
    }
}
