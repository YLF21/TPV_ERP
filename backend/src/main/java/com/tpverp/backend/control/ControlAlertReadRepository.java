package com.tpverp.backend.control;

import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Labels for an already bounded alert page; never loads the page's complete histories. */
public interface ControlAlertReadRepository extends Repository<ControlAlert, UUID> {

    String AUTHORIZATION_SOURCES = """
            with sources as (
                select checkout.documento_id as document_id, 'POS_CASH' as source_type,
                       checkout.checkout_id as source_id, checkout.user_id as operator_id
                from pos_cash_checkout checkout
                where checkout.store_id = :storeId and checkout.documento_id in (:documentIds)
                union all
                select checkout.documento_id, 'POS_CARD', checkout.id, checkout.requested_user_id
                from pos_card_checkout checkout
                join documento document on document.id = checkout.documento_id
                    and document.tienda_id = :storeId
                where checkout.requested_store_id = :storeId
                    and checkout.documento_id in (:documentIds)
                union all
                select session.ticket_id, 'PAYMENT_SESSION', session.id, session.user_id
                from sale_payment_session session
                where session.store_id = :storeId and session.ticket_id in (:documentIds)
            )
            """;

    /** Only persisted checkout-to-document links establish the authorization's scope. */
    @Query(value = AUTHORIZATION_SOURCES + """
            select distinct source.document_id as documentId,
                   source.operator_id as operatorId,
                   audit.datos ->> 'operationCode' as operationCode,
                   audit.datos ->> 'authorizerId' as authorizerId,
                   audit.datos ->> 'authorizerUsername' as authorizerName,
                   audit.datos ->> 'delegated' as delegated,
                   audit.creada_en as authorizedAt
            from sources source
            join auditoria audit on audit.tienda_id = :storeId
                and audit.event = 'SALE_OPERATION_AUTHORIZED' and audit.result = 'EXITO'
                and audit.datos ->> 'sourceType' = source.source_type
                and audit.datos ->> 'sourceId' = cast(source.source_id as text)
                and audit.datos ->> 'operatorId' = cast(source.operator_id as text)
            """, nativeQuery = true)
    List<AuthorizationEvidence> authorizationEvidence(@Param("storeId") UUID storeId,
            @Param("documentIds") List<UUID> documentIds);

    interface AuthorizationEvidence {
        UUID getDocumentId();
        UUID getOperatorId();
        String getOperationCode();
        String getAuthorizerId();
        String getAuthorizerName();
        String getDelegated();
        Instant getAuthorizedAt();
    }

    @Query(value = AUTHORIZATION_SOURCES + """
            select distinct source.document_id as documentId, source.operator_id as userId,
                   actor.user_name as userName
            from sources source
            left join usuario actor on actor.id = source.operator_id
                and exists (select 1 from tienda actor_store
                    join tienda current_store on current_store.id = :storeId
                        and current_store.empresa_id = actor_store.empresa_id
                    where actor_store.id = actor.tienda_id)
            """, nativeQuery = true)
    List<CheckoutOperator> checkoutOperators(@Param("storeId") UUID storeId,
            @Param("documentIds") List<UUID> documentIds);

    interface CheckoutOperator {
        UUID getDocumentId();
        UUID getUserId();
        String getUserName();
    }

    /** These checks happen after session creation, so its lifecycle gives exact indexable bounds. */
    @Query(value = """
            select distinct session.ticket_id as documentId, session.user_id as operatorId,
                   case audit.event
                       when 'CUSTOMER_CREDIT_LIMIT_OVERRIDDEN' then 'CREDIT_OVERRIDE'
                       when 'CUSTOMER_PENDING_RECEIVABLE_AUTHORIZED' then 'CREATE_PENDING_RECEIVABLE'
                       else audit.datos ->> 'operationCode' end as operationCode,
                   coalesce(audit.datos ->> 'authorizerId',
                            audit.datos ->> 'authorizerUserId') as authorizerId,
                   audit.datos ->> 'authorizerUsername' as authorizerName,
                   audit.datos ->> 'delegated' as delegated, audit.creada_en as authorizedAt
            from sale_payment_session session
            join auditoria audit on audit.tienda_id = session.store_id and audit.result = 'EXITO'
                and audit.creada_en >= session.created_at and audit.creada_en <= session.updated_at
            where session.store_id = :storeId and session.ticket_id in (:documentIds)
                and (
                    (audit.event in ('CUSTOMER_CREDIT_LIMIT_OVERRIDDEN',
                                     'CUSTOMER_PENDING_RECEIVABLE_AUTHORIZED')
                        and audit.datos ->> 'documentId' = cast(session.ticket_id as text)
                        and audit.datos ->> 'operatorUserId' = cast(session.user_id as text))
                    or
                    (audit.event in ('SALE_STANDARD_PAYMENT_AUTHORIZED',
                                     'REFUND_POLICY_OVERRIDE_AUTHORIZED',
                                     'REFUND_TENDER_OVERRIDE_AUTHORIZED')
                        and audit.datos ->> 'paymentSessionId' = cast(session.id as text)
                        and audit.datos ->> 'operatorId' = cast(session.user_id as text)
                        and exists (select 1 from sale_payment_allocation allocation
                            where allocation.session_id = session.id and allocation.status = 'APPROVED'
                                and cast(allocation.id as text) = audit.datos ->> 'allocationId'))
                )
            """, nativeQuery = true)
    List<AuthorizationEvidence> paymentSessionAuthorizations(@Param("storeId") UUID storeId,
            @Param("documentIds") List<UUID> documentIds);

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
