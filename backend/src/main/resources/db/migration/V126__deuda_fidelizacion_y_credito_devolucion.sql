alter table miembro
    add column loyalty_balance_debt numeric(19,2) not null default 0,
    add column loyalty_points_debt bigint not null default 0,
    add constraint ck_miembro_loyalty_balance_debt
        check (loyalty_balance_debt >= 0),
    add constraint ck_miembro_loyalty_points_debt
        check (loyalty_points_debt >= 0);

alter table member_movement
    drop constraint if exists ck_member_movement_type;

alter table member_movement
    add constraint ck_member_movement_type check (type in (
        'ALTA_MIEMBRO', 'DESACTIVACION_MIEMBRO', 'CAMBIO_CATEGORIA',
        'ACUMULACION_PUNTOS', 'ACUMULACION_SALDO', 'USO_SALDO',
        'CADUCIDAD_SALDO', 'AJUSTE_MANUAL_SALDO', 'AJUSTE_MANUAL_PUNTOS',
        'AJUSTE_SAAS', 'ANULACION_ACUMULACION_PUNTOS',
        'ANULACION_ACUMULACION_SALDO', 'ANULACION_USO_SALDO',
        'DEVOLUCION_ACUMULACION_PUNTOS', 'DEVOLUCION_ACUMULACION_SALDO',
        'DEVOLUCION_RESTAURACION_SALDO', 'PAGO_DEUDA_PUNTOS',
        'PAGO_DEUDA_SALDO'
    ));

insert into metodo_pago (
    id, empresa_id, nombre, protegido, activo,
    requiere_referencia, abre_caja_registradora
)
select gen_random_uuid(), empresa.id, 'CREDITO_DEVOLUCION', true, true, false, false
from empresa
where not exists (
    select 1
    from metodo_pago
    where metodo_pago.empresa_id = empresa.id
      and metodo_pago.nombre = 'CREDITO_DEVOLUCION'
);
