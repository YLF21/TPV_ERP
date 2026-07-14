# Individual Checkout Classic UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the visible split-payment entry with direct Cash, Card, and disabled Customer Pending actions while retaining the persistent payment-session safety model.

**Architecture:** `SalePaymentCheckout` remains the stateful payment orchestrator, but delegates ordinary presentation to focused button and dialog components. Cash and card each create one allocation for the full outstanding ticket; exceptional recovered sessions continue to expose query, finalization, operation management, and compensation controls.

**Tech Stack:** React 19, TypeScript, Vitest, Spring Boot 4, Java 25, PostgreSQL/Flyway.

## Global Constraints

- Preserve the Business Classic layout and visible F10/F11/F12 shortcuts.
- Cash opens in touch mode on every new dialog and may switch to physical keyboard for that dialog.
- Customer Pending remains visible and disabled; no receivable behavior is added.
- Do not remove backend split-payment support or its tests.
- Do not create a second charge after timeout, uncertain HTTP outcome, or ticket-finalization failure.
- Do not push changes to a remote repository.

---

### Task 1: Individual payment action bar

**Files:**
- Create: `frontend/packages/app-common/src/components/IndividualPaymentActions.tsx`
- Create: `frontend/packages/app-common/src/components/IndividualPaymentActions.test.tsx`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEs.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEn.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesZh.ts`

**Interfaces:**
- Consumes: `disabled: boolean`, `busy: boolean`, `cardEnabled: boolean`.
- Produces: `IndividualPaymentActions` with `onCash(): void` and `onCard(): void` callbacks.

- [ ] **Step 1: Write failing rendering and callback tests**

```tsx
render(<IndividualPaymentActions disabled={false} busy={false} cardEnabled onCash={onCash} onCard={onCard} />);
expect(screen.getByRole("button", { name: /Efectivo/ })).toBeEnabled();
expect(screen.getByRole("button", { name: /Tarjeta/ })).toBeEnabled();
expect(screen.getByRole("button", { name: /Pendiente cliente/ })).toBeDisabled();
fireEvent.click(screen.getByRole("button", { name: /Efectivo/ }));
expect(onCash).toHaveBeenCalledOnce();
```

- [ ] **Step 2: Run the focused test and confirm the missing component failure**

Run: `npm.cmd test -- IndividualPaymentActions.test.tsx`

Expected: FAIL because `IndividualPaymentActions` does not exist.

- [ ] **Step 3: Implement the compact action component**

```tsx
type Props = {
  disabled: boolean;
  busy: boolean;
  cardEnabled: boolean;
  onCash: () => void;
  onCard: () => void;
};

export function IndividualPaymentActions(props: Props) {
  return <div className="sale-payment-actions individual-payment-actions">
    <button type="button" disabled={props.disabled || props.busy} onClick={props.onCash}>
      <span>Efectivo</span><kbd>F10</kbd>
    </button>
    <button type="button" disabled={props.disabled || props.busy || !props.cardEnabled} onClick={props.onCard}>
      <span>Tarjeta</span><kbd>F11</kbd>
    </button>
    <button type="button" disabled title="Funcionalidad pendiente de definir">
      <span>Pendiente cliente</span><kbd>F12</kbd>
    </button>
  </div>;
}
```

- [ ] **Step 4: Run the focused test**

Run: `npm.cmd test -- IndividualPaymentActions.test.tsx`

Expected: PASS.

- [ ] **Step 5: Commit the action bar**

```bash
git add frontend/packages/app-common/src/components/IndividualPaymentActions.tsx frontend/packages/app-common/src/components/IndividualPaymentActions.test.tsx frontend/packages/app-common/src/i18n/MessagesEs.ts frontend/packages/app-common/src/i18n/MessagesEn.ts frontend/packages/app-common/src/i18n/MessagesZh.ts
git commit -m "feat(payment): add individual checkout actions"
```

### Task 2: Direct cash calculator and result propagation

**Files:**
- Modify: `frontend/packages/app-common/src/components/CashPaymentDialog.tsx`
- Modify: `frontend/packages/app-common/src/components/CashPaymentDialog.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SalePaymentCheckout.tsx`
- Modify: `frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`

**Interfaces:**
- Consumes: existing `add({ kind, amountCents, provider, reference })` session operation.
- Produces: `onFinalized(ticketNumber, totalCents, cashReceivedCents?)` and a touch-first cash dialog.

- [ ] **Step 1: Add failing tests for touch-first cash behavior**

```tsx
render(<CashPaymentDialog totalCents={1210} submitting={false} error="" initialMode="touch" onCancel={onCancel} onConfirm={onConfirm} />);
expect(screen.getByRole("button", { name: "Tecla 7" })).toBeVisible();
fireEvent.click(screen.getByRole("button", { name: "Usar teclado físico" }));
expect(screen.queryByRole("button", { name: "Tecla 7" })).not.toBeInTheDocument();
```

Add a checkout test asserting that clicking Cash opens the calculator and confirming `20,00` submits `{ kind: "CASH", amountCents: totalCents }` exactly once.

- [ ] **Step 2: Run focused tests and confirm failure**

Run: `npm.cmd test -- CashPaymentDialog.test.tsx SalePaymentCheckout.test.ts SaleScreen.test.tsx`

Expected: FAIL because checkout does not expose the individual cash action.

- [ ] **Step 3: Add touch-first state and cash metadata**

In `SalePaymentCheckout`, add:

```tsx
const [cashOpen, setCashOpen] = useState(false);
const [cashReceivedCents, setCashReceivedCents] = useState<number | undefined>();

