ALTER TABLE entrada_almacen_linea
    ADD COLUMN nombre_producto VARCHAR(255);

UPDATE entrada_almacen_linea linea
SET nombre_producto = COALESCE(NULLIF(BTRIM(producto.nombre), ''), linea.producto_id::text)
FROM producto
WHERE producto.id = linea.producto_id;

UPDATE entrada_almacen_linea
SET nombre_producto = producto_id::text
WHERE nombre_producto IS NULL OR BTRIM(nombre_producto) = '';

ALTER TABLE entrada_almacen_linea
    ALTER COLUMN nombre_producto SET NOT NULL;
