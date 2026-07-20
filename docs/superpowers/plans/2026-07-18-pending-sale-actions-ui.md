# Pending Sale Actions UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give pending-sale payment and confirmation actions the same clear blue hierarchy as the normal checkout dialog.

**Architecture:** Preserve `CustomerPendingSaleDialog` state and API behavior while adding semantic, component-scoped button classes. A final scoped CSS block supplies equal payment buttons, a flexible confirmation footer, disabled states, and responsive stacking.

**Tech Stack:** React 19, TypeScript 5.7, Vitest 4, Testing Library, CSS.

## Global Constraints

- Do not change quote, payment, recovery, confirmation, or API behavior.
- Preserve every existing `disabled` expression and callback.
- Style only inside `.customer-pending-sale-dialog`.
- Keep Cancel secondary; payment actions and Confirm primary blue.
- Do not modify backend or database files.

---

### Task 1: Pending-sale action class contract and layout

**Files:**
- Modify: `frontend/packages/app-common/src/components/CustomerPendingSaleDialog.tsx`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`
- Test: `frontend/packages/app-common/src/components/CustomerPendingSaleDialog.test.tsx`

**Interfaces:**
- Consumes: current payment callbacks `openCash`, `chargeCard`, `setTransferOpen`, `onCancel`, `confirm` and all current disabled expressions.
- Produces: `.pending-sale-payment-button`, `.pending-sale-footer`, `.pending-sale-cancel-button`, `.pending-sale-confirm-button`.

- [ ] **Step 1: Write a failing visual contract test**

Add a test using the existing quote/payment-method mock pattern:

```tsx
it("uses primary payment actions and a flexible confirmation footer", async () => {
  const request = vi.fn(async (path: string) => {
    if (path.endsWith("/quote")) return { total: "10.00" };
    if (path === "/payment-methods") return [
      { id: "cash", name: "EFECTIVO", active: true },
      { id: "card", name: "TARJETA", active: true },
      { id: "transfer", name: "TRANSFERENCIA", active: true },
    ];
    throw new Error(`unexpected ${path}`);
  });
  render(<CustomerPendingSaleDialog customerName="Cliente" draft={draft}
    request={request as never} onCancel={vi.fn()} onSuccess={vi.fn()} />);

  const cash = await screen.findByRole("button", { name: /añadir efectivo/i });
  const card = screen.getByRole("button", { name: /añadir tarjeta/i });
  const transfer = screen.getByRole("button", { name: /añadir transferencia/i });
  [cash, card, transfer].forEach((button) => expect(button).toHaveClass("pending-sale-payment-button"));
  expect(screen.getByRole("button", { name: /^cancelar$/i })).toHaveClass("pending-sale-cancel-button");
  expect(screen.getByRole("button", { name: /confirmar venta pendiente/i })).toHaveClass("pending-sale-confirm-button");
  expect(screen.getByRole("button", { name: /confirmar venta pendiente/i }).parentElement).toHaveClass("pending-sale-footer");
});
```

- [ ] **Step 2: Run the focused test and verify failure**

Run from `frontend`:

```powershell
npm.cmd test -- CustomerPendingSaleDialog.test.tsx
```

Expected: FAIL because the four scoped classes are absent.

- [ ] **Step 3: Add scoped classes without changing behavior**

Change only `className` attributes around the existing handlers:

```tsx
<div className="pending-sale-payment-actions">
  <button type="button" className="pending-sale-payment-button"
    disabled={disabled || hasCardEffect || createDurable || !resolvedMethods.cash || summary.pendingCents <= 0 || uncertain}
    onClick={openCash}>{t("pendingSale.addCash")}</button>
  <button type="button" className="pending-sale-payment-button"
    disabled={disabled || hasCardEffect || createDurable || !resolvedMethods.card || summary.pendingCents <= 0 || uncertain}
    onClick={() => void chargeCard()}>{t("pendingSale.addCard")}</button>
  <button type="button" className="pending-sale-payment-button"
    disabled={disabled || hasCardEffect || createDurable || !resolvedMethods.transfer || summary.pendingCents <= 0 || uncertain}
    onClick={() => { if (!createDurable) setTransferOpen(true); }}>{t("pendingSale.addTransfer")}</button>
