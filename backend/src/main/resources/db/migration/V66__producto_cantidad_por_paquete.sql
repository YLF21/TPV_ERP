alter table producto
    add column package_quantity numeric(19,3) default 1;

alter table producto
    add constraint ck_producto_package_quantity
        check (package_quantity is null or package_quantity >= 0);
