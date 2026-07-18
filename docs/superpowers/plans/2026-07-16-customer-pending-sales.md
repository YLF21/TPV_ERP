# Customer Pending Sales Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir crear albaranes y facturas de venta pendientes desde APP VENTA, registrar cobros iniciales reales y cobrar posteriormente la deuda por efectivo, tarjeta o transferencia.

**Architecture:** `CommercialDocument` sigue siendo la unica fuente de verdad: deuda es `total - pagos` y no un metodo de pago. Un coordinador POS idempotente crea el documento con snapshot autoritativo; un servicio de cuentas a cobrar consulta y registra pagos posteriores bajo bloqueo. Las operaciones de tarjeta se reservan y recuperan mediante `PaymentTerminalOperationService` y solo se vinculan al documento tras aprobación.

**Tech Stack:** Java 25, Spring Boot 4, Spring Data JPA, PostgreSQL/Flyway, React 19, TypeScript, Vitest, Testing Library y Vite.

## Global Constraints

- Solo `ALBARAN_VENTA` y `FACTURA_VENTA`; `TICKET` queda fuera.
- El cliente activo y la fecha de vencimiento son obligatorios.
- Vencimiento inicial de UI: fecha local de tienda + 30 dias; editable.
- Sin limite de credito, vales ni saldo de socio en esta fase.
- Metodos permitidos: `CASH`, `INTEGRATED_CARD`, `MANUAL_CARD` si la tienda lo permite y `TRANSFER`.
- El pendiente nunca crea `documento_pago`; caja e informes solo reciben pagos reales.
- Backend autoritativo para precio, descuento, promociones, impuesto, saldo y estado.
- Toda creacion y todo cobro son idempotentes; un timeout de tarjeta bloquea otro cargo hasta consulta.
- No modificar ni renumerar `V60`–`V66`; la migracion de esta funcionalidad es `V67`.
- Preservar los cambios locales ajenos de migraciones y `.superpowers/brainstorm/`.

---

## File map

**Persistencia y dominio**

- `V72__customer_pending_sales.sql`: idempotencia de checkout/pagos e indices de deuda.
- `CustomerPendingSaleCheckout*`: reserva/replay de la creacion POS.
- `CommercialDocument`, `DocumentPayment`, repositorios: vencimiento, request id y bloqueos.

**Aplicacion backend**

- `CustomerPendingSaleService/Controller`: cotiza, autoriza tarjeta y confirma.
- `CustomerReceivableService/Controller`: lista, detalla y cobra deuda.
- `CustomerReceivableView`: proyeccion financiera con cliente y vencimiento.
- `DocumentService`: unica ruta que confirma documento, stock, pagos, fidelizacion y fiscalidad.

**Frontend**

- `CustomerPendingSaleDialog`: F12, documento, vencimiento, asignaciones y resumen.
- `CustomerReceivablesScreen`: listado/filtros y acceso desde Venta/ficha de cliente.
- `CustomerReceivablePaymentDialog`: cobro posterior y recuperacion de tarjeta.
- `customerReceivables.ts`: contratos API, centimos e idempotencia local.

---

### Task 1: Persistencia idempotente y estado financiero

**Files:**
- Create: `backend/src/main/resources/db/migration/V72__customer_pending_sales.sql`
- Create: `backend/src/main/java/com/tpverp/backend/document/CustomerPendingSaleCheckout.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/CustomerPendingSaleCheckoutRepository.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/CommercialDocument.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentPayment.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentPaymentRepository.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/CommercialDocumentRepository.java`
- Create: `backend/src/test/java/com/tpverp/backend/persistence/MigrationV72ContractTest.java`
- Create: `backend/src/test/java/com/tpverp/backend/document/CustomerPendingSaleCheckoutTest.java`
- Create: `backend/src/test/java/com/tpverp/backend/document/CommercialDocumentTest.java`

