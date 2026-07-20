do $$
declare
    admin_id uuid;
    admin_role_id uuid;
begin
    select usuario.id, usuario.rol_id
      into admin_id, admin_role_id
      from usuario
      join rol on rol.id = usuario.rol_id
     where usuario.nombre = 'ADMIN'
       and usuario.protegido
       and usuario.activo
       and usuario.tienda_id is not null
       and rol.nombre = 'ADMIN'
       and rol.protegido
       and not exists (
           select 1
             from usuario global_admin
            where global_admin.nombre = 'ADMIN'
              and global_admin.protegido
              and global_admin.tienda_id is null
       )
       and (
           select count(*)
             from usuario legacy_admin
            where legacy_admin.nombre = 'ADMIN'
              and legacy_admin.protegido
              and legacy_admin.tienda_id is not null
       ) = 1
       and (
           select count(*)
             from usuario role_user
            where role_user.rol_id = usuario.rol_id
       ) = 1;

    if admin_id is not null then
        delete from usuario_tienda where usuario_id = admin_id;
        update rol set tienda_id = null where id = admin_role_id;
        update usuario set tienda_id = null where id = admin_id;
    end if;
end
$$;
