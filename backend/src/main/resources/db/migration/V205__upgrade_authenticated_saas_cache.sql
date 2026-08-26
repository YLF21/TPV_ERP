create or replace function prevent_authenticated_saas_license_cache_downgrade()
returns trigger
language plpgsql
as $$
begin
    if old.format_version >= 5 and new.format_version < old.format_version then
        raise exception 'una licencia SaaS autenticada no puede reducir su formato';
    end if;
    return new;
end;
$$;

comment on function prevent_authenticated_saas_license_cache_downgrade() is
    'Permite actualizar de la MAC legacy v5 a v6 y bloquea cualquier downgrade posterior.';
