create table pda_vinculacion_temporal (
    id uuid primary key,
    terminal_id uuid not null references terminal(id) on delete cascade,
    codigo_hash varchar(64) not null unique,
    emitido_en timestamptz not null,
    expira_en timestamptz not null,
    consumido_en timestamptz,
    version bigint not null default 0
);

create index idx_pda_vinculacion_terminal
    on pda_vinculacion_temporal(terminal_id, emitido_en desc);
create index idx_pda_vinculacion_pendiente
    on pda_vinculacion_temporal(expira_en)
    where consumido_en is null;