function confirmCash(receivedCents: number) {
  setCashReceivedCents(receivedCents);
  void add({ kind: "CASH", amountCents: totalCents });
}
```

Render the dialog with `initialMode="touch"`. Close it only on cancellation or successful finalization. Extend `finish` so `onFinalized` receives the captured amount, then clear the ephemeral value.

- [ ] **Step 4: Preserve received amount and change on the result dialog**

Update the SaleScreen callback:

```tsx
onFinalized={(ticketNumber, authoritativeTotalCents, receivedCents) => {
  setLines([]);
  setSelectedProductId(null);
  setSelectedCustomer(null);
  setQuery("");
  setReservedPaymentTotalCents(null);
  setCashResult({
    ticketNumber,
    totalCents: authoritativeTotalCents,
    receivedCents: receivedCents ?? authoritativeTotalCents,
  });
}}
```

- [ ] **Step 5: Run the focused cash and sale tests**

Run: `npm.cmd test -- CashPaymentDialog.test.tsx SalePaymentCheckout.test.ts SaleScreen.test.tsx`

Expected: PASS.

- [ ] **Step 6: Commit the direct cash flow**

```bash
git add frontend/packages/app-common/src/components/CashPaymentDialog.tsx frontend/packages/app-common/src/components/CashPaymentDialog.test.tsx frontend/packages/app-common/src/components/SalePaymentCheckout.tsx frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts frontend/packages/app-common/src/components/SaleScreen.tsx frontend/packages/app-common/src/components/SaleScreen.test.tsx
git commit -m "feat(payment): open touch cash checkout directly"
```

### Task 3: Direct integrated and manual card flow

**Files:**
- Create: `frontend/packages/app-common/src/components/ManualCardReferenceDialog.tsx`
- Create: `frontend/packages/app-common/src/components/ManualCardReferenceDialog.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SalePaymentCheckout.tsx`
- Modify: `frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts`

**Interfaces:**
- Consumes: loaded `providers: string[]`, `manual: boolean`, `add()` and persistent session state.
- Produces: direct full-total integrated allocation or trimmed manual-card reference.

- [ ] **Step 1: Write failing direct-card tests**

```tsx
fireEvent.click(screen.getByRole("button", { name: /Tarjeta/ }));
expect(postAllocation).toHaveBeenCalledWith(expect.objectContaining({
  kind: "INTEGRATED_CARD",
  amount: "12.10",
  provider: "GLOBAL_PAYMENTS",
}));
```

Add a manual-mode test verifying that Card opens a dialog, whitespace-only references are rejected, and `" REF-1 "` is submitted as `"REF-1"`.

- [ ] **Step 2: Run focused tests and confirm failure**

Run: `npm.cmd test -- ManualCardReferenceDialog.test.tsx SalePaymentCheckout.test.ts`

Expected: FAIL because the direct card dialog and action do not exist.

- [ ] **Step 3: Implement the ephemeral manual-reference dialog**

```tsx
type Props = { busy: boolean; onCancel: () => void; onConfirm: (reference: string) => void };

