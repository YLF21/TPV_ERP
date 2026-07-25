# APP VENTA Focused Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the remaining misleading copy and hardcoded Spanish in APP VENTA, verify desktop accessibility at the three supported resolutions and locales, and prove that concurrent sales cannot silently corrupt stock.

**Architecture:** Keep the existing React translator and component boundaries, adding only catalog keys and focused tests. Exercise responsive behavior through Playwright without changing the global browser matrix. Add one PostgreSQL integration test at the inventory gateway boundary; if the test exposes the current read/modify/write race, reuse the repository's existing pessimistic-lock query rather than introducing a new locking mechanism.

**Tech Stack:** React 18, TypeScript, Vitest, Playwright, Spring Boot, Spring Data JPA, PostgreSQL, Maven, Flyway.

## Global Constraints

- Preserve unrelated user and colleague changes in the dirty worktree.
- Do not add mobile support below 1024 px.
- Do not add a new accessibility dependency such as axe-core.
- Do not change payment-provider SDK behavior, VeriFactu certification, or dataphone integration.
- Do not create new business features.
- Keep the three message catalogs (`es`, `en`, `zh`) key-for-key identical.
- Use `apply_patch` for hand-written edits.
- Run the smallest red test before each implementation change, then the focused green test, then the broader regression command.
- Every task ends with an intentional commit containing only that task's files.

---

## Task 1: Localize every APP VENTA sale action dialog

**Files:**

- Modify: `frontend/packages/app-common/src/i18n/MessagesEs.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEn.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesZh.ts`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Verify: `frontend/packages/app-common/src/i18n/messages.test.ts`

- [ ] **Step 1: Add a failing locale-sensitive component test**

In `SaleScreen.test.tsx`, reuse the existing render/session/API helpers and add one parameterized test for `es`, `en`, and `zh`. Open the quantity, discount, customer, and remove-line dialogs using their existing controls or keyboard shortcuts. Assert the dialog title, form label, action buttons, and accessible input names in the selected locale.

The assertions must use a table rather than Spanish-only regular expressions:

```tsx
it.each([
  ["es", {
    quantityTitle: "Cambiar cantidad",
    quantityInput: "Nueva cantidad",
    cancel: "Cancelar",
    save: "Guardar"
  }],
  ["en", {
    quantityTitle: "Change quantity",
    quantityInput: "New quantity",
    cancel: "Cancel",
    save: "Save"
  }],
  ["zh", {
    quantityTitle: "更改数量",
    quantityInput: "新数量",
    cancel: "取消",
    save: "保存"
  }]
] as const)("localizes sale dialogs in %s", async (locale, expected) => {
  // Render SaleScreen with locale, add/select a line, open F2 dialog.
  expect(screen.getByRole("dialog", { name: expected.quantityTitle })).toBeInTheDocument();
  expect(screen.getByRole("spinbutton", { name: expected.quantityInput })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: expected.cancel })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: expected.save })).toBeInTheDocument();
});
```

Cover the remaining dialog strings in the same test or a second parameterized test:

- discount title, percentage label, and accessible input name;
- manager authorization title, explanation, username, password, authorize;
- customer title, search label/placeholder, loading/error/no-customer/fallback labels, close;
- remove-line title, confirmation sentence, cancel, and destructive action;
- close icon accessible name from `SaleActionDialog`.

- [ ] **Step 2: Run the focused test and confirm it fails**

Run:

```powershell
cd E:\workspace\gitwork\TPV_ERP\frontend
npm.cmd test -- SaleScreen.test.tsx
```

Expected: FAIL because English and Chinese render the current Spanish literals such as `Cambiar cantidad` and `Seleccionar cliente`.

- [ ] **Step 3: Add the complete message-key family in all three catalogs**

Add the following keys to `MessagesEs.ts`, `MessagesEn.ts`, and `MessagesZh.ts`:

