package com.tpverp.saas.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.loyalty.MemberBalanceType;
import com.tpverp.saas.loyalty.SaasMemberBalanceAccount;
import com.tpverp.saas.loyalty.SaasMemberBalanceAccountRepository;
import com.tpverp.saas.loyalty.SaasMemberBalanceLot;
import com.tpverp.saas.loyalty.SaasMemberBalanceLotRepository;
import com.tpverp.saas.loyalty.SaasMemberBalanceReservation;
import com.tpverp.saas.loyalty.SaasMemberBalanceReservationLot;
import com.tpverp.saas.loyalty.SaasMemberBalanceReservationLotRepository;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionClaim;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionClaimRepository;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionClaimStatus;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionReceipt;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Comparator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberWalletSyncProjector {

    static final String ENTITY_TYPE = "MEMBER_WALLET_LOT";
    private static final Instant MIN_CIVIL_INSTANT = Instant.parse("0000-01-01T00:00:00Z");
    private static final Instant MAX_CIVIL_INSTANT =
            Instant.parse("9999-12-31T23:59:59.999999999Z");

    private final SaasMemberBalanceAccountRepository accounts;
    private final SaasMemberBalanceLotRepository lots;
    private final ObjectMapper mapper;
    private SaasMemberBalanceRetentionClaimRepository retentionClaims;
    private SaasMemberBalanceReservationLotRepository reservationLots;

    @Autowired
    public MemberWalletSyncProjector(
            SaasMemberBalanceAccountRepository accounts,
            SaasMemberBalanceLotRepository lots,
            ObjectMapper mapper) {
        this.accounts = accounts;
        this.lots = lots;
        this.mapper = mapper;
    }

    public MemberWalletSyncProjector(
            SaasMemberBalanceAccountRepository accounts,
            SaasMemberBalanceLotRepository lots,
            ObjectMapper mapper,
            SaasMemberBalanceRetentionClaimRepository retentionClaims,
            SaasMemberBalanceReservationLotRepository reservationLots) {
        this(accounts, lots, mapper);
        this.retentionClaims = retentionClaims;
        this.reservationLots = reservationLots;
    }

    @Autowired
    void setRetentionRepositories(
            SaasMemberBalanceRetentionClaimRepository retentionClaims,
            SaasMemberBalanceReservationLotRepository reservationLots) {
        this.retentionClaims = retentionClaims;
        this.reservationLots = reservationLots;
    }

    public boolean supports(String entityType, SyncOperation operation) {
        return ENTITY_TYPE.equals(entityType) && operation == SyncOperation.CREAR;
    }

    public void project(SaasSyncEvent event, Map<String, Object> payload, Instant now) {
        WalletLotCreation creation = parse(payload);
        UUID companyId = event.getCompany().getId();
        UUID lotId = event.getEntityId();

        lockCreationKeys(companyId, creation.memberId(), lotId, creation.sourceMovementId());

        SaasMemberBalanceLot existingLot = lots.findById(lotId).orElse(null);
        if (existingLot != null) {
            if (sameImmutableLot(existingLot, companyId, creation)) {
                applyExistingClaimIfNeeded(existingLot, companyId, creation, now);
                return;
            }
            throw conflict("El lotId ya existe con datos diferentes: " + lotId);
        }

        SaasMemberBalanceLot existingMovement = lots
                .findByCompanyIdAndSourceMovementId(companyId, creation.sourceMovementId())
                .orElse(null);
        if (existingMovement != null) {
            throw conflict("El sourceMovementId ya pertenece a otro lote: "
                    + creation.sourceMovementId());
        }

        List<SaasMemberBalanceRetentionClaim> claims = orderedRetentionClaims(findRetentionClaims(
                lotId, creation.sourceMovementId(), companyId, creation.memberId()));
        // ProjectionException is deliberately not rollback-only in the sync
        // pipeline. Validate every claim before phase 2 releases stale
        // reservations or mutates any lot/account state.
        claims.forEach(claim -> validateClaim(claim, lotId, companyId, creation));
        claims = releaseInactiveReservationClaims(claims, now);
        List<RetentionAllocation> allocations = allocateClaims(claims, creation.amount());
        BigDecimal allocatedPending = allocations.stream()
                .filter(RetentionAllocation::pending)
                .map(RetentionAllocation::allocated)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        BigDecimal creditedAmount = creation.amount().subtract(allocatedPending);
        List<RetentionAllocation> activeMissingAllocations = allocations.stream()
                .filter(value -> !value.pending()
                        && value.claim().getReservation() != null
                        && value.claim().getReservation().isActive()
                        && !value.claim().getReservation().isExpiredAt(now))
                .toList();
        // Claim validation and capacity planning are complete. Phase 2 now
        // applies stale-claim releases and the planned lot/account changes.
        SaasMemberBalanceAccount account = accounts
                .findForUpdate(companyId, creation.memberId())
                .orElseGet(() -> new SaasMemberBalanceAccount(
                        UUID.randomUUID(),
                        companyId,
                        creation.memberId(),
                        BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO.setScale(4),
                        now));
        allocations.forEach(allocation -> applyAllocation(allocation, now));
        refreshReceipts(claims, now);
        if (creditedAmount.signum() > 0) {
            account.credit(creation.balanceType(), creditedAmount, now);
        }
        accounts.save(account);
        SaasMemberBalanceLot savedLot = lots.save(new SaasMemberBalanceLot(
                lotId,
                account,
                creation.balanceType(),
                creation.amount(),
                creditedAmount,
                creation.createdAt(),
                creation.expiresAt(),
                creation.sourceMovementId(),
                creation.documentId()));
        if (!activeMissingAllocations.isEmpty()) {
            incorporateActiveReservationClaims(activeMissingAllocations, savedLot, now);
        }
    }

    private void lockCreationKeys(
            UUID companyId,
            UUID memberId,
            UUID lotId,
            UUID sourceMovementId) {
        List.of(
                        "COMPANY:" + companyId,
                        "ACCOUNT:" + companyId + ":" + memberId,
                        "LOT:" + lotId,
                        "SOURCE:" + companyId + ":" + sourceMovementId)
                .stream()
                .sorted()
                .forEach(lockKey -> {
                    accounts.ensureProjectionLock(lockKey);
                    accounts.lockProjectionKey(lockKey);
                });
    }

    private static boolean sameImmutableLot(
            SaasMemberBalanceLot lot,
            UUID companyId,
            WalletLotCreation creation) {
        return lot.getCompanyId().equals(companyId)
                && lot.getMemberId().equals(creation.memberId())
                && lot.getBalanceType() == creation.balanceType()
                && lot.getOriginalAmount().compareTo(creation.amount()) == 0
                && samePostgresInstant(lot.getCreatedAt(), creation.createdAt())
                && samePostgresInstant(lot.getExpiresAt(), creation.expiresAt())
                && Objects.equals(lot.getSourceMovementId(), creation.sourceMovementId())
                && Objects.equals(lot.getDocumentId(), creation.documentId());
    }

    private static boolean samePostgresInstant(Instant left, Instant right) {
        return left == null ? right == null
                : right != null
                        && left.truncatedTo(ChronoUnit.MICROS)
                                .equals(right.truncatedTo(ChronoUnit.MICROS));
    }

    private WalletLotCreation parse(Map<String, Object> payload) {
        requireSchemaVersion(payload);
        UUID memberId = requiredUuid(payload, "memberId");
        MemberBalanceType balanceType = requiredBalanceType(payload);
        BigDecimal amount = requiredAmount(payload);
        Instant createdAt = requiredInstant(payload, "createdAt");
        Instant expiresAt = optionalInstant(payload, "expiresAt");
        UUID sourceMovementId = requiredUuid(payload, "sourceMovementId");
        UUID documentId = optionalUuid(payload, "documentId");
        return new WalletLotCreation(
                memberId,
                balanceType,
                amount,
                createdAt,
                expiresAt,
                sourceMovementId,
                documentId);
    }

    private List<SaasMemberBalanceRetentionClaim> findRetentionClaims(
            UUID lotId, UUID sourceMovementId, UUID companyId, UUID memberId) {
        if (retentionClaims == null) return List.of();
        List<SaasMemberBalanceRetentionClaim> candidates = retentionClaims.findByLotIdAndSourceMovementIdAndStatusIn(
                lotId, sourceMovementId,
                List.of(SaasMemberBalanceRetentionClaimStatus.COMMITTED_PENDING,
                        SaasMemberBalanceRetentionClaimStatus.HELD_MISSING,
                        SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN));
        if (candidates == null) return List.of();
        return candidates.stream()
                .filter(claim -> claim.getReservation() != null
                        ? claim.getReservation().getAccount().getCompanyId().equals(companyId)
                                && claim.getReservation().getAccount().getMemberId().equals(memberId)
                        : claim.getReceipt() != null
                                && claim.getReceipt().getCompanyId().equals(companyId)
                                && claim.getReceipt().getMemberId().equals(memberId))
                .toList();
    }

    private void applyExistingClaimIfNeeded(
            SaasMemberBalanceLot existingLot, UUID companyId, WalletLotCreation creation, Instant now) {
        List<SaasMemberBalanceRetentionClaim> claims = orderedRetentionClaims(findRetentionClaims(
                existingLot.getId(), creation.sourceMovementId(), companyId, creation.memberId()));
        if (claims.isEmpty()) return;
        claims.forEach(claim -> validateClaim(claim, existingLot.getId(), companyId, creation));
        claims = releaseInactiveReservationClaims(claims, now);
        List<RetentionAllocation> allocations = allocateClaims(claims, existingLot.getRemainingAmount());
        BigDecimal allocatedPending = allocations.stream()
                .filter(RetentionAllocation::pending)
                .map(RetentionAllocation::allocated)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        if (allocatedPending.signum() > 0) {
            SaasMemberBalanceAccount account = accounts
                    .findForUpdate(companyId, creation.memberId())
                    .orElseThrow(() -> conflict("Cuenta de miembro no encontrada para retencion"));
            existingLot.consume(allocatedPending);
            account.debit(creation.balanceType(), allocatedPending, now);
        }
        allocations.forEach(allocation -> applyAllocation(allocation, now));
        refreshReceipts(claims, now);
        incorporateActiveReservationClaims(allocations, existingLot, now);
    }

    private List<SaasMemberBalanceRetentionClaim> releaseInactiveReservationClaims(
            List<SaasMemberBalanceRetentionClaim> values, Instant now) {
        values.stream()
                .filter(claim -> claim.getStatus() == SaasMemberBalanceRetentionClaimStatus.HELD_MISSING
                        || claim.getStatus() == SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN)
                .filter(claim -> {
                    SaasMemberBalanceReservation reservation = claim.getReservation();
                    return reservation == null || !reservation.isActive()
                            || reservation.isExpiredAt(now);
                })
                .forEach(claim -> claim.release(now));
        return values.stream()
                .filter(claim -> claim.getStatus() != SaasMemberBalanceRetentionClaimStatus.CANCELLED)
                .toList();
    }

    private void incorporateActiveReservationClaims(
            List<RetentionAllocation> allocations,
            SaasMemberBalanceLot lot,
            Instant now) {
        if (reservationLots == null || lot == null) return;
        allocations.stream()
                .filter(value -> !value.pending() && value.allocated().signum() > 0)
                .map(RetentionAllocation::claim)
                .filter(claim -> claim.getReservation() != null)
                .filter(claim -> claim.getReservation().isActive()
                        && !claim.getReservation().isExpiredAt(now))
                .forEach(claim -> {
                    SaasMemberBalanceReservation reservation = claim.getReservation();
                    BigDecimal held = claim.getHeldAmount();
                    SaasMemberBalanceReservationLot link = reservationLots
                            .findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservation.getId())
                            .stream().filter(value -> value.getLot().getId().equals(lot.getId()))
                            .findFirst().orElse(null);
                    BigDecimal linked = link == null ? BigDecimal.ZERO.setScale(2)
                            : link.getRemainingAmount();
                    BigDecimal delta = held.subtract(linked).max(BigDecimal.ZERO.setScale(2));
                    if (delta.signum() <= 0) return;
                    reservation.incorporateWalletLot(lot.getBalanceType(), delta);
                    if (link == null) {
                        reservationLots.save(new SaasMemberBalanceReservationLot(
                                UUID.randomUUID(), reservation, lot, held));
                    } else {
                        link.incorporate(delta);
                        reservationLots.save(link);
                    }
                });
    }

    private List<RetentionAllocation> allocateClaims(
            List<SaasMemberBalanceRetentionClaim> claims, BigDecimal availableAmount) {
        BigDecimal available = availableAmount.setScale(2, RoundingMode.UNNECESSARY);
        java.util.ArrayList<RetentionAllocation> result = new java.util.ArrayList<>(claims.size());
        for (SaasMemberBalanceRetentionClaim claim : claims) {
            boolean pending = claim.getStatus() == SaasMemberBalanceRetentionClaimStatus.COMMITTED_PENDING;
            BigDecimal allocated = claim.getHeldAmount().min(available);
            result.add(new RetentionAllocation(claim, allocated, pending));
            available = available.subtract(allocated);
        }
        return List.copyOf(result);
    }

    private void applyAllocation(RetentionAllocation allocation, Instant now) {
        SaasMemberBalanceRetentionClaim claim = allocation.claim();
        claim.setHeldAmount(allocation.allocated());
        if (allocation.pending()) {
            claim.apply(now);
            return;
        }
        SaasMemberBalanceReservation reservation = claim.getReservation();
        if (reservation != null && reservation.isActive() && !reservation.isExpiredAt(now)) {
            claim.markHeldKnown(now);
        } else {
            claim.release(now);
        }
    }

    private List<SaasMemberBalanceRetentionClaim> orderedRetentionClaims(
            List<SaasMemberBalanceRetentionClaim> values) {
        return values.stream().sorted(Comparator
                .comparing((SaasMemberBalanceRetentionClaim value) ->
                        value.getStatus() == SaasMemberBalanceRetentionClaimStatus.COMMITTED_PENDING ? 0 : 1)
                .thenComparing((SaasMemberBalanceRetentionClaim value) -> value.getReceipt() == null
                        ? "" : value.getReceipt().getOperationId().toString())
                .thenComparing(value -> value.getId().toString())).toList();
    }

    private void refreshReceipts(List<SaasMemberBalanceRetentionClaim> touched, Instant now) {
        if (retentionClaims == null) return;
        touched.stream()
                .map(SaasMemberBalanceRetentionClaim::getReceipt)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toMap(
                        SaasMemberBalanceRetentionReceipt::getOperationId, value -> value,
                        (first, ignored) -> first))
                .values()
                .forEach(receipt -> {
                    List<SaasMemberBalanceRetentionClaim> receiptClaims =
                            retentionClaims.findByReceipt_OperationIdOrderByLotIdAsc(
                                    receipt.getOperationId());
                    // A projection test/delivery can observe the receipt
                    // before its claim rows are visible. Do not replace a
                    // conserved metric snapshot with an all-zero value.
                    if (receiptClaims == null || receiptClaims.isEmpty()) return;
                    BigDecimal recovered = receiptClaims.stream()
                            .filter(claim -> claim.getStatus() == SaasMemberBalanceRetentionClaimStatus.APPLIED)
                            .map(SaasMemberBalanceRetentionClaim::getHeldAmount)
                            .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
                    BigDecimal pending = receiptClaims.stream()
                            .filter(claim -> claim.getStatus() == SaasMemberBalanceRetentionClaimStatus.COMMITTED_PENDING
                                    || claim.getStatus() == SaasMemberBalanceRetentionClaimStatus.HELD_MISSING)
                            .map(SaasMemberBalanceRetentionClaim::getHeldAmount)
                            .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
                    BigDecimal shortfall = receiptClaims.stream()
                            .filter(claim -> claim.getStatus() == SaasMemberBalanceRetentionClaimStatus.APPLIED)
                            .map(claim -> claim.getAmount().subtract(claim.getHeldAmount()))
                            .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
                    receipt.replaceMetrics(recovered, pending, shortfall, now);
                });
    }

    private void validateClaim(
            SaasMemberBalanceRetentionClaim claim, UUID lotId,
            UUID companyId, WalletLotCreation creation) {
        SaasMemberBalanceReservation reservation = claim.getReservation();
        boolean ownerMatches = reservation != null
                ? reservation.getAccount().getCompanyId().equals(companyId)
                        && reservation.getAccount().getMemberId().equals(creation.memberId())
                : claim.getReceipt() != null
                        && claim.getReceipt().getCompanyId().equals(companyId)
                        && claim.getReceipt().getMemberId().equals(creation.memberId());
        if (!ownerMatches
                || !claim.getLotId().equals(lotId)
                || !claim.getSourceMovementId().equals(creation.sourceMovementId())
                || !Objects.equals(claim.getSourceDocumentId(), creation.documentId())
                || claim.getAmountOriginal().compareTo(creation.amount()) != 0
                || claim.getHeldAmount().signum() < 0) {
            throw conflict("El claim de retencion no coincide con el lote entrante");
        }
    }

    private record RetentionAllocation(
            SaasMemberBalanceRetentionClaim claim,
            BigDecimal allocated,
            boolean pending) {
    }

    private static void requireSchemaVersion(Map<String, Object> payload) {
        Object raw = payload.get("schemaVersion");
        if (!(raw instanceof Number number)) {
            throw badRequest("schemaVersion debe ser el numero 2");
        }
        try {
            if (new BigDecimal(number.toString()).intValueExact() != 2) {
                throw badRequest("schemaVersion no soportado");
            }
        } catch (ArithmeticException exception) {
            throw badRequest("schemaVersion debe ser el numero 2");
        }
    }

    private static MemberBalanceType requiredBalanceType(Map<String, Object> payload) {
        String value = requiredText(payload, "balanceType");
        try {
            return MemberBalanceType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw badRequest("balanceType debe ser LOYALTY o RETURN_CREDIT");
        }
    }

    private BigDecimal requiredAmount(Map<String, Object> payload) {
        Object raw = payload.get("amount");
        JsonNode node = mapper.valueToTree(raw);
        if (node == null || node.isNull() || (!node.isNumber() && !node.isTextual())) {
            throw badRequest("amount debe ser un numero JSON o un decimal textual");
        }
        String text = node.asText();
        if (text.isBlank()) {
            throw badRequest("amount no puede estar vacio");
        }
        try {
            BigDecimal amount = new BigDecimal(text).setScale(2, RoundingMode.UNNECESSARY);
            if (amount.signum() <= 0) {
                throw badRequest("amount debe ser mayor que cero");
            }
            return amount;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw badRequest("amount debe ser un decimal monetario valido con maximo 2 decimales");
        }
    }

    private static UUID requiredUuid(Map<String, Object> payload, String field) {
        String value = requiredText(payload, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw badRequest(field + " debe ser un UUID valido");
        }
    }

    private static UUID optionalUuid(Map<String, Object> payload, String field) {
        Object raw = payload.get(field);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String value) || value.isBlank()) {
            throw badRequest(field + " debe ser null o un UUID valido");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw badRequest(field + " debe ser null o un UUID valido");
        }
    }

    private static Instant requiredInstant(Map<String, Object> payload, String field) {
        Object raw = payload.get(field);
        if (raw == null) {
            throw badRequest(field + " es obligatorio");
        }
        return parseInstant(raw, field, false);
    }

    private static Instant optionalInstant(Map<String, Object> payload, String field) {
        Object raw = payload.get(field);
        if (raw == null) {
            return null;
        }
        return parseInstant(raw, field, true);
    }

    private static Instant parseInstant(Object raw, String field, boolean optional) {
        try {
            Instant parsed;
            if (raw instanceof String value) {
                if (value.isBlank()) {
                    throw badRequest(instantMessage(field, optional));
                }
                parsed = Instant.parse(value);
            } else if (raw instanceof Number number) {
                if (number instanceof Double doubleValue && !Double.isFinite(doubleValue)
                        || number instanceof Float floatValue && !Float.isFinite(floatValue)) {
                    throw badRequest(instantMessage(field, optional));
                }
                BigDecimal seconds = new BigDecimal(number.toString());
                if (seconds.stripTrailingZeros().scale() > 9) {
                    throw badRequest(field + " epoch debe tener como maximo 9 decimales");
                }
                BigDecimal minimum = BigDecimal.valueOf(MIN_CIVIL_INSTANT.getEpochSecond());
                BigDecimal maximum = BigDecimal.valueOf(MAX_CIVIL_INSTANT.getEpochSecond())
                        .add(BigDecimal.valueOf(MAX_CIVIL_INSTANT.getNano(), 9));
                if (seconds.compareTo(minimum) < 0 || seconds.compareTo(maximum) > 0) {
                    throw badRequest(field + " fuera del rango civil permitido");
                }
                BigDecimal wholeSeconds = seconds.setScale(0, RoundingMode.FLOOR);
                long epochSecond = wholeSeconds.longValueExact();
                int nanos = seconds.subtract(wholeSeconds)
                        .movePointRight(9).intValueExact();
                parsed = Instant.ofEpochSecond(epochSecond, nanos);
            } else {
                throw badRequest(instantMessage(field, optional));
            }
            if (parsed.isBefore(MIN_CIVIL_INSTANT) || parsed.isAfter(MAX_CIVIL_INSTANT)) {
                throw badRequest(field + " fuera del rango civil permitido");
            }
            return parsed;
        } catch (DateTimeParseException | NumberFormatException | ArithmeticException exception) {
            throw badRequest(instantMessage(field, optional));
        }
    }

    private static String instantMessage(String field, boolean optional) {
        return optional
                ? field + " debe ser null, un instante ISO-8601 o epoch-seconds valido"
                : field + " debe ser un instante ISO-8601 o epoch-seconds valido";
    }

    private static String requiredText(Map<String, Object> payload, String field) {
        Object raw = payload.get(field);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw badRequest(field + " es obligatorio");
        }
        return value;
    }

    static ProjectionException conflict(String reason) {
        return new ProjectionException(HttpStatus.CONFLICT, reason);
    }

    private static ProjectionException badRequest(String reason) {
        return new ProjectionException(HttpStatus.BAD_REQUEST, reason);
    }

    private record WalletLotCreation(
            UUID memberId,
            MemberBalanceType balanceType,
            BigDecimal amount,
            Instant createdAt,
            Instant expiresAt,
            UUID sourceMovementId,
            UUID documentId) {
    }

    public static final class ProjectionException extends ResponseStatusException {

        private ProjectionException(HttpStatus status, String reason) {
            super(status, reason);
        }
    }
}
