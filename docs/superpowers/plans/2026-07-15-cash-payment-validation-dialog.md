# Cash Payment Validation Dialog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mostrar las validaciones del importe recibido únicamente después de intentar confirmar el cobro, mediante una ventana ERP accesible que conserva el importe y devuelve el foco al campo.

**Architecture:** Crear un componente pequeño y autónomo para la ventana de aviso, reutilizando el atrapamiento de foco modal existente. `CashPaymentDialog` será responsable de decidir el mensaje, bloquear su manejador de teclado mientras el aviso esté abierto y restaurar el foco del campo al cerrar; el componente de aviso solo presentará el mensaje y emitirá la acción de aceptación.

**Tech Stack:** React 19, TypeScript, Vitest, Testing Library y CSS compartido de app-common.

## Global Constraints

- No mostrar mensajes de validación mientras el usuario escribe o utiliza el teclado táctil.
- Usar exactamente `Debe indicar el importe recibido.` para `0,00`.
- Usar exactamente `El importe recibido no cubre el total.` para un importe positivo inferior al total.
- Aplicar la misma validación al botón `Confirmar cobro` y a Enter.
- Conservar el importe y devolver el foco a `Dinero recibido` después de cerrar el aviso.
- Mantener el flujo actual cuando el importe sea igual o superior al total.
- No cambiar la presentación de errores externos recibidos mediante la propiedad `error`.
- No añadir dependencias.

---

## File Structure

- Create `frontend/packages/app-common/src/components/CashPaymentValidationDialog.tsx`: ventana de aviso accesible, control de Enter/Escape y atrapamiento de foco.
- Create `frontend/packages/app-common/src/components/CashPaymentValidationDialog.test.tsx`: contrato de marcado, foco y teclado de la ventana.
- Modify `frontend/packages/app-common/src/components/CashPaymentDialog.tsx`: estado de validación, decisión del mensaje, integración con botón/Enter y restauración del foco.
- Modify `frontend/packages/app-common/src/components/CashPaymentDialog.test.tsx`: pruebas integradas del comportamiento solicitado.
- Modify `frontend/packages/app-common/src/styles/tpv.css`: estilo ERP compacto y capa que bloquea el diálogo subyacente.

### Task 1: Ventana ERP de validación

**Files:**
- Create: `frontend/packages/app-common/src/components/CashPaymentValidationDialog.tsx`
- Create: `frontend/packages/app-common/src/components/CashPaymentValidationDialog.test.tsx`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

**Interfaces:**
- Consumes: `activateModalFocusTrap(root: ModalFocusRoot, doc: ModalFocusDocument)` de `modalFocusTrap.ts`.
- Produces: `CashPaymentValidationDialog({ message, onAccept }: { message: string; onAccept: () => void })`.

- [ ] **Step 1: Escribir las pruebas fallidas del marcado, aceptación y teclado**

Crear `CashPaymentValidationDialog.test.tsx` con pruebas que exijan el contrato accesible y que Enter/Escape invoquen `onAccept`:

```tsx
// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CashPaymentValidationDialog } from "./CashPaymentValidationDialog";

afterEach(cleanup);

describe("CashPaymentValidationDialog", () => {
  it("renders a compact accessible warning with one acceptance action", () => {
    render(<CashPaymentValidationDialog message="Debe indicar el importe recibido." onAccept={vi.fn()} />);

    const dialog = screen.getByRole("alertdialog", { name: "Aviso" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(dialog).toHaveTextContent("Debe indicar el importe recibido.");
    expect(screen.getByRole("button", { name: "Aceptar" })).toHaveFocus();
  });

  it.each(["Enter", "Escape"])("accepts the warning with %s", (key) => {
    const onAccept = vi.fn();
    render(<CashPaymentValidationDialog message="El importe recibido no cubre el total." onAccept={onAccept} />);

    fireEvent.keyDown(screen.getByRole("alertdialog"), { key });

    expect(onAccept).toHaveBeenCalledOnce();
  });

  it("accepts the warning from its button", () => {
    const onAccept = vi.fn();
    render(<CashPaymentValidationDialog message="Debe indicar el importe recibido." onAccept={onAccept} />);

    fireEvent.click(screen.getByRole("button", { name: "Aceptar" }));

    expect(onAccept).toHaveBeenCalledOnce();
  });
});
```

- [ ] **Step 2: Ejecutar la prueba y confirmar RED**

