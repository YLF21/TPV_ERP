insert into permiso (id, codigo, translation_key, grupo)
select gen_random_uuid(), 'GESTION_ALMACEN', 'inventory.permissions.warehouseManagement', 'INVENTORY'
where not exists (
    select 1 from permiso where codigo = 'GESTION_ALMACEN'
);

insert into rol_permiso (rol_id, permiso_id)
select distinct rol_permiso.rol_id, gestion_almacen.id
from rol_permiso
join permiso permiso_anterior on permiso_anterior.id = rol_permiso.permiso_id
cross join permiso gestion_almacen
where permiso_anterior.codigo in (
    'WAREHOUSE_INPUTS_READ',
    'WAREHOUSE_INPUTS_WRITE',
    'WAREHOUSE_INPUTS_DELETE',
    'WAREHOUSE_INPUTS_CONFIRM',
    'WAREHOUSE_OUTPUTS_READ',
    'WAREHOUSE_OUTPUTS_EDIT',
    'WAREHOUSE_OUTPUTS_DELETE',
    'WAREHOUSE_OUTPUTS_CONFIRM'
)
and gestion_almacen.codigo = 'GESTION_ALMACEN'
on conflict do nothing;

delete from rol_permiso
where permiso_id in (
    select id
    from permiso
    where codigo in (
        'WAREHOUSE_INPUTS_READ',
        'WAREHOUSE_INPUTS_WRITE',
        'WAREHOUSE_INPUTS_DELETE',
        'WAREHOUSE_INPUTS_CONFIRM',
        'WAREHOUSE_OUTPUTS_READ',
        'WAREHOUSE_OUTPUTS_EDIT',
        'WAREHOUSE_OUTPUTS_DELETE',
        'WAREHOUSE_OUTPUTS_CONFIRM'
    )
);

delete from permiso
where codigo in (
    'WAREHOUSE_INPUTS_READ',
    'WAREHOUSE_INPUTS_WRITE',
    'WAREHOUSE_INPUTS_DELETE',
    'WAREHOUSE_INPUTS_CONFIRM',
    'WAREHOUSE_OUTPUTS_READ',
    'WAREHOUSE_OUTPUTS_EDIT',
    'WAREHOUSE_OUTPUTS_DELETE',
    'WAREHOUSE_OUTPUTS_CONFIRM'
);
