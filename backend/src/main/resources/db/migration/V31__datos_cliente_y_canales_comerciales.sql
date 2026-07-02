create table commercial_contact_channel (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    code varchar(32) not null,
    name varchar(64) not null,
    active boolean not null default true,
    version bigint not null default 0,
    constraint ux_commercial_contact_channel_empresa_code unique (empresa_id, code)
);

insert into commercial_contact_channel (id, empresa_id, code, name, active, version)
select gen_random_uuid(), id, 'EMAIL', 'Email', true, 0
from empresa;

insert into commercial_contact_channel (id, empresa_id, code, name, active, version)
select gen_random_uuid(), id, 'WHATSAPP', 'WhatsApp', true, 0
from empresa;

alter table cliente
    add column birthday date,
    add column gender varchar(16),
    add column commercial_consent boolean not null default false,
    add column preferred_commercial_channel_id uuid
        references commercial_contact_channel(id),
    add constraint ck_cliente_gender
        check (gender is null or gender in ('MASCULINO', 'FEMENINO', 'OTRO')),
    add constraint ck_cliente_commercial_consent_channel
        check (commercial_consent = false or preferred_commercial_channel_id is not null);
