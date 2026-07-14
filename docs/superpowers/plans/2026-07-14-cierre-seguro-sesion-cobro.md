# Cierre seguro de usuario con cobro pendiente Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cancelar automáticamente las sesiones de cobro seguras antes del logout y bloquear el cierre cuando exista un cobro incierto, aprobado o pendiente de resolución.

**Architecture:** `SalePaymentCheckout` será la autoridad sobre la sesión de pago activa y expondrá un método imperativo `prepareLogout()` con resultado explícito. `SaleScreen` interceptará el botón de logout, esperará esa preparación y solo eliminará la sesión de usuario cuando el checkout confirme que es seguro. El panel excepcional conservará todas las operaciones de recuperación, pero se presentará como “Cobro pendiente”.

**Tech Stack:** React 19, TypeScript, Vitest, Testing Library, Vite.

## Global Constraints

- Nunca cancelar automáticamente una asignación `PENDING`, `TIMEOUT` o `APPROVED`.
- Nunca eliminar el token antes de completar una cancelación segura.
- Un fallo de red debe conservar al usuario conectado y la sesión de cobro recuperable.
- No habilitar cargos nuevos durante una recuperación incierta.
- No persistir referencias manuales, contraseñas ni datos sensibles.
- El flujo ordinario conserva **Efectivo**, **Tarjeta** y **Pendiente cliente**.
- El flujo de Venta no mostrará el texto **Cobro dividido**.

---

### Task 1: Política y preparación segura del logout

**Files:**
- Modify: `frontend/packages/app-common/src/components/SalePaymentCheckout.tsx`
- Test: `frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts`

**Interfaces:**
- Produces: `PaymentLogoutPreparation = "READY" | "BLOCKED"`.
- Produces: `paymentLogoutDisposition(session?: ServerSession | null, hydrationComplete?: boolean): "READY" | "AUTO_CANCEL" | "BLOCKED"`.
- Produces: `SalePaymentCheckoutHandle = { prepareLogout(): Promise<PaymentLogoutPreparation> }`.
- Consumes: endpoint existente `POST /pos/payment-sessions/{id}/cancel`.

- [ ] **Step 1: Escribir pruebas fallidas de clasificación**

```ts
expect(paymentLogoutDisposition(null, true)).toBe("READY");
expect(paymentLogoutDisposition(sessionWith([]), true)).toBe("AUTO_CANCEL");
expect(paymentLogoutDisposition(sessionWith(["DECLINED", "ERROR"]), true)).toBe("AUTO_CANCEL");
for (const status of ["PENDING", "TIMEOUT", "APPROVED"]) {
  expect(paymentLogoutDisposition(sessionWith([status]), true)).toBe("BLOCKED");
}
expect(paymentLogoutDisposition({ ...sessionWith([]), status: "COVERED" }, true)).toBe("BLOCKED");
expect(paymentLogoutDisposition({ ...sessionWith([]), status: "COMPENSATION_REQUIRED" }, true)).toBe("BLOCKED");
expect(paymentLogoutDisposition(null, false)).toBe("BLOCKED");
```

- [ ] **Step 2: Ejecutar la prueba y confirmar RED**

Run: `npm.cmd test -- SalePaymentCheckout.test.ts`

Expected: FAIL porque `paymentLogoutDisposition` y el handle todavía no existen.

- [ ] **Step 3: Implementar la clasificación mínima**

```ts
export type PaymentLogoutPreparation = "READY" | "BLOCKED";
export type SalePaymentCheckoutHandle = { prepareLogout(): Promise<PaymentLogoutPreparation> };

export function paymentLogoutDisposition(session: ServerSession | null | undefined, hydrationComplete: boolean) {
  if (!hydrationComplete) return "BLOCKED" as const;
  if (!session || session.status === "FINALIZED" || session.status === "CANCELLED") return "READY" as const;
  if (session.status !== "COLLECTING") return "BLOCKED" as const;
  return session.allocations.every(({ status }) => ["DECLINED", "ERROR", "CANCELLED"].includes(status))
    ? "AUTO_CANCEL" as const
    : "BLOCKED" as const;
}
```

- [ ] **Step 4: Escribir pruebas fallidas del handle**

Las pruebas DOM deben verificar:

```ts
await expect(ref.current?.prepareLogout()).resolves.toBe("READY");
expect(cancelRequest).toHaveBeenCalledTimes(1); // sesión vacía o rechazada
expect(onLogout).not.toHaveBeenCalled();        // no pertenece al checkout
```

Y para estados inseguros:

```ts
await expect(ref.current?.prepareLogout()).resolves.toBe("BLOCKED");
expect(cancelRequest).not.toHaveBeenCalled();
expect(screen.getByRole("alert")).toHaveTextContent("Debes resolver el cobro pendiente");
```

- [ ] **Step 5: Implementar `forwardRef` y `prepareLogout()`**

Usar `forwardRef`/`useImperativeHandle`, registrar cuándo termina la carga de `/pos/payment-sessions/active` y reutilizar una función de cancelación que devuelva `true` solo cuando el servidor responda `CANCELLED`. En error de red, mantener `server`, `sessionStorage`, `localStorage` y guards intactos, devolver `BLOCKED` y mostrar `payment.pending.logoutError`.

