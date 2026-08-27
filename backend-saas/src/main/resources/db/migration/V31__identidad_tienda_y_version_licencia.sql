alter table saas_store
    add column time_zone_id varchar(64);

update saas_store
set time_zone_id = case
    when lower(coalesce(store_address ->> 'provincia', '')) in (
        'las palmas', 'santa cruz de tenerife'
    ) then 'Atlantic/Canary'
    else 'Europe/Madrid'
end;

alter table saas_store
    alter column time_zone_id set not null,
    alter column time_zone_id set default 'Europe/Madrid';

with invalid_store as (
    select id,
           company_id,
           row_number() over (partition by company_id order by created_at, id) as position
    from saas_store
    where code !~ '^[0-9]{3}$' or code = '000'
), available_code as (
    select company.id as company_id,
           lpad(candidate.number::text, 3, '0') as code,
           row_number() over (partition by company.id order by candidate.number) as position
    from saas_company company
    cross join generate_series(1, 999) candidate(number)
    where not exists (
        select 1
        from saas_store reserved
        where reserved.company_id = company.id
          and reserved.code = lpad(candidate.number::text, 3, '0')
    )
), mapping as (
    select invalid_store.id, available_code.code
    from invalid_store
    join available_code
      on available_code.company_id = invalid_store.company_id
     and available_code.position = invalid_store.position
)
update saas_store store
set code = mapping.code
from mapping
where store.id = mapping.id;

alter table saas_store
    add constraint ck_saas_store_code_three_digits
        check (code ~ '^[0-9]{3}$' and code <> '000');

alter table saas_license
    add column license_version bigint not null default 1,
    add constraint ck_saas_license_version_positive check (license_version >= 1);
