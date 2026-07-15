# Fila de cambio y etiquetas destacadas en el resultado de efectivo

## Problema

El flujo de efectivo finalizado mediante `SalePaymentCheckout` conserva el total y el dinero recibido, pero `cashResultFromFinalization` no genera `changeCents`. Como `CashPaymentResultDialog` solo renderiza la fila cuando ese valor existe, el resultado omite `Cambio` aunque el cliente haya entregado un importe superior.

Además, las etiquetas `Total`, `Dinero recibido` y `Cambio` tienen menos peso visual que sus importes.

## Diseño aprobado

- `cashResultFromFinalization` calculará `changeCents` como `Math.max(0, receivedCents - totalCents)` y solo representará finalizaciones de efectivo.
- `SalePaymentCheckout` emitirá un `PaymentFinalizationSummary` discriminado por `kind: "CASH" | "CARD" | "MIXED"`; no se inferirá el método por la ausencia de otros campos.
- El productor derivará `kind` de las allocations `APPROVED` de la sesión finalizada: solo `CASH` produce `CASH`, solo `MANUAL_CARD`/`INTEGRATED_CARD` produce `CARD` y la combinación produce `MIXED`.
- Solo el resumen `CASH` contendrá `receivedCents`. El teclado conservará el recibido del intento local; sin intento local se limitará a la suma de efectivo autorizado o al total, sin inventar sobrepago, por lo que el cambio será `0`.
- Todo resultado de efectivo mostrará siempre `Total`, `Dinero recibido` y `Cambio`, incluido el pago exacto con `Cambio 0,00`.
- Los pagos con tarjeta conservarán su presentación actual y no mostrarán `Dinero recibido` ni `Cambio`.
- Los pagos mixtos mostrarán el método `Mixto` y tampoco mostrarán `Dinero recibido` ni `Cambio`.
- Las etiquetas de las tres filas de efectivo se mostrarán en negrita mediante una regla limitada a `.cash-payment-result-dialog .cash-payment-summary span`.
- Los importes continuarán usando elementos `strong`, tamaño de `16px` y cifras tabulares.
- La fila `Cambio` conservará el fondo verde suave existente.

## Flujo de datos

1. `SalePaymentCheckout` inspecciona las allocations `APPROVED` de la sesión finalizada y construye un `PaymentFinalizationSummary` explícito.
2. `SalePaymentCheckout.onFinalized` entrega `ticketNumber` y el resumen discriminado a `SaleScreen`.
3. `paymentResultFromFinalization` consume `summary.kind`: delega `CASH` a `cashResultFromFinalization`, traduce `CARD` a `Tarjeta` y `MIXED` a `Mixto`.
4. `cashResultFromFinalization` conserva el recibido y calcula el cambio no negativo para efectivo.
5. `SaleScreen` guarda el resultado completo en `cashResult`.
6. `CashPaymentResultDialog` recibe el resultado y renderiza las filas presentes sin lógica aritmética adicional.

## Alcance

- Modificar el resumen emitido por `SalePaymentCheckout` y su consumo en `SaleScreen.tsx`.
- Reforzar las etiquetas en `tpv.css`.
- No modificar contratos backend, endpoints, finalización de pagos ni componentes de tarjeta.

## Pruebas

- Probar que `cashResultFromFinalization` calcula cambio positivo.
- Probar que el pago exacto genera `changeCents: 0` y conserva la fila.
- Probar en el productor CASH desde `PaymentAllocationPanel` sin intento local, CARD y MIXED a partir de allocations efectivas.
- Probar el boundary real `SalePaymentCheckout.onFinalized -> SaleScreen` para los tres `kind`, incluido `Mixto` sin filas exclusivas de efectivo.
- Mantener la prueba aislada que confirma que la tarjeta no muestra filas exclusivas de efectivo.
- Añadir un contrato CSS para el peso de las etiquetas.
- Ejecutar pruebas enfocadas, suite frontend completa y builds de APP GESTIÓN y APP VENTA.
