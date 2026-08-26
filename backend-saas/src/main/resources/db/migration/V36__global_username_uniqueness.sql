-- A login identifier belongs to exactly one authentication realm.  The
-- preflight deliberately aborts instead of guessing which existing account
-- should keep a colliding identifier.
do $$
declare
    collision varchar(80);
begin
    select normalized_username
      into collision
      from (
          select lower(btrim(username)) as normalized_username
            from saas_admin_user
          union all
          select lower(btrim(username)) as normalized_username
            from saas_tenant_user
      ) usernames
     group by normalized_username
    having count(*) > 1
     order by normalized_username
     limit 1;

    if collision is not null then
        raise exception
            'No se puede aplicar V36: el usuario normalizado "%" esta repetido entre cuentas SaaS',
            collision
            using errcode = '23505';
    end if;
end
$$;

create table saas_global_username (
    normalized_username varchar(80) primary key,
    realm varchar(16) not null check (realm in ('admin', 'tenant')),
    user_id uuid not null unique
);

insert into saas_global_username(normalized_username, realm, user_id)
select lower(btrim(username)), 'admin', id
  from saas_admin_user
union all
select lower(btrim(username)), 'tenant', id
  from saas_tenant_user;

create unique index uk_saas_admin_user_username_normalized
    on saas_admin_user ((lower(btrim(username))));

create unique index uk_saas_tenant_user_username_normalized
    on saas_tenant_user ((lower(btrim(username))));

create or replace function enforce_saas_global_username()
returns trigger
language plpgsql
as $$
declare
    old_username varchar(80);
    new_username varchar(80);
    first_lock varchar(80);
    second_lock varchar(80);
begin
    old_username := case when tg_op in ('UPDATE', 'DELETE')
        then lower(btrim(old.username)) else null end;
    new_username := case when tg_op in ('INSERT', 'UPDATE')
        then lower(btrim(new.username)) else null end;

    if new_username is not null and new_username = '' then
        raise exception 'El nombre de usuario SaaS no puede estar vacio'
            using errcode = '23514';
    end if;

    -- Updates lock both identifiers in deterministic order.  This avoids
    -- deadlocks if two transactions try to exchange names.  The registry PK
    -- is the final concurrent uniqueness barrier; the advisory lock also
    -- serializes all writes for the same normalized identifier.
    if old_username is not null and new_username is not null
            and old_username <> new_username then
        first_lock := least(old_username, new_username);
        second_lock := greatest(old_username, new_username);
        perform pg_advisory_xact_lock(hashtextextended(first_lock, 2026082501));
        perform pg_advisory_xact_lock(hashtextextended(second_lock, 2026082501));
    else
        perform pg_advisory_xact_lock(hashtextextended(
            coalesce(new_username, old_username), 2026082501));
    end if;

    if tg_op = 'DELETE' then
        delete from saas_global_username
         where normalized_username = old_username
           and realm = tg_argv[0]
           and user_id = old.id;
        return old;
    end if;

    if tg_op = 'UPDATE' and old_username <> new_username then
        delete from saas_global_username
         where normalized_username = old_username
           and realm = tg_argv[0]
           and user_id = old.id;
    end if;

    if tg_op = 'INSERT' or old_username <> new_username then
        begin
            insert into saas_global_username(normalized_username, realm, user_id)
            values (new_username, tg_argv[0], new.id);
        exception
            when unique_violation then
                raise exception
                    'El nombre de usuario SaaS "%" ya pertenece a otra cuenta',
                    new_username
                    using errcode = '23505',
                          constraint = 'uk_saas_global_username';
        end;
    end if;

    return new;
end
$$;

create trigger trg_saas_admin_user_global_username
before insert or update of username or delete on saas_admin_user
for each row execute function enforce_saas_global_username('admin');

create trigger trg_saas_tenant_user_global_username
before insert or update of username or delete on saas_tenant_user
for each row execute function enforce_saas_global_username('tenant');
