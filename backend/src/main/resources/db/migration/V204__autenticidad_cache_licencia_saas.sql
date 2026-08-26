create or replace function prevent_authenticated_saas_license_cache_downgrade()
returns trigger
language plpgsql
as $$
begin
    if old.format_version = 5 and new.format_version <> 5 then
        raise exception 'una licencia SaaS autenticada no puede abandonar el formato 5';
    end if;
    return new;
end;
$$;

create trigger trg_licencia_saas_authenticated_format
before update of format_version on licencia
for each row
execute function prevent_authenticated_saas_license_cache_downgrade();