**Interfaces:**
- Produces: `CustomerPendingSaleCheckout.reserve(...)`, `complete(UUID)`, `matchesHash(String)`.
- Produces: `DocumentPayment.getRequestId()` y `findByRequestId(UUID)`.
- Produces: `CommercialDocument.setDueDate(LocalDate)`, `getDueDate()` y repositorio `findLockedReceivable(...)`.

- [ ] **Step 1: Write migration and domain tests first**

```java
@Test void migrationAddsIdempotencyWithoutInventingPendingPaymentMethod() throws Exception {
    var sql = Files.readString(Path.of("src/main/resources/db/migration/V72__customer_pending_sales.sql"));
    assertThat(sql).contains("customer_pending_sale_checkout", "request_hash", "documento_pago", "request_id");
    assertThat(sql).contains("unique (terminal_id, checkout_id)", "unique (request_id)");
    assertThat(sql.toUpperCase()).doesNotContain("'PENDIENTE'");
}

@Test void receivableStateUsesOnlyRealPayments() {
    var document = saleInvoice(total("100.00"));
    document.confirm("FV-1", USER_ID, NOW, false);
    document.setDueDate(LocalDate.of(2026, 8, 15));
    assertThat(document.getPendingTotal()).isEqualByComparingTo("100.00");
    assertThat(document.getEstado()).isEqualTo(DocumentStatus.PENDIENTE);
}
```

- [ ] **Step 2: Run RED**

Run:

```powershell
mvn.cmd "-Dtest=MigrationV72ContractTest,CustomerPendingSaleCheckoutTest,CommercialDocumentTest" test
```

Expected: FAIL because V67, checkout entity and due-date accessors do not exist.

- [ ] **Step 3: Implement the minimal schema**

```sql
alter table documento_pago add column request_id uuid;
alter table documento_pago add constraint uk_documento_pago_request unique (request_id);

create table customer_pending_sale_checkout (
    id uuid primary key,
    checkout_id uuid not null,
    terminal_id uuid not null references terminal(id),
    store_id uuid not null references tienda(id),
    user_id uuid not null references usuario(id),
    request_hash varchar(64) not null check (request_hash ~ '^[0-9a-f]{64}$'),
    document_id uuid unique references documento(id),
    created_at timestamptz not null,
    completed_at timestamptz,
    version bigint not null default 0,
    unique (terminal_id, checkout_id),
    foreign key (terminal_id, store_id) references terminal(id, tienda_id)
);

create index idx_customer_receivable_scope
    on documento(tienda_id, cliente_id, estado, fecha_vencimiento)
    where tipo in ('ALBARAN_VENTA','FACTURA_VENTA')
      and estado in ('PENDIENTE','PARCIAL');
```

Add `requestId` to `DocumentPayment`, preserve constructor overloads, and add:

```java
Optional<DocumentPayment> findByRequestId(UUID requestId);

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select d from CommercialDocument d left join fetch d.pagos where d.id=:id and d.tiendaId=:storeId")
Optional<CommercialDocument> findLockedReceivable(UUID id, UUID storeId);
```

- [ ] **Step 4: Run GREEN and migration integration**

```powershell
mvn.cmd "-Dtest=MigrationV72ContractTest,CustomerPendingSaleCheckoutTest,CommercialDocumentTest,PostgreSqlMigrationTest" test
```

Expected: all tests PASS on empty and upgraded PostgreSQL schemas.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/resources/db/migration/V72__customer_pending_sales.sql backend/src/main/java/com/tpverp/backend/document backend/src/test/java/com/tpverp/backend/document backend/src/test/java/com/tpverp/backend/persistence/MigrationV72ContractTest.java
git commit -m "feat(receivables): add pending sale persistence"
```

### Task 2: Permisos y proyeccion financiera

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/security/application/CorePermissionBootstrap.java`
- Modify: `frontend/packages/app-common/src/types.ts`
- Modify: `backend/src/main/resources/i18n/messages_es.properties`
- Modify: `backend/src/main/resources/i18n/messages_en.properties`
- Modify: `backend/src/main/resources/i18n/messages_zh.properties`
- Create: `backend/src/main/java/com/tpverp/backend/document/CustomerReceivableView.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/DocumentViewAssembler.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentView.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/InvoiceController.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DeliveryNoteController.java`
- Modify: `backend/src/test/java/com/tpverp/backend/document/DocumentViewTest.java`
- Modify: `backend/src/test/java/com/tpverp/backend/security/application/CorePermissionBootstrapTest.java`

