# Descuento de socio en la pantalla de venta

## Objetivo

Aplicar inmediatamente el beneficio de categoría del socio al seleccionar un cliente en `SaleScreen`, respetando la regla de dominio existente: solo los productos configurados como `MEMBER_DISCOUNT` reciben el porcentaje de categoría y los productos normales no cambian.

## Datos

`GET /api/v1/customers/sale-options` devolverá, además de la identificación mínima del cliente:

- si tiene una afiliación activa;
- nombre de la categoría activa;
- porcentaje habilitado de descuento de categoría, o cero cuando no corresponda.

El endpoint no expondrá datos personales adicionales. La proyección de productos utilizada por venta incluirá `discountType`; el precio de socio queda fuera de este cambio porque el objetivo solicitado es `MEMBER_DISCOUNT`.

## Cálculo

Cada línea conservará dos valores independientes:

- `manualDiscountPercent`, introducido por el operador;
- `memberDiscountPercent`, calculado desde cliente y producto.

El descuento efectivo será el máximo de ambos, igual que `MemberLoyaltyService.applyLineBenefit`. Nunca se sumarán porcentajes. Al cambiar o quitar cliente se recalcula únicamente el componente de socio y se conserva el manual.

Al seleccionar un cliente:

1. Si no es socio activo, todas las líneas quedan con descuento de socio cero.
2. Si es socio activo pero la categoría no habilita descuento, se aplica cero.
3. Si el producto no es `MEMBER_DISCOUNT`, se aplica cero.
4. Si producto y categoría cumplen la regla, se aplica el porcentaje de categoría.
5. Los productos añadidos posteriormente reciben la misma regla.

La línea mostrará una indicación `Socio X%` cuando el beneficio de socio sea el descuento efectivo. El total se recalculará inmediatamente.

## Persistencia de demostración

Se crearán productos demo nuevos, sin modificar los existentes, con códigos y códigos de barras únicos, precios diferentes y `discount_type = MEMBER_DISCOUNT`. Se asociarán a la tienda, impuesto y almacén existentes siguiendo el modelo actual de catálogo.

La inserción será idempotente por código para poder repetirla sin duplicados. Los clientes Básico, Bronce, Plata y Oro ya existentes permitirán comprobar 0%, 5%, 10% y 15% respectivamente.

## Seguridad y autoridad

La pantalla ofrece una previsualización inmediata. El flujo de ticket real seguirá recalculando el beneficio mediante la lógica autoritativa de backend; el navegador no será la autoridad contable del descuento.

## Pruebas

- DTO de venta devuelve categoría y descuento solo para afiliación activa/habilitada.
- Seleccionar Bronce aplica 5% únicamente a `MEMBER_DISCOUNT`.
- Seleccionar Oro sustituye el componente de socio por 15%.
- Cambiar a Básico o quitar cliente elimina solo el componente de socio.
- Un descuento manual superior prevalece y se conserva.
- Un descuento manual inferior deja visible el beneficio de socio.
- Añadir un producto después de seleccionar cliente aplica la regla.
- Producto normal no cambia.
- Script/operación de datos crea productos demo una sola vez.

## Criterios de aceptación

Al seleccionar cualquiera de los clientes demo, el ticket recalcula de inmediato los productos `MEMBER_DISCOUNT`, muestra el origen socio y mantiene intactos los descuentos manuales. Los productos demo pueden localizarse por código o nombre y la operación puede repetirse sin crear duplicados.