```ts
"sale.dialog.close"
"sale.dialog.cancel"
"sale.dialog.save"
"sale.quantity.title"
"sale.quantity.label"
"sale.quantity.inputAria"
"sale.discount.title"
"sale.discount.label"
"sale.discount.inputAria"
"sale.discountAuthorization.title"
"sale.discountAuthorization.exceedsLimit"
"sale.discountAuthorization.managerUser"
"sale.discountAuthorization.managerPassword"
"sale.discountAuthorization.authorize"
"sale.customer.title"
"sale.customer.search"
"sale.customer.placeholder"
"sale.customer.loading"
"sale.customer.loadError"
"sale.customer.none"
"sale.customer.unnamed"
"sale.customer.noCode"
"sale.removeLine.title"
"sale.removeLine.confirm"
"sale.removeLine.productFallback"
"sale.removeLine.action"
```

Use interpolation tokens consistently in the three catalogs:

```ts
// Spanish
"sale.discountAuthorization.exceedsLimit":
  "El descuento del {discount}% supera tu límite del {limit}%.",
"sale.removeLine.confirm": "Se eliminará {product} del ticket.",

// English
"sale.discountAuthorization.exceedsLimit":
  "The {discount}% discount exceeds your {limit}% limit.",
"sale.removeLine.confirm": "{product} will be removed from the ticket.",

// Chinese
"sale.discountAuthorization.exceedsLimit":
  "{discount}% 的折扣超过了您的 {limit}% 限制。",
"sale.removeLine.confirm": "将从小票中移除 {product}。"
```

- [ ] **Step 4: Replace every hardcoded literal in `SaleScreen.tsx`**

Use the existing `t` translator for all visible and accessible text. For interpolated sentences, use the translator's existing parameter API if present; otherwise perform explicit token replacement in a tiny local helper without changing the global translator contract:

```tsx
const interpolate = (template: string, values: Record<string, string>) =>
  Object.entries(values).reduce(
    (result, [key, value]) => result.replaceAll(`{${key}}`, value),
    template
  );
```

Example replacement:

```tsx
<SaleActionDialog
  title={t("sale.quantity.title")}
  closeLabel={t("sale.dialog.close")}
  onClose={() => setActionDialog(null)}
>
  <label>
    <span>{t("sale.quantity.label")}</span>
    <input aria-label={t("sale.quantity.inputAria")} ... />
  </label>
  <div className="sale-action-buttons">
    <button type="button" onClick={() => setActionDialog(null)}>
      {t("sale.dialog.cancel")}
    </button>
    <button type="submit">{t("sale.dialog.save")}</button>
  </div>
</SaleActionDialog>
```

If `SaleActionDialog` does not currently accept `closeLabel`, add that required prop locally in `SaleScreen.tsx` and use it for the close button's `aria-label`. Do not leave a Spanish default that can leak into other locales.

- [ ] **Step 5: Run focused component and catalog tests**

Run:

```powershell
cd E:\workspace\gitwork\TPV_ERP\frontend
npm.cmd test -- SaleScreen.test.tsx messages.test.ts
```

Expected: PASS, including exact key parity among `es`, `en`, and `zh`.

- [ ] **Step 6: Run the full frontend unit suite**

Run:

```powershell
cd E:\workspace\gitwork\TPV_ERP\frontend
npm.cmd test
```

Expected: PASS.

- [ ] **Step 7: Commit Task 1**

```powershell
cd E:\workspace\gitwork\TPV_ERP
git add frontend/packages/app-common/src/components/SaleScreen.tsx frontend/packages/app-common/src/components/SaleScreen.test.tsx frontend/packages/app-common/src/i18n/MessagesEs.ts frontend/packages/app-common/src/i18n/MessagesEn.ts frontend/packages/app-common/src/i18n/MessagesZh.ts
git commit -m "fix: localize app venta action dialogs"
```

---

## Task 2: Replace stale provisional copy with truthful implemented-state copy

**Files:**

- Modify: `frontend/packages/app-common/src/i18n/MessagesEs.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEn.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesZh.ts`
- Modify: `frontend/packages/app-common/src/i18n/messages.test.ts`

- [ ] **Step 1: Add a failing copy-quality test**

