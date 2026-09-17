package com.tpverp.backend.control;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Labels for an already bounded alert page; never loads the page's complete histories. */
public interface ControlAlertReadRepository extends Repository<ControlAlert, UUID> {

    @Query(value = """
            select candidate.id as userId, candidate.nombre as name, candidate.user_name as userName
            from usuario candidate
            join tienda user_store on user_store.id = candidate.tienda_id
            join tienda current_store on current_store.empresa_id = user_store.empresa_id
            join rol role on role.id = candidate.rol_id
            where current_store.id = :storeId and candidate.activo = true
              and (cast(:assigneeId as uuid) is null or candidate.id = :assigneeId)
              and (candidate.tienda_id = current_store.id or exists (
                  select 1 from usuario_tienda access
                  where access.usuario_id = candidate.id and access.tienda_id = current_store.id
              ))
              and (role.nombre = 'ADMIN' or (
                  exists (select 1 from rol_permiso grant_entry
                          join permiso permission on permission.id = grant_entry.permiso_id
                          where grant_entry.rol_id = role.id and permission.codigo = 'APP_GESTION_ACCESS')
                  and exists (select 1 from rol_permiso grant_entry
                              join permiso permission on permission.id = grant_entry.permiso_id
                              where grant_entry.rol_id = role.id
                                and permission.codigo in ('CONTROL_ALERTS_READ', 'CONTROL_ALERTS_MANAGE'))
              ))
            order by candidate.nombre, candidate.id
            """, nativeQuery = true)
    List<AssigneeCandidate> eligibleAssignees(@Param("storeId") UUID storeId,
            @Param("assigneeId") UUID assigneeId);

    @Query(value = """
            select alert.id as alertId, terminal.nombre as terminalName,
                   assignee.nombre as assigneeName, review.comentario as reviewComment
            from control_alerta alert
            join control_evento event on event.id = alert.evento_id and event.tienda_id = :storeId
            join tienda store on store.id = alert.tienda_id
            left join terminal on terminal.id = event.terminal_id and terminal.tienda_id = store.id
            left join usuario assignee on assignee.id = alert.asignada_a
                and exists (select 1 from tienda user_store
                            where user_store.id = assignee.tienda_id
                              and user_store.empresa_id = store.empresa_id)
            left join lateral (
                select history.comentario
                from control_alerta_historial history
                where history.alerta_id = alert.id and history.tienda_id = store.id
                  and history.comentario ~ '[^[:space:]]'
                order by history.cambiado_en desc, history.id desc
                limit 1
            ) review on true
            where alert.tienda_id = :storeId and alert.id in (:alertIds)
            """, nativeQuery = true)
    List<SummaryLabels> summaryLabels(@Param("storeId") UUID storeId,
            @Param("alertIds") List<UUID> alertIds);

    @Query(value = """
            select distinct alert.id as alertId, line.posicion as position,
                   line.producto_id as productId, line.codigo as code, line.nombre as name
            from control_alerta alert
            join control_evento event on event.id = alert.evento_id and event.tienda_id = :storeId
            join tienda store on store.id = alert.tienda_id
            join documento document on document.id = event.documento_id
                and document.tienda_id = store.id
            cross join lateral jsonb_array_elements(
                (case when jsonb_typeof(event.datos -> 'changedLines') = 'array'
                      then event.datos -> 'changedLines' else '[]'::jsonb end)
                || (case when jsonb_typeof(event.datos -> 'discountedLines') = 'array'
                         then event.datos -> 'discountedLines' else '[]'::jsonb end)
                || (case when jsonb_typeof(event.datos -> 'matchingLines') = 'array'
                         then event.datos -> 'matchingLines' else '[]'::jsonb end)
            ) evidence(value)
            join documento_linea line on line.documento_id = document.id
                and cast(line.posicion as text) = evidence.value ->> 'position'
                and cast(line.producto_id as text) = evidence.value ->> 'productId'
            where alert.tienda_id = :storeId and alert.id in (:alertIds)
            """, nativeQuery = true)
    List<EvidenceLineLabels> evidenceLineLabels(@Param("storeId") UUID storeId,
            @Param("alertIds") List<UUID> alertIds);

    @Query(value = """
            select actor.id as userId, actor.nombre as name
            from usuario actor
            join tienda user_store on user_store.id = actor.tienda_id
            join tienda current_store on current_store.empresa_id = user_store.empresa_id
            where current_store.id = :storeId and actor.id in (:userIds)
            """, nativeQuery = true)
    List<UserLabel> userLabels(@Param("storeId") UUID storeId,
            @Param("userIds") List<UUID> userIds);

    @Query(value = """
            select customer.nombre_fiscal
            from documento document
            join tienda store on store.id = document.tienda_id
            join cliente customer on customer.id = document.cliente_id
                and customer.empresa_id = store.empresa_id
            where document.id = :documentId and document.tienda_id = :storeId
            """, nativeQuery = true)
    String customerName(@Param("storeId") UUID storeId, @Param("documentId") UUID documentId);

    interface AssigneeCandidate {
        UUID getUserId();
        String getName();
        String getUserName();
    }

    interface SummaryLabels {
        UUID getAlertId();
        String getTerminalName();
        String getAssigneeName();
        String getReviewComment();
    }

    interface EvidenceLineLabels {
        UUID getAlertId();
        int getPosition();
        UUID getProductId();
        String getCode();
        String getName();
    }

    interface UserLabel {
        UUID getUserId();
        String getName();
    }
}
