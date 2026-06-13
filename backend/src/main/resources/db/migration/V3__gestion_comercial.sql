alter table tienda
    add column impuestos_incluidos_defecto boolean not null default true;

alter table licencia
    add column regimen_impuesto varchar(8),
    add constraint ck_licencia_regimen_impuesto
        check (regimen_impuesto is null or regimen_impuesto in ('IVA', 'IGIC'));

create table impuesto_tienda (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    porcentaje numeric(5,2) not null,
    activo boolean not null default true,
    predeterminado boolean not null default false,
    version bigint not null default 0,
    unique (tienda_id, porcentaje),
    check (porcentaje between 0 and 100)
);

create unique index ux_impuesto_predeterminado_tienda
    on impuesto_tienda(tienda_id) where predeterminado;

create table almacen (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    nombre varchar(128) not null,
    predeterminado boolean not null default false,
    activo boolean not null default true,
    version bigint not null default 0,
    check (char_length(trim(nombre)) > 0)
);

create unique index ux_almacen_nombre_tienda_ci on almacen(tienda_id, upper(trim(nombre)));
create unique index ux_almacen_predeterminado_tienda on almacen(tienda_id) where predeterminado;

create table familia (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    nombre varchar(128) not null,
    predeterminada boolean not null default false,
    version bigint not null default 0,
    check (char_length(trim(nombre)) > 0)
);

create unique index ux_familia_nombre_tienda_ci on familia(tienda_id, upper(trim(nombre)));
create unique index ux_familia_predeterminada_tienda on familia(tienda_id) where predeterminada;

create table subfamilia (
    id uuid primary key,
    familia_id uuid not null references familia(id) on delete cascade,
    nombre varchar(128) not null,
    version bigint not null default 0,
    check (char_length(trim(nombre)) > 0)
);

create unique index ux_subfamilia_nombre_familia_ci on subfamilia(familia_id, upper(trim(nombre)));

create table producto (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    familia_id uuid not null references familia(id),
    subfamilia_id uuid references subfamilia(id),
    impuesto_id uuid not null references impuesto_tienda(id),
    nombre varchar(255) not null,
    descripcion text,
    precio_compra numeric(19,2) not null default 0,
    impuestos_incluidos boolean not null default true,
    oferta_activa boolean not null default false,
    oferta_desde date,
    oferta_hasta date,
    imagen_id varchar(255),
    imagen_tipo varchar(128),
    imagen_tamano bigint,
    imagen_hash varchar(128),
    version bigint not null default 0,
    check (char_length(trim(nombre)) > 0),
    check (precio_compra >= 0),
    check (imagen_tamano is null or imagen_tamano >= 0),
    check (oferta_hasta is null or oferta_desde is not null),
    check (oferta_hasta is null or oferta_hasta >= oferta_desde)
);

create table producto_identificador (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    producto_id uuid not null references producto(id) on delete cascade,
    tipo varchar(16) not null,
    valor varchar(128) not null,
    version bigint not null default 0,
    unique (producto_id, tipo),
    check (tipo in ('CODIGO', 'CODIGO_BARRAS')),
    check (valor = upper(trim(valor))),
    check (char_length(valor) > 0)
);

create unique index ux_producto_identificador_tienda_valor
    on producto_identificador(tienda_id, valor);

create table producto_precio (
    id uuid primary key,
    producto_id uuid not null references producto(id) on delete cascade,
    tarifa varchar(16) not null,
    importe numeric(19,2),
    version bigint not null default 0,
    unique (producto_id, tarifa),
    check (tarifa in ('VENTA', 'SOCIO', 'MAYORISTA', 'OFERTA')),
    check ((tarifa = 'VENTA' and importe is not null and importe >= 0)
        or (tarifa <> 'VENTA' and (importe is null or importe >= 0.01)))
);

create table existencia (
    id uuid primary key,
    producto_id uuid not null references producto(id),
    almacen_id uuid not null references almacen(id),
    cantidad integer not null default 0,
    version bigint not null default 0,
    unique (producto_id, almacen_id)
);

create table salida_almacen (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    almacen_id uuid not null references almacen(id),
    numero varchar(32),
    fecha date not null,
    estado varchar(16) not null default 'BORRADOR',
    destino varchar(255),
    concepto text,
    creada_por uuid not null references usuario(id),
    confirmada_por uuid references usuario(id),
    confirmada_en timestamptz,
    version bigint not null default 0,
    unique (tienda_id, numero),
    check (estado in ('BORRADOR', 'CONFIRMADA')),
    check ((estado = 'BORRADOR' and numero is null and confirmada_en is null)
        or (estado = 'CONFIRMADA' and numero is not null and confirmada_en is not null))
);

create table salida_almacen_linea (
    id uuid primary key,
    salida_id uuid not null references salida_almacen(id) on delete cascade,
    producto_id uuid not null references producto(id),
    cantidad integer not null,
    version bigint not null default 0,
    check (cantidad <> 0)
);

