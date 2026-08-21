package com.tpverp.saas.loyalty;

import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.loyalty.MemberCategoryOfficialApiModels.AssignmentValue;
import com.tpverp.saas.loyalty.MemberCategoryOfficialApiModels.CategoryValue;
import com.tpverp.saas.loyalty.MemberCategoryOfficialApiModels.SnapshotResponse;
import com.tpverp.saas.loyalty.MemberCategoryOfficialApiModels.StoreRequest;
import com.tpverp.saas.loyalty.MemberCategoryOfficialApiModels.AssignmentChange;
import com.tpverp.saas.loyalty.MemberCategoryOfficialApiModels.CategoryChange;
import com.tpverp.saas.loyalty.MemberCategoryOfficialApiModels.FeedRequest;
import com.tpverp.saas.loyalty.MemberCategoryOfficialApiModels.FeedResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberCategoryOfficialService {
    private final SaasInstallationRepository installations;
    private final InstallationAuthenticator authenticator;
    private final JdbcTemplate jdbc;

    public MemberCategoryOfficialService(
            SaasInstallationRepository installations,
            InstallationAuthenticator authenticator,
            JdbcTemplate jdbc) {
        this.installations = installations;
        this.authenticator = authenticator;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public SnapshotResponse snapshot(StoreRequest request, String token) {
        Context context = authenticate(request, token);
        Revision revision = currentRevision(context.companyId());
        List<CategoryValue> categories = categories(context.companyId());
        List<AssignmentValue> assignments = assignments(context.companyId());
        String categoryHash = hashCategories(categories);
        String assignmentHash = hashAssignments(assignments);
        return new SnapshotResponse(
                context.companyId(),
                revision.configRevision(),
                revision.assignmentRevision(),
                categories,
                assignments,
                categoryHash,
                assignmentHash,
                sha256("CATEGORIES|" + categoryHash + "\n"
                        + "ASSIGNMENTS|" + assignmentHash + "\n"));
    }

    @Transactional(readOnly = true)
    public FeedResponse feed(FeedRequest request, String token) {
        if (request == null || request.afterConfigRevision() < 0
                || request.afterAssignmentRevision() < 0
                || request.limit() < 1 || request.limit() > 500) {
            throw invalid("Cursor o limite del feed de categorias invalido");
        }
        Context context = authenticate(
                new StoreRequest(request.companyId(), request.storeId()), token);
        List<CategoryChange> categoryChanges = categoryChanges(context.companyId(), request);
        List<AssignmentChange> assignmentChanges = assignmentChanges(context.companyId(), request);
        Cursor configCursor = categoryChanges.isEmpty()
                ? new Cursor(request.afterConfigRevision(), request.afterConfigId())
                : new Cursor(
                        categoryChanges.get(categoryChanges.size() - 1).revision(),
                        categoryChanges.get(categoryChanges.size() - 1).value().categoryId());
        Cursor assignmentCursor = assignmentChanges.isEmpty()
                ? new Cursor(request.afterAssignmentRevision(), request.afterAssignmentId())
                : new Cursor(
                        assignmentChanges.get(assignmentChanges.size() - 1).revision(),
                        assignmentChanges.get(assignmentChanges.size() - 1).value().memberId());
        String categoryHash = hashCategoryChanges(categoryChanges);
        String assignmentHash = hashAssignmentChanges(assignmentChanges);
        return new FeedResponse(
                context.companyId(),
                request.afterConfigRevision(),
                request.afterConfigId(),
                configCursor.revision(),
                configCursor.id(),
                request.afterAssignmentRevision(),
                request.afterAssignmentId(),
                assignmentCursor.revision(),
                assignmentCursor.id(),
                categoryChanges,
                assignmentChanges,
                categoryHash,
                assignmentHash,
                sha256("CONFIG_FEED|" + categoryHash + "\n"
                        + "ASSIGNMENT_FEED|" + assignmentHash + "\n"));
    }

    private Context authenticate(StoreRequest request, String token) {
        if (request == null || request.companyId() == null || request.storeId() == null) {
            throw invalid("companyId y storeId son obligatorios");
        }
        var installation = authenticator.requireLinkedInstallation(
                request.companyId(),
                request.storeId(),
                installations.findByCompany_IdAndStore_Id(
                        request.companyId(), request.storeId()),
                token);
        return new Context(
                installation.getCompany().getId(),
                installation.getStore().getId());
    }

    private Revision currentRevision(UUID companyId) {
        List<Revision> values = jdbc.query("""
                select greatest(
                           coalesce((select max(config_revision)
                                     from saas_member_category
                                     where company_id = ?), 0),
                           coalesce((select max(config_revision)
                                     from saas_member_category_bootstrap
                                     where company_id = ? and status = 'COMPLETED'), 0)
                       ) as config_revision,
                       greatest(
                           coalesce((select max(assignment_revision)
                                     from saas_member_category_assignment
                                     where company_id = ?), 0),
                           coalesce((select max(assignment_revision)
                                     from saas_member_category_bootstrap
                                     where company_id = ? and status = 'COMPLETED'), 0)
                       ) as assignment_revision
                """, (rs, row) -> new Revision(
                        rs.getLong("config_revision"),
                        rs.getLong("assignment_revision")),
                companyId, companyId, companyId, companyId);
        if (values.isEmpty() || values.get(0).configRevision() <= 0
                || values.get(0).assignmentRevision() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La autoridad central de categorias aun no esta inicializada");
        }
        return values.get(0);
    }

    private List<CategoryValue> categories(UUID companyId) {
        return jdbc.query("""
                select category_id, code, name, min_points, discount_percent,
                       discount_enabled, manual_only, active, sort_order
                from saas_member_category
                where company_id = ?
                order by code, category_id
                """, (rs, row) -> new CategoryValue(
                        rs.getObject("category_id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getLong("min_points"),
                        rs.getObject("discount_percent", BigDecimal.class),
                        rs.getBoolean("discount_enabled"),
                        rs.getBoolean("manual_only"),
                        rs.getBoolean("active"),
                        rs.getInt("sort_order")), companyId);
    }

    private List<AssignmentValue> assignments(UUID companyId) {
        return jdbc.query("""
                select member_id, category_id, lock_automatic, lock_known,
                       assigned_at, assignment_source, assignment_action
                from saas_member_category_assignment
                where company_id = ?
                order by member_id
                """, (rs, row) -> new AssignmentValue(
                        rs.getObject("member_id", UUID.class),
                        rs.getObject("category_id", UUID.class),
                        rs.getBoolean("lock_automatic"),
                        rs.getBoolean("lock_known"),
                        rs.getObject("assigned_at", Instant.class),
                        rs.getString("assignment_source"),
                        rs.getString("assignment_action")), companyId);
    }

    private List<CategoryChange> categoryChanges(UUID companyId, FeedRequest request) {
        return jdbc.query("""
                select category_id, code, name, min_points, discount_percent,
                       discount_enabled, manual_only, active, sort_order,
                       config_revision
                from saas_member_category
                where company_id = ?
                  and (config_revision > ?
                       or (config_revision = ? and cast(? as uuid) is not null
                           and category_id > ?))
                order by config_revision, category_id
                limit ?
                """, (rs, row) -> new CategoryChange(
                        rs.getLong("config_revision"),
                        new CategoryValue(
                                rs.getObject("category_id", UUID.class),
                                rs.getString("code"),
                                rs.getString("name"),
                                rs.getLong("min_points"),
                                rs.getObject("discount_percent", BigDecimal.class),
                                rs.getBoolean("discount_enabled"),
                                rs.getBoolean("manual_only"),
                                rs.getBoolean("active"),
                                rs.getInt("sort_order"))),
                companyId,
                request.afterConfigRevision(),
                request.afterConfigRevision(),
                request.afterConfigId(),
                request.afterConfigId(),
                request.limit());
    }

    private List<AssignmentChange> assignmentChanges(UUID companyId, FeedRequest request) {
        return jdbc.query("""
                select member_id, category_id, lock_automatic, lock_known,
                       assigned_at, assignment_source, assignment_action,
                       assignment_revision
                from saas_member_category_assignment
                where company_id = ?
                  and (assignment_revision > ?
                       or (assignment_revision = ? and cast(? as uuid) is not null
                           and member_id > ?))
                order by assignment_revision, member_id
                limit ?
                """, (rs, row) -> new AssignmentChange(
                        rs.getLong("assignment_revision"),
                        new AssignmentValue(
                                rs.getObject("member_id", UUID.class),
                                rs.getObject("category_id", UUID.class),
                                rs.getBoolean("lock_automatic"),
                                rs.getBoolean("lock_known"),
                                rs.getObject("assigned_at", Instant.class),
                                rs.getString("assignment_source"),
                                rs.getString("assignment_action"))),
                companyId,
                request.afterAssignmentRevision(),
                request.afterAssignmentRevision(),
                request.afterAssignmentId(),
                request.afterAssignmentId(),
                request.limit());
    }

    private static String hashCategoryChanges(List<CategoryChange> values) {
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

    private static String hashAssignmentChanges(List<AssignmentChange> values) {
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

    private static String hashCategories(List<CategoryValue> values) {
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

    private static String hashAssignments(List<AssignmentValue> values) {
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

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record Context(UUID companyId, UUID storeId) {
    }

    private record Revision(long configRevision, long assignmentRevision) {
    }

    private record Cursor(long revision, UUID id) {
    }
}
