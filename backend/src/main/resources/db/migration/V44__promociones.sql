alter table documento_linea
    add column tipo_linea varchar(24) not null default 'PRODUCT',
    add column promocion_id uuid,
    add column promocion_version_id uuid,
    add column cupon_promocional_id uuid,
    add column metadata_promocion jsonb;

alter table documento_linea
    alter column producto_id drop not null;

alter table documento_linea
    add constraint ck_documento_linea_tipo_linea
        check (tipo_linea in ('PRODUCT', 'PROMOTION', 'PROMOTIONAL_COUPON')),
    add constraint ck_documento_linea_product_integrity
        check (
            tipo_linea <> 'PRODUCT'
            or (
                producto_id is not null
                and promocion_id is null
                and promocion_version_id is null
                and cupon_promocional_id is null
            )
        ),
    add constraint ck_documento_linea_promotion_integrity
        check (
            tipo_linea <> 'PROMOTION'
            or (
                producto_id is null
                and promocion_id is not null
                and cupon_promocional_id is null
                and total <= 0
            )
        ),
    add constraint ck_documento_linea_coupon_integrity
        check (
            tipo_linea <> 'PROMOTIONAL_COUPON'
            or (
                producto_id is null
                and cupon_promocional_id is not null
                and total <= 0
            )
        ),
    add constraint ck_documento_linea_metadata_promocion
        check (metadata_promocion is null or jsonb_typeof(metadata_promocion) = 'object');

create table promocion (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    version_origen_id uuid references promocion(id),
    nombre varchar(160) not null,
    descripcion text,
    tipo varchar(40) not null,
    estado varchar(16) not null,
    segmento_cliente varchar(32) not null,
    member_category_id uuid references member_category(id),
    ambito varchar(24) not null,
    fecha_inicio date not null,
    fecha_fin date,
    minimo_importe numeric(19,2),
    minimo_cantidad numeric(19,3),
    compra_cantidad numeric(19,3),
    paga_cantidad numeric(19,3),
    descuento_importe numeric(19,2),
    descuento_porcentaje numeric(5,2),
    descuento_maximo numeric(19,2),
    precio_lote numeric(19,2),
    genera_cupon boolean not null default false,
    cupon_importe numeric(19,2),
    cupon_porcentaje numeric(5,2),
    cupon_descuento_maximo numeric(19,2),
    cupon_minimo_importe numeric(19,2),
    cupon_valido_desde_modo varchar(24),
    cupon_valido_desde_fecha date,
    cupon_valido_desde_dias integer,
    cupon_valido_hasta_fecha date,
    cupon_valido_dias integer,
    usada boolean not null default false,
    creado_en timestamptz not null default now(),
    actualizado_en timestamptz not null default now(),
    version bigint not null default 0,
    constraint ck_promocion_nombre check (char_length(trim(nombre)) > 0),
    constraint ck_promocion_tipo check (tipo in (
        'PURCHASE_THRESHOLD_COUPON',
        'PURCHASE_THRESHOLD_DISCOUNT',
        'BUY_X_PAY_Y',
        'SECOND_UNIT_PERCENT',
        'FIXED_PACK_PRICE',
        'QUANTITY_DISCOUNT')),
    constraint ck_promocion_estado check (estado in ('DRAFT', 'ACTIVE', 'INACTIVE')),
    constraint ck_promocion_segmento_cliente check (segmento_cliente in (
        'ALL',
        'IDENTIFIED_CUSTOMERS',
        'MEMBERS_ONLY',
        'MEMBER_CATEGORY')),
    constraint ck_promocion_member_category_segmento check (
        (segmento_cliente = 'MEMBER_CATEGORY' and member_category_id is not null)
        or (segmento_cliente <> 'MEMBER_CATEGORY' and member_category_id is null)
    ),
    constraint ck_promocion_ambito check (ambito in ('SALE', 'PRODUCT_LIST', 'FAMILY', 'SUBFAMILY')),
    constraint ck_promocion_fechas check (fecha_fin is null or fecha_fin >= fecha_inicio),
    constraint ck_promocion_minimo_importe check (minimo_importe is null or minimo_importe >= 0),
    constraint ck_promocion_minimo_cantidad check (minimo_cantidad is null or minimo_cantidad > 0),
    constraint ck_promocion_compra_cantidad check (compra_cantidad is null or compra_cantidad > 0),
    constraint ck_promocion_paga_cantidad check (paga_cantidad is null or paga_cantidad >= 0),
    constraint ck_promocion_compra_paga_cantidad check (
        compra_cantidad is null or paga_cantidad is null or paga_cantidad < compra_cantidad
    ),
    constraint ck_promocion_descuento_importe check (descuento_importe is null or descuento_importe > 0),
    constraint ck_promocion_descuento_porcentaje check (
        descuento_porcentaje is null or descuento_porcentaje > 0 and descuento_porcentaje <= 100
    ),
    constraint ck_promocion_descuento_maximo check (descuento_maximo is null or descuento_maximo > 0),
    constraint ck_promocion_precio_lote check (precio_lote is null or precio_lote > 0),
    constraint ck_promocion_cupon_importe check (cupon_importe is null or cupon_importe > 0),
    constraint ck_promocion_cupon_porcentaje check (
        cupon_porcentaje is null or cupon_porcentaje > 0 and cupon_porcentaje <= 100
    ),
    constraint ck_promocion_cupon_descuento_maximo check (
        cupon_descuento_maximo is null or cupon_descuento_maximo > 0
    ),
    constraint ck_promocion_cupon_minimo_importe check (
        cupon_minimo_importe is null or cupon_minimo_importe >= 0
    ),
    constraint ck_promocion_cupon_valido_desde_modo check (
        cupon_valido_desde_modo is null or cupon_valido_desde_modo in (
            'IMMEDIATE',
            'NEXT_DAY',
            'AFTER_DAYS',
            'FIXED_DATE')
    ),
    constraint ck_promocion_cupon_valido_desde_dias check (
        cupon_valido_desde_dias is null or cupon_valido_desde_dias >= 0
    ),
    constraint ck_promocion_cupon_valido_dias check (cupon_valido_dias is null or cupon_valido_dias > 0),
    constraint ck_promocion_cupon_fechas check (
        cupon_valido_hasta_fecha is null
        or cupon_valido_desde_fecha is null
        or cupon_valido_hasta_fecha >= cupon_valido_desde_fecha
    )
);

