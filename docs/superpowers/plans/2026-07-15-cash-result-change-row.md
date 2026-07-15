# Cash Result Change Row Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Always show the calculated change for cash results and render the three cash summary labels in bold.

**Architecture:** Before the irreversible finalize POST, `SalePaymentCheckout` validates the authoritative COVERED session and derives an explicit `PaymentFinalizationSummary` from its effective allocations (`CASH`, `CARD`, or `MIXED`). The ticket response cannot redefine the summary. `SaleScreen` consumes that discriminated summary and calculates `changeCents` only for CASH, keeping `CashPaymentResultDialog` presentation-only.

**Tech Stack:** React 19, TypeScript, CSS, Vitest, React DOM server rendering.

## Global Constraints

- Cash results always contain `Total`, `Dinero recibido`, and `Cambio`, including `Cambio 0,00` for exact payment.
- Calculate `changeCents` as `Math.max(0, receivedCents - totalCents)` for cash.
- Never infer the payment method from `receivedCents`; use explicit `kind: "CASH" | "CARD" | "MIXED"`.
- Derive `kind` from `APPROVED` allocations. Only CASH carries `receivedCents`; CASH without a local attempt uses authorized cash capped at the total, while CARD and MIXED omit it.
- Keep card results without `Dinero recibido` and `Cambio`.
- Keep mixed results without `Dinero recibido` and `Cambio`, and show method `Mixto`.
- Bold only the cash-result summary labels through `.cash-payment-result-dialog .cash-payment-summary span`.
- Do not change backend contracts, endpoints, payment finalization, card behavior, or result-dialog arithmetic.

---

### Task 1: Complete and emphasize the cash result summary

**Files:**
- Modify: `frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts`
- Modify: `frontend/packages/app-common/src/components/SalePaymentCheckout.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/CashPaymentResultDialog.test.tsx`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

**Interfaces:**
- Produces: `SalePaymentCheckout.onFinalized(ticketNumber, PaymentFinalizationSummary)`, where only `{ kind: "CASH" }` includes `receivedCents`.
- Consumes: `paymentResultFromFinalization(ticketNumber, summary)` at the shared handler and `cashResultFromFinalization(ticketNumber, totalCents, receivedCents)` for cash only.

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

Add producer tests that finalize sessions with effective allocations for CASH without `cashAttempt`, CARD, and MIXED. Add a boundary test that invokes the `onFinalized` callback wired by `SaleScreen` with each explicit summary: CARD renders `Tarjeta`, CASH renders its calculated change, and MIXED renders `Mixto`; CARD/MIXED omit `Dinero recibido` and `Cambio`.

Keep the existing `CashPaymentResultDialog` assertions that already verify a positive `Cambio` row and that card results omit `Dinero recibido` and `Cambio`; do not duplicate them.

- [ ] **Step 2: Write the failing bold-label CSS contract**

Add this assertion to `uses the approved compact rectangular ERP result layout`:

```tsx
expect(tpvCss).toMatch(/\.cash-payment-result-dialog \.cash-payment-summary span\s*{[^}]*font-weight:\s*800;/s);
```

- [ ] **Step 3: Run focused tests and verify RED**

Run from `frontend`:

```powershell
npm.cmd test -- SalePaymentCheckout.test.ts SaleScreen.test.tsx -t "explicit .*finalization summar|explicit checkout finalization summaries"
```

Expected for the architectural regression: producer tests fail because `finish` still emits scalar arguments, and the consumer test fails because `SaleScreen` does not consume the discriminated summary.

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

export type PaymentFinalizationSummary =
  | { kind: "CASH"; totalCents: number; receivedCents: number }
  | { kind: "CARD" | "MIXED"; totalCents: number; receivedCents?: never };
```

Derive and validate the summary in `SalePaymentCheckout.finish` from the COVERED `next` session's `APPROVED` allocations before calling `/finalize`. Preserve `cashAttempt.receivedCents` for keyboard CASH; otherwise cap authorized CASH at `totalCents`. Reuse the precomputed summary when the response contains a ticket, even if the response omits allocations. Wire `SaleScreen.onFinalized` through `paymentResultFromFinalization(ticketNumber, summary)` and map `CARD`/`MIXED` to `Tarjeta`/`Mixto` without cash fields.

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
npm.cmd test -- SalePaymentCheckout.test.ts SaleScreen.test.tsx CashPaymentResultDialog.test.tsx
```

Expected: all tests in all three files pass, including the card-only negative assertions.

- [ ] **Step 7: Run full frontend verification**

Run:

```powershell
npm.cmd test
npm.cmd run build
```

Expected: all frontend tests pass; APP GESTIÓN and APP VENTA production builds succeed.

- [ ] **Step 8: Commit the implementation**

```powershell
git add -- frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts frontend/packages/app-common/src/components/SalePaymentCheckout.tsx frontend/packages/app-common/src/components/SaleScreen.test.tsx frontend/packages/app-common/src/components/SaleScreen.tsx frontend/packages/app-common/src/components/CashPaymentResultDialog.test.tsx frontend/packages/app-common/src/styles/tpv.css docs/superpowers/specs/2026-07-15-cash-result-change-row-design.md docs/superpowers/plans/2026-07-15-cash-result-change-row.md
git commit -m "fix(payment): summarize finalized payment method"
```
