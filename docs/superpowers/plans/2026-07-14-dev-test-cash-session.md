# Dev Test Cash Session Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a development-only `Abrir caja de prueba` action that opens the demo terminal's real cash session with a zero opening fund and preserves the covered payment for an explicit finalization retry.

**Architecture:** Seed one deterministic closed zero-balance cash session through the existing dev-only data seeder so the real `CashSessionService.open` rules remain unchanged. Add an opt-in `testCashEnabled` capability to `SalePaymentCheckout`, then enable it only from APP VENTA when `import.meta.env.DEV` is true. The action calls the existing `/cash/sessions/open` endpoint and never retries payment finalization automatically.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL, JUnit 5, React 19, TypeScript, Vitest, Testing Library, CSS, Vite.

## Global Constraints

- The button text is `Abrir caja de prueba`; loading text is `Abriendo caja...`; success text is `Caja de prueba abierta. Pulse Finalizar venta.`.
- The button is opt-in through `testCashEnabled?: boolean`, which defaults to `false`.
- APP VENTA passes `testCashEnabled={import.meta.env.DEV && app === "venta"}`; production must not enable it.
- Offer the action only for a `COVERED` payment after an error containing `sesion de caja abierta`, normalized to lowercase without diacritics, and only when `terminal.terminalId` exists.
- Opening uses the existing `POST /cash/sessions/open` endpoint with body `{ terminalId }`, the current token, permissions, and validations.
- Opening the test cash session must never create another payment allocation or automatically call payment finalization.
- The dev seed inserts one deterministic historical `CERRADA` session with all monetary values at `0.00`; it must be idempotent and remain under the Spring `dev` profile.
- Do not add a bypass endpoint, relax `CashSessionService`, auto-open a cash session, or affect production seed data.

---

## File Structure

- `backend/src/main/java/com/tpverp/backend/dev/DevSampleDataSeeder.java`: creates the dev-only zero-balance cash history required by the real first-opening rule.
- `backend/src/test/java/com/tpverp/backend/dev/DevSampleDataSeederPostgreSqlTest.java`: verifies zero values and idempotency against PostgreSQL.
- `backend/src/test/java/com/tpverp/backend/cash/CashSessionServiceTest.java`: protects the service invariant that zero retained cash opens a zero-fund session.
- `frontend/packages/app-common/src/components/SalePaymentCheckout.tsx`: detects the missing-cash-session failure, exposes the opt-in action, calls the existing endpoint, and preserves explicit retry semantics.
- `frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts`: tests visibility, request payload, success/error states, and non-duplication.
- `frontend/packages/app-common/src/components/SaleScreen.tsx`: enables the opt-in only for APP VENTA in Vite development.
- `frontend/packages/app-common/src/components/SaleScreen.test.tsx`: verifies the capability is wired into the checkout.
- `frontend/packages/app-common/src/i18n/MessagesEs.ts`, `MessagesEn.ts`, `MessagesZh.ts`: provide the four test-cash UI strings.
- `frontend/packages/app-common/src/styles/tpv.css`: gives the action and status the existing compact ERP presentation.

### Task 1: Seed a closed zero-balance cash history in dev

**Files:**
- Modify: `backend/src/test/java/com/tpverp/backend/dev/DevSampleDataSeederPostgreSqlTest.java`
- Modify: `backend/src/test/java/com/tpverp/backend/cash/CashSessionServiceTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/dev/DevSampleDataSeeder.java`

**Interfaces:**
- Consumes: deterministic `DevSampleDataSeeder.id(String)`, demo `STORE`, `USER`, `TERMINAL`, `NOW`, the existing `sesion_caja` schema, and `CashSessionService.open(UUID, Authentication)`.
- Produces: one idempotent closed session with id `id("cash-session-history")`, `fondo_dejado = 0.00`, and no automatically open session.

- [ ] **Step 1: Write the failing PostgreSQL seed test and zero-retained service test**

In `DevSampleDataSeederPostgreSqlTest`, autowire the seeder and add:

