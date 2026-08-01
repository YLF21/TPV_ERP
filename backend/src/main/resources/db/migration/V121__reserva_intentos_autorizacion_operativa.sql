alter table intento_autorizacion_operacion_venta
    add column reserva_id uuid,
    add column reserva_hasta timestamptz;

alter table intento_autorizacion_operacion_venta
    add constraint intento_autorizacion_operacion_venta_reserva_ck
        check (
            (reserva_id is null and reserva_hasta is null)
            or (reserva_id is not null and reserva_hasta is not null)
        );

create index intento_autorizacion_operacion_venta_reserva_idx
    on intento_autorizacion_operacion_venta (
        tienda_id,
        terminal_id,
        reserva_hasta
    )
    where reserva_hasta is not null;
