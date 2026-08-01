create table idempotencia_retirada_cierre (
    clave_idempotencia uuid primary key,
    tienda_id uuid not null references tienda(id),
    terminal_id uuid not null,
    sesion_caja_id uuid not null,
    movimiento_caja_id uuid unique references movimiento_caja(id),
    huella_solicitud varchar(64) not null,
    creado_en timestamptz not null,
    version bigint not null default 0,
    constraint idemp_retirada_cierre_sesion_fk
        foreign key (sesion_caja_id, terminal_id, tienda_id)
        references sesion_caja(id, terminal_id, tienda_id),
    constraint idemp_retirada_cierre_huella_ck
        check (huella_solicitud ~ '^[0-9a-f]{64}$')
);

create index idemp_retirada_cierre_sesion_idx
    on idempotencia_retirada_cierre(sesion_caja_id);
