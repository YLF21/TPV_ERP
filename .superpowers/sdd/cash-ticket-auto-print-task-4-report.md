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

- Ninguna bloqueante. El flujo directo `/pos/cash` queda accesible desde el control Efectivo/F10 y probado de extremo a extremo dentro de `SaleScreen`.

## Review fixes

- Hallazgo Important corregido: se añadió una integración real de `SaleScreen` que carga catálogo, añade una línea, acciona Efectivo/F10, obtiene quote y confirma mediante `POST /pos/cash` con snapshot autoritativo.
- El RED demostró un defecto real: `openCashDialog` no tenía caller y el control Efectivo/F10 no abría el flujo directo. El GREEN añade el callback opcional `SalePaymentCheckout.onCash`; `SaleScreen` lo conecta a `openCashDialog` y el checkout mantiene su fallback de sesión cuando no se proporciona.
- La prueba mantiene `hardware.printTicket` pendiente para observar `PRINTING`, resuelve a fallo para observar `FAILED` con `Finalizar` y reintento, y comprueba dos llamadas de hardware frente a una sola llamada `/pos/cash` y ninguna `/finalize`.
- El cierre antes de una resolución tardía ya queda cubierto por `ignores a late print result after the completed-payment dialog is closed`; la guardia contra alterar otro ticket está cubierta por `does not apply an old print result to a newer completed ticket`.
- Verificación de la corrección: `SaleScreen.test.tsx` 59/59; enfocadas de Tarea 4 157/157; suite frontend 55 archivos y 474/474; build APP VENTA PASS (TypeScript + Vite, 145 módulos).

## Review fixes 2

- Important RED: dos pulsaciones síncronas sobre Efectivo/F10 durante una quote pendiente producían 2 llamadas `/pos/cash/quote`. GREEN: `runGuardedCashOpening` aplica exclusión síncrona, `cashOpening` deshabilita efectivamente el control y la segunda activación no crea una segunda apertura ni una respuesta capaz de sobrescribir/reabrir estado.
- La prueba integrada usa una quote controlada: pulsa dos veces antes de resolverla, exige exactamente una petición y el botón deshabilitado, y sólo entonces libera la respuesta y continúa el flujo directo completo. Al no existir una segunda petición, el orden inverso de respuestas queda eliminado por construcción.
- Minor: se añadió una prueba real de `SalePaymentCheckout` con `onCash`. Una mutación temporal que ignoraba el callback produjo RED (0 llamadas esperadas 1); restaurado el contrato, el botón delega exactamente una vez, no abre el diálogo fallback y no crea sesión. Las pruebas existentes sin callback conservan la cobertura del fallback.
- La protección tardía posterior al resultado sigue cubierta por los casos de diálogo cerrado y ticket nuevo citados en Review fixes; no se añaden manejadores F10 globales, por lo que click/atajo visual comparten un único callback.
- Verificación de Review fixes 2: RED tardío confirmó reapertura tras finalización; GREEN dirigido 1/1 y callback dirigido 1/1; enfocadas finales de Tarea 4 159/159. Antes de añadir la invalidación de identidad, la suite frontend pasó 475/475 y el build APP VENTA pasó (TypeScript + Vite, 145 módulos); la ejecución completa posterior fue interrumpida y, por instrucción expresa, no se repitió.

## Review fixes 3

- RED: una quote invalidada por una finalización posterior rechazaba y publicaba el error obsoleto `stale quote failure` sobre el pago ganador.
- GREEN: el `catch` de `openCashDialog` sólo actualiza `cashStatus` cuando `opening.isCurrent()`; rechazos de generaciones invalidadas quedan ignorados igual que respuestas tardías exitosas.
- Cobertura: quote pendiente, `onFinalized` CARD ganador y rechazo tardío; el ticket ganador permanece visible, sin error obsoleto ni reapertura del diálogo de efectivo.
- Verificación: caso dirigido 1/1; enfocadas Task 4 160/160; suite frontend 55 archivos y 477/477; build APP VENTA PASS (TypeScript + Vite, 145 módulos).
