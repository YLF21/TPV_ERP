alter table producto
    add column if not exists package_quantity numeric(19,3) default 1;

do $$
begin
    alter table producto
        add constraint ck_producto_package_quantity
            check (package_quantity is null or package_quantity >= 0);
exception
    when duplicate_object then null;
end $$;
