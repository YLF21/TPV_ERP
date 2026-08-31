package com.tpverp.saas.loyalty;

import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasInstallationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import com.tpverp.saas.sync.MemberReturnBalanceRecoveryCommand;
import com.tpverp.saas.sync.MemberReturnBalanceRecoveryProjector;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberBalanceReservationService {

    static final int HEARTBEAT_INTERVAL_SECONDS = 30;
    static final int LEASE_SECONDS = 120;
    private static final Duration LEASE_DURATION = Duration.ofSeconds(LEASE_SECONDS);

    private final SaasInstallationRepository installations;
    private final InstallationAuthenticator installationAuthenticator;
    private final SaasMemberLoyaltyBootstrapRepository bootstraps;
    private final SaasMemberWalletBootstrapRepository walletBootstraps;
    private final SaasMemberBalanceAccountRepository accounts;
    private final SaasMemberBalanceLotRepository lots;
    private final SaasMemberBalanceReservationRepository reservations;
    private final SaasMemberBalanceReservationLotRepository reservationLots;
    private SaasMemberBalanceRetentionClaimRepository retentionClaims;
    private SaasMemberBalanceRetentionReceiptRepository retentionReceipts;
    private SaasMemberBalanceRetentionReceiptAliasRepository retentionReceiptAliases;
    private MemberReturnBalanceRecoveryProjector retentionReconciler;
    private final EntityManager entityManager;
    private final Clock clock;
    private SaasMemberPointsBootstrapRepository pointsBootstraps;
    private SaasMemberPointsAuthorityRepository pointsAuthorities;

    @Autowired
    void setPointsBootstrapProtection(
            SaasMemberPointsBootstrapRepository pointsBootstraps,
            SaasMemberPointsAuthorityRepository pointsAuthorities) {
        this.pointsBootstraps = pointsBootstraps;
        this.pointsAuthorities = pointsAuthorities;
    }

    @Autowired
    void setRetentionClaims(SaasMemberBalanceRetentionClaimRepository retentionClaims) {
        this.retentionClaims = retentionClaims;
    }

    @Autowired
    void setRetentionReceipts(SaasMemberBalanceRetentionReceiptRepository retentionReceipts) {
        this.retentionReceipts = retentionReceipts;
    }

    @Autowired(required = false)
    void setRetentionReceiptAliases(
            SaasMemberBalanceRetentionReceiptAliasRepository retentionReceiptAliases) {
        this.retentionReceiptAliases = retentionReceiptAliases;
    }

    @Autowired(required = false)
    void setRetentionReconciler(MemberReturnBalanceRecoveryProjector retentionReconciler) {
        this.retentionReconciler = retentionReconciler;
    }

    @Autowired
    public MemberBalanceReservationService(
            SaasInstallationRepository installations,
            InstallationAuthenticator installationAuthenticator,
            SaasMemberLoyaltyBootstrapRepository bootstraps,
            SaasMemberWalletBootstrapRepository walletBootstraps,
            SaasMemberBalanceAccountRepository accounts,
            SaasMemberBalanceLotRepository lots,
            SaasMemberBalanceReservationRepository reservations,
            SaasMemberBalanceReservationLotRepository reservationLots,
            EntityManager entityManager) {
        this(
                installations,
                installationAuthenticator,
                bootstraps,
                walletBootstraps,
                accounts,
                lots,
                reservations,
                reservationLots,
                entityManager,
                Clock.systemUTC());
    }

    public MemberBalanceReservationService(
            SaasInstallationRepository installations,
            InstallationAuthenticator installationAuthenticator,
            SaasMemberLoyaltyBootstrapRepository bootstraps,
            SaasMemberWalletBootstrapRepository walletBootstraps,
            SaasMemberBalanceAccountRepository accounts,
            SaasMemberBalanceLotRepository lots,
            SaasMemberBalanceReservationRepository reservations,
            SaasMemberBalanceReservationLotRepository reservationLots,
            EntityManager entityManager,
            Clock clock) {
        this.installations = installations;
        this.installationAuthenticator = installationAuthenticator;
        this.bootstraps = bootstraps;
        this.walletBootstraps = walletBootstraps;
        this.accounts = accounts;
        this.lots = lots;
        this.reservations = reservations;
        this.reservationLots = reservationLots;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    public MemberBalanceReservationService(
            SaasInstallationRepository installations,
            InstallationAuthenticator installationAuthenticator,
            SaasMemberLoyaltyBootstrapRepository bootstraps,
            SaasMemberWalletBootstrapRepository walletBootstraps,
            SaasMemberBalanceAccountRepository accounts,
            SaasMemberBalanceLotRepository lots,
            SaasMemberBalanceReservationRepository reservations,
            SaasMemberBalanceReservationLotRepository reservationLots,
            SaasMemberBalanceRetentionClaimRepository retentionClaims,
            EntityManager entityManager,
            Clock clock) {
        this(installations, installationAuthenticator, bootstraps, walletBootstraps, accounts,
                lots, reservations, reservationLots, entityManager, clock);
        this.retentionClaims = retentionClaims;
    }

    @Transactional
    public LoyaltyApiModels.BootstrapResponse bootstrap(
            LoyaltyApiModels.BootstrapRequest request,
            String token) {
        requireBootstrapRequest(request);
        requireLegacyPointsWritable(request.companyId());
        SaasInstallation installation = authenticate(request.companyId(), request.storeId(), token);
        entityManager.find(SaasCompany.class, request.companyId(), LockModeType.PESSIMISTIC_WRITE);

        SaasMemberLoyaltyBootstrap bootstrap = bootstraps.findById(request.companyId())
                .orElseThrow(() -> conflict("Debe designarse la tienda fuente antes de iniciar el bootstrap"));
        if (!bootstrap.getSourceStoreId().equals(request.storeId())) {
            throw conflict("Esta tienda no es la fuente designada para el bootstrap de fidelizacion");
        }

        Set<UUID> memberIds = new HashSet<>();
        Set<UUID> lotIds = new HashSet<>();
        for (LoyaltyApiModels.BootstrapAccount source : request.accounts()) {
            validateBootstrapAccount(source, request.snapshotAt(), memberIds, lotIds);
        }

        String calculatedChecksum = calculateChecksum(request);
        if (!calculatedChecksum.equalsIgnoreCase(request.checksum().trim())) {
            throw conflict("El checksum del bootstrap no coincide con su contenido");
        }

        if (bootstrap.isCompleted()) {
            if (!bootstrap.getSourceChecksum().equals(calculatedChecksum)) {
                throw conflict("La fidelizacion ya fue inicializada con un contenido diferente");
            }
            return bootstrapResponse("VERIFIED", bootstrap, request.accounts().size());
        }
        if (accounts.existsByCompanyId(request.companyId())) {
            throw conflict("Existen cuentas centrales sin un bootstrap registrado");
        }

        Instant now = clock.instant();
        for (LoyaltyApiModels.BootstrapAccount source : request.accounts()) {
            SaasMemberBalanceAccount account = accounts.save(new SaasMemberBalanceAccount(
                    UUID.randomUUID(),
                    request.companyId(),
                    source.memberId(),
                    money(source.balance()),
                    points(source.points()),
                    request.snapshotAt()));
            for (LoyaltyApiModels.BootstrapLot sourceLot : safeList(source.lots())) {
                lots.save(new SaasMemberBalanceLot(
                        sourceLot.lotId(),
                        account,
                        money(sourceLot.remainingAmount()),
                        sourceLot.createdAt(),
                        sourceLot.expiresAt(),
                        sourceLot.sourceMovementId()));
            }
        }

        bootstrap.complete(
                installation.getId(),
                calculatedChecksum,
                request.snapshotAt(),
                now);
        return bootstrapResponse("IMPORTED", bootstrap, request.accounts().size());
    }

    @Transactional
    public LoyaltyApiModels.ReservationResponse reserve(
            LoyaltyApiModels.ReserveRequest request,
            String token) {
        requireReserveRequest(request);
        SaasInstallation installation = authenticate(request.companyId(), request.storeId(), token);
        requireLegacyBootstrapped(request.companyId());
        SaasMemberBalanceAccount account = accountForUpdate(request.companyId(), request.memberId());
        Instant now = clock.instant();

        SaasMemberBalanceReservation active = activeReservation(account.getId());
        if (active != null && active.isExpiredAt(now)) {
            active.expire(now);
            cancelClaimsForReservation(active.getId(), now);
            active = null;
        }
        if (active != null) {
            if (!active.belongsTo(installation.getId(), request.terminalId().trim(), request.saleId().trim())) {
                throw reservationConflict("El saldo del miembro esta reservado temporalmente en otra caja");
            }
            active.renew(now, LEASE_DURATION);
            return response(active, account);
        }

        List<SaasMemberBalanceLot> accountLots = lots.findByAccount_IdOrderByCreatedAtAscIdAsc(account.getId());
        expireAvailableLots(account, accountLots, now);
        List<SaasMemberBalanceLot> availableLots = accountLots.stream()
                .filter(lot -> lot.getBalanceType() == MemberBalanceType.LOYALTY)
                .filter(lot -> lot.getRemainingAmount().signum() > 0)
                .filter(lot -> !lot.isExpiredAt(now))
                .toList();
        BigDecimal available = availableLots.stream()
                .map(SaasMemberBalanceLot::getRemainingAmount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        if (available.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "El miembro no dispone de saldo utilizable");
        }

        SaasMemberBalanceReservation reservation = reservations.save(new SaasMemberBalanceReservation(
                UUID.randomUUID(),
                account,
                request.storeId(),
                installation.getId(),
                request.terminalId().trim(),
                request.saleId().trim(),
                available,
                now,
                LEASE_DURATION));
        for (SaasMemberBalanceLot lot : availableLots) {
            reservationLots.save(new SaasMemberBalanceReservationLot(
                    UUID.randomUUID(),
                    reservation,
                    lot,
                    lot.getRemainingAmount()));
        }
        return response(reservation, account);
    }

    @Transactional
    public LoyaltyApiModels.ReservationResponse heartbeat(
            UUID reservationId,
            LoyaltyApiModels.ReservationOwnerRequest request,
            String token) {
        requireOwnerRequest(request);
        SaasInstallation installation = authenticate(request.companyId(), request.storeId(), token);
        SaasMemberBalanceReservation reservation = reservation(reservationId);
        SaasMemberBalanceAccount account = accountForUpdate(
                request.companyId(), reservation.getAccount().getMemberId());
        requireOwner(reservation, installation, request);
        Instant now = clock.instant();
        requireLive(reservation, now);
        reservation.renew(now, LEASE_DURATION);
        return response(reservation, account);
    }

    @Transactional
    public LoyaltyApiModels.ReservationResponse release(
            UUID reservationId,
            LoyaltyApiModels.ReservationOwnerRequest request,
            String token) {
        requireOwnerRequest(request);
        SaasInstallation installation = authenticate(request.companyId(), request.storeId(), token);
        SaasMemberBalanceReservation reservation = reservation(reservationId);
        SaasMemberBalanceAccount account = accountForUpdate(
                request.companyId(), reservation.getAccount().getMemberId());
        requireOwner(reservation, installation, request);
        reservation.release(clock.instant());
        return response(reservation, account);
    }

    @Transactional
    public LoyaltyApiModels.ReservationResponse prepare(
            UUID reservationId,
            LoyaltyApiModels.PrepareRequest request,
            String token) {
        requirePrepareRequest(request);
        SaasInstallation installation = authenticate(request.companyId(), request.storeId(), token);
        SaasMemberBalanceReservation reservation = reservation(reservationId);
        SaasMemberBalanceAccount account = accountForUpdate(
                request.companyId(), reservation.getAccount().getMemberId());
        requireOwner(reservation, installation, new LoyaltyApiModels.ReservationOwnerRequest(
                request.companyId(), request.storeId(), request.terminalId(), request.saleId()));
        BigDecimal amount = money(request.amount());
        if (reservation.isPrepared()) {
            if (reservation.preparedBy(request.operationId())
                    && reservation.getPreparedAmount().compareTo(amount) == 0) {
                return response(reservation, account);
            }
            throw conflict("La reserva ya fue preparada por otra operacion");
        }
        Instant now = clock.instant();
        requireLive(reservation, now);
        if (amount.signum() <= 0 || amount.compareTo(reservation.getReservedTotal()) > 0) {
            throw invalid("El importe a consumir debe ser positivo y no superar el saldo reservado");
        }

        reservation.prepare(request.operationId(), amount, now);
        return response(reservation, account);
    }

    @Transactional
    public LoyaltyApiModels.ReservationResponse finalizePrepared(
            UUID reservationId,
            LoyaltyApiModels.PreparedOwnerRequest request,
            String token) {
        requirePreparedOwnerRequest(request);
        SaasInstallation installation = authenticate(request.companyId(), request.storeId(), token);
        SaasMemberBalanceReservation reservation = reservation(reservationId);
        SaasMemberBalanceAccount account = accountForUpdate(
                request.companyId(), reservation.getAccount().getMemberId());
        requireOwner(reservation, installation, new LoyaltyApiModels.ReservationOwnerRequest(
                request.companyId(), request.storeId(), request.terminalId(), request.saleId()));
        if (SaasMemberBalanceReservation.CONSUMED.equals(reservation.getStatus())) {
            if (reservation.preparedBy(request.operationId())) {
                return response(reservation, account);
            }
            throw conflict("La reserva ya fue finalizada por otra operacion");
        }
        if (!reservation.isPrepared() || !reservation.preparedBy(request.operationId())) {
            throw conflict("La reserva no esta preparada para esta operacion");
        }

        BigDecimal pending = reservation.getPreparedAmount();
        for (SaasMemberBalanceReservationLot reservationLot
                : reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservationId)) {
            if (reservationLot.getBalanceType() != MemberBalanceType.LOYALTY) {
                continue;
            }
            BigDecimal consumed = pending.min(reservationLot.getReservedAmount());
            if (consumed.signum() > 0) {
                reservationLot.getLot().consume(consumed);
                reservationLot.consume(consumed);
                pending = pending.subtract(consumed);
            }
            if (pending.signum() == 0) {
                break;
            }
        }
        if (pending.signum() != 0) {
            throw conflict("Los lotes reservados no cubren el importe solicitado");
        }
        Instant now = clock.instant();
        account.debit(reservation.getPreparedAmount(), now);
        reservation.finalizePrepared(request.operationId(), now);
        return response(reservation, account);
    }

    @Transactional
    public LoyaltyApiModels.ReservationResponse abortPrepared(
            UUID reservationId,
            LoyaltyApiModels.PreparedOwnerRequest request,
            String token) {
        requirePreparedOwnerRequest(request);
        SaasInstallation installation = authenticate(request.companyId(), request.storeId(), token);
        SaasMemberBalanceReservation reservation = reservation(reservationId);
        SaasMemberBalanceAccount account = accountForUpdate(
                request.companyId(), reservation.getAccount().getMemberId());
        requireOwner(reservation, installation, new LoyaltyApiModels.ReservationOwnerRequest(
                request.companyId(), request.storeId(), request.terminalId(), request.saleId()));
        if (SaasMemberBalanceReservation.RELEASED.equals(reservation.getStatus())
                && reservation.preparedBy(request.operationId())) {
            return response(reservation, account);
        }
        if (!reservation.isPrepared() || !reservation.preparedBy(request.operationId())) {
            throw conflict("La reserva no esta preparada para abortar esta operacion");
        }
        reservation.abortPrepared(request.operationId(), clock.instant());
        return response(reservation, account);
    }

    @Transactional
    public LoyaltyApiModels.WalletReservationResponse reserveWallet(
            LoyaltyApiModels.ReserveRequest request,
            String token) {
        requireReserveRequest(request);
        SaasInstallation installation = authenticate(request.companyId(), request.storeId(), token);
        requireWalletBootstrapped(request.companyId());
        SaasMemberBalanceAccount account = accountForUpdate(request.companyId(), request.memberId());
        Instant now = clock.instant();

        SaasMemberBalanceReservation active = activeReservation(account.getId());
        if (active != null && active.isExpiredAt(now)) {
            active.expire(now);
            cancelClaimsForReservation(active.getId(), now);
            active = null;
        }
        if (active != null) {
            if (!active.belongsTo(installation.getId(), request.terminalId().trim(), request.saleId().trim())) {
                throw reservationConflict("El monedero del miembro esta reservado temporalmente en otra caja");
            }
            active.renew(now, LEASE_DURATION);
            if (!request.retentionClaims().isEmpty()) {
                configureRetention(active.getId(), new LoyaltyApiModels.RetentionConfigureRequest(
                        request.companyId(), request.storeId(), request.terminalId(), request.saleId(),
                        request.attributedAmount(), request.retentionClaims()), token);
            }
            return walletResponse(active, account);
        }

        List<SaasMemberBalanceLot> accountLots = lots.findByAccount_IdOrderByCreatedAtAscIdAsc(account.getId());
        expireAvailableLots(account, accountLots, now);
        List<SaasMemberBalanceLot> availableLots = accountLots.stream()
                .filter(lot -> lot.getRemainingAmount().signum() > 0)
                .filter(lot -> !lot.isExpiredAt(now))
                .sorted(walletLotComparator())
                .toList();
        BigDecimal availableLoyalty = availableAmount(availableLots, MemberBalanceType.LOYALTY);
        BigDecimal availableReturnCredit = availableAmount(availableLots, MemberBalanceType.RETURN_CREDIT);
        // Selecting a member owns the wallet for the lifetime of this POS
        // operation, even when both typed buckets are empty. A pure return
        // can create MEMBER_CREDIT without spending a previous balance, but
        // still needs this central ownership lock to prevent another till
        // from operating on the same member concurrently.
        SaasMemberBalanceReservation reservation = reservations.save(new SaasMemberBalanceReservation(
                UUID.randomUUID(),
                account,
                request.storeId(),
                installation.getId(),
                request.terminalId().trim(),
                request.saleId().trim(),
                availableLoyalty,
                availableReturnCredit,
                now,
                LEASE_DURATION));
        for (SaasMemberBalanceLot lot : availableLots) {
            reservationLots.save(new SaasMemberBalanceReservationLot(
                    UUID.randomUUID(),
                    reservation,
                    lot,
                    lot.getRemainingAmount()));
        }
        if (!request.retentionClaims().isEmpty()) {
            configureRetention(reservation.getId(), new LoyaltyApiModels.RetentionConfigureRequest(
                    request.companyId(), request.storeId(), request.terminalId(), request.saleId(),
                    request.attributedAmount(), request.retentionClaims()), token);
        }
        return walletResponse(reservation, account);
    }

    @Transactional
    public LoyaltyApiModels.WalletReservationResponse configureRetention(
            UUID reservationId,
            LoyaltyApiModels.RetentionConfigureRequest request,
            String token) {
        requireRetentionConfigureRequest(request);
        SaasInstallation installation = authenticate(request.companyId(), request.storeId(), token);
        SaasMemberBalanceReservation reservation = reservation(reservationId);
        SaasMemberBalanceAccount account = accountForUpdate(
                request.companyId(), reservation.getAccount().getMemberId());
        requireOwner(reservation, installation, new LoyaltyApiModels.ReservationOwnerRequest(
                request.companyId(), request.storeId(), request.terminalId(), request.saleId()));
        Instant now = clock.instant();
        requireLive(reservation, now);
        if (!reservation.isActive()) {
            throw conflict("La retencion solo puede configurarse mientras la reserva esta ACTIVE");
        }
        List<LoyaltyApiModels.RetentionClaim> requested = normalizeRetentionClaims(request.retentionClaims());
        String fingerprint = retentionFingerprint(request.attributedAmount(), requested);
        List<SaasMemberBalanceRetentionClaim> current = retentionClaims == null
                ? List.of() : retentionClaims.findByReservation_IdOrderByLotIdAsc(reservationId);
        if (reservation.getRetentionRevision() > 0
                && reservation.getRetentionFingerprint().equals(fingerprint)) {
            return walletResponse(reservation, account);
        }
        if (!current.isEmpty() && reservation.getRetentionRevision() > 0 && reservation.isPrepared()) {
            throw conflict("La retencion no puede reconfigurarse despues de PREPARED");
        }
        if (current.stream().anyMatch(claim -> claim.getStatus() == SaasMemberBalanceRetentionClaimStatus.COMMITTED_PENDING
                || claim.getStatus() == SaasMemberBalanceRetentionClaimStatus.APPLIED)) {
            throw conflict("La retencion ya fue comprometida");
        }
        cancelClaims(current, now);
        Map<UUID, SaasMemberBalanceLot> byLot = lots.findByAccount_IdOrderByCreatedAtAscIdAsc(account.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(SaasMemberBalanceLot::getId, value -> value));
        java.util.Map<UUID, SaasMemberBalanceReservationLot> linkedLots = reservationLots == null
                ? new java.util.HashMap<>()
                : reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservationId)
                        .stream().collect(java.util.stream.Collectors.toMap(
                                value -> value.getLot().getId(), value -> value));
        long revision = reservation.getRetentionRevision() + 1;
        for (LoyaltyApiModels.RetentionClaim claim : requested) {
            SaasMemberBalanceLot lot = byLot.get(claim.lotId());
            BigDecimal amount = money(claim.amount());
            BigDecimal original = claim.amountOriginal() == null ? amount : money(claim.amountOriginal());
            if (amount.signum() <= 0 || original.signum() <= 0 || amount.compareTo(original) > 0) {
                throw invalid("Cada claim de retencion debe tener un importe positivo valido");
            }
            if (lot != null && (lot.getBalanceType() != MemberBalanceType.LOYALTY
                    || !account.getCompanyId().equals(lot.getCompanyId())
                    || !account.getMemberId().equals(lot.getMemberId())
                    || !claim.sourceMovementId().equals(lot.getSourceMovementId())
                    || !Objects.equals(claim.sourceDocumentId(), lot.getDocumentId()))) {
                throw conflict("El lote no coincide con el movimiento/documento de origen");
            }
            if (lot != null && original.compareTo(lot.getOriginalAmount()) != 0) {
                throw conflict("amountOriginal no coincide con el importe original del lote");
            }
            SaasMemberBalanceRetentionClaimStatus status = lot == null
                    ? SaasMemberBalanceRetentionClaimStatus.HELD_MISSING
                    : SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN;
            BigDecimal held = lot == null ? amount
                    : amount.min(lot.getRemainingAmount());
            if (lot != null && held.signum() > 0) {
                SaasMemberBalanceReservationLot linked = linkedLots.get(lot.getId());
                BigDecimal linkedRemaining = linked == null ? BigDecimal.ZERO.setScale(2)
                        : linked.getRemainingAmount();
                BigDecimal delta = held.subtract(linkedRemaining).max(BigDecimal.ZERO.setScale(2));
                if (delta.signum() > 0) {
                    // A lot can be synchronized between reserve and configure.
                    // Incorporate only the additional retained portion; a
                    // later lower configure never shrinks reserved capacity.
                    reservation.incorporateWalletLot(MemberBalanceType.LOYALTY, delta);
                    if (linked == null) {
                        linked = new SaasMemberBalanceReservationLot(
                                UUID.randomUUID(), reservation, lot, held);
                        linkedLots.put(lot.getId(), linked);
                    } else {
                        linked.incorporate(delta);
                    }
                    reservationLots.save(linked);
                }
            }
            SaasMemberBalanceRetentionClaim persisted = current.stream()
                    .filter(existing -> existing.getLotId().equals(claim.lotId()))
                    .findFirst()
                    .orElseGet(() -> new SaasMemberBalanceRetentionClaim(
                            UUID.randomUUID(), reservation, claim.lotId(), claim.sourceMovementId(),
                            claim.sourceDocumentId(), original, amount, status, now));
            persisted.replace(claim.sourceMovementId(), claim.sourceDocumentId(), original, amount,
                    held, status, now);
            retentionClaims.save(persisted);
        }
        reservation.configureRetention(revision, fingerprint,
                request.attributedAmount() == null
                        ? requested.stream().map(LoyaltyApiModels.RetentionClaim::amount)
                                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add)
                        : money(request.attributedAmount()));
        return walletResponse(reservation, account);
    }

    @Transactional
    public LoyaltyApiModels.WalletReservationResponse heartbeatWallet(
            UUID reservationId,
            LoyaltyApiModels.ReservationOwnerRequest request,
            String token) {
        requireOwnerRequest(request);
        SaasInstallation installation = authenticate(request.companyId(), request.storeId(), token);
        SaasMemberBalanceReservation reservation = reservation(reservationId);
        SaasMemberBalanceAccount account = accountForUpdate(
                request.companyId(), reservation.getAccount().getMemberId());
        requireOwner(reservation, installation, request);
        Instant now = clock.instant();
        requireLive(reservation, now);
        reservation.renew(now, LEASE_DURATION);
        return walletResponse(reservation, account);
    }

    @Transactional
    public LoyaltyApiModels.WalletReservationResponse releaseWallet(
            UUID reservationId,
            LoyaltyApiModels.ReservationOwnerRequest request,
            String token) {
        requireOwnerRequest(request);
        SaasInstallation installation = authenticate(request.companyId(), request.storeId(), token);
        SaasMemberBalanceReservation reservation = reservation(reservationId);
        SaasMemberBalanceAccount account = accountForUpdate(
                request.companyId(), reservation.getAccount().getMemberId());
        requireOwner(reservation, installation, request);
        if (!reservation.isActive()) {
            if (SaasMemberBalanceReservation.RELEASED.equals(reservation.getStatus())) {
                return walletResponse(reservation, account);
            }
            throw conflict("La reserva de monedero solo puede liberarse mientras esta ACTIVE");
        }
        Instant now = clock.instant();
        reservation.release(now);
        cancelClaimsForReservation(reservationId, now);
        return walletResponse(reservation, account);
    }

    @Transactional
    public LoyaltyApiModels.WalletReservationResponse prepareWallet(
            UUID reservationId,
            LoyaltyApiModels.WalletPrepareRequest request,
            String token) {
        requireWalletPrepareRequest(request);
        SaasInstallation installation = authenticate(request.companyId(), request.storeId(), token);
        SaasMemberBalanceReservation reservation = reservation(reservationId);
        SaasMemberBalanceAccount account = accountForUpdate(
                request.companyId(), reservation.getAccount().getMemberId());
        requireOwner(reservation, installation, new LoyaltyApiModels.ReservationOwnerRequest(
                request.companyId(), request.storeId(), request.terminalId(), request.saleId()));

        BigDecimal loyaltyAmount = money(request.loyaltyAmount());
        BigDecimal returnCreditAmount = money(request.returnCreditAmount());
        requireWalletAmounts(reservation, loyaltyAmount, returnCreditAmount);
        Instant now = clock.instant();
        if (request.expectedRetentionRevision() != reservation.getRetentionRevision()
                || !request.expectedRetentionFingerprint().equals(reservation.getRetentionFingerprint())) {
            throw conflict("La revision de retencion de la reserva ha cambiado");
        }
        if (reservation.isPrepared()) {
            if (!reservation.preparedBy(request.operationId())) {
                throw conflict("La reserva ya fue preparada por otra operacion");
            }
            if (reservation.getPreparedLoyaltyAmount().compareTo(loyaltyAmount) == 0
                    && reservation.getPreparedReturnCreditAmount().compareTo(returnCreditAmount) == 0) {
                return walletResponse(reservation, account);
            }
            reservation.reprepareTyped(
                    request.operationId(),
                    loyaltyAmount,
                    returnCreditAmount,
                    now);
            return walletResponse(reservation, account);
        }
        requireLive(reservation, now);
        reservation.prepareTyped(
                request.operationId(),
                loyaltyAmount,
                returnCreditAmount,
                now);
        return walletResponse(reservation, account);
    }

    @Transactional
    public LoyaltyApiModels.WalletReservationResponse finalizePreparedWallet(
            UUID reservationId,
            LoyaltyApiModels.PreparedOwnerRequest request,
            String token) {
        requirePreparedOwnerRequest(request);
        SaasInstallation installation = authenticate(request.companyId(), request.storeId(), token);
        SaasMemberBalanceReservation reservation = reservation(reservationId);
        requireOwner(reservation, installation, new LoyaltyApiModels.ReservationOwnerRequest(
                request.companyId(), request.storeId(), request.terminalId(), request.saleId()));
        // Reject a stale owner/state before inspecting or validating a new
        // retention snapshot. A CONSUMED replay is only valid for its own
        // prepare operation; another operation must not get as far as any
        // snapshot/projector work.
        boolean consumed = SaasMemberBalanceReservation.CONSUMED.equals(reservation.getStatus());
        if ((!consumed && (!reservation.isPrepared() || !reservation.preparedBy(request.operationId())))
                || (consumed && !reservation.preparedBy(request.operationId()))) {
            throw conflict(consumed
                    ? "La reserva de monedero ya fue finalizada por otra operacion"
                    : "La reserva de monedero no esta preparada para esta operacion");
        }
        requirePositiveRetentionSnapshot(reservation, request);
        if (request.retentionSnapshot() != null) {
            validateRetentionSnapshot(reservation, request, request.retentionSnapshot());
        }
        // The projector and this online finalize path must acquire the
        // operation plus retention projection locks before the pessimistic
        // account row lock. Otherwise two different operations can deadlock
        // while one holds ACCOUNT and the other holds projection ACCOUNT.
        lockOperation(request.operationId());
        lockRetentionProjectionBeforeAccount(reservation, request);
        SaasMemberBalanceAccount account = accountForUpdate(
                request.companyId(), reservation.getAccount().getMemberId());
        if (SaasMemberBalanceReservation.CONSUMED.equals(reservation.getStatus())) {
            if (reservation.preparedBy(request.operationId())) {
                validateConsumedRetentionReplay(reservation, request);
                return walletResponse(reservation, account, request.operationId());
            }
            throw conflict("La reserva de monedero ya fue finalizada por otra operacion");
        }
        if (!reservation.isPrepared() || !reservation.preparedBy(request.operationId())) {
            throw conflict("La reserva de monedero no esta preparada para esta operacion");
        }

        // Reconcile the final durable photo before consuming F10. The same
        // command is used by the outbox projector, so a shifted lot cannot be
        // consumed by the wallet while its retention claim still points at it.
        Instant now = clock.instant();
        boolean reconciled = reconcileRetention(reservation, request, now);
        validateRetentionBeforeFinalize(reservation, request, reconciled);
        List<SaasMemberBalanceReservationLot> preparedLots = walletReservationLots(reservationId);
        consumeSpendableType(
                preparedLots,
                MemberBalanceType.LOYALTY,
                reservation.getPreparedLoyaltyAmount(), reservationId);
        consumeSpendableType(
                preparedLots,
                MemberBalanceType.RETURN_CREDIT,
                reservation.getPreparedReturnCreditAmount(), reservationId);

        BigDecimal heldKnown = heldKnownAmount(reservationId);
        if (!reconciled) {
            consumeKnownClaims(reservationId, now);
            if (retentionClaims != null) {
                retentionClaims.findByReservation_IdOrderByLotIdAsc(reservationId).stream()
                        .filter(claim -> claim.getStatus() == SaasMemberBalanceRetentionClaimStatus.HELD_MISSING)
                        .forEach(claim -> claim.commitPending(now));
            }
        }
        if (reservation.getPreparedLoyaltyAmount().signum() > 0) {
            account.debit(MemberBalanceType.LOYALTY, reservation.getPreparedLoyaltyAmount(), now);
        }
        if (reservation.getPreparedReturnCreditAmount().signum() > 0) {
            account.debit(
                    MemberBalanceType.RETURN_CREDIT,
                    reservation.getPreparedReturnCreditAmount(),
                    now);
        }
        if (!reconciled && heldKnown.signum() > 0) {
            account.debit(MemberBalanceType.LOYALTY, heldKnown, now);
        }
        BigDecimal retainedForMetrics = reconciled
                ? recoveredKnownForOperation(request.operationId()) : heldKnown;
        boolean hasUsage = reservation.getPreparedLoyaltyAmount().signum() > 0
                || reservation.getPreparedReturnCreditAmount().signum() > 0
                || reservation.getRetentionAttributedAmount().signum() > 0
                || heldKnown.signum() > 0
                || retainedForMetrics.signum() > 0;
        if (hasUsage) {
            reservation.finalizePreparedTyped(request.operationId(), now, retainedForMetrics);
        } else {
            // Finalizing an empty wallet reservation only closes ownership; it
            // must not create a fake CONSUMED movement.
            reservation.abortPrepared(request.operationId(), now);
        }
        if (!reconciled) ensureRetentionReceipt(reservation, request, now);
        return walletResponse(reservation, account, request.operationId());
    }

    private void validateConsumedRetentionReplay(
            SaasMemberBalanceReservation reservation,
            LoyaltyApiModels.PreparedOwnerRequest request) {
        if (request.retentionSnapshot() == null) return;
        if (retentionReceipts == null) {
            throw conflict("No existe receipt de retencion para validar el replay");
        }
        validateRetentionSnapshot(reservation, request, request.retentionSnapshot());
        SaasMemberBalanceRetentionReceipt receipt = receiptForOperation(request.operationId());
        LoyaltyApiModels.RetentionSnapshot snapshot = request.retentionSnapshot();
        if (snapshot.returnDocumentId() == null) {
            throw conflict("El replay de retencion requiere returnDocumentId");
        }
        if (receipt == null || !receipt.matchesImmutable(
                reservation.getAccount().getCompanyId(), reservation.getStoreId(),
                reservation.getAccount().getMemberId(), snapshot.sourceDocumentId(),
                snapshot.returnDocumentId(), snapshot.attributedAmount(), snapshot.fingerprint())) {
            throw conflict("El replay de retencion no coincide con el receipt comprometido");
        }
    }

    private void validateRetentionSnapshot(
            SaasMemberBalanceReservation reservation,
            LoyaltyApiModels.PreparedOwnerRequest request,
            LoyaltyApiModels.RetentionSnapshot snapshot) {
        if (snapshot == null || snapshot.claims() == null) {
            throw conflict("El snapshot de retencion es invalido");
        }
        if (!reservation.getAccount().getMemberId().equals(snapshot.memberId())) {
            throw conflict("El snapshot de retencion no pertenece al miembro de la reserva");
        }
        if (snapshot.returnDocumentId() == null) {
            throw conflict("El snapshot de retencion requiere returnDocumentId");
        }
        if (snapshot.claims().stream().anyMatch(Objects::isNull)) {
            throw conflict("El snapshot de retencion es invalido");
        }
        List<MemberReturnBalanceRecoveryCommand.Claim> commandClaims = snapshot.claims().stream()
                .map(value -> value == null ? null : new MemberReturnBalanceRecoveryCommand.Claim(
                        value.lotId(), value.sourceMovementId(), value.sourceDocumentId(),
                        value.amountOriginal(), value.amount()))
                .toList();
        List<LoyaltyApiModels.RetentionClaim> normalized = normalizeRetentionClaims(snapshot.claims());
        try {
            if (!retentionFingerprint(snapshot.attributedAmount(), normalized)
                    .equals(snapshot.fingerprint())) {
                throw conflict("El fingerprint del snapshot de retencion no coincide");
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw conflict("El snapshot de retencion es invalido");
        }
        if (retentionReconciler != null) {
            retentionReconciler.validate(new MemberReturnBalanceRecoveryCommand(
                    request.operationId(), reservation.getAccount().getCompanyId(), reservation.getStoreId(),
                    reservation.getAccount().getMemberId(), reservation.getId(), reservation.getSaleId(),
                    snapshot.sourceDocumentId(), snapshot.returnDocumentId(), snapshot.attributedAmount(),
                    snapshot.fingerprint(), commandClaims));
        }
    }

    private boolean reconcileRetention(
            SaasMemberBalanceReservation reservation,
            LoyaltyApiModels.PreparedOwnerRequest request,
            Instant now) {
        if (retentionReconciler == null || retentionClaims == null
                || reservation.getRetentionRevision() <= 0) return false;
        List<SaasMemberBalanceRetentionClaim> values = retentionClaims
                .findByReservation_IdOrderByLotIdAsc(reservation.getId()).stream()
                .filter(value -> value.getStatus() != SaasMemberBalanceRetentionClaimStatus.CANCELLED)
                .toList();
        if (values.isEmpty() && request.retentionSnapshot() == null) return false;
        retentionReconciler.reconcile(retentionRecoveryCommand(reservation, request, values), now);
        return true;
    }

    private void lockRetentionProjectionBeforeAccount(
            SaasMemberBalanceReservation reservation,
            LoyaltyApiModels.PreparedOwnerRequest request) {
        if (retentionReconciler == null || retentionClaims == null
                || reservation.getRetentionRevision() <= 0) {
            return;
        }
        List<SaasMemberBalanceRetentionClaim> values = retentionClaims
                .findByReservation_IdOrderByLotIdAsc(reservation.getId()).stream()
                .filter(value -> value.getStatus() != SaasMemberBalanceRetentionClaimStatus.CANCELLED)
                .toList();
        if (values.isEmpty() && request.retentionSnapshot() == null) return;
        retentionReconciler.lockForRecovery(retentionRecoveryCommand(reservation, request, values));
    }

    private MemberReturnBalanceRecoveryCommand retentionRecoveryCommand(
            SaasMemberBalanceReservation reservation,
            LoyaltyApiModels.PreparedOwnerRequest request,
            List<SaasMemberBalanceRetentionClaim> values) {
        LoyaltyApiModels.RetentionSnapshot snapshot = request.retentionSnapshot();
        UUID sourceDocumentId = snapshot == null
                ? values.get(0).getSourceDocumentId() : snapshot.sourceDocumentId();
        List<MemberReturnBalanceRecoveryCommand.Claim> commandClaims = snapshot == null
                ? values.stream().map(value -> new MemberReturnBalanceRecoveryCommand.Claim(
                        value.getLotId(), value.getSourceMovementId(), value.getSourceDocumentId(),
                        value.getAmountOriginal(), value.getAmount())).toList()
                : snapshot.claims().stream().map(value -> new MemberReturnBalanceRecoveryCommand.Claim(
                        value.lotId(), value.sourceMovementId(), value.sourceDocumentId(),
                        value.amountOriginal(), value.amount())).toList();
        return new MemberReturnBalanceRecoveryCommand(
                request.operationId(), reservation.getAccount().getCompanyId(), reservation.getStoreId(),
                reservation.getAccount().getMemberId(), reservation.getId(), reservation.getSaleId(),
                sourceDocumentId, snapshot == null ? null : snapshot.returnDocumentId(),
                snapshot == null ? reservation.getRetentionAttributedAmount() : snapshot.attributedAmount(),
                snapshot == null ? reservation.getRetentionFingerprint() : snapshot.fingerprint(),
                commandClaims);
    }

    private void requirePositiveRetentionSnapshot(
            SaasMemberBalanceReservation reservation,
            LoyaltyApiModels.PreparedOwnerRequest request) {
        if (reservation.getRetentionRevision() <= 0
                || reservation.getRetentionAttributedAmount().signum() <= 0) {
            return;
        }
        LoyaltyApiModels.RetentionSnapshot snapshot = request.retentionSnapshot();
        if (snapshot == null || snapshot.returnDocumentId() == null) {
            throw conflict("La retencion positiva requiere snapshot con returnDocumentId");
        }
    }

    private BigDecimal recoveredKnownForOperation(UUID operationId) {
        if (retentionReceipts == null) return BigDecimal.ZERO.setScale(2);
        SaasMemberBalanceRetentionReceipt receipt = receiptForOperation(operationId);
        return receipt == null ? BigDecimal.ZERO.setScale(2) : receipt.getRecoveredKnown();
    }

    private SaasMemberBalanceRetentionReceipt receiptForOperation(UUID operationId) {
        if (operationId == null || retentionReceipts == null) return null;
        SaasMemberBalanceRetentionReceipt direct = retentionReceipts.findById(operationId)
                .orElse(null);
        if (retentionReceiptAliases == null) return direct;
        SaasMemberBalanceRetentionReceiptAlias alias = retentionReceiptAliases.findById(operationId)
                .orElse(null);
        if (direct != null && alias != null) {
            throw conflict("El operationId no puede tener receipt y alias simultaneamente");
        }
        if (direct != null) return direct;
        return alias == null ? null : alias.getReceipt();
    }

    @Transactional
    public LoyaltyApiModels.WalletReservationResponse abortPreparedWallet(
            UUID reservationId,
            LoyaltyApiModels.PreparedOwnerRequest request,
            String token) {
        requirePreparedOwnerRequest(request);
        SaasInstallation installation = authenticate(request.companyId(), request.storeId(), token);
        SaasMemberBalanceReservation reservation = reservation(reservationId);
        SaasMemberBalanceAccount account = accountForUpdate(
                request.companyId(), reservation.getAccount().getMemberId());
        requireOwner(reservation, installation, new LoyaltyApiModels.ReservationOwnerRequest(
                request.companyId(), request.storeId(), request.terminalId(), request.saleId()));
        if (SaasMemberBalanceReservation.RELEASED.equals(reservation.getStatus())
                && reservation.preparedBy(request.operationId())) {
            return walletResponse(reservation, account);
        }
        if (!reservation.isPrepared() || !reservation.preparedBy(request.operationId())) {
            throw conflict("La reserva de monedero no esta preparada para abortar esta operacion");
        }
        Instant now = clock.instant();
        reservation.abortPrepared(request.operationId(), now);
        cancelClaimsForReservation(reservationId, now);
        return walletResponse(reservation, account);
    }

    private SaasInstallation authenticate(UUID companyId, UUID storeId, String token) {
        if (companyId == null || storeId == null) {
            throw invalid("companyId y storeId son obligatorios");
        }
        return installationAuthenticator.requireLinkedInstallation(
                companyId,
                storeId,
                installations.findByCompany_IdAndStore_Id(companyId, storeId),
                token);
    }

    private void requireLegacyBootstrapped(UUID companyId) {
        if (bootstraps.findById(companyId).filter(SaasMemberLoyaltyBootstrap::isCompleted).isEmpty()) {
            throw conflict("La fidelizacion central de la empresa aun no ha sido inicializada");
        }
    }

    private void requireLegacyPointsWritable(UUID companyId) {
        if (pointsAuthorities.findById(companyId).filter(SaasMemberPointsAuthority::isActive).isPresent()
                || pointsBootstraps.existsByCompany_IdAndStatusNot(
                        companyId, SaasMemberPointsBootstrap.CANCELLED)) {
            throw conflict("El bootstrap legado no puede escribir puntos durante o despues del bootstrap V21");
        }
    }

    private void requireWalletBootstrapped(UUID companyId) {
        if (walletBootstraps.findFirstByCompany_IdOrderByCreatedAtDesc(companyId)
                .filter(SaasMemberWalletBootstrap::isCompleted)
                .isEmpty()) {
            throw new MemberWalletBootstrapRequiredException(
                    "El bootstrap historico multi-tienda del monedero aun no esta completado");
        }
    }

    private SaasMemberBalanceAccount accountForUpdate(UUID companyId, UUID memberId) {
        return accounts.findForUpdate(companyId, memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Miembro no encontrado en SaaS"));
    }

    private SaasMemberBalanceReservation reservation(UUID reservationId) {
        if (reservationId == null) {
            throw invalid("reservationId es obligatorio");
        }
        return reservations.findById(reservationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reserva no encontrada"));
    }

    private SaasMemberBalanceReservation activeReservation(UUID accountId) {
        return reservations.findFirstByAccount_IdAndStatusInOrderByCreatedAtDesc(
                accountId,
                List.of(SaasMemberBalanceReservation.ACTIVE, SaasMemberBalanceReservation.PREPARED)).orElse(null);
    }

    private void expireAvailableLots(
            SaasMemberBalanceAccount account,
            List<SaasMemberBalanceLot> accountLots,
            Instant now) {
        for (SaasMemberBalanceLot lot : accountLots) {
            if (lot.getRemainingAmount().signum() > 0 && lot.isExpiredAt(now)) {
                BigDecimal expired = lot.expire();
                account.debit(lot.getBalanceType(), expired, now);
            }
        }
    }

    private void requireOwner(
            SaasMemberBalanceReservation reservation,
            SaasInstallation installation,
            LoyaltyApiModels.ReservationOwnerRequest request) {
        if (!reservation.getAccount().getCompanyId().equals(request.companyId())
                || !reservation.getStoreId().equals(request.storeId())
                || !reservation.belongsTo(
                        installation.getId(),
                        request.terminalId().trim(),
                        request.saleId().trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "La reserva pertenece a otra venta o instalacion");
        }
    }

    private void requireLive(SaasMemberBalanceReservation reservation, Instant now) {
        if (reservation.isExpiredAt(now)) {
            reservation.expire(now);
            cancelClaimsForReservation(reservation.getId(), now);
            throw conflict("La reserva de saldo ha caducado por falta de conexion");
        }
        if (!reservation.isActive()) {
            throw conflict("La reserva de saldo ya no esta activa");
        }
    }

    private LoyaltyApiModels.ReservationResponse response(
            SaasMemberBalanceReservation reservation,
            SaasMemberBalanceAccount account) {
        return new LoyaltyApiModels.ReservationResponse(
                reservation.getId(),
                account.getMemberId(),
                reservation.getStatus(),
                reservation.getReservedTotal(),
                reservation.getPreparedAmount(),
                reservation.getPrepareOperationId(),
                reservation.getConsumedTotal(),
                account.getBalance(),
                reservation.getHeartbeatAt(),
                reservation.getLeaseExpiresAt(),
                HEARTBEAT_INTERVAL_SECONDS,
                LEASE_SECONDS);
    }

    private List<LoyaltyApiModels.RetentionClaim> retentionClaimsForResponse(UUID reservationId) {
        if (retentionClaims == null) {
            return List.of();
        }
        return retentionClaims.findByReservation_IdOrderByLotIdAsc(reservationId).stream()
                .filter(claim -> claim.getStatus() != SaasMemberBalanceRetentionClaimStatus.CANCELLED)
                .map(claim -> new LoyaltyApiModels.RetentionClaim(
                        claim.getLotId(), claim.getSourceMovementId(), claim.getSourceDocumentId(),
                        claim.getAmountOriginal(), claim.getAmount(), claim.getHeldAmount()))
                .toList();
    }

    private BigDecimal heldKnownAmount(UUID reservationId) {
        return sumClaims(reservationId, SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN);
    }

    private BigDecimal recoveredKnownAmount(UUID reservationId) {
        return sumClaims(reservationId, SaasMemberBalanceRetentionClaimStatus.APPLIED);
    }

    private BigDecimal pendingMissingAmount(UUID reservationId) {
        return sumClaims(reservationId, SaasMemberBalanceRetentionClaimStatus.HELD_MISSING,
                SaasMemberBalanceRetentionClaimStatus.COMMITTED_PENDING);
    }

    private BigDecimal spentShortfallAmount(UUID reservationId) {
        if (retentionClaims == null) return BigDecimal.ZERO.setScale(2);
        return retentionClaims.findByReservation_IdOrderByLotIdAsc(reservationId).stream()
                .filter(claim -> claim.getStatus() == SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN
                        || claim.getStatus() == SaasMemberBalanceRetentionClaimStatus.APPLIED)
                .map(claim -> claim.getAmount().subtract(claim.getHeldAmount()))
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private BigDecimal spendableAmount(SaasMemberBalanceReservation reservation) {
        return reservation.getReservedLoyaltyAmount()
                .add(reservation.getReservedReturnCreditAmount())
                .subtract(heldKnownAmount(reservation.getId()))
                .max(BigDecimal.ZERO.setScale(2));
    }

    private BigDecimal sumClaims(UUID reservationId, SaasMemberBalanceRetentionClaimStatus... statuses) {
        if (retentionClaims == null) return BigDecimal.ZERO.setScale(2);
        Set<SaasMemberBalanceRetentionClaimStatus> wanted = Set.of(statuses);
        return retentionClaims.findByReservation_IdOrderByLotIdAsc(reservationId).stream()
                .filter(claim -> wanted.contains(claim.getStatus()))
                .map(SaasMemberBalanceRetentionClaim::getHeldAmount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private LoyaltyApiModels.WalletReservationResponse walletResponse(
            SaasMemberBalanceReservation reservation,
            SaasMemberBalanceAccount account) {
        return walletResponse(reservation, account, null);
    }

    private LoyaltyApiModels.WalletReservationResponse walletResponse(
            SaasMemberBalanceReservation reservation,
            SaasMemberBalanceAccount account,
            UUID retentionOperationId) {
        List<LoyaltyApiModels.WalletReservedLot> reservedLotViews = walletReservationLots(reservation.getId()).stream()
                .map(reservationLot -> new LoyaltyApiModels.WalletReservedLot(
                        reservationLot.getBalanceType(),
                        reservationLot.getLot().getId(),
                        reservationLot.getRemainingAmount(),
                        reservationLot.getLot().getCreatedAt(),
                        reservationLot.getLot().getExpiresAt(),
                        reservationLot.getLot().getSourceMovementId(),
                        reservationLot.getLot().getDocumentId()))
                .toList();
        SaasMemberBalanceRetentionReceipt receipt = receiptForOperation(retentionOperationId);
        if (receipt != null && !receiptBelongsToReservation(receipt, reservation, account)) {
            // Operation ids are shared by the local return and the wallet
            // reservation. A standalone recovery for another member must not
            // leak its metrics into this reservation replay.
            receipt = null;
        }
        BigDecimal heldKnown = receipt == null ? heldKnownAmount(reservation.getId())
                : BigDecimal.ZERO.setScale(2);
        BigDecimal pendingMissing = receipt == null ? pendingMissingAmount(reservation.getId())
                : receipt.getPendingMissing();
        BigDecimal spentShortfall = receipt == null ? spentShortfallAmount(reservation.getId())
                : receipt.getSpentShortfall();
        BigDecimal recoveredKnown = receipt == null ? recoveredKnownAmount(reservation.getId())
                : receipt.getRecoveredKnown();
        List<LoyaltyApiModels.RetentionClaim> responseClaims = receipt == null
                ? retentionClaimsForResponse(reservation.getId())
                : retentionClaims == null ? List.of()
                        : retentionClaims.findByReceipt_OperationIdOrderByLotIdAsc(
                                receipt.getOperationId()).stream()
                                .map(claim -> new LoyaltyApiModels.RetentionClaim(
                                        claim.getLotId(), claim.getSourceMovementId(),
                                        claim.getSourceDocumentId(), claim.getAmountOriginal(),
                                        claim.getAmount(), claim.getHeldAmount()))
                                .toList();
        return new LoyaltyApiModels.WalletReservationResponse(
                reservation.getId(),
                account.getMemberId(),
                reservation.getStatus(),
                reservation.getReservedLoyaltyAmount(),
                reservation.getReservedReturnCreditAmount(),
                reservation.getPreparedLoyaltyAmount(),
                reservation.getPreparedReturnCreditAmount(),
                reservation.getPrepareOperationId(),
                reservation.getConsumedLoyaltyAmount(),
                reservation.getConsumedReturnCreditAmount(),
                account.getBalance(),
                account.getReturnCreditBalance(),
                reservedLotViews,
                reservation.getHeartbeatAt(),
                reservation.getLeaseExpiresAt(),
                HEARTBEAT_INTERVAL_SECONDS,
                LEASE_SECONDS,
                reservation.getRetentionRevision(),
                reservation.getRetentionFingerprint(),
                responseClaims,
                heldKnown,
                pendingMissing,
                spentShortfall,
                spendableAmount(reservation),
                recoveredKnown);
    }

    private boolean receiptBelongsToReservation(
            SaasMemberBalanceRetentionReceipt receipt,
            SaasMemberBalanceReservation reservation,
            SaasMemberBalanceAccount account) {
        if (!account.getCompanyId().equals(receipt.getCompanyId())
                || !reservation.getStoreId().equals(receipt.getStoreId())
                || !account.getMemberId().equals(receipt.getMemberId())) {
            return false;
        }
        return reservation.getRetentionRevision() > 0
                && reservation.getRetentionAttributedAmount().compareTo(receipt.getAttributedAmount()) == 0
                && Objects.equals(reservation.getRetentionFingerprint(), receipt.getFingerprint());
    }

    private List<SaasMemberBalanceReservationLot> walletReservationLots(UUID reservationId) {
        return reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservationId).stream()
                .sorted(Comparator
                        .comparingInt((SaasMemberBalanceReservationLot value) ->
                                balanceTypeOrder(value.getBalanceType()))
                        .thenComparing(value -> value.getLot().getCreatedAt())
                        .thenComparing(value -> value.getLot().getId()))
                .toList();
    }

    private Comparator<SaasMemberBalanceLot> walletLotComparator() {
        return Comparator
                .comparingInt((SaasMemberBalanceLot value) -> balanceTypeOrder(value.getBalanceType()))
                .thenComparing(SaasMemberBalanceLot::getCreatedAt)
                .thenComparing(SaasMemberBalanceLot::getId);
    }

    private int balanceTypeOrder(MemberBalanceType balanceType) {
        return balanceType == MemberBalanceType.LOYALTY ? 0 : 1;
    }

    private BigDecimal availableAmount(
            List<SaasMemberBalanceLot> availableLots,
            MemberBalanceType balanceType) {
        return availableLots.stream()
                .filter(lot -> lot.getBalanceType() == balanceType)
                .map(SaasMemberBalanceLot::getRemainingAmount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private void consumeReservedType(
            List<SaasMemberBalanceReservationLot> preparedLots,
            MemberBalanceType balanceType,
            BigDecimal amount) {
        BigDecimal pending = amount;
        for (SaasMemberBalanceReservationLot reservationLot : preparedLots) {
            if (reservationLot.getBalanceType() != balanceType || pending.signum() == 0) {
                continue;
            }
            if (reservationLot.getLot().getBalanceType() != balanceType) {
                throw conflict("El tipo del lote reservado no coincide con el monedero central");
            }
            BigDecimal consumed = pending.min(reservationLot.getRemainingAmount());
            if (consumed.signum() > 0) {
                reservationLot.getLot().consume(consumed);
                reservationLot.consume(consumed);
                pending = pending.subtract(consumed);
            }
        }
        if (pending.signum() != 0) {
            throw conflict("Los lotes reservados de " + balanceType + " no cubren el importe solicitado");
        }
    }

    private void consumeSpendableType(
            List<SaasMemberBalanceReservationLot> preparedLots,
            MemberBalanceType balanceType,
            BigDecimal amount,
            UUID reservationId) {
        BigDecimal pending = amount;
        for (SaasMemberBalanceReservationLot reservationLot : preparedLots) {
            if (reservationLot.getBalanceType() != balanceType || pending.signum() == 0) continue;
            BigDecimal held = retentionClaims == null ? BigDecimal.ZERO.setScale(2)
                    : retentionClaims.findByReservation_IdOrderByLotIdAsc(reservationId).stream()
                    .filter(claim -> claim.getStatus() == SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN)
                    .filter(claim -> claim.getLotId().equals(reservationLot.getLot().getId()))
                    .map(SaasMemberBalanceRetentionClaim::getHeldAmount)
                    .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
            BigDecimal spendable = reservationLot.getRemainingAmount().subtract(held).max(BigDecimal.ZERO.setScale(2));
            BigDecimal consumed = pending.min(spendable);
            if (consumed.signum() > 0) {
                reservationLot.getLot().consume(consumed);
                reservationLot.consume(consumed);
                pending = pending.subtract(consumed);
            }
        }
        if (pending.signum() != 0) {
            throw conflict("El saldo utilizable no cubre el importe solicitado");
        }
    }

    private void consumeKnownClaims(UUID reservationId, Instant now) {
        if (retentionClaims == null) return;
        List<SaasMemberBalanceReservationLot> links = walletReservationLots(reservationId);
        retentionClaims.findByReservation_IdOrderByLotIdAsc(reservationId).stream()
                .filter(claim -> claim.getStatus() == SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN)
                .forEach(claim -> {
                    BigDecimal pending = claim.getHeldAmount();
                    for (SaasMemberBalanceReservationLot link : links) {
                        if (pending.signum() == 0 || !link.getLot().getId().equals(claim.getLotId())) continue;
                        BigDecimal consumed = pending.min(link.getRemainingAmount());
                        if (consumed.signum() > 0) {
                            link.getLot().consume(consumed);
                            link.consume(consumed);
                            pending = pending.subtract(consumed);
                        }
                    }
                    if (pending.signum() != 0) throw conflict("El lote retenido ya no dispone de saldo");
                    claim.apply(now);
                });
    }

    private void ensureRetentionReceipt(
            SaasMemberBalanceReservation reservation,
            LoyaltyApiModels.PreparedOwnerRequest request,
            Instant now) {
        if (retentionClaims == null || retentionReceipts == null
                || reservation.getRetentionRevision() <= 0) {
            return;
        }
        List<SaasMemberBalanceRetentionClaim> claims =
                retentionClaims.findByReservation_IdOrderByLotIdAsc(reservation.getId()).stream()
                        .filter(claim -> claim.getStatus() != SaasMemberBalanceRetentionClaimStatus.CANCELLED)
                        .toList();
        if (claims.isEmpty()) return;
        UUID sourceDocumentId = claims.get(0).getSourceDocumentId();
        if (sourceDocumentId == null || claims.stream()
                .anyMatch(claim -> !sourceDocumentId.equals(claim.getSourceDocumentId()))) {
            throw conflict("Todos los claims de retencion deben compartir documento origen");
        }
        List<LoyaltyApiModels.RetentionClaim> receiptClaims = claims.stream()
                .map(claim -> new LoyaltyApiModels.RetentionClaim(
                        claim.getLotId(), claim.getSourceMovementId(), claim.getSourceDocumentId(),
                        claim.getAmountOriginal(), claim.getAmount(), claim.getHeldAmount()))
                .toList();
        String calculatedFingerprint = retentionFingerprint(
                reservation.getRetentionAttributedAmount(), receiptClaims);
        if (!calculatedFingerprint.equals(reservation.getRetentionFingerprint())) {
            throw conflict("El fingerprint de claims de retencion no coincide con la reserva");
        }
        BigDecimal recovered = claims.stream()
                .filter(claim -> claim.getStatus() == SaasMemberBalanceRetentionClaimStatus.APPLIED)
                .map(SaasMemberBalanceRetentionClaim::getHeldAmount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        BigDecimal pending = claims.stream()
                .filter(claim -> claim.getStatus() == SaasMemberBalanceRetentionClaimStatus.COMMITTED_PENDING
                        || claim.getStatus() == SaasMemberBalanceRetentionClaimStatus.HELD_MISSING)
                .map(SaasMemberBalanceRetentionClaim::getHeldAmount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        BigDecimal shortfall = claims.stream()
                .filter(claim -> claim.getStatus() == SaasMemberBalanceRetentionClaimStatus.APPLIED)
                .map(claim -> claim.getAmount().subtract(claim.getHeldAmount()))
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        LoyaltyApiModels.RetentionSnapshot snapshot = request.retentionSnapshot();
        UUID returnDocumentId = snapshot == null ? null : snapshot.returnDocumentId();
        if (reservation.getRetentionAttributedAmount().signum() > 0 && returnDocumentId == null) {
            throw conflict("La retencion positiva requiere snapshot con returnDocumentId");
        }
        UUID operationId = request.operationId();
        lockOperation(operationId);
        SaasMemberBalanceRetentionReceipt existing = receiptForOperation(operationId);
        if (existing != null) {
            if (!existing.matchesImmutable(
                    reservation.getAccount().getCompanyId(), reservation.getStoreId(),
                    reservation.getAccount().getMemberId(), sourceDocumentId,
                    returnDocumentId, reservation.getRetentionAttributedAmount(),
                    reservation.getRetentionFingerprint())) {
                throw conflict("El receipt de retencion ya existe con datos diferentes");
            }
            if (existing.getReturnDocumentId() == null && returnDocumentId != null) {
                existing.attachReturnDocument(returnDocumentId, now);
                retentionReceipts.save(existing);
            }
            return;
        }
        SaasMemberBalanceRetentionReceipt receipt = retentionReceipts.save(
                new SaasMemberBalanceRetentionReceipt(
                        operationId,
                        reservation.getAccount().getCompanyId(),
                        reservation.getStoreId(),
                        reservation.getAccount().getMemberId(),
                        sourceDocumentId,
                        returnDocumentId,
                        reservation.getRetentionAttributedAmount(),
                        reservation.getRetentionFingerprint(),
                        recovered,
                        pending,
                        shortfall,
                        now));
        for (SaasMemberBalanceRetentionClaim claim : claims) {
            if (claim.getReceipt() != null && !claim.getReceipt().getOperationId().equals(operationId)) {
                throw conflict("El claim de retencion ya pertenece a otro receipt");
            }
            claim.attachReceipt(receipt);
            retentionClaims.save(claim);
        }
    }

    private void lockOperation(UUID operationId) {
        if (operationId == null) {
            throw conflict("operationId es obligatorio para el receipt de retencion");
        }
        String lockKey = "OPERATION:" + operationId;
        accounts.ensureProjectionLock(lockKey);
        accounts.lockProjectionKey(lockKey);
    }

    /**
     * Checks the durable final snapshot without changing any entity. This is
     * intentionally called before F10 consumption in finalizePreparedWallet;
     * all subsequent operations only transition already validated state.
     */
    private void validateRetentionBeforeFinalize(
            SaasMemberBalanceReservation reservation,
            LoyaltyApiModels.PreparedOwnerRequest request,
            boolean reconciled) {
        if (retentionClaims == null || reservation.getRetentionRevision() <= 0) return;
        List<SaasMemberBalanceRetentionClaim> values =
                retentionClaims.findByReservation_IdOrderByLotIdAsc(reservation.getId());
        if (values.isEmpty()) {
            if (reservation.getRetentionAttributedAmount().signum() == 0) return;
            if (reconciled && hasCommittedRetentionReceipt(reservation, request)) return;
            throw conflict("La reserva tiene retencion sin claims persistidos");
        }
        UUID sourceDocumentId = values.get(0).getSourceDocumentId();
        if (sourceDocumentId == null || values.stream()
                .anyMatch(value -> !sourceDocumentId.equals(value.getSourceDocumentId()))) {
            throw conflict("Todos los claims de retencion deben compartir documento origen");
        }
        List<LoyaltyApiModels.RetentionClaim> input = values.stream()
                .filter(value -> value.getStatus() != SaasMemberBalanceRetentionClaimStatus.CANCELLED)
                .map(value -> new LoyaltyApiModels.RetentionClaim(
                        value.getLotId(), value.getSourceMovementId(), value.getSourceDocumentId(),
                        value.getAmountOriginal(), value.getAmount(), value.getHeldAmount()))
                .toList();
        if (input.isEmpty()) {
            if (reservation.getRetentionAttributedAmount().signum() == 0) return;
            throw conflict("La retencion de la reserva ya esta cancelada");
        }
        BigDecimal total = input.stream()
                .map(LoyaltyApiModels.RetentionClaim::amount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        if (total.compareTo(reservation.getRetentionAttributedAmount()) != 0) {
            throw conflict("Los claims de retencion no coinciden con el saldo atribuido");
        }
        String calculated = retentionFingerprint(reservation.getRetentionAttributedAmount(), input);
        if (!calculated.equals(reservation.getRetentionFingerprint())) {
            throw conflict("El fingerprint de claims de retencion no coincide con la reserva");
        }
        BigDecimal knownHeld = values.stream()
                .filter(value -> value.getStatus() == SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN)
                .map(SaasMemberBalanceRetentionClaim::getHeldAmount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        BigDecimal preparedPlusRetention = reservation.getPreparedLoyaltyAmount().add(knownHeld);
        if (preparedPlusRetention.compareTo(reservation.getReservedLoyaltyAmount()) > 0) {
            throw conflict("El F10 preparado mas la retencion supera la capacidad reservada");
        }
    }

    private boolean hasCommittedRetentionReceipt(
            SaasMemberBalanceReservation reservation,
            LoyaltyApiModels.PreparedOwnerRequest request) {
        if (retentionReceipts == null) return false;
        SaasMemberBalanceRetentionReceipt receipt = receiptForOperation(request.operationId());
        if (receipt == null
                || receipt.getStatus() != SaasMemberBalanceRetentionReceiptStatus.COMMITTED) {
            return false;
        }
        LoyaltyApiModels.RetentionSnapshot snapshot = request.retentionSnapshot();
        if (snapshot == null) return false;
        return receipt.matchesImmutable(
                reservation.getAccount().getCompanyId(), reservation.getStoreId(),
                reservation.getAccount().getMemberId(), snapshot.sourceDocumentId(),
                snapshot.returnDocumentId(), snapshot.attributedAmount(), snapshot.fingerprint());
    }

    private void cancelClaimsForReservation(UUID reservationId, Instant now) {
        if (retentionClaims != null) {
            cancelClaims(retentionClaims.findByReservation_IdOrderByLotIdAsc(reservationId), now);
        }
    }

    private void cancelClaims(List<SaasMemberBalanceRetentionClaim> claims, Instant now) {
        claims.forEach(claim -> claim.release(now));
    }

    private List<LoyaltyApiModels.RetentionClaim> normalizeRetentionClaims(
            List<LoyaltyApiModels.RetentionClaim> values) {
        Set<String> unique = new HashSet<>();
        return values.stream().map(claim -> {
            if (claim == null || claim.lotId() == null || claim.sourceMovementId() == null) {
                throw invalid("Cada claim debe identificar lote y movimiento origen");
            }
            if (!unique.add(claim.lotId().toString())) {
                throw invalid("No puede repetirse el mismo lote en la retencion");
            }
            return claim;
        }).sorted(Comparator.comparing(value -> value.lotId().toString())).toList();
    }

    private String retentionFingerprint(BigDecimal attributedAmount,
            List<LoyaltyApiModels.RetentionClaim> claims) {
        BigDecimal total = claims.stream().map(LoyaltyApiModels.RetentionClaim::amount)
                .map(this::money).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        BigDecimal attributed = attributedAmount == null ? total : money(attributedAmount);
        if (total.compareTo(attributed) != 0) {
            throw invalid("Los claims de retencion deben coincidir exactamente con el saldo atribuido");
        }
        String canonical = attributed.toPlainString() + "\n" + claims.stream()
                .map(claim -> claim.lotId() + "|" + claim.sourceMovementId() + "|"
                        + claim.sourceDocumentId() + "|" + (claim.amountOriginal() == null
                            ? money(claim.amount()).toPlainString()
                            : money(claim.amountOriginal()).toPlainString())
                        + "|" + money(claim.amount()).toPlainString())
                .collect(Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo calcular la huella de retencion", exception);
        }
    }

    private LoyaltyApiModels.BootstrapResponse bootstrapResponse(
            String status,
            SaasMemberLoyaltyBootstrap bootstrap,
            int accountCount) {
        return new LoyaltyApiModels.BootstrapResponse(
                status,
                bootstrap.getSourceStoreId(),
                bootstrap.getSourceInstallationId(),
                bootstrap.getSourceChecksum(),
                bootstrap.getSnapshotAt(),
                accountCount);
    }

    private void requireBootstrapRequest(LoyaltyApiModels.BootstrapRequest request) {
        if (request == null
                || request.companyId() == null
                || request.storeId() == null
                || request.snapshotAt() == null
                || request.checksum() == null
                || request.checksum().isBlank()
                || request.accounts() == null) {
            throw invalid("Bootstrap incompleto");
        }
        if (request.snapshotAt().isAfter(clock.instant())) {
            throw invalid("snapshotAt no puede estar en el futuro");
        }
    }

    private void validateBootstrapAccount(
            LoyaltyApiModels.BootstrapAccount account,
            Instant snapshotAt,
            Set<UUID> memberIds,
            Set<UUID> lotIds) {
        if (account == null || account.memberId() == null || account.balance() == null || account.points() == null) {
            throw invalid("Cuenta de miembro incompleta en bootstrap");
        }
        if (!memberIds.add(account.memberId())) {
            throw invalid("Miembro duplicado en bootstrap: " + account.memberId());
        }
        BigDecimal balance = money(account.balance());
        BigDecimal points = points(account.points());
        if (balance.signum() < 0 || points.signum() < 0) {
            throw invalid("Saldo y puntos deben ser no negativos");
        }
        BigDecimal lotTotal = BigDecimal.ZERO.setScale(2);
        for (LoyaltyApiModels.BootstrapLot lot : safeList(account.lots())) {
            if (lot == null || lot.lotId() == null || lot.remainingAmount() == null || lot.createdAt() == null) {
                throw invalid("Lote incompleto en bootstrap");
            }
            if (!lotIds.add(lot.lotId())) {
                throw invalid("Lote duplicado en bootstrap: " + lot.lotId());
            }
            BigDecimal amount = money(lot.remainingAmount());
            if (amount.signum() <= 0) {
                throw invalid("Los lotes del bootstrap deben tener saldo positivo");
            }
            if (lot.createdAt().isAfter(snapshotAt)) {
                throw invalid("Un lote no puede ser posterior al snapshot");
            }
            if (lot.expiresAt() != null && !lot.expiresAt().isAfter(snapshotAt)) {
                throw invalid("El bootstrap contiene un lote ya caducado");
            }
            lotTotal = lotTotal.add(amount);
        }
        if (lotTotal.compareTo(balance) != 0) {
            throw invalid("El saldo del miembro no coincide con la suma de sus lotes: " + account.memberId());
        }
    }

    private void requireReserveRequest(LoyaltyApiModels.ReserveRequest request) {
        if (request == null || request.memberId() == null) {
            throw invalid("memberId es obligatorio");
        }
        requireText(request.terminalId(), "terminalId");
        requireText(request.saleId(), "saleId");
    }

    private void requireOwnerRequest(LoyaltyApiModels.ReservationOwnerRequest request) {
        if (request == null) {
            throw invalid("Datos de la venta obligatorios");
        }
        requireText(request.terminalId(), "terminalId");
        requireText(request.saleId(), "saleId");
    }

    private void requirePrepareRequest(LoyaltyApiModels.PrepareRequest request) {
        if (request == null || request.amount() == null || request.operationId() == null) {
            throw invalid("amount y operationId son obligatorios");
        }
        requireText(request.terminalId(), "terminalId");
        requireText(request.saleId(), "saleId");
    }

    private void requireWalletPrepareRequest(LoyaltyApiModels.WalletPrepareRequest request) {
        if (request == null
                || request.operationId() == null
                || request.loyaltyAmount() == null
                || request.returnCreditAmount() == null) {
            throw invalid("operationId, loyaltyAmount y returnCreditAmount son obligatorios");
        }
        requireText(request.terminalId(), "terminalId");
        requireText(request.saleId(), "saleId");
    }

    private void requireWalletAmounts(
            SaasMemberBalanceReservation reservation,
            BigDecimal loyaltyAmount,
            BigDecimal returnCreditAmount) {
        if (loyaltyAmount.signum() < 0 || returnCreditAmount.signum() < 0) {
            throw invalid("Los importes del monedero no pueden ser negativos");
        }
        // Retention-only returns are committed through the recovery outbox.
        // PREPARED/CONSUMED is reserved for an actual wallet spend and must
        // satisfy the V17 positive-amount contract.
        if (loyaltyAmount.add(returnCreditAmount).signum() <= 0) {
            throw invalid("Debe prepararse un importe positivo del monedero");
        }
        BigDecimal heldKnown = heldKnownAmount(reservation.getId());
        BigDecimal attributed = reservation.getRetentionAttributedAmount();
        if (loyaltyAmount.add(returnCreditAmount).signum() > 0
                && (pendingMissingAmount(reservation.getId()).signum() > 0
                || spentShortfallAmount(reservation.getId()).signum() > 0
                || recoveredKnownAmount(reservation.getId()).signum() > 0
                || attributed.compareTo(heldKnown) != 0)) {
            throw conflict("La retencion de devolucion aun no esta confirmada");
        }
        BigDecimal spendableLoyalty = reservation.getReservedLoyaltyAmount()
                .subtract(heldKnownAmount(reservation.getId())).max(BigDecimal.ZERO.setScale(2));
        if (loyaltyAmount.compareTo(spendableLoyalty) > 0) {
            throw invalid("loyaltyAmount supera el saldo LOYALTY reservado");
        }
        if (returnCreditAmount.compareTo(reservation.getReservedReturnCreditAmount()) > 0) {
            throw invalid("returnCreditAmount supera el saldo RETURN_CREDIT reservado");
        }
    }

    private void requirePreparedOwnerRequest(LoyaltyApiModels.PreparedOwnerRequest request) {
        if (request == null || request.operationId() == null) {
            throw invalid("operationId es obligatorio");
        }
        requireText(request.terminalId(), "terminalId");
        requireText(request.saleId(), "saleId");
    }

    private void requireRetentionConfigureRequest(LoyaltyApiModels.RetentionConfigureRequest request) {
        if (request == null) throw invalid("Datos de retencion obligatorios");
        requireOwnerRequest(new LoyaltyApiModels.ReservationOwnerRequest(
                request.companyId(), request.storeId(), request.terminalId(), request.saleId()));
        if (request.attributedAmount() != null) money(request.attributedAmount());
        if (request.retentionClaims() == null) throw invalid("retentionClaims es obligatorio");
        if (!request.retentionClaims().isEmpty()
                && (request.sourceDocumentId() == null
                || request.retentionClaims().stream().anyMatch(claim -> claim == null
                        || claim.sourceDocumentId() == null
                        || !request.sourceDocumentId().equals(claim.sourceDocumentId())))) {
            throw invalid("Todos los claims deben pertenecer al sourceDocumentId indicado");
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 120) {
            throw invalid(field + " es obligatorio y admite hasta 120 caracteres");
        }
    }

    String calculateChecksum(LoyaltyApiModels.BootstrapRequest request) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(request.companyId()).append('\n');
        safeList(request.accounts()).stream()
                .sorted(Comparator.comparing(account -> account.memberId().toString()))
                .forEach(account -> {
                    canonical.append(account.memberId())
                            .append('|').append(money(account.balance()).toPlainString())
                            .append('|').append(points(account.points()).toPlainString())
                            .append('\n');
                    safeList(account.lots()).stream()
                            .sorted(Comparator.comparing(lot -> lot.lotId().toString()))
                            .forEach(lot -> canonical.append(lot.lotId())
                                    .append('|').append(money(lot.remainingAmount()).toPlainString())
                                    .append('|').append(lot.createdAt())
                                    .append('|').append(Objects.toString(lot.expiresAt(), ""))
                                    .append('|').append(Objects.toString(lot.sourceMovementId(), ""))
                                    .append('\n'));
                });
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo calcular el checksum del bootstrap", exception);
        }
    }

    private BigDecimal money(BigDecimal value) {
        if (value == null) {
            throw invalid("Importe monetario obligatorio");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw invalid("Los importes monetarios admiten como maximo dos decimales");
        }
    }

    private BigDecimal points(BigDecimal value) {
        if (value == null) {
            throw invalid("Puntos obligatorios");
        }
        try {
            return value.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw invalid("Los puntos admiten como maximo cuatro decimales");
        }
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private MemberBalanceReservationConflictException reservationConflict(String message) {
        return new MemberBalanceReservationConflictException(message);
    }
}
