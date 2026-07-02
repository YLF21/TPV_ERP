create table saas_admin_audit_log (
    id uuid primary key,
    username varchar(80) not null,
    action varchar(80) not null,
    target_type varchar(80) not null,
    target_id varchar(120) not null,
    created_at timestamp with time zone not null
);
