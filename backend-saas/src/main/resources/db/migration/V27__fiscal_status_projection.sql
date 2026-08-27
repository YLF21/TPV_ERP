create table saas_fiscal_status (
    id uuid primary key,
    installation_id uuid not null unique references saas_installation(id),
    company_id uuid not null references saas_company(id),
    store_id uuid not null references saas_store(id),
    source_installation_id uuid not null,
    entity_id uuid not null,
    effective_mode varchar(32) not null,
    activation_state varchar(32) not null,
    mode_version bigint not null,
    mode_since timestamp with time zone,
    activation_date date,
    policy_version bigint,
    runtime_class varchar(16) not null,
    endpoint_environment varchar(16) not null,
    transport_mode varchar(16) not null,
    reported_at timestamp with time zone not null,
    received_at timestamp with time zone not null,
    payload_hash varchar(64) not null,
    constraint uq_saas_fiscal_status_source unique (source_installation_id, entity_id),
    constraint ck_saas_fiscal_status_mode check (effective_mode in ('PRE_SIF', 'NO_VERIFACTU', 'VERIFACTU')),
    constraint ck_saas_fiscal_status_state check (activation_state in ('ACTIVE', 'PENDING', 'DUE_REVIEW', 'UNKNOWN')),
    constraint ck_saas_fiscal_status_mode_version check (mode_version >= 0),
    constraint ck_saas_fiscal_status_policy_version check (policy_version is null or policy_version >= 0)
);

create index ix_saas_fiscal_status_company on saas_fiscal_status(company_id, store_id);
create index ix_saas_fiscal_status_reported_at on saas_fiscal_status(reported_at);
