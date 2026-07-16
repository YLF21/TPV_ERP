create extension if not exists pg_trgm;

create index if not exists ix_producto_stock_page_tienda_nombre
    on producto (tienda_id, lower(nombre), id);

create index if not exists ix_producto_stock_page_tipo
    on producto (tienda_id, product_type, lower(nombre), id);

create index if not exists ix_producto_stock_page_uso_precio
    on producto (tienda_id, price_use_mode, lower(nombre), id);

create index if not exists ix_producto_stock_page_descuento
    on producto (tienda_id, discount_type, lower(nombre), id);

create index if not exists ix_producto_stock_page_familia
    on producto (tienda_id, familia_id, lower(nombre), id);

create index if not exists ix_producto_stock_page_subfamilia
    on producto (tienda_id, subfamilia_id, lower(nombre), id)
    where subfamilia_id is not null;

create index if not exists ix_producto_stock_page_impuesto
    on producto (tienda_id, impuesto_id, lower(nombre), id);

create index if not exists ix_producto_stock_page_oferta_activa
    on producto (tienda_id, oferta_activa, lower(nombre), id);

create index if not exists ix_producto_stock_search_nombre_trgm
    on producto using gin (lower(nombre) gin_trgm_ops);

create index if not exists ix_producto_stock_search_descripcion_trgm
    on producto using gin (lower(descripcion) gin_trgm_ops)
    where descripcion is not null;

create index if not exists ix_producto_stock_search_comments_trgm
    on producto using gin (lower(comments) gin_trgm_ops)
    where comments is not null;

create index if not exists ix_producto_identificador_stock_search_valor_trgm
    on producto_identificador using gin (lower(valor) gin_trgm_ops);

create index if not exists ix_producto_identificador_producto
    on producto_identificador (producto_id);
