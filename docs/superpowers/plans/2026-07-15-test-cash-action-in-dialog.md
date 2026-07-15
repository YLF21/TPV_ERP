# Test Cash Action in Dialog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `Abrir caja de prueba` operable from the active cash-payment dialog after finalization fails because no cash session is open.

**Architecture:** `SalePaymentCheckout` remains the owner of the recovery state and API call. It passes an optional action and status into `CashPaymentDialog`, which renders them inside the modal; the existing lateral action remains available only as a fallback outside the modal.

**Tech Stack:** React 19, TypeScript, Vitest, Testing Library, CSS.

## Global Constraints

- Keep the received cash amount and payment attempt unchanged while opening the test cash session.
- Do not allocate payment again or finalize automatically after opening the test cash session.
- Keep the feature limited to APP VENTA in development.
- Reuse `POST /cash/sessions/open`; do not add backend behavior.

---

### Task 1: Expose the recovery action inside the cash dialog

**Files:**
- Modify: `frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts`
- Modify: `frontend/packages/app-common/src/components/SalePaymentCheckout.tsx`
- Modify: `frontend/packages/app-common/src/components/CashPaymentDialog.tsx`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

**Interfaces:**
- Consumes: `shouldOfferTestCashSession(enabled, status, missing, terminalId)` and the existing `openTestCashSession()` callback.
- Produces: optional `testCashAction` and `testCashStatus` props for `CashPaymentDialog`; no exported API changes.

- [ ] **Step 1: Write the failing regression test**

Add a test that starts a real cash-dialog flow, makes allocation succeed and finalization fail, then scopes the recovery button to the active dialog:

```tsx
it("offers test cash inside the active cash dialog after finalization is blocked", async () => {
  const session = { id: "session-modal-cash", total: "12.10", status: "COLLECTING", allocations: [] };
  apiRequestMock.mockImplementation(async (path: string, options?: { body?: unknown }) => {
    if (path === "/terminal-configuration/payment") return {
      rules: { cardManualEnabled: true, integratedCardEnabled: false },
      providerDescriptors: [],
      configuration: { provider: "", enabled: false },
    };
    if (path === "/pos/payment-sessions/active") return null;
    if (path === "/pos/payment-sessions") return session;
    if (path === "/pos/payment-sessions/session-modal-cash/allocations") return {
      ...session,
      status: "COVERED",
      allocations: [{
        id: (options?.body as { allocationId: string }).allocationId,
        idempotencyKey: "cash-modal",
        kind: "CASH",
        amount: "12.10",
        status: "APPROVED",
      }],
    };
    if (path === "/pos/payment-sessions/session-modal-cash/finalize") {
      throw new ApiError("No hay una sesión de caja abierta", 409);
    }
    if (path === "/cash/sessions/open") return { id: "cash-session-modal", status: "ABIERTA", openingFund: "0.00" };
    throw new Error(`unexpected request ${path}`);
  });

  render(createElement(SalePaymentCheckout, {
    locale: "es",
    totalCents: 1210,
    sale: { customerId: null, lines: [{ productId: "p-1", quantity: 1, discount: 0 }] },
    permissions: ["ADMIN"],
    terminal: { storeName: "Tienda", terminalCode: "01", terminalId: "terminal-1" },
    testCashEnabled: true,
    onFinalized: vi.fn(),
  }));

  fireEvent.click(await screen.findByRole("button", { name: /Efectivo/ }));
  const dialog = screen.getByRole("dialog", { name: "Cobro en efectivo" });
  fireEvent.click(within(dialog).getByRole("button", { name: "Exacto" }));
  fireEvent.click(within(dialog).getByRole("button", { name: "Confirmar cobro" }));

  const openTestCash = await within(dialog).findByRole("button", { name: "Abrir caja de prueba" });
  fireEvent.click(openTestCash);

  expect(await within(dialog).findByRole("status")).toHaveTextContent("Caja de prueba abierta");
  expect(apiRequestMock.mock.calls.filter(([path]) => path.includes("/allocations"))).toHaveLength(1);
  expect(apiRequestMock.mock.calls.filter(([path]) => path.endsWith("/finalize"))).toHaveLength(1);
});
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
npm.cmd test -- SalePaymentCheckout.test.ts -t "offers test cash inside the active cash dialog"
```

Expected: FAIL because `Abrir caja de prueba` is not found within the cash dialog.

- [ ] **Step 3: Add optional recovery props to `CashPaymentDialog`**

Extend its props and render the recovery state before the footer:

```tsx
type CashPaymentDialogProps = {
  // existing fields
  testCashAction?: { label: string; onOpen: () => void };
  testCashStatus?: string;
};

{testCashAction && (
  <button
    className="test-cash-session-button"
    type="button"
    disabled={submitting}
    onClick={testCashAction.onOpen}
  >
    {testCashAction.label}
  </button>
)}
{testCashStatus && <p className="test-cash-session-status" role="status">{testCashStatus}</p>}
```

- [ ] **Step 4: Pass the existing recovery action from `SalePaymentCheckout`**

Build the dialog with the same predicate already used by the lateral panel:

```tsx
const testCashOffered = shouldOfferTestCashSession(
  testCashEnabled,
  server?.status,
  testCashRequired,
  terminal.terminalId,
);

const cashDialog = cashOpen ? (
  <CashPaymentDialog
    totalCents={totalCents}
    submitting={busy}
    error={error}
    initialMode="touch"
    onCancel={cancelCashDialog}
    onConfirm={confirmCash}
    testCashAction={testCashOffered ? {
      label: busy ? t("payment.testCash.opening") : t("payment.testCash.open"),
      onOpen: () => void openTestCashSession(),
    } : undefined}
    testCashStatus={testCashStatus}
  />
) : null;
```

Keep the existing lateral action unchanged for recovery when `cashOpen` is false.

- [ ] **Step 5: Keep the action compact inside the modal**

Add a dialog-specific override without changing the lateral button:

```css
.cash-payment-entry-dialog > .test-cash-session-button {
  width: calc(100% - 24px);
  margin: 8px 12px 0;
}

.cash-payment-entry-dialog > .test-cash-session-status {
  margin: 8px 12px 0;
}
```

- [ ] **Step 6: Run focused and full frontend verification**

Run:

```powershell
npm.cmd test -- SalePaymentCheckout.test.ts
npm.cmd test
npm.cmd run build
```

Expected: all tests pass; APP GESTIÓN and APP VENTA builds succeed.

- [ ] **Step 7: Commit the fix**

```powershell
git add -- frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts frontend/packages/app-common/src/components/SalePaymentCheckout.tsx frontend/packages/app-common/src/components/CashPaymentDialog.tsx frontend/packages/app-common/src/styles/tpv.css
git commit -m "fix(payment): expose test cash action in dialog"
```

