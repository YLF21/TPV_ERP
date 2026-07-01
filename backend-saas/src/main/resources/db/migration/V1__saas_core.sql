create table saas_company (
    id uuid primary key,
    name varchar(200) not null,
    tax_id varchar(32) not null unique,
    taxpayer_type varchar(32) not null,
    tax_regime varchar(16) not null,
    created_at timestamp with time zone not null
);

create table saas_store (
    id uuid primary key,
    company_id uuid not null references saas_company(id),
    code varchar(64) not null,
    name varchar(200) not null,
    created_at timestamp with time zone not null,
    unique(company_id, code)
);

create table saas_license (
    id uuid primary key,
    company_id uuid not null references saas_company(id),
    reference varchar(80) not null unique,
    valid_until timestamp with time zone not null,
    status varchar(32) not null,
    max_windows integer not null,
    max_pda integer not null,
    created_at timestamp with time zone not null
);

create table saas_pairing_code (
    id uuid primary key,
    company_id uuid not null references saas_company(id),
    store_id uuid not null references saas_store(id),
    license_id uuid not null references saas_license(id),
    code varchar(64) not null unique,
    consumed_at timestamp with time zone,
    expires_at timestamp with time zone not null,
    created_at timestamp with time zone not null
);

create table saas_installation (
    id uuid primary key,
    company_id uuid not null references saas_company(id),
    store_id uuid not null references saas_store(id),
    license_id uuid not null references saas_license(id),
    installation_id uuid not null unique,
    installation_reference varchar(120) not null,
    installation_public_key text,
    token_hash varchar(128) not null,
    linked_at timestamp with time zone not null,
    last_validated_at timestamp with time zone
);

create table saas_sync_event (
    event_id uuid primary key,
    company_id uuid not null references saas_company(id),
    store_id uuid references saas_store(id),
    installation_id uuid references saas_installation(id),
    entity_type varchar(80) not null,
    entity_id uuid not null,
    operation varchar(32) not null,
    payload text not null,
    received_at timestamp with time zone not null
);
