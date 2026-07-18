# Contrato fiscal para ventas pendientes de cliente

## Objetivo

Corregir la valoración de albaranes y facturas pendientes cuando el catálogo público de productos solo entrega `taxId`. La pantalla de venta debe enviar el porcentaje y el régimen fiscal reales del producto, sin valores inventados ni relajación de las validaciones fiscales del backend.

## Causa raíz

`SaleScreen` construye el borrador pendiente a partir de `/products`. El tipo del frontend admite `taxPercentage` y `taxRegime`, pero `ProductView` no publica esos campos. Como consecuencia, el frontend sustituye los valores ausentes por `0.00` y `GENERAL`. El backend contrasta el porcentaje recibido con `impuesto_tienda` y rechaza correctamente el borrador de un producto con IVA del 21 %.

## Diseño aprobado

El backend enriquecerá el contrato público del producto con:

- `taxPercentage`, resuelto desde el `taxId` del producto.
- `taxRegime`, resuelto desde la configuración fiscal activa de la tienda/licencia (`IVA` o `IGIC`).

La pantalla de venta consumirá estos campos y eliminará los valores fiscales de reserva `0.00` y `GENERAL`. Si el catálogo no proporciona una fotografía fiscal válida, la creación de la venta pendiente fallará de forma visible antes de enviar una petición incorrecta.

La validación autoritativa de `PromotionCatalogGateway` seguirá comprobando que impuestos incluidos, porcentaje y régimen coinciden con la configuración fiscal del servidor.

## Flujo de datos

1. El frontend solicita `/api/v1/products`.
2. El backend obtiene el producto, su impuesto asociado y el régimen fiscal activo.
3. La respuesta incluye `taxId`, `taxPercentage`, `taxRegime` y `taxesIncluded`.
4. `pendingSaleDraftForCustomer` copia esa fotografía fiscal al borrador.
5. El endpoint de valoración valida la fotografía contra el catálogo y calcula el total.
6. El diálogo muestra total, pagado y pendiente y habilita las formas de pago.

## Tratamiento de errores

- Un producto sin impuesto asociado o sin régimen fiscal válido no se convierte silenciosamente en 0 %.
- El frontend muestra un error de catálogo y no permite confirmar el documento.
- El backend conserva sus validaciones y continúa rechazando fotografías fiscales manipuladas u obsoletas.

## Pruebas

- Contrato backend: el producto público incluye el porcentaje y el régimen fiscal configurados.
- Frontend: el catálogo real con `taxId` genera un borrador con el porcentaje y régimen recibidos.
- Regresión: no se emiten `0.00` ni `GENERAL` cuando los campos fiscales faltan.
- Integración focalizada: un producto IVA 21 % puede valorarse como venta pendiente sin el error `porcentajeImpuesto no coincide con el catalogo del producto`.

## Fuera de alcance

- Modificar las reglas de descuento de socio.
- Relajar la validación fiscal del backend.
- Cambiar el flujo de cobro o los estados de deuda del cliente.
