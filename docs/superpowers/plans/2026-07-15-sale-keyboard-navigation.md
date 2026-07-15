# Sale Keyboard Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hacer que cantidad, descuento, anulación, efectivo y selección de líneas puedan operarse correctamente con teclado en la pantalla de Venta.

**Architecture:** `SaleScreen` conservará un único manejador global que delega en las mismas acciones usadas por los botones. Los formularios de cantidad y descuento resolverán `Enter` mediante `onSubmit`; el diálogo reutilizable gestionará el foco inicial y la confirmación de anulación gestionará `Enter`/`Escape` en su ámbito.

**Tech Stack:** React 19, TypeScript, Vitest, Testing Library, Vite.

## Global Constraints

- `PageDown` sustituye completamente a `F10` para efectivo.
- Solo `ArrowUp` y `ArrowDown` navegan entre líneas; no hay paginación ni navegación horizontal.
- Los extremos no producen selección circular.
- Los eventos repetidos, las acciones deshabilitadas y los diálogos incompatibles no deben activar atajos globales.
- No se modifica backend ni base de datos.

---

### Task 1: Formularios de cantidad y descuento

**Files:**
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`

**Interfaces:**
- Consumes: `openQuantityDialog()`, `saveQuantity()`, `openDiscountDialog()`, `saveDiscount()`.
- Produces: diálogos que enfocan su campo al abrir y confirman mediante el envío del formulario.

- [ ] **Step 1: Escribir pruebas fallidas de foco y Enter**

Añadir pruebas que abran cada diálogo con su tecla, comprueben `toHaveFocus()`, cambien el valor y envíen `Enter`:

```tsx
fireEvent.keyDown(window, { key: "F2" });
const quantity = screen.getByRole("spinbutton", { name: "Nueva cantidad" });
expect(quantity).toHaveFocus();
fireEvent.change(quantity, { target: { value: "3" } });
fireEvent.keyDown(quantity, { key: "Enter", code: "Enter" });
expect(screen.queryByRole("dialog", { name: "Cambiar cantidad" })).not.toBeInTheDocument();

fireEvent.keyDown(window, { key: "F7" });
const discount = screen.getByRole("spinbutton", { name: "Nuevo descuento" });
expect(discount).toHaveFocus();
fireEvent.change(discount, { target: { value: "10" } });
fireEvent.keyDown(discount, { key: "Enter", code: "Enter" });
expect(screen.queryByRole("dialog", { name: "Aplicar descuento" })).not.toBeInTheDocument();
```

- [ ] **Step 2: Ejecutar las pruebas y confirmar RED**

Run: `npm.cmd test -- SaleScreen.test.tsx`

Expected: FAIL porque los campos no reciben foco y `Enter` no envía todavía los diálogos.

- [ ] **Step 3: Implementar formularios y foco inicial**

Crear referencias y convertir el contenido en formularios:

```tsx
const quantityInputRef = useRef<HTMLInputElement>(null);
const discountInputRef = useRef<HTMLInputElement>(null);

useEffect(() => {
  if (actionDialog === "quantity") quantityInputRef.current?.focus();
  if (actionDialog === "discount") discountInputRef.current?.focus();
}, [actionDialog]);

<form onSubmit={(event) => { event.preventDefault(); saveQuantity(); }}>
  <input ref={quantityInputRef} aria-label="Nueva cantidad" ... />
  <button type="submit">Guardar</button>
</form>
```

Aplicar el mismo patrón a descuento con `discountInputRef` y `saveDiscount()`.

- [ ] **Step 4: Ejecutar la prueba y confirmar GREEN**

Run: `npm.cmd test -- SaleScreen.test.tsx`

Expected: PASS para foco y confirmación de cantidad/descuento.

### Task 2: Confirmación de anulación con Enter y Escape

**Files:**
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`

**Interfaces:**
- Consumes: `confirmRemoveLine()` y `setActionDialog(null)`.
- Produces: `SaleActionDialog` con un callback opcional `onConfirm?: () => void` y manejo de teclado limitado al diálogo.

- [ ] **Step 1: Escribir pruebas fallidas para Enter y Escape**

```tsx
fireEvent.keyDown(window, { key: "Delete" });
fireEvent.keyDown(screen.getByRole("dialog", { name: "Anular linea" }), { key: "Escape" });
expect(screen.queryByRole("dialog", { name: "Anular linea" })).not.toBeInTheDocument();

fireEvent.keyDown(window, { key: "Delete" });
fireEvent.keyDown(screen.getByRole("dialog", { name: "Anular linea" }), { key: "Enter" });
expect(screen.queryByText("Cafe molido")).not.toBeInTheDocument();
```

- [ ] **Step 2: Ejecutar y confirmar RED**

Run: `npm.cmd test -- SaleScreen.test.tsx`

Expected: FAIL porque el diálogo no procesa esas teclas.

- [ ] **Step 3: Implementar manejo de teclado contextual**

Extender `SaleActionDialog` con `onConfirm` y añadir `onKeyDown` a la sección:

```tsx
onKeyDown={(event) => {
  if (event.key === "Escape") { event.preventDefault(); onClose(); }
  if (event.key === "Enter" && onConfirm) { event.preventDefault(); onConfirm(); }
}}
```

Pasar `onConfirm={confirmRemoveLine}` solo al diálogo de anulación. Evitar que `Enter` de un botón dispare dos veces comprobando `event.repeat` y deteniendo el evento gestionado.

