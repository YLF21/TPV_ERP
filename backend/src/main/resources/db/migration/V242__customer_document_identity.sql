-- Match Java trim() at the edges and remove ASCII spaces/hyphens internally.
create function customer_document_key(value text) returns text
language sql immutable parallel safe as $$
    select upper(replace(replace(btrim(coalesce(value, ''),
        (select string_agg(chr(code), '' order by code) from generate_series(1, 32) as code)), ' ', ''), '-', ''));
$$;

-- Read-only preflight: do not merge or discard customers when applying the new key.
do $$
begin
    if exists (select 1 from cliente group by empresa_id,
        customer_document_key(numero_documento) having count(*) > 1) then
        raise exception 'CUSTOMER_DOCUMENT_DUPLICATE: reconcile normalized customer documents before migration V242';
    end if;
end $$;

-- Preserve legacy wire values and history; CIF/OTRO are aliases in customer forms.
alter table cliente drop constraint if exists cliente_tipo_documento_check;
alter table cliente add constraint cliente_tipo_documento_check
    check (tipo_documento in ('NIE', 'DNI', 'NIF', 'CIF', 'PASAPORTE', 'OTRO'));
drop index ux_cliente_documento_empresa;
create unique index ux_cliente_documento_empresa on cliente
    (empresa_id, customer_document_key(numero_documento));

alter table cliente add column saas_customer_id uuid;
alter table cliente add column saas_identity_revision bigint;
alter table cliente add constraint ck_cliente_saas_identity
    check ((saas_customer_id is null and saas_identity_revision is null)
        or (saas_customer_id is not null and saas_identity_revision is not null and saas_identity_revision > 0));
create unique index ux_cliente_saas_identity on cliente(empresa_id, saas_customer_id)
    where saas_customer_id is not null;

-- An intent is committed independently BEFORE HTTP. It contains only identity metadata,
-- survives a timeout/rollback, and is reused rather than allocating another customer UUID.
create table customer_identity_operation (
    operation_id uuid primary key,
    company_id uuid not null references empresa(id),
    store_id uuid not null references tienda(id),
    customer_id uuid not null,
    creating boolean not null,
    document_type varchar(16) not null,
    document_number varchar(64) not null,
    expected_customer_id uuid,
    expected_revision bigint,
    state varchar(24) not null check (state in ('PENDING','CANCEL_PENDING','CANCELLED','LOCAL_COMMITTED')),
    created_at timestamptz not null default now()
);
create unique index ux_customer_identity_pending_customer
    on customer_identity_operation(company_id, customer_id) where state in ('PENDING','CANCEL_PENDING');
create unique index ux_customer_identity_pending_number
    on customer_identity_operation(company_id, document_number) where state in ('PENDING','CANCEL_PENDING');
create index ix_customer_identity_cancellation
    on customer_identity_operation(created_at) where state = 'CANCEL_PENDING';