Run:

```powershell
cd frontend
npm.cmd test -- packages/app-common/src/components/CashPaymentValidationDialog.test.tsx
```

Expected: FAIL porque `./CashPaymentValidationDialog` todavía no existe.

- [ ] **Step 3: Implementar la ventana mínima con atrapamiento de foco**

Crear `CashPaymentValidationDialog.tsx`:

```tsx
import { useEffect, useRef } from "react";
import { activateModalFocusTrap, type ModalFocusRoot } from "./modalFocusTrap";

type CashPaymentValidationDialogProps = {
  message: string;
  onAccept: () => void;
};

export function CashPaymentValidationDialog({ message, onAccept }: CashPaymentValidationDialogProps) {
  const dialogRef = useRef<HTMLElement>(null);

  useEffect(() => dialogRef.current
    ? activateModalFocusTrap(dialogRef.current as unknown as ModalFocusRoot, document)
    : undefined, []);

  return (
    <div className="cash-payment-validation-overlay" role="presentation">
      <section
        ref={dialogRef}
        className="cash-payment-validation-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="cash-payment-validation-title"
        aria-describedby="cash-payment-validation-message"
        onKeyDown={(event) => {
          if (event.key !== "Enter" && event.key !== "Escape") return;
          event.preventDefault();
          event.stopPropagation();
          onAccept();
        }}
      >
        <header><h2 id="cash-payment-validation-title">Aviso</h2></header>
        <p id="cash-payment-validation-message">{message}</p>
        <footer><button type="button" autoFocus onClick={onAccept}>Aceptar</button></footer>
      </section>
    </div>
  );
}
```

Añadir a `tpv.css` estilos específicos, sin reutilizar las reglas redondeadas antiguas:

```css
.cash-payment-validation-overlay {
  position: fixed;
  z-index: 171;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(12, 29, 53, 0.18);
}

.cash-payment-validation-dialog {
  box-sizing: border-box;
  width: min(360px, calc(100vw - 32px));
  overflow: hidden;
  border: 1px solid var(--tpv-v3-line);
  border-radius: 4px;
  background: var(--tpv-v3-surface);
  box-shadow: 0 8px 24px rgba(23, 32, 51, 0.24);
}

.cash-payment-validation-dialog > header {
  min-height: 38px;
  display: flex;
  align-items: center;
  padding: 0 10px;
  border-bottom: 1px solid var(--tpv-v3-line);
  background: var(--tpv-v3-surface-alt);
}

.cash-payment-validation-dialog h2,
.cash-payment-validation-dialog p {
  margin: 0;
}

.cash-payment-validation-dialog h2 {
  font-size: 15px;
}

.cash-payment-validation-dialog p {
  padding: 16px 12px;
  color: var(--tpv-v3-text);
  font-weight: 700;
}

.cash-payment-validation-dialog footer {
  padding: 0 12px 12px;
}

.cash-payment-validation-dialog button {
  width: 100%;
  min-height: 34px;
  border: 1px solid var(--tpv-v3-blue);
  border-radius: 3px;
  background: var(--tpv-v3-blue);
  color: #fff;
  font-weight: 800;
}
```

- [ ] **Step 4: Ejecutar las pruebas del componente y confirmar GREEN**

Run:

```powershell
cd frontend
npm.cmd test -- packages/app-common/src/components/CashPaymentValidationDialog.test.tsx
```

Expected: 4 tests PASS (el caso parametrizado ejecuta una prueba para Enter y otra para Escape).

- [ ] **Step 5: Confirmar que el CSS cumple el diseño rectangular**

Añadir estas importaciones y lectura al archivo de prueba:

```tsx
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const tpvCss = readFileSync(resolve(process.cwd(), "packages/app-common/src/styles/tpv.css"), "utf8");
```

Añadir a la primera prueba estas aserciones:

```tsx
expect(tpvCss).toMatch(/\.cash-payment-validation-dialog\s*{[^}]*width:\s*min\(360px,\s*calc\(100vw - 32px\)\);[^}]*border-radius:\s*4px;/s);
expect(tpvCss).toMatch(/\.cash-payment-validation-dialog button\s*{[^}]*min-height:\s*34px;/s);
```

Volver a ejecutar el archivo y esperar 4 tests PASS.

- [ ] **Step 6: Commit**