**Interfaces:**
- Produces permissions `CUSTOMER_RECEIVABLES_READ`, `CUSTOMER_RECEIVABLES_CREATE`, `CUSTOMER_RECEIVABLES_PAY`.
- Produces `CustomerReceivableView.from(document, customerName, businessDate)`.

- [ ] **Step 1: Add failing permission/view tests**

```java
@Test void exposesFinancialFieldsAndOverdueState() {
    var view = CustomerReceivableView.from(documentWithPayment("100", "30", LocalDate.of(2026, 7, 1)), "CLIENTE DEMO", LocalDate.of(2026, 7, 16));
    assertThat(view.paidTotal()).isEqualByComparingTo("30.00");
    assertThat(view.pendingTotal()).isEqualByComparingTo("70.00");
    assertThat(view.overdue()).isTrue();
}
```

- [ ] **Step 2: Run RED**

```powershell
mvn.cmd "-Dtest=DocumentViewTest,CorePermissionBootstrapTest" test
```

- [ ] **Step 3: Implement exact view contract**

```java
public record CustomerReceivableView(
    UUID documentId, CommercialDocumentType documentType, String documentNumber,
    UUID customerId, String customerName, LocalDate issueDate, LocalDate dueDate,
    BigDecimal total, BigDecimal paidTotal, BigDecimal pendingTotal,
    DocumentStatus status, boolean overdue) {
  static CustomerReceivableView from(CommercialDocument d, String name, LocalDate today) {
    var pending = d.getPendingTotal();
    return new CustomerReceivableView(d.getId(), d.getTipo(), d.getNumero(), d.getClienteId(), name,
        d.getFecha(), d.getDueDate(), d.getTotal(), d.getPaidTotal(), pending, d.getEstado(),
        pending.signum() > 0 && d.getDueDate() != null && d.getDueDate().isBefore(today));
  }
}
```

Add all three codes to backend bootstrap, ADMIN role assignment, frontend `Permission`, and translations.
`DocumentView` adds `customerId`, `customerName`, `dueDate`, `paidTotal` and
`pendingTotal`. `DocumentViewAssembler` resolves the customer name within the
authenticated company and is used by invoice/delivery-note controllers; the
receivables API uses the same assembler data rather than trusting a name sent
by the browser.

- [ ] **Step 4: Run GREEN and commit**

```powershell
mvn.cmd "-Dtest=DocumentViewTest,CorePermissionBootstrapTest" test
npm.cmd test -- --run packages/app-common/src/auth/auth.test.ts
git add backend/src/main/java/com/tpverp/backend/security backend/src/main/java/com/tpverp/backend/document/CustomerReceivableView.java backend/src/main/java/com/tpverp/backend/document/DocumentView.java backend/src/main/resources/i18n frontend/packages/app-common/src/types.ts backend/src/test
git commit -m "feat(receivables): expose financial state and permissions"
```

