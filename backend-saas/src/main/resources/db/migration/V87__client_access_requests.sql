-- New customer identities have no originating company. Effective scope remains
-- entirely in the explicit V61 grants, including for existing users.
alter table saas_tenant_user alter column company_id drop not null;

create table saas_client_access_request (
    id uuid primary key,
    submission_id uuid not null unique,
    username varchar(80) not null,
    password_hash varchar(128),
    full_name varchar(160) not null,
    tax_id varchar(40) not null,
    email varchar(254) not null,
    phone varchar(40) not null,
    address varchar(240) not null,
    postal_code varchar(20) not null,
    city varchar(100) not null,
    province varchar(100) not null,
    country varchar(100) not null,
    requested_stores text[] not null check (cardinality(requested_stores) <= 20),
    notes varchar(1000),
    status varchar(12) not null default 'PENDING',
    version bigint not null default 0,
    created_at timestamptz not null default current_timestamp,
    reviewed_at timestamptz,
    reviewed_by varchar(80),
    review_reason varchar(500),
    tenant_user_id uuid unique references saas_tenant_user(id),
    check (
        (status = 'PENDING' and version = 0 and password_hash is not null and tenant_user_id is null
            and reviewed_at is null and reviewed_by is null and review_reason is null)
        or
        (status in ('APPROVED','REJECTED') and version = 1 and password_hash is null
            and reviewed_at is not null and reviewed_by is not null
            and ((status = 'APPROVED' and tenant_user_id is not null)
                or (status = 'REJECTED' and tenant_user_id is null and review_reason is not null
                    and length(btrim(review_reason)) > 0)))
    )
);
create index idx_client_access_request_queue on saas_client_access_request(status, created_at desc, id);
create index idx_client_access_request_created on saas_client_access_request(created_at desc, id);