```powershell
git add frontend/packages/app-common/src/components/CashPaymentValidationDialog.tsx frontend/packages/app-common/src/components/CashPaymentValidationDialog.test.tsx frontend/packages/app-common/src/styles/tpv.css
git commit -m "feat(payment): add cash validation dialog"
```

### Task 2: Validación bajo intento de confirmación

**Files:**
- Modify: `frontend/packages/app-common/src/components/CashPaymentDialog.tsx`
- Modify: `frontend/packages/app-common/src/components/CashPaymentDialog.test.tsx`

**Interfaces:**
- Consumes: `CashPaymentValidationDialog({ message, onAccept })` de Task 1.
- Produces: validación mediante botón/Enter que solo llama `onConfirm(receivedCents)` cuando `receivedCents >= totalCents`.

- [ ] **Step 1: Escribir pruebas fallidas del flujo solicitado**

Añadir pruebas con Testing Library que cubran los cuatro estados principales:

```tsx
it("keeps validation hidden while typing and warns only after confirming an insufficient amount", () => {
  const onConfirm = vi.fn();
  render(<CashPaymentDialog {...baseProps} totalCents={1210} initialMode="touch" onConfirm={onConfirm} />);
  const input = screen.getByRole("textbox", { name: "Dinero recibido" });

  fireEvent.change(input, { target: { value: "5" } });
  expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Confirmar cobro" }));

  expect(screen.getByRole("alertdialog", { name: "Aviso" })).toHaveTextContent("El importe recibido no cubre el total.");
  expect(onConfirm).not.toHaveBeenCalled();
});

it("uses the formal empty-amount warning", () => {
  render(<CashPaymentDialog {...baseProps} totalCents={1210} initialMode="touch" />);

  fireEvent.click(screen.getByRole("button", { name: "Confirmar cobro" }));

  expect(screen.getByRole("alertdialog")).toHaveTextContent("Debe indicar el importe recibido.");
});

it("preserves the amount and returns focus to the input after accepting", () => {
  render(<CashPaymentDialog {...baseProps} totalCents={1210} initialMode="touch" />);
  const input = screen.getByRole("textbox", { name: "Dinero recibido" });
  fireEvent.change(input, { target: { value: "5" } });
  fireEvent.click(screen.getByRole("button", { name: "Confirmar cobro" }));

  fireEvent.click(screen.getByRole("button", { name: "Aceptar" }));

  expect(input).toHaveValue("5");
  expect(input).toHaveFocus();
});

it("validates Enter and confirms only an amount that covers the total", () => {
  const onConfirm = vi.fn();
  render(<CashPaymentDialog {...baseProps} totalCents={1210} initialMode="keyboard" onConfirm={onConfirm} />);
  const input = screen.getByRole("textbox", { name: "Dinero recibido" });

  fireEvent.keyDown(window, { key: "Enter" });
  expect(screen.getByRole("alertdialog")).toHaveTextContent("Debe indicar el importe recibido.");
  fireEvent.keyDown(screen.getByRole("alertdialog"), { key: "Escape" });
  fireEvent.change(input, { target: { value: "12,10" } });
  fireEvent.keyDown(window, { key: "Enter" });

  expect(onConfirm).toHaveBeenCalledWith(1210);
});
```

Actualizar la tabla de `cashPaymentKeyAction` para usar la nueva firma y exigir que Enter represente un intento de confirmación con independencia del importe:

```tsx
it.each([
  ["Escape", false, false, "cancel"],
  ["Enter", false, false, "confirm"],
  ["a", false, false, "none"],
  ["Escape", true, false, "none"],
  ["Enter", true, false, "none"],
  ["Escape", false, true, "none"],
  ["Enter", false, true, "none"],
] as const)("decides %s with submitting=%s validationOpen=%s as %s", (key, submitting, validationOpen, action) => {
  expect(cashPaymentKeyAction(key, submitting, validationOpen)).toBe(action);
});
```

- [ ] **Step 2: Ejecutar las pruebas y confirmar RED**

Run:

```powershell
cd frontend
npm.cmd test -- packages/app-common/src/components/CashPaymentDialog.test.tsx
```

Expected: FAIL porque el botón sigue deshabilitado para importes insuficientes, el aviso se muestra en línea y Enter no inicia la validación.

- [ ] **Step 3: Implementar el estado y la decisión de validación**

En `CashPaymentDialog.tsx`:

