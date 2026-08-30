-- Durable, reclaimable submission queue.  A row remains ENVIANDO only while
-- its lease is held; a stale worker can never complete a row without its token.
alter table estado_envio_fiscal
    add column if not exists intentos integer not null default 0,
    add column if not exists proximo_intento_en timestamptz,
    add column if not exists lease_owner uuid,
    add column if not exists lease_hasta timestamptz,
    add column if not exists claim_token uuid;

-- Recover rows claimed by the pre-lease implementation without losing their
-- retry history.  ENVIANDO was not durable there, so it is immediately retryable.
update estado_envio_fiscal
   set estado = 'ENVIADO',
       proximo_intento_en = current_timestamp,
       lease_owner = null,
       lease_hasta = null,
       claim_token = null
 where estado = 'ENVIANDO';

update estado_envio_fiscal
   set proximo_intento_en = case
         when estado = 'PENDIENTE' then coalesce(proximo_intento_en, current_timestamp)
         when estado = 'ENVIADO' then coalesce(
                 proximo_intento_en, actualizado_en + interval '1 hour')
         else null
       end,
       lease_owner = null,
       lease_hasta = null,
       claim_token = null
 where estado in ('PENDIENTE', 'ENVIADO');

alter table estado_envio_fiscal
    drop constraint if exists estado_envio_fiscal_estado_check;

alter table estado_envio_fiscal
    add constraint ck_estado_envio_fiscal_estado check (estado in (
        'PENDIENTE', 'ENVIANDO', 'ENVIADO', 'ACEPTADO',
        'ACEPTADO_CON_ERRORES', 'RECHAZADO', 'DEFECTUOSO', 'SUBSANADO')),
    add constraint ck_estado_envio_fiscal_intentos check (intentos >= 0),
    add constraint ck_estado_envio_fiscal_claim check (
        (estado = 'ENVIANDO'
            and lease_owner is not null
            and lease_hasta is not null
            and claim_token is not null
            and proximo_intento_en is null)
        or (estado in ('PENDIENTE', 'ENVIADO')
            and lease_owner is null
            and lease_hasta is null
            and claim_token is null
            and proximo_intento_en is not null)
        or (estado in ('ACEPTADO', 'ACEPTADO_CON_ERRORES', 'RECHAZADO',
                       'DEFECTUOSO', 'SUBSANADO')
            and lease_owner is null
            and lease_hasta is null
            and claim_token is null
            and proximo_intento_en is null)
    );

create index if not exists ix_estado_envio_fiscal_claim
    on estado_envio_fiscal(estado, proximo_intento_en, lease_hasta,
                           actualizado_en, registro_id)
    where estado in ('PENDIENTE', 'ENVIANDO', 'ENVIADO');

create index if not exists ix_estado_envio_fiscal_record_status
    on estado_envio_fiscal(registro_id, estado);

create index if not exists ix_registro_fiscal_cadena_secuencia
    on registro_fiscal(cadena_id, secuencia, id);
