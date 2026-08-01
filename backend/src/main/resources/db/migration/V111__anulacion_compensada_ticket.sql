alter table vale
    drop constraint if exists vale_status_check;

alter table vale
    add constraint vale_status_check
        check (status in ('ACTIVE', 'CONSUMED', 'INVALIDATED'));

create table vale_evento (
    id uuid primary key,
    vale_id uuid not null references vale(id),
    documento_id uuid not null references documento(id),
    tienda_id uuid not null references tienda(id),
    tipo varchar(24) not null
        check (tipo in ('RESTORED', 'INVALIDATED')),
    importe numeric(19,2) not null check (importe >= 0),
    usuario_id uuid not null references usuario(id),
    ocurrido_en timestamptz not null,
    detalle jsonb not null default '{}'::jsonb,
    unique (vale_id, documento_id, tipo)
);

create index ix_vale_evento_documento
    on vale_evento(documento_id, ocurrido_en);

create index if not exists ix_vale_tickets_origen_gin
    on vale using gin (tickets_origen);

alter table member_movement
    drop constraint if exists ck_member_movement_type;

alter table member_movement
    add constraint ck_member_movement_type check (type in (
        'ALTA_MIEMBRO', 'DESACTIVACION_MIEMBRO', 'CAMBIO_CATEGORIA',
        'ACUMULACION_PUNTOS', 'ACUMULACION_SALDO', 'USO_SALDO',
        'CADUCIDAD_SALDO', 'AJUSTE_MANUAL_SALDO', 'AJUSTE_MANUAL_PUNTOS',
        'AJUSTE_SAAS', 'ANULACION_ACUMULACION_PUNTOS',
        'ANULACION_ACUMULACION_SALDO', 'ANULACION_USO_SALDO'
    ));

create table ticket_anulacion_operacion (
    id uuid primary key,
    ticket_id uuid not null references documento(id),
    tienda_id uuid not null references tienda(id),
    terminal_id uuid not null references terminal(id),
    operador_usuario_id uuid not null references usuario(id),
    autorizador_usuario_id uuid not null references usuario(id),
    motivo text not null,
    solicitud_hash varchar(64) not null,
    estado varchar(24) not null check (estado in (
        'PREPARED', 'COMPENSATING', 'READY', 'REVIEW_REQUIRED',
        'FAILED', 'COMPLETED'
    )),
    compensaciones_manuales jsonb not null default '{}'::jsonb,
    operaciones_tarjeta jsonb not null default '{}'::jsonb,
    mensaje_error text,
    creado_en timestamptz not null,
    actualizado_en timestamptz not null,
    completado_en timestamptz,
    version bigint not null default 0,
    constraint fk_ticket_anulacion_terminal_tienda
        foreign key (terminal_id, tienda_id) references terminal(id, tienda_id)
);

create unique index ux_ticket_anulacion_operacion_activa
    on ticket_anulacion_operacion(ticket_id)
    where estado in ('PREPARED', 'COMPENSATING', 'READY', 'REVIEW_REQUIRED');

create index ix_ticket_anulacion_operacion_tienda
    on ticket_anulacion_operacion(tienda_id, creado_en desc);
