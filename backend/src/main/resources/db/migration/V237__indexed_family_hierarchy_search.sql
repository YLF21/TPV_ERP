-- Global hierarchy search is store-scoped through the family join. Keep this
-- immutable function byte-for-byte aligned with CatalogText.searchTerm: NFD
-- followed by removal of the five Unicode combining-mark blocks used there.
create extension if not exists pg_trgm;

create or replace function tpv_catalog_search_normalize(value text)
returns text
language sql
immutable
parallel safe
as $$
    select regexp_replace(
        normalize(upper(value), NFD),
        U&'[\0300-\036f\1ab0-\1aff\1dc0-\1dff\20d0-\20ff\fe20-\fe2f]',
        '',
        'g'
    )
$$;

-- Two-character queries use prefixes; longer queries use the trigram index
-- for names and the same prefix index for commercial codes.
create index if not exists ix_familia_search_nombre_normalizado_trgm
    on familia using gin (
        (tpv_catalog_search_normalize(nombre)) gin_trgm_ops
    );

create index if not exists ix_familia_search_prefijo
    on familia (
        (tpv_catalog_search_normalize(nombre)) text_pattern_ops,
        tienda_id,
        id
    );

create index if not exists ix_familia_search_codigo_prefijo
    on familia (
        tienda_id,
        family_code text_pattern_ops,
        id
    );

create index if not exists ix_subfamilia_search_prefijo
    on subfamilia (
        (tpv_catalog_search_normalize(nombre)) text_pattern_ops,
        familia_id,
        subfamily_code,
        id
    );

create index if not exists ix_subfamilia_search_codigo_prefijo
    on subfamilia (
        subfamily_code text_pattern_ops,
        familia_id,
        id
    );

create index if not exists ix_subfamilia_search_nombre_normalizado_trgm
    on subfamilia using gin (
        (tpv_catalog_search_normalize(nombre)) gin_trgm_ops
    );
