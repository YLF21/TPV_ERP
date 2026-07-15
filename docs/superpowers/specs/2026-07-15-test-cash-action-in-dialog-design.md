# Acción de caja de prueba dentro del diálogo de efectivo

## Problema

Cuando la finalización del cobro en efectivo falla porque no existe una sesión de caja abierta, el diálogo de efectivo continúa abierto. La acción `Abrir caja de prueba` se renderiza en el panel de cobro situado detrás del overlay modal, por lo que es visible pero no se puede pulsar.

## Diseño aprobado

- Mantener abierto el diálogo de cobro en efectivo y conservar el importe recibido.
- Mostrar `Abrir caja de prueba` dentro del propio diálogo cuando el servidor indique que falta una sesión de caja y la función de prueba esté habilitada.
- Colocar la acción junto al mensaje de error, antes del pie con `Cancelar` y `Confirmar cobro`.
- Mientras se abre la caja, deshabilitar las acciones del diálogo y mostrar el estado de carga existente.
- Si la apertura tiene éxito, limpiar el error de sesión, mostrar la confirmación y permitir que el usuario pulse de nuevo `Confirmar cobro`.
- Si falla, mantener la acción disponible para reintentar y mostrar el error devuelto.
- La acción seguirá limitada a APP VENTA en desarrollo; APP GESTIÓN y producción no cambian.
- El panel lateral podrá conservar la misma acción como recuperación cuando el diálogo ya no esté abierto, pero nunca será la única acción accesible durante un modal.

## Componentes

- `CashPaymentDialog` recibirá de forma opcional el estado y la acción de caja de prueba.
- `SalePaymentCheckout` determinará cuándo ofrecerla y pasará esos datos al diálogo.
- Se reutilizará la petición existente `POST /cash/sessions/open`; no se crearán endpoints ni lógica backend nueva.

## Pruebas

- Una prueba de regresión reproducirá el cobro cubierto cuyo cierre falla por falta de caja y comprobará que `Abrir caja de prueba` está dentro del diálogo activo.
- La prueba abrirá la caja desde el diálogo y comprobará que el intento de pago no se duplica ni se finaliza automáticamente.
- Las pruebas existentes cubrirán el reintento, los errores y la visibilidad exclusiva en desarrollo.

