package com.tpverp.saas.fiscal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaasFiscalStatusRepository extends JpaRepository<SaasFiscalStatus, UUID> {
    Optional<SaasFiscalStatus> findByInstallation_Id(UUID installationId);
    @Query(value = """
            select c.id as companyId, c.name as companyName, c.tax_id as taxId,
                   s.id as storeId, s.name as storeName,
                   i.installation_id as installationId,
                   i.installation_reference as installationReference,
                   coalesce(fs.effective_mode, 'UNKNOWN') as effectiveMode,
                   coalesce(fs.activation_state, 'UNKNOWN') as activationState,
                   coalesce(fs.mode_version, 0) as modeVersion,
                   fs.mode_since as modeSince, fs.activation_date as activationDate,
                   fs.policy_version as policyVersion, fs.runtime_class as runtimeClass,
                   fs.endpoint_environment as endpointEnvironment,
                   fs.transport_mode as transportMode, fs.reported_at as reportedAt,
                   fs.received_at as receivedAt,
                   (fs.id is null or fs.reported_at < :staleBefore) as stale,
                   lower(c.name) as companySort, lower(s.name) as storeSort,
                   lower(s.code) as codeSort
              from saas_store s
              join saas_company c on c.id = s.company_id
              left join lateral (
                  select i.* from saas_installation i
                   where i.store_id = s.id and i.active = true
                   order by i.linked_at desc, i.id desc
                   limit 1
              ) i on true
              left join saas_fiscal_status fs on fs.installation_id = i.id
             where (:companyId is null or s.company_id = :companyId)
               and (:storeId is null or s.id = :storeId)
               and (:installationId is null or i.installation_id = :installationId)
               and (:effectiveMode is null or coalesce(fs.effective_mode, 'UNKNOWN') = :effectiveMode)
               and (:activationState is null or coalesce(fs.activation_state, 'UNKNOWN') = :activationState)
               and (:stale is null or (fs.id is null or fs.reported_at < :staleBefore) = :stale)
               and (:cursorCompanySort is null
                    or lower(c.name) > :cursorCompanySort
                    or (lower(c.name) = :cursorCompanySort and lower(s.name) > :cursorStoreSort)
                    or (lower(c.name) = :cursorCompanySort and lower(s.name) = :cursorStoreSort
                        and lower(s.code) > :cursorCodeSort)
                    or (lower(c.name) = :cursorCompanySort and lower(s.name) = :cursorStoreSort
                        and lower(s.code) = :cursorCodeSort and s.id > :cursorStoreId))
             order by companySort, storeSort, codeSort, s.id
            """, nativeQuery = true)
    List<AdminStatusRow> findAdminPage(
            @Param("companyId") UUID companyId,
            @Param("storeId") UUID storeId,
            @Param("installationId") UUID installationId,
            @Param("effectiveMode") String effectiveMode,
            @Param("activationState") String activationState,
            @Param("stale") Boolean stale,
            @Param("staleBefore") Instant staleBefore,
            @Param("cursorCompanySort") String cursorCompanySort,
            @Param("cursorStoreSort") String cursorStoreSort,
            @Param("cursorCodeSort") String cursorCodeSort,
            @Param("cursorStoreId") UUID cursorStoreId,
            Pageable pageable);

    @Query(value = """
            with inventory as (
                select s.company_id, s.id as store_id, i.id as installation_id,
                       fs.effective_mode, fs.activation_state, fs.reported_at
                  from saas_store s
                  left join lateral (
                      select i.* from saas_installation i
                       where i.store_id = s.id and i.active = true
                       order by i.linked_at desc, i.id desc limit 1
                  ) i on true
                  left join saas_fiscal_status fs on fs.installation_id = i.id
            )
            select c.id as companyId, c.name as companyName, c.tax_id as taxId,
                   count(inv.store_id) as stores,
                   count(inv.installation_id) as installations,
                   count(inv.store_id) - count(inv.installation_id) as unlinkedStores,
                   count(inv.installation_id) filter (
                       where inv.reported_at is null or inv.reported_at < :staleBefore) as staleInstallations,
                   count(distinct coalesce(inv.effective_mode, 'UNKNOWN')) as modeCount,
                   min(coalesce(inv.effective_mode, 'UNKNOWN')) as singleMode,
                   count(distinct inv.activation_state) filter (
                       where inv.reported_at is not null and inv.reported_at >= :staleBefore) as stateCount,
                   min(inv.activation_state) filter (
                       where inv.reported_at is not null and inv.reported_at >= :staleBefore) as singleState,
                   max(inv.reported_at) as lastReportedAt,
                   lower(c.name) as companySort
              from saas_company c
              join inventory inv on inv.company_id = c.id
             where (:companyId is null or c.id = :companyId)
               and (:companyName is null or lower(c.name) like lower(:companyName))
               and (:cursorCompanySort is null or lower(c.name) > :cursorCompanySort
                    or (lower(c.name) = :cursorCompanySort and c.id > :cursorCompanyId))
             group by c.id, c.name, c.tax_id
             order by companySort, c.id
            """, nativeQuery = true)
    List<AdminCompanyRow> findAdminCompanyPage(
            @Param("companyId") UUID companyId,
            @Param("companyName") String companyName,
            @Param("staleBefore") Instant staleBefore,
            @Param("cursorCompanySort") String cursorCompanySort,
            @Param("cursorCompanyId") UUID cursorCompanyId,
            Pageable pageable);

    interface AdminStatusRow {
        UUID getCompanyId();
        String getCompanyName();
        String getTaxId();
        UUID getStoreId();
        String getStoreName();
        UUID getInstallationId();
        String getInstallationReference();
        String getEffectiveMode();
        String getActivationState();
        long getModeVersion();
        Instant getModeSince();
        java.time.LocalDate getActivationDate();
        Long getPolicyVersion();
        String getRuntimeClass();
        String getEndpointEnvironment();
        String getTransportMode();
        Instant getReportedAt();
        Instant getReceivedAt();
        boolean isStale();
        String getCompanySort();
        String getStoreSort();
        String getCodeSort();
    }

    interface AdminCompanyRow {
        UUID getCompanyId();
        String getCompanyName();
        String getTaxId();
        long getStores();
        long getInstallations();
        long getUnlinkedStores();
        long getStaleInstallations();
        long getModeCount();
        String getSingleMode();
        long getStateCount();
        String getSingleState();
        Instant getLastReportedAt();
        String getCompanySort();
    }
}
