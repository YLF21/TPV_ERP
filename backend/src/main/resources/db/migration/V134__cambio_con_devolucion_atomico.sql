-- A customer-facing exchange is backed by two fiscal documents: the hidden
-- rectification and the new sale. COMPENSA links both without conflating the
-- return with a normal incoming payment.
alter table documento_relacion
    drop constraint if exists documento_relacion_tipo_check;

alter table documento_relacion
    add constraint documento_relacion_tipo_check
    check (tipo in ('FACTURA_DE', 'RECTIFICA', 'COMPENSA'));

alter table documento_devolucion_pago
    drop constraint if exists chk_documento_devolucion_pago_tipo;

alter table documento_devolucion_pago
    drop constraint if exists chk_documento_devolucion_pago_terminal;

alter table documento_devolucion_pago
    add constraint chk_documento_devolucion_pago_tipo
    check (tipo in ('CASH', 'CARD', 'VOUCHER', 'EXCHANGE'));

alter table documento_devolucion_pago
    add constraint chk_documento_devolucion_pago_terminal
    check (
        (
            tipo = 'CARD'
            and (
                terminal_operacion_id is not null
                or documento_pago_original_id is not null
                or nullif(btrim(referencia), '') is not null
            )
        )
        or (
            tipo in ('CASH', 'VOUCHER', 'EXCHANGE')
            and terminal_operacion_id is null
        )
    );

insert into metodo_pago (
    id, empresa_id, nombre, protegido, activo,
    requiere_referencia, abre_caja_registradora)
select gen_random_uuid(), empresa.id, 'COMPENSACION_DEVOLUCION', true, true, false, false
from empresa
where not exists (
    select 1
    from metodo_pago
    where metodo_pago.empresa_id = empresa.id
      and metodo_pago.nombre = 'COMPENSACION_DEVOLUCION'
);
