# Calculadora de efectivo y ticket real

## Objetivo

Permitir que el operador cobre una venta completa en efectivo desde `SaleScreen`, calcule el cambio mediante una calculadora táctil y cree un ticket real con su pago y movimiento de caja.

## Flujo de usuario

El botón `Efectivo` estará deshabilitado cuando no existan líneas o el total no sea positivo. Al pulsarlo se abrirá un diálogo modal con:

- total autoritativo de la venta;
- importe recibido editable;
- cambio calculado, nunca negativo;
- teclado numérico `0-9`, separador decimal, borrar y limpiar;
- atajos `Exacto`, `5 €`, `10 €`, `20 €` y `50 €`;
- acciones `Cancelar` y `Confirmar cobro`.

Los atajos de billete fijan el recibido al importe elegido; `Exacto` lo iguala al total. Solo se admiten dos decimales y `Confirmar cobro` permanece deshabilitado mientras el recibido sea inferior al total o haya una petición activa.

## Backend autoritativo

Se añadirá una operación POS específica para efectivo. La solicitud contendrá una clave idempotente de checkout, cliente opcional, líneas mínimas (`productId`, cantidad y descuento manual) y dinero recibido. El backend derivará tienda, terminal, almacén predeterminado activo, fecha local, producto, precio, fiscalidad, beneficio de socio y promociones.

El backend calculará el total definitivo. Si el recibido no lo cubre, rechazará la operación sin crear documento. Si lo cubre:

1. resolverá el método activo de tipo/nombre protegido `EFECTIVO`;
2. construirá el ticket y su pago principal por el total;
3. guardará `entregado` y `cambio`;
4. confirmará stock y ticket en la transacción existente;
5. registrará el movimiento de caja mediante `CashPaymentRecorder`.

La respuesta incluirá identificador, número, total, entregado y cambio. Una repetición del mismo checkout y payload devolverá el mismo ticket; reutilizar la clave con contenido distinto producirá conflicto.

## Consistencia visual

Antes de abrir la calculadora, el backend devolverá una cotización del total para evitar cobrar sobre un importe calculado únicamente por el navegador. Mientras el modal esté abierto se congelan líneas, cliente y descuentos. La creación del ticket consume esa cotización.

## Errores

- Sin almacén predeterminado: indicar que debe configurarse.
- Sin método EFECTIVO activo: indicar que debe habilitarse.
- Sin sesión de caja abierta: indicar que debe abrirse caja.
- Cotización caducada antes de confirmar: recotizar antes de aceptar dinero; si ya comenzó la confirmación, conservar checkout y consultar el resultado.
- Error de red o backend: conservar ticket, cliente, recibido y checkout para reintentar.
- Conflicto de idempotencia: no crear otro ticket y mostrar error operativo.

## Accesibilidad

El modal tendrá foco inicial en el importe, navegación por teclado, Escape para cerrar solo antes de confirmar, restauración de foco al botón Efectivo y anuncios `aria-live` para recibido, cambio y estado. Los botones del teclado tendrán nombres accesibles.

## Pruebas

- Aritmética en céntimos, separador decimal, borrar y límites de dos decimales.
- Atajos Exacto/5/10/20/50 y cambio correcto.
- Confirmación bloqueada con importe insuficiente y durante petición.
- Cotización autoritativa con cliente socio y productos `MEMBER_DISCOUNT`.
- Creación real con pago EFECTIVO, entregado, cambio, stock y movimiento de caja.
- Caja cerrada, método inactivo, almacén ausente y errores de red conservan estado.
- Doble clic y repetición tras pérdida de respuesta producen un único ticket.
- Éxito limpia la venta únicamente después de respuesta confirmada.

## Criterios de aceptación

Con una venta válida, el operador puede abrir la calculadora, introducir o seleccionar el efectivo recibido, visualizar el cambio y confirmar. PostgreSQL contiene un solo ticket confirmado, un pago EFECTIVO con total/entregado/cambio y un único movimiento de caja. La pantalla muestra número y cambio y comienza una venta vacía.
