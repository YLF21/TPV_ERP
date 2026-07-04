alter table usuario
    add column must_change_password boolean not null default false;

update usuario
set must_change_password = true
where nombre = 'ADMIN'
  and tienda_id is null
  and protegido = true;
