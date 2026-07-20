-- Reconciles databases where older V81/V82 contents were recorded before those
-- migration numbers were reassigned during branch integration.
alter table documento
    add column if not exists terminal_origen_id uuid;

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'documento_terminal_origen_tienda_fk'
          and conrelid = 'documento'::regclass
    ) then
        alter table documento
            add constraint documento_terminal_origen_tienda_fk
            foreign key (terminal_origen_id, tienda_id) references terminal(id, tienda_id);
    end if;
end
$$;

create index if not exists ix_documento_tienda_terminal_fecha
    on documento(tienda_id, terminal_origen_id, fecha desc)
    where terminal_origen_id is not null;

create or replace function impedir_cambio_terminal_origen_documento()
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

do $$
begin
    if not exists (
        select 1 from pg_trigger
        where tgname = 'documento_terminal_origen_inmutable'
          and tgrelid = 'documento'::regclass
          and not tgisinternal
    ) then
        create trigger documento_terminal_origen_inmutable
        before update of terminal_origen_id on documento
        for each row execute function impedir_cambio_terminal_origen_documento();
    end if;
end
$$;

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
  and document.terminal_origen_id is null
  and source_terminal.tienda_id = document.tienda_id;

create table if not exists documento_evento_operativo (
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

create index if not exists ix_documento_evento_documento_fecha
    on documento_evento_operativo(documento_id, ocurrido_en, id);

create index if not exists ix_documento_evento_tienda_fecha
    on documento_evento_operativo(tienda_id, ocurrido_en desc, id);

insert into documento_evento_operativo (
    id, documento_id, tienda_id, tipo, usuario_id, terminal_id, ocurrido_en, datos)
select gen_random_uuid(), document.id, document.tienda_id, 'CREADO', document.creado_por,
       document.terminal_origen_id, document.creado_en, jsonb_build_object('migrado', true)
from documento document
where not exists (
    select 1 from documento_evento_operativo event
    where event.documento_id = document.id
      and event.tipo = 'CREADO'
      and event.datos @> '{"migrado": true}'::jsonb
);

insert into documento_evento_operativo (
    id, documento_id, tienda_id, tipo, usuario_id, terminal_id, ocurrido_en, datos)
select gen_random_uuid(), document.id, document.tienda_id, 'CONFIRMADO', document.confirmado_por,
       document.terminal_origen_id, document.confirmado_en, jsonb_build_object('migrado', true)
from documento document
where document.confirmado_por is not null
  and document.confirmado_en is not null
  and not exists (
      select 1 from documento_evento_operativo event
      where event.documento_id = document.id
        and event.tipo = 'CONFIRMADO'
        and event.datos @> '{"migrado": true}'::jsonb
  );

insert into documento_evento_operativo (
    id, documento_id, tienda_id, tipo, usuario_id, terminal_id, ocurrido_en, datos)
select gen_random_uuid(), document.id, document.tienda_id, 'ANULADO', document.anulado_por,
       null, document.anulado_en, jsonb_build_object('migrado', true)
from documento document
where document.anulado_por is not null
  and document.anulado_en is not null
  and not exists (
      select 1 from documento_evento_operativo event
      where event.documento_id = document.id
        and event.tipo = 'ANULADO'
        and event.datos @> '{"migrado": true}'::jsonb
  );

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
where relation.tipo in ('FACTURA_DE', 'RECTIFICA')
  and not exists (
      select 1 from documento_evento_operativo event
      where event.documento_id = relation.origen_id
        and event.tipo = case when relation.tipo = 'FACTURA_DE' then 'CONVERTIDO' else 'RECTIFICADO' end
        and event.datos ->> 'documentoRelacionadoId' = target.id::text
  );

create or replace function impedir_mutacion_documento_evento_operativo()
returns trigger
language plpgsql
as $$
begin
    raise exception 'documento_evento_operativo es append-only';
end;
$$;

do $$
begin
    if not exists (
        select 1 from pg_trigger
        where tgname = 'documento_evento_operativo_append_only'
          and tgrelid = 'documento_evento_operativo'::regclass
          and not tgisinternal
    ) then
        create trigger documento_evento_operativo_append_only
        before update or delete on documento_evento_operativo
        for each row execute function impedir_mutacion_documento_evento_operativo();
    end if;
end
$$;

-- Re-run the V82 normalization safely in case the recorded V82 contained older SQL.
do $$
declare
    admin_id uuid;
    admin_role_id uuid;
begin
    select usuario.id, usuario.rol_id
      into admin_id, admin_role_id
      from usuario
      join rol on rol.id = usuario.rol_id
     where usuario.nombre = 'ADMIN'
       and usuario.protegido
       and usuario.activo
       and usuario.tienda_id is not null
       and rol.nombre = 'ADMIN'
       and rol.protegido
       and not exists (
           select 1 from usuario global_admin
            where global_admin.nombre = 'ADMIN'
              and global_admin.protegido
              and global_admin.tienda_id is null
       )
       and (
           select count(*) from usuario legacy_admin
            where legacy_admin.nombre = 'ADMIN'
              and legacy_admin.protegido
              and legacy_admin.tienda_id is not null
       ) = 1
       and (
           select count(*) from usuario role_user
            where role_user.rol_id = usuario.rol_id
       ) = 1;

    if admin_id is not null then
        delete from usuario_tienda where usuario_id = admin_id;
        update rol set tienda_id = null where id = admin_role_id;
        update usuario set tienda_id = null where id = admin_id;
    end if;
end
$$;
