alter table saas_marketing_demo_request
    add column products varchar(24)[];

-- Preserve the full selection for new requests and the original choice for existing leads.
update saas_marketing_demo_request set products = array[product];

alter table saas_marketing_demo_request
    alter column products set not null,
    add constraint ck_marketing_demo_request_products check (
        array_ndims(products) = 1
        and array_lower(products, 1) = 1
        and cardinality(products) between 1 and 4
        and array_position(products, null) is null
        and products <@ array['APP_VENTA', 'APP_GESTION', 'APP_PDA', 'APP_SAAS']::varchar[]
        and cardinality(products) =
            (case when 'APP_VENTA' = any(products) then 1 else 0 end)
          + (case when 'APP_GESTION' = any(products) then 1 else 0 end)
          + (case when 'APP_PDA' = any(products) then 1 else 0 end)
          + (case when 'APP_SAAS' = any(products) then 1 else 0 end)
        and product = products[1]
    );
