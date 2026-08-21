package com.tpverp.saas.loyalty;

import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.loyalty.MemberCategoryAdminApiModels.AssignmentCommand;
import com.tpverp.saas.loyalty.MemberCategoryAdminApiModels.CategoryCommand;
import com.tpverp.saas.loyalty.MemberCategoryAdminApiModels.CommandResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberCategoryAdminService {
    private final SaasInstallationRepository installations;
    private final InstallationAuthenticator authenticator;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MemberCategoryAdminService(
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
    public CommandResult category(CategoryCommand command, String token) {
        validateCategory(command);
        Context context = authenticate(command.companyId(), command.storeId(), token);
        requireAdmin(command.actorRole());
        lockCommand(command.commandId());
        CommandResult previous = previous(command.commandId());
        if (previous != null) {
            return previous;
        }
        requireAuthority(context.companyId());
        assertCategoryIdentity(context.companyId(), command);

        long configRevision = nextRevision();
        Long assignmentRevision = null;
        CategoryBefore before = categoryBefore(context.companyId(), command.categoryId());
        if (before != null && before.active() && !command.active()) {
            assignmentRevision = reassignDeactivatedCategory(
                    context.companyId(), command.categoryId(), before.minPoints());
        }
        int changed = jdbc.update("""
                insert into saas_member_category (
                    category_id, company_id, code, name, min_points,
                    discount_percent, discount_enabled, manual_only, active,
                    sort_order, config_revision, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (category_id) do update set
                    name = excluded.name,
                    min_points = excluded.min_points,
                    discount_percent = excluded.discount_percent,
                    discount_enabled = excluded.discount_enabled,
                    manual_only = excluded.manual_only,
                    active = excluded.active,
                    sort_order = excluded.sort_order,
                    config_revision = excluded.config_revision,
                    updated_at = excluded.updated_at
                where saas_member_category.company_id = excluded.company_id
                  and saas_member_category.code = excluded.code
                """,
                command.categoryId(),
                context.companyId(),
                normalizeCode(command.code()),
                command.name().trim(),
                command.minPoints(),
                command.discountPercent(),
                command.discountEnabled(),
                command.manualOnly(),
                command.active(),
                command.sortOrder(),
                configRevision,
                clock.instant());
        if (changed != 1) {
            throw conflict("La identidad de la categoria no coincide con la oficial");
        }
        return audit(
                command.commandId(), context, command.actorUserId(), command.actorName(),
                "UPSERT_CATEGORY", command.categoryId(), command.reason(),
                configRevision, assignmentRevision);
    }

    @Transactional
    public CommandResult assignment(AssignmentCommand command, String token) {
        validateAssignment(command);
        Context context = authenticate(command.companyId(), command.storeId(), token);
        requireAdmin(command.actorRole());
        lockCommand(command.commandId());
        CommandResult previous = previous(command.commandId());
        if (previous != null) {
            return previous;
        }
        requireAuthority(context.companyId());
        String action = command.action().trim().toUpperCase(Locale.ROOT);
        if ("SET".equals(action)) {
            requireActiveCategory(context.companyId(), command.categoryId());
        }
        long revision = nextRevision();
        int changed = jdbc.update("""
                insert into saas_member_category_assignment (
                    member_id, company_id, category_id, lock_automatic,
                    assigned_at, assignment_source, assignment_revision,
                    updated_at, assignment_action, lock_known
                ) values (?, ?, ?, ?, ?, 'ADMIN', ?, ?, ?, true)
                on conflict (member_id) do update set
                    company_id = excluded.company_id,
                    category_id = excluded.category_id,
                    lock_automatic = excluded.lock_automatic,
                    assigned_at = excluded.assigned_at,
                    assignment_source = excluded.assignment_source,
                    assignment_revision = excluded.assignment_revision,
                    updated_at = excluded.updated_at,
                    assignment_action = excluded.assignment_action,
                    lock_known = true
                where saas_member_category_assignment.company_id = excluded.company_id
                """,
                command.memberId(),
                context.companyId(),
                "SET".equals(action) ? command.categoryId() : null,
                "SET".equals(action) && command.lockAutomatic(),
                clock.instant(),
                revision,
                clock.instant(),
                action);
        if (changed != 1) {
            throw conflict("El socio ya pertenece a otra empresa en la autoridad central");
        }
        return audit(
                command.commandId(), context, command.actorUserId(), command.actorName(),
                action + "_ASSIGNMENT", command.memberId(), command.reason(),
                null, revision);
    }

    private Long reassignDeactivatedCategory(
            UUID companyId,
            UUID categoryId,
            long minPoints) {
        Integer assigned = jdbc.queryForObject("""
                select count(*) from saas_member_category_assignment
                where company_id = ? and category_id = ? and assignment_action = 'SET'
                """, Integer.class, companyId, categoryId);
        if (assigned == null || assigned == 0) {
            return null;
        }
        List<UUID> fallback = jdbc.query("""
                select category_id from saas_member_category
                where company_id = ? and category_id <> ? and active = true
                  and manual_only = false
                  and min_points < ?
                order by min_points desc, sort_order asc, category_id
                limit 1
                """, (rs, row) -> rs.getObject(1, UUID.class),
                companyId, categoryId, minPoints);
        if (fallback.isEmpty()) {
            throw conflict("No existe una categoria inferior activa para los socios afectados");
        }
        long revision = nextRevision();
        jdbc.update("""
                update saas_member_category_assignment
                set category_id = ?, lock_automatic = false,
                    assigned_at = ?, assignment_source = 'ADMIN',
                    assignment_revision = ?, updated_at = ?,
                    assignment_action = 'SET', lock_known = true
                where company_id = ? and category_id = ?
                  and assignment_action = 'SET'
                """,
                fallback.get(0), clock.instant(), revision, clock.instant(),
                companyId, categoryId);
        return revision;
    }

    private void assertCategoryIdentity(UUID companyId, CategoryCommand command) {
        List<CategoryIdentity> byId = jdbc.query("""
                select company_id, code from saas_member_category where category_id = ?
                """, (rs, row) -> new CategoryIdentity(
                        rs.getObject("company_id", UUID.class), rs.getString("code")),
                command.categoryId());
        if (!byId.isEmpty() && (!companyId.equals(byId.get(0).companyId())
                || !normalizeCode(command.code()).equals(byId.get(0).code()))) {
            throw conflict("El id o codigo de categoria ya pertenece a otra identidad");
        }
    }

    private CategoryBefore categoryBefore(UUID companyId, UUID categoryId) {
        List<CategoryBefore> values = jdbc.query("""
                select min_points, active from saas_member_category
                where company_id = ? and category_id = ?
                """, (rs, row) -> new CategoryBefore(
                        rs.getLong("min_points"), rs.getBoolean("active")),
                companyId, categoryId);
        return values.isEmpty() ? null : values.get(0);
    }

    private void requireActiveCategory(UUID companyId, UUID categoryId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from saas_member_category
                where company_id = ? and category_id = ? and active = true
                """, Integer.class, companyId, categoryId);
        if (count == null || count == 0) {
            throw invalid("La categoria asignada no existe o esta inactiva");
        }
    }

    private void requireAuthority(UUID companyId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from saas_member_category_bootstrap
                where company_id = ? and status = 'COMPLETED'
                """, Integer.class, companyId);
        if (count == null || count == 0) {
            throw conflict("La autoridad central de categorias no esta activa");
        }
    }

    private Context authenticate(UUID companyId, UUID storeId, String token) {
        if (companyId == null || storeId == null) {
            throw invalid("companyId y storeId son obligatorios");
        }
        var installation = authenticator.requireLinkedInstallation(
                companyId,
                storeId,
                installations.findByCompany_IdAndStore_Id(companyId, storeId),
                token);
        return new Context(
                installation.getCompany().getId(), installation.getStore().getId());
    }

    private CommandResult audit(
            UUID commandId,
            Context context,
            UUID actorUserId,
            String actorName,
            String operation,
            UUID targetId,
            String reason,
            Long configRevision,
            Long assignmentRevision) {
        Instant acceptedAt = clock.instant();
        jdbc.update("""
                insert into saas_member_category_admin_audit (
                    command_id, company_id, store_id, actor_user_id, actor_name,
                    actor_role, operation, target_id, reason,
                    config_revision, assignment_revision, accepted_at
                ) values (?, ?, ?, ?, ?, 'ADMIN', ?, ?, ?, ?, ?, ?)
                """,
                commandId, context.companyId(), context.storeId(), actorUserId,
                actorName.trim(), operation, targetId, reason.trim(),
                configRevision, assignmentRevision, acceptedAt);
        return new CommandResult(
                commandId, operation, targetId,
                configRevision, assignmentRevision, acceptedAt);
    }

    private CommandResult previous(UUID commandId) {
        List<CommandResult> values = jdbc.query("""
                select command_id, operation, target_id, config_revision,
                       assignment_revision, accepted_at
                from saas_member_category_admin_audit where command_id = ?
                """, (rs, row) -> new CommandResult(
                        rs.getObject("command_id", UUID.class),
                        rs.getString("operation"),
                        rs.getObject("target_id", UUID.class),
                        nullableLong(rs.getObject("config_revision")),
                        nullableLong(rs.getObject("assignment_revision")),
                        rs.getObject("accepted_at", Instant.class)), commandId);
        return values.isEmpty() ? null : values.get(0);
    }

    private void lockCommand(UUID commandId) {
        jdbc.query(
                "select pg_advisory_xact_lock(hashtext(?)::bigint)",
                (rs, row) -> Boolean.TRUE,
                commandId.toString());
    }

    private long nextRevision() {
        Long value = jdbc.queryForObject(
                "select nextval('saas_member_category_revision_seq')", Long.class);
        if (value == null) {
            throw new IllegalStateException("No se pudo reservar revision de categorias");
        }
        return value;
    }

    private static void validateCategory(CategoryCommand command) {
        if (command == null || command.commandId() == null
                || command.actorUserId() == null || command.categoryId() == null
                || blank(command.actorName()) || blank(command.actorRole())
                || blank(command.code()) || blank(command.name()) || blank(command.reason())
                || command.minPoints() < 0 || command.discountPercent() == null
                || command.discountPercent().compareTo(BigDecimal.ZERO) < 0
                || command.discountPercent().compareTo(new BigDecimal("100")) > 0) {
            throw invalid("Comando administrativo de categoria invalido");
        }
    }

    private static void validateAssignment(AssignmentCommand command) {
        if (command == null || command.commandId() == null
                || command.actorUserId() == null || command.memberId() == null
                || blank(command.actorName()) || blank(command.actorRole())
                || blank(command.action()) || blank(command.reason())) {
            throw invalid("Comando administrativo de asignacion invalido");
        }
        String action = command.action().trim().toUpperCase(Locale.ROOT);
        if (!("SET".equals(action) || "CLEAR".equals(action))
                || ("SET".equals(action) && command.categoryId() == null)
                || ("CLEAR".equals(action)
                        && (command.categoryId() != null || command.lockAutomatic()))) {
            throw invalid("Accion administrativa de asignacion invalida");
        }
    }

    private static void requireAdmin(String role) {
        if (!"ADMIN".equals(role.trim().toUpperCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Se requiere ADMIN");
        }
    }

    private static String normalizeCode(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private record Context(UUID companyId, UUID storeId) {
    }

    private record CategoryIdentity(UUID companyId, String code) {
    }

    private record CategoryBefore(long minPoints, boolean active) {
    }
}
