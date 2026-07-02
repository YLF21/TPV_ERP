alter table producto
    add column product_type varchar(16) not null default 'UNIT',
    add column discount_type varchar(32) not null default 'NORMAL',
    add column comments text;

alter table documento_linea
    alter column cantidad type numeric(19, 3) using cantidad::numeric(19, 3);

alter table existencia
    alter column cantidad type numeric(19, 3) using cantidad::numeric(19, 3);

alter table movimiento_stock
    alter column cantidad type numeric(19, 3) using cantidad::numeric(19, 3);
