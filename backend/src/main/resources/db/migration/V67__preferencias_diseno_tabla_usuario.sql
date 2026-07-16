create table preferencia_diseno_tabla (
    id uuid primary key,
    usuario_id uuid not null references usuario(id) on delete cascade,
    app varchar(16) not null,
    table_key varchar(128) not null,
    columnas jsonb not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint preferencia_diseno_tabla_app_ck
        check (app in ('venta', 'gestion')),
    constraint preferencia_diseno_tabla_table_key_ck
        check (table_key ~ '^[A-Za-z0-9._-]+$'),
    constraint preferencia_diseno_tabla_columnas_ck
        check (
            jsonb_typeof(columnas) = 'array'
            and jsonb_array_length(columnas) <= 128
        ),
    constraint preferencia_diseno_tabla_fechas_ck
        check (updated_at >= created_at),
    constraint preferencia_diseno_tabla_usuario_app_tabla_uq
        unique (usuario_id, app, table_key)
);

create index ix_preferencia_diseno_tabla_usuario_app
    on preferencia_diseno_tabla(usuario_id, app);

with stock_layouts as (
    select distinct on (stock.usuario_id, stock.app, layout.table_key)
        stock.usuario_id,
        stock.app,
        layout.table_key,
        layout.columns,
        stock.creada_en as created_at,
        stock.actualizada_en as updated_at
    from preferencia_columnas_stock stock
    cross join lateral jsonb_each(stock.columnas) as layout(table_key, columns)
    order by
        stock.usuario_id,
        stock.app,
        layout.table_key,
        stock.actualizada_en desc,
        stock.creada_en desc,
        stock.id desc
)
insert into preferencia_diseno_tabla (
    id, usuario_id, app, table_key, columnas, created_at, updated_at, version)
select
    gen_random_uuid(),
    usuario_id,
    app,
    table_key,
    columns,
    created_at,
    updated_at,
    0
from stock_layouts;

do $do$
begin
    if to_regclass('preferencia_visualizacion_informe') is not null
            and has_table_privilege(
                current_user,
                'preferencia_visualizacion_informe',
                'select') then
        execute $migration$
            insert into preferencia_diseno_tabla (
                id, usuario_id, app, table_key, columnas, created_at, updated_at, version)
            select
                gen_random_uuid(),
                report.usuario_id,
                report.app,
                'reports.' || report.report_key,
                layout.columns,
                report.created_at,
                report.updated_at,
                0
            from preferencia_visualizacion_informe report
            cross join lateral (
                select jsonb_agg(
                    jsonb_build_object('key', attribute.value, 'visible', true)
                    order by attribute.position
                ) as columns
                from jsonb_array_elements_text(report.visible_attributes)
                    with ordinality as attribute(value, position)
            ) layout
        $migration$;
    end if;
end
$do$;