### Task 3: Creacion autoritativa de albaran/factura pendiente

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/document/CustomerPendingSaleRequestHasher.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/CustomerPendingSaleService.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/CustomerPendingSaleController.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/PaymentCommand.java`
- Modify: `backend/src/test/java/com/tpverp/backend/document/DocumentServiceTest.java`
- Create: `backend/src/test/java/com/tpverp/backend/document/CustomerPendingSaleServiceTest.java`
- Create: `backend/src/test/java/com/tpverp/backend/document/CustomerPendingSaleControllerContractTest.java`

**Interfaces:**
- Produces `quote(CreateRequest, Authentication)`, `chargeCard(CardChargeRequest, Authentication)` y `create(CreateRequest, Authentication)`.
- Consumes `PaymentTerminalOperationService.charge/requireFinalizableApprovedCharge/linkDocument`.

- [ ] **Step 1: Write failing service tests**

Cover: inactive/missing customer, invalid type, due date absent, zero/full/partial payment, transfer reference, closed cash session, overpayment, changed quote, approved/reused/uncertain card, checkout replay and hash conflict.

```java
@Test void createsPartialSalesInvoiceWithoutFakePendingPayment() {
    var result = service.create(request("FACTURA_VENTA", cash("30.00"), "100.00"), auth);
    assertThat(result.status()).isEqualTo(DocumentStatus.PARCIAL);
    assertThat(result.pendingTotal()).isEqualByComparingTo("70.00");
    assertThat(savedDocument.getPagos()).singleElement().extracting(DocumentPayment::getImporte).isEqualTo(new BigDecimal("30.00"));
}
```

- [ ] **Step 2: Run RED**

```powershell
mvn.cmd "-Dtest=CustomerPendingSaleServiceTest,CustomerPendingSaleControllerContractTest,DocumentServiceTest" test
```

- [ ] **Step 3: Implement document transaction**

Add to `DocumentService` a package-visible operation with this invariant:

```java
@Transactional
CommercialDocument createPendingSale(DocumentCommand command, LocalDate dueDate,
        List<PaymentCommand> payments, Authentication auth) {
    if (!Set.of(ALBARAN_VENTA, FACTURA_VENTA).contains(command.tipo())) throw invalidType();
    var customer = requireActiveCustomer(command.clienteId());
    var document = createDraft(command, auth, promotionPricing.customerContext(companyId(), customer.getId()));
    applyDirectPromotions(document, promotionContext(document));
    document.setDueDate(Objects.requireNonNull(dueDate));
    requirePaymentTotalAtMost(payments, document.getTotal());
    confirmWithStockFiscalAndOptionalPayments(document, payments, auth);
    document.updatePaymentStatus();
    return documents.save(document);
}
```

`PaymentCommand` gains `UUID requestId`; legacy constructors pass `null`. Resolve methods reject zero payments and duplicate request ids.

- [ ] **Step 4: Implement controller contracts**

```http
POST /api/v1/pos/customer-pending-sales/quote
POST /api/v1/pos/customer-pending-sales/card-charges
POST /api/v1/pos/customer-pending-sales
```

The card charge hashes checkout, type, customer, due date, canonical lines, amount and authoritative total. Final creation recomputes the same hash and accepts only an unused approved operation with equal amount/store/terminal.

- [ ] **Step 5: Run GREEN and commit**

```powershell
mvn.cmd "-Dtest=CustomerPendingSaleServiceTest,CustomerPendingSaleControllerContractTest,DocumentServiceTest,DocumentPromotionIntegrationTest" test
git add backend/src/main/java/com/tpverp/backend/document backend/src/test/java/com/tpverp/backend/document
git commit -m "feat(receivables): create pending sales from POS"
```

### Task 4: Consulta y cobro posterior

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/document/CustomerReceivableFilter.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/CustomerReceivableService.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/CustomerReceivableController.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/CommercialDocumentRepository.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentService.java`
- Create: `backend/src/test/java/com/tpverp/backend/document/CustomerReceivableServiceTest.java`
- Create: `backend/src/test/java/com/tpverp/backend/document/CustomerReceivableControllerContractTest.java`
- Modify: `backend/src/test/java/com/tpverp/backend/document/DailyCommercialReportServiceTest.java`

**Interfaces:**
- Produces list/detail endpoints and `pay(documentId, PaymentRequest, auth)`.
- Produces card charge endpoint bound to `documentId + current pending + amount + paymentId`.