- [ ] **Step 6: Ejecutar pruebas focalizadas**

Run: `npm.cmd test -- SalePaymentCheckout.test.ts`

Expected: PASS, sin errores no controlados.

- [ ] **Step 7: Commit**

```bash
git add frontend/packages/app-common/src/components/SalePaymentCheckout.tsx frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts
git commit -m "feat(payment): prepare pending checkout before logout"
```

---

### Task 2: Coordinar el botón Cerrar usuario desde Venta

**Files:**
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Test: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`

**Interfaces:**
- Consumes: `SalePaymentCheckoutHandle.prepareLogout(): Promise<"READY" | "BLOCKED">`.
- Produces: `handleSaleLogout()` que llama a `onLogout` solamente tras `READY`.

- [ ] **Step 1: Escribir pruebas fallidas del flujo de logout**

Añadir pruebas DOM que pulsen **Cerrar usuario** y comprueben:

```ts
expect(prepareLogout).toHaveBeenCalledTimes(1);
expect(onLogout).toHaveBeenCalledTimes(1); // cuando devuelve READY
```

```ts
expect(prepareLogout).toHaveBeenCalledTimes(1);
expect(onLogout).not.toHaveBeenCalled();   // cuando devuelve BLOCKED
```

Añadir un caso de doble clic para comprobar que una preparación en curso solo ejecuta una cancelación y un logout.

- [ ] **Step 2: Ejecutar la prueba y confirmar RED**

Run: `npm.cmd test -- SaleScreen.test.tsx`

Expected: FAIL porque `SessionTopControls` todavía recibe `onLogout` directamente.

- [ ] **Step 3: Implementar el coordinador en `SaleScreen`**

```ts
const paymentCheckoutRef = useRef<SalePaymentCheckoutHandle>(null);
const logoutInProgressRef = useRef(false);

async function handleSaleLogout() {
  if (logoutInProgressRef.current) return;
  logoutInProgressRef.current = true;
  try {
    const result = await paymentCheckoutRef.current?.prepareLogout();
    if (result === "READY") onLogout?.();
  } finally {
    logoutInProgressRef.current = false;
  }
}
```

Pasar `onLogout={() => void handleSaleLogout()}` a `SessionTopControls` y `ref={paymentCheckoutRef}` a `SalePaymentCheckout`.

- [ ] **Step 4: Ejecutar pruebas focalizadas**

Run: `npm.cmd test -- SaleScreen.test.tsx SalePaymentCheckout.test.ts`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/packages/app-common/src/components/SaleScreen.tsx frontend/packages/app-common/src/components/SaleScreen.test.tsx
git commit -m "feat(venta): coordinate safe user logout"
```

---

### Task 3: Sustituir la presentación “Cobro dividido” por “Cobro pendiente”

**Files:**
- Modify: `frontend/packages/app-common/src/i18n/MessagesEs.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEn.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesZh.ts`
- Modify: `frontend/packages/app-common/src/components/PaymentAllocationPanel.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts`

**Interfaces:**
- Consumes: claves `payment.split.*` existentes para evitar una migración amplia.
- Produces: copia visible `Cobro pendiente` / `Pending payment` / equivalente chino.

- [ ] **Step 1: Escribir pruebas fallidas de presentación**

```ts
expect(html).toContain("Cobro pendiente");
expect(html).not.toContain("Cobro dividido");
expect(recoveryHtml).not.toContain("Iniciar cobro dividido");
```

Comprobar también que `Consultar estado`, `Gestionar operación`, `Cancelar sesión de cobro`, finalización y compensación continúan disponibles en sus estados correspondientes.

- [ ] **Step 2: Ejecutar pruebas y confirmar RED**

Run: `npm.cmd test -- PaymentAllocationPanel.test.tsx SalePaymentCheckout.test.ts`

Expected: FAIL al encontrar `Cobro dividido`.

- [ ] **Step 3: Cambiar únicamente la copia visible**

Actualizar `payment.split.title` y `payment.split.start` en los tres idiomas. No renombrar endpoints, tipos ni claves internas; no habilitar `allowAdd` durante `RECOVERY`.

- [ ] **Step 4: Ejecutar pruebas focalizadas**

Run: `npm.cmd test -- PaymentAllocationPanel.test.tsx SalePaymentCheckout.test.ts SaleScreen.test.tsx`

Expected: PASS.

- [ ] **Step 5: Ejecutar verificación integral**

Run: `npm.cmd test`

Expected: todas las pruebas frontend pasan.

Run: `npm.cmd run build`

Expected: APP GESTIÓN y APP VENTA compilan con código 0.

Run: `git diff --check`

Expected: sin salida.

- [ ] **Step 6: Commit**

```bash
git add frontend/packages/app-common/src/i18n/MessagesEs.ts frontend/packages/app-common/src/i18n/MessagesEn.ts frontend/packages/app-common/src/i18n/MessagesZh.ts frontend/packages/app-common/src/components/PaymentAllocationPanel.test.tsx frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts
git commit -m "fix(payment): present unfinished checkout as pending"
```

