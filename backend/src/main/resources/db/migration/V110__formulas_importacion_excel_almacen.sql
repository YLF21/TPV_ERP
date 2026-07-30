alter table entrada_almacen
    add column importacion_excel jsonb;

alter table salida_almacen
    add column importacion_excel jsonb;

alter table entrada_almacen
    add constraint ck_entrada_almacen_importacion_excel
    check (importacion_excel is null or jsonb_typeof(importacion_excel) = 'object');

alter table salida_almacen
    add constraint ck_salida_almacen_importacion_excel
    check (importacion_excel is null or jsonb_typeof(importacion_excel) = 'object');
