-- Retain the original account row, version and encrypted SMTP/OAuth material in place.
alter table saas_smtp_settings add column id uuid not null default gen_random_uuid(),
    add column removed_at timestamptz,
    add column listed boolean not null default false;
alter table saas_smtp_settings drop constraint saas_smtp_settings_pkey;
alter table saas_smtp_settings alter column singleton drop not null;
alter table saas_smtp_settings add primary key(id);
create unique index ux_saas_mail_default on saas_smtp_settings(singleton) where singleton;
create unique index ux_saas_mail_active_address on saas_smtp_settings(lower(from_address))
    where removed_at is null and from_address <> '';
alter table saas_smtp_settings add constraint saas_smtp_active_default check (singleton is null or removed_at is null);

alter table saas_mail_oauth_connection add column account_id uuid references saas_smtp_settings(id) on delete cascade;
update saas_mail_oauth_connection set account_id=(select id from saas_smtp_settings where singleton);
alter table saas_mail_oauth_connection drop column singleton;
alter table saas_mail_oauth_connection add primary key(account_id);

alter table saas_mail_oauth_flow add column account_id uuid references saas_smtp_settings(id) on delete cascade;
update saas_mail_oauth_flow set account_id=(select id from saas_smtp_settings where singleton);
alter table saas_mail_oauth_flow drop column singleton;
alter table saas_mail_oauth_flow add primary key(account_id);
alter table saas_mail_oauth_flow add unique(state_hash);

alter table saas_communication add column sender_account_id uuid references saas_smtp_settings(id);
update saas_communication set sender_account_id=(select id from saas_smtp_settings where singleton);
alter table saas_communication alter column sender_account_id set not null;
create index ix_saas_communication_sender on saas_communication(sender_account_id);

-- Test sends keep a global cooldown even if the operator changes accounts.
alter table saas_communication_pacing add column last_test_at timestamptz;
update saas_communication_pacing set last_test_at=(select last_test_at from saas_smtp_settings where singleton);
alter table saas_smtp_settings drop column last_test_at;
