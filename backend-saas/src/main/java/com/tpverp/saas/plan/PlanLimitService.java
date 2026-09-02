package com.tpverp.saas.plan;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlanLimitService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PlanLimitService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void requireCapacity(UUID companyId, PlanResource resource) {
        lockCompany(companyId);
        PlanUsageResponse usage = usage(companyId);
        long used = usage.usage().get(resource);
        long limit = usage.limits().get(resource);
        if (used >= limit) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Limite del plan " + usage.planName() + " alcanzado para " + resource.name());
        }
    }

    public String requireKnownPlan(String value) {
        String normalized = value == null || value.isBlank()
                ? "STANDARD" : value.trim().toUpperCase(java.util.Locale.ROOT);
        Boolean exists = jdbc.queryForObject(
                "select exists(select 1 from saas_plan_policy where plan_name = ?)", Boolean.class, normalized);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Plan no soportado: " + normalized);
        }
        return normalized;
    }
    public PlanUsageResponse usage(UUID companyId) {
        Policy policy = jdbc.query("""
                select p.plan_name, p.max_tenant_users, p.max_stores, p.max_licenses,
                       p.max_master_records, p.max_sync_events_per_day
                from saas_plan_policy p
                where p.plan_name = coalesce((
                    select upper(o.plan_name) from saas_company_operations o where o.company_id = ?
                ), 'STANDARD')
                """, rs -> rs.next() ? new Policy(
                rs.getString("plan_name"),
                rs.getLong("max_tenant_users"),
                rs.getLong("max_stores"),
                rs.getLong("max_licenses"),
                rs.getLong("max_master_records"),
                rs.getLong("max_sync_events_per_day")) : null, companyId);
        if (policy == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El plan de la empresa no tiene politica configurada");
        }
        EnumMap<PlanResource, Long> used = new EnumMap<>(PlanResource.class);
        used.put(PlanResource.TENANT_USERS, count(
                "select count(*) from saas_tenant_user where company_id = ? and active = true", companyId));
        used.put(PlanResource.STORES, count("select count(*) from saas_store where company_id = ?", companyId));
        used.put(PlanResource.LICENSES, count("select count(*) from saas_license where company_id = ?", companyId));
        used.put(PlanResource.MASTER_RECORDS, count("""
                select (select count(*) from saas_erp_customer where company_id = ?)
                     + (select count(*) from saas_erp_product where company_id = ?)
                     + (select count(*) from saas_erp_supplier where company_id = ?)
                     + (select count(*) from saas_erp_warehouse where company_id = ?)
                """, companyId, companyId, companyId, companyId));
        used.put(PlanResource.SYNC_EVENTS_DAILY, count(
                "select count(*) from saas_sync_event where company_id = ? and received_at >= ?",
                companyId, Timestamp.from(clock.instant().minus(Duration.ofDays(1)))));

        EnumMap<PlanResource, Long> limits = new EnumMap<>(PlanResource.class);
        limits.put(PlanResource.TENANT_USERS, policy.maxTenantUsers());
        limits.put(PlanResource.STORES, policy.maxStores());
        limits.put(PlanResource.LICENSES, policy.maxLicenses());
        limits.put(PlanResource.MASTER_RECORDS, policy.maxMasterRecords());
        limits.put(PlanResource.SYNC_EVENTS_DAILY, policy.maxSyncEventsPerDay());
        return new PlanUsageResponse(companyId, policy.name(), Map.copyOf(used), Map.copyOf(limits));
    }


    private void lockCompany(UUID companyId) {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement(
                    "select pg_advisory_xact_lock(hashtextextended(?::text, 0))")) {
                statement.setObject(1, companyId);
                statement.execute();
            }
            return null;
        });
    }    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private record Policy(String name, long maxTenantUsers, long maxStores, long maxLicenses,
            long maxMasterRecords, long maxSyncEventsPerDay) {
    }
}
