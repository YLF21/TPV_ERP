create table saas_billing_invoice (
    id uuid primary key,
    company_id uuid not null references saas_company(id),
    number varchar(80) not null unique,
    concept varchar(240) not null,
    amount varchar(32) not null,
    currency varchar(8) not null,
    status varchar(32) not null,
    issued_at timestamp with time zone not null,
    due_at timestamp with time zone not null,
    created_at timestamp with time zone not null
);

create table saas_billing_payment (
    id uuid primary key,
    invoice_id uuid not null references saas_billing_invoice(id),
    amount varchar(32) not null,
    method varchar(80) not null,
    reference varchar(120),
    paid_at timestamp with time zone not null,
    created_at timestamp with time zone not null
);

insert into saas_admin_permission(code) values
    ('MANAGE_BILLING'),
    ('MANAGE_TENANT_USERS');

insert into saas_admin_role_permission(role_id, permission_code)
select r.id, p.code
from saas_admin_role r
join saas_admin_permission p on p.code in ('MANAGE_BILLING', 'MANAGE_TENANT_USERS')
where r.name = 'ADMIN';

insert into saas_admin_role_permission(role_id, permission_code)
select r.id, 'MANAGE_BILLING'
from saas_admin_role r
where r.name = 'BILLING';