```java
@Autowired
private DevSampleDataSeeder seeder;

@Test
void seedsOneClosedZeroBalanceCashHistoryIdempotently() {
    assertThat(jdbc.queryForObject("""
            select count(*)
            from sesion_caja
            where estado = 'CERRADA'
              and fondo_inicial = 0.00
              and efectivo_teorico = 0.00
              and fondo_dejado = 0.00
              and descuadre = 0.00
            """, Integer.class)).isEqualTo(1);

    seeder.seed();

    assertThat(jdbc.queryForObject("""
            select count(*)
            from sesion_caja
            where estado = 'CERRADA'
              and fondo_inicial = 0.00
              and efectivo_teorico = 0.00
              and fondo_dejado = 0.00
              and descuadre = 0.00
            """, Integer.class)).isEqualTo(1);
}
```

In `CashSessionServiceTest`, add the service-level invariant:

```java
@Test
void opensWithZeroFundFromClosedDemoHistory() {
    var fixture = serviceFixture();
    var previous = closedSession(
            fixture.store.getId(), fixture.terminal.getId(), fixture.user.getId(), "0.00");
    when(fixture.sessions.findByTerminalIdAndStatus(
            fixture.terminal.getId(), CashSessionStatus.ABIERTA))
            .thenReturn(Optional.empty());
    when(fixture.sessions.findFirstByTerminalIdAndStatusOrderByClosedAtDesc(
            fixture.terminal.getId(), CashSessionStatus.CERRADA))
            .thenReturn(Optional.of(previous));
    when(fixture.movements.findAllByTerminalIdAndSesionCajaIsNullOrderByCreadoEnAsc(
            fixture.terminal.getId()))
            .thenReturn(List.of());

    var opened = fixture.service.open(
            fixture.terminal.getId(), salesAuthentication(fixture.user));

    assertThat(opened.status()).isEqualTo(CashSessionStatus.ABIERTA);
    assertThat(opened.openingFund()).isEqualByComparingTo("0.00");
}
```

- [ ] **Step 2: Run the focused backend tests and verify the seed assertion fails**

Run from `backend`:

```powershell
.\mvnw.cmd -Dtest=DevSampleDataSeederPostgreSqlTest,CashSessionServiceTest test
```

Expected: `opensWithZeroFundFromClosedDemoHistory` passes against existing service behavior; `seedsOneClosedZeroBalanceCashHistoryIdempotently` fails because the dev seed has no matching closed cash session.

- [ ] **Step 3: Add the deterministic closed session to the dev seeder**

Add the constant beside the other deterministic IDs:

```java
private static final UUID CASH_SESSION_HISTORY = id("cash-session-history");
```

Call the new method immediately after `seedSecurity()` in `seed()`:

```java
seedSecurity();
seedCashSessionHistory();
seedLicense(installation);
```

Add this method after `seedSecurity()`:

```java
private void seedCashSessionHistory() {
    jdbc.update("""
            insert into sesion_caja
                (id, tienda_id, terminal_id, usuario_apertura_id, abierta_en,
                 fondo_inicial, usuario_cierre_id, cerrada_en, efectivo_teorico,
                 fondo_dejado, descuadre, estado, cierre_tardio)
            values (?, ?, ?, ?, ?, 0.00, ?, ?, 0.00, 0.00, 0.00, 'CERRADA', false)
            on conflict (id) do nothing
            """,
            CASH_SESSION_HISTORY,
            STORE,
            TERMINAL,
            USER,
            ts(NOW.minusSeconds(7_200)),
            USER,
            ts(NOW.minusSeconds(3_600)));
}
```

- [ ] **Step 4: Run the focused backend tests and verify they pass**

Run from `backend`:

```powershell
.\mvnw.cmd -Dtest=DevSampleDataSeederPostgreSqlTest,CashSessionServiceTest test
```

Expected: both test classes pass; rerunning `seeder.seed()` leaves exactly one matching historical session.

- [ ] **Step 5: Commit the backend demo preparation**

