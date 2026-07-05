create table saas_support_ticket (
    id uuid primary key,
    company_id uuid not null references saas_company(id),
    title varchar(200) not null,
    description text,
    status varchar(32) not null,
    priority varchar(32) not null,
    created_by varchar(80) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

insert into saas_admin_permission(code) values ('MANAGE_SUPPORT_TICKETS');

insert into saas_admin_role_permission(role_id, permission_code)
select r.id, 'MANAGE_SUPPORT_TICKETS'
from saas_admin_role r
where r.name in ('ADMIN', 'SUPPORT');
