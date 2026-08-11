alter table documento
    add column liquidado_por_origen boolean not null default false;

update documento invoice
set estado = 'PAGADO',
    liquidado_por_origen = true,
    version = version + 1
where invoice.tipo = 'FACTURA_VENTA'
  and invoice.estado = 'PENDIENTE'
  and invoice.liquidado_por_origen = false
  and not exists (
      select 1
      from documento_pago invoice_payment
      where invoice_payment.documento_id = invoice.id)
  and exists (
      select 1
      from documento_relacion relation
      join documento source_ticket on source_ticket.id = relation.origen_id
      where relation.documento_id = invoice.id
        and relation.tipo = 'FACTURA_DE'
        and source_ticket.tipo = 'TICKET'
        and source_ticket.estado = 'CONFIRMADO'
        and source_ticket.tienda_id = invoice.tienda_id
        and source_ticket.moneda = invoice.moneda
        and source_ticket.numero = invoice.num_ticket
        and source_ticket.total = invoice.total
        and coalesce((
            select sum(source_payment.importe)
            from documento_pago source_payment
            where source_payment.documento_id = source_ticket.id
        ), 0) = source_ticket.total);
