alter table tienda
    add column saas_internal_code varchar(7),
    add constraint ck_tienda_saas_internal_code check (
        saas_internal_code is null or (
            saas_internal_code ~ '^(0[1-9]|[1-4][0-9]|5[0-2])[0-9]{5}$'
            and right(saas_internal_code, 5) <> '00000'
        )
    );

comment on column tienda.saas_internal_code is
    'Optional SaaS store lookup code; presentation only, independent of local and fiscal identity.';
