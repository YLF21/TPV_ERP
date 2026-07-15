# Cash validation global fix report

## Base y alcance

- Base revisada: `5534f35 fix(payment): defer cash amount validation`.
- Alcance: corrección del orden de foco antes de aplicar `aria-hidden` al diálogo de efectivo y eliminación de la restauración redundante de foco exclusivamente en el aviso de validación.
- No se modificó el comportamiento predeterminado de las demás trampas de foco.

## Cambios

1. `CashPaymentDialog.tsx`
   - Añade `showValidation`, que ejecuta `blur()` síncronamente si el elemento activo pertenece al diálogo de entrada.
   - Solo después libera el foco y actualiza `validationMessage`, permitiendo que React aplique `aria-hidden="true"` sin conservar foco dentro del árbol oculto.
2. `modalFocusTrap.ts`
   - Añade la opción compatible hacia atrás `{ restoreFocus?: boolean }`, con valor predeterminado `true`.
3. `CashPaymentValidationDialog.tsx`
   - Activa su trampa con `{ restoreFocus: false }`; al desmontarse no vuelve a enfocar el botón `Aceptar` que deja de existir.
   - El padre sigue siendo el único responsable de devolver el foco al input.
4. Pruebas
   - Verifican que `blur()` ocurre antes de que aparezca `aria-hidden` y que el foco sale del diálogo oculto.
   - Verifican que el aviso no restaura foco al desmontarse.
   - Verifican directamente que la trampa restaura por defecto y permite desactivar la restauración.

## Evidencia RED

Comando:

```text
npm.cmd test -- packages/app-common/src/components/CashPaymentDialog.test.tsx packages/app-common/src/components/CashPaymentValidationDialog.test.tsx
```

Resultado antes de producción: exit 1, 2 archivos fallidos, 2 pruebas fallidas y 22 aprobadas.

- `CashPaymentDialog > releases focus before hiding the cash-entry dialog`: `blur` esperado 1 vez, recibido 0.
- `CashPaymentValidationDialog > does not restore focus when its focus trap is removed`: `focus` esperado 0 veces, recibido 1.

Nota de entorno: la primera invocación con `npm` fue bloqueada por la Execution Policy de PowerShell al intentar cargar `npm.ps1`; todos los comandos efectivos se ejecutaron con `npm.cmd`.

## Evidencia GREEN

### Archivos modificados y trampa de foco

```text
npm.cmd test -- packages/app-common/src/components/CashPaymentDialog.test.tsx packages/app-common/src/components/CashPaymentValidationDialog.test.tsx packages/app-common/src/components/modalFocusTrap.test.ts
```

Resultado: exit 0; 3 archivos, 26 pruebas aprobadas.

### Regresión de checkout

```text
npm.cmd test -- packages/app-common/src/components/SalePaymentCheckout.test.ts
```

Resultado: exit 0; 1 archivo, 82 pruebas aprobadas.

### Suite completa

```text
npm.cmd test
```

Resultado: exit 0; 51 archivos, 438 pruebas aprobadas.

### Build

```text
npm.cmd run build
```

Resultado: exit 0; TypeScript y Vite completaron correctamente los builds de `@tpverp/app-gestion` y `@tpverp/app-venta`.

## Riesgos y preocupaciones

- No quedan preocupaciones funcionales conocidas dentro del alcance.
- `restoreFocus` conserva `true` como valor predeterminado; `CardPaymentDialog`, `CashPaymentDialog`, `CashPaymentResultDialog` y `ManualCardReferenceDialog` no pasan la opción y mantienen el comportamiento anterior.
- La protección de `blur()` se limita al elemento activo contenido en el diálogo de entrada, por lo que no desenfoca controles externos.
