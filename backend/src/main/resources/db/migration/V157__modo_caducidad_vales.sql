alter table configuracion_vale_tienda
    add column modo_caducidad varchar(16) not null default 'DAYS';

alter table configuracion_vale_tienda
    add constraint configuracion_vale_tienda_modo_ck
        check (modo_caducidad in ('DAYS', 'NEVER'));
