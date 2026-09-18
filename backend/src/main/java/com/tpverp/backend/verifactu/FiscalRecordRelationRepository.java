package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FiscalRecordRelationRepository
        extends JpaRepository<FiscalRecordRelation, FiscalRecordRelation.Key> {

    @Query(value = """
            with recursive ancestors(registro_id, cadena_id, documento_id, secuencia, camino) as (
                select parent.id, parent.cadena_id, parent.documento_id, parent.secuencia,
                       array[child.id, parent.id]
                  from registro_fiscal_relacion relation
                  join registro_fiscal child on child.id = relation.registro_id
                  join registro_fiscal parent on parent.id = relation.relacionado_id
                 where relation.registro_id = :recordId
                   and relation.tipo = 'SUBSANA'
                   and relation.cadena_id = child.cadena_id
                   and parent.cadena_id = child.cadena_id
                   and parent.documento_id is not distinct from child.documento_id
                   and parent.secuencia < child.secuencia
                union all
                select parent.id, parent.cadena_id, parent.documento_id, parent.secuencia,
                       ancestors.camino || parent.id
                  from registro_fiscal_relacion relation
                  join ancestors on ancestors.registro_id = relation.registro_id
                                  and ancestors.cadena_id = relation.cadena_id
                  join registro_fiscal parent on parent.id = relation.relacionado_id
                 where relation.tipo = 'SUBSANA'
                   and parent.cadena_id = ancestors.cadena_id
                   and parent.documento_id is not distinct from ancestors.documento_id
                   and parent.secuencia < ancestors.secuencia
                   and not (parent.id = any(ancestors.camino))
            )
            select registro_id from ancestors
             group by registro_id
             order by min(secuencia), registro_id
            """, nativeQuery = true)
    List<UUID> findSubsanationAncestorIds(@Param("recordId") UUID recordId);

    Optional<FiscalRecordRelation> findByRecordIdAndType(
            UUID recordId, FiscalRelationType type);
}