1. Importar `useCallback`, `CashPaymentValidationDialog`, crear `const inputRef = useRef<HTMLInputElement>(null)` y añadir `ref={inputRef}` al `input` existente con `aria-label="Dinero recibido"`.
2. Añadir `validationMessage: string | null` y una referencia booleana para restaurar el foco después del desmontaje del aviso.
3. Cambiar `cashPaymentKeyAction` a esta firma e implementación para devolver `confirm` ante Enter cuando no se esté registrando ni haya un aviso abierto:

```tsx
export function cashPaymentKeyAction(
  key: string,
  submitting: boolean,
  validationOpen: boolean,
): CashPaymentKeyAction {
  if (submitting || validationOpen) return "none";
  if (key === "Escape") return "cancel";
  if (key === "Enter") return "confirm";
  return "none";
}
```
4. Crear `attemptConfirm` con este orden exacto:

```tsx
const attemptConfirm = useCallback(() => {
  if (receivedCents === 0) {
    setValidationMessage("Debe indicar el importe recibido.");
    return;
  }
  if (receivedCents < totalCents) {
    setValidationMessage("El importe recibido no cubre el total.");
    return;
  }
  onConfirm(receivedCents);
}, [onConfirm, receivedCents, totalCents]);
```

5. Hacer que el manejador de teclado use la nueva firma e invoque `attemptConfirm`:

```tsx
const action = cashPaymentKeyAction(event.key, submitting, validationMessage !== null);
if (action === "cancel") {
  event.preventDefault();
  onCancel();
} else if (action === "confirm") {
  event.preventDefault();
  attemptConfirm();
}
```
6. Eliminar el párrafo condicionado por `receivedCents > 0 && receivedCents < totalCents`; conservar sin cambios el párrafo de `error`.
7. Dejar el botón deshabilitado solo con `submitting` y usar `onClick={attemptConfirm}`.
8. Marcar la sección principal con `aria-hidden={validationMessage !== null ? true : undefined}` para que deje de exponerse a tecnologías de asistencia:

```tsx
<section
  ref={dialogRef}
  className="cash-payment-dialog cash-payment-entry-dialog"
  role="dialog"
  aria-modal="true"
  aria-labelledby="cash-payment-title"
  aria-hidden={validationMessage !== null ? true : undefined}
>
```

Después del cierre existente de esa sección, renderizar `CashPaymentValidationDialog` como hermano cuando haya mensaje:

```tsx
{validationMessage !== null && (
  <CashPaymentValidationDialog message={validationMessage} onAccept={closeValidation} />
)}
```
9. Al aceptar, marcar la restauración, limpiar `validationMessage` y, desde un efecto posterior al desmontaje, enfocar `inputRef.current`.

El cierre y la restauración deben usar este patrón, evitando temporizadores:

```tsx
const restoreInputFocusRef = useRef(false);

useEffect(() => {
  if (validationMessage !== null || !restoreInputFocusRef.current) return;
  restoreInputFocusRef.current = false;
  inputRef.current?.focus();
}, [validationMessage]);

const closeValidation = () => {
  restoreInputFocusRef.current = true;
  setValidationMessage(null);
};
```

- [ ] **Step 4: Ejecutar las pruebas focalizadas y confirmar GREEN**

Run:

```powershell
cd frontend
npm.cmd test -- packages/app-common/src/components/CashPaymentValidationDialog.test.tsx packages/app-common/src/components/CashPaymentDialog.test.tsx
```

Expected: ambos archivos PASS, sin llamadas a `onConfirm` para `0,00` ni importes insuficientes.

- [ ] **Step 5: Ejecutar las regresiones del checkout**

Run:

```powershell
cd frontend
npm.cmd test -- packages/app-common/src/components/SalePaymentCheckout.test.ts
```

Expected: PASS; los cobros suficientes y el flujo de caja de prueba siguen funcionando.

- [ ] **Step 6: Ejecutar la verificación completa**

Run:

```powershell
cd frontend
npm.cmd test
npm.cmd run build
```

Expected: toda la suite PASS y las compilaciones de `app-gestion` y `app-venta` terminan con exit code 0.

- [ ] **Step 7: Commit**

```powershell
git add frontend/packages/app-common/src/components/CashPaymentDialog.tsx frontend/packages/app-common/src/components/CashPaymentDialog.test.tsx
git commit -m "fix(payment): defer cash amount validation"
```
