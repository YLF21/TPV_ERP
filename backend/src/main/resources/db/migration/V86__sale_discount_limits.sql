alter table usuario
    add column max_discount_percent numeric(5, 2) not null default 0.00;

alter table usuario
    add constraint ck_usuario_max_discount_percent
    check (max_discount_percent >= 0.00 and max_discount_percent <= 100.00);

update usuario usuario_actual
set max_discount_percent = 100.00
where usuario_actual.protegido = true
   or exists (
       select 1
       from rol_permiso
       join permiso on permiso.id = rol_permiso.permiso_id
       where rol_permiso.rol_id = usuario_actual.rol_id
         and permiso.codigo = 'APLICAR_DESCUENTO'
   );
