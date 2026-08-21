package com.tpverp.backend.party.loyalty.category;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberCategoryOfficialSnapshotApplicationService {
    private final JdbcTemplate jdbc;
    private final MemberCategoryProjectionCoordinator projection;
    private final Clock clock;

    public MemberCategoryOfficialSnapshotApplicationService(
            JdbcTemplate jdbc,
            MemberCategoryProjectionCoordinator projection,
            Clock clock) {
        this.jdbc = jdbc;
        this.projection = projection;
        this.clock = clock;
    }

    @Transactional
    public void apply(
            UUID localCompanyId,
            UUID localStoreId,
            UUID bootstrapId,
            UUID sourceSnapshotId,
            MemberCategoryBootstrapGateway.OfficialSnapshot official) {
        validate(official);
        if (alreadyApplied(
                bootstrapId,
                localStoreId,
                official.configRevision(),
                official.assignmentRevision())) {
            projection.activateCentral(
                    localCompanyId,
                    localStoreId,
                    bootstrapId,
                    sourceSnapshotId,
                    official.configRevision(),
                    official.assignmentRevision());
            return;
        }

        UUID receiptId = UUID.randomUUID();
        var now = clock.instant();
        jdbc.update("""
                insert into member_category_official_snapshot (
                    id, bootstrap_id, source_snapshot_id, empresa_id, tienda_id,
                    official_company_id, config_revision, assignment_revision,
                    category_count, assignment_count, category_hash,
                    assignment_hash, snapshot_checksum, status, received_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVED', ?)
                """,
                receiptId,
                bootstrapId,
                sourceSnapshotId,
                localCompanyId,
                localStoreId,
                official.companyId(),
                official.configRevision(),
                official.assignmentRevision(),
                official.categories().size(),
                official.assignments().size(),
                official.categoryHash(),
                official.assignmentHash(),
                official.snapshotChecksum(),
                now);

        upsertCategories(localCompanyId, official.categories());
        applyAssignments(localCompanyId, official.assignments());
        projection.activateCentral(
                localCompanyId,
                localStoreId,
                bootstrapId,
                sourceSnapshotId,
                official.configRevision(),
                official.assignmentRevision());
        jdbc.update("""
                update member_category_official_snapshot
                set status = 'APPLIED', applied_at = ?
                where id = ?
                """, clock.instant(), receiptId);
    }

    @Transactional
    public void applyFeed(
            UUID localCompanyId,
            UUID localStoreId,
            MemberCategoryBootstrapGateway.OfficialFeed feed) {
        validateFeed(feed);
        var categories = feed.categories().stream()
                .map(MemberCategoryBootstrapGateway.CategoryChange::value)
                .toList();
        var assignments = feed.assignments().stream()
                .map(MemberCategoryBootstrapGateway.AssignmentChange::value)
                .toList();
        upsertCategories(localCompanyId, categories);
        if (!categories.isEmpty()) {
            recalculateAutomaticMembers(localCompanyId);
        }
        applyAssignments(localCompanyId, assignments);
        projection.advanceOfficialFeed(
                localCompanyId,
                localStoreId,
                feed.fromConfigRevision(),
                feed.fromConfigId(),
                feed.nextConfigRevision(),
                feed.nextConfigId(),
                feed.fromAssignmentRevision(),
                feed.fromAssignmentId(),
                feed.nextAssignmentRevision(),
                feed.nextAssignmentId());
        jdbc.update("""
                insert into member_category_official_feed_page (
                    id, empresa_id, tienda_id, official_company_id,
                    from_config_revision, from_config_id,
                    to_config_revision, to_config_id,
                    from_assignment_revision, from_assignment_id,
                    to_assignment_revision, to_assignment_id,
                    category_count, assignment_count, page_checksum, applied_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), localCompanyId, localStoreId, feed.companyId(),
                feed.fromConfigRevision(), feed.fromConfigId(),
                feed.nextConfigRevision(), feed.nextConfigId(),
                feed.fromAssignmentRevision(), feed.fromAssignmentId(),
                feed.nextAssignmentRevision(), feed.nextAssignmentId(),
                categories.size(), assignments.size(), feed.pageChecksum(), clock.instant());
    }

    private void recalculateAutomaticMembers(UUID companyId) {
        jdbc.update("""
                update miembro member
                set member_category_id = (
                        select category.id
                        from member_category category
                        where category.empresa_id = member.empresa_id
                          and category.active = true
                          and category.manual_only = false
                          and category.min_points <= member.member_points
                        order by category.min_points desc, category.sort_order asc,
                                 category.id
                        limit 1
                    ),
                    version = version + 1
                where member.empresa_id = ? and member.auto_category_locked = false
                """, companyId);
    }

    private void upsertCategories(
            UUID companyId,
            List<MemberCategoryBootstrapGateway.CategoryValue> values) {
        jdbc.batchUpdate("""
                insert into member_category (
                    id, empresa_id, code, name, min_points, discount_percent,
                    discount_enabled, manual_only, active, sort_order, version
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                on conflict (id) do update set
                    name = excluded.name,
                    min_points = excluded.min_points,
                    discount_percent = excluded.discount_percent,
                    discount_enabled = excluded.discount_enabled,
                    manual_only = excluded.manual_only,
                    active = excluded.active,
                    sort_order = excluded.sort_order,
                    version = member_category.version + 1
                where member_category.empresa_id = excluded.empresa_id
                  and member_category.code = excluded.code
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                var value = values.get(index);
                statement.setObject(1, value.categoryId());
                statement.setObject(2, companyId);
                statement.setString(3, value.code());
                statement.setString(4, value.name());
                statement.setLong(5, value.minPoints());
                statement.setBigDecimal(6, value.discountPercent());
                statement.setBoolean(7, value.discountEnabled());
                statement.setBoolean(8, value.manualOnly());
                statement.setBoolean(9, value.active());
                statement.setInt(10, value.sortOrder());
            }

            @Override
            public int getBatchSize() {
                return values.size();
            }
        });
    }

    private void applyAssignments(
            UUID companyId,
            List<MemberCategoryBootstrapGateway.AssignmentValue> values) {
        for (var value : values) {
            if ("SET".equals(value.assignmentAction())) {
                jdbc.update("""
                        update miembro
                        set member_category_id = ?, auto_category_locked = ?,
                            version = version + 1
                        where id = ? and empresa_id = ?
                        """,
                        value.categoryId(),
                        value.lockAutomatic(),
                        value.memberId(),
                        companyId);
                continue;
            }
            jdbc.update("""
                    update miembro member
                    set member_category_id = (
                            select category.id
                            from member_category category
                            where category.empresa_id = member.empresa_id
                              and category.active = true
                              and category.manual_only = false
                              and category.min_points <= member.member_points
                            order by category.min_points desc, category.sort_order asc,
                                     category.id
                            limit 1
                        ),
                        auto_category_locked = false,
                        version = version + 1
                    where member.id = ? and member.empresa_id = ?
                    """, value.memberId(), companyId);
        }
    }

    private static void validate(MemberCategoryBootstrapGateway.OfficialSnapshot official) {
        if (official == null || official.companyId() == null
                || official.configRevision() <= 0 || official.assignmentRevision() <= 0
                || official.categories() == null || official.assignments() == null) {
            throw new IllegalArgumentException("Snapshot oficial de categorias incompleto");
        }
        Set<UUID> categoryIds = new HashSet<>();
        for (var category : official.categories()) {
            if (category.categoryId() == null || !categoryIds.add(category.categoryId())) {
                throw new IllegalArgumentException("Categoria oficial duplicada o sin id");
            }
        }
        Set<UUID> memberIds = new HashSet<>();
        for (var assignment : official.assignments()) {
            boolean set = "SET".equals(assignment.assignmentAction());
            boolean clear = "CLEAR".equals(assignment.assignmentAction());
            if (assignment.memberId() == null || !memberIds.add(assignment.memberId())
                    || !assignment.lockKnown()
                    || (!set && !clear)
                    || (set && (assignment.categoryId() == null
                            || !categoryIds.contains(assignment.categoryId())))
                    || (clear && (assignment.categoryId() != null
                            || assignment.lockAutomatic()))) {
                throw new IllegalArgumentException("Asignacion oficial de categoria invalida");
            }
        }
        String categoryHash = hashCategories(official.categories());
        String assignmentHash = hashAssignments(official.assignments());
        String checksum = sha256("CATEGORIES|" + categoryHash + "\n"
                + "ASSIGNMENTS|" + assignmentHash + "\n");
        if (!categoryHash.equals(official.categoryHash())
                || !assignmentHash.equals(official.assignmentHash())
                || !checksum.equals(official.snapshotChecksum())) {
            throw new IllegalArgumentException("Hash del snapshot oficial de categorias invalido");
        }
    }

    private static void validateFeed(MemberCategoryBootstrapGateway.OfficialFeed feed) {
        if (feed == null || feed.companyId() == null
                || feed.categories() == null || feed.assignments() == null) {
            throw new IllegalArgumentException("Pagina oficial de categorias incompleta");
        }
        String categoryHash = hashCategoryChanges(feed.categories());
        String assignmentHash = hashAssignmentChanges(feed.assignments());
        String checksum = sha256("CONFIG_FEED|" + categoryHash + "\n"
                + "ASSIGNMENT_FEED|" + assignmentHash + "\n");
        if (!categoryHash.equals(feed.categoryHash())
                || !assignmentHash.equals(feed.assignmentHash())
                || !checksum.equals(feed.pageChecksum())) {
            throw new IllegalArgumentException("Hash de pagina oficial de categorias invalido");
        }
        long configRevision = feed.fromConfigRevision();
        UUID configId = feed.fromConfigId();
        for (var change : feed.categories()) {
            requireAfter(configRevision, configId, change.revision(), change.value().categoryId());
            configRevision = change.revision();
            configId = change.value().categoryId();
        }
        long assignmentRevision = feed.fromAssignmentRevision();
        UUID assignmentId = feed.fromAssignmentId();
        for (var change : feed.assignments()) {
            requireAfter(
                    assignmentRevision,
                    assignmentId,
                    change.revision(),
                    change.value().memberId());
            assignmentRevision = change.revision();
            assignmentId = change.value().memberId();
            var value = change.value();
            if (!value.lockKnown()
                    || (!("SET".equals(value.assignmentAction()))
                            && !("CLEAR".equals(value.assignmentAction())))
                    || ("SET".equals(value.assignmentAction()) && value.categoryId() == null)
                    || ("CLEAR".equals(value.assignmentAction())
                            && (value.categoryId() != null || value.lockAutomatic()))) {
                throw new IllegalArgumentException("Cambio oficial de asignacion invalido");
            }
        }
        if (configRevision != feed.nextConfigRevision()
                || !java.util.Objects.equals(configId, feed.nextConfigId())
                || assignmentRevision != feed.nextAssignmentRevision()
                || !java.util.Objects.equals(assignmentId, feed.nextAssignmentId())) {
            throw new IllegalArgumentException("Cursor final de la pagina oficial invalido");
        }
    }

    private static void requireAfter(
            long previousRevision,
            UUID previousId,
            long revision,
            UUID id) {
        if (id == null || revision < previousRevision
                || (revision == previousRevision
                        && (previousId == null || id.compareTo(previousId) <= 0))) {
            throw new IllegalArgumentException("Orden del feed oficial invalido");
        }
    }

    private static String hashCategoryChanges(
            List<MemberCategoryBootstrapGateway.CategoryChange> values) {
        var canonical = new StringBuilder();
        values.forEach(change -> canonical.append("R|")
                .append(change.revision()).append("|C|")
                .append(change.value().categoryId()).append('|')
                .append(escaped(change.value().code())).append('|')
                .append(escaped(change.value().name())).append('|')
                .append(change.value().minPoints()).append('|')
                .append(decimal(change.value().discountPercent())).append('|')
                .append(change.value().discountEnabled()).append('|')
                .append(change.value().manualOnly()).append('|')
                .append(change.value().active()).append('|')
                .append(change.value().sortOrder()).append('\n'));
        return sha256(canonical.toString());
    }

    private static String hashAssignmentChanges(
            List<MemberCategoryBootstrapGateway.AssignmentChange> values) {
        var canonical = new StringBuilder();
        values.forEach(change -> canonical.append("R|")
                .append(change.revision()).append("|M|")
                .append(change.value().memberId()).append('|')
                .append(change.value().assignmentAction()).append('|')
                .append(change.value().categoryId() == null
                        ? "-" : change.value().categoryId()).append('|')
                .append(change.value().lockKnown()
                        ? Boolean.toString(change.value().lockAutomatic()) : "?").append('|')
                .append(change.value().assignedAt()).append('|')
                .append(change.value().assignmentSource()).append('\n'));
        return sha256(canonical.toString());
    }

    private boolean alreadyApplied(
            UUID bootstrapId,
            UUID storeId,
            long configRevision,
            long assignmentRevision) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                from member_category_official_snapshot
                where bootstrap_id = ? and tienda_id = ?
                  and config_revision = ? and assignment_revision = ?
                  and status = 'APPLIED'
                """, Integer.class,
                bootstrapId, storeId, configRevision, assignmentRevision);
        return count != null && count > 0;
    }

    private static String hashCategories(
            List<MemberCategoryBootstrapGateway.CategoryValue> values) {
        var canonical = new StringBuilder();
        values.forEach(value -> canonical.append("C|")
                .append(value.categoryId()).append('|')
                .append(escaped(value.code())).append('|')
                .append(escaped(value.name())).append('|')
                .append(value.minPoints()).append('|')
                .append(decimal(value.discountPercent())).append('|')
                .append(value.discountEnabled()).append('|')
                .append(value.manualOnly()).append('|')
                .append(value.active()).append('|')
                .append(value.sortOrder()).append('\n'));
        return sha256(canonical.toString());
    }

    private static String hashAssignments(
            List<MemberCategoryBootstrapGateway.AssignmentValue> values) {
        var canonical = new StringBuilder();
        values.forEach(value -> canonical.append("M|")
                .append(value.memberId()).append('|')
                .append(value.assignmentAction()).append('|')
                .append(value.categoryId() == null ? "-" : value.categoryId()).append('|')
                .append(value.lockKnown()
                        ? Boolean.toString(value.lockAutomatic()) : "?").append('|')
                .append(value.assignedAt()).append('|')
                .append(value.assignmentSource()).append('\n'));
        return sha256(canonical.toString());
    }

    private static String escaped(String value) {
        return value.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String decimal(BigDecimal value) {
        var normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }
}
