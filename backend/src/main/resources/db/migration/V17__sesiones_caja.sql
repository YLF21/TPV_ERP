create table configuracion_caja_tienda (
    tienda_id uuid primary key references tienda(id),
    tolerancia_descuadre numeric(19,2) not null default 0.00,
    requiere_desglose_entrada boolean not null default false,
    requiere_desglose_retirada boolean not null default false,
    requiere_desglose_cierre boolean not null default false,
    version bigint not null default 0,
    constraint caja_config_tolerancia_no_negativa check (tolerancia_descuadre >= 0)
);

create table sesion_caja (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    terminal_id uuid not null references terminal(id),
    usuario_apertura_id uuid not null references usuario(id),
    abierta_en timestamptz not null,
    fondo_inicial numeric(19,2) not null,
    usuario_cierre_id uuid references usuario(id),
    cerrada_en timestamptz,
    efectivo_teorico numeric(19,2),
    fondo_dejado numeric(19,2),
    descuadre numeric(19,2),
    estado varchar(16) not null,
    cierre_tardio boolean not null default false,
    version bigint not null default 0,
    constraint sesion_caja_estado_ck check (estado in ('ABIERTA', 'CERRADA')),
    constraint sesion_caja_fondo_inicial_ck check (fondo_inicial >= 0)
);

create unique index sesion_caja_terminal_abierta_uq
    on sesion_caja(terminal_id) where estado = 'ABIERTA';

create table movimiento_caja (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    terminal_id uuid not null references terminal(id),
    sesion_caja_id uuid references sesion_caja(id),
    tipo varchar(32) not null,
    importe numeric(19,2) not null,
    creado_en timestamptz not null,
    usuario_id uuid not null references usuario(id),
    usuario_autorizador_id uuid references usuario(id),
    comentario varchar(500),
    documento_id uuid references documento(id),
    documento_pago_id uuid references documento_pago(id),
    impreso_en timestamptz,
    version bigint not null default 0,
    constraint movimiento_caja_tipo_ck check (tipo in (
        'COBRO_EFECTIVO',
        'DEVOLUCION_EFECTIVO',
        'ENTRADA',
        'RETIRADA',
        'RETIRADA_CIERRE',
        'ENTRE_SESIONES')),
    constraint movimiento_caja_importe_positivo_ck check (importe > 0)
);

create unique index movimiento_caja_pago_uq
    on movimiento_caja(documento_pago_id) where documento_pago_id is not null;

create table movimiento_caja_denominacion (
    id uuid primary key,
    movimiento_caja_id uuid not null references movimiento_caja(id) on delete cascade,
    denominacion numeric(19,2) not null,
    cantidad integer not null,
    constraint movimiento_caja_denominacion_cantidad_ck check (cantidad > 0)
);

create table intento_arqueo_caja (
    id uuid primary key,
    sesion_caja_id uuid not null references sesion_caja(id),
    numero_intento integer not null,
    usuario_id uuid not null references usuario(id),
    creado_en timestamptz not null,
    fondo_declarado numeric(19,2) not null,
    efectivo_teorico numeric(19,2) not null,
    descuadre numeric(19,2) not null,
    cerro_sesion boolean not null,
    constraint intento_arqueo_numero_ck check (numero_intento in (1, 2))
);

insert into configuracion_caja_tienda (tienda_id)
select id from tienda
on conflict do nothing;

insert into permiso (id, codigo, translation_key, grupo)
values
    (gen_random_uuid(), 'GESTION_CUENTAS', 'cash.permissions.accounts.manage', 'CASH'),
    (gen_random_uuid(), 'CASH_READ', 'cash.permissions.read', 'CASH'),
    (gen_random_uuid(), 'CASH_OPERATE', 'cash.permissions.operate', 'CASH'),
    (gen_random_uuid(), 'CASH_CONFIGURE', 'cash.permissions.configure', 'CASH')
on conflict (codigo) do nothing;
