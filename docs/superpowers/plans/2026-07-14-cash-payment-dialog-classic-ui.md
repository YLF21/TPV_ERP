# Classic Cash Payment Dialog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle the APP VENTA cash-entry dialog as a compact, rectangular ERP dialog while preserving its complete touch keypad and current behavior.

**Architecture:** Add one modifier class to `CashPaymentDialog` so the visual override is isolated from the shared `cash-payment-dialog` styles used by card and result dialogs. Append a scoped CSS block that reuses the existing `--tpv-v3-*` tokens; no payment state, calculation, focus, keyboard, or component structure changes are required.

**Tech Stack:** React 19, TypeScript, CSS, Vitest, Testing Library, Vite.

## Global Constraints

- Limit the change to the cash-entry `CashPaymentDialog`.
- Keep the current React structure and tab order.
- Keep all five amount shortcuts and the complete three-column touch keypad.
- Use a `420px` dialog width limited by the viewport and `max-height: calc(100dvh - 32px)` with vertical scrolling.
- Use a `4px` outer radius and existing `--tpv-v3-*` design tokens.
- Do not change card payment, payment result, calculation, confirmation, focus trap, keyboard shortcuts, accessibility, or payment contracts.

---

## File Structure

- `frontend/packages/app-common/src/components/CashPaymentDialog.tsx`: adds the cash-entry-only modifier class; behavior remains unchanged.
- `frontend/packages/app-common/src/components/CashPaymentDialog.test.tsx`: proves the modifier is rendered on the intended dialog.
- `frontend/packages/app-common/src/styles/tpv.css`: contains the isolated classic ERP presentation.

### Task 1: Apply the isolated classic ERP presentation

**Files:**
- Modify: `frontend/packages/app-common/src/components/CashPaymentDialog.test.tsx:28-34`
- Modify: `frontend/packages/app-common/src/components/CashPaymentDialog.tsx:59`
- Modify: `frontend/packages/app-common/src/styles/tpv.css` (append the scoped block after the current APP VENTA overrides)

**Interfaces:**
- Consumes: existing `CashPaymentDialogProps`, `cash-payment-dialog`, `cash-payment-summary`, `cash-received-input`, `cash-shortcuts`, `cash-keypad`, `cash-input-mode-toggle`, `cash-payment-actions`, and `--tpv-v3-*` CSS tokens.
- Produces: the `cash-payment-entry-dialog` modifier class, used only by the cash-entry dialog and its scoped CSS rules.

- [ ] **Step 1: Write the failing modifier-class test**

Extend the existing visible-heading test in `CashPaymentDialog.test.tsx`:

```tsx
it("labels and scopes the cash-entry modal", () => {
  const html = renderToStaticMarkup(<CashPaymentDialog {...baseProps} initialMode="touch" />);
  expect(html).toContain('class="cash-payment-dialog cash-payment-entry-dialog"');
  expect(html).toContain('aria-labelledby="cash-payment-title"');
  expect(html).toContain('<h2 id="cash-payment-title">Cobro en efectivo</h2>');
  expect(html).not.toContain('aria-label="Cobro en efectivo"');
});
```

- [ ] **Step 2: Run the focused test and verify the new assertion fails**

Run from `frontend`:

```bash
npm test -- packages/app-common/src/components/CashPaymentDialog.test.tsx
```

Expected: FAIL in `labels and scopes the cash-entry modal` because the rendered class is still only `cash-payment-dialog`.

- [ ] **Step 3: Add the cash-entry-only modifier class**

Change the dialog section in `CashPaymentDialog.tsx` without changing any props or handlers:

```tsx
<section
  ref={dialogRef}
  className="cash-payment-dialog cash-payment-entry-dialog"
  role="dialog"
  aria-modal="true"
  aria-labelledby="cash-payment-title"
>
```

- [ ] **Step 4: Run the focused test and verify it passes**

Run from `frontend`:

```bash
npm test -- packages/app-common/src/components/CashPaymentDialog.test.tsx
```

Expected: PASS for every test in `CashPaymentDialog.test.tsx`.

- [ ] **Step 5: Add the scoped compact ERP styles**

Append this block to `frontend/packages/app-common/src/styles/tpv.css` after the current APP VENTA overrides so it wins the existing shared cash-dialog rules without affecting card or result dialogs:

