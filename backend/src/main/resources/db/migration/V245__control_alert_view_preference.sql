-- Each user owns their presentation settings; this does not alter detection rules.
create table preferencia_vista_alertas (
    usuario_id uuid primary key references usuario(id) on delete cascade,
    opciones jsonb not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint preferencia_vista_alertas_opciones_ck check (jsonb_typeof(opciones) = 'object'),
    constraint preferencia_vista_alertas_fechas_ck check (updated_at >= created_at)
);
