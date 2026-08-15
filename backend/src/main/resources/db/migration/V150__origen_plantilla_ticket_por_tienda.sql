alter table configuracion_documento_impreso_tienda
    add column origen_plantilla_ticket varchar(16);

update configuracion_documento_impreso_tienda configuracion
set origen_plantilla_ticket = case
    when exists (
        select 1
        from plantilla_documento plantilla
        where plantilla.tipo = 'TICKET'
          and plantilla.formato = 'TICKET_80'
          and plantilla.estado = 'ACTIVE'
          and (
              (plantilla.ambito = 'STORE'
                  and plantilla.tienda_id = configuracion.tienda_id)
              or (plantilla.ambito = 'COMPANY'
                  and plantilla.empresa_id = (
                      select tienda.empresa_id
                      from tienda
                      where tienda.id = configuracion.tienda_id
                  ))
              or plantilla.ambito = 'SYSTEM'
          )
    ) then 'IMPORTED'
    else 'INTEGRATED'
end;

alter table configuracion_documento_impreso_tienda
    alter column origen_plantilla_ticket set default 'INTEGRATED',
    alter column origen_plantilla_ticket set not null;

alter table configuracion_documento_impreso_tienda
    add constraint ck_configuracion_documento_origen_plantilla_ticket
        check (origen_plantilla_ticket in ('INTEGRATED', 'IMPORTED'));
