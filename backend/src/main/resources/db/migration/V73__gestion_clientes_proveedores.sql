insert into permiso (id, codigo, translation_key, grupo)
select gen_random_uuid(), 'GESTION_CLIENTE_PROVEEDOR', 'party.permissions.management', 'PARTY'
where not exists (
    select 1 from permiso where codigo = 'GESTION_CLIENTE_PROVEEDOR'
);
