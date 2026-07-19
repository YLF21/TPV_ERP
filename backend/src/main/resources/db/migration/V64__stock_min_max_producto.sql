alter table producto
    add column stock_min numeric(19,3),
    add column stock_max numeric(19,3);

alter table producto
    add constraint ck_producto_stock_min_max
        check (stock_min is null or stock_max is null or stock_max >= stock_min);