```powershell
git add backend/src/main/java/com/tpverp/backend/dev/DevSampleDataSeeder.java backend/src/test/java/com/tpverp/backend/dev/DevSampleDataSeederPostgreSqlTest.java backend/src/test/java/com/tpverp/backend/cash/CashSessionServiceTest.java
git commit -m "feat(dev): seed zero test cash history"
```

### Task 2: Add the opt-in test-cash action to payment checkout

**Files:**
- Modify: `frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts`
- Modify: `frontend/packages/app-common/src/components/SalePaymentCheckout.tsx`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEs.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEn.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesZh.ts`

**Interfaces:**
- Consumes: `ApiError`, `apiRequest`, `ServerSession.status`, `TerminalContext.terminalId`, the existing `busy`, `error`, and `FINALIZE_RETRY` flow.
- Produces: optional prop `testCashEnabled?: boolean`; pure helpers `isMissingCashSessionError(message: string): boolean` and `shouldOfferTestCashSession(enabled: boolean, status: string | undefined, missing: boolean, terminalId?: string): boolean`; POST `/cash/sessions/open` with `{terminalId}`.

- [ ] **Step 1: Write failing pure-helper tests**

Import the new helpers in `SalePaymentCheckout.test.ts` and add:

```ts
it("recognizes the missing cash-session error with or without accents", () => {
  expect(isMissingCashSessionError("No hay una sesion de caja abierta")).toBe(true);
  expect(isMissingCashSessionError("No hay una sesión de caja abierta")).toBe(true);
  expect(isMissingCashSessionError("La terminal no existe")).toBe(false);
});

it("offers test cash only for an enabled covered checkout with a terminal", () => {
  expect(shouldOfferTestCashSession(true, "COVERED", true, "terminal-1")).toBe(true);
  expect(shouldOfferTestCashSession(false, "COVERED", true, "terminal-1")).toBe(false);
  expect(shouldOfferTestCashSession(true, "COLLECTING", true, "terminal-1")).toBe(false);
  expect(shouldOfferTestCashSession(true, "COVERED", false, "terminal-1")).toBe(false);
  expect(shouldOfferTestCashSession(true, "COVERED", true, undefined)).toBe(false);
});
```

- [ ] **Step 2: Run the focused frontend test and verify the helper imports fail**

Run from `frontend`:

```powershell
npm.cmd test -- packages/app-common/src/components/SalePaymentCheckout.test.ts
```

Expected: FAIL because `isMissingCashSessionError` and `shouldOfferTestCashSession` are not exported.

- [ ] **Step 3: Implement the pure guards and opt-in prop**

Add to `Props`:

```ts
testCashEnabled?: boolean;
```

Add the helpers beside the existing exported checkout helpers:

```ts
export function isMissingCashSessionError(message: string) {
  return message
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLocaleLowerCase("es")
    .includes("sesion de caja abierta");
}

