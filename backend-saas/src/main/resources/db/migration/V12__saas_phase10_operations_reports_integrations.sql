-- Runs after V11__verifactu_activation_policy from the remote main branch.
create table saas_subscription (
    id uuid primary key,
    company_id uuid not null references saas_company(id),
    plan_name varchar(80) not null,
    status varchar(32) not null,
    billing_cycle varchar(32) not null,
    amount varchar(32) not null,
    currency varchar(8) not null,
    started_at timestamp with time zone not null,
    next_billing_at timestamp with time zone,
    cancelled_at timestamp with time zone,
    created_at timestamp with time zone not null
);

create table saas_sales_document (
    id uuid primary key,
    company_id uuid not null references saas_company(id),
    store_id uuid references saas_store(id),
    document_number varchar(80) not null,
    customer_code varchar(40),
    total varchar(32) not null,
    currency varchar(8) not null,
    status varchar(32) not null,
    issued_at timestamp with time zone not null,
    created_at timestamp with time zone not null,
    unique(company_id, document_number)
);

create table saas_inventory_movement (
    id uuid primary key,
    company_id uuid not null references saas_company(id),
    warehouse_code varchar(40) not null,
    product_sku varchar(60) not null,
    movement_type varchar(32) not null,
    quantity varchar(32) not null,
    reason varchar(160),
    moved_at timestamp with time zone not null,
    created_at timestamp with time zone not null
);

create table saas_integration_endpoint (
    id uuid primary key,
    company_id uuid references saas_company(id),
    name varchar(120) not null,
    integration_type varchar(40) not null,
    status varchar(32) not null,
    target_url varchar(500),
    api_key varchar(160),
    last_sync_at timestamp with time zone,
    created_at timestamp with time zone not null
);

create index idx_saas_subscription_company on saas_subscription(company_id);
create index idx_saas_sales_document_company on saas_sales_document(company_id);
create index idx_saas_inventory_movement_company on saas_inventory_movement(company_id);
create index idx_saas_integration_company on saas_integration_endpoint(company_id);

insert into saas_admin_permission(code) values
    ('MANAGE_OPERATIONS'),
    ('MANAGE_SUBSCRIPTIONS'),
    ('MANAGE_INTEGRATIONS'),
    ('VIEW_REPORTS');

insert into saas_admin_role_permission(role_id, permission_code)
select r.id, p.code
from saas_admin_role r
join saas_admin_permission p on p.code in ('MANAGE_OPERATIONS', 'MANAGE_SUBSCRIPTIONS', 'MANAGE_INTEGRATIONS', 'VIEW_REPORTS')
where r.name = 'ADMIN';

insert into saas_admin_role_permission(role_id, permission_code)
select r.id, 'VIEW_REPORTS'
from saas_admin_role r
where r.name in ('AUDITOR', 'BILLING', 'SUPPORT', 'VIEWER');
