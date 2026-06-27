alter table metodo_pago
    add column requiere_referencia boolean not null default false,
    add column abre_caja_registradora boolean not null default false;

alter table documento_pago
    add column referencia varchar(128);

update metodo_pago
set abre_caja_registradora = true
where nombre = 'EFECTIVO';

insert into metodo_pago (id, empresa_id, nombre, protegido, activo, requiere_referencia, abre_caja_registradora)
select gen_random_uuid(), empresa.id, metodo.nombre, true, true, metodo.requiere_referencia, metodo.abre_caja_registradora
from empresa
cross join (values
    ('EFECTIVO', false, true),
    ('TARJETA', false, false),
    ('TRANSFERENCIA', false, false),
    ('VALE', false, false),
    ('DESCUENTO', false, false),
    ('OTRO', false, false)
) as metodo(nombre, requiere_referencia, abre_caja_registradora)
where not exists (
    select 1
    from metodo_pago
    where metodo_pago.empresa_id = empresa.id
      and metodo_pago.nombre = metodo.nombre
);
