alter table control_regla
    drop constraint control_regla_tipo_ck;

alter table control_regla
    add constraint control_regla_tipo_ck check (tipo in (
        'SALE_SCREEN_CLEARED',
        'CONSECUTIVE_LINE_DELETIONS',
        'MANUAL_PRICE_CHANGE_OVER_PERCENT',
        'MANUAL_PRICE_CHANGED',
        'MANUAL_DISCOUNT_OVER_PERCENT',
        'PRODUCT_DISCOUNT_APPLIED',
        'TICKET_CANCELLED',
        'INACTIVE_PRODUCT_SOLD'
    ));

drop index ux_control_regla_tienda_nombre;

update control_regla
set nombre = case tipo
    when 'SALE_SCREEN_CLEARED' then 'Eliminacion completa de carrito'
    when 'CONSECUTIVE_LINE_DELETIONS' then 'Eliminacion de lineas consecutivas'
    when 'MANUAL_PRICE_CHANGE_OVER_PERCENT' then 'Cambio manual de precio superior al porcentaje'
    when 'MANUAL_PRICE_CHANGED' then 'Cambio manual de precio'
    when 'MANUAL_DISCOUNT_OVER_PERCENT' then 'Descuento manual superior al porcentaje'
    when 'PRODUCT_DISCOUNT_APPLIED' then 'Descuento manual aplicado a producto'
    when 'TICKET_CANCELLED' then 'Anulacion de ticket'
    when 'INACTIVE_PRODUCT_SOLD' then 'Venta de producto desactivado'
end;

do $$
begin
    if exists (
        select 1
        from control_regla
        group by tienda_id, tipo
        having count(*) > 1
    ) then
        raise exception using
            message = 'No se puede aplicar la unicidad de reglas: existen tipos duplicados por tienda',
            hint = 'Consolide los duplicados preservando sus eventos e historial antes de reintentar la migracion';
    end if;
end;
$$;

create unique index ux_control_regla_tienda_tipo
    on control_regla(tienda_id, tipo);

alter table control_regla
    add constraint control_regla_configuracion_tipo_ck check (
        (tipo in ('MANUAL_DISCOUNT_OVER_PERCENT', 'MANUAL_PRICE_CHANGE_OVER_PERCENT')
            and configuracion ? 'thresholdPercent'
            and configuracion - 'thresholdPercent' = '{}'::jsonb)
        or (tipo = 'CONSECUTIVE_LINE_DELETIONS'
            and configuracion ? 'minimumCount'
            and configuracion - 'minimumCount' = '{}'::jsonb)
        or (tipo in (
                'SALE_SCREEN_CLEARED',
                'MANUAL_PRICE_CHANGED',
                'PRODUCT_DISCOUNT_APPLIED',
                'TICKET_CANCELLED',
                'INACTIVE_PRODUCT_SOLD'
            ) and configuracion = '{}'::jsonb)
    );

create index ix_control_evento_tienda_regla_fecha
    on control_evento(tienda_id, regla_id, ocurrido_en desc);

create table venta_operacion_eliminacion (
    id uuid primary key,
    tienda_id uuid not null references tienda(id) on delete restrict,
    terminal_id uuid not null references terminal(id) on delete restrict,
    usuario_id uuid not null references usuario(id) on delete restrict,
    operacion_venta_id uuid not null,
    vaciado_completo boolean not null,
    eliminado_en timestamptz not null,
    constraint venta_operacion_eliminacion_tienda_uq unique (id, tienda_id)
);

create index ix_venta_operacion_eliminacion_secuencia
    on venta_operacion_eliminacion(
        tienda_id,
        terminal_id,
        usuario_id,
        operacion_venta_id,
        eliminado_en
    );

alter table venta_linea_eliminada
    add column operacion_venta_id uuid,
    add column operacion_eliminacion_id uuid,
    add constraint venta_linea_eliminada_operacion_fk
        foreign key (operacion_eliminacion_id, tienda_id)
        references venta_operacion_eliminacion(id, tienda_id)
        on delete restrict;

create index ix_venta_linea_eliminada_secuencia
    on venta_linea_eliminada(
        tienda_id,
        terminal_id,
        usuario_id,
        operacion_venta_id,
        eliminado_en,
        operacion_eliminacion_id
    );
