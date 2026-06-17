alter table documento_pago
    add column codigo_vale varchar(32);

insert into metodo_pago (id, empresa_id, nombre, protegido, activo)
select gen_random_uuid(), empresa.id, 'VALE', true, true
from empresa
where not exists (
    select 1
    from metodo_pago
    where metodo_pago.empresa_id = empresa.id
      and metodo_pago.nombre = 'VALE'
);
