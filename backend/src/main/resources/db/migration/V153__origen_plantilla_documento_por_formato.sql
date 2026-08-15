create table configuracion_origen_plantilla_documento (
    tienda_id uuid not null references tienda(id) on delete cascade,
    tipo varchar(32) not null,
    formato varchar(16) not null,
    origen varchar(16) not null default 'INTEGRATED',
    version bigint not null default 0,
    primary key (tienda_id, tipo, formato),
    constraint ck_configuracion_origen_plantilla_tipo_formato check (
        (tipo = 'FACTURA_VENTA' and formato in ('A4', 'TICKET_80'))
        or (tipo = 'ALBARAN_VENTA' and formato = 'A4')
        or (tipo = 'TICKET' and formato = 'TICKET_80')
        or (tipo = 'VALE' and formato = 'TICKET_80')
    ),
    constraint ck_configuracion_origen_plantilla_origen
        check (origen in ('INTEGRATED', 'IMPORTED'))
);

insert into configuracion_origen_plantilla_documento (
    tienda_id, tipo, formato, origen
)
select tienda.id,
       'TICKET',
       'TICKET_80',
       coalesce(configuracion.origen_plantilla_ticket, 'INTEGRATED')
from tienda
left join configuracion_documento_impreso_tienda configuracion
    on configuracion.tienda_id = tienda.id;

insert into configuracion_origen_plantilla_documento (
    tienda_id, tipo, formato, origen
)
select tienda.id,
       'FACTURA_VENTA',
       formato.valor,
       case when exists (
           select 1
           from plantilla_documento plantilla
           where plantilla.tipo = 'FACTURA_VENTA'
             and plantilla.formato = formato.valor
             and plantilla.estado = 'ACTIVE'
             and (
                 (plantilla.ambito = 'STORE'
                     and plantilla.tienda_id = tienda.id)
                 or (plantilla.ambito = 'COMPANY'
                     and plantilla.empresa_id = tienda.empresa_id
                     and plantilla.tienda_id is null)
                 or (plantilla.ambito = 'SYSTEM'
                     and plantilla.empresa_id is null
                     and plantilla.tienda_id is null)
             )
       ) then 'IMPORTED' else 'INTEGRATED' end
from tienda
cross join (values ('A4'), ('TICKET_80')) formato(valor);
