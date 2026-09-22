package com.tpverp.saas.access;

import static com.tpverp.saas.access.TenantAccessResponse.*;

import com.tpverp.saas.admin.AdminAuditService;
import com.tpverp.saas.plan.PlanLimitService;
import com.tpverp.saas.plan.PlanResource;
import com.tpverp.saas.tenant.SaasTenantUser;
import com.tpverp.saas.tenant.SaasTenantUserRepository;
import com.tpverp.saas.tenant.TenantRole;
import java.sql.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TenantAccessService {
    private final JdbcTemplate jdbc;
    private final SaasTenantUserRepository users;
    private final PlanLimitService limits;
    private final AdminAuditService audit;

    public TenantAccessService(JdbcTemplate jdbc, SaasTenantUserRepository users,
            PlanLimitService limits, AdminAuditService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.limits = limits;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public TenantAccessResponse access(String username) {
        return access(requireUser(username));
    }

    @Transactional(readOnly = true)
    public TenantAccessResponse access(SaasTenantUser user) {
        Map<UUID, List<StoreAccess>> stores = new LinkedHashMap<>();
        jdbc.query("""
                select a.company_id, s.id, s.code, s.name, s.internal_code, s.active
                from saas_tenant_store_access a join saas_store s on s.id = a.store_id and s.company_id = a.company_id
                where a.user_id = ? order by a.company_id, s.code, s.id
                """, rs -> {
                    UUID companyId = rs.getObject("company_id", UUID.class);
                    stores.computeIfAbsent(companyId, ignored -> new ArrayList<>()).add(new StoreAccess(
                            rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                            rs.getString("internal_code"), rs.getBoolean("active")));
                }, user.getId());
        List<CompanyAccess> companies = jdbc.query("""
                select a.company_id, c.name, a.role_name, a.company_privileges
                from saas_tenant_company_access a join saas_company c on c.id = a.company_id
                where a.user_id = ? order by lower(c.name), c.id
                """, (rs, row) -> {
                    UUID companyId = rs.getObject("company_id", UUID.class);
                    Array privileges = rs.getArray("company_privileges");
                    try {
                        Set<TenantCompanyPrivilege> allowed = Arrays.stream((String[]) privileges.getArray())
                                .map(TenantCompanyPrivilege::valueOf).collect(Collectors.toUnmodifiableSet());
                        return new CompanyAccess(companyId, rs.getString("name"), rs.getString("role_name"),
                                allowed, List.copyOf(stores.getOrDefault(companyId, List.of())));
                    } finally { privileges.free(); }
                }, user.getId());
        return new TenantAccessResponse(user.getUsername(), List.copyOf(companies));
    }

    @Transactional
    public TenantAccessResponse replace(String username, UUID companyId, UpdateTenantAccessRequest request) {
        SaasTenantUser user = requireUser(username);
        lockUser(user.getId());
        TenantRole role = role(request.roleName());
        CompanyAccess previous = access(user).companies().stream()
                .filter(company -> company.companyId().equals(companyId)).findFirst().orElse(null);
        String previousRole = previous == null ? null : previous.roleName();
        if (role == TenantRole.OWNER && !TenantRole.OWNER.name().equals(previousRole)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No se puede conceder el rol OWNER");
        }
        writeAccess(user, companyId, role, request.companyPrivileges(), request.storeIds(), previousRole == null);
        TenantAccessResponse updated = access(user);
        CompanyAccess current = updated.companies().stream().filter(company -> company.companyId().equals(companyId)).findFirst().orElseThrow();
        audit.log("UPDATE_TENANT_ACCESS", "TENANT_USER", user.getUsername(),
                "company=" + companyId + "; before=" + accessDetails(previous) + "; after=" + accessDetails(current));
        return updated;
    }

    @Transactional
    public TenantAccessResponse revoke(String username, UUID companyId) {
        SaasTenantUser user = requireUser(username);
        lockUser(user.getId());
        CompanyAccess previous = access(user).companies().stream()
                .filter(company -> company.companyId().equals(companyId)).findFirst().orElse(null);
        jdbc.update("delete from saas_tenant_company_access where user_id = ? and company_id = ?", user.getId(), companyId);
        audit.log("REVOKE_TENANT_ACCESS", "TENANT_USER", user.getUsername(),
                "company=" + companyId + "; before=" + accessDetails(previous));
        return access(user);
    }

    /** Compatibility adapter for creation flows. The caller supplies the exact initial stores. */
    @Transactional
    public void initializeAccess(UUID userId, UUID companyId, String roleName, Collection<UUID> storeIds) {
        users.flush();
        SaasTenantUser user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario cliente no existe"));
        lockUser(userId);
        Boolean exists = jdbc.queryForObject("select exists(select 1 from saas_tenant_company_access where user_id = ? and company_id = ?)",
                Boolean.class, userId, companyId);
        if (Boolean.TRUE.equals(exists)) return;
        TenantRole role = role(roleName);
        var privileges = EnumSet.allOf(TenantCompanyPrivilege.class);
        if (!role.canWriteErpMasters()) privileges.remove(TenantCompanyPrivilege.WRITE_MASTERS);
        writeAccess(user, companyId, role, privileges, Set.copyOf(storeIds), true);
    }

    private void writeAccess(SaasTenantUser user, UUID companyId, TenantRole role,
            Set<TenantCompanyPrivilege> privileges, Set<UUID> storeIds, boolean newMembership) {
        if (privileges == null || storeIds == null || storeIds.size() > 2000
                || privileges.stream().anyMatch(java.util.Objects::isNull)
                || storeIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Asignacion de acceso invalida");
        }
        if (privileges.contains(TenantCompanyPrivilege.WRITE_MASTERS)
                && (!role.canWriteErpMasters() || !privileges.contains(TenantCompanyPrivilege.READ_MASTERS))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escritura de maestros requiere lectura y rol OWNER o MANAGER");
        }
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from saas_company where id = ?)", Boolean.class, companyId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no existe");
        }
        Set<UUID> companyStores = Set.copyOf(jdbc.query("select id from saas_store where company_id = ?",
                (rs, row) -> rs.getObject(1, UUID.class), companyId));
        if (!companyStores.containsAll(storeIds)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Todas las tiendas deben pertenecer a la empresa seleccionada");
        }
        if (newMembership && user.isActive()) limits.requireCapacity(companyId, PlanResource.TENANT_USERS);
        String[] privilegeNames = privileges.stream().map(Enum::name).sorted().toArray(String[]::new);
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into saas_tenant_company_access(user_id, company_id, role_name, company_privileges)
                    values (?, ?, ?, ?)
                    on conflict (user_id, company_id) do update set role_name = excluded.role_name,
                        company_privileges = excluded.company_privileges, updated_at = current_timestamp
                    """);
            statement.setObject(1, user.getId());
            statement.setObject(2, companyId);
            statement.setString(3, role.name());
            statement.setArray(4, connection.createArrayOf("text", privilegeNames));
            return statement;
        });
        jdbc.update("delete from saas_tenant_store_access where user_id = ? and company_id = ?", user.getId(), companyId);
        for (UUID storeId : storeIds.stream().sorted().toList()) {
            jdbc.update("insert into saas_tenant_store_access(user_id, company_id, store_id) values (?, ?, ?)",
                    user.getId(), companyId, storeId);
        }
    }

    private void lockUser(UUID userId) {
        jdbc.queryForObject("select id from saas_tenant_user where id = ? for update", UUID.class, userId);
    }

    private static String accessDetails(CompanyAccess value) {
        if (value == null) return "none";
        return "role=" + value.roleName() + ",privileges="
                + value.companyPrivileges().stream().map(Enum::name).sorted().toList()
                + ",stores=" + value.stores().stream().map(StoreAccess::storeId).sorted().toList();
    }

    private SaasTenantUser requireUser(String username) {
        return users.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario cliente no existe"));
    }

    private static TenantRole role(String value) {
        try { return TenantRole.parse(value); }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rol cliente no valido");
        }
    }
}
