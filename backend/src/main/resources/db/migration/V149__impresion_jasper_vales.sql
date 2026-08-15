alter table configuracion_documento_impreso_tienda
    add column observaciones_vale varchar(2000);

alter table vale
    add column impresion_snapshot jsonb;

alter table vale
    add constraint ck_vale_impresion_snapshot
        check (impresion_snapshot is null or jsonb_typeof(impresion_snapshot) = 'object');