create table promocion_objetivo (
    id uuid primary key,
    promocion_id uuid not null references promocion(id) on delete cascade,
    tipo varchar(16) not null,
    objetivo_id uuid not null,
    version bigint not null default 0,
    constraint ck_promocion_objetivo_tipo check (tipo in ('PRODUCT', 'FAMILY', 'SUBFAMILY')),
    constraint ux_promocion_objetivo unique (promocion_id, tipo, objetivo_id)
);

create table cupon_promocional (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    tienda_generado_id uuid not null references tienda(id),
    tienda_canjeado_id uuid references tienda(id),
    promocion_id uuid not null references promocion(id),
    documento_generado_id uuid not null references documento(id),
    documento_canjeado_id uuid references documento(id),
    cliente_id uuid references cliente(id),
    member_id uuid references miembro(id),
    codigo_hash varchar(128) not null,
    codigo_ultimos4 varchar(4) not null,
    estado varchar(16) not null,
    beneficio_tipo varchar(16) not null,
    importe numeric(19,2),
    porcentaje numeric(5,2),
    descuento_maximo numeric(19,2),
    minimo_importe numeric(19,2),
    valido_desde date not null,
    valido_hasta date not null,
    creado_en timestamptz not null default now(),
    usado_en timestamptz,
    cancelado_en timestamptz,
    cancelado_por uuid references usuario(id),
    motivo_cancelacion text,
    reactivado_en timestamptz,
    reactivado_por uuid references usuario(id),
    motivo_reactivacion text,
    version bigint not null default 0,
    constraint ux_cupon_promocional_codigo unique (empresa_id, codigo_hash),
    constraint ck_cupon_promocional_codigo_hash check (char_length(trim(codigo_hash)) > 0),
    constraint ck_cupon_promocional_codigo_ultimos4 check (char_length(trim(codigo_ultimos4)) = 4),
    constraint ck_cupon_promocional_estado check (estado in ('ACTIVE', 'USED', 'EXPIRED', 'CANCELLED')),
    constraint ck_cupon_promocional_beneficio_tipo check (beneficio_tipo in ('AMOUNT', 'PERCENT')),
    constraint ck_cupon_promocional_importe check (importe is null or importe > 0),
    constraint ck_cupon_promocional_porcentaje check (porcentaje is null or porcentaje > 0 and porcentaje <= 100),
    constraint ck_cupon_promocional_descuento_maximo check (descuento_maximo is null or descuento_maximo > 0),
    constraint ck_cupon_promocional_minimo_importe check (minimo_importe is null or minimo_importe >= 0),
    constraint ck_cupon_promocional_beneficio_valor check (
        (beneficio_tipo = 'AMOUNT' and importe is not null and porcentaje is null)
        or (beneficio_tipo = 'PERCENT' and porcentaje is not null and importe is null)
    ),
    constraint ck_cupon_promocional_validez check (valido_hasta >= valido_desde),
    constraint ck_cupon_promocional_usado check (
        (estado = 'USED' and usado_en is not null and documento_canjeado_id is not null)
        or estado <> 'USED'
    ),
    constraint ck_cupon_promocional_cancelado check (
        (
            estado = 'CANCELLED'
            and cancelado_en is not null
            and cancelado_por is not null
            and char_length(trim(motivo_cancelacion)) > 0
        )
        or estado <> 'CANCELLED'
    ),
    constraint ck_cupon_promocional_reactivado check (
        (reactivado_en is null and reactivado_por is null and motivo_reactivacion is null)
        or (
            reactivado_en is not null
            and reactivado_por is not null
            and char_length(trim(motivo_reactivacion)) > 0
        )
    )
);

