# Product Types, Discount Types, And Decimal Quantities Design

## Objetivo

Añadir configuración de tipo de producto, tipo de descuento y comentarios largos en productos. El tipo de producto debe afectar ya a cantidades de documentos y stock.

## Producto

Añadir `ProductType`:

- `UNIT`: producto unitario. Cantidad siempre entera.
- `SERVICE`: servicio. Cantidad decimal hasta 3 decimales. No controla stock ni genera movimientos.
- `WEIGHT`: producto a peso. Cantidad decimal hasta 3 decimales. Controla stock decimal.

Añadir `DiscountType`:

- `NONE`: no se aplica ningún descuento automático.
- `NORMAL`: se permiten descuentos posteriores.
- `MEMBER_DISCOUNT`: el frontend aplicará porcentaje según categoría de miembro.
- `MEMBER_PRICE`: el frontend aplicará precio miembro.
- `DISCOUNT_PRICE`: el frontend aplicará precio oferta/descuento.

Añadir `comments` opcional como texto largo.

Productos existentes migran con:

- `product_type = UNIT`
- `discount_type = NORMAL`
- `comments = null`

## Validaciones

- `DISCOUNT_PRICE` exige `offerPrice`, `offerActive = true` y `offerFrom`.
- `offerUntil` es opcional.
- `UNIT` no acepta cantidad decimal en líneas de documentos.
- `SERVICE` y `WEIGHT` aceptan cantidad decimal con escala máxima 3.
- Cantidades negativas siguen permitidas donde ya se permitían, también con decimales para `SERVICE` y `WEIGHT`.

## Documentos

Cambiar cantidades de líneas de documento de entero a decimal.

- `documento_linea.cantidad` usará escala 3.
- El cálculo de base, impuesto y total usará cantidad decimal.
- El backend validará el tipo del producto al crear/editar documentos.
- El backend no aplicará todavía precios automáticos según `DiscountType`; solo guarda la regla en producto.

## Stock

Cambiar cantidades de stock y movimientos a decimal con escala 3 donde afecte.

- `UNIT`: stock entero.
- `WEIGHT`: stock decimal.
- `SERVICE`: sin movimiento de stock.

Los documentos con servicios pueden confirmarse en todos los tipos documentales permitidos, pero esas líneas no moverán stock.

## Importación Excel

- Las cantidades importadas podrán tener hasta 3 decimales.
- Productos nuevos sin tipo explícito se crearán como `UNIT`.
- No se añade mapeo Excel para `productType` ni `discountType` en este bloque.

## Fuera De Alcance

- Motor backend de precios/descuentos automáticos.
- Descuentos por categoría de miembro.
- Historial de comentarios.
- Configuración de escala por producto.

## Pruebas

- Migración añade campos nuevos y cambia cantidades.
- Producto guarda y valida `productType`, `discountType`, `comments`.
- `DISCOUNT_PRICE` exige oferta activa, precio y fecha inicial.
- Documento rechaza cantidad decimal para `UNIT`.
- Documento acepta decimal para `SERVICE` y `WEIGHT`.
- `SERVICE` no genera movimiento de stock.
- Cálculos monetarios siguen redondeando a 2 decimales.
