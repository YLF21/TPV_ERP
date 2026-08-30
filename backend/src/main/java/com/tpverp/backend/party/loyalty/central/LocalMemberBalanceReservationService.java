package com.tpverp.backend.party.loyalty.central;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.DocumentRelationRepository;
import com.tpverp.backend.document.DocumentRelationType;
import com.tpverp.backend.document.TicketReturnValuationService;
import com.tpverp.backend.document.SalePaymentSession;
import com.tpverp.backend.document.SalePaymentSessionRepository;
import com.tpverp.backend.document.SalePaymentSessionService;
import com.tpverp.backend.document.SalePaymentSessionStatus;
import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.terminal.PaymentTerminalRefundLineSelection;
import com.tpverp.backend.terminal.TerminalRepository;
import java.time.Clock;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocalMemberBalanceReservationService {

    private static final long ORPHAN_HEARTBEAT_GRACE_SECONDS = 65L;

    private final LocalMemberBalanceReservationRepository reservations;
    private final TerminalRepository terminals;
    private final MemberBalanceReservationCoordinator coordinator;
    private final Clock clock;
    private CommercialDocumentRepository documents;
    private DocumentRelationRepository documentRelations;
    private TicketReturnValuationService returnValuations;
    private MemberReturnBalanceRetentionPlanner retentionPlanner;
    private SalePaymentSessionRepository paymentSessions;
    private SalePaymentSessionService paymentSessionService;
    private AuditService audit;

    public LocalMemberBalanceReservationService(
            LocalMemberBalanceReservationRepository reservations,
            TerminalRepository terminals,
            MemberBalanceReservationCoordinator coordinator,
            Clock clock) {
        this.reservations = reservations;
        this.terminals = terminals;
        this.coordinator = coordinator;
        this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRetentionDependencies(
            CommercialDocumentRepository documents,
            TicketReturnValuationService returnValuations,
            MemberReturnBalanceRetentionPlanner retentionPlanner) {
        this.documents = documents;
        this.returnValuations = returnValuations;
        this.retentionPlanner = retentionPlanner;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDocumentRelations(DocumentRelationRepository documentRelations) {
        this.documentRelations = documentRelations;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRetryDependencies(
            SalePaymentSessionRepository paymentSessions,
            AuditService audit,
            SalePaymentSessionService paymentSessionService) {
        this.paymentSessions = paymentSessions;
        this.audit = audit;
        this.paymentSessionService = paymentSessionService;
    }

    /**
     * Resolves the local wallet blocker behind the POS retry action. This is
     * deliberately conservative: only ACTIVE leases from this terminal with
     * no live payment session are released. Prepared/committed protocol states
     * are reported for recovery instead of being released as ordinary leases.
     */
    @Transactional
    public RetryResolution resolveRetry(
            UUID storeId,
            UUID terminalId,
            UUID memberId,
            String saleId) {
        requireTerminal(storeId, terminalId);
        Set<LocalMemberBalanceReservationStatus> inspectable = Set.of(
                LocalMemberBalanceReservationStatus.ACTIVE,
                LocalMemberBalanceReservationStatus.PREPARED,
                LocalMemberBalanceReservationStatus.TICKET_COMMITTED,
                LocalMemberBalanceReservationStatus.FINALIZE_PENDING,
                LocalMemberBalanceReservationStatus.ABORT_PENDING,
                LocalMemberBalanceReservationStatus.RELEASE_PENDING);
        List<LocalMemberBalanceReservation> candidates = reservations.findForRetry(
                storeId, terminalId, memberId, inspectable);
        LocalMemberBalanceReservation sameSale = candidates.stream()
                .filter(value -> value.getSaleId().equals(saleId))
                .findFirst()
                .orElse(null);

        if (sameSale != null && sameSale.isActive()) {
            // Same-sale recovery is idempotent and does not release another
            // lease, so it may continue through reserve() when the optional
            // session/audit collaborators are unavailable.
            RetryResolution activeSession = recoverActiveSession(sameSale, storeId, terminalId, false);
            if (activeSession != null) {
                recordRetry(activeSession.outcome().name(), sameSale, activeSession.message(),
                        storeId, terminalId, memberId, saleId, List.of());
                if (activeSession.outcome() != RetryOutcome.RECOVERED) return activeSession;
            }
            try {
                LocalMemberBalanceReservation recovered = reserve(storeId, terminalId, memberId, saleId);
                recordRetry("RECOVERED", recovered, null, storeId, terminalId, memberId, saleId, List.of());
                return RetryResolution.recovered(recovered);
            } catch (MemberBalanceCentralException exception) {
                RetryResolution result = centralConflict(exception, sameSale);
                recordRetry(result.outcome().name(), sameSale, result.message(),
                        storeId, terminalId, memberId, saleId, List.of());
                return result;
            }
        }
        if (sameSale != null && !sameSale.isClosed()) {
            RetryResolution protocol = recoverProtocol(sameSale, storeId, terminalId);
            if (protocol != null) {
                if (protocol.outcome() != RetryOutcome.RECOVERED) {
                    recordRetry(protocol.outcome().name(), sameSale, protocol.message(),
                            storeId, terminalId, memberId, saleId, List.of());
                    return protocol;
                }
            }
        }

        LocalMemberBalanceReservation blocking = null;
        List<LocalMemberBalanceReservation> released = new java.util.ArrayList<>();
        for (LocalMemberBalanceReservation candidate : candidates) {
            if (candidate == sameSale) continue;
            if (candidate.getStatus() == LocalMemberBalanceReservationStatus.PREPARED
                    || candidate.getStatus() == LocalMemberBalanceReservationStatus.TICKET_COMMITTED
                    || candidate.getStatus() == LocalMemberBalanceReservationStatus.FINALIZE_PENDING
                    || candidate.getStatus() == LocalMemberBalanceReservationStatus.ABORT_PENDING
                    || candidate.getStatus() == LocalMemberBalanceReservationStatus.RELEASE_PENDING) {
                RetryResolution protocol = recoverProtocol(candidate, storeId, terminalId);
                if (protocol != null) {
                    recordRetry(protocol.outcome().name(), candidate, protocol.message(),
                            storeId, terminalId, memberId, saleId, released);
                    if (protocol.outcome() != RetryOutcome.RECOVERED) return protocol;
                }
                continue;
            }
            if (!candidate.isActive()) continue;
            RetryResolution activeSession = recoverActiveSession(candidate, storeId, terminalId, true);
            if (activeSession != null) {
                recordRetry(activeSession.outcome().name(), candidate, activeSession.message(),
                        storeId, terminalId, memberId, saleId, released);
                if (activeSession.outcome() != RetryOutcome.RECOVERED) return activeSession;
                // Recovery closed the old reservation. Never release it through
                // the orphan takeover path even if this persistence context has
                // not refreshed the entity yet.
                continue;
            }
            if (hasBlockingPaymentSession(candidate)) {
                blocking = candidate;
                break;
            }
            if (!orphanTakeoverEligible(candidate)) {
                // A recent lease without a durable payment session is not
                // evidence that the current sale acquired it. Keep the
                // candidate as diagnostic blocker only, so the frontend can
                // continue this sale without falsely classifying it as
                // uncertain/not-finalizable.
                RetryResolution result = new RetryResolution(RetryOutcome.BLOCKED_LIVE_SALE,
                        null, saleId, candidate.getId(), candidate.getSaleId(),
                        "member_balance_retry_recent_reservation");
                recordRetry(result.outcome().name(), candidate, result.message(),
                        storeId, terminalId, memberId, saleId, released);
                return result;
            }
            if (audit == null) {
                RetryResolution result = new RetryResolution(RetryOutcome.UNAVAILABLE,
                        null, saleId, candidate.getId(), candidate.getSaleId(),
                        "member_balance_retry_audit_unavailable");
                return result;
            }
            // The retry action is an audited takeover only after the
            // reservation has missed two heartbeats (or its lease expired).
            // An ACTIVE reservation without a session may still belong to a
            // live POS tab, so recent heartbeats remain pending.
            try {
                LocalMemberBalanceReservation releasedReservation = releaseInternal(candidate, false);
                if (!releasedReservation.isClosed()) {
                    blocking = candidate;
                    break;
                }
                released.add(candidate);
            } catch (MemberBalanceCentralException exception) {
                RetryResolution result = centralConflict(exception, candidate);
                recordRetry(result.outcome().name(), candidate, result.message(),
                        storeId, terminalId, memberId, saleId, released);
                return result;
            }
        }
        if (blocking != null) {
            recordRetry(RetryOutcome.BLOCKED_LIVE_SALE.name(), blocking,
                    "member_balance_retry_blocked_live_sale", storeId, terminalId, memberId, saleId,
                    released);
            return new RetryResolution(RetryOutcome.BLOCKED_LIVE_SALE, null, saleId, blocking.getId(),
                    blocking.getSaleId(), "member_balance_retry_blocked_live_sale");
        }

        try {
            LocalMemberBalanceReservation recovered = reserve(storeId, terminalId, memberId, saleId);
            recordRetry("RECOVERED", recovered, null, storeId, terminalId, memberId, saleId, released);
            return RetryResolution.recovered(recovered);
        } catch (MemberBalanceCentralException exception) {
            RetryResolution result = centralConflict(exception, null);
            recordRetry(result.outcome().name(), null, result.message(),
                    storeId, terminalId, memberId, saleId, released);
            return result;
        }
    }

    private RetryResolution recoverProtocol(
            LocalMemberBalanceReservation reservation,
            UUID storeId,
            UUID terminalId) {
        if (reservation.getStatus() == LocalMemberBalanceReservationStatus.RELEASE_PENDING) {
            try {
                LocalMemberBalanceReservation released = releaseInternal(reservation, false);
                if (released.isClosed()) {
                    return new RetryResolution(RetryOutcome.RECOVERED, null, null,
                            null, null, null);
                }
            } catch (RuntimeException ignored) {
                // Keep the local release protocol pending for the next retry.
            }
            return new RetryResolution(RetryOutcome.RECOVERY_PENDING, reservation.getId(),
                    reservation.getSaleId(), null, null, "member_balance_retry_release_pending");
        }
        if (paymentSessions == null || paymentSessionService == null) {
            return new RetryResolution(RetryOutcome.RECOVERY_PENDING, reservation.getId(),
                    reservation.getSaleId(), null, null, "member_balance_retry_protocol_pending");
        }
        var session = paymentSessions.findFirstByMemberBalanceReservationIdOrderByUpdatedAtDesc(
                reservation.getId()).orElse(null);
        if (session == null || !storeId.equals(session.getStoreId())
                || !terminalId.equals(session.getTerminalId())) {
            return new RetryResolution(RetryOutcome.RECOVERY_PENDING, reservation.getId(),
                    reservation.getSaleId(), null, null, "member_balance_retry_protocol_pending");
        }
        return recoverPaymentSession(reservation, session);
    }

    /**
     * ACTIVE is not synonymous with a live payment. A durable session may have
     * already finalized or been cancelled while the local lease was left
     * ACTIVE, so resolve that protocol before considering an orphan takeover.
     */
    private RetryResolution recoverActiveSession(
            LocalMemberBalanceReservation reservation,
            UUID storeId,
            UUID terminalId,
            boolean failClosedWhenUnavailable) {
        if (paymentSessions == null || paymentSessionService == null) {
            if (!failClosedWhenUnavailable) return null;
            return new RetryResolution(RetryOutcome.BLOCKED_LIVE_SALE, reservation.getId(),
                    reservation.getSaleId(), reservation.getId(), reservation.getSaleId(),
                    "member_balance_retry_blocked_live_sale");
        }
        var session = paymentSessions.findFirstByMemberBalanceReservationIdOrderByUpdatedAtDesc(
                reservation.getId()).orElse(null);
        if (session == null) return null;
        if (!storeId.equals(session.getStoreId()) || !terminalId.equals(session.getTerminalId())) {
            return new RetryResolution(RetryOutcome.RECOVERY_PENDING, reservation.getId(),
                    reservation.getSaleId(), null, null, "member_balance_retry_protocol_pending");
        }
        return recoverPaymentSession(reservation, session);
    }

    private RetryResolution recoverPaymentSession(
            LocalMemberBalanceReservation reservation,
            SalePaymentSession session) {
        try {
            if (session.getStatus() == SalePaymentSessionStatus.FINALIZED && session.getTicketId() != null) {
                paymentSessionService.recoverMemberBalanceFinalization(session.getId());
            } else if (session.getStatus() == SalePaymentSessionStatus.CANCELLED) {
                paymentSessionService.recoverMemberBalanceAbort(session.getId());
            } else if (session.getStatus() == SalePaymentSessionStatus.COLLECTING
                    || session.getStatus() == SalePaymentSessionStatus.COVERED
                    || session.getStatus() == SalePaymentSessionStatus.COMPENSATION_REQUIRED) {
                return new RetryResolution(RetryOutcome.BLOCKED_LIVE_SALE, reservation.getId(),
                        reservation.getSaleId(), reservation.getId(), reservation.getSaleId(),
                        "member_balance_retry_blocked_live_sale");
            } else {
                return new RetryResolution(RetryOutcome.RECOVERY_PENDING, reservation.getId(),
                        reservation.getSaleId(), null, null, "member_balance_retry_protocol_pending");
            }
        } catch (RuntimeException ignored) {
            return new RetryResolution(RetryOutcome.RECOVERY_PENDING, reservation.getId(),
                    reservation.getSaleId(), null, null,
                    session.getStatus() == SalePaymentSessionStatus.CANCELLED
                            ? "member_balance_retry_abort_pending"
                            : "member_balance_retry_finalization_pending");
        }
        return reservations.findById(reservation.getId()).filter(LocalMemberBalanceReservation::isClosed)
                .map(ignored -> new RetryResolution(RetryOutcome.RECOVERED, null, null,
                        null, null, null)).orElseGet(() -> new RetryResolution(
                                RetryOutcome.RECOVERY_PENDING, reservation.getId(), reservation.getSaleId(),
                                null, null, "member_balance_retry_protocol_pending"));
    }

    private boolean hasBlockingPaymentSession(LocalMemberBalanceReservation reservation) {
        if (paymentSessions == null || paymentSessionService == null) return true;
        return !paymentSessions.findBlockingMemberBalanceSessionsByReservationIds(
                List.of(reservation.getId())).isEmpty();
    }

    private boolean orphanTakeoverEligible(LocalMemberBalanceReservation reservation) {
        Instant now = clock.instant();
        if (reservation.leaseExpiredAt(now)) {
            return true;
        }
        Instant heartbeatAt = reservation.getHeartbeatAt();
        return heartbeatAt != null
                && !heartbeatAt.isAfter(now.minusSeconds(ORPHAN_HEARTBEAT_GRACE_SECONDS));
    }

    private RetryResolution centralConflict(
            MemberBalanceCentralException exception,
            LocalMemberBalanceReservation reservation) {
        RetryOutcome outcome = reservation != null && !reservation.isActive()
                ? RetryOutcome.RECOVERY_PENDING : RetryOutcome.UNAVAILABLE;
        // The central conflict response does not carry a verified owner
        // terminal. It can be caused by a same-terminal sale that is not
        // visible in this local projection, so never present it as another
        // terminal without an explicit local cross-terminal match.
        String message = exception instanceof MemberBalanceReservationConflictException
                ? "member_balance_retry_blocking_owner_unidentified"
                : "member_balance_retry_unavailable";
        return new RetryResolution(outcome,
                reservation == null ? null : reservation.getId(),
                reservation == null ? null : reservation.getSaleId(),
                null, null, message);
    }

    private void recordRetry(
            String outcome,
            LocalMemberBalanceReservation reservation,
            String message,
            UUID storeId,
            UUID terminalId,
            UUID memberId,
            String currentSaleId,
            List<LocalMemberBalanceReservation> released) {
        if (audit == null) return;
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("outcome", outcome);
        details.put("storeId", storeId.toString());
        details.put("terminalId", terminalId.toString());
        details.put("memberId", memberId.toString());
        details.put("currentSaleId", currentSaleId);
        if (reservation != null) {
            details.put("reservationId", reservation.getId().toString());
            details.put("saleId", reservation.getSaleId());
            if (currentSaleId.equals(reservation.getSaleId())) {
                details.put("newReservationId", reservation.getId().toString());
                details.put("newSaleId", currentSaleId);
            }
        }
        if (released != null && !released.isEmpty()) {
            var orphan = released.get(0);
            details.put("oldReservationId", orphan.getId().toString());
            details.put("oldSaleId", orphan.getSaleId());
            details.put("releasedReservationIds", released.stream()
                    .map(value -> value.getId().toString()).toList());
            details.put("releasedSaleIds", released.stream()
                    .map(LocalMemberBalanceReservation::getSaleId).toList());
        }
        if (message != null) details.put("message", message);
        audit.record("MEMBER_BALANCE_RETRY_RESOLUTION", AuditResult.EXITO, Map.copyOf(details));
    }

    public enum RetryOutcome {
        RECOVERED,
        BLOCKED_OTHER_TERMINAL,
        BLOCKED_LIVE_SALE,
        RECOVERY_PENDING,
        UNAVAILABLE
    }

    public record RetryResolution(
            RetryOutcome outcome,
            UUID reservationId,
            String saleId,
            UUID blockingReservationId,
            String blockingSaleId,
            String message) {
        static RetryResolution recovered(LocalMemberBalanceReservation value) {
            return new RetryResolution(RetryOutcome.RECOVERED, value.getId(),
                    value.getSaleId(), null, null, null);
        }
    }

    @Transactional
    public LocalMemberBalanceReservation configureRetention(
            UUID reservationId,
            UUID storeId,
            UUID terminalId,
            String saleId,
            UUID sourceDocumentId,
            java.util.List<PaymentTerminalRefundLineSelection> selections) {
        if (documents == null || returnValuations == null || retentionPlanner == null) {
            throw new MemberBalanceCentralException(
                    MemberBalanceCentralException.Kind.UNAVAILABLE,
                    "La valoracion de devolucion no esta disponible");
        }
        LocalMemberBalanceReservation reservation = ownedForUpdate(
                reservationId, storeId, terminalId, saleId);
        if (!reservation.isActive()) {
            throw new IllegalStateException("La retencion solo puede configurarse en una reserva activa");
        }
        var source = documents.findByIdAndTiendaId(sourceDocumentId, storeId)
                .orElseThrow(() -> new IllegalArgumentException("Documento de origen no encontrado"));
        var loyaltySource = loyaltySource(source, storeId);
        MemberReturnBalanceRetentionPlanner.Plan plan;
        if (selections == null || selections.isEmpty()) {
            plan = MemberReturnBalanceRetentionPlanner.Plan.none(loyaltySource.getId());
        } else {
            var selected = selections.stream().collect(java.util.stream.Collectors.toMap(
                    PaymentTerminalRefundLineSelection::lineId,
                    PaymentTerminalRefundLineSelection::quantity,
                    BigDecimal::add,
                    java.util.LinkedHashMap::new));
            var valuation = returnValuations.value(source, selected);
            plan = retentionPlanner.plan(loyaltySource,
                    valuation.cumulativeRefundableAmount(),
                    valuation.cumulativeEligibleRefundableAmount());
        }
        if (plan.memberId() != null && !reservation.getMemberId().equals(plan.memberId())) {
            // A retention plan for another member must never be converted into
            // an empty configure: doing so would erase the claims belonging to
            // the source ticket and leave the central hold unprotected.
            throw new MemberBalanceCentralException(
                    MemberBalanceCentralException.Kind.CONFLICT,
                    "member_balance_retention_member_mismatch");
        }
        if (!plan.claims().isEmpty() && plan.memberId() == null) {
            throw new MemberBalanceCentralException(
                    MemberBalanceCentralException.Kind.INVALID_RESPONSE,
                    "member_balance_retention_contract_incompatible");
        }
        if (plan.claims().isEmpty() || plan.attributedAmount().signum() == 0) {
            // A zero plan is still an authoritative replacement. The central
            // side may have committed a previous snapshot while the local
            // response was lost, so even a local revision of zero must retry
            // the canonical empty configure.
            var cleared = coordinator.configureRetention(
                    reservation.getCentralReservationId(), storeId, terminalId, saleId,
                    operationId(saleId), loyaltySource.getId(), BigDecimal.ZERO.setScale(2),
                    java.util.List.of());
            reservation.apply(cleared, clock.instant());
            return reservations.save(reservation);
        }
        var central = coordinator.configureRetention(
                reservation.getCentralReservationId(), storeId, terminalId, saleId,
                operationId(saleId), plan.sourceDocumentId(), plan.attributedAmount(), plan.claims());
        reservation.apply(central, clock.instant());
        return reservations.save(reservation);
    }

    public LocalMemberBalanceReservation reserve(
            UUID storeId,
            UUID terminalId,
            UUID memberId,
            String saleId) {
        requireTerminal(storeId, terminalId);
        Instant now = clock.instant();
        LocalMemberBalanceReservation previous = reservations
                .findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(storeId, terminalId, saleId)
                .orElse(null);
        if (previous != null && previous.leaseExpiredAt(now)) {
            previous.markExpired(now);
            reservations.save(previous);
        }
        if (previous != null && previous.isActive() && previous.getMemberId().equals(memberId)) {
            MemberBalanceCentralGateway.ReservationResponse central = coordinator.reserve(
                    storeId, terminalId, memberId, saleId);
            previous.apply(central, now);
            return reservations.save(previous);
        }
        if (previous != null && !previous.isClosed()) {
            LocalMemberBalanceReservation released = releaseInternal(previous, false);
            if (!released.isClosed()) {
                throw new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.UNAVAILABLE,
                        "La reserva anterior queda pendiente de liberacion");
            }
        }

        MemberBalanceCentralGateway.ReservationResponse central = coordinator.reserve(
                storeId, terminalId, memberId, saleId);
        LocalMemberBalanceReservation reservation = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, saleId, central, now);
        try {
            return reservations.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException exception) {
            return reservations.findByCentralReservationId(central.reservationId()).orElseThrow(() -> exception);
        }
    }

    @Transactional
    public LocalMemberBalanceReservation heartbeat(
            UUID reservationId,
            UUID storeId,
            UUID terminalId,
            String saleId) {
        requireTerminal(storeId, terminalId);
        LocalMemberBalanceReservation reservation = ownedForUpdate(
                reservationId, storeId, terminalId, saleId);
        Instant now = clock.instant();
        if (reservation.leaseExpiredAt(now)) {
            reservation.markExpired(now);
            throw new MemberBalanceCentralException(
                    MemberBalanceCentralException.Kind.CONFLICT,
                    "La reserva local ha superado su lease");
        }
        if (!reservation.isActive()) {
            throw new IllegalStateException("La reserva de saldo socio ya no esta activa");
        }
        try {
            reservation.apply(coordinator.heartbeat(
                    reservation.getCentralReservationId(), storeId, terminalId, saleId), now);
            return reservation;
        } catch (MemberBalanceCentralException exception) {
            if (exception.getKind() == MemberBalanceCentralException.Kind.CONFLICT) {
                reservation.markExpired(now);
                return reservations.save(reservation);
            }
            throw exception;
        }
    }

    @Transactional
    public LocalMemberBalanceReservation release(
            UUID reservationId,
            UUID storeId,
            UUID terminalId,
            String saleId) {
        requireTerminal(storeId, terminalId);
        return releaseInternal(ownedForUpdate(reservationId, storeId, terminalId, saleId), true);
    }

    private LocalMemberBalanceReservation releaseInternal(
            LocalMemberBalanceReservation reservation,
            boolean tolerateUnavailable) {
        if (reservation.isClosed()) {
            return reservation;
        }
        if (!reservation.isActive()
                && reservation.getStatus() != LocalMemberBalanceReservationStatus.RELEASE_PENDING) {
            // A sale that is already prepared/committed has crossed the
            // release boundary. Keep its durable protocol state untouched and
            // never send a generic release to the central wallet.
            return reservation;
        }
        Instant now = clock.instant();
        if (reservation.leaseExpiredAt(now)) {
            reservation.markExpired(now);
            return reservations.save(reservation);
        }
        try {
            reservation.apply(coordinator.release(
                    reservation.getCentralReservationId(),
                    reservation.getStoreId(),
                    reservation.getTerminalId(),
                    reservation.getSaleId()), now);
            return reservations.save(reservation);
        } catch (MemberBalanceCentralException exception) {
            if (exception.getStatusCode() != null && exception.getStatusCode() == 404) {
                reservation.markReleaseConfirmed(now);
                return reservations.save(reservation);
            }
            if (exception.getKind() == MemberBalanceCentralException.Kind.UNAVAILABLE) {
                reservation.markReleasePending(now);
                LocalMemberBalanceReservation saved = reservations.save(reservation);
                if (tolerateUnavailable) {
                    return saved;
                }
            }
            throw exception;
        }
    }

    private LocalMemberBalanceReservation ownedForUpdate(
            UUID reservationId,
            UUID storeId,
            UUID terminalId,
            String saleId) {
        LocalMemberBalanceReservation reservation = reservations.findForUpdate(reservationId)
                .orElseThrow(() -> new NoSuchElementException("Reserva de saldo socio no encontrada"));
        if (!reservation.matches(storeId, terminalId, saleId)) {
            throw new IllegalStateException("La reserva no pertenece a esta venta y terminal");
        }
        return reservation;
    }

    private void requireTerminal(UUID storeId, UUID terminalId) {
        if (storeId == null || terminalId == null
                || terminals.findByIdAndTiendaId(terminalId, storeId).isEmpty()) {
            throw new IllegalArgumentException("El terminal no pertenece a la tienda indicada");
        }
    }

    private static UUID operationId(String saleId) {
        try {
            return UUID.fromString(saleId);
        } catch (IllegalArgumentException ignored) {
            return UUID.nameUUIDFromBytes(saleId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private CommercialDocument loyaltySource(CommercialDocument source, UUID storeId) {
        if (source.getTipo() != CommercialDocumentType.FACTURA_VENTA
                || !source.isSettledByOrigin()) {
            return source;
        }
        if (documentRelations == null) {
            throw new MemberBalanceCentralException(
                    MemberBalanceCentralException.Kind.UNAVAILABLE,
                    "La relacion del ticket origen no esta disponible");
        }
        UUID originId = documentRelations.findOriginId(source.getId(), DocumentRelationType.FACTURA_DE)
                .orElseThrow(() -> new IllegalStateException(
                        "La factura no conserva el ticket origen de sus pagos"));
        var origin = documents.findByIdAndTiendaId(originId, storeId)
                .orElseThrow(() -> new IllegalStateException("El ticket origen de la factura no existe"));
        if (origin.getTipo() != CommercialDocumentType.TICKET) {
            throw new IllegalStateException("La relacion FACTURA_DE no apunta a un ticket origen");
        }
        return origin;
    }
}
