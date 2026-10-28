-- The address book has its own concurrency boundary. Never update legacy profile
-- columns here: V53 deliberately includes them in identity_revision.
alter table saas_erp_customer add constraint uq_saas_customer_company_id unique(company_id, id);

create table saas_customer_address_book (
    company_id uuid not null,
    customer_id uuid not null,
    revision bigint not null default 1 check (revision > 0),
    addresses_json jsonb not null check (jsonb_typeof(addresses_json) = 'array'
        and jsonb_array_length(addresses_json) between 1 and 50),
    primary key(company_id, customer_id),
    foreign key(company_id, customer_id) references saas_erp_customer(company_id, id)
);

insert into saas_customer_address_book(company_id, customer_id, addresses_json)
select company_id, id, jsonb_build_array(jsonb_build_object(
    'id', id, 'address', coalesce(address_json, '{}'::jsonb),
    'phone', phone, 'email', email, 'notes', null))
from saas_erp_customer;

create table saas_customer_address_operation (
    operation_id uuid primary key,
    company_id uuid not null,
    customer_id uuid not null,
    installation_id uuid not null references saas_installation(id),
    store_id uuid not null references saas_store(id),
    local_customer_id uuid not null,
    expected_revision bigint not null check (expected_revision > 0),
    payload_hash varchar(64) not null,
    status varchar(16) not null check (status in ('APPLIED', 'CONFLICT')),
    created_at timestamp with time zone not null default now(),
    foreign key(company_id, customer_id) references saas_customer_address_book(company_id, customer_id)
);
create index ix_saas_customer_address_operation_owner
    on saas_customer_address_operation(company_id, customer_id, installation_id);