create table cliente (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    nombre_fiscal varchar(255) not null,
    tipo_documento varchar(16) not null,
    numero_documento varchar(64) not null,
    direccion varchar(255),
    codigo_postal varchar(16),
    poblacion varchar(128),
    provincia varchar(128),
    pais varchar(2) default 'ES',
    telefono varchar(64),
    email varchar(320),
    observaciones text,
    tarifa varchar(16) not null default 'VENTA',
    descuento numeric(5,2) not null default 0,
    saldo_socio numeric(19,2) not null default 0,
    activo boolean not null default true,
    version bigint not null default 0,
    check (char_length(trim(nombre_fiscal)) > 0),
    check (tipo_documento in ('NIF', 'CIF', 'NIE', 'PASAPORTE', 'OTRO')),
    check (numero_documento = upper(trim(numero_documento))),
    check (char_length(numero_documento) > 0),
    check (tarifa in ('VENTA', 'SOCIO')),
    check (descuento between 0 and 100),
    check (saldo_socio >= 0)
);

create unique index ux_cliente_documento_empresa
    on cliente(empresa_id, tipo_documento, numero_documento);

create table movimiento_saldo_socio (
    id uuid primary key,
    cliente_id uuid not null references cliente(id),
    usuario_id uuid not null references usuario(id),
    documento_id uuid,
    importe numeric(19,2) not null,
    motivo text not null,
    creado_en timestamptz not null,
    compensacion_de_id uuid references movimiento_saldo_socio(id),
    version bigint not null default 0,
    check (importe <> 0),
    check (char_length(trim(motivo)) > 0)
);

create table proveedor (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    razon_social varchar(255) not null,
    nombre_comercial varchar(255),
    tipo_documento varchar(16) not null,
    numero_documento varchar(64) not null,
    direccion varchar(255),
    codigo_postal varchar(16),
    poblacion varchar(128),
    provincia varchar(128),
    pais varchar(2) default 'ES',
    telefono varchar(64),
    email varchar(320),
    observaciones text,
    activo boolean not null default true,
    version bigint not null default 0,
    check (char_length(trim(razon_social)) > 0),
    check (tipo_documento in ('NIF', 'CIF', 'NIE', 'PASAPORTE', 'OTRO')),
    check (numero_documento = upper(trim(numero_documento))),
    check (char_length(numero_documento) > 0)
);

create unique index ux_proveedor_documento_empresa
    on proveedor(empresa_id, tipo_documento, numero_documento);

create table comercial (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    nombre varchar(255) not null,
    telefono varchar(64),
    email varchar(320),
    otro_contacto varchar(255),
    version bigint not null default 0,
    check (char_length(trim(nombre)) > 0)
);

create table proveedor_comercial (
    proveedor_id uuid not null references proveedor(id) on delete cascade,
    comercial_id uuid not null references comercial(id) on delete cascade,
    principal boolean not null default false,
    primary key (proveedor_id, comercial_id)
);

create unique index ux_proveedor_comercial_principal
    on proveedor_comercial(proveedor_id) where principal;

create table producto_proveedor (
    id uuid primary key,
    producto_id uuid not null references producto(id),
    proveedor_id uuid not null references proveedor(id),
    referencia_proveedor varchar(128) not null,
    ultima_fecha_entrada date,
    version bigint not null default 0,
    unique (producto_id, proveedor_id),
    check (referencia_proveedor = upper(trim(referencia_proveedor))),
    check (char_length(referencia_proveedor) > 0)
);

create unique index ux_producto_proveedor_referencia
    on producto_proveedor(proveedor_id, referencia_proveedor);

create table metodo_pago (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    nombre varchar(64) not null,
    protegido boolean not null default false,
    activo boolean not null default true,
    version bigint not null default 0,
    check (nombre = upper(trim(nombre))),
    check (char_length(nombre) > 0)
);

create unique index ux_metodo_pago_nombre_empresa on metodo_pago(empresa_id, nombre);

create table contador_documento (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    tipo varchar(24) not null,
    periodo varchar(8) not null,
    ultimo_numero integer not null default 0,
    version bigint not null default 0,
    unique (tienda_id, tipo, periodo),
    check (tipo in ('SAL', 'AV', 'AC', 'T', 'FV', 'FC', 'FRV', 'FRC')),
    check (ultimo_numero >= 0)
);

