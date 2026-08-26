package com.tpverp.saas.loyalty;

import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.loyalty.MemberCategoryBootstrapApiModels.AssignmentValue;
import com.tpverp.saas.loyalty.MemberCategoryBootstrapApiModels.BeginRequest;
import com.tpverp.saas.loyalty.MemberCategoryBootstrapApiModels.CategoryValue;
import com.tpverp.saas.loyalty.MemberCategoryBootstrapApiModels.ChunkRequest;
import com.tpverp.saas.loyalty.MemberCategoryBootstrapApiModels.CompleteRequest;
import com.tpverp.saas.loyalty.MemberCategoryBootstrapApiModels.Status;
import com.tpverp.saas.loyalty.MemberCategoryBootstrapApiModels.StoreRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberCategoryBootstrapService {
    private static final int MAX = 500;
    private static final String CATEGORIES = "CATEGORIES";
    private static final String ASSIGNMENTS = "ASSIGNMENTS";

    private final SaasInstallationRepository installations;
    private final InstallationAuthenticator authenticator;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MemberCategoryBootstrapService(
            SaasInstallationRepository installations,
            InstallationAuthenticator authenticator,
            JdbcTemplate jdbc,
            Clock clock) {
        this.installations = installations;
        this.authenticator = authenticator;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public Status discover(StoreRequest request, String token) {
        Context context = authenticate(request, token);
        jdbc.query(
                "select pg_advisory_xact_lock(hashtext(?)::bigint)",
                (rs, row) -> Boolean.TRUE,
                context.companyId().toString());
        UUID existing = discoverableBootstrap(context.companyId());
        if (existing != null) {
            return status(existing, context);
        }
        List<UUID> expectedStores = installations.findByCompany_IdAndActiveTrue(context.companyId()).stream()
                .map(value -> value.getStore().getId())
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        if (expectedStores.isEmpty()) {
            throw invalid("La empresa no tiene tiendas vinculadas");
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update("""
                insert into saas_member_category_bootstrap (
                    id, company_id, status, expected_store_count, created_at
                ) values (?, ?, 'COLLECTING', ?, ?)
                """, id, context.companyId(), expectedStores.size(), timestamp(now));
        expectedStores.forEach(storeId -> jdbc.update("""
                insert into saas_member_category_bootstrap_store (
                    id, bootstrap_id, store_id
                ) values (?, ?, ?)
                """, UUID.randomUUID(), id, storeId));
        return status(id, context);
    }

    @Transactional(readOnly = true)
    public Status status(UUID bootstrapId, StoreRequest request, String token) {
        Context context = authenticate(request, token);
        return status(bootstrapId, context);
    }

    @Transactional
    public Status begin(UUID bootstrapId, BeginRequest request, String token) {
        Context context = authenticate(request == null
                ? null : new StoreRequest(request.companyId(), request.storeId()), token);
        validateBegin(request);
        Bootstrap bootstrap = lockBootstrap(bootstrapId, context.companyId());
        requireCollecting(bootstrap);
        requireExpectedStore(bootstrapId, context.storeId());
        List<UUID> existing = jdbc.query(
                "select snapshot_id from saas_member_category_bootstrap_snapshot where bootstrap_id=? and store_id=?",
                (rs, row) -> rs.getObject(1, UUID.class),
                bootstrapId,
                context.storeId());
        if (!existing.isEmpty()) {
            if (existing.get(0).equals(request.snapshotId())) {
                return status(bootstrapId, context);
            }
            return conflict(bootstrapId, context.storeId(),
                    "La tienda ya tiene otro snapshot inmutable");
        }
        jdbc.update("""
                insert into saas_member_category_bootstrap_snapshot (
                    snapshot_id, bootstrap_id, store_id,
                    category_chunk_count, assignment_chunk_count,
                    category_count, assignment_count,
                    category_hash, assignment_hash, snapshot_checksum, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                request.snapshotId(), bootstrapId, context.storeId(),
                request.categoryChunkCount(), request.assignmentChunkCount(),
                request.categoryCount(), request.assignmentCount(),
                hash(request.categoryHash()), hash(request.assignmentHash()),
                hash(request.snapshotChecksum()), timestamp(clock.instant()));
        jdbc.update("""
                update saas_member_category_bootstrap_store
                set snapshot_id=? where bootstrap_id=? and store_id=?
                """, request.snapshotId(), bootstrapId, context.storeId());
        return status(bootstrapId, context);
    }

    @Transactional
    public Status chunk(
            UUID bootstrapId,
            UUID snapshotId,
            String rawKind,
            int index,
            ChunkRequest request,
            String token) {
        Context context = authenticate(request == null
                ? null : new StoreRequest(request.companyId(), request.storeId()), token);
        String kind = kind(rawKind);
        if (index < 0 || request == null) {
            throw invalid("Chunk invalido");
        }
        Bootstrap bootstrap = lockBootstrap(bootstrapId, context.companyId());
        requireCollecting(bootstrap);
        Snapshot snapshot = snapshot(bootstrapId, snapshotId, context.storeId());
        int expectedChunks = CATEGORIES.equals(kind)
                ? snapshot.categoryChunkCount() : snapshot.assignmentChunkCount();
        if (index >= expectedChunks) {
            throw invalid("Indice de chunk fuera del manifiesto");
        }
        Normalized normalized = normalize(kind, request);
        if (!normalized.hash().equalsIgnoreCase(hash(request.chunkHash()))) {
            return conflict(bootstrapId, context.storeId(), "chunkHash no coincide");
        }
        List<String> receipt = jdbc.query(
                "select chunk_hash from saas_member_category_bootstrap_chunk where snapshot_id=? and kind=? and chunk_index=?",
                (rs, row) -> rs.getString(1), snapshotId, kind, index);
        if (!receipt.isEmpty()) {
            if (receipt.get(0).equalsIgnoreCase(normalized.hash())) {
                return status(bootstrapId, context);
            }
            return conflict(bootstrapId, context.storeId(),
                    "El chunk ya existe con otro contenido");
        }
        try {
            if (CATEGORIES.equals(kind)) {
                for (CategoryValue value : normalized.categories()) {
                    jdbc.update("""
                            insert into saas_member_category_bootstrap_category (
                                id, snapshot_id, category_id, code, name, min_points,
                                discount_percent, discount_enabled, manual_only,
                                active, sort_order
                            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, UUID.randomUUID(), snapshotId, value.categoryId(),
                            value.code(), value.name(), value.minPoints(),
                            value.discountPercent(), value.discountEnabled(),
                            value.manualOnly(), value.active(), value.sortOrder());
                }
            } else {
                for (AssignmentValue value : normalized.assignments()) {
                    jdbc.update("""
                            insert into saas_member_category_bootstrap_assignment (
                                id, snapshot_id, member_id, category_id,
                                lock_automatic, lock_known, assigned_at,
                                assignment_source, assignment_action
                            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, UUID.randomUUID(), snapshotId, value.memberId(),
                            value.categoryId(), value.lockAutomatic(),
                            value.lockKnown(), timestamp(value.assignedAt()),
                            value.assignmentSource(), value.assignmentAction());
                }
            }
        } catch (DataIntegrityViolationException exception) {
            return conflict(bootstrapId, context.storeId(),
                    "Identificador duplicado entre chunks");
        }
        jdbc.update("""
                insert into saas_member_category_bootstrap_chunk (
                    id, snapshot_id, kind, chunk_index,
                    chunk_hash, record_count, created_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), snapshotId, kind, index,
                normalized.hash(), normalized.size(), timestamp(clock.instant()));
        return status(bootstrapId, context);
    }

    @Transactional
    public Status complete(
            UUID bootstrapId,
            UUID snapshotId,
            CompleteRequest request,
            String token) {
        Context context = authenticate(request == null
                ? null : new StoreRequest(request.companyId(), request.storeId()), token);
        Bootstrap bootstrap = lockBootstrap(bootstrapId, context.companyId());
        if ("COMPLETED".equals(bootstrap.status())) {
            return status(bootstrapId, context);
        }
        requireCollecting(bootstrap);
        Snapshot snapshot = snapshot(bootstrapId, snapshotId, context.storeId());
        if (!snapshot.snapshotChecksum().equalsIgnoreCase(
                hash(request == null ? null : request.snapshotChecksum()))) {
            return conflict(bootstrapId, context.storeId(),
                    "snapshotChecksum distinto al begin");
        }
        String verificationError = verifySnapshot(snapshot);
        if (verificationError != null) {
            return conflict(bootstrapId, context.storeId(), verificationError);
        }
        Instant now = clock.instant();
        jdbc.update("update saas_member_category_bootstrap_snapshot set completed_at=? where snapshot_id=?",
                timestamp(now), snapshotId);
        jdbc.update("update saas_member_category_bootstrap_store set completed_at=? where bootstrap_id=? and store_id=?",
                timestamp(now), bootstrapId, context.storeId());
        int missing = jdbc.queryForObject("""
                select count(*) from saas_member_category_bootstrap_store
                where bootstrap_id=? and completed_at is null
                """, Integer.class, bootstrapId);
        if (missing == 0) {
            reconcile(bootstrapId, context.companyId());
        }
        return status(bootstrapId, context);
    }

    private void reconcile(UUID bootstrapId, UUID companyId) {
        List<StoreSnapshot> storeSnapshots = jdbc.query("""
                select s.store_id, p.snapshot_id, p.category_hash
                from saas_member_category_bootstrap_store s
                join saas_member_category_bootstrap_snapshot p
                  on p.snapshot_id=s.snapshot_id
                where s.bootstrap_id=?
                order by s.store_id::text
                """, (rs, row) -> new StoreSnapshot(
                        rs.getObject("store_id", UUID.class),
                        rs.getObject("snapshot_id", UUID.class),
                        rs.getString("category_hash")), bootstrapId);
        Set<String> categoryHashes = storeSnapshots.stream()
                .map(StoreSnapshot::categoryHash)
                .collect(java.util.stream.Collectors.toSet());
        if (categoryHashes.size() != 1) {
            markConflict(bootstrapId, null,
                    "Las configuraciones de categorias difieren entre tiendas");
            return;
        }
        MergeAssignments merged = mergeAssignments(storeSnapshots);
        if (merged.conflictReason() != null) {
            markConflict(bootstrapId, null, merged.conflictReason());
            return;
        }
        UUID canonicalSnapshot = storeSnapshots.get(0).snapshotId();
        Set<UUID> categoryIds = new HashSet<>(jdbc.query("""
                select category_id from saas_member_category_bootstrap_category
                where snapshot_id=?
                """, (rs, row) -> rs.getObject(1, UUID.class), canonicalSnapshot));
        for (AssignmentValue assignment : merged.values()) {
            if ("SET".equals(assignment.assignmentAction())
                    && !categoryIds.contains(assignment.categoryId())) {
                markConflict(bootstrapId, null,
                        "Una asignacion manual referencia una categoria inexistente");
                return;
            }
        }
        long configRevision = nextRevision();
        long assignmentRevision = nextRevision();
        Instant now = clock.instant();
        jdbc.update("delete from saas_member_category_assignment where company_id=?", companyId);
        jdbc.update("delete from saas_member_category where company_id=?", companyId);
        jdbc.update("""
                insert into saas_member_category (
                    category_id, company_id, code, name, min_points,
                    discount_percent, discount_enabled, manual_only,
                    active, sort_order, config_revision, updated_at
                )
                select category_id, ?, code, name, min_points,
                       discount_percent, discount_enabled, manual_only,
                       active, sort_order, ?, ?
                from saas_member_category_bootstrap_category
                where snapshot_id=?
                """, companyId, configRevision, timestamp(now), canonicalSnapshot);
        for (AssignmentValue assignment : merged.values()) {
            jdbc.update("""
                    insert into saas_member_category_assignment (
                        member_id, company_id, category_id, lock_automatic,
                        lock_known, assigned_at, assignment_source,
                        assignment_action, assignment_revision, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, assignment.memberId(), companyId, assignment.categoryId(),
                    assignment.lockAutomatic(), assignment.lockKnown(),
                    timestamp(assignment.assignedAt()), assignment.assignmentSource(),
                    assignment.assignmentAction(), assignmentRevision, timestamp(now));
        }
        jdbc.update("""
                update saas_member_category_bootstrap
                set status='COMPLETED', config_revision=?, assignment_revision=?,
                    completed_at=?, conflict_reason=null
                where id=?
                """, configRevision, assignmentRevision, timestamp(now), bootstrapId);
    }

    private MergeAssignments mergeAssignments(List<StoreSnapshot> snapshots) {
        Map<UUID, List<AssignmentValue>> grouped = new HashMap<>();
        for (StoreSnapshot snapshot : snapshots) {
            List<AssignmentValue> values = jdbc.query("""
                    select member_id, category_id, lock_automatic, lock_known,
                           assigned_at, assignment_source, assignment_action
                    from saas_member_category_bootstrap_assignment
                    where snapshot_id=?
                    order by member_id::text
                    """, (rs, row) -> new AssignmentValue(
                            rs.getObject("member_id", UUID.class),
                            rs.getObject("category_id", UUID.class),
                            rs.getBoolean("lock_automatic"),
                            rs.getBoolean("lock_known"),
                            rs.getTimestamp("assigned_at").toInstant(),
                            rs.getString("assignment_source"),
                            rs.getString("assignment_action")), snapshot.snapshotId());
            values.forEach(value -> grouped
                    .computeIfAbsent(value.memberId(), ignored -> new ArrayList<>())
                    .add(value));
        }
        var merged = new ArrayList<AssignmentValue>();
        for (var entry : grouped.entrySet()) {
            Instant latest = entry.getValue().stream()
                    .map(AssignmentValue::assignedAt)
                    .max(Comparator.naturalOrder()).orElseThrow();
            List<AssignmentValue> candidates = entry.getValue().stream()
                    .filter(value -> value.assignedAt().equals(latest))
                    .toList();
            AssignmentValue selected = candidates.get(0);
            boolean contradiction = candidates.stream().anyMatch(value ->
                    !java.util.Objects.equals(
                            value.categoryId(), selected.categoryId())
                            || value.lockKnown() != selected.lockKnown()
                            || (value.lockKnown()
                                    && value.lockAutomatic() != selected.lockAutomatic())
                            || !value.assignmentAction().equals(
                                    selected.assignmentAction()));
            if (contradiction) {
                return new MergeAssignments(List.of(),
                        "Empate contradictorio en la asignacion manual del socio "
                                + entry.getKey());
            }
            if (!selected.lockKnown()) {
                return new MergeAssignments(List.of(),
                        "No se puede reconstruir lockAutomatic del socio "
                                + entry.getKey());
            }
            merged.add(selected);
        }
        merged.sort(Comparator.comparing(value -> value.memberId().toString()));
        return new MergeAssignments(List.copyOf(merged), null);
    }

    private String verifySnapshot(Snapshot snapshot) {
        for (String kind : List.of(CATEGORIES, ASSIGNMENTS)) {
            int expectedChunks = CATEGORIES.equals(kind)
                    ? snapshot.categoryChunkCount() : snapshot.assignmentChunkCount();
            int expectedRecords = CATEGORIES.equals(kind)
                    ? snapshot.categoryCount() : snapshot.assignmentCount();
            List<ChunkRow> chunks = jdbc.query("""
                    select chunk_index, chunk_hash, record_count
                    from saas_member_category_bootstrap_chunk
                    where snapshot_id=? and kind=? order by chunk_index
                    """, (rs, row) -> new ChunkRow(
                            rs.getInt("chunk_index"),
                            rs.getString("chunk_hash"),
                            rs.getInt("record_count")), snapshot.snapshotId(), kind);
            if (chunks.size() != expectedChunks) {
                return "Faltan chunks de " + kind;
            }
            for (int index = 0; index < chunks.size(); index++) {
                if (chunks.get(index).index() != index) {
                    return "Indices no contiguos de " + kind;
                }
            }
            if (chunks.stream().mapToInt(ChunkRow::records).sum() != expectedRecords) {
                return "Count incorrecto de " + kind;
            }
        }
        String categoryCanonical = jdbc.query("""
                select category_id, code, name, min_points, discount_percent,
                       discount_enabled, manual_only, active, sort_order
                from saas_member_category_bootstrap_category
                where snapshot_id=? order by code, category_id::text
                """, (rs, row) -> categoryLine(new CategoryValue(
                        rs.getObject("category_id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getLong("min_points"),
                        rs.getBigDecimal("discount_percent"),
                        rs.getBoolean("discount_enabled"), rs.getBoolean("manual_only"),
                        rs.getBoolean("active"), rs.getInt("sort_order"))),
                snapshot.snapshotId()).stream().reduce("", String::concat);
        String assignmentCanonical = jdbc.query("""
                select member_id, category_id, lock_automatic, lock_known,
                       assigned_at, assignment_source, assignment_action
                from saas_member_category_bootstrap_assignment
                where snapshot_id=? order by member_id::text
                """, (rs, row) -> assignmentLine(new AssignmentValue(
                        rs.getObject("member_id", UUID.class),
                        rs.getObject("category_id", UUID.class),
                        rs.getBoolean("lock_automatic"),
                        rs.getBoolean("lock_known"),
                        rs.getTimestamp("assigned_at").toInstant(),
                        rs.getString("assignment_source"),
                        rs.getString("assignment_action"))),
                snapshot.snapshotId()).stream().reduce("", String::concat);
        String categoryHash = sha256(categoryCanonical);
        String assignmentHash = sha256(assignmentCanonical);
        if (!categoryHash.equalsIgnoreCase(snapshot.categoryHash())) {
            return "categoryHash no coincide con el contenido";
        }
        if (!assignmentHash.equalsIgnoreCase(snapshot.assignmentHash())) {
            return "assignmentHash no coincide con el contenido";
        }
        String checksum = sha256("CATEGORIES|" + categoryHash + "\n"
                + "ASSIGNMENTS|" + assignmentHash + "\n");
        return checksum.equalsIgnoreCase(snapshot.snapshotChecksum())
                ? null : "snapshotChecksum no coincide con el contenido";
    }

    private Normalized normalize(String kind, ChunkRequest request) {
        List<CategoryValue> categories = request.categories() == null
                ? List.of() : request.categories();
        List<AssignmentValue> assignments = request.assignments() == null
                ? List.of() : request.assignments();
        if (CATEGORIES.equals(kind)) {
            if (categories.isEmpty() || categories.size() > MAX || !assignments.isEmpty()) {
                throw invalid("Chunk CATEGORIES debe contener entre 1 y 500 categorias");
            }
            var normalized = categories.stream()
                    .map(this::normalizeCategory)
                    .sorted(Comparator.comparing(CategoryValue::code)
                            .thenComparing(value -> value.categoryId().toString()))
                    .toList();
            ensureDistinct(normalized.stream().map(CategoryValue::categoryId).toList());
            String canonical = normalized.stream()
                    .map(MemberCategoryBootstrapService::categoryLine)
                    .reduce("", String::concat);
            return new Normalized(normalized, List.of(), sha256(canonical), normalized.size());
        }
        if (assignments.isEmpty() || assignments.size() > MAX || !categories.isEmpty()) {
            throw invalid("Chunk ASSIGNMENTS debe contener entre 1 y 500 asignaciones");
        }
        var normalized = assignments.stream()
                .map(this::normalizeAssignment)
                .sorted(Comparator.comparing(value -> value.memberId().toString()))
                .toList();
        ensureDistinct(normalized.stream().map(AssignmentValue::memberId).toList());
        String canonical = normalized.stream()
                .map(MemberCategoryBootstrapService::assignmentLine)
                .reduce("", String::concat);
        return new Normalized(List.of(), normalized, sha256(canonical), normalized.size());
    }

    private CategoryValue normalizeCategory(CategoryValue value) {
        if (value == null || value.categoryId() == null) {
            throw invalid("Categoria invalida");
        }
        String code = required(value.code(), "code").toUpperCase(Locale.ROOT);
        String name = required(value.name(), "name");
        BigDecimal discount = value.discountPercent() == null
                ? null : value.discountPercent().setScale(2, RoundingMode.HALF_UP);
        if (code.length() > 32 || name.length() > 64
                || value.minPoints() < 0 || discount == null
                || discount.signum() < 0 || discount.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw invalid("Valores de categoria invalidos");
        }
        return new CategoryValue(
                value.categoryId(), code, name, value.minPoints(), discount,
                value.discountEnabled(), value.manualOnly(), value.active(), value.sortOrder());
    }

    private AssignmentValue normalizeAssignment(AssignmentValue value) {
        if (value == null || value.memberId() == null
                || value.assignedAt() == null
                || !Set.of("MOVEMENT", "LEGACY_CURRENT").contains(value.assignmentSource())) {
            throw invalid("Asignacion manual invalida");
        }
        if (!Set.of("SET", "CLEAR").contains(value.assignmentAction())
                || ("SET".equals(value.assignmentAction()) && value.categoryId() == null)
                || ("CLEAR".equals(value.assignmentAction())
                        && (value.categoryId() != null || value.lockAutomatic()))) {
            throw invalid("Accion de asignacion manual invalida");
        }
        return value;
    }

    private Status status(UUID bootstrapId, Context context) {
        Bootstrap bootstrap = bootstrap(bootstrapId, context.companyId());
        requireExpectedStore(bootstrapId, context.storeId());
        List<StoreStatus> stores = jdbc.query("""
                select store_id, completed_at, conflict_reason
                from saas_member_category_bootstrap_store
                where bootstrap_id=? order by store_id::text
                """, (rs, row) -> new StoreStatus(
                        rs.getObject("store_id", UUID.class),
                        rs.getTimestamp("completed_at") == null
                                ? null : rs.getTimestamp("completed_at").toInstant(),
                        rs.getString("conflict_reason")), bootstrapId);
        List<UUID> expected = stores.stream().map(StoreStatus::storeId).toList();
        List<UUID> completed = stores.stream()
                .filter(value -> value.completedAt() != null)
                .map(StoreStatus::storeId).toList();
        List<UUID> missing = stores.stream()
                .filter(value -> value.completedAt() == null)
                .map(StoreStatus::storeId).toList();
        List<UUID> conflicts = stores.stream()
                .filter(value -> value.conflictReason() != null)
                .map(StoreStatus::storeId).toList();
        return new Status(
                bootstrap.id(), bootstrap.companyId(), bootstrap.status(),
                expected, completed, missing, conflicts,
                bootstrap.conflictReason(), bootstrap.configRevision(),
                bootstrap.assignmentRevision(), bootstrap.createdAt(),
                bootstrap.completedAt());
    }

    private Status conflict(UUID bootstrapId, UUID storeId, String reason) {
        markConflict(bootstrapId, storeId, reason);
        Bootstrap bootstrap = bootstrapAny(bootstrapId);
        return status(bootstrapId, new Context(bootstrap.companyId(), storeId));
    }

    private void markConflict(UUID bootstrapId, UUID storeId, String reason) {
        jdbc.update("update saas_member_category_bootstrap set status='CONFLICT', conflict_reason=? where id=? and status<>'COMPLETED'",
                reason, bootstrapId);
        if (storeId != null) {
            jdbc.update("update saas_member_category_bootstrap_store set conflict_reason=? where bootstrap_id=? and store_id=?",
                    reason, bootstrapId, storeId);
        } else {
            jdbc.update("update saas_member_category_bootstrap_store set conflict_reason=? where bootstrap_id=?",
                    reason, bootstrapId);
        }
    }

    private Context authenticate(StoreRequest request, String token) {
        if (request == null || request.companyId() == null || request.storeId() == null) {
            throw invalid("companyId y storeId son obligatorios");
        }
        var installation = authenticator.requireLinkedInstallation(
                request.companyId(), request.storeId(),
                installations.findByCompany_IdAndStore_Id(
                        request.companyId(), request.storeId()), token);
        return new Context(
                installation.getCompany().getId(), installation.getStore().getId());
    }

    UUID discoverableBootstrap(UUID companyId) {
        List<UUID> values = jdbc.query("""
                select id from saas_member_category_bootstrap
                where company_id=?
                  and status in ('COMPLETED','COLLECTING','CONFLICT')
                order by case when status='COMPLETED' then 0 else 1 end,
                         created_at desc
                limit 1
                """, (rs, row) -> rs.getObject(1, UUID.class), companyId);
        return values.isEmpty() ? null : values.get(0);
    }

    private Bootstrap lockBootstrap(UUID id, UUID companyId) {
        List<Bootstrap> values = jdbc.query("""
                select * from saas_member_category_bootstrap
                where id=? and company_id=? for update
                """, this::bootstrapRow, id, companyId);
        if (values.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bootstrap no encontrado");
        }
        return values.get(0);
    }

    private Bootstrap bootstrap(UUID id, UUID companyId) {
        List<Bootstrap> values = jdbc.query(
                "select * from saas_member_category_bootstrap where id=? and company_id=?",
                this::bootstrapRow, id, companyId);
        if (values.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bootstrap no encontrado");
        }
        return values.get(0);
    }

    private Bootstrap bootstrapAny(UUID id) {
        List<Bootstrap> values = jdbc.query(
                "select * from saas_member_category_bootstrap where id=?",
                this::bootstrapRow, id);
        if (values.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bootstrap no encontrado");
        }
        return values.get(0);
    }

    private Bootstrap bootstrapRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        var completed = rs.getTimestamp("completed_at");
        Number config = (Number) rs.getObject("config_revision");
        Number assignment = (Number) rs.getObject("assignment_revision");
        return new Bootstrap(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("status"), rs.getString("conflict_reason"),
                config == null ? null : config.longValue(),
                assignment == null ? null : assignment.longValue(),
                rs.getTimestamp("created_at").toInstant(),
                completed == null ? null : completed.toInstant());
    }

    private Snapshot snapshot(UUID bootstrapId, UUID snapshotId, UUID storeId) {
        List<Snapshot> values = jdbc.query("""
                select * from saas_member_category_bootstrap_snapshot
                where snapshot_id=? and bootstrap_id=? and store_id=?
                """, (rs, row) -> new Snapshot(
                        rs.getObject("snapshot_id", UUID.class),
                        rs.getInt("category_chunk_count"),
                        rs.getInt("assignment_chunk_count"),
                        rs.getInt("category_count"),
                        rs.getInt("assignment_count"),
                        rs.getString("category_hash"),
                        rs.getString("assignment_hash"),
                        rs.getString("snapshot_checksum")),
                snapshotId, bootstrapId, storeId);
        if (values.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Snapshot no encontrado");
        }
        return values.get(0);
    }

    private void requireExpectedStore(UUID bootstrapId, UUID storeId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from saas_member_category_bootstrap_store
                where bootstrap_id=? and store_id=?
                """, Integer.class, bootstrapId, storeId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Tienda fuera de expectedStoreIds");
        }
    }

    private static void requireCollecting(Bootstrap bootstrap) {
        if (!"COLLECTING".equals(bootstrap.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El bootstrap no admite mas snapshots: " + bootstrap.status());
        }
    }

    private static void validateBegin(BeginRequest request) {
        if (request == null || request.snapshotId() == null
                || request.categoryChunkCount() < 0
                || request.assignmentChunkCount() < 0
                || request.categoryCount() < 0
                || request.assignmentCount() < 0
                || (request.categoryCount() == 0) != (request.categoryChunkCount() == 0)
                || (request.assignmentCount() == 0) != (request.assignmentChunkCount() == 0)) {
            throw invalid("Begin de categorias incompleto");
        }
        hash(request.categoryHash());
        hash(request.assignmentHash());
        hash(request.snapshotChecksum());
    }

    private static String kind(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!Set.of(CATEGORIES, ASSIGNMENTS).contains(value)) {
            throw invalid("kind no soportado");
        }
        return value;
    }

    private static String categoryLine(CategoryValue value) {
        return "C|" + value.categoryId()
                + "|" + text(value.code())
                + "|" + text(value.name())
                + "|" + value.minPoints()
                + "|" + decimal(value.discountPercent())
                + "|" + value.discountEnabled()
                + "|" + value.manualOnly()
                + "|" + value.active()
                + "|" + value.sortOrder() + "\n";
    }

    private static String assignmentLine(AssignmentValue value) {
        return "M|" + value.memberId()
                + "|" + value.assignmentAction()
                + "|" + (value.categoryId() == null ? "-" : value.categoryId())
                + "|" + (value.lockKnown()
                        ? Boolean.toString(value.lockAutomatic()) : "?")
                + "|" + value.assignedAt()
                + "|" + value.assignmentSource() + "\n";
    }

    private static String text(String value) {
        return value.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String decimal(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " es obligatorio");
        }
        return value.trim();
    }

    private static String hash(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw invalid("Hash SHA-256 invalido");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static void ensureDistinct(List<UUID> values) {
        if (new LinkedHashSet<>(values).size() != values.size()) {
            throw invalid("El chunk contiene identificadores duplicados");
        }
    }

    private long nextRevision() {
        return jdbc.queryForObject(
                "select nextval('saas_member_category_revision_seq')",
                Long.class);
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record Context(UUID companyId, UUID storeId) {
    }

    private record Bootstrap(
            UUID id,
            UUID companyId,
            String status,
            String conflictReason,
            Long configRevision,
            Long assignmentRevision,
            Instant createdAt,
            Instant completedAt) {
    }

    private record Snapshot(
            UUID snapshotId,
            int categoryChunkCount,
            int assignmentChunkCount,
            int categoryCount,
            int assignmentCount,
            String categoryHash,
            String assignmentHash,
            String snapshotChecksum) {
    }

    private record Normalized(
            List<CategoryValue> categories,
            List<AssignmentValue> assignments,
            String hash,
            int size) {
    }

    private record StoreSnapshot(UUID storeId, UUID snapshotId, String categoryHash) {
    }

    private record StoreStatus(
            UUID storeId, Instant completedAt, String conflictReason) {
    }

    private record ChunkRow(int index, String hash, int records) {
    }

    private record MergeAssignments(
            List<AssignmentValue> values, String conflictReason) {
    }
}
