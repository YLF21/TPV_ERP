create table if not exists preferencia_visualizacion_informe (
    id uuid primary key,
    usuario_id uuid not null references usuario(id) on delete cascade,
    app varchar(32) not null,
    report_key varchar(128) not null,
    visible_attributes jsonb not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint preferencia_visualizacion_informe_app_ck check (char_length(trim(app)) > 0),
    constraint preferencia_visualizacion_informe_report_key_ck check (char_length(trim(report_key)) > 0),
    constraint preferencia_visualizacion_informe_visible_attributes_ck check (
        jsonb_typeof(visible_attributes) = 'array'
        and jsonb_array_length(visible_attributes) > 0
    ),
    constraint preferencia_visualizacion_informe_usuario_app_report_uq unique (usuario_id, app, report_key)
);

create index if not exists ix_preferencia_visualizacion_informe_usuario_app
    on preferencia_visualizacion_informe(usuario_id, app);
