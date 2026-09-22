alter table saas_store
    add column internal_code varchar(7),
    add column active boolean not null default true,
    add constraint uk_saas_store_internal_code unique (internal_code),
    add constraint ck_saas_store_internal_code check (
        internal_code is null or (internal_code ~ '^(0[1-9]|[1-4][0-9]|5[0-2])[0-9]{5}$' and right(internal_code, 5) <> '00000')),
    add constraint uk_saas_store_company_id unique (company_id, id);

create table saas_store_code_counter (
    postal_prefix varchar(2) primary key check (postal_prefix ~ '^(0[1-9]|[1-4][0-9]|5[0-2])$'),
    last_number integer not null check (last_number between 1 and 99999)
);

-- Allocate centrally for every provisioning path, including older admin clients.
-- Overseas or incomplete addresses remain explicitly unassigned.
create function saas_assign_store_internal_code() returns trigger language plpgsql as $$
declare
    prefix text;
    allocated integer;
begin
    if TG_OP = 'UPDATE' and old.internal_code is not null then
        if new.internal_code is distinct from old.internal_code then
            raise exception 'El codigo interno de tienda es permanente' using errcode = '23514';
        end if;
        return new;
    end if;
    if upper(btrim(coalesce(new.store_address ->> 'pais', ''))) = 'ES'
       and btrim(coalesce(new.store_address ->> 'codigoPostal', '')) ~ '^(0[1-9]|[1-4][0-9]|5[0-2])[0-9]{3}$' then
        prefix := left(btrim(new.store_address ->> 'codigoPostal'), 2);
    end if;
    if new.internal_code is not null then
        if prefix is null or new.internal_code !~ '^[0-9]{7}$'
           or left(new.internal_code, 2) <> prefix or right(new.internal_code, 5) = '00000' then
            raise exception 'Codigo interno incompatible con el codigo postal de la tienda' using errcode = '23514';
        end if;
        perform pg_advisory_xact_lock(hashtextextended(new.company_id::text, 0));
        insert into saas_store_code_counter(postal_prefix, last_number)
        values (prefix, right(new.internal_code, 5)::integer)
        on conflict (postal_prefix) do update
        set last_number = greatest(saas_store_code_counter.last_number, excluded.last_number);
    elsif prefix is not null then
        perform pg_advisory_xact_lock(hashtextextended(new.company_id::text, 0));
        insert into saas_store_code_counter(postal_prefix, last_number) values (prefix, 1)
        on conflict (postal_prefix) do update
        set last_number = saas_store_code_counter.last_number + 1
        where saas_store_code_counter.last_number < 99999
        returning last_number into allocated;
        if allocated is null then
            raise exception 'Numeracion interna agotada para el prefijo postal %', prefix using errcode = '23514';
        end if;
        new.internal_code := prefix || lpad(allocated::text, 5, '0');
    end if;
    return new;
end;
$$;

create trigger trg_saas_store_internal_code
before insert or update of internal_code, store_address on saas_store
for each row execute function saas_assign_store_internal_code();

do $$
declare existing_store record;
begin
    for existing_store in select id from saas_store order by created_at, id loop
        update saas_store set internal_code = internal_code where id = existing_store.id;
    end loop;
end;
$$;

alter table saas_license add column store_id uuid;
alter table saas_license add constraint fk_saas_license_company_store
    foreign key (company_id, store_id) references saas_store(company_id, id);

-- Preserve historical licences that span multiple stores: they retain a null
-- direct store, and their linked stores are listed from the original records.
with links as (
    select license_id, store_id from saas_pairing_code
    union select license_id, store_id from saas_installation
), unambiguous as (
    select license_id, min(store_id::text)::uuid as store_id
    from links group by license_id having count(distinct store_id) = 1
)
update saas_license license set store_id = link.store_id
from unambiguous link join saas_store store on store.id = link.store_id
where license.id = link.license_id and license.company_id = store.company_id;

create index idx_saas_store_active_company on saas_store(active, company_id, internal_code);
create index idx_saas_license_store on saas_license(store_id);
create index idx_saas_license_expiry on saas_license(valid_until, id);
create index idx_saas_installation_store_active on saas_installation(store_id, active);
create index idx_saas_installation_license_active_validation on saas_installation(license_id, active, last_validated_at desc);
create index idx_saas_sync_installation_received on saas_sync_event(installation_id, received_at desc);
