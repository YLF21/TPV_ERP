# Compact Payment Result Dialog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle `Pago completado` to match the compact rectangular ERP treatment of `Cobro en efectivo` without changing payment behavior.

**Architecture:** Keep the existing semantic React markup and behavior of `CashPaymentResultDialog`. Add component-scoped CSS overrides under `.cash-payment-result-dialog` and protect the approved geometry with a CSS contract test in the existing component test file.

**Tech Stack:** React 19, TypeScript, CSS, Vitest, React DOM server rendering.

## Global Constraints

- Use the approved visual variant A and match the compact cash-entry dialog.
- Keep the dialog at a maximum width of `420px`, with a `1px` border and `4px` radius.
- Keep the header at `38px`, result rows at least `34px`, amounts at `16px`, row radius at `3px`, and the final action at least `34px`.
- Preserve overlay, focus trap, `autoFocus`, `onFinish`, metadata and conditional rows exactly as they work now.
- Limit production changes to component-scoped presentation; do not refactor other dialogs or payment behavior.

---

### Task 1: Apply and lock down the compact ERP result style

**Files:**
- Modify: `frontend/packages/app-common/src/components/CashPaymentResultDialog.test.tsx`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

**Interfaces:**
- Consumes: existing markup classes `.cash-payment-result-dialog`, `.cash-payment-result-header`, `.cash-payment-result-mark`, `.cash-payment-ticket`, `.cash-payment-summary`, `.cash-change`, and `.cash-payment-actions`.
- Produces: component-scoped CSS contract for the approved compact visual; no TypeScript API changes.

- [ ] **Step 1: Add a failing CSS contract test**

Add Node filesystem imports and load the shared stylesheet once:

```tsx
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const tpvCss = readFileSync(resolve(process.cwd(), "packages/app-common/src/styles/tpv.css"), "utf8");
```

Add this test inside `describe("CashPaymentResultDialog", ...)`:

```tsx
it("uses the approved compact rectangular ERP result layout", () => {
  expect(tpvCss).toMatch(/\.cash-payment-result-dialog\s*{[^}]*width:\s*min\(420px,\s*calc\(100vw - 32px\)\)\s*!important;[^}]*padding:\s*0\s*!important;[^}]*border:\s*1px solid var\(--tpv-v3-line\)\s*!important;[^}]*border-radius:\s*4px\s*!important;/s);
  expect(tpvCss).toMatch(/\.cash-payment-result-dialog\s*>\s*header\s*{[^}]*min-height:\s*38px;[^}]*border-bottom:\s*1px solid var\(--tpv-v3-line\);/s);
  expect(tpvCss).toMatch(/\.cash-payment-result-dialog \.cash-payment-summary\s*>\s*div\s*{[^}]*min-height:\s*34px;[^}]*border-radius:\s*3px;/s);
  expect(tpvCss).toMatch(/\.cash-payment-result-dialog \.cash-payment-summary strong\s*{[^}]*font-size:\s*16px;[^}]*font-variant-numeric:\s*tabular-nums;/s);
  expect(tpvCss).toMatch(/\.cash-payment-result-dialog \.cash-payment-actions button\s*{[^}]*min-height:\s*34px;[^}]*border-radius:\s*3px;/s);
});
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
npm.cmd test -- CashPaymentResultDialog.test.tsx -t "uses the approved compact rectangular ERP result layout"
```

Expected: FAIL because the component-scoped compact CSS block does not exist.

- [ ] **Step 3: Add the compact component-scoped CSS overrides**

Add the following alongside the existing compact cash-entry block in `tpv.css`:

```css
/* APP VENTA: compact ERP payment result dialog. */
.cash-payment-result-dialog {
  box-sizing: border-box !important;
  width: min(420px, calc(100vw - 32px)) !important;
  max-height: calc(100dvh - 32px) !important;
  padding: 0 !important;
  overflow-y: auto !important;
  border: 1px solid var(--tpv-v3-line) !important;
  border-radius: 4px !important;
  background: var(--tpv-v3-surface) !important;
  box-shadow: 0 8px 24px rgba(23, 32, 51, 0.22) !important;
}

.cash-payment-result-dialog > header {
  min-height: 38px;
  padding: 0 10px 0 12px;
  border-bottom: 1px solid var(--tpv-v3-line);
  background: var(--tpv-v3-surface-alt);
}

.cash-payment-result-dialog > header h2 {
  margin: 0;
  font-size: 16px;
  line-height: 1.2;
}

.cash-payment-result-dialog .cash-payment-result-mark {
  width: 26px;
  height: 26px;
  border: 1px solid #b9ddc7;
  border-radius: 3px;
  background: #eef8f2;
  font-size: 16px;
}

.cash-payment-result-dialog .cash-payment-ticket {
  margin: 8px 12px 0;
  font-size: 12px;
}

.cash-payment-result-dialog .cash-payment-summary {
  gap: 4px;
  margin: 10px 12px 8px;
}

.cash-payment-result-dialog .cash-payment-summary > div {
  min-height: 34px;
  box-sizing: border-box;
  padding: 6px 9px;
  border: 1px solid var(--tpv-v3-line-soft);
  border-radius: 3px;
  background: var(--tpv-v3-surface-alt);
}

.cash-payment-result-dialog .cash-payment-summary strong {
  color: var(--tpv-v3-text);
  font-size: 16px;
  font-variant-numeric: tabular-nums;
}

.cash-payment-result-dialog .cash-payment-summary .cash-change {
  border-color: #b9ddc7;
  background: #eef8f2;
  color: #116634;
}

.cash-payment-result-dialog .cash-payment-actions {
  margin: 10px 12px 12px;
}

.cash-payment-result-dialog .cash-payment-actions button {
  min-height: 34px;
  padding: 5px 10px;
  border: 1px solid var(--tpv-v3-blue);
  border-radius: 3px;
  background: var(--tpv-v3-blue);
  color: #ffffff;
  box-shadow: none;
}
```

- [ ] **Step 4: Run focused verification and verify GREEN**

Run:

```powershell
npm.cmd test -- CashPaymentResultDialog.test.tsx
```

Expected: all `CashPaymentResultDialog` tests pass, including focus trap, metadata and callback tests.

- [ ] **Step 5: Run full frontend verification**

Run:

```powershell
npm.cmd test
npm.cmd run build
```

Expected: all frontend tests pass; APP GESTIÓN and APP VENTA production builds succeed.

- [ ] **Step 6: Commit the implementation**

```powershell
git add -- frontend/packages/app-common/src/components/CashPaymentResultDialog.test.tsx frontend/packages/app-common/src/styles/tpv.css
git commit -m "fix(payment): compact payment result dialog"
```