export function shouldOfferTestCashSession(
  enabled: boolean,
  status: string | undefined,
  missing: boolean,
  terminalId?: string,
) {
  return enabled && status === "COVERED" && missing && Boolean(terminalId);
}
```

Default the prop in the component signature:

```ts
export const SalePaymentCheckout = forwardRef<SalePaymentCheckoutHandle, Props>(
  function SalePaymentCheckout({
    locale,
    totalCents,
    sale,
    token,
    permissions,
    terminal,
    disabled,
    testCashEnabled = false,
    onLockedChange,
    onFinalized,
  }, ref) {
```

- [ ] **Step 4: Run the helper tests and verify they pass**

Run from `frontend`:

```powershell
npm.cmd test -- packages/app-common/src/components/SalePaymentCheckout.test.ts
```

Expected: all `SalePaymentCheckout` tests pass.

- [ ] **Step 5: Write the failing component test for opening test cash without auto-finalization**

Add this test to `SalePaymentCheckout.test.ts`:

```tsx
it("opens test cash for a covered checkout and leaves finalization explicit", async () => {
  const session = {
    id: "session-test-cash",
    total: "12.10",
    status: "COVERED",
    allocations: [{
      id: "cash-1",
      idempotencyKey: "cash-1",
      kind: "CASH" as const,
      amount: "12.10",
      status: "APPROVED",
    }],
  };
  let finalizeCalls = 0;
  apiRequestMock.mockImplementation(async (path: string, options?: { body?: unknown }) => {
    if (path === "/terminal-configuration/payment") return {
      rules: { cardManualEnabled: true, integratedCardEnabled: false },
      providerDescriptors: [],
      configuration: { provider: "", enabled: false },
    };
    if (path === "/pos/payment-sessions/active") return session;
    if (path === "/pos/payment-sessions/session-test-cash/finalize") {
      finalizeCalls += 1;
      throw new ApiError("No hay una sesión de caja abierta", 409);
    }
    if (path === "/cash/sessions/open") {
      expect(options?.body).toEqual({ terminalId: "terminal-1" });
      return { id: "cash-session-1", status: "ABIERTA", openingFund: "0.00" };
    }
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

  const finalize = await screen.findByRole("button", { name: "Finalizar venta" });
  fireEvent.click(finalize);
  const openTestCash = await screen.findByRole("button", { name: "Abrir caja de prueba" });
  fireEvent.click(openTestCash);

  expect(await screen.findByRole("status")).toHaveTextContent(
    "Caja de prueba abierta. Pulse Finalizar venta.",
  );
  expect(finalizeCalls).toBe(1);
  expect(apiRequestMock.mock.calls.filter(([path]) =>
    path === "/cash/sessions/open")).toHaveLength(1);
});
```

- [ ] **Step 6: Run the component test and verify the button assertion fails**

Run from `frontend`:

```powershell
npm.cmd test -- packages/app-common/src/components/SalePaymentCheckout.test.ts
```

Expected: FAIL because the missing-session state, button, endpoint call, and success status are not implemented.

- [ ] **Step 7: Add translations and the explicit opening flow**

Add these keys to all three message maps:

```ts
// MessagesEs.ts
"payment.testCash.open": "Abrir caja de prueba",
"payment.testCash.opening": "Abriendo caja...",
"payment.testCash.opened": "Caja de prueba abierta. Pulse Finalizar venta.",
"payment.testCash.error": "No se pudo abrir la caja de prueba",

// MessagesEn.ts
"payment.testCash.open": "Open test cash session",
"payment.testCash.opening": "Opening test cash session...",
"payment.testCash.opened": "Test cash session opened. Select Finalize sale.",
"payment.testCash.error": "Could not open the test cash session",

// MessagesZh.ts
"payment.testCash.open": "打开测试钱箱",
"payment.testCash.opening": "正在打开测试钱箱...",
"payment.testCash.opened": "测试钱箱已打开。请选择完成销售。",
"payment.testCash.error": "无法打开测试钱箱",
```

Add state beside the existing payment state:

```ts
const [testCashRequired, setTestCashRequired] = useState(false);
const [testCashStatus, setTestCashStatus] = useState("");
```

Add a small error marker and opening function inside the component:

```ts
function markTestCashRequirement(failure: unknown) {
  if (testCashEnabled && failure instanceof ApiError
      && isMissingCashSessionError(failure.message)) {
    setTestCashRequired(true);
    setTestCashStatus("");
  }
}

async function openTestCashSession() {
  if (!testCashEnabled || !terminal.terminalId || busy) return;
  setBusy(true);
  setTestCashStatus("");
  try {
    await apiRequest("/cash/sessions/open", {
      token,
      body: { terminalId: terminal.terminalId },
    });
    setError("");
    setTestCashRequired(false);
    setTestCashStatus(t("payment.testCash.opened"));
  } catch (failure) {
    setError(failure instanceof ApiError
      ? failure.message
      : t("payment.testCash.error"));
  } finally {
    setBusy(false);
  }
}
```

Call `markTestCashRequirement(e)` at the beginning of both the `retryFinish` catch and the `add` catch. Reset both test-cash states from `clearRecoveredSession`:

```ts
setTestCashRequired(false);
setTestCashStatus("");
```

In the `FINALIZE_RETRY` branch, render the existing error, status, and action together:

```tsx
{error && <p role="alert">{error}</p>}
{testCashStatus && <p className="test-cash-session-status" role="status">{testCashStatus}</p>}
{shouldOfferTestCashSession(
  testCashEnabled,
  server!.status,
  testCashRequired,
  terminal.terminalId,
) && (
  <button
    className="test-cash-session-button"
    type="button"
    disabled={busy}
    onClick={() => void openTestCashSession()}
  >
    {busy ? t("payment.testCash.opening") : t("payment.testCash.open")}
  </button>
)}
```

Do not call `retryFinish`, `add`, or `finish` from `openTestCashSession`.

- [ ] **Step 8: Run checkout tests and verify the flow passes**

Run from `frontend`:

```powershell
npm.cmd test -- packages/app-common/src/components/SalePaymentCheckout.test.ts
```

Expected: all tests pass; the endpoint is called once and `finalizeCalls` remains `1` after opening test cash.

- [ ] **Step 9: Commit the opt-in checkout behavior**

```powershell
git add frontend/packages/app-common/src/components/SalePaymentCheckout.tsx frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts frontend/packages/app-common/src/i18n/MessagesEs.ts frontend/packages/app-common/src/i18n/MessagesEn.ts frontend/packages/app-common/src/i18n/MessagesZh.ts
git commit -m "feat(payment): add test cash session action"
```

### Task 3: Enable and style the action only in APP VENTA development

**Files:**
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

**Interfaces:**
- Consumes: Task 2's `SalePaymentCheckout.testCashEnabled` prop, `test-cash-session-button`, and `test-cash-session-status` classes.
- Produces: `testCashEnabled={import.meta.env.DEV && app === "venta"}` from the real sale screen and compact ERP presentation for the contextual action.

- [ ] **Step 1: Write the failing SaleScreen wiring test**

Extend the existing hoisted mock state in `SaleScreen.test.tsx`:

```ts
const { prepareApplicationClose, prepareLogout, checkoutHandle, checkoutProps } = vi.hoisted(() => ({
  prepareApplicationClose: vi.fn(),
  prepareLogout: vi.fn(),
  checkoutHandle: { attached: true },
  checkoutProps: { current: null as null | { testCashEnabled?: boolean } },
}));
```

Capture props in the checkout mock:

```tsx
SalePaymentCheckout: forwardRef(function MockSalePaymentCheckout(props, ref) {
  checkoutProps.current = props;
  useImperativeHandle(ref, () => checkoutHandle.attached
    ? ({ prepareApplicationClose, prepareLogout })
    : null);
  return null;
})
```

Reset `checkoutProps.current` in `afterEach`, then add:

```tsx
it("enables test cash for APP VENTA only in Vite development", async () => {
  renderSaleScreen();
  await waitFor(() => expect(checkoutProps.current).not.toBeNull());
  expect(checkoutProps.current?.testCashEnabled).toBe(import.meta.env.DEV);
});
```

- [ ] **Step 2: Run the SaleScreen test and verify it fails**

Run from `frontend`:

```powershell
npm.cmd test -- packages/app-common/src/components/SaleScreen.test.tsx
```

Expected: FAIL because `SaleScreen` does not pass `testCashEnabled`.

- [ ] **Step 3: Wire the Vite development guard**

Expand the `SalePaymentCheckout` JSX in `SaleScreen.tsx` and add the prop without changing existing callbacks:

```tsx
<SalePaymentCheckout
  ref={paymentCheckoutRef}
  locale={locale}
  totalCents={Math.round(total * 100)}
  sale={cashSaleRequest()}
  token={session.accessToken}
  permissions={session.permissions}
  terminal={terminalContext}
  disabled={lines.length === 0 || total <= 0}
  testCashEnabled={import.meta.env.DEV && app === "venta"}
  onLockedChange={(locked, reservedTotalCents) => {
    setPaymentLocked(locked);
    setReservedPaymentTotalCents(
      locked && reservedTotalCents != null ? reservedTotalCents : null,
    );
  }}
  onFinalized={(ticketNumber, authoritativeTotalCents, receivedCents) => {
    setLines([]);
    setSelectedProductId(null);
    setSelectedCustomer(null);
    setQuery("");
    setReservedPaymentTotalCents(null);
    setCashResult(cashResultFromFinalization(
      ticketNumber,
      authoritativeTotalCents,
      receivedCents,
    ));
  }}
/>
```

- [ ] **Step 4: Add compact ERP styling**

Append to `tpv.css` after the current sale-payment overrides:

```css
.test-cash-session-button {
  width: 100%;
  min-height: 34px;
  margin-top: 6px;
  padding: 5px 10px;
  border: 1px solid var(--tpv-v3-blue);
  border-radius: var(--tpv-v3-radius);
  background: var(--tpv-v3-blue);
  color: #ffffff;
  box-shadow: none;
  font-weight: 800;
}

.test-cash-session-status {
  margin: 6px 0 0;
  padding: 6px 8px;
  border: 1px solid #b9ddc7;
  border-radius: var(--tpv-v3-radius);
  background: #eef8f2;
  color: #116634;
  font-size: 12px;
  font-weight: 750;
}
```

- [ ] **Step 5: Run frontend regression tests**

Run from `frontend`:

```powershell
npm.cmd test -- packages/app-common/src/components/SaleScreen.test.tsx packages/app-common/src/components/SalePaymentCheckout.test.ts
```

Expected: both test files pass.

- [ ] **Step 6: Build APP VENTA in production mode**

Run from `frontend`:

```powershell
npm.cmd run build --workspace @tpverp/app-venta
```

Expected: TypeScript and Vite succeed. The production evaluation of `import.meta.env.DEV` is `false`, so the real APP VENTA checkout receives `testCashEnabled={false}`.

- [ ] **Step 7: Perform the complete development flow check**

Start the backend with its default `dev` profile and APP VENTA development server. Then:

1. Add a demo product to a sale.
2. Confirm an exact cash payment with no open cash session.
3. Verify the covered checkout shows the missing-session error and `Abrir caja de prueba`.
4. Press the button and verify it shows `Abriendo caja...`, then the success status.
5. Verify no second allocation appears and the sale remains pending.
6. Press `Finalizar venta` and verify one ticket is produced.
7. Reload and confirm the existing open cash session prevents another opening, while normal sales can finalize.

- [ ] **Step 8: Commit the development-only wiring and style**

```powershell
git add frontend/packages/app-common/src/components/SaleScreen.tsx frontend/packages/app-common/src/components/SaleScreen.test.tsx frontend/packages/app-common/src/styles/tpv.css
git commit -m "feat(sale): expose dev test cash button"
```

### Task 4: Run final cross-layer verification

**Files:**
- Verify only; no planned file changes.

**Interfaces:**
- Consumes: Tasks 1-3 commits.
- Produces: fresh evidence that backend cash rules, frontend checkout recovery, production build, and the existing UI remain healthy together.

- [ ] **Step 1: Run the full frontend unit suite**

Run from `frontend`:

```powershell
npm.cmd test
```

Expected: every Vitest file passes with zero failures.

- [ ] **Step 2: Build all frontend workspaces**

Run from `frontend`:

```powershell
npm.cmd run build
```

Expected: every workspace build exits successfully.

- [ ] **Step 3: Run the backend cash and dev-seed suites**

Run from `backend`:

```powershell
.\mvnw.cmd -Dtest=DevSampleDataSeederTest,DevSampleDataSeederPostgreSqlTest,CashSessionServiceTest,CashPaymentRecorderTest,CashControllerContractTest,DocumentServiceTest test
```

Expected: all listed backend tests pass with zero failures.

- [ ] **Step 4: Confirm the final diff and workspace state**

```powershell
git diff --check
git status --short
git log -4 --oneline
```

Expected: `git diff --check` is clean; no uncommitted implementation files remain; the three implementation commits appear above the design and plan commits.
