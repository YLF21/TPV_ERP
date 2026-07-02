create table saas_admin_permission (
    code varchar(64) primary key
);

create table saas_admin_role (
    id uuid primary key,
    name varchar(80) not null unique,
    created_at timestamp with time zone not null
);

create table saas_admin_role_permission (
    role_id uuid not null references saas_admin_role(id),
    permission_code varchar(64) not null references saas_admin_permission(code),
    primary key(role_id, permission_code)
);

create table saas_admin_user (
    id uuid primary key,
    username varchar(80) not null unique,
    password_hash varchar(128) not null,
    active boolean not null,
    created_at timestamp with time zone not null
);

create table saas_admin_user_role (
    user_id uuid not null references saas_admin_user(id),
    role_id uuid not null references saas_admin_role(id),
    primary key(user_id, role_id)
);

insert into saas_admin_permission(code) values
    ('ADD_COMPANY'),
    ('RENEW_LICENSE'),
    ('BLOCK_LICENSE'),
    ('UNBLOCK_LICENSE'),
    ('EDIT_COMPANY_DATA'),
    ('VIEW_ADMIN_DATA'),
    ('REGENERATE_PAIRING_CODE');

insert into saas_admin_role(id, name, created_at) values
    ('11111111-1111-1111-1111-111111111111', 'ADMIN', current_timestamp),
    ('22222222-2222-2222-2222-222222222222', 'VIEWER', current_timestamp);

insert into saas_admin_role_permission(role_id, permission_code)
select '11111111-1111-1111-1111-111111111111', code from saas_admin_permission;

insert into saas_admin_role_permission(role_id, permission_code) values
    ('22222222-2222-2222-2222-222222222222', 'VIEW_ADMIN_DATA');

insert into saas_admin_user(id, username, password_hash, active, created_at) values
    ('33333333-3333-3333-3333-333333333333', 'admin', '8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918', true, current_timestamp),
    ('44444444-4444-4444-4444-444444444444', 'viewer', '8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918', true, current_timestamp);

insert into saas_admin_user_role(user_id, role_id) values
    ('33333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111'),
    ('44444444-4444-4444-4444-444444444444', '22222222-2222-2222-2222-222222222222');
