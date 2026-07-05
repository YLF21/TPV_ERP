create table saas_company_operations (
    company_id uuid primary key references saas_company(id),
    plan_name varchar(80) not null,
    billing_status varchar(32) not null,
    renewal_date timestamp with time zone,
    monthly_price varchar(32),
    support_status varchar(32) not null,
    contact_name varchar(160),
    contact_email varchar(160),
    notes text,
    updated_at timestamp with time zone not null
);

alter table saas_installation add column app_version varchar(80);
alter table saas_installation add column operating_system varchar(120);
alter table saas_installation add column terminal_name varchar(160);
alter table saas_installation add column last_ip varchar(80);

insert into saas_admin_role(id, name, created_at) values
    ('55555555-5555-5555-5555-555555555555', 'SUPPORT', current_timestamp),
    ('66666666-6666-6666-6666-666666666666', 'BILLING', current_timestamp),
    ('77777777-7777-7777-7777-777777777777', 'AUDITOR', current_timestamp);

insert into saas_admin_role_permission(role_id, permission_code)
select r.id, p.code
from saas_admin_role r
join saas_admin_permission p on p.code in ('VIEW_ADMIN_DATA', 'REGENERATE_PAIRING_CODE')
where r.name = 'SUPPORT';

insert into saas_admin_role_permission(role_id, permission_code)
select r.id, p.code
from saas_admin_role r
join saas_admin_permission p on p.code in ('VIEW_ADMIN_DATA', 'RENEW_LICENSE', 'EDIT_COMPANY_DATA')
where r.name = 'BILLING';

insert into saas_admin_role_permission(role_id, permission_code)
select r.id, p.code
from saas_admin_role r
join saas_admin_permission p on p.code = 'VIEW_ADMIN_DATA'
where r.name = 'AUDITOR';
