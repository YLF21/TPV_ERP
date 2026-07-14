# Cobro individual en APP VENTA

## Objetivo

Recuperar una interfaz de cobro directa y compacta en APP VENTA sin eliminar las garantías del motor actual de pagos. El operador verá tres acciones principales —Efectivo, Tarjeta y Pendiente cliente— en lugar del panel de cobro dividido.

## Alcance

- Sustituir la entrada visible de `SalePaymentCheckout` por tres botones de cobro individual.
- Abrir la calculadora de efectivo directamente desde el botón Efectivo.
- Iniciar el flujo configurado de tarjeta directamente desde el botón Tarjeta.
- Mostrar Pendiente cliente desactivado hasta diseñar su comportamiento contable.
- Conservar el diseño Business Classic y el motor persistente de sesiones y asignaciones.

No se implementará en esta fase el cobro pendiente de cliente ni se eliminará el soporte backend para pagos divididos.

## Interfaz principal

La sección Cobro mostrará tres botones grandes:

1. `Efectivo`, con acceso visual `F10`.
2. `Tarjeta`, con acceso visual `F11`.
3. `Pendiente cliente`, con acceso visual `F12`, inicialmente desactivado.

No se mostrará el título, importe editable, lista ni acciones propias de Cobro dividido durante una venta ordinaria. Si se recupera una sesión incierta, cubierta o que requiere compensación, la interfaz mostrará únicamente los controles necesarios para resolverla y evitar un segundo cargo.

## Cobro en efectivo

Al pulsar Efectivo se abrirá `CashPaymentDialog` con:

- Total de la venta.
- Dinero recibido.
- Cambio calculado en tiempo real.
- Teclado táctil visible al abrir la ventana.
- Atajos Exacto, 5 €, 10 €, 20 € y 50 €.
- Botón para alternar entre teclado táctil y teclado físico.
- Confirmación desactivada mientras el dinero recibido sea inferior al total.
- Cancelación mediante botón o Escape.
- Confirmación mediante botón o Enter cuando el importe sea suficiente.

El cambio a teclado físico afecta a la ventana abierta. La siguiente apertura vuelve a comenzar en modo táctil. Al confirmar se registra una única asignación `CASH` por el total del ticket; el dinero recibido y el cambio se conservan en el resultado mostrado al operador.

## Cobro con tarjeta

Al pulsar Tarjeta se creará o recuperará una sesión de pago y se añadirá una única asignación por el total pendiente:

- `INTEGRATED_CARD` cuando exista proveedor integrado activo.
- `MANUAL_CARD` cuando la configuración permita únicamente tarjeta manual; en este caso se solicitará una referencia obligatoria.

El flujo conservará:

- Clave de idempotencia estable.
- Prevención de cobros duplicados.
- Estado de procesamiento, aprobación, rechazo y error.
- Consulta de estado después de timeout o respuesta incierta.
- Reintento de finalización del ticket sin repetir el cargo.
- Gestión de anulación, devolución, impresión y compensación cuando corresponda.

Una aprobación que cubra el total finaliza automáticamente la venta, muestra el resultado y limpia el ticket anterior.

## Sesiones recuperadas y errores

- Una sesión activa reserva el ticket e impide cambiar líneas, cliente, cantidad o descuento.
- Un resultado incierto mantiene visible la consulta de estado y no habilita un nuevo cobro.
- Una sesión cubierta que no pudo generar el ticket ofrece reintentar la finalización.
- Una sesión con compensación mantiene visibles sus operaciones y las acciones administrativas necesarias.
- Los errores seguros anteriores al efecto liberan el intento; los errores inciertos conservan la identidad de la operación.

## Componentes

- `SalePaymentCheckout`: orquestación del estado persistente y presentación de los tres botones.
- `CashPaymentDialog`: captura táctil/física del efectivo.
- Diálogo de referencia manual: referencia efímera para tarjeta manual.
- `PaymentOperationPanel`: recuperación, anulación, devolución e impresión.
- `SalePaymentSessionService`: autoridad backend para asignaciones y finalización.

El panel de asignaciones divididas permanecerá disponible como componente interno y cubierto por pruebas, pero no formará parte del flujo ordinario de esta pantalla.

## Pruebas de aceptación

- Una venta nueva muestra Efectivo, Tarjeta y Pendiente cliente; no muestra Cobro dividido.
- Pendiente cliente está desactivado.
- Efectivo abre siempre con teclado táctil y permite cambiar al físico.
- La calculadora muestra total, recibido y cambio correctos.
- Confirmar efectivo genera un único ticket y limpia la venta.
- Tarjeta aprobada genera un único ticket y limpia la venta.
- Tarjeta rechazada conserva la venta y permite un nuevo intento seguro.
- Un timeout permite consultar la misma operación sin crear otro cargo.
- Una sesión recuperada mantiene bloqueado el ticket hasta su resolución.
- Las pruebas frontend, builds de Venta/Gestión y suite backend continúan pasando.

