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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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

    MemberBalanceReservationService(
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
            active = null;
        }
        if (active != null) {
            if (!active.belongsTo(installation.getId(), request.terminalId().trim(), request.saleId().trim())) {
                throw conflict("El saldo del socio esta reservado temporalmente en otra caja");
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
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "El socio no dispone de saldo utilizable");
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
            active = null;
        }
        if (active != null) {
            if (!active.belongsTo(installation.getId(), request.terminalId().trim(), request.saleId().trim())) {
                throw conflict("El monedero del socio esta reservado temporalmente en otra caja");
            }
            active.renew(now, LEASE_DURATION);
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
        if (availableLoyalty.add(availableReturnCredit).signum() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "El socio no dispone de saldo utilizable en su monedero");
        }

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
        reservation.release(clock.instant());
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
        SaasMemberBalanceAccount account = accountForUpdate(
                request.companyId(), reservation.getAccount().getMemberId());
        requireOwner(reservation, installation, new LoyaltyApiModels.ReservationOwnerRequest(
                request.companyId(), request.storeId(), request.terminalId(), request.saleId()));
        if (SaasMemberBalanceReservation.CONSUMED.equals(reservation.getStatus())) {
            if (reservation.preparedBy(request.operationId())) {
                return walletResponse(reservation, account);
            }
            throw conflict("La reserva de monedero ya fue finalizada por otra operacion");
        }
        if (!reservation.isPrepared() || !reservation.preparedBy(request.operationId())) {
            throw conflict("La reserva de monedero no esta preparada para esta operacion");
        }

        List<SaasMemberBalanceReservationLot> preparedLots = walletReservationLots(reservationId);
        consumeReservedType(
                preparedLots,
                MemberBalanceType.LOYALTY,
                reservation.getPreparedLoyaltyAmount());
        consumeReservedType(
                preparedLots,
                MemberBalanceType.RETURN_CREDIT,
                reservation.getPreparedReturnCreditAmount());

        Instant now = clock.instant();
        if (reservation.getPreparedLoyaltyAmount().signum() > 0) {
            account.debit(MemberBalanceType.LOYALTY, reservation.getPreparedLoyaltyAmount(), now);
        }
        if (reservation.getPreparedReturnCreditAmount().signum() > 0) {
            account.debit(
                    MemberBalanceType.RETURN_CREDIT,
                    reservation.getPreparedReturnCreditAmount(),
                    now);
        }
        reservation.finalizePreparedTyped(request.operationId(), now);
        return walletResponse(reservation, account);
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
        reservation.abortPrepared(request.operationId(), clock.instant());
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
            throw conflict("El bootstrap historico multi-tienda del monedero aun no esta completado");
        }
    }

    private SaasMemberBalanceAccount accountForUpdate(UUID companyId, UUID memberId) {
        return accounts.findForUpdate(companyId, memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Socio no encontrado en SaaS"));
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

    private LoyaltyApiModels.WalletReservationResponse walletResponse(
            SaasMemberBalanceReservation reservation,
            SaasMemberBalanceAccount account) {
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
                LEASE_SECONDS);
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
            throw invalid("Cuenta de socio incompleta en bootstrap");
        }
        if (!memberIds.add(account.memberId())) {
            throw invalid("Socio duplicado en bootstrap: " + account.memberId());
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
            throw invalid("El saldo del socio no coincide con la suma de sus lotes: " + account.memberId());
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
        if (loyaltyAmount.add(returnCreditAmount).signum() <= 0) {
            throw invalid("Debe prepararse un importe positivo del monedero");
        }
        if (loyaltyAmount.compareTo(reservation.getReservedLoyaltyAmount()) > 0) {
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
}
