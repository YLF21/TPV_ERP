package com.tpverp.backend.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductPriceRuleRepository extends JpaRepository<ProductPriceRule, UUID> {

    List<ProductPriceRule> findByCompanyIdOrderByUpdatedAtDesc(UUID companyId);

    Optional<ProductPriceRule> findByIdAndCompanyId(UUID id, UUID companyId);

    @Query(value = """
            select exists(
                select 1
                from producto_regla_precio rule
                cross join lateral jsonb_array_elements(rule.formularios) form
                cross join lateral jsonb_array_elements(
                    coalesce(form -> 'conditions', '[]'::jsonb)) condition
                cross join lateral jsonb_array_elements_text(
                    coalesce(condition -> 'values', '[]'::jsonb)) selected_value
                where rule.empresa_id = :companyId
                  and condition ->> 'type' = 'REFERENCE'
                  and condition ->> 'field' in ('FAMILY', 'SUBFAMILY')
                  and case when selected_value ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                           then selected_value::uuid end = any(cast(:targetIds as uuid[]))
            )
            """, nativeQuery = true)
    boolean existsFamilyOrSubfamilyReference(UUID companyId, String targetIds);

    @Query(value = """
            select distinct rule.id as "ruleId", rule.nombre as "ruleName"
            from producto_regla_precio rule
            cross join lateral jsonb_array_elements(rule.formularios) form
            cross join lateral jsonb_array_elements(
                coalesce(form -> 'conditions', '[]'::jsonb)) condition
            cross join lateral jsonb_array_elements_text(
                coalesce(condition -> 'values', '[]'::jsonb)) selected_value
            where rule.empresa_id = :companyId
              and condition ->> 'type' = 'REFERENCE'
              and condition ->> 'field' in ('FAMILY', 'SUBFAMILY')
              and case when selected_value ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                       then selected_value::uuid end = any(cast(:targetIds as uuid[]))
            """, nativeQuery = true)
    List<ProductPriceRuleReference> findFamilyOrSubfamilyReferences(UUID companyId, String targetIds);
}
