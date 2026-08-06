-- V131 allowed the unified checkout to settle negative carts. Early builds
-- created the negative ticket but omitted the fiscal link to its source ticket.
-- Restore that link from the immutable original line reference so subsequent
-- partial returns subtract the amount already returned.
insert into documento_relacion (documento_id, origen_id, tipo)
select distinct refund_line.documento_id, source_line.documento_id, 'RECTIFICA'
  from documento_linea refund_line
  join documento refund_document
    on refund_document.id = refund_line.documento_id
  join documento_linea source_line
    on source_line.id = refund_line.original_document_line_id
 where refund_document.estado not in ('BORRADOR', 'ANULADO')
   and refund_document.total <= 0
   and refund_line.original_document_line_id is not null
   and refund_line.documento_id <> source_line.documento_id
on conflict (documento_id, origen_id) do nothing;
