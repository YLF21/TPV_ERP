alter table sync_outbox
    add column proximo_intento_en timestamptz,
    add column reclamado_en timestamptz,
    add column claim_token uuid,
    add column actualizado_en timestamptz;

-- Un ENVIANDO anterior a esta migracion no tiene propietario ni lease fiable.
-- Se recupera como error reintentable en vez de perderlo o dejarlo bloqueado.
update sync_outbox
set estado = 'ERROR',
    ultimo_error = left(coalesce(nullif(trim(ultimo_error), ''),
        'Claim anterior recuperado durante la migracion del outbox'), 1000),
    proximo_intento_en = current_timestamp,
    reclamado_en = null,
    claim_token = null,
    actualizado_en = current_timestamp
where estado = 'ENVIANDO';

-- PENDIENTE y ERROR historicos vuelven a ser elegibles inmediatamente.
update sync_outbox
set proximo_intento_en = case
        when estado in ('PENDIENTE', 'ERROR') then current_timestamp
        else null
    end,
    reclamado_en = null,
    claim_token = null,
    ultimo_error = case
        when ultimo_error is null then null
        else left(ultimo_error, 1000)
    end,
    actualizado_en = coalesce(enviado_en, creado_en, current_timestamp);

alter table sync_outbox
    alter column ultimo_error type varchar(1000),
    alter column actualizado_en set not null;

alter table sync_outbox
    drop constraint if exists sync_outbox_estado_check;

alter table sync_outbox
    add constraint ck_sync_outbox_estado
        check (estado in ('PENDIENTE', 'ENVIANDO', 'ENVIADO', 'ERROR', 'DEAD_LETTER')),
    add constraint ck_sync_outbox_claim_completo
        check ((reclamado_en is null and claim_token is null)
            or (reclamado_en is not null and claim_token is not null));

create index ix_sync_outbox_reintento
    on sync_outbox(estado, proximo_intento_en, creado_en)
    where estado in ('PENDIENTE', 'ERROR');

create index ix_sync_outbox_claim_caducado
    on sync_outbox(reclamado_en, creado_en)
    where estado = 'ENVIANDO';
