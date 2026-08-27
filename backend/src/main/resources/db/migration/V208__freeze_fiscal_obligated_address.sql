-- New fiscal artifacts freeze the issuer address used on their first print and
-- every later reprint. Historical artifacts remain null rather than copying a
-- mutable current company address without immutable evidence.
alter table artefacto_registro_fiscal
    add column obligado_direccion jsonb,
    add constraint ck_artefacto_obligado_direccion
        check (obligado_direccion is null or (
            jsonb_typeof(obligado_direccion) = 'object'
            and char_length(trim(coalesce(obligado_direccion ->> 'linea1', ''))) > 0
            and char_length(trim(coalesce(obligado_direccion ->> 'codigoPostal', ''))) > 0
            and char_length(trim(coalesce(obligado_direccion ->> 'ciudad', ''))) > 0
            and char_length(trim(coalesce(obligado_direccion ->> 'provincia', ''))) > 0
            and char_length(trim(coalesce(obligado_direccion ->> 'pais', ''))) > 0
        ));

create or replace function validar_nuevo_artefacto_fiscal() returns trigger
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

    if new.obligado_nombre is null or new.obligado_nif is null
            or new.obligado_direccion is null then
        raise exception 'un artefacto fiscal nuevo requiere identidad y direccion congeladas';
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
