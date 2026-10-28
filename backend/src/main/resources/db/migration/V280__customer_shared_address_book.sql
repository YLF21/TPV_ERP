-- Preserve the local default exactly; the first explicit refresh reconciles it with the central book.
alter table cliente
    add column addresses_json jsonb not null default '[]'::jsonb,
    add column saas_address_revision bigint,
    add column address_sync_status varchar(16) not null default 'LOCAL',
    add column address_operation_id uuid;

update cliente set addresses_json = jsonb_build_array(jsonb_build_object(
    'id', id, 'address', jsonb_build_object('address', direccion, 'postalCode', codigo_postal,
        'city', poblacion, 'province', provincia, 'country', pais),
    'phone', telefono, 'email', email, 'notes', observaciones));

alter table cliente add constraint ck_cliente_address_book
    check (jsonb_typeof(addresses_json) = 'array' and jsonb_array_length(addresses_json) between 1 and 50),
    add constraint ck_cliente_address_revision check (saas_address_revision is null or saas_address_revision > 0),
    add constraint ck_cliente_address_status check (address_sync_status in ('LOCAL','SYNCED','PENDING','CONFLICT'));

-- Legacy inserts omit the new column. Give them a complete default without changing their credit policy.
create function cliente_initial_address_book() returns trigger language plpgsql as $$
begin
    if new.addresses_json = '[]'::jsonb then
        new.addresses_json := jsonb_build_array(jsonb_build_object(
            'id', new.id, 'address', jsonb_build_object('address', new.direccion, 'postalCode', new.codigo_postal,
                'city', new.poblacion, 'province', new.provincia, 'country', new.pais),
            'phone', new.telefono, 'email', new.email, 'notes', new.observaciones));
    end if;
    return new;
end;
$$;
create trigger trg_cliente_initial_address_book before insert on cliente
    for each row execute function cliente_initial_address_book();
