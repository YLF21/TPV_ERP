create table preferencia_columnas_stock (
    id uuid primary key,
    empresa_id uuid not null,
    tienda_id uuid not null,
    usuario_id uuid not null,
    app varchar(16) not null,
    columnas jsonb not null,
    creada_en timestamptz not null,
    actualizada_en timestamptz not null,
    constraint preferencia_columnas_stock_tienda_empresa_fk
        foreign key (tienda_id, empresa_id)
        references tienda(id, empresa_id) on delete cascade,
    constraint preferencia_columnas_stock_usuario_tienda_fk
        foreign key (usuario_id, tienda_id)
        references usuario_tienda(usuario_id, tienda_id) on delete cascade,
    constraint preferencia_columnas_stock_app_ck
        check (app in ('venta', 'gestion')),
    constraint preferencia_columnas_stock_columnas_ck
        check (jsonb_typeof(columnas) = 'object' and columnas <> '{}'::jsonb),
    constraint preferencia_columnas_stock_fechas_ck
        check (actualizada_en >= creada_en),
    constraint preferencia_columnas_stock_tienda_usuario_app_uq
        unique (tienda_id, usuario_id, app)
);

create index ix_preferencia_columnas_stock_scope
    on preferencia_columnas_stock(empresa_id, tienda_id, usuario_id, app);