</div>
<footer className="pending-sale-footer">
  <button type="button" className="pending-sale-cancel-button"
    disabled={submitting || hasCardEffect || createDurable}
    onClick={onCancel}>{t("common.cancel")}</button>
  <button type="button" className="pending-sale-confirm-button"
    aria-label={createDurable && !submitting ? `${t("pendingSale.retryCreate")} · ${t("pendingSale.confirm")}` : undefined}
    disabled={disabled || submitting || quoteLoading || !quoteReady || uncertain || cardFinalFailure || summary.pendingCents < 0 || !draft.dueDate}
    onClick={() => void confirm()}>{t(submitting ? "pendingSale.creating" : createDurable ? "pendingSale.retryCreate" : "pendingSale.confirm")}</button>
</footer>
```

- [ ] **Step 4: Add the final scoped CSS block**

```css
.customer-pending-sale-dialog .pending-sale-payment-actions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-top: 6px;
}
.customer-pending-sale-dialog .pending-sale-payment-button,
.customer-pending-sale-dialog .pending-sale-confirm-button {
  min-height: 46px;
  border: 1px solid #2f70b7;
  border-radius: 7px;
  background: #2f78bd;
  color: #fff;
  font-weight: 800;
}
.customer-pending-sale-dialog .pending-sale-payment-button:hover:not(:disabled),
.customer-pending-sale-dialog .pending-sale-payment-button:focus-visible,
.customer-pending-sale-dialog .pending-sale-confirm-button:hover:not(:disabled),
.customer-pending-sale-dialog .pending-sale-confirm-button:focus-visible {
  background: #255f99;
}
.customer-pending-sale-dialog .pending-sale-payment-button:disabled,
.customer-pending-sale-dialog .pending-sale-confirm-button:disabled {
  border-color: #cbd4df;
  background: #e7ecf2;
  color: #8b97a6;
  cursor: not-allowed;
}
.customer-pending-sale-dialog > .pending-sale-footer {
  display: flex;
  gap: 12px;
  margin-top: 18px;
  align-items: stretch;
}
.customer-pending-sale-dialog .pending-sale-cancel-button {
  flex: 0 0 auto;
  min-height: 46px;
  padding-inline: 18px;
}
.customer-pending-sale-dialog .pending-sale-confirm-button {
  flex: 1 1 auto;
}
@media (max-width: 640px) {
  .customer-pending-sale-dialog .pending-sale-payment-actions { grid-template-columns: 1fr; }
  .customer-pending-sale-dialog > .pending-sale-footer { flex-direction: column-reverse; }
  .customer-pending-sale-dialog .pending-sale-cancel-button,
  .customer-pending-sale-dialog .pending-sale-confirm-button { width: 100%; }
}
```

- [ ] **Step 5: Run dialog tests and APP VENTA build**

Run from `frontend`:

```powershell
npm.cmd test -- CustomerPendingSaleDialog.test.tsx
npm.cmd run build --workspace @tpverp/app-venta
```

Expected: all dialog tests PASS; TypeScript/Vite build exits 0.

- [ ] **Step 6: Inspect the dialog locally**

Verify with enabled and disabled payment methods: three equal blue actions; disabled buttons remain visibly disabled; Cancel stays secondary; Confirm fills the remaining footer width; errors and transfer form do not overlap the footer; narrow layout stacks cleanly.

- [ ] **Step 7: Commit the dialog slice**

```powershell
git add frontend/packages/app-common/src/components/CustomerPendingSaleDialog.tsx frontend/packages/app-common/src/components/CustomerPendingSaleDialog.test.tsx frontend/packages/app-common/src/styles/tpv.css
git commit -m "style: refine pending sale payment actions"
```
