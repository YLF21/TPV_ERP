-- V203 intentionally did not copy mutable company data into historical fiscal
-- artifacts. This migration inventories those rows and provides a separate,
-- append-only destination for identities proven later from immutable evidence.
create table inventario_identidad_legacy_fiscal (
    registro_id uuid primary key references artefacto_registro_fiscal(registro_id),
    detectado_en timestamptz not null
);

insert into inventario_identidad_legacy_fiscal (registro_id, detectado_en)
select registro_id, current_timestamp
from artefacto_registro_fiscal
where obligado_nombre is null
  and obligado_nif is null;

create table identidad_legacy_artefacto_fiscal (
    registro_id uuid primary key
        references inventario_identidad_legacy_fiscal(registro_id),
    obligado_nombre varchar(250) not null,
    obligado_nif varchar(9) not null,
    fuente varchar(32) not null,
    resuelto_en timestamptz not null,
    check (char_length(trim(obligado_nombre)) > 0),
    check (obligado_nif ~ '^[0-9A-Z]{9}$'),
    check (fuente in ('REGISTRO_ALTA_XML', 'ALTA_RELACIONADA'))
);

create trigger tr_inventario_identidad_legacy_inmutable
before update or delete on inventario_identidad_legacy_fiscal
for each row execute function impedir_mutacion_fiscal();

create trigger tr_identidad_legacy_artefacto_inmutable
before update or delete on identidad_legacy_artefacto_fiscal
for each row execute function impedir_mutacion_fiscal();

create view estado_identidad_legacy_fiscal as
select i.registro_id,
       case when f.registro_id is null
            then 'LEGACY_UNRESOLVED'
            else 'RESUELTA_CON_EVIDENCIA'
       end as estado,
       f.fuente,
       i.detectado_en,
       f.resuelto_en
from inventario_identidad_legacy_fiscal i
left join identidad_legacy_artefacto_fiscal f
  on f.registro_id = i.registro_id;

-- RegistroAnulacion is sent to AEAT but is not a newly issued invoice. Only
-- RegistroAlta owns an invoice QR and a frozen print snapshot.
alter table artefacto_registro_fiscal
    alter column qr_url drop not null,
    alter column qr_hash drop not null,
    alter column qr_prefijo drop not null,
    alter column qr_prefijo drop default;

create function validar_nuevo_artefacto_fiscal() returns trigger
language plpgsql
set search_path from current
as $$
declare
    v_operacion varchar(16);
begin
    select operacion into v_operacion
      from registro_fiscal
     where id = new.registro_id;

    if v_operacion is null then
        raise exception 'registro fiscal inexistente para artefacto %', new.registro_id;
    end if;

    if new.obligado_nombre is null or new.obligado_nif is null then
        raise exception 'un artefacto fiscal nuevo requiere identidad congelada';
    end if;

    if v_operacion = 'ALTA' then
        if new.qr_url is null or new.qr_hash is null or new.qr_prefijo is null then
            raise exception 'RegistroAlta requiere evidencia QR congelada';
        end if;
    elsif new.qr_url is not null or new.qr_hash is not null
            or new.qr_prefijo is not null or new.qr_leyenda is not null
            or new.aviso_pruebas is not null then
        raise exception 'RegistroAnulacion no puede contener QR de factura';
    end if;
    return new;
end;
$$;

create trigger tr_validar_nuevo_artefacto_fiscal
before insert on artefacto_registro_fiscal
for each row execute function validar_nuevo_artefacto_fiscal();

create function validar_snapshot_impresion_solo_alta() returns trigger
language plpgsql
set search_path from current
as $$
begin
    if not exists (
        select 1
          from registro_fiscal
         where id = new.registro_id
           and operacion = 'ALTA'
    ) then
        raise exception 'solo RegistroAlta puede tener snapshot de impresion fiscal';
    end if;
    return new;
end;
$$;

create trigger tr_snapshot_impresion_solo_alta
before insert on snapshot_impresion_fiscal
for each row execute function validar_snapshot_impresion_solo_alta();
