-- Preserve legacy commercial profiles without assigning a fictional profile to
-- new companies; commercial activity is configured by store from now on.
alter table saas_company alter column commercial_profile drop not null;
alter table saas_company alter column commercial_profile drop default;

-- Historical companies have no inferred owners. Full profile writes and new
-- registrations validate at least one explicitly supplied owner in the API.
alter table saas_company add column owners jsonb not null default '[]'::jsonb;
alter table saas_company add constraint ck_saas_company_owners_array
    check (jsonb_typeof(owners) = 'array');

alter table saas_company_operations add column contact_phone varchar(40);
