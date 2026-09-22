-- Tenant identities are independent from their explicitly granted company/store access.
-- company_id on saas_tenant_user remains the historical originating company for compatibility.
create table saas_tenant_company_access (
    user_id uuid not null references saas_tenant_user(id),
    company_id uuid not null references saas_company(id),
    role_name varchar(40) not null check (role_name in ('OWNER', 'MANAGER', 'VIEWER', 'BILLING')),
    company_privileges text[] not null default '{}',
    updated_at timestamp with time zone not null default current_timestamp,
    primary key (user_id, company_id),
    check (company_privileges <@ array['READ_COMPANY','READ_BILLING','READ_MASTERS','WRITE_MASTERS','SUPPORT']::text[]),
    check (not ('WRITE_MASTERS' = any(company_privileges)) or role_name in ('OWNER','MANAGER')),
    check (not ('WRITE_MASTERS' = any(company_privileges)) or 'READ_MASTERS' = any(company_privileges))
);
create index idx_saas_tenant_company_access_company on saas_tenant_company_access(company_id, user_id);

create table saas_tenant_store_access (
    user_id uuid not null,
    company_id uuid not null,
    store_id uuid not null,
    granted_at timestamp with time zone not null default current_timestamp,
    primary key (user_id, company_id, store_id),
    foreign key (user_id, company_id) references saas_tenant_company_access(user_id, company_id) on delete cascade,
    foreign key (store_id, company_id) references saas_store(id, company_id)
);
create index idx_saas_tenant_store_access_store on saas_tenant_store_access(store_id, user_id);

-- Snapshot legacy permissions and current stores once. A future store is NEVER granted by a trigger.
insert into saas_tenant_company_access(user_id, company_id, role_name, company_privileges)
select id, company_id, role_name,
       case when role_name in ('OWNER','MANAGER')
            then array['READ_COMPANY','READ_BILLING','READ_MASTERS','WRITE_MASTERS','SUPPORT']::text[]
            else array['READ_COMPANY','READ_BILLING','READ_MASTERS','SUPPORT']::text[] end
from saas_tenant_user;
insert into saas_tenant_store_access(user_id, company_id, store_id)
select u.id, u.company_id, s.id from saas_tenant_user u join saas_store s on s.company_id = u.company_id;

-- Count a single account once in every company to which it has access.
drop trigger if exists trg_saas_tenant_user_plan_limit on saas_tenant_user;
create function enforce_saas_tenant_access_plan_limit() returns trigger language plpgsql as $$
declare used bigint; allowed bigint;
begin
    perform id from saas_tenant_user where id = new.user_id for update;
    if exists(select 1 from saas_tenant_company_access where user_id = new.user_id and company_id = new.company_id)
       or not exists(select 1 from saas_tenant_user where id = new.user_id and active = true) then
        return new;
    end if;
    perform pg_advisory_xact_lock(hashtextextended(new.company_id::text, 0));
    select count(*) into used from saas_tenant_company_access a join saas_tenant_user u on u.id = a.user_id
     where a.company_id = new.company_id and u.active = true;
    allowed := saas_plan_limit(new.company_id, 'max_tenant_users');
    if used >= allowed then raise exception 'Limite de plan alcanzado para usuarios cliente'; end if;
    return new;
end;
$$;
create trigger trg_saas_tenant_access_plan_limit before insert on saas_tenant_company_access
for each row execute function enforce_saas_tenant_access_plan_limit();

create function enforce_saas_tenant_reactivation_plan_limit() returns trigger language plpgsql as $$
declare company uuid; used bigint; allowed bigint;
begin
    if old.active = true or new.active = false then return new; end if;
    for company in select company_id from saas_tenant_company_access where user_id = new.id order by company_id loop
        perform pg_advisory_xact_lock(hashtextextended(company::text, 0));
        select count(*) into used from saas_tenant_company_access a join saas_tenant_user u on u.id = a.user_id
         where a.company_id = company and u.active = true and u.id <> new.id;
        allowed := saas_plan_limit(company, 'max_tenant_users');
        if used >= allowed then raise exception 'Limite de plan alcanzado para usuarios cliente'; end if;
    end loop;
    return new;
end;
$$;
create trigger trg_saas_tenant_reactivation_plan_limit before update of active on saas_tenant_user
for each row execute function enforce_saas_tenant_reactivation_plan_limit();
