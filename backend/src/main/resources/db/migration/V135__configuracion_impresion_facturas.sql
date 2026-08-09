create table configuracion_impresion_factura (
    empresa_id uuid primary key references empresa(id) on delete cascade,
    observaciones varchar(2000),
    version bigint not null default 0
);

create table cuenta_bancaria_factura (
    id uuid primary key,
    empresa_id uuid not null references empresa(id) on delete cascade,
    entidad varchar(120) not null,
    iban varchar(34) not null,
    activa boolean not null default true,
    orden integer not null,
    version bigint not null default 0,
    constraint uk_cuenta_bancaria_factura_empresa_iban unique (empresa_id, iban),
    constraint ck_cuenta_bancaria_factura_orden check (orden >= 0)
);

create index idx_cuenta_bancaria_factura_empresa_orden
    on cuenta_bancaria_factura (empresa_id, activa, orden, id);

alter table documento add column impresion_factura_snapshot jsonb;

alter table licencia add column perfil_comercial varchar(16) not null default 'MAYORISTA';
