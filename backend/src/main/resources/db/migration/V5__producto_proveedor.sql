drop index if exists ux_producto_proveedor_referencia;

alter table producto_proveedor
    alter column referencia_proveedor drop not null;

alter table producto_proveedor
    drop constraint if exists producto_proveedor_referencia_proveedor_check;

alter table producto_proveedor
    drop constraint if exists producto_proveedor_referencia_proveedor_check1;

alter table producto_proveedor
    add constraint ck_producto_proveedor_referencia
    check (
        referencia_proveedor is null
        or (
            referencia_proveedor = upper(trim(referencia_proveedor))
            and char_length(referencia_proveedor) > 0
        )
    );
