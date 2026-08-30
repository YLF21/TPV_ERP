alter table producto
    add column if not exists requires_serial_number boolean not null default false;

do $$
begin
    alter table producto
        add constraint ck_producto_requires_serial_number_unit
            check (requires_serial_number = false or product_type = 'UNIT');
exception
    when duplicate_object then null;
end $$;
