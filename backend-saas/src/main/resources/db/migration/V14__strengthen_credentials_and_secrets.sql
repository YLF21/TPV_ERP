alter table saas_admin_user
    alter column password_hash type varchar(255);

alter table saas_tenant_user
    alter column password_hash type varchar(255);

alter table saas_integration_endpoint
    add column if not exists api_key_encrypted text;
