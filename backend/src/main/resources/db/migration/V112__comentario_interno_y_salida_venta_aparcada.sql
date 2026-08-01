alter table documento
    add column comentario_interno varchar(500);

alter table venta_aparcada
    add column metodo_impresion varchar(24) not null default 'DEFAULT';

alter table venta_aparcada
    add constraint ck_venta_aparcada_metodo_impresion
        check (metodo_impresion in (
            'DEFAULT',
            'TICKET_PRINTER',
            'A4_PRINTER',
            'PDF',
            'NONE'
        ));

alter table venta_aparcada_recuperacion
    add column metodo_impresion varchar(24) not null default 'DEFAULT';

alter table venta_aparcada_recuperacion
    add constraint ck_venta_aparcada_recuperacion_metodo_impresion
        check (metodo_impresion in (
            'DEFAULT',
            'TICKET_PRINTER',
            'A4_PRINTER',
            'PDF',
            'NONE'
        ));