```css
/* APP VENTA: classic, compact cash-entry dialog. */
.cash-payment-entry-dialog {
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

.cash-payment-entry-dialog > header {
  min-height: 38px;
  padding: 0 10px 0 12px;
  border-bottom: 1px solid var(--tpv-v3-line);
  background: var(--tpv-v3-surface-alt);
}

.cash-payment-entry-dialog > header h2 {
  margin: 0;
  font-size: 16px;
  line-height: 1.2;
}

.cash-payment-entry-dialog > header button {
  width: 28px;
  height: 28px;
  border: 1px solid var(--tpv-v3-line);
  border-radius: 3px;
  background: var(--tpv-v3-surface);
  color: var(--tpv-v3-muted);
}

.cash-payment-entry-dialog .cash-payment-summary {
  gap: 4px;
  margin: 10px 12px 8px;
}

.cash-payment-entry-dialog .cash-payment-summary > div {
  min-height: 34px;
  box-sizing: border-box;
  padding: 6px 9px;
  border: 1px solid var(--tpv-v3-line-soft);
  border-radius: 3px;
  background: var(--tpv-v3-surface-alt);
}

.cash-payment-entry-dialog .cash-payment-summary strong {
  color: var(--tpv-v3-text);
  font-size: 16px;
  font-variant-numeric: tabular-nums;
}

.cash-payment-entry-dialog .cash-payment-summary .cash-change {
  border-color: #b9ddc7;
  background: #eef8f2;
  color: #116634;
}

.cash-payment-entry-dialog .cash-received-input {
  width: calc(100% - 24px);
  min-height: 40px;
  margin: 0 12px;
  padding: 6px 10px;
  border: 1px solid var(--tpv-v3-line);
  border-radius: 3px;
  font-size: 20px;
  font-variant-numeric: tabular-nums;
}

.cash-payment-entry-dialog .cash-input-mode-toggle {
  margin: 6px 12px 0 auto;
  padding: 2px 0;
  color: var(--tpv-v3-blue);
  font-size: 12px;
}

.cash-payment-entry-dialog .cash-shortcuts,
.cash-payment-entry-dialog .cash-keypad {
  gap: 4px;
  margin: 8px 12px 0;
}

.cash-payment-entry-dialog .cash-shortcuts button,
.cash-payment-entry-dialog .cash-keypad button,
.cash-payment-entry-dialog .cash-payment-actions button {
  border: 1px solid var(--tpv-v3-line);
  border-radius: 3px;
  background: var(--tpv-v3-surface-alt);
  color: var(--tpv-v3-text);
  box-shadow: none;
  font-weight: 800;
}

.cash-payment-entry-dialog .cash-shortcuts button {
  min-height: 32px;
  padding: 4px;
  font-size: 12px;
}

.cash-payment-entry-dialog .cash-keypad button {
  min-height: 42px;
  font-size: 16px;
}

.cash-payment-entry-dialog .sale-action-error {
  margin: 8px 12px 0;
}

.cash-payment-entry-dialog .cash-payment-actions {
  gap: 6px;
  margin: 10px 12px 12px;
}

.cash-payment-entry-dialog .cash-payment-actions button {
  min-height: 34px;
  padding: 5px 10px;
}

.cash-payment-entry-dialog .cash-payment-actions button:last-child {
  border-color: var(--tpv-v3-blue);
  background: var(--tpv-v3-blue);
  color: #ffffff;
}
```

- [ ] **Step 6: Run the component regression tests**

Run from `frontend`:

```bash
npm test -- packages/app-common/src/components/CashPaymentDialog.test.tsx packages/app-common/src/components/CashPaymentResultDialog.test.tsx
```

Expected: PASS for both test files, confirming cash-entry behavior remains intact and the shared result dialog still renders successfully.

- [ ] **Step 7: Build APP VENTA**

Run from `frontend`:

```bash
npm run build --workspace @tpverp/app-venta
```

Expected: TypeScript and Vite complete successfully and generate the APP VENTA production bundle.

- [ ] **Step 8: Perform the visual acceptance check**

Run from `frontend`:

```bash
npm run dev:venta
```

Open APP VENTA, create a sale line, choose `Efectivo`, and verify:

- The modal is rectangular, `420px` wide when space permits, and visually matches the classic ERP panels.
- The header, summary rows, input, shortcuts, keypad, and footer use compact spacing and low-radius borders.
- `Exacto`, `5 €`, `10 €`, `20 €`, `50 €`, every digit, decimal separator, backspace, and `C` remain visible and usable.
- A short viewport scrolls the modal instead of clipping controls.
- `Escape`, valid `Enter`, the physical-keyboard toggle, focus ring, disabled confirmation, and error state still work.
- Card and payment-result dialogs retain their previous presentation.

Stop the development server after verification.

- [ ] **Step 9: Commit the implementation**

```bash
git add frontend/packages/app-common/src/components/CashPaymentDialog.tsx frontend/packages/app-common/src/components/CashPaymentDialog.test.tsx frontend/packages/app-common/src/styles/tpv.css
git commit -m "fix(sale): align cash dialog with classic UI"
```
