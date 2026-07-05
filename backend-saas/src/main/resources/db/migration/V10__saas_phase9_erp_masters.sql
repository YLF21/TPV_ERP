create table saas_erp_customer (
    id uuid primary key,
    company_id uuid not null references saas_company(id),
    code varchar(40) not null,
    name varchar(160) not null,
    tax_id varchar(40),
    email varchar(160),
    phone varchar(40),
    active boolean not null,
    created_at timestamp not null,
    unique(company_id, code)
);

create table saas_erp_product (
    id uuid primary key,
    company_id uuid not null references saas_company(id),
    sku varchar(60) not null,
    name varchar(180) not null,
    category varchar(100),
    price varchar(32) not null,
    tax_rate varchar(32) not null,
    min_stock varchar(32) not null,
    active boolean not null,
    created_at timestamp not null,
    unique(company_id, sku)
);

create table saas_erp_supplier (
    id uuid primary key,
    company_id uuid not null references saas_company(id),
    code varchar(40) not null,
    name varchar(160) not null,
    tax_id varchar(40),
    email varchar(160),
    phone varchar(40),
    active boolean not null,
    created_at timestamp not null,
    unique(company_id, code)
);

create table saas_erp_warehouse (
    id uuid primary key,
    company_id uuid not null references saas_company(id),
    code varchar(40) not null,
    name varchar(160) not null,
    address varchar(240),
    active boolean not null,
    created_at timestamp not null,
    unique(company_id, code)
);

create index idx_saas_erp_customer_company on saas_erp_customer(company_id);
create index idx_saas_erp_product_company on saas_erp_product(company_id);
create index idx_saas_erp_supplier_company on saas_erp_supplier(company_id);
create index idx_saas_erp_warehouse_company on saas_erp_warehouse(company_id);

insert into saas_admin_permission(code) values ('MANAGE_ERP_MASTERS');
insert into saas_admin_role_permission(role_id, permission_code)
select r.id, p.code
from saas_admin_role r
join saas_admin_permission p on p.code = 'MANAGE_ERP_MASTERS'
where r.name = 'ADMIN';