Extend `messages.test.ts` with a table of the three stale keys and prohibit future-tense/provisional phrases in each locale:

```ts
it("does not describe implemented settings and coupons as future work", () => {
  const stalePatterns = [
    /se conectar[aá]n|se gestionar[aá]n|fase posterior/i,
    /will be connected|will be managed|later phase/i,
    /将在此连接|将在此管理|后续阶段/
  ];
  const keys = [
    "settings.user.placeholder",
    "settings.reports.placeholder",
    "promotion.coupon.placeholder"
  ] as const;

  for (const messages of Object.values(LocalizedMessages.values)) {
    for (const key of keys) {
      for (const pattern of stalePatterns) {
        expect(messages[key]).not.toMatch(pattern);
      }
    }
  }
});
```

- [ ] **Step 2: Run the catalog test and confirm it fails**

Run:

```powershell
cd E:\workspace\gitwork\TPV_ERP\frontend
npm.cmd test -- messages.test.ts
```

Expected: FAIL on the three currently provisional messages.

- [ ] **Step 3: Rewrite the three messages in all locales**

Use truthful descriptions of current behavior:

```ts
// Spanish
"settings.user.placeholder":
  "Consulta tu perfil, cambia la contraseña y elige el idioma de la aplicación.",
"settings.reports.placeholder":
  "Configura la densidad, impresión y exportación de los informes.",
"promotion.coupon.placeholder":
  "Introduce un código de cupón para recalcular la promoción y el total del ticket.",

// English
"settings.user.placeholder":
  "Review your profile, change your password, and choose the application language.",
"settings.reports.placeholder":
  "Configure report density, printing, and export.",
"promotion.coupon.placeholder":
  "Enter a coupon code to recalculate the promotion and ticket total.",

// Chinese
"settings.user.placeholder":
  "查看个人资料、修改密码并选择应用语言。",
"settings.reports.placeholder":
  "配置报表密度、打印和导出。",
"promotion.coupon.placeholder":
  "输入优惠券代码以重新计算促销和小票总额。"
```

- [ ] **Step 4: Run catalog and full frontend unit tests**

Run:

```powershell
cd E:\workspace\gitwork\TPV_ERP\frontend
npm.cmd test -- messages.test.ts
npm.cmd test
```

Expected: both commands PASS.

- [ ] **Step 5: Commit Task 2**

```powershell
cd E:\workspace\gitwork\TPV_ERP
git add frontend/packages/app-common/src/i18n/MessagesEs.ts frontend/packages/app-common/src/i18n/MessagesEn.ts frontend/packages/app-common/src/i18n/MessagesZh.ts frontend/packages/app-common/src/i18n/messages.test.ts
git commit -m "fix: describe implemented venta features accurately"
```

---

## Task 3: Verify desktop responsive layout and keyboard accessibility

**Files:**

- Create: `frontend/e2e/app-venta-responsive-accessibility.spec.ts`
- Modify if the red test proves necessary: `frontend/packages/app-common/src/styles/tpv.css`
- Modify if the red test proves necessary: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Modify if the red test proves necessary: `frontend/packages/app-common/src/components/SettingsScreen.tsx`
- Reference: `frontend/e2e/support/ui.ts`
- Reference: `frontend/playwright.config.ts`

- [ ] **Step 1: Create a failing dedicated Playwright specification**

Do not multiply every existing E2E test across nine combinations. Create a focused spec that loops over:

```ts
const viewports = [
  { name: "full-hd", width: 1920, height: 1080 },
  { name: "laptop", width: 1366, height: 768 },
  { name: "minimum-desktop", width: 1024, height: 768 }
] as const;

const locales = ["es", "en", "zh"] as const;
```

For each viewport and locale:

1. call `page.setViewportSize`;
2. login and select the locale through the existing globe/language control;
3. open APP VENTA;
4. assert the sale shell, sidebar, ticket area, promotions panel, and footer are within the viewport;
5. assert the body has no horizontal overflow:

```ts
expect(await page.evaluate(() => document.documentElement.scrollWidth))
  .toBeLessThanOrEqual(viewport.width);
```

