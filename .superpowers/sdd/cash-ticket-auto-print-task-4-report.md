# Tarea 4 — Estados, reintento y ambos flujos de cobro

## Estado

Implementación completada en el worktree `cash-ticket-auto-print`. El resultado de pago se muestra antes de esperar al hardware; efectivo directo y sesiones CASH/MIXED imprimen automáticamente; CARD puro conserva el flujo sin esta impresión.

## Implementación

- `CashPaymentResultDialog` consume `locale`, `printStatus` y `onRetryPrint`; muestra `PRINTING`, `PRINTED`, `FAILED` y omite contenido para `SKIPPED`.
- El fallo de hardware mantiene simultáneamente el cobro completado, el reintento y `Finalizar`; los textos usan las claves i18n existentes.
- `SalePaymentCheckout` exige `printTicket` autoritativo junto a `ticketNumber` antes de limpiar almacenamiento/estado y emite el snapshot completo con el resumen del método.
- Una respuesta finalizada sin snapshot conserva la sesión COVERED recuperable y muestra el error de finalización.
- `SaleScreen` abre el resultado en `PRINTING` y lanza después el orquestador para cobro directo y sesiones CASH/MIXED. CARD usa `SKIPPED`.
- El reintento llama sólo a `retryConfirmedTicketPrint`; no repite cobro/finalización.
- Toda resolución asíncrona compara `documentId`; no reabre un diálogo cerrado ni altera un ticket posterior.
- CSS compacto añadido para estado, alerta roja y botón secundario sin cambiar dimensiones principales.

## TDD por comportamiento

1. Diálogo RED: 3 fallos por ausencia de roles/mensajes/reintento; GREEN: 10/10. CSS RED por ausencia de reglas; GREEN posterior.
2. Checkout RED: callbacks aún enviaban número y el caso sin snapshot limpiaba estado (10 fallos esperados); GREEN: snapshot completo y sesión recuperable.
3. SaleScreen RED: snapshot renderizado como hijo React y ausencia del orquestador (5 fallos esperados); GREEN: estados, CARD omitido, CASH/MIXED, fallo/reintento y carreras tardías.
4. Cobro directo RED: helper de transición inexistente; GREEN: snapshot autoritativo + `PRINTING`.
5. Locale RED: feedback inglés seguía en español; GREEN: traducción mediante `createTranslator`.

## Comandos y salidas

- `npm.cmd test -- packages/app-common/src/components/CashPaymentResultDialog.test.tsx`: RED 3/10; luego GREEN.
- `npm.cmd test -- packages/app-common/src/components/SalePaymentCheckout.test.ts`: RED 10/82; luego 82/82.
- `npm.cmd test -- packages/app-common/src/components/SaleScreen.test.tsx`: RED 5/55; transición directa RED 1/58.
- Enfocadas finales: 4 archivos, 156 tests PASS.
- Suite frontend final: 55 archivos, 473 tests PASS.
- `npm.cmd run build --workspace @tpverp/app-venta`: PASS, TypeScript + Vite, 145 módulos.
- `git diff --check`: sin errores; sólo avisos informativos de normalización LF/CRLF.

## Archivos

- `frontend/packages/app-common/src/components/CashPaymentResultDialog.tsx`
- `frontend/packages/app-common/src/components/CashPaymentResultDialog.test.tsx`
- `frontend/packages/app-common/src/components/SalePaymentCheckout.tsx`
- `frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts`
- `frontend/packages/app-common/src/components/SaleScreen.tsx`
- `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- `frontend/packages/app-common/src/styles/tpv.css`

## Self-review

- Contratos comprobados contra el brief: snapshot antes de limpiar; resultado antes de hardware; CASH/MIXED sí, CARD no; reintento sólo hardware; guardia por `documentId`; `Finalizar` permanece activo y `autoFocus`.
- No se modificaron informes históricos ni archivos fuera del alcance, salvo este informe obligatorio.
- Se reutilizaron `printConfirmedTicketAutomatically` y `retryConfirmedTicketPrint`; no se duplicó lógica de ruta/copias.

## Preocupaciones

- Ninguna bloqueante. El flujo directo `/pos/cash` conserva su integración existente y ahora tiene una transición explícita y probada al snapshot autoritativo; su activación de UI sigue dependiendo del cableado preexistente de esa ruta.