- [ ] **Step 1: Write failing query/payment tests**

```java
@Test void filtersOnlySalesReceivablesInCurrentStore() {
    assertThat(service.list(filter(customerId, true), auth))
        .allMatch(v -> v.pendingTotal().signum() > 0 && v.overdue());
}

@Test void replayedPaymentReturnsSameStateWithoutSecondPayment() {
    var first = service.pay(documentId, transfer(PAYMENT_ID, "20.00", "TR-1"), auth);
    var replay = service.pay(documentId, transfer(PAYMENT_ID, "20.00", "TR-1"), auth);
    assertThat(replay).isEqualTo(first);
    assertThat(paymentRepository.findAllByDocumentoId(documentId)).hasSize(1);
}
```

- [ ] **Step 2: Run RED**

```powershell
mvn.cmd "-Dtest=CustomerReceivableServiceTest,CustomerReceivableControllerContractTest,DailyCommercialReportServiceTest" test
```

- [ ] **Step 3: Implement scoped locked payment**

Endpoints:

```http
GET  /api/v1/customer-receivables
GET  /api/v1/customer-receivables/{documentId}
POST /api/v1/customer-receivables/{documentId}/card-charges
POST /api/v1/customer-receivables/{documentId}/payments
```

Inside one transaction: load `findLockedReceivable`, validate type/status/store/customer, replay `requestId`, ensure amount `<= pending`, resolve method and card operation, append payment, update status, record cash/fidelity/outbox, then link approved card operation to the exact `DocumentPayment`.

- [ ] **Step 4: Verify cash/report separation and commit**

```powershell
mvn.cmd "-Dtest=CustomerReceivableServiceTest,CustomerReceivableControllerContractTest,DailyCommercialReportServiceTest,PaymentTerminalOperationServiceTest" test
git add backend/src/main/java/com/tpverp/backend/document backend/src/test/java/com/tpverp/backend/document
git commit -m "feat(receivables): list and collect customer debt"
```

### Task 5: Dialogo F12 de venta pendiente

