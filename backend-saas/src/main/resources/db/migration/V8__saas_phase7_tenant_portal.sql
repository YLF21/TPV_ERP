create table saas_tenant_user (
    id uuid primary key,
    company_id uuid not null references saas_company(id),
    username varchar(80) not null unique,
    password_hash varchar(128) not null,
    role_name varchar(40) not null,
    active boolean not null,
    created_at timestamp with time zone not null
);

create index idx_saas_tenant_user_company on saas_tenant_user(company_id);
