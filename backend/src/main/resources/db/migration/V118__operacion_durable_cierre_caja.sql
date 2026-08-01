create table operacion_cierre_caja (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    terminal_id uuid not null,
    sesion_caja_id uuid not null unique,
    movimiento_retirada_id uuid unique references movimiento_caja(id),
    importe_retirada numeric(19,2) not null,
    comentario_retirada varchar(500),
    huella_retirada varchar(64) not null,
    estado varchar(32) not null,
    creado_en timestamptz not null,
    actualizado_en timestamptz not null,
    version bigint not null default 0,
    constraint operacion_cierre_sesion_fk
        foreign key (sesion_caja_id, terminal_id, tienda_id)
        references sesion_caja(id, terminal_id, tienda_id),
    constraint operacion_cierre_importe_ck check (importe_retirada >= 0),
    constraint operacion_cierre_huella_ck check (huella_retirada ~ '^[0-9a-f]{64}$'),
    constraint operacion_cierre_estado_ck check (
        estado in ('INICIADA', 'REQUIERE_ARQUEO', 'CERRADA')
    )
);

insert into operacion_cierre_caja (
    id,
    tienda_id,
    terminal_id,
    sesion_caja_id,
    movimiento_retirada_id,
    importe_retirada,
    comentario_retirada,
    huella_retirada,
    estado,
    creado_en,
    actualizado_en,
    version
)
select
    idempotencia.clave_idempotencia,
    idempotencia.tienda_id,
    idempotencia.terminal_id,
    idempotencia.sesion_caja_id,
    idempotencia.movimiento_caja_id,
    coalesce(movimiento.importe, 0),
    movimiento.comentario,
    idempotencia.huella_solicitud,
    case
        when sesion.estado = 'CERRADA' then 'CERRADA'
        when exists (
            select 1
              from intento_arqueo_caja intento
             where intento.sesion_caja_id = idempotencia.sesion_caja_id
        ) then 'REQUIERE_ARQUEO'
        else 'INICIADA'
    end,
    idempotencia.creado_en,
    idempotencia.creado_en,
    0
from idempotencia_retirada_cierre idempotencia
join sesion_caja sesion on sesion.id = idempotencia.sesion_caja_id
left join movimiento_caja movimiento on movimiento.id = idempotencia.movimiento_caja_id
on conflict (id) do nothing;

alter table intento_arqueo_caja
    add column operacion_cierre_id uuid references operacion_cierre_caja(id),
    add column clave_idempotencia uuid,
    add column huella_solicitud varchar(64);

update intento_arqueo_caja intento
set operacion_cierre_id = operacion.id
from operacion_cierre_caja operacion
where operacion.sesion_caja_id = intento.sesion_caja_id;

alter table intento_arqueo_caja
    add constraint intento_arqueo_idempotencia_completa_ck check (
        (clave_idempotencia is null and huella_solicitud is null)
        or
        (operacion_cierre_id is not null and clave_idempotencia is not null
            and huella_solicitud ~ '^[0-9a-f]{64}$')
    );

create unique index intento_arqueo_clave_idempotencia_uq
    on intento_arqueo_caja(operacion_cierre_id, clave_idempotencia)
    where clave_idempotencia is not null;

create index operacion_cierre_terminal_estado_idx
    on operacion_cierre_caja(terminal_id, estado);