**Files:**
- Create: `frontend/packages/app-common/src/sale/customerReceivables.ts`
- Create: `frontend/packages/app-common/src/components/CustomerPendingSaleDialog.tsx`
- Create: `frontend/packages/app-common/src/components/CustomerPendingSaleDialog.test.tsx`
- Modify: `frontend/packages/app-common/src/components/IndividualPaymentActions.tsx`
- Modify: `frontend/packages/app-common/src/components/IndividualPaymentActions.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

**Interfaces:**
- Produces `PendingSaleDraft`, `PendingPaymentAllocation` and cent-based summary helpers.
- Consumes quote/card/create endpoints from Task 3.

- [ ] **Step 1: Write failing keyboard and payload tests**

```tsx
it("opens F12, defaults due date to plus 30 days and keeps pending outside payments", async () => {
  fireEvent.keyDown(window, { key: "F12" });
  expect(await screen.findByRole("dialog", { name: /venta pendiente/i })).toBeVisible();
  expect(screen.getByLabelText(/vencimiento/i)).toHaveValue("2026-08-15");
  fireEvent.click(screen.getByRole("button", { name: /confirmar venta pendiente/i }));
  expect(request).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({
    body: expect.not.objectContaining({ paymentMethod: "PENDIENTE" })
  }));
});
```

- [ ] **Step 2: Run RED**

```powershell
npm.cmd test -- --run packages/app-common/src/components/CustomerPendingSaleDialog.test.tsx packages/app-common/src/components/IndividualPaymentActions.test.tsx packages/app-common/src/components/SaleScreen.test.tsx
```

- [ ] **Step 3: Implement dialog state machine**

Use integer cents:

```ts
export const pendingSummary = (totalCents: number, payments: PendingPaymentAllocation[]) => {
  const paidCents = payments.filter(p => p.status === "APPROVED").reduce((n, p) => n + p.amountCents, 0);
  return { totalCents, paidCents, pendingCents: totalCents - paidCents };
};
```

F12 without customer opens existing customer selector and continues after selection. Escape closes only before external effects. Integrated-card timeout shows query action and blocks confirmation/removal; approved card requires void before removal. On success clear sale; on failure retain everything.

- [ ] **Step 4: Run GREEN, accessibility checks and commit**

```powershell
npm.cmd test -- --run packages/app-common/src/components/CustomerPendingSaleDialog.test.tsx packages/app-common/src/components/IndividualPaymentActions.test.tsx packages/app-common/src/components/SaleScreen.test.tsx
git add frontend/packages/app-common/src/sale/customerReceivables.ts frontend/packages/app-common/src/components/CustomerPendingSaleDialog* frontend/packages/app-common/src/components/IndividualPaymentActions* frontend/packages/app-common/src/components/SaleScreen* frontend/packages/app-common/src/styles/tpv.css
git commit -m "feat(sale): add pending customer checkout"
```

### Task 6: Pantalla de deudas y cobro posterior

**Files:**
- Create: `frontend/packages/app-common/src/components/CustomerReceivablesScreen.tsx`
- Create: `frontend/packages/app-common/src/components/CustomerReceivablesScreen.test.tsx`
- Create: `frontend/packages/app-common/src/components/CustomerReceivablePaymentDialog.tsx`
- Create: `frontend/packages/app-common/src/components/CustomerReceivablePaymentDialog.test.tsx`
- Modify: `frontend/apps/app-venta/src/main.tsx`
- Modify: `frontend/apps/app-venta/src/main.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SessionHomeScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/SessionHomeScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/components/PartyDirectoryPanel.tsx`
- Modify: `frontend/packages/app-common/src/components/PartyDirectoryPanel.test.ts`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

**Interfaces:**
- Screen accepts optional `initialCustomerId` and exposes back/logout/locale callbacks.
- Payment dialog consumes endpoints from Task 4 and existing cash/card operation helpers.

- [ ] **Step 1: Write failing navigation/filter/payment tests**

Test home access with `CUSTOMER_RECEIVABLES_READ`, customer-directory action with prefilter, text/status/type/overdue/date filters, partial cash, approved card, transfer reference, overpayment, paid document, retry and refreshed row.

- [ ] **Step 2: Run RED**

```powershell
npm.cmd test -- --run packages/app-common/src/components/CustomerReceivablesScreen.test.tsx packages/app-common/src/components/CustomerReceivablePaymentDialog.test.tsx packages/app-common/src/components/SessionHomeScreen.test.tsx frontend/apps/app-venta/src/main.test.tsx
```

- [ ] **Step 3: Implement list and payment dialog**

The table columns are document, customer, issue, due, total, paid, pending and status. `Cobrar` defaults amount to current pending. Cash reuses `CashPaymentDialog`; transfer requires reference; card uses stable `paymentId` in local storage until terminal result and backend payment are confirmed.

- [ ] **Step 4: Run GREEN and commit**

```powershell
npm.cmd test -- --run packages/app-common/src/components/CustomerReceivablesScreen.test.tsx packages/app-common/src/components/CustomerReceivablePaymentDialog.test.tsx packages/app-common/src/components/SessionHomeScreen.test.tsx frontend/apps/app-venta/src/main.test.tsx
git add frontend/apps/app-venta/src frontend/packages/app-common/src/components frontend/packages/app-common/src/styles/tpv.css
git commit -m "feat(venta): manage customer receivables"
```

### Task 7: I18n, informes, impresion y recuperacion transversal

**Files:**
- Modify: `frontend/packages/app-common/src/i18n/MessagesEs.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEn.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesZh.ts`
- Modify: `frontend/packages/app-common/src/components/SalesReportScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/SalesReportScreen.test.tsx`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DailyCommercialReportService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DailyCommercialReportView.java`
- Modify: `backend/src/test/java/com/tpverp/backend/document/DailyCommercialReportServiceTest.java`
- Modify: `frontend/packages/app-common/src/sale/ticketPrinting.ts`

