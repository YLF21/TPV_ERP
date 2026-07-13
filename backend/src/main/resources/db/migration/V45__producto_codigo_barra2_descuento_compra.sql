alter table producto
    add column if not exists descuento_compra_porcentaje numeric(5,2);

alter table producto
    add constraint ck_producto_descuento_compra_porcentaje
    check (descuento_compra_porcentaje is null or descuento_compra_porcentaje between 0 and 100);

alter table producto_identificador
    drop constraint if exists producto_identificador_tipo_check;

alter table producto_identificador
    add constraint producto_identificador_tipo_check
    check (tipo in ('CODIGO', 'CODIGO_BARRAS', 'CODIGO_BARRAS_2'));