export function ManualCardReferenceDialog({ busy, onCancel, onConfirm }: Props) {
  const [reference, setReference] = useState("");
  const normalized = reference.trim();
  return <div role="dialog" aria-modal="true" aria-labelledby="manual-card-title">
    <h2 id="manual-card-title">Cobro con tarjeta manual</h2>
    <label>Referencia obligatoria<input value={reference} autoComplete="off" onChange={event => setReference(event.currentTarget.value)} /></label>
    <button type="button" disabled={busy || !normalized} onClick={() => onConfirm(normalized)}>Confirmar</button>
    <button type="button" disabled={busy} onClick={onCancel}>Cancelar</button>
  </div>;
}
```

- [ ] **Step 4: Route Card to the configured mode**

```tsx
function startCard() {
  if (providers[0]) {
    void add({ kind: "INTEGRATED_CARD", amountCents: totalCents, provider: providers[0] });
  } else if (manual) {
    setManualCardOpen(true);
  }
}
```

Clear the manual reference on confirm, cancel, unmount, and finalization. Preserve the current allocation attempt key for timeout and HTTP uncertainty.

- [ ] **Step 5: Run focused card tests**

Run: `npm.cmd test -- ManualCardReferenceDialog.test.tsx SalePaymentCheckout.test.ts`

Expected: PASS.

- [ ] **Step 6: Commit the direct card flow**

```bash
git add frontend/packages/app-common/src/components/ManualCardReferenceDialog.tsx frontend/packages/app-common/src/components/ManualCardReferenceDialog.test.tsx frontend/packages/app-common/src/components/SalePaymentCheckout.tsx frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts
git commit -m "feat(payment): start individual card checkout directly"
```

### Task 4: Exceptional-session presentation and full verification

**Files:**
- Modify: `frontend/packages/app-common/src/components/SalePaymentCheckout.tsx`
- Modify: `frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

**Interfaces:**
- Consumes: `ServerSession.status`, existing `query`, `retryFinish`, `manage`, `cancel`, and `acknowledge` functions.
- Produces: ordinary three-button state and exceptional recovery-only state.

- [ ] **Step 1: Add failing state-transition tests**

Cover these exact cases:

```ts
expect(checkoutPresentation(null)).toBe("INDIVIDUAL_ACTIONS");
expect(checkoutPresentation("COLLECTING")).toBe("RECOVERY");
expect(checkoutPresentation("COVERED")).toBe("FINALIZE_RETRY");
expect(checkoutPresentation("COMPENSATION_REQUIRED")).toBe("COMPENSATION");
expect(checkoutPresentation("FINALIZED")).toBe("INDIVIDUAL_ACTIONS");
```

- [ ] **Step 2: Run the focused test and confirm failure**

Run: `npm.cmd test -- SalePaymentCheckout.test.ts`

Expected: FAIL because `checkoutPresentation` is not defined.

- [ ] **Step 3: Implement presentation selection and compact recovery UI**

```ts
export function checkoutPresentation(status?: string | null) {
  if (!status || status === "FINALIZED" || status === "CANCELLED") return "INDIVIDUAL_ACTIONS";
  if (status === "COVERED") return "FINALIZE_RETRY";
  if (status === "COMPENSATION_REQUIRED") return "COMPENSATION";
  return "RECOVERY";
}
```

Render `PaymentAllocationPanel` only for recovery, finalization retry, or compensation. Keep query/manage/cancel/acknowledge operations unchanged. Add Business Classic CSS for `.individual-payment-actions` and the two card/cash modal layouts without changing global tokens.

- [ ] **Step 4: Run the complete frontend test and build matrix**

Run:

```powershell
cd frontend
npm.cmd test
npm.cmd run build --workspace @tpverp/app-venta
npm.cmd run build --workspace @tpverp/app-gestion
```

Expected: all Vitest files pass and both Vite builds exit with code 0.

- [ ] **Step 5: Run the complete backend suite with PostgreSQL variables**

Run:

```powershell
cd backend
$env:TPV_TEST_DB_URL="jdbc:postgresql://localhost:5432/tpv_erp_test"
$env:TPV_TEST_DB_USERNAME="tpv_erp_test"
$env:TPV_TEST_DB_PASSWORD="admin"
$env:TPV_ERP_TEST_DB_URL=$env:TPV_TEST_DB_URL
$env:TPV_ERP_TEST_DB_USER=$env:TPV_TEST_DB_USERNAME
$env:TPV_ERP_TEST_DB_USERNAME=$env:TPV_TEST_DB_USERNAME
$env:TPV_ERP_TEST_DB_PASSWORD=$env:TPV_TEST_DB_PASSWORD
mvn.cmd test
```

Expected: BUILD SUCCESS with zero failures and zero errors.

- [ ] **Step 6: Verify the worktree and commit**

Run:

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors and only the planned frontend files are modified.

```bash
git add frontend/packages/app-common/src/components frontend/packages/app-common/src/styles/tpv.css
git commit -m "fix(payment): preserve recovery in individual checkout UI"
```

