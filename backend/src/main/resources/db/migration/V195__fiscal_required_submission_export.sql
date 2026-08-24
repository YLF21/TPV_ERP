alter table requerimiento_fiscal
    add column exportacion_id uuid references exportacion_fiscal(id);

create unique index ux_requerimiento_fiscal_exportacion
    on requerimiento_fiscal(exportacion_id)
    where exportacion_id is not null;
