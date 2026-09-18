package com.tpverp.backend.control;

import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ControlRuleVersionRepository extends JpaRepository<ControlRuleVersion, UUID> {
    List<ControlRuleVersion> findAllByRuleIdOrderByRuleVersionDesc(UUID ruleId);

    /** At most one effective version per rule and affected sequence point, including inactive versions. */
    @Query(value = """
            select distinct effective.*
            from venta_operacion_eliminacion operation
            join control_regla rule on rule.tienda_id = operation.tienda_id and rule.tipo in (:types)
            join lateral (
                select version.*
                from control_regla_version version
                where version.tienda_id = :storeId
                  and version.regla_id = rule.id
                  and version.cambiado_en <= operation.eliminado_en
                order by version.numero_version desc
                limit 1
            ) effective on true
            where operation.tienda_id = :storeId and operation.terminal_id = :terminalId
              and operation.usuario_id = :userId and operation.operacion_venta_id = :saleOperationId
              and operation.eliminado_en >= :fromOccurredAt
            """, nativeQuery = true)
    List<ControlRuleVersion> findEffectiveForDeletionSequence(UUID storeId, UUID terminalId, UUID userId,
            UUID saleOperationId, Instant fromOccurredAt, List<String> types);
}