**Interfaces:**
- Report returns `invoiced`, `collectedCurrent`, `newPending`, `priorDebtCollected`, `cashInflow`.
- Printing distinguishes commercial document from later payment receipt.

- [ ] **Step 1: Write failing report/i18n tests**

```java
assertThat(report.invoiced()).isEqualByComparingTo("100.00");
assertThat(report.collectedCurrent()).isEqualByComparingTo("30.00");
assertThat(report.newPending()).isEqualByComparingTo("70.00");
assertThat(report.priorDebtCollected()).isEqualByComparingTo("20.00");
assertThat(report.cashInflow()).isEqualByComparingTo("50.00");
```

- [ ] **Step 2: Run RED, implement separate accounting buckets, then GREEN**

```powershell
mvn.cmd "-Dtest=DailyCommercialReportServiceTest" test
npm.cmd test -- --run packages/app-common/src/components/SalesReportScreen.test.tsx
```

Ensure all visible copy is translated; correct legacy mojibake such as `AvP谩g` only in files touched by this feature.

- [ ] **Step 3: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/document/DailyCommercialReport* backend/src/test/java/com/tpverp/backend/document/DailyCommercialReportServiceTest.java frontend/packages/app-common/src/i18n frontend/packages/app-common/src/components/SalesReportScreen* frontend/packages/app-common/src/sale/ticketPrinting.ts
git commit -m "feat(reports): separate customer debt movements"
```

### Task 8: Verificacion integral y documentacion operativa

**Files:**
- Modify: `backend/README.md`
- Modify: `frontend/README.md`
- Create: `docs/customer-pending-sales-operations.md`
- Test: all files changed in Tasks 1–7.

**Interfaces:**
- Produces operator instructions, recovery procedure and final evidence.

- [ ] **Step 1: Add manual recovery checklist**

Document: creating fully pending and mixed sales; collecting later; resolving terminal timeout; reopening after restart; confirming stock once; checking daily report; and never recording pending as payment.

- [ ] **Step 2: Run backend focused suites**

```powershell
mvn.cmd "-Dtest=MigrationV72ContractTest,PostgreSqlMigrationTest,CustomerPendingSaleCheckoutTest,CustomerPendingSaleServiceTest,CustomerPendingSaleControllerContractTest,CustomerReceivableServiceTest,CustomerReceivableControllerContractTest,DocumentServiceTest,DocumentPromotionIntegrationTest,PaymentTerminalOperationServiceTest,DailyCommercialReportServiceTest" test
```

Expected: 0 failures/errors/skips unless a test is explicitly platform-gated.

- [ ] **Step 3: Run frontend and builds**

```powershell
cd frontend
npm.cmd test
npm.cmd run build --workspace @tpverp/app-venta
npm.cmd run build --workspace @tpverp/app-gestion
```

- [ ] **Step 4: Run full backend and diff audit**

```powershell
cd ..\backend
mvn.cmd test
cd ..
git diff --check
git status --short
```

Confirm no changes to V60–V66, no secrets, no generated reports, no PAN/PIN/CVV, and no unrelated migration renumbering staged.

- [ ] **Step 5: Manual acceptance with PostgreSQL and simulator**

Execute: albaran 100% pending; invoice cash 30 + pending 70; card 30 + pending 70; transfer; later partial/full collection; timeout/query; replay same checkout/payment; customer filtering; report totals; stock single movement.

- [ ] **Step 6: Final review and commit**

```powershell
git add backend/README.md frontend/README.md docs/customer-pending-sales-operations.md
git commit -m "docs(receivables): add pending sales operations"
```

Request independent review of the complete range. Important findings require one TDD fix wave and repeat of Steps 2–5 before merge or PR.
