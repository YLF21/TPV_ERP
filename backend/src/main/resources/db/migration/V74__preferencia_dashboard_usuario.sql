create table preferencia_dashboard (
    id uuid primary key,
    usuario_id uuid not null references usuario(id) on delete cascade,
    widgets jsonb not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint preferencia_dashboard_widgets_ck
        check (
            jsonb_typeof(widgets) = 'array'
            and jsonb_array_length(widgets) <= 24
        ),
    constraint preferencia_dashboard_fechas_ck
        check (updated_at >= created_at),
    constraint preferencia_dashboard_usuario_uq unique (usuario_id)
);

create index ix_preferencia_dashboard_usuario
    on preferencia_dashboard(usuario_id);