6. add or locate a product and open the quantity dialog;
7. use `Tab` to reach its input/actions, `Enter` to submit, and `Escape` to close;
8. open customer selection, assert the dialog has a translated accessible name, and close with `Escape`;
9. assert no interactive control in the current view has an empty accessible name;
10. capture no screenshots in the repository unless a failure occurs through Playwright's configured artifact handling.

Use locale-specific expectations only where needed. Prefer stable roles and translated regex tables over CSS text selectors.

- [ ] **Step 2: Run only this E2E spec and record the first real failure**

With the backend and frontend already running:

```powershell
cd E:\workspace\gitwork\TPV_ERP\frontend
$env:E2E_REUSE_EXTERNAL_SERVERS = "true"
$env:E2E_BACKEND_URL = "http://127.0.0.1:18080"
$env:E2E_VENTA_URL = "http://127.0.0.1:4173"
$env:E2E_ADMIN_USERNAME = "ADMIN"
$env:E2E_ADMIN_PASSWORD = "0000"
npm.cmd run test:e2e -- app-venta-responsive-accessibility.spec.ts
```

Expected before fixes: FAIL if a supported viewport overflows, a dialog leaks Spanish, focus cannot reach a control, or `Escape` does not close it. If the test unexpectedly passes after Task 1, do not change production CSS merely for activity.

- [ ] **Step 3: Make only evidence-driven layout/accessibility fixes**

For each reproduced failure:

- use responsive grid/flex constraints and `min-width: 0` on overflowing children;
- keep the minimum supported layout at 1024 px;
- keep primary controls visible without clipping;
- add `aria-label`, `aria-labelledby`, or semantic roles only where Playwright proves the accessible name is missing;
- keep modal `Escape`, `Enter`, and focus behavior consistent;
- do not introduce mobile stacking below 1024 px.

Example CSS pattern, only if the sale columns overflow:

```css
@media (max-width: 1366px) and (min-width: 1024px) {
  .sale-layout {
    grid-template-columns: minmax(220px, 260px) minmax(0, 1fr);
  }

  .sale-main,
  .sale-sidebar {
    min-width: 0;
  }
}
```

- [ ] **Step 4: Re-run focused E2E until green**

Run the exact command from Step 2.

Expected: all 9 viewport/locale scenarios PASS.

- [ ] **Step 5: Run the existing operational flow regression**

Run:

```powershell
cd E:\workspace\gitwork\TPV_ERP\frontend
npm.cmd run test:e2e -- app-venta-operational-flows.spec.ts
```

Expected: 7 tests PASS. If Task 1 changed accessible names, update this E2E file to select text through the locale table or run it explicitly in Spanish; do not restore hardcoded Spanish in production.

- [ ] **Step 6: Run frontend build**

Run:

```powershell
cd E:\workspace\gitwork\TPV_ERP\frontend
npm.cmd run build
```

Expected: PASS with no TypeScript or Vite build error.

- [ ] **Step 7: Commit Task 3**

Stage the new spec plus only production files that the red test required:

```powershell
cd E:\workspace\gitwork\TPV_ERP
git add frontend/e2e/app-venta-responsive-accessibility.spec.ts frontend/e2e/app-venta-operational-flows.spec.ts frontend/packages/app-common/src/styles/tpv.css frontend/packages/app-common/src/components/SaleScreen.tsx frontend/packages/app-common/src/components/SettingsScreen.tsx
git commit -m "test: cover venta desktop accessibility"
```

Before committing, use `git diff --cached --name-only` and unstage any listed path that was not actually modified.

---

## Task 4: Prove concurrent sales preserve stock and movement invariants

**Files:**

