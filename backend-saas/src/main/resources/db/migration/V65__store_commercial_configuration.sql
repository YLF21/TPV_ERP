-- Preserve the tax regime already delivered to existing installations before
-- allowing new companies that do not yet have a store or a tax regime.
alter table saas_store add column tax_regime varchar(16);
update saas_store s set tax_regime = c.tax_regime
from saas_company c where c.id = s.company_id;
alter table saas_store alter column tax_regime set not null;
alter table saas_store add constraint ck_saas_store_tax_regime
    check (tax_regime in ('IVA', 'IGIC'));
alter table saas_company alter column tax_regime drop not null;

-- Corporate prices remain historical corporate data. No amount is distributed
-- between stores and no period is invented for a previously unpriced store.
alter table saas_store add column service_price numeric(19,2);
alter table saas_store add column billing_period varchar(16);
alter table saas_store add column max_windows integer not null default 1;
alter table saas_store add column max_pda integer not null default 0;
alter table saas_store add column valid_until timestamptz;
alter table saas_store add constraint ck_saas_store_service_price
    check (service_price is null or service_price >= 0);
alter table saas_store add constraint ck_saas_store_billing_period
    check (billing_period is null or billing_period in ('MONTHLY', 'ANNUAL'));
alter table saas_store add constraint ck_saas_store_price_period
    check ((service_price is null) = (billing_period is null));
alter table saas_store add constraint ck_saas_store_terminal_limits
    check (max_windows >= 1 and max_pda >= 0);

-- Only a single directly assigned license gives an unambiguous store setting.
-- Shared and multiply licensed historical stores keep their licenses untouched.
update saas_store s set max_windows = l.max_windows, max_pda = l.max_pda,
    valid_until = l.valid_until
from saas_license l
where l.store_id = s.id
  and not exists(select 1 from saas_license other where other.id <> l.id and
      (other.store_id = s.id
       or exists(select 1 from saas_pairing_code p where p.license_id = other.id and p.store_id = s.id)
       or exists(select 1 from saas_installation i where i.license_id = other.id and i.store_id = s.id)))
  and not exists(select 1 from saas_pairing_code p where p.license_id = l.id and p.store_id <> s.id)
  and not exists(select 1 from saas_installation i where i.license_id = l.id and i.store_id <> s.id);

create function preserve_licensed_store_tax_regime() returns trigger language plpgsql as $$
begin
    if new.tax_regime is distinct from old.tax_regime and (
        exists(select 1 from saas_license where store_id = old.id)
        or exists(select 1 from saas_pairing_code where store_id = old.id)
        or exists(select 1 from saas_installation where store_id = old.id)
    ) then
        raise exception 'El regimen fiscal de una tienda con licencia no puede cambiar' using errcode = '23514';
    end if;
    return new;
end;
$$;
create trigger trg_preserve_licensed_store_tax_regime
before update of tax_regime on saas_store
for each row execute function preserve_licensed_store_tax_regime();
