package com.tpverp.saas.loyalty;

import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasInstallationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberWalletBootstrapIngestionService {

    private static final int MAX_CHUNK_SIZE = 500;
    private static final Pattern HASH = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final String ACCOUNTS = "ACCOUNTS";
    private static final String LOTS = "LOTS";

    private final SaasInstallationRepository installations;
    private final InstallationAuthenticator authenticator;
    private final SaasMemberWalletBootstrapRepository bootstraps;
    private final SaasMemberWalletBootstrapStoreRepository expectedStores;
    private final SaasMemberWalletBootstrapSnapshotRepository snapshots;
    private final SaasMemberWalletBootstrapChunkRepository chunks;
    private final SaasMemberWalletBootstrapStagingAccountRepository stagingAccounts;
    private final SaasMemberWalletBootstrapStagingLotRepository stagingLots;
    private final MemberWalletBootstrapReconciliationService reconciliation;
    private final MemberWalletBootstrapStatusService statuses;
    private final Clock clock;

    public MemberWalletBootstrapIngestionService(
            SaasInstallationRepository installations,
            InstallationAuthenticator authenticator,
            SaasMemberWalletBootstrapRepository bootstraps,
            SaasMemberWalletBootstrapStoreRepository expectedStores,
            SaasMemberWalletBootstrapSnapshotRepository snapshots,
            SaasMemberWalletBootstrapChunkRepository chunks,
            SaasMemberWalletBootstrapStagingAccountRepository stagingAccounts,
            SaasMemberWalletBootstrapStagingLotRepository stagingLots,
            MemberWalletBootstrapReconciliationService reconciliation,
            MemberWalletBootstrapStatusService statuses,
            Clock clock) {
        this.installations = installations;
        this.authenticator = authenticator;
        this.bootstraps = bootstraps;
        this.expectedStores = expectedStores;
        this.snapshots = snapshots;
        this.chunks = chunks;
        this.stagingAccounts = stagingAccounts;
        this.stagingLots = stagingLots;
        this.reconciliation = reconciliation;
        this.statuses = statuses;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LoyaltyApiModels.WalletBootstrapStatus discover(
            LoyaltyApiModels.WalletBootstrapDiscoverRequest request,
            String token) {
        if (request == null || request.companyId() == null || request.storeId() == null) {
            throw invalid("companyId y storeId son obligatorios");
        }
        authenticate(request.companyId(), request.storeId(), token);
        SaasMemberWalletBootstrap bootstrap = bootstraps
                .findFirstByCompany_IdOrderByCreatedAtDesc(request.companyId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No existe bootstrap historico para la empresa"));
        requireExpectedStore(bootstrap, request.storeId());
        return statuses.status(bootstrap);
    }

    @Transactional(readOnly = true)
    public LoyaltyApiModels.WalletBootstrapStatus status(
            UUID bootstrapId,
            LoyaltyApiModels.BootstrapStoreRequest request,
            String token) {
        if (request == null || request.companyId() == null || request.storeId() == null) {
            throw invalid("companyId y storeId son obligatorios");
        }
        authenticate(request.companyId(), request.storeId(), token);
        SaasMemberWalletBootstrap bootstrap = bootstraps.findById(bootstrapId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Bootstrap no encontrado"));
        if (!bootstrap.getCompanyId().equals(request.companyId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "El bootstrap pertenece a otra empresa");
        }
        requireExpectedStore(bootstrap, request.storeId());
        return statuses.status(bootstrap);
    }

    @Transactional(noRollbackFor = MemberWalletBootstrapConflictException.class)
    public LoyaltyApiModels.WalletBootstrapStatus begin(
            UUID bootstrapId,
            LoyaltyApiModels.WalletBootstrapBeginRequest request,
            String token) {
        validateBegin(request);
        authenticate(request.companyId(), request.storeId(), token);
        SaasMemberWalletBootstrap bootstrap = lockBootstrap(bootstrapId, request.companyId());
        SaasMemberWalletBootstrapStore store = requireExpectedStore(bootstrap, request.storeId());
        String checksum = normalizeHash(request.snapshotChecksum(), "snapshotChecksum");

        SaasMemberWalletBootstrapSnapshot sameId = snapshots
                .findByBootstrap_IdAndSnapshotId(bootstrapId, request.snapshotId())
                .orElse(null);
        if (sameId != null) {
            if (sameId.matches(request, checksum)) {
                return statuses.status(bootstrap);
            }
            throw persistConflict(bootstrap, store, sameId, "snapshotId reutilizado con metadatos diferentes");
        }
        requireCollecting(bootstrap, store, null);
        SaasMemberWalletBootstrapSnapshot sameStore = snapshots
                .findByBootstrap_IdAndStoreId(bootstrapId, request.storeId())
                .orElse(null);
        if (sameStore != null) {
            throw persistConflict(bootstrap, store, sameStore, "La tienda ya inicio otro snapshot");
        }
        if (bootstrap.getCutoffAt() == null) {
            bootstrap.establishCutoff(request.cutoffAt());
        } else if (!bootstrap.getCutoffAt().equals(request.cutoffAt())) {
            throw persistConflict(bootstrap, store, null, "Todas las tiendas deben usar el mismo cutoffAt");
        }
        snapshots.save(new SaasMemberWalletBootstrapSnapshot(
                UUID.randomUUID(),
                bootstrap,
                request,
                checksum,
                clock.instant()));
        return statuses.status(bootstrap);
    }

    @Transactional(noRollbackFor = MemberWalletBootstrapConflictException.class)
    public LoyaltyApiModels.WalletBootstrapStatus chunk(
            UUID bootstrapId,
            UUID snapshotId,
            String rawKind,
            int index,
            LoyaltyApiModels.WalletBootstrapChunkRequest request,
            String token) {
        String kind = requireKind(rawKind);
        NormalizedChunk normalized = normalizeChunk(kind, request);
        authenticate(request.companyId(), request.storeId(), token);
        SaasMemberWalletBootstrap bootstrap = lockBootstrap(bootstrapId, request.companyId());
        SaasMemberWalletBootstrapStore store = requireExpectedStore(bootstrap, request.storeId());
        SaasMemberWalletBootstrapSnapshot snapshot = snapshots
                .findByBootstrap_IdAndSnapshotId(bootstrapId, snapshotId)
                .filter(value -> value.getStoreId().equals(request.storeId()))
                .orElse(null);
        String suppliedHash = normalizeHash(request.chunkHash(), "chunkHash");
        if (!suppliedHash.equals(normalized.hash())) {
            throw completedSafeConflict(
                    bootstrap,
                    store,
                    snapshot,
                    "chunkHash no coincide con el contenido canonico");
        }
        if (snapshot == null) {
            if (bootstrap.isCompleted()) {
                throw immutableCompletedConflict("snapshotId desconocido en reintento de chunk");
            }
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Snapshot no encontrado para la tienda");
        }

        SaasMemberWalletBootstrapChunk existing = chunks
                .findBySnapshot_IdAndKindAndChunkIndex(snapshot.getId(), kind, index)
                .orElse(null);
        if (existing != null) {
            if (existing.getChunkHash().equals(suppliedHash)
                    && existing.getRecordCount() == normalized.recordCount()) {
                return statuses.status(bootstrap);
            }
            throw completedSafeConflict(
                    bootstrap,
                    store,
                    snapshot,
                    "Chunk reutilizado con contenido diferente");
        }
        requireCollecting(bootstrap, store, snapshot);
        int expectedChunkCount = ACCOUNTS.equals(kind)
                ? snapshot.getAccountChunkCount()
                : snapshot.getLotChunkCount();
        if (index < 0 || index >= expectedChunkCount) {
            throw invalid("Indice de chunk fuera del rango declarado");
        }

        if (ACCOUNTS.equals(kind)) {
            for (LoyaltyApiModels.SnapshotAccount account : normalized.accounts()) {
                if (stagingAccounts.existsBySnapshot_IdAndMemberId(snapshot.getId(), account.memberId())) {
                    throw persistConflict(bootstrap, store, snapshot, "memberId repetido entre chunks: "
                            + account.memberId());
                }
            }
        } else {
            for (LoyaltyApiModels.SnapshotLot lot : normalized.lots()) {
                if (stagingLots.existsBySnapshot_IdAndLotId(snapshot.getId(), lot.lotId())) {
                    throw persistConflict(bootstrap, store, snapshot, "lotId repetido entre chunks: "
                            + lot.lotId());
                }
            }
        }

        chunks.save(new SaasMemberWalletBootstrapChunk(
                UUID.randomUUID(),
                snapshot,
                kind,
                index,
                suppliedHash,
                normalized.recordCount()));
        for (LoyaltyApiModels.SnapshotAccount account : normalized.accounts()) {
            stagingAccounts.save(new SaasMemberWalletBootstrapStagingAccount(
                    UUID.randomUUID(),
                    snapshot,
                    account.memberId(),
                    money(account.loyaltyBalance()),
                    money(account.returnCreditBalance())));
        }
        for (LoyaltyApiModels.SnapshotLot lot : normalized.lots()) {
            stagingLots.save(new SaasMemberWalletBootstrapStagingLot(
                    UUID.randomUUID(),
                    snapshot,
                    lot,
                    money(lot.originalAmount()),
                    money(lot.remainingAmount())));
        }
        return statuses.status(bootstrap);
    }

    @Transactional(noRollbackFor = MemberWalletBootstrapConflictException.class)
    public LoyaltyApiModels.WalletBootstrapStatus complete(
            UUID bootstrapId,
            UUID snapshotId,
            LoyaltyApiModels.WalletBootstrapCompleteRequest request,
            String token) {
        if (request == null || request.companyId() == null || request.storeId() == null) {
            throw invalid("companyId y storeId son obligatorios");
        }
        String suppliedChecksum = normalizeHash(request.snapshotChecksum(), "snapshotChecksum");
        authenticate(request.companyId(), request.storeId(), token);
        SaasMemberWalletBootstrap bootstrap = lockBootstrap(bootstrapId, request.companyId());
        SaasMemberWalletBootstrapStore store = requireExpectedStore(bootstrap, request.storeId());
        SaasMemberWalletBootstrapSnapshot snapshot = snapshots
                .findByBootstrap_IdAndSnapshotId(bootstrapId, snapshotId)
                .filter(value -> value.getStoreId().equals(request.storeId()))
                .orElse(null);
        if (snapshot == null) {
            if (bootstrap.isCompleted()) {
                throw immutableCompletedConflict("snapshotId desconocido en reintento de complete");
            }
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Snapshot no encontrado para la tienda");
        }
        if (snapshot.isCompleted() && snapshot.getSnapshotChecksum().equals(suppliedChecksum)) {
            return statuses.status(bootstrap);
        }
        requireCollecting(bootstrap, store, snapshot);
        if (!snapshot.getSnapshotChecksum().equals(suppliedChecksum)) {
            throw persistConflict(bootstrap, store, snapshot, "snapshotChecksum difiere del declarado en begin");
        }

        List<SaasMemberWalletBootstrapChunk> received = chunks.findBySnapshot_Id(snapshot.getId());
        long accountChunks = received.stream().filter(value -> ACCOUNTS.equals(value.getKind())).count();
        long lotChunks = received.stream().filter(value -> LOTS.equals(value.getKind())).count();
        long accountRecords = received.stream()
                .filter(value -> ACCOUNTS.equals(value.getKind()))
                .mapToLong(SaasMemberWalletBootstrapChunk::getRecordCount)
                .sum();
        long lotRecords = received.stream()
                .filter(value -> LOTS.equals(value.getKind()))
                .mapToLong(SaasMemberWalletBootstrapChunk::getRecordCount)
                .sum();
        if (accountChunks != snapshot.getAccountChunkCount()
                || lotChunks != snapshot.getLotChunkCount()
                || accountRecords != snapshot.getAccountCount()
                || lotRecords != snapshot.getLotCount()
                || stagingAccounts.countBySnapshot_Id(snapshot.getId()) != snapshot.getAccountCount()
                || stagingLots.countBySnapshot_Id(snapshot.getId()) != snapshot.getLotCount()) {
            throw persistConflict(bootstrap, store, snapshot, "Faltan chunks o los recuentos no coinciden");
        }
        String calculatedChecksum = snapshotChecksum(received);
        if (!calculatedChecksum.equals(suppliedChecksum)) {
            throw persistConflict(bootstrap, store, snapshot, "snapshotChecksum no coincide con los chunks recibidos");
        }
        String accountError = validateSnapshotAccounts(snapshot);
        if (accountError != null) {
            throw persistConflict(bootstrap, store, snapshot, accountError);
        }

        Instant now = clock.instant();
        snapshot.complete(now);
        store.complete(now);
        List<SaasMemberWalletBootstrapStore> allStores = expectedStores
                .findByBootstrap_IdOrderByStoreIdAsc(bootstrapId);
        if (allStores.stream().allMatch(value -> value.getCompletedAt() != null)) {
            bootstrap.beginReconciliation();
            try {
                reconciliation.reconcile(bootstrap, now);
                bootstrap.complete(now);
            } catch (MemberWalletBootstrapConflictException exception) {
                bootstrap.markConflict(exception.getReason());
                for (UUID conflictStoreId : exception.getConflictStoreIds()) {
                    expectedStores.findByBootstrap_IdAndStoreId(bootstrapId, conflictStoreId)
                            .ifPresent(value -> value.markConflict(exception.getReason()));
                    snapshots.findByBootstrap_IdAndStoreId(bootstrapId, conflictStoreId)
                            .ifPresent(value -> value.markConflict(exception.getReason()));
                }
                throw exception;
            }
        }
        return statuses.status(bootstrap);
    }

    private String validateSnapshotAccounts(SaasMemberWalletBootstrapSnapshot snapshot) {
        List<SaasMemberWalletBootstrapStagingAccount> accounts = stagingAccounts
                .findBySnapshot_IdOrderByMemberIdAsc(snapshot.getId());
        List<SaasMemberWalletBootstrapStagingLot> lots = stagingLots
                .findBySnapshot_IdOrderByLotIdAsc(snapshot.getId());
        Map<UUID, SaasMemberWalletBootstrapStagingAccount> byMember = new HashMap<>();
        for (SaasMemberWalletBootstrapStagingAccount account : accounts) {
            byMember.put(account.getMemberId(), account);
        }
        Map<AccountTypeKey, BigDecimal> sums = new HashMap<>();
        for (SaasMemberWalletBootstrapStagingLot lot : lots) {
            if (!byMember.containsKey(lot.getMemberId())) {
                return "El lote " + lot.getLotId() + " no tiene SnapshotAccount en su tienda";
            }
            BigDecimal effective = isExpiredAt(lot.getExpiresAt(), snapshot.getCutoffAt())
                    ? BigDecimal.ZERO.setScale(2)
                    : lot.getRemainingAmount();
            sums.merge(
                    new AccountTypeKey(lot.getMemberId(), lot.getBalanceType()),
                    effective,
                    BigDecimal::add);
        }
        for (SaasMemberWalletBootstrapStagingAccount account : accounts) {
            BigDecimal loyalty = sums.getOrDefault(
                    new AccountTypeKey(account.getMemberId(), MemberBalanceType.LOYALTY),
                    BigDecimal.ZERO.setScale(2));
            BigDecimal returnCredit = sums.getOrDefault(
                    new AccountTypeKey(account.getMemberId(), MemberBalanceType.RETURN_CREDIT),
                    BigDecimal.ZERO.setScale(2));
            if (loyalty.compareTo(account.getLoyaltyBalance()) != 0
                    || returnCredit.compareTo(account.getReturnCreditBalance()) != 0) {
                return "Los saldos declarados no cuadran con los lotes efectivos del socio "
                        + account.getMemberId();
            }
        }
        return null;
    }

    private NormalizedChunk normalizeChunk(
            String kind,
            LoyaltyApiModels.WalletBootstrapChunkRequest request) {
        if (request == null || request.companyId() == null || request.storeId() == null) {
            throw invalid("companyId y storeId son obligatorios");
        }
        List<LoyaltyApiModels.SnapshotAccount> sourceAccounts = safeList(request.accounts());
        List<LoyaltyApiModels.SnapshotLot> sourceLots = safeList(request.lots());
        if (ACCOUNTS.equals(kind) && !sourceLots.isEmpty()) {
            throw invalid("Un chunk ACCOUNTS no puede contener lots");
        }
        if (LOTS.equals(kind) && !sourceAccounts.isEmpty()) {
            throw invalid("Un chunk LOTS no puede contener accounts");
        }
        int size = ACCOUNTS.equals(kind) ? sourceAccounts.size() : sourceLots.size();
        if (size <= 0 || size > MAX_CHUNK_SIZE) {
            throw invalid("Cada chunk debe contener entre 1 y 500 registros");
        }

        List<LoyaltyApiModels.SnapshotAccount> normalizedAccounts = new ArrayList<>();
        Set<UUID> memberIds = new HashSet<>();
        for (LoyaltyApiModels.SnapshotAccount account : sourceAccounts) {
            if (account == null || account.memberId() == null) {
                throw invalid("SnapshotAccount incompleto");
            }
            if (!memberIds.add(account.memberId())) {
                throw invalid("memberId duplicado dentro del chunk");
            }
            BigDecimal loyalty = money(account.loyaltyBalance());
            BigDecimal returnCredit = money(account.returnCreditBalance());
            if (loyalty.signum() < 0 || returnCredit.signum() < 0) {
                throw invalid("Los saldos declarados no pueden ser negativos");
            }
            normalizedAccounts.add(new LoyaltyApiModels.SnapshotAccount(
                    account.memberId(),
                    loyalty,
                    returnCredit));
        }
        normalizedAccounts.sort(Comparator.comparing(
                account -> account.memberId().toString()));

        List<LoyaltyApiModels.SnapshotLot> normalizedLots = new ArrayList<>();
        Set<UUID> lotIds = new HashSet<>();
        for (LoyaltyApiModels.SnapshotLot lot : sourceLots) {
            if (lot == null
                    || lot.lotId() == null
                    || lot.memberId() == null
                    || lot.balanceType() == null
                    || lot.createdAt() == null) {
                throw invalid("SnapshotLot incompleto");
            }
            if (!lotIds.add(lot.lotId())) {
                throw invalid("lotId duplicado dentro del chunk");
            }
            BigDecimal original = money(lot.originalAmount());
            BigDecimal remaining = money(lot.remainingAmount());
            if (original.signum() <= 0 || remaining.signum() < 0 || remaining.compareTo(original) > 0) {
                throw invalid("Importes de SnapshotLot no validos");
            }
            normalizedLots.add(new LoyaltyApiModels.SnapshotLot(
                    lot.lotId(),
                    lot.memberId(),
                    lot.balanceType(),
                    original,
                    remaining,
                    lot.createdAt(),
                    lot.expiresAt(),
                    lot.sourceMovementId(),
                    lot.documentId()));
        }
        normalizedLots.sort(Comparator.comparing(
                lot -> lot.lotId().toString()));

        StringBuilder canonical = new StringBuilder();
        for (LoyaltyApiModels.SnapshotAccount account : normalizedAccounts) {
            canonical.append(accountLine(account));
        }
        for (LoyaltyApiModels.SnapshotLot lot : normalizedLots) {
            canonical.append(lotLine(lot));
        }
        return new NormalizedChunk(
                normalizedAccounts,
                normalizedLots,
                sha256(canonical.toString()),
                size);
    }

    private void validateBegin(LoyaltyApiModels.WalletBootstrapBeginRequest request) {
        if (request == null
                || request.companyId() == null
                || request.storeId() == null
                || request.snapshotId() == null
                || request.cutoffAt() == null) {
            throw invalid("Begin de snapshot incompleto");
        }
        validateCounts(request.accountCount(), request.accountChunkCount(), "accounts");
        validateCounts(request.lotCount(), request.lotChunkCount(), "lots");
        normalizeHash(request.snapshotChecksum(), "snapshotChecksum");
    }

    private void validateCounts(int count, int chunkCount, String field) {
        if (count < 0 || chunkCount < 0) {
            throw invalid("Los counts de " + field + " no pueden ser negativos");
        }
        if ((count == 0) != (chunkCount == 0)) {
            throw invalid("Un snapshot vacio debe declarar cero chunks en " + field);
        }
        if (count > 0) {
            int minimumChunks = (count + MAX_CHUNK_SIZE - 1) / MAX_CHUNK_SIZE;
            if (chunkCount < minimumChunks || chunkCount > count) {
                throw invalid("chunkCount incompatible con count y el limite de 500 en " + field);
            }
        }
    }

    private SaasMemberWalletBootstrap lockBootstrap(UUID bootstrapId, UUID companyId) {
        if (bootstrapId == null || companyId == null) {
            throw invalid("bootstrapId y companyId son obligatorios");
        }
        return bootstraps.findForUpdate(bootstrapId)
                .filter(value -> value.getCompanyId().equals(companyId))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Bootstrap no encontrado para la empresa"));
    }

    private SaasMemberWalletBootstrapStore requireExpectedStore(
            SaasMemberWalletBootstrap bootstrap,
            UUID storeId) {
        return expectedStores.findByBootstrap_IdAndStoreId(bootstrap.getId(), storeId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "La tienda no forma parte de expectedStoreIds"));
    }

    private SaasMemberWalletBootstrapSnapshot requireSnapshot(
            SaasMemberWalletBootstrap bootstrap,
            UUID snapshotId,
            UUID storeId) {
        return snapshots.findByBootstrap_IdAndSnapshotId(bootstrap.getId(), snapshotId)
                .filter(value -> value.getStoreId().equals(storeId))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Snapshot no encontrado para la tienda"));
    }

    private void requireCollecting(
            SaasMemberWalletBootstrap bootstrap,
            SaasMemberWalletBootstrapStore store,
            SaasMemberWalletBootstrapSnapshot snapshot) {
        if (!bootstrap.isCollecting()) {
            throw persistConflict(
                    bootstrap,
                    store,
                    snapshot,
                    "El bootstrap ya no admite nuevos datos: " + bootstrap.getStatus());
        }
    }

    private MemberWalletBootstrapConflictException persistConflict(
            SaasMemberWalletBootstrap bootstrap,
            SaasMemberWalletBootstrapStore store,
            SaasMemberWalletBootstrapSnapshot snapshot,
            String reason) {
        if (bootstrap.isCompleted()) {
            return immutableCompletedConflict(reason);
        }
        bootstrap.markConflict(reason);
        store.markConflict(reason);
        if (snapshot != null) {
            snapshot.markConflict(reason);
        }
        return new MemberWalletBootstrapConflictException(reason, Set.of(store.getStoreId()));
    }

    private MemberWalletBootstrapConflictException completedSafeConflict(
            SaasMemberWalletBootstrap bootstrap,
            SaasMemberWalletBootstrapStore store,
            SaasMemberWalletBootstrapSnapshot snapshot,
            String reason) {
        return bootstrap.isCompleted()
                ? immutableCompletedConflict(reason)
                : persistConflict(bootstrap, store, snapshot, reason);
    }

    private MemberWalletBootstrapConflictException immutableCompletedConflict(String reason) {
        return new MemberWalletBootstrapConflictException(
                "Bootstrap COMPLETED inmutable: " + reason,
                Set.of());
    }

    private void authenticate(UUID companyId, UUID storeId, String token) {
        authenticator.requireLinkedInstallation(
                companyId,
                storeId,
                installations.findByCompany_IdAndStore_Id(companyId, storeId),
                token);
    }

    private String snapshotChecksum(List<SaasMemberWalletBootstrapChunk> values) {
        StringBuilder canonical = new StringBuilder();
        values.stream()
                .sorted(Comparator
                        .comparingInt((SaasMemberWalletBootstrapChunk value) ->
                                ACCOUNTS.equals(value.getKind()) ? 0 : 1)
                        .thenComparingInt(SaasMemberWalletBootstrapChunk::getChunkIndex))
                .forEach(value -> canonical
                        .append(value.getKind())
                        .append('|')
                        .append(value.getChunkIndex())
                        .append('|')
                        .append(value.getChunkHash())
                        .append('\n'));
        return sha256(canonical.toString());
    }

    private String accountLine(LoyaltyApiModels.SnapshotAccount account) {
        return "A|" + account.memberId()
                + "|" + money(account.loyaltyBalance()).toPlainString()
                + "|" + money(account.returnCreditBalance()).toPlainString()
                + "\n";
    }

    private String lotLine(LoyaltyApiModels.SnapshotLot lot) {
        return "L|" + lot.lotId()
                + "|" + lot.memberId()
                + "|" + lot.balanceType()
                + "|" + money(lot.originalAmount()).toPlainString()
                + "|" + money(lot.remainingAmount()).toPlainString()
                + "|" + lot.createdAt()
                + "|" + Objects.toString(lot.expiresAt(), "-")
                + "|" + Objects.toString(lot.sourceMovementId(), "-")
                + "|" + Objects.toString(lot.documentId(), "-")
                + "\n";
    }

    private String requireKind(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!ACCOUNTS.equals(normalized) && !LOTS.equals(normalized)) {
            throw invalid("kind debe ser ACCOUNTS o LOTS");
        }
        return normalized;
    }

    private String normalizeHash(String value, String field) {
        if (value == null || !HASH.matcher(value.trim()).matches()) {
            throw invalid(field + " debe ser SHA-256 hexadecimal");
        }
        return value.trim().toLowerCase(Locale.ROOT);
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

    private boolean isExpiredAt(Instant expiresAt, Instant cutoffAt) {
        return expiresAt != null && !expiresAt.isAfter(cutoffAt);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo calcular SHA-256", exception);
        }
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private record NormalizedChunk(
            List<LoyaltyApiModels.SnapshotAccount> accounts,
            List<LoyaltyApiModels.SnapshotLot> lots,
            String hash,
            int recordCount) {
    }

    private record AccountTypeKey(UUID memberId, MemberBalanceType balanceType) {
    }
}
