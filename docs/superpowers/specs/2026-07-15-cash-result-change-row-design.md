# Fila de cambio y etiquetas destacadas en el resultado de efectivo

## Problema

El flujo de efectivo finalizado mediante `SalePaymentCheckout` conserva el total y el dinero recibido, pero `cashResultFromFinalization` no genera `changeCents`. Como `CashPaymentResultDialog` solo renderiza la fila cuando ese valor existe, el resultado omite `Cambio` aunque el cliente haya entregado un importe superior.

Además, las etiquetas `Total`, `Dinero recibido` y `Cambio` tienen menos peso visual que sus importes.

## Diseño aprobado

- `cashResultFromFinalization` calculará `changeCents` como `Math.max(0, receivedCents - totalCents)`.
- Si `receivedCents` no viene informado, se seguirá usando `totalCents`; por tanto, el cambio será `0`.
- Todo resultado de efectivo mostrará siempre `Total`, `Dinero recibido` y `Cambio`, incluido el pago exacto con `Cambio 0,00`.
- Los pagos con tarjeta conservarán su presentación actual y no mostrarán `Dinero recibido` ni `Cambio`.
- Las etiquetas de las tres filas de efectivo se mostrarán en negrita mediante una regla limitada a `.cash-payment-result-dialog .cash-payment-summary span`.
- Los importes continuarán usando elementos `strong`, tamaño de `16px` y cifras tabulares.
- La fila `Cambio` conservará el fondo verde suave existente.

## Flujo de datos

1. `SalePaymentCheckout` entrega `ticketNumber`, `authoritativeTotalCents` y el efectivo recibido a `SaleScreen`.
2. `cashResultFromFinalization` normaliza el recibido y calcula el cambio no negativo.
3. `SaleScreen` guarda el resultado completo en `cashResult`.
4. `CashPaymentResultDialog` recibe `changeCents` y renderiza la fila existente sin lógica aritmética adicional.

## Alcance

- Modificar la construcción del resultado de efectivo en `SaleScreen.tsx`.
- Reforzar las etiquetas en `tpv.css`.
- No modificar contratos backend, endpoints, finalización de pagos ni componentes de tarjeta.

## Pruebas

- Probar que `cashResultFromFinalization` calcula cambio positivo.
- Probar que el pago exacto genera `changeCents: 0` y conserva la fila.
- Mantener la prueba que confirma que la tarjeta no muestra filas exclusivas de efectivo.
- Añadir un contrato CSS para el peso de las etiquetas.
- Ejecutar pruebas enfocadas, suite frontend completa y builds de APP GESTIÓN y APP VENTA.

