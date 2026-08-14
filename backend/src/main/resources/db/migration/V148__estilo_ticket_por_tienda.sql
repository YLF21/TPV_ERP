alter table configuracion_documento_impreso_tienda
    add column estilo_ticket varchar(16) not null default 'PRINCIPAL';

alter table configuracion_documento_impreso_tienda
    add constraint ck_configuracion_documento_estilo_ticket
        check (estilo_ticket in ('PRINCIPAL', 'COMPACTA', 'MINIMALISTA'));
