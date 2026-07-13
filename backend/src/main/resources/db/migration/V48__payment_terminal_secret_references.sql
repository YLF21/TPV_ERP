create table payment_terminal_secret_reference (
    id uuid primary key,
    company_id uuid not null references empresa(id),
    store_id uuid not null references tienda(id),
    terminal_id uuid not null references terminal(id),
    opaque_reference varchar(96) not null,
    provider varchar(32) not null,
    version integer not null check (version > 0),
    protected_value bytea not null check (octet_length(protected_value) > 0),
    active boolean not null default true,
    created_at timestamptz not null,
    retired_at timestamptz,
    constraint uq_payment_terminal_secret_version unique (company_id, store_id, terminal_id, opaque_reference, version),
    constraint ck_payment_terminal_secret_lifecycle check (
        (active and retired_at is null) or (not active and retired_at is not null)
    )
);

create unique index uq_payment_terminal_secret_active
    on payment_terminal_secret_reference (company_id, store_id, terminal_id, opaque_reference) where active;
create index ix_payment_terminal_secret_provider on payment_terminal_secret_reference (provider, active);

alter table configuracion_pago_terminal
    add column secret_reference_version integer,
    add constraint ck_configuracion_pago_terminal_secret_version
        check (secret_reference_version is null or secret_reference_version > 0);

update configuracion_pago_terminal
set secret_reference = null,
    secret_reference_version = null,
    enabled = false,
    last_connection_status = 'ERROR'
where secret_reference is not null;

alter table payment_terminal_secret_reference
    add constraint fk_payment_secret_store_company foreign key (store_id, company_id) references tienda(id, empresa_id),
    add constraint fk_payment_secret_terminal_store foreign key (terminal_id, store_id) references terminal(id, tienda_id);
