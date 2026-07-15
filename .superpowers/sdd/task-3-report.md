# Task 3 report: PageDown para efectivo y etiquetas AvPág

## Alcance implementado

- El atajo global de efectivo usa `KeyboardEvent.key === "PageDown"`.
- `F10` deja de invocar `triggerCash()`.
- La protección existente frente a eventos repetidos y diálogos incompatibles se conserva.
- Las etiquetas visibles de efectivo muestran `AvPág` tanto en el botón como en la barra inferior.
- No se renombró la API interna `triggerCash`.

## Evidencia TDD

### RED inicial

Comando:

`npm.cmd test -- SaleScreen.test.tsx SalePaymentCheckout.test.ts`

Resultado esperado y observado: exit 1; 8 fallos y 146 pruebas aprobadas. Los fallos demostraron que `PageDown` todavía no invocaba efectivo y que producción todavía renderizaba `F10`.

### GREEN focused

Comando:

`npm.cmd test -- SaleScreen.test.tsx SalePaymentCheckout.test.ts`

Resultado: exit 0; 2 archivos y 154/154 pruebas aprobadas.

### RED adicional de suite completa

Comando:

`npm.cmd test`

Resultado: exit 1; 1 fallo y 519/520 pruebas aprobadas. `IndividualPaymentActions.test.tsx` conservaba la expectativa visible `F10`; se actualizó a `AvPág`.

### GREEN del componente y suite completa

- `npm.cmd test -- IndividualPaymentActions.test.tsx`: exit 0; 6/6 pruebas aprobadas.
- `npm.cmd test`: exit 0; 59 archivos y 520/520 pruebas aprobadas.

## Auto-revisión

- Comportamiento de ratón preservado: no se modificaron `onClick` ni `onCash`.
- F11 y F12 preservados: sus ramas de teclado y etiquetas no cambiaron.
- Repeticiones bloqueadas por la guarda global existente y cubiertas con `PageDown` repetido.
- Diálogos incompatibles bloquean `PageDown`, cubierto con el diálogo de cliente abierto.
- `F10` queda inerte, cubierto explícitamente sin incrementar el contador de `triggerCash`.
- Sin cambios en backend, base de datos, migraciones ni navegación por flechas.
- `git diff --check` no encontró errores de whitespace; sólo mostró advertencias de normalización LF/CRLF propias del worktree.
- Los cambios preexistentes en `task-1-report.md` y `task-2-report.md` no forman parte de Task 3 y se excluyen del commit.