- [ ] **Step 4: Ejecutar y confirmar GREEN**

Run: `npm.cmd test -- SaleScreen.test.tsx`

Expected: PASS para confirmación y cancelación.

### Task 3: PageDown para efectivo y etiquetas AvPág

**Files:**
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts`
- Modify: `frontend/packages/app-common/src/components/IndividualPaymentActions.tsx`

**Interfaces:**
- Consumes: `paymentCheckoutRef.current?.triggerCash()`.
- Produces: acceso global `PageDown` y etiqueta visual `AvPág` en botón y barra de ayuda.

- [ ] **Step 1: Cambiar las pruebas a PageDown y exigir que F10 no actúe**

```tsx
fireEvent.keyDown(window, { key: "PageDown" });
expect(triggerCash).toHaveBeenCalledTimes(1);
fireEvent.keyDown(window, { key: "F10" });
expect(triggerCash).toHaveBeenCalledTimes(1);
expect(screen.getByRole("button", { name: /Efectivo.*AvPág/ })).toBeVisible();
```

Actualizar expectativas estáticas que buscan `F10` para que busquen `AvPág`.

- [ ] **Step 2: Ejecutar y confirmar RED**

Run: `npm.cmd test -- SaleScreen.test.tsx SalePaymentCheckout.test.ts`

Expected: FAIL porque `F10` sigue enlazado y la etiqueta todavía muestra `F10`.

- [ ] **Step 3: Implementar PageDown y actualizar la etiqueta**

En el `switch` global reemplazar:

```tsx
case "PageDown":
  paymentCheckoutRef.current?.triggerCash();
  break;
```

Cambiar los `<kbd>F10</kbd>` del botón de efectivo y la barra inferior por `<kbd>AvPág</kbd>` sin alterar `triggerCash()`.

- [ ] **Step 4: Ejecutar y confirmar GREEN**

Run: `npm.cmd test -- SaleScreen.test.tsx SalePaymentCheckout.test.ts`

Expected: PASS y ninguna expectativa funcional de efectivo dependiente de F10.

### Task 4: Navegación de líneas con flechas verticales

**Files:**
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`

**Interfaces:**
- Consumes: `lines`, `selectedProductId`, `setSelectedProductId`.
- Produces: helper puro `saleLineSelectionAfterArrow(lines, selectedProductId, key): string | null`.

- [ ] **Step 1: Escribir pruebas unitarias e interacción fallidas**

```tsx
expect(saleLineSelectionAfterArrow(lines, null, "ArrowDown")).toBe(lines[0].product.id);
expect(saleLineSelectionAfterArrow(lines, null, "ArrowUp")).toBe(lines.at(-1)?.product.id);
expect(saleLineSelectionAfterArrow(lines, lines[0].product.id, "ArrowUp")).toBe(lines[0].product.id);
expect(saleLineSelectionAfterArrow(lines, lines.at(-1)!.product.id, "ArrowDown")).toBe(lines.at(-1)!.product.id);
```

En interacción, añadir dos productos, pulsar `ArrowUp`/`ArrowDown` y comprobar `aria-pressed`.

- [ ] **Step 2: Ejecutar y confirmar RED**

Run: `npm.cmd test -- SaleScreen.test.tsx`

Expected: FAIL porque el helper y los casos del manejador aún no existen.

- [ ] **Step 3: Implementar helper y conexión global**

```tsx
export function saleLineSelectionAfterArrow(
  lines: SaleLine[],
  selectedId: string | null,
  key: "ArrowUp" | "ArrowDown",
) {
  if (lines.length === 0) return null;
  const current = lines.findIndex(({ product }) => product.id === selectedId);
  if (current < 0) return key === "ArrowDown" ? lines[0].product.id : lines[lines.length - 1].product.id;
  const offset = key === "ArrowDown" ? 1 : -1;
  return lines[Math.max(0, Math.min(lines.length - 1, current + offset))].product.id;
}
```

Agregar casos `ArrowUp` y `ArrowDown` al manejador, respetando diálogos, campos editables, bloqueo y repetición.

- [ ] **Step 4: Ejecutar y confirmar GREEN**

Run: `npm.cmd test -- SaleScreen.test.tsx`

Expected: PASS para selección inicial, movimiento y extremos.

### Task 5: Regresión integral

**Files:**
- Verify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Verify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Verify: `frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts`
- Verify: `frontend/packages/app-common/src/components/IndividualPaymentActions.tsx`

**Interfaces:**
- Consumes: los cuatro comportamientos implementados.
- Produces: frontend verificado y compilable.

- [ ] **Step 1: Ejecutar pruebas específicas**

Run: `npm.cmd test -- SaleScreen.test.tsx SalePaymentCheckout.test.ts`

Expected: PASS.

- [ ] **Step 2: Ejecutar toda la suite frontend**

Run: `npm.cmd test`

Expected: todos los archivos y pruebas PASS.

- [ ] **Step 3: Compilar APP VENTA**

Run: `npm.cmd run build --workspace @tpverp/app-venta`

Expected: TypeScript y Vite terminan con código 0.

- [ ] **Step 4: Revisar el diff**

Run: `git diff --check && git status --short`

Expected: sin errores de espacios; solo archivos de frontend y documentación de esta tarea, además de los cambios locales de migraciones preexistentes.
