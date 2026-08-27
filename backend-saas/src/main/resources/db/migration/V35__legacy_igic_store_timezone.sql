-- V28 did not have address data to infer the timezone of legacy stores.
-- For the legacy rows only, the company tax regime is the authoritative clue:
-- IGIC companies operate in the Canary Islands in the current company-scoped model.
update saas_store store
set time_zone_id = 'Atlantic/Canary'
from saas_company company
where company.id = store.company_id
  and company.tax_regime = 'IGIC'
  and store.store_address is null
  and store.time_zone_id = 'Europe/Madrid';
