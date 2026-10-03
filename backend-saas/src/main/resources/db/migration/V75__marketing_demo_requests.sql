create table saas_marketing_demo_request (
    id uuid primary key,
    product varchar(24) not null
        check (product in ('APP_VENTA', 'APP_GESTION', 'APP_PDA', 'APP_SAAS')),
    contact_name varchar(160) not null,
    company_name varchar(200) not null,
    email varchar(160) not null,
    phone varchar(40),
    message varchar(2000),
    locale varchar(5) not null
        check (locale in ('es', 'en', 'zh')),
    privacy_accepted_at timestamptz not null,
    status varchar(16) not null default 'NEW'
        check (status in ('NEW', 'CONTACTED', 'CLOSED')),
    created_at timestamptz not null
);

create index idx_saas_marketing_demo_request_inbox
    on saas_marketing_demo_request(status, created_at desc, id);

create index idx_saas_marketing_demo_request_email
    on saas_marketing_demo_request(lower(email), created_at desc);
