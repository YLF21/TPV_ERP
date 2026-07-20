alter table documento
    add column terminal_origen_id uuid;

alter table documento
    add constraint documento_terminal_origen_tienda_fk
        foreign key (terminal_origen_id, tienda_id) references terminal(id, tienda_id);

create index ix_documento_tienda_terminal_fecha
    on documento(tienda_id, terminal_origen_id, fecha desc)
    where terminal_origen_id is not null;

create function impedir_cambio_terminal_origen_documento()
returns trigger
language plpgsql
as $$
begin
    if old.terminal_origen_id is not null
       and new.terminal_origen_id is distinct from old.terminal_origen_id then
        raise exception 'documento.terminal_origen_id es inmutable';
    end if;
    return new;
end;
$$;

create trigger documento_terminal_origen_inmutable
before update of terminal_origen_id on documento
for each row execute function impedir_cambio_terminal_origen_documento();

with pares_candidatos as (
    select distinct checkout.documento_id, checkout.terminal_id
    from pos_cash_checkout checkout
    where checkout.documento_id is not null
    union
    select distinct checkout.documento_id, checkout.terminal_id
    from pos_card_checkout checkout
    where checkout.documento_id is not null
    union
    select distinct session.ticket_id, session.terminal_id
    from sale_payment_session session
    where session.ticket_id is not null
    union
    select distinct movement.documento_id, movement.terminal_id
    from movimiento_caja movement
    where movement.documento_id is not null
), documentos_inequivocos as (
    select documento_id
    from pares_candidatos
    group by documento_id
    having count(*) = 1
)
update documento document
set terminal_origen_id = candidate.terminal_id
from pares_candidatos candidate
join documentos_inequivocos unambiguous
  on unambiguous.documento_id = candidate.documento_id
join terminal source_terminal
  on source_terminal.id = candidate.terminal_id
where document.id = candidate.documento_id
  and source_terminal.tienda_id = document.tienda_id;

create table documento_evento_operativo (
    id uuid primary key,
    documento_id uuid not null,
    tienda_id uuid not null references tienda(id),
    tipo varchar(24) not null,
    usuario_id uuid not null references usuario(id),
    terminal_id uuid,
    ocurrido_en timestamptz not null,
    datos jsonb not null default '{}'::jsonb,
    constraint documento_evento_documento_tienda_fk
        foreign key (documento_id, tienda_id) references documento(id, tienda_id),
    constraint documento_evento_terminal_tienda_fk
        foreign key (terminal_id, tienda_id) references terminal(id, tienda_id),
    constraint documento_evento_tipo_ck check (tipo in (
        'CREADO',
        'CONFIRMADO',
        'ANULADO',
        'MODIFICADO',
        'COBRADO',
        'CONVERTIDO',
        'RECTIFICADO'
    ))
);

create index ix_documento_evento_documento_fecha
    on documento_evento_operativo(documento_id, ocurrido_en, id);

create index ix_documento_evento_tienda_fecha
    on documento_evento_operativo(tienda_id, ocurrido_en desc, id);

insert into documento_evento_operativo (
    id, documento_id, tienda_id, tipo, usuario_id, terminal_id, ocurrido_en, datos)
select gen_random_uuid(), id, tienda_id, 'CREADO', creado_por, terminal_origen_id,
       creado_en, jsonb_build_object('migrado', true)
from documento;

insert into documento_evento_operativo (
    id, documento_id, tienda_id, tipo, usuario_id, terminal_id, ocurrido_en, datos)
select gen_random_uuid(), id, tienda_id, 'CONFIRMADO', confirmado_por, terminal_origen_id,
       confirmado_en, jsonb_build_object('migrado', true)
from documento
where confirmado_por is not null and confirmado_en is not null;

insert into documento_evento_operativo (
    id, documento_id, tienda_id, tipo, usuario_id, terminal_id, ocurrido_en, datos)
select gen_random_uuid(), id, tienda_id, 'ANULADO', anulado_por, null,
       anulado_en, jsonb_build_object('migrado', true)
from documento
where anulado_por is not null and anulado_en is not null;

insert into documento_evento_operativo (
    id, documento_id, tienda_id, tipo, usuario_id, terminal_id, ocurrido_en, datos)
select gen_random_uuid(), relation.origen_id, source.tienda_id,
       case when relation.tipo = 'FACTURA_DE' then 'CONVERTIDO' else 'RECTIFICADO' end,
       coalesce(target.confirmado_por, target.creado_por),
       case when target.tienda_id = source.tienda_id then target.terminal_origen_id else null end,
       coalesce(target.confirmado_en, target.creado_en),
       jsonb_build_object('documentoRelacionadoId', target.id, 'migrado', true)
from documento_relacion relation
join documento source on source.id = relation.origen_id
join documento target on target.id = relation.documento_id
where relation.tipo in ('FACTURA_DE', 'RECTIFICA');

create function impedir_mutacion_documento_evento_operativo()
returns trigger
language plpgsql
as $$
begin
    raise exception 'documento_evento_operativo es append-only';
end;
$$;

create trigger documento_evento_operativo_append_only
before update or delete on documento_evento_operativo
for each row execute function impedir_mutacion_documento_evento_operativo();
