# Three-Row Payment Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mostrar Efectivo, Tarjeta y Pendiente cliente en tres filas táctiles legibles dentro del panel lateral de cobro.

**Architecture:** Mantener intacto `IndividualPaymentActions.tsx` y resolver la presentación mediante las clases existentes. La cuadrícula exterior tendrá una columna y cada botón utilizará una cuadrícula interna de tres columnas para que la etiqueta quede centrada y el atajo permanezca anclado a la derecha.

**Tech Stack:** React, TypeScript, CSS, Vitest, Testing Library, Vite.

## Global Constraints

- No modificar comportamiento, callbacks, permisos, estados deshabilitados ni atajos.
- Mantener tres acciones: `Efectivo`, `Tarjeta` y `Pendiente cliente`.
- Cada acción ocupa una fila y todo el ancho disponible.
- Etiqueta centrada; `kbd` alineado a la derecha sin solapamiento.
- Compatible con modo normal y táctil.

---

### Task 1: Payment actions vertical layout

**Files:**
- Modify: `frontend/packages/app-common/src/styles/tpv.css:9513`
- Modify: `frontend/packages/app-common/src/components/IndividualPaymentActions.test.tsx`

**Interfaces:**
- Consumes: markup `.sale-payment-actions.individual-payment-actions > button > span + kbd` producido por `IndividualPaymentActions`.
- Produces: cuadrícula vertical estable mediante `.individual-payment-actions` y alineación interna mediante `.individual-payment-actions button`.

- [ ] **Step 1: Write the failing structural style test**

Añadir al test una lectura del CSS que verifique las reglas críticas:

```ts
const css = readFileSync(new URL("../styles/tpv.css", import.meta.url), "utf8");
expect(css).toMatch(/\.individual-payment-actions\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\)/s);
expect(css).toMatch(/\.individual-payment-actions button\s*\{[^}]*grid-template-columns:\s*1fr auto 1fr/s);
expect(css).toMatch(/\.individual-payment-actions button kbd\s*\{[^}]*grid-column:\s*3/s);
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
cd frontend
npm.cmd test -- IndividualPaymentActions.test.tsx
```

Expected: FAIL porque la regla actual usa `repeat(3, minmax(0, 1fr))` y no existe la alineación interna.

- [ ] **Step 3: Implement the minimal CSS**

Sustituir y ampliar las reglas por:

```css
.individual-payment-actions {
  grid-template-columns: minmax(0, 1fr) !important;
}

.individual-payment-actions button {
  display: grid !important;
  grid-template-columns: 1fr auto 1fr !important;
  align-items: center !important;
  width: 100% !important;
}

.individual-payment-actions button span {
  grid-column: 2 !important;
  text-align: center !important;
}

.individual-payment-actions button kbd {
  grid-column: 3 !important;
  justify-self: end !important;
}
```

- [ ] **Step 4: Run focused tests**

Run:

```powershell
cd frontend
npm.cmd test -- IndividualPaymentActions.test.tsx
```

Expected: todos los tests de `IndividualPaymentActions` pasan.

- [ ] **Step 5: Run regression and production build**

Run:

```powershell
npm.cmd test
npm.cmd run build
git diff --check
```

Expected: todos los tests pasan, APP GESTIÓN y APP VENTA compilan, y `git diff --check` no muestra errores.

- [ ] **Step 6: Verify visually**

Abrir APP VENTA con el panel lateral estrecho y comprobar: tres filas, ancho completo, etiqueta centrada, `F10/F11/F12` a la derecha, sin desbordamiento.

- [ ] **Step 7: Commit**

```powershell
git add frontend/packages/app-common/src/styles/tpv.css frontend/packages/app-common/src/components/IndividualPaymentActions.test.tsx
git commit -m "fix(venta): stack payment actions vertically"
```
