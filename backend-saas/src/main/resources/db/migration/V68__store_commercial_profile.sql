-- Preserve the previous company-level setting on each existing store. Company
-- values remain historical compatibility data; each store is now authoritative.
alter table saas_store add column commercial_profile varchar(16);

update saas_store s
set commercial_profile = c.commercial_profile
from saas_company c
where c.id = s.company_id;

alter table saas_store alter column commercial_profile set not null;
alter table saas_store add constraint ck_saas_store_commercial_profile
    check (commercial_profile in ('MAYORISTA', 'MINORISTA'));

-- Legacy insert clients may inherit an existing company setting. New companies
-- without one must supply the store setting explicitly; no default is invented.
create function saas_initialize_store_commercial_profile() returns trigger
language plpgsql as $$
begin
    if new.commercial_profile is null then
        select c.commercial_profile into new.commercial_profile
        from saas_company c where c.id = new.company_id;
    end if;
    return new;
end;
$$;

create trigger trg_saas_store_initialize_commercial_profile
before insert on saas_store
for each row execute function saas_initialize_store_commercial_profile();
