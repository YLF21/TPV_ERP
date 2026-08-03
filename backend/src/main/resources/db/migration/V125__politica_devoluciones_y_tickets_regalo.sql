create table configuracion_devolucion_tienda (
    tienda_id uuid primary key references tienda(id) on delete cascade,
    politica varchar(32) not null default 'REFUND_ALLOWED',
    version bigint not null default 0,
    constraint configuracion_devolucion_tienda_politica_ck
        check (politica in ('REFUND_ALLOWED', 'EXCHANGE_OR_VOUCHER_ONLY'))
);

insert into configuracion_devolucion_tienda (tienda_id, politica)
select id, 'REFUND_ALLOWED'
from tienda
on conflict (tienda_id) do nothing;

create table secuencia_ticket_regalo (
    tienda_id uuid not null references tienda(id) on delete cascade,
    fecha date not null,
    ultimo_numero integer not null,
    primary key (tienda_id, fecha),
    constraint secuencia_ticket_regalo_numero_ck
        check (ultimo_numero between 1 and 99999)
);

create table ticket_regalo (
    id uuid primary key,
    tienda_id uuid not null references tienda(id) on delete restrict,
    documento_origen_id uuid not null references documento(id) on delete restrict,
    request_id uuid not null,
    codigo varchar(32) not null,
    creado_por uuid not null references usuario(id) on delete restrict,
    terminal_id uuid references terminal(id) on delete restrict,
    creado_en timestamptz not null,
    version bigint not null default 0,
    constraint ticket_regalo_codigo_uk unique (tienda_id, codigo),
    constraint ticket_regalo_request_uk unique (tienda_id, request_id)
);

create index ticket_regalo_documento_origen_ix
    on ticket_regalo(documento_origen_id, creado_en desc);

create table ticket_regalo_linea (
    id uuid primary key,
    ticket_regalo_id uuid not null references ticket_regalo(id) on delete cascade,
    documento_linea_origen_id uuid not null references documento_linea(id) on delete restrict,
    cantidad numeric(19,3) not null,
    posicion integer not null,
    constraint ticket_regalo_linea_cantidad_ck check (cantidad > 0),
    constraint ticket_regalo_linea_posicion_ck check (posicion > 0),
    constraint ticket_regalo_linea_origen_uk
        unique (ticket_regalo_id, documento_linea_origen_id),
    constraint ticket_regalo_linea_posicion_uk
        unique (ticket_regalo_id, posicion)
);

create index ticket_regalo_linea_origen_ix
    on ticket_regalo_linea(documento_linea_origen_id);

create table ticket_regalo_linea_numero_serie (
    ticket_regalo_linea_id uuid not null references ticket_regalo_linea(id) on delete cascade,
    posicion integer not null,
    numero_serie varchar(128) not null,
    primary key (ticket_regalo_linea_id, posicion),
    constraint ticket_regalo_linea_numero_serie_uk
        unique (ticket_regalo_linea_id, numero_serie)
);

alter table documento_linea
    add column ticket_regalo_linea_id uuid references ticket_regalo_linea(id) on delete restrict;

create index documento_linea_ticket_regalo_linea_ix
    on documento_linea(ticket_regalo_linea_id)
    where ticket_regalo_linea_id is not null;
