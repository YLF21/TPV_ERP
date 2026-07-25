create table if not exists documento_linea_numero_serie (
    documento_linea_id uuid not null
        references documento_linea(id) on delete cascade,
    posicion integer not null,
    numero_serie varchar(128) not null,
    primary key (documento_linea_id, posicion),
    constraint chk_documento_linea_numero_serie_posicion
        check (posicion >= 0),
    constraint chk_documento_linea_numero_serie_valor
        check (btrim(numero_serie) <> '')
);

create unique index if not exists ux_documento_linea_numero_serie_normalizado
    on documento_linea_numero_serie(documento_linea_id, upper(btrim(numero_serie)));

create index if not exists idx_documento_linea_numero_serie_busqueda
    on documento_linea_numero_serie(upper(btrim(numero_serie)));
