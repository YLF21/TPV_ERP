-- Preserve recoverability for templates retired before the reason was stored.
-- Both audit events are emitted by the same "use predefined ticket design" transaction.
update plantilla_documento template
set motivo_retirada = 'BUILT_IN_DESIGN_SELECTED'
where template.estado = 'RETIRED'
  and template.tipo = 'TICKET'
  and template.artifact_reference is not null
  and template.sha256 is not null
  and exists (
      select 1
      from auditoria retired
      join auditoria style
        on style.tienda_id = retired.tienda_id
       and style.event = 'STORE_TICKET_PRINT_STYLE_UPDATED'
       and style.result = 'EXITO'
       and abs(extract(epoch from (style.creada_en - retired.creada_en))) <= 5
      where retired.event = 'DOCUMENT_TEMPLATE_RETIRED'
        and retired.result = 'EXITO'
        and retired.datos ->> 'templateId' = template.id::text
  );