- Create: `backend/src/test/java/com/tpverp/backend/inventory/InventoryDocumentGatewayConcurrencyPostgreSqlTest.java`
- Modify if the test fails: `backend/src/main/java/com/tpverp/backend/inventory/InventoryDocumentGateway.java`
- Modify if the implementation changes: `backend/src/test/java/com/tpverp/backend/inventory/InventoryDocumentGatewayTest.java`
- Reference: `backend/src/main/java/com/tpverp/backend/inventory/StockLevelRepository.java`
- Reference: `backend/src/test/java/com/tpverp/backend/document/DocumentConfirmationRollbackPostgreSqlTest.java`
- Reference: `backend/src/test/java/com/tpverp/backend/document/SalePaymentFinalizeConcurrencyPostgreSqlTest.java`

- [ ] **Step 1: Add the PostgreSQL concurrency test fixture**

Create `InventoryDocumentGatewayConcurrencyPostgreSqlTest` using the repository's established PostgreSQL-test conventions:

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TPV_ERP_TEST_DB_PASSWORD", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(InventoryDocumentGateway.class)
class InventoryDocumentGatewayConcurrencyPostgreSqlTest {
    // DynamicPropertySource: TPV_ERP_TEST_DB_URL, TPV_ERP_TEST_DB_USER,
    // TPV_ERP_TEST_DB_PASSWORD and a unique schema.
    // Mock StockMovementSyncPublisher exactly as in DocumentConfirmationRollbackPostgreSqlTest.
}
```

Seed through `JdbcTemplate` the minimum valid graph used by `DocumentConfirmationRollbackPostgreSqlTest`:

- company, store, terminal/user context if required by the document model;
- tax, family, warehouse;
- one product;
- one initial `existencia` row with quantity `10`;
- two distinct sale documents (`TICKET`) with one line each, quantity `1`, same product and warehouse.

Use two executor threads and a `CountDownLatch` so both confirmations begin together:

```java
CountDownLatch ready = new CountDownLatch(2);
CountDownLatch start = new CountDownLatch(1);

Callable<Boolean> confirm = () -> {
    ready.countDown();
    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
    return transactionTemplate.execute(status -> {
        CommercialDocument document = loadOneUnconfirmedTicketForCurrentThread();
        gateway.confirm(document);
        return true;
    });
};
```

Make each task load a different document ID. Await both futures with a finite timeout and require both operations to succeed.
Execute the scenario for 20 independent fixture suffixes in the same test method so the current
unlocked read path has repeated opportunities to expose a lost update. Each round must create a
fresh product, stock row and two ticket IDs; do not reuse balances between rounds.

- [ ] **Step 2: Assert both the final balance and the ledger invariant**

After both futures complete:

```java
BigDecimal stock = jdbc.queryForObject(
    "select cantidad from existencia where producto_id = ? and almacen_id = ?",
    BigDecimal.class, productId, warehouseId
);
BigDecimal movementDelta = jdbc.queryForObject(
    "select coalesce(sum(cantidad), 0) from movimiento_stock where producto_id = ? and almacen_id = ?",
    BigDecimal.class, productId, warehouseId
);
Integer movementCount = jdbc.queryForObject(
    "select count(*) from movimiento_stock where producto_id = ? and almacen_id = ?",
    Integer.class, productId, warehouseId
);

assertThat(movementCount).isEqualTo(2);
assertThat(movementDelta).isEqualByComparingTo("-2");
assertThat(stock).isEqualByComparingTo("8");
assertThat(stock).isEqualByComparingTo(new BigDecimal("10").add(movementDelta));
```

The exact table/column names must match the current Flyway schema. Do not weaken the test to accept one failed checkout: this scenario models two valid, distinct sales with sufficient stock.

- [ ] **Step 3: Run the new test against PostgreSQL and confirm the race**

Configure the dedicated test database without reusing a development database:

```powershell
cd E:\workspace\gitwork\TPV_ERP\backend
$env:TPV_ERP_TEST_DB_URL = "jdbc:postgresql://127.0.0.1:5432/tpv_erp_test"
$env:TPV_ERP_TEST_DB_USER = "tpv_erp"
# TPV_ERP_TEST_DB_PASSWORD debe estar definida previamente en el entorno.
mvn.cmd -Dtest=InventoryDocumentGatewayConcurrencyPostgreSqlTest test
```

Expected on the current unlocked read path: FAIL through an optimistic-lock/concurrency exception or a final stock/movement mismatch. Save the failure output in the task notes, not in a committed artifact.

- [ ] **Step 4: Use the existing pessimistic-lock repository method**

Only after Step 3 reproduces the defect, change `InventoryDocumentGateway.apply`:

```java
StockLevel stock = stockLevels
    .findByProductIdAndWarehouseIdForUpdate(product.getId(), warehouse.getId())
    .orElseGet(() -> StockLevel.create(product, warehouse));
