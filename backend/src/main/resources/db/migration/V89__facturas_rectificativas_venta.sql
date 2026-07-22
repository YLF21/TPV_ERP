create table factura_rectificacion_venta (
    documento_id uuid primary key references documento(id) on delete cascade,
    origen_documento_id uuid not null references documento(id),
    tipo_fiscal varchar(2) not null,
    metodo char(1) not null,
    motivo varchar(40) not null,
    detalle text not null,
    afecta_stock boolean not null,
    creado_en timestamptz not null,
    version bigint not null default 0,
    check (documento_id <> origen_documento_id),
    check (tipo_fiscal in ('R1', 'R4')),
    check (metodo = 'I'),
    check (motivo in (
        'GOODS_RETURN',
        'POST_SALE_DISCOUNT',
        'POST_SALE_PRICE_CHANGE',
        'OPERATION_CANCELLATION',
        'LEGAL_OR_TAX_ERROR',
        'OTHER'
    )),
    check (char_length(trim(detalle)) between 10 and 500)
);

create index ix_factura_rectificacion_venta_origen
    on factura_rectificacion_venta(origen_documento_id, creado_en desc);