create table cupon_promocional_intento (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    tienda_id uuid not null references tienda(id),
    usuario_id uuid references usuario(id),
    terminal_id uuid references terminal(id),
    documento_id uuid references documento(id),
    codigo_hash varchar(128),
    codigo_ultimos4 varchar(4),
    motivo varchar(48) not null,
    creado_en timestamptz not null default now(),
    version bigint not null default 0,
    constraint ck_cupon_promocional_intento_codigo_hash check (
        codigo_hash is null or char_length(trim(codigo_hash)) > 0
    ),
    constraint ck_cupon_promocional_intento_codigo_ultimos4 check (
        codigo_ultimos4 is null or char_length(trim(codigo_ultimos4)) = 4
    ),
    constraint ck_cupon_promocional_intento_motivo check (motivo in (
        'NOT_FOUND',
        'EXPIRED',
        'CANCELLED',
        'USED',
        'CUSTOMER_MISMATCH',
        'DOCUMENT_NOT_ELIGIBLE',
        'MINIMUM_NOT_REACHED'))
);

alter table documento_linea
    add constraint fk_documento_linea_promocion
        foreign key (promocion_id) references promocion(id),
    add constraint fk_documento_linea_promocion_version
        foreign key (promocion_version_id) references promocion(id),
    add constraint fk_documento_linea_cupon_promocional
        foreign key (cupon_promocional_id) references cupon_promocional(id);

create index ix_promocion_empresa_estado_fecha
    on promocion(empresa_id, estado, fecha_inicio, fecha_fin);
create index ix_promocion_objetivo_objetivo
    on promocion_objetivo(tipo, objetivo_id);
create index ix_cupon_empresa_estado_fecha
    on cupon_promocional(empresa_id, estado, valido_desde, valido_hasta);
create index ix_cupon_documento_generado
    on cupon_promocional(documento_generado_id);
create index ix_cupon_documento_canjeado
    on cupon_promocional(documento_canjeado_id);
create index ix_documento_linea_promocion
    on documento_linea(promocion_id);
create index ix_documento_linea_promocion_version
    on documento_linea(promocion_version_id);
create index ix_documento_linea_cupon_promocional
    on documento_linea(cupon_promocional_id);
