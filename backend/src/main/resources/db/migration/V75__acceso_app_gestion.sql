insert into permiso (id, codigo, translation_key, grupo)
select gen_random_uuid(), 'APP_GESTION_ACCESS', 'security.permissions.appGestionAccess', 'SECURITY'
where not exists (
    select 1 from permiso where codigo = 'APP_GESTION_ACCESS'
);

insert into rol_permiso (rol_id, permiso_id)
select distinct rol_permiso.rol_id, app_gestion_access.id
from rol_permiso
join permiso permiso_anterior on permiso_anterior.id = rol_permiso.permiso_id
cross join permiso app_gestion_access
where permiso_anterior.codigo in (
    'GESTION_VENTAS',
    'GESTION_PRODUCTO',
    'GESTION_ALMACEN'
)
and app_gestion_access.codigo = 'APP_GESTION_ACCESS'
on conflict do nothing;