```

Do not remove `@Version`, add process-local synchronization, or create a second lock query. The existing `StockLevelRepository.findByProductIdAndWarehouseIdForUpdate` is the authoritative mechanism.

- [ ] **Step 5: Update the gateway unit tests**

In `InventoryDocumentGatewayTest`, replace mocks and verifications of:

```java
findByProductIdAndWarehouseId(productId, warehouseId)
```

with:

```java
findByProductIdAndWarehouseIdForUpdate(productId, warehouseId)
```

Add an explicit verification that confirmation uses the locked lookup for an existing stock row.

- [ ] **Step 6: Run focused backend tests**

Run:

```powershell
cd E:\workspace\gitwork\TPV_ERP\backend
mvn.cmd -Dtest=InventoryDocumentGatewayTest,InventoryDocumentGatewayConcurrencyPostgreSqlTest test
```

Expected: PASS; both sales succeed, final quantity is `8`, and two movements total `-2`.

- [ ] **Step 7: Run the backend unit suite**

Run:

```powershell
cd E:\workspace\gitwork\TPV_ERP\backend
mvn.cmd test
```

Expected: PASS. The normal Surefire configuration continues to exclude tests tagged `integration`; the new test is invoked explicitly in Step 6 and must also be documented in the audit.

- [ ] **Step 8: Commit Task 4**

If the red test required the lock fix:

```powershell
cd E:\workspace\gitwork\TPV_ERP
git add backend/src/test/java/com/tpverp/backend/inventory/InventoryDocumentGatewayConcurrencyPostgreSqlTest.java backend/src/main/java/com/tpverp/backend/inventory/InventoryDocumentGateway.java backend/src/test/java/com/tpverp/backend/inventory/InventoryDocumentGatewayTest.java
git commit -m "fix: serialize concurrent stock updates"
```

If the production code already passes unchanged, commit only the test:

```powershell
git add backend/src/test/java/com/tpverp/backend/inventory/InventoryDocumentGatewayConcurrencyPostgreSqlTest.java
git commit -m "test: verify concurrent venta stock invariants"
```

---

## Task 5: Rewrite the frontend/backend audit as an operational truth source

**Files:**

- Modify: `docs/frontend-backend-audit.md`
- Reference: `docs/superpowers/specs/2026-07-25-app-venta-focused-hardening-design.md`
- Reference: `frontend/e2e/app-venta-operational-flows.spec.ts`
- Reference: `frontend/e2e/app-venta-responsive-accessibility.spec.ts`
- Reference: `backend/src/test/java/com/tpverp/backend/inventory/InventoryDocumentGatewayConcurrencyPostgreSqlTest.java`

- [ ] **Step 1: Replace the stale snapshot, do not append another contradictory section**

Rewrite `docs/frontend-backend-audit.md` with a current date and these explicit statuses:

1. **Implemented and locally testable**
   - product/customer search and keyboard flows;
   - authoritative quote, member price/discount, coupons and promotions;
   - cash, simulated card outcomes, pending customer, vouchers;
   - parked sales and post-sale ticket management;
   - receivables/credit controls;
   - stock movement integration;
   - audit, correlation IDs and operational monitoring already present.
2. **Implemented but operationally dependent**
   - printing needs configured printer/bridge;
   - PostgreSQL concurrency verification needs a dedicated test database;
   - production observability needs the deployment stack and alert routing.
3. **External or incomplete**
   - physical Redsys TPV-PC, PAYTEF, PAYCOMET and Global Payments communication requires official SDK/protocol/local service;
   - VeriFactu production certification and certificate setup;
   - production hardware validation.
4. **Supported UI scope**
   - desktop resolutions: 1920x1080, 1366x768, 1024x768;
   - locales: Spanish, English, Chinese;
   - mobile below 1024 px is not claimed.
5. **Verification commands**
   - frontend unit suite;
   - focused and operational Playwright specs;
   - backend unit suite;
   - explicit PostgreSQL concurrency test with required environment variables.

Do not claim a physical dataphone is implemented merely because simulators and provider configuration exist.

- [ ] **Step 2: Check documentation for obsolete claims**

Run:

```powershell
cd E:\workspace\gitwork\TPV_ERP
rg -n "fase posterior|no veo frontend|no implementado|pendiente de conectar|will be connected|later phase" docs/frontend-backend-audit.md
```

Expected: no stale claim unless it is intentionally listed under external/incomplete with a precise dependency.

- [ ] **Step 3: Run final verification from clean service instances**

Frontend:

```powershell
cd E:\workspace\gitwork\TPV_ERP\frontend
npm.cmd test
npm.cmd run build
```

Backend:

```powershell
cd E:\workspace\gitwork\TPV_ERP\backend
mvn.cmd test
```

E2E, with the expected services running:

```powershell
cd E:\workspace\gitwork\TPV_ERP\frontend
$env:E2E_REUSE_EXTERNAL_SERVERS = "true"
$env:E2E_BACKEND_URL = "http://127.0.0.1:18080"
$env:E2E_VENTA_URL = "http://127.0.0.1:4173"
$env:E2E_ADMIN_USERNAME = "ADMIN"
$env:E2E_ADMIN_PASSWORD = "0000"
npm.cmd run test:e2e -- app-venta-operational-flows.spec.ts app-venta-responsive-accessibility.spec.ts
```

PostgreSQL concurrency:

```powershell
cd E:\workspace\gitwork\TPV_ERP\backend
$env:TPV_ERP_TEST_DB_URL = "jdbc:postgresql://127.0.0.1:5432/tpv_erp_test"
$env:TPV_ERP_TEST_DB_USER = "tpv_erp"
# TPV_ERP_TEST_DB_PASSWORD debe estar definida previamente en el entorno.
mvn.cmd -Dtest=InventoryDocumentGatewayConcurrencyPostgreSqlTest test
```

Expected: every command PASS. If a service or PostgreSQL is unavailable, report that command as not run; do not translate absence of evidence into a passing result.

- [ ] **Step 4: Review the final diff and status**

Run:

```powershell
cd E:\workspace\gitwork\TPV_ERP
git diff --check
git status --short
git log --oneline -8
```

Expected:

- `git diff --check` prints nothing;
- no generated reports, credentials, database dumps, or Playwright artifacts are staged;
- only planned source, test, and documentation files remain.

- [ ] **Step 5: Commit Task 5**

```powershell
cd E:\workspace\gitwork\TPV_ERP
git add docs/frontend-backend-audit.md
git commit -m "docs: align app venta audit with implementation"
```

---

## Final Acceptance Checklist

- [ ] No quantity, discount, authorization, customer, remove-line, or dialog-close text remains hardcoded in Spanish inside `SaleScreen.tsx`.
- [ ] `es`, `en`, and `zh` catalogs contain identical keys and truthful implemented-state copy.
- [ ] APP VENTA passes the dedicated desktop check at 1920x1080, 1366x768, and 1024x768 in all three locales.
- [ ] Tab, Enter, and Escape operate the tested dialogs, and tested interactive controls have accessible names.
- [ ] Two distinct concurrent sales of the same product both complete against PostgreSQL.
- [ ] Final stock equals initial stock plus the sum of stock movements.
- [ ] The audit distinguishes implemented, operationally dependent, and external functionality.
- [ ] Frontend unit tests, frontend build, backend tests, focused PostgreSQL test, and both APP VENTA E2E specs pass or are explicitly reported as not run with the missing dependency.
- [ ] No physical SDK, mobile layout, or unrelated feature work entered the diff.
