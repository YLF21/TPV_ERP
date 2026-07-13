# Cash Input Mode and Payment Result Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Añadir una preferencia local Táctil/Teclado normal al cobro en efectivo y mostrar un resumen confirmado después del pago.

**Architecture:** Un módulo puro encapsula la clave y validación de `localStorage`. Configuración edita la preferencia, el diálogo de efectivo recibe el modo inicial y permite alternarlo temporalmente, y `SaleScreen` conserva los datos confirmados para presentar un diálogo final independiente.

**Tech Stack:** React 19, TypeScript, Vitest, Vite, CSS existente del paquete `@tpverp/app-common`.

## Global Constraints

- La preferencia se guarda únicamente en `localStorage`; no se modifica PostgreSQL ni el backend.
- El valor predeterminado es `touch`.
- El cambio dentro del diálogo de cobro es temporal.
- `Enter` confirma un importe suficiente y `Escape` cancela, salvo durante el envío.
- La venta solo se limpia tras una respuesta correcta del backend.
- Todo queda local: sin commit ni push.

---

### Task 1: Preferencia local del modo de entrada

**Files:**
- Create: `frontend/packages/app-common/src/sale/cashInputMode.ts`
- Create: `frontend/packages/app-common/src/sale/cashInputMode.test.ts`

**Interfaces:**
- Produces: `type CashInputMode = "touch" | "keyboard"`, `readCashInputMode(storage?)`, `writeCashInputMode(mode, storage?)`.

- [ ] **Step 1: Escribir pruebas que exijan valor táctil predeterminado, lectura válida, recuperación de valor inválido y escritura.**
- [ ] **Step 2: Ejecutar `npm.cmd test -- cashInputMode.test.ts` y comprobar que falla porque el módulo no existe.**
- [ ] **Step 3: Implementar el módulo con la clave `tpverp.cashInputMode.v1`, validación estricta y protección ante errores de almacenamiento.**
- [ ] **Step 4: Repetir la prueba y comprobar que pasa.**

### Task 2: Selector en Configuración

**Files:**
- Modify: `frontend/packages/app-common/src/components/SettingsScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/SettingsScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

**Interfaces:**
- Consumes: `readCashInputMode`, `writeCashInputMode`, `CashInputMode`.

- [ ] **Step 1: Añadir una prueba de renderizado que espere “Entrada de cobro”, las opciones “Táctil” y “Teclado normal” y un `select` accesible.**
- [ ] **Step 2: Ejecutar `npm.cmd test -- SettingsScreen.test.tsx` y verificar el fallo esperado.**
- [ ] **Step 3: Añadir la tarjeta en la sección Terminal, inicializarla desde `localStorage` y persistir cada cambio válido.**
- [ ] **Step 4: Añadir estilos coherentes con las tarjetas existentes y repetir la prueba hasta obtener PASS.**

### Task 3: Modos táctil y teclado físico

**Files:**
- Modify: `frontend/packages/app-common/src/components/CashPaymentDialog.tsx`
- Modify: `frontend/packages/app-common/src/components/CashPaymentDialog.test.tsx`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

**Interfaces:**
- Consumes: propiedad `initialMode: CashInputMode`.
- Preserves: `onConfirm(receivedCents)` y `onCancel()`.

- [ ] **Step 1: Añadir pruebas para teclado visible en `touch`, oculto en `keyboard`, y textos de alternancia correctos.**
- [ ] **Step 2: Ejecutar `npm.cmd test -- CashPaymentDialog.test.tsx` y comprobar que las pruebas fallan por la propiedad/comportamiento ausente.**
- [ ] **Step 3: Implementar estado local inicializado desde `initialMode`, botón de alternancia temporal y renderizado condicional del teclado y accesos rápidos.**
- [ ] **Step 4: Añadir un controlador de teclado de ventana: `Enter` confirma solo con importe suficiente y `Escape` cancela; ambos quedan bloqueados durante `submitting`.**
- [ ] **Step 5: Repetir las pruebas y verificar PASS sin cambiar la preferencia persistida.**

### Task 4: Diálogo de pago completado

**Files:**
- Create: `frontend/packages/app-common/src/components/CashPaymentResultDialog.tsx`
- Create: `frontend/packages/app-common/src/components/CashPaymentResultDialog.test.tsx`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

**Interfaces:**
- Produces: `CashPaymentResultDialog({ ticketNumber, totalCents, receivedCents, changeCents, onFinish })`.

- [ ] **Step 1: Escribir una prueba que exija título, ticket, total, recibido, cambio y botón Finalizar.**
- [ ] **Step 2: Ejecutar `npm.cmd test -- CashPaymentResultDialog.test.tsx` y comprobar el fallo por módulo inexistente.**
- [ ] **Step 3: Crear el componente presentacional reutilizando el formato monetario español y las clases del resumen de efectivo.**
- [ ] **Step 4: Añadir estilos de confirmación y repetir la prueba hasta PASS.**

### Task 5: Orquestación del flujo en SaleScreen

**Files:**
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`

**Interfaces:**
- Consumes: `readCashInputMode()` al abrir cada cobro y `CashPaymentResultDialog` tras confirmar.
- Stores: `{ ticketNumber, totalCents, receivedCents, changeCents } | null`.

- [ ] **Step 1: Añadir pruebas de contrato/renderizado para el modo inicial y el diálogo de resultado con datos confirmados.**
- [ ] **Step 2: Ejecutar `npm.cmd test -- SaleScreen.test.tsx` y verificar el fallo esperado.**
- [ ] **Step 3: Leer la preferencia al abrir el cobro, pasarla como `initialMode` y sustituir el mensaje textual de éxito por el estado estructurado del resultado.**
- [ ] **Step 4: Usar total y cambio de la respuesta del servidor cuando estén disponibles, conservar el dinero recibido enviado y enfocar el buscador al pulsar Finalizar.**
- [ ] **Step 5: Confirmar que un error mantiene abierto el primer diálogo, conserva la venta y no crea el resultado.**
- [ ] **Step 6: Repetir la prueba y comprobar PASS.**

### Task 6: Verificación integral

**Files:**
- Verify all modified frontend files.

- [ ] **Step 1: Ejecutar `npm.cmd test -- cashInputMode.test.ts CashPaymentDialog.test.tsx CashPaymentResultDialog.test.tsx SettingsScreen.test.tsx SaleScreen.test.tsx`.**
- [ ] **Step 2: Ejecutar `npm.cmd run build` y comprobar que compilan `app-venta` y `app-gestion`.**
- [ ] **Step 3: Ejecutar `git diff --check` desde la raíz y revisar que no haya errores de espacios ni cambios ajenos.**
- [ ] **Step 4: Probar manualmente en `http://127.0.0.1:5173`: ambos modos, alternancia temporal, Enter, Escape, error de servidor y resumen final.**