create table documento (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    almacen_id uuid not null references almacen(id),
    tipo varchar(16) not null,
    estado varchar(16) not null,
    numero varchar(32),
    fecha date not null,
    creado_en timestamptz not null,
    confirmado_en timestamptz,
    anulado_en timestamptz,
    creado_por uuid not null references usuario(id),
    confirmado_por uuid references usuario(id),
    anulado_por uuid references usuario(id),
    cliente_id uuid references cliente(id),
    proveedor_id uuid references proveedor(id),
    numero_externo varchar(128),
    motivo_anulacion text,
    descuento_global numeric(5,2) not null default 0,
    base_total numeric(19,2) not null default 0,
    impuesto_total numeric(19,2) not null default 0,
    total numeric(19,2) not null default 0,
    moneda char(3) not null default 'EUR',
    fecha_vencimiento date,
    origen_stock boolean not null default false,
    version bigint not null default 0,
    unique (tienda_id, tipo, numero),
    check (tipo in ('ALBARAN_VENTA', 'ALBARAN_COMPRA', 'TICKET',
        'FACTURA_VENTA', 'FACTURA_COMPRA', 'RECTIFICATIVA_VENTA', 'RECTIFICATIVA_COMPRA')),
    check (estado in ('BORRADOR', 'CONFIRMADO', 'ANULADO', 'PENDIENTE', 'PAGADO')),
    check (descuento_global between 0 and 100),
    check (moneda = 'EUR'),
    check (numero is null or char_length(trim(numero)) > 0)
);

create unique index ux_documento_numero_externo_proveedor
    on documento(proveedor_id, tipo, upper(trim(numero_externo)))
    where proveedor_id is not null and numero_externo is not null;

alter table movimiento_saldo_socio
    add constraint fk_movimiento_saldo_documento foreign key (documento_id) references documento(id);

create table documento_linea (
    id uuid primary key,
    documento_id uuid not null references documento(id) on delete cascade,
    producto_id uuid not null references producto(id),
    posicion integer not null,
    cantidad integer not null,
    codigo varchar(128) not null,
    nombre varchar(255) not null,
    tarifa varchar(16),
    precio_unitario numeric(19,2) not null,
    descuento numeric(5,2) not null default 0,
    impuestos_incluidos boolean not null,
    regimen_impuesto varchar(8) not null,
    porcentaje_impuesto numeric(5,2) not null,
    base numeric(19,2) not null,
    impuesto numeric(19,2) not null,
    total numeric(19,2) not null,
    version bigint not null default 0,
    unique (documento_id, posicion),
    check (cantidad <> 0),
    check (descuento between 0 and 100),
    check (regimen_impuesto in ('IVA', 'IGIC')),
    check (porcentaje_impuesto between 0 and 100)
);

create table documento_pago (
    id uuid primary key,
    documento_id uuid not null references documento(id) on delete cascade,
    metodo_pago_id uuid not null references metodo_pago(id),
    posicion integer not null,
    importe numeric(19,2) not null,
    principal boolean not null default false,
    entregado numeric(19,2),
    cambio numeric(19,2),
    creado_en timestamptz not null,
    version bigint not null default 0,
    unique (documento_id, posicion),
    check (importe >= 0),
    check (entregado is null or entregado >= importe),
    check (cambio is null or cambio >= 0)
);

create unique index ux_documento_pago_principal
    on documento_pago(documento_id) where principal;

create table documento_relacion (
    documento_id uuid not null references documento(id) on delete cascade,
    origen_id uuid not null references documento(id),
    tipo varchar(16) not null,
    primary key (documento_id, origen_id),
    check (documento_id <> origen_id),
    check (tipo in ('FACTURA_DE', 'RECTIFICA'))
);

create table movimiento_stock (
    id uuid primary key,
    producto_id uuid not null references producto(id),
    almacen_id uuid not null references almacen(id),
    usuario_id uuid not null references usuario(id),
    documento_id uuid references documento(id),
    salida_almacen_id uuid references salida_almacen(id),
    tipo varchar(32) not null,
    cantidad integer not null,
    motivo text,
    creado_en timestamptz not null,
    compensacion_de_id uuid references movimiento_stock(id),
    transferencia_id uuid,
    version bigint not null default 0,
    check (cantidad <> 0),
    check (tipo in ('AJUSTE', 'TRANSFERENCIA_SALIDA', 'TRANSFERENCIA_ENTRADA',
        'SALIDA_ALMACEN', 'ALBARAN_VENTA', 'ALBARAN_COMPRA', 'TICKET',
        'FACTURA_VENTA', 'FACTURA_COMPRA', 'ANULACION')),
    check (tipo <> 'AJUSTE' or char_length(trim(motivo)) > 0)
);

create index ix_impuesto_tienda on impuesto_tienda(tienda_id);
create index ix_almacen_tienda on almacen(tienda_id);
create index ix_producto_tienda on producto(tienda_id);
create index ix_existencia_almacen on existencia(almacen_id);
create index ix_movimiento_stock_producto_fecha on movimiento_stock(producto_id, creado_en desc);
create index ix_cliente_empresa on cliente(empresa_id);
create index ix_proveedor_empresa on proveedor(empresa_id);
create index ix_documento_tienda_fecha on documento(tienda_id, fecha desc);
create index ix_documento_cliente on documento(cliente_id);
create index ix_documento_proveedor on documento(proveedor_id);
