# Cash Result Change Row Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Always show the calculated change for cash results and render the three cash summary labels in bold.

**Architecture:** Discriminate card and cash at the `SaleScreen` finalization boundary, then calculate `changeCents` in the cash-only `cashResultFromFinalization` helper so `CashPaymentResultDialog` remains presentation-only. Add one component-scoped CSS rule for label emphasis and protect the boundary and presentation with focused tests.

**Tech Stack:** React 19, TypeScript, CSS, Vitest, React DOM server rendering.

## Global Constraints

- Cash results always contain `Total`, `Dinero recibido`, and `Cambio`, including `Cambio 0,00` for exact payment.
- Calculate `changeCents` as `Math.max(0, receivedCents - totalCents)` for cash.
- `receivedCents === undefined` means card; cash finalizations always provide `receivedCents`, including exact payment.
- Keep card results without `Dinero recibido` and `Cambio`.
- Bold only the cash-result summary labels through `.cash-payment-result-dialog .cash-payment-summary span`.
- Do not change backend contracts, endpoints, payment finalization, card behavior, or result-dialog arithmetic.

---

### Task 1: Complete and emphasize the cash result summary

**Files:**
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/CashPaymentResultDialog.test.tsx`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

**Interfaces:**
- Consumes: `paymentResultFromFinalization(ticketNumber: string, totalCents: number, receivedCents?: number): CashPaymentResult` at the shared handler and `cashResultFromFinalization(ticketNumber: string, totalCents: number, receivedCents: number): CashPaymentResult` for cash only.
- Produces: a card result with `method: "Tarjeta"` and no `receivedCents`/`changeCents` when the boundary receives `undefined`, or a cash result with defined `receivedCents` and `changeCents`; component-scoped bold label contract.

- [ ] **Step 1: Write failing change-calculation tests**

Replace the current `cashResultFromFinalization` expectation in `SaleScreen.test.tsx` with:

```tsx
it("preserves cash received and calculates non-negative change for individual checkout", () => {
  expect(cashResultFromFinalization("T-1", 1210, 2000)).toEqual({
    ticketNumber: "T-1",
    totalCents: 1210,
    receivedCents: 2000,
    changeCents: 790,
  });
  expect(cashResultFromFinalization("T-2", 1210, 1210)).toEqual({
    ticketNumber: "T-2",
    totalCents: 1210,
    receivedCents: 1210,
    changeCents: 0,
  });
  expect(cashResultFromFinalization("T-3", 1210, 1000).changeCents).toBe(0);
});
```

Add a boundary test that invokes the `onFinalized` callback wired by `SaleScreen`: `onFinalized("CARD-1", 1210)` must render method `Tarjeta` without `Dinero recibido` or `Cambio`, while `onFinalized("CASH-1", 1210, 2000)` must render cash change `7,90`.

Keep the existing `CashPaymentResultDialog` assertions that already verify a positive `Cambio` row and that card results omit `Dinero recibido` and `Cambio`; do not duplicate them.

- [ ] **Step 2: Write the failing bold-label CSS contract**

Add this assertion to `uses the approved compact rectangular ERP result layout`:

```tsx
expect(tpvCss).toMatch(/\.cash-payment-result-dialog \.cash-payment-summary span\s*{[^}]*font-weight:\s*800;/s);
```

- [ ] **Step 3: Run focused tests and verify RED**

Run from `frontend`:

```powershell
npm.cmd test -- SaleScreen.test.tsx CashPaymentResultDialog.test.tsx -t "cash received|compact rectangular ERP result layout"
```

Expected for the original implementation: the `cashResultFromFinalization` assertions fail because `changeCents` is absent, and the CSS contract fails because the bold-label rule is absent. The boundary regression test must additionally fail while `undefined` is incorrectly normalized as exact cash.

- [ ] **Step 4: Calculate change at the result boundary**

Replace `cashResultFromFinalization` in `SaleScreen.tsx` with:

```tsx
export function cashResultFromFinalization(
  ticketNumber: string,
  totalCents: number,
  receivedCents: number,
): CashPaymentResult {
  return {
    ticketNumber,
    totalCents,
    receivedCents,
    changeCents: Math.max(0, receivedCents - totalCents),
  };
}

export function paymentResultFromFinalization(
  ticketNumber: string,
  totalCents: number,
  receivedCents?: number,
): CashPaymentResult {
  if (receivedCents === undefined) {
    return { ticketNumber, totalCents, method: "Tarjeta" };
  }
  return cashResultFromFinalization(ticketNumber, totalCents, receivedCents);
}
```

Wire the shared `SaleScreen.onFinalized` handler through `paymentResultFromFinalization`; do not pass card events to `cashResultFromFinalization`.

- [ ] **Step 5: Make result labels bold**

Add next to the existing result-summary styles in `tpv.css`:

```css
.cash-payment-result-dialog .cash-payment-summary span {
  font-weight: 800;
}
```

- [ ] **Step 6: Run focused verification and verify GREEN**

Run from `frontend`:

```powershell
npm.cmd test -- SaleScreen.test.tsx CashPaymentResultDialog.test.tsx
```

Expected: all tests in both files pass, including the card-only negative assertions.

- [ ] **Step 7: Run full frontend verification**

Run:

```powershell
npm.cmd test
npm.cmd run build
```

Expected: all frontend tests pass; APP GESTIÓN and APP VENTA production builds succeed.

- [ ] **Step 8: Commit the implementation**

```powershell
git add -- frontend/packages/app-common/src/components/SaleScreen.test.tsx frontend/packages/app-common/src/components/SaleScreen.tsx frontend/packages/app-common/src/components/CashPaymentResultDialog.test.tsx frontend/packages/app-common/src/styles/tpv.css
git commit -m "fix(payment): show cash result change"
```
