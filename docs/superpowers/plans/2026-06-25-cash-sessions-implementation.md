# Cash Sessions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add terminal cash sessions with opening, withdrawals, authorized entries, blind reconciliation, cash reports and mandatory open-session checks for sales.

**Architecture:** Implement a new `com.tpverp.backend.cash` domain that owns cash persistence, calculations, permissions and API filtering. Integrate it into existing document flows by recording cash movements from confirmed tickets and paid invoices, while keeping fiscal, stock and document numbering behavior unchanged.

**Tech Stack:** Java 25, Spring Boot 4, Maven, JPA/Hibernate, Flyway, PostgreSQL 18, existing Spring Security and localized i18n resources.

---

## File Structure

- Create `backend/src/main/resources/db/migration/V17__sesiones_caja.sql`: cash tables, indexes and permission bootstrap rows.
- Modify `backend/src/main/java/com/tpverp/backend/security/application/CorePermissionBootstrap.java`: add `GESTION_CUENTAS`, `CASH_READ`, `CASH_OPERATE`, `CASH_CONFIGURE`.
- Modify `backend/src/main/resources/i18n/messages_es.properties`, `messages_en.properties`, `messages_zh.properties`: cash permission labels and system messages.
- Create package `backend/src/main/java/com/tpverp/backend/cash/`.
- Create `CashMovementType.java`, `CashSessionStatus.java`, `CashDenomination.java`: enums/value catalog.
- Create entities `CashStoreConfig.java`, `CashSession.java`, `CashMovement.java`, `CashMovementDenomination.java`, `CashReconciliationAttempt.java`.
- Create repositories `CashStoreConfigRepository.java`, `CashSessionRepository.java`, `CashMovementRepository.java`, `CashReconciliationAttemptRepository.java`.
- Create command/view records `CashOpenRequest.java`, `CashEntryRequest.java`, `CashWithdrawalRequest.java`, `CashCloseRequest.java`, `CashDenominationCommand.java`, `CashSessionView.java`, `CashMovementView.java`, `CashReportView.java`, `CashReceiptView.java`.
- Create services `CashPermissionService.java`, `CashAmountCalculator.java`, `CashSessionService.java`, `CashPaymentRecorder.java`, `CashReportService.java`, `CashReceiptService.java`.
- Create `CashController.java`.
- Modify `backend/src/main/java/com/tpverp/backend/document/DocumentService.java`: enforce open cash session and record cash movements for tickets and invoice payments.
- Modify document controllers only if they bypass `DocumentService`; otherwise leave them unchanged.
- Add focused tests:
  - `backend/src/test/java/com/tpverp/backend/persistence/MigrationV17ContractTest.java`
  - `backend/src/test/java/com/tpverp/backend/cash/CashSessionServiceTest.java`
  - `backend/src/test/java/com/tpverp/backend/cash/CashPaymentRecorderTest.java`
  - `backend/src/test/java/com/tpverp/backend/cash/CashControllerContractTest.java`
  - Extend `backend/src/test/java/com/tpverp/backend/document/DocumentServiceTest.java`

## Task 1: Migration, Permissions And I18n

**Files:**
- Create: `backend/src/main/resources/db/migration/V17__sesiones_caja.sql`
- Modify: `backend/src/main/java/com/tpverp/backend/security/application/CorePermissionBootstrap.java`
- Modify: `backend/src/main/resources/i18n/messages_es.properties`
- Modify: `backend/src/main/resources/i18n/messages_en.properties`
- Modify: `backend/src/main/resources/i18n/messages_zh.properties`
- Test: `backend/src/test/java/com/tpverp/backend/persistence/MigrationV17ContractTest.java`

- [ ] **Step 1: Write the failing migration contract test**

Create `MigrationV17ContractTest` with one PostgreSQL migration test that asserts all new tables exist and the partial unique index prevents two open sessions per terminal:

```java
package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MigrationV17ContractTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsCashTablesAndBlocksTwoOpenSessionsPerTerminal() {
        var storeId = jdbc.queryForObject("select id from tienda limit 1", UUID.class);
        var terminalId = jdbc.queryForObject("select id from terminal limit 1", UUID.class);
        var userId = jdbc.queryForObject("select id from usuario limit 1", UUID.class);

        assertThat(tableExists("sesion_caja")).isTrue();
        assertThat(tableExists("movimiento_caja")).isTrue();
        assertThat(tableExists("intento_arqueo_caja")).isTrue();

        insertOpenSession(storeId, terminalId, userId);
        assertThatThrownBy(() -> insertOpenSession(storeId, terminalId, userId))
                .hasMessageContaining("sesion_caja_terminal_abierta_uq");
    }

    private boolean tableExists(String tableName) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                  select 1 from information_schema.tables
                  where table_schema = 'public' and table_name = ?
                )
                """, Boolean.class, tableName));
    }

    private void insertOpenSession(UUID storeId, UUID terminalId, UUID userId) {
        jdbc.update("""
                insert into sesion_caja
                (id, tienda_id, terminal_id, usuario_apertura_id, abierta_en,
                 fondo_inicial, estado, cierre_tardio, version)
                values (?, ?, ?, ?, ?, ?, 'ABIERTA', false, 0)
                """,
                UUID.randomUUID(), storeId, terminalId, userId,
                Timestamp.from(Instant.now()), BigDecimal.ZERO);
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=MigrationV17ContractTest test
```

Expected: FAIL because `sesion_caja` does not exist.

- [ ] **Step 3: Add the migration**

Create tables with these core constraints:

```sql
create table configuracion_caja_tienda (
    tienda_id uuid primary key references tienda(id),
    tolerancia_descuadre numeric(19,2) not null default 0.00,
    requiere_desglose_entrada boolean not null default false,
    requiere_desglose_retirada boolean not null default false,
    requiere_desglose_cierre boolean not null default false,
    version bigint not null default 0,
    constraint caja_config_tolerancia_no_negativa check (tolerancia_descuadre >= 0)
);

create table sesion_caja (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    terminal_id uuid not null references terminal(id),
    usuario_apertura_id uuid not null references usuario(id),
    abierta_en timestamp with time zone not null,
    fondo_inicial numeric(19,2) not null,
    usuario_cierre_id uuid references usuario(id),
    cerrada_en timestamp with time zone,
    efectivo_teorico numeric(19,2),
    fondo_dejado numeric(19,2),
    descuadre numeric(19,2),
    estado varchar(16) not null,
    cierre_tardio boolean not null default false,
    version bigint not null default 0,
    constraint sesion_caja_estado_ck check (estado in ('ABIERTA', 'CERRADA')),
    constraint sesion_caja_fondo_inicial_ck check (fondo_inicial >= 0)
);

create unique index sesion_caja_terminal_abierta_uq
    on sesion_caja(terminal_id)
    where estado = 'ABIERTA';
```

Also create `movimiento_caja`, `movimiento_caja_denominacion` and `intento_arqueo_caja` with immutable append-only shape:

```sql
create table movimiento_caja (
    id uuid primary key,
    tienda_id uuid not null references tienda(id),
    terminal_id uuid not null references terminal(id),
    sesion_caja_id uuid references sesion_caja(id),
    tipo varchar(32) not null,
    importe numeric(19,2) not null,
    creado_en timestamp with time zone not null,
    usuario_id uuid not null references usuario(id),
    usuario_autorizador_id uuid references usuario(id),
    comentario varchar(500),
    documento_id uuid references documento(id),
    documento_pago_id uuid references documento_pago(id),
    impreso_en timestamp with time zone,
    version bigint not null default 0,
    constraint movimiento_caja_tipo_ck check (tipo in (
        'COBRO_EFECTIVO', 'DEVOLUCION_EFECTIVO', 'ENTRADA',
        'RETIRADA', 'RETIRADA_CIERRE', 'ENTRE_SESIONES'
    )),
    constraint movimiento_caja_importe_positivo_ck check (importe > 0)
);

create unique index movimiento_caja_pago_uq
    on movimiento_caja(documento_pago_id)
    where documento_pago_id is not null;

create table movimiento_caja_denominacion (
    id uuid primary key,
    movimiento_caja_id uuid not null references movimiento_caja(id) on delete cascade,
    denominacion numeric(19,2) not null,
    cantidad integer not null,
    constraint movimiento_caja_denominacion_cantidad_ck check (cantidad > 0)
);

create table intento_arqueo_caja (
    id uuid primary key,
    sesion_caja_id uuid not null references sesion_caja(id),
    numero_intento integer not null,
    usuario_id uuid not null references usuario(id),
    creado_en timestamp with time zone not null,
    fondo_declarado numeric(19,2) not null,
    efectivo_teorico numeric(19,2) not null,
    descuadre numeric(19,2) not null,
    cerro_sesion boolean not null,
    constraint intento_arqueo_numero_ck check (numero_intento in (1, 2))
);
```

Seed one config row per existing store and add permissions through migration-compatible inserts:

```sql
insert into configuracion_caja_tienda (tienda_id)
select id from tienda
on conflict do nothing;

insert into permiso (id, codigo, translation_key, grupo, version)
values
    (gen_random_uuid(), 'GESTION_CUENTAS', 'cash.permissions.accounting', 'CASH', 0),
    (gen_random_uuid(), 'CASH_READ', 'cash.permissions.read', 'CASH', 0),
    (gen_random_uuid(), 'CASH_OPERATE', 'cash.permissions.operate', 'CASH', 0),
    (gen_random_uuid(), 'CASH_CONFIGURE', 'cash.permissions.configure', 'CASH', 0)
on conflict (codigo) do nothing;
```

- [ ] **Step 4: Add permission constants and translations**

Add constants in `CorePermissionBootstrap` and include them in `initialize()`:

```java
public static final String GESTION_CUENTAS = "GESTION_CUENTAS";
public static final String CASH_READ = "CASH_READ";
public static final String CASH_OPERATE = "CASH_OPERATE";
public static final String CASH_CONFIGURE = "CASH_CONFIGURE";
```

Add translations:

```properties
cash.permissions.accounting=Gestion de cuentas
cash.permissions.read=Consultar caja
cash.permissions.operate=Operar caja
cash.permissions.configure=Configurar caja
cash.session.open.required=La terminal necesita una sesion de caja abierta
cash.session.already.open=La terminal ya tiene una sesion de caja abierta
cash.session.not.found=Sesion de caja no encontrada
cash.amount.exceeds.available=La retirada supera el efectivo disponible
cash.authorization.required=La entrada necesita autorizacion de ADMIN o GESTION_CUENTAS
```

Use equivalent English and Chinese values in the other two files.

- [ ] **Step 5: Verify and commit**

Run:

```powershell
.\mvnw.cmd -Dtest=MigrationV17ContractTest,CorePermissionBootstrapTest test
```

Expected: PASS.

Commit:

```powershell
git add backend/src/main/resources/db/migration/V17__sesiones_caja.sql backend/src/main/java/com/tpverp/backend/security/application/CorePermissionBootstrap.java backend/src/main/resources/i18n/messages_es.properties backend/src/main/resources/i18n/messages_en.properties backend/src/main/resources/i18n/messages_zh.properties backend/src/test/java/com/tpverp/backend/persistence/MigrationV17ContractTest.java
git commit -m "feat: add cash session schema"
```

## Task 2: Cash Domain Entities And Calculations

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/cash/CashMovementType.java`
- Create: `backend/src/main/java/com/tpverp/backend/cash/CashSessionStatus.java`
- Create: `backend/src/main/java/com/tpverp/backend/cash/CashDenomination.java`
- Create: `backend/src/main/java/com/tpverp/backend/cash/CashStoreConfig.java`
- Create: `backend/src/main/java/com/tpverp/backend/cash/CashSession.java`
- Create: `backend/src/main/java/com/tpverp/backend/cash/CashMovement.java`
- Create: `backend/src/main/java/com/tpverp/backend/cash/CashMovementDenomination.java`
- Create: `backend/src/main/java/com/tpverp/backend/cash/CashReconciliationAttempt.java`
- Create: repository interfaces in the same package.
- Test: `backend/src/test/java/com/tpverp/backend/cash/CashSessionServiceTest.java`

- [ ] **Step 1: Write domain tests for money and second-attempt closing**

Create tests asserting:

```java
@Test
void secondMismatchClosesSessionAndStoresDiscrepancy() {
    var session = CashSession.open(storeId, terminalId, userId, now, euros("100.00"));
    session.registerAttempt(userId, now.plusSeconds(10), euros("90.00"), euros("100.00"), euros("0.00"));
    assertThat(session.getStatus()).isEqualTo(CashSessionStatus.ABIERTA);

    session.registerAttempt(userId, now.plusSeconds(20), euros("91.00"), euros("100.00"), euros("0.00"));

    assertThat(session.getStatus()).isEqualTo(CashSessionStatus.CERRADA);
    assertThat(session.getDescuadre()).isEqualByComparingTo("-9.00");
    assertThat(session.getFondoDejado()).isEqualByComparingTo("91.00");
}
```

Add a second test where first attempt is inside tolerance and closes immediately.

- [ ] **Step 2: Run the failing test**

Run:

```powershell
.\mvnw.cmd -Dtest=CashSessionServiceTest test
```

Expected: FAIL because cash domain classes do not exist.

- [ ] **Step 3: Implement enums and denominations**

Use fixed EUR values:

```java
public enum CashMovementType {
    COBRO_EFECTIVO, DEVOLUCION_EFECTIVO, ENTRADA, RETIRADA, RETIRADA_CIERRE, ENTRE_SESIONES
}

public enum CashSessionStatus {
    ABIERTA, CERRADA
}
```

`CashDenomination.valuesInEuroOrder()` must return `100, 50, 20, 10, 5, 2, 1, 0.50, 0.20, 0.10, 0.05, 0.02, 0.01` as `BigDecimal` scaled through existing `Money.euros`.

- [ ] **Step 4: Implement entities**

Keep entity methods public only where the service needs them. Add `//` comments only to public methods and non-obvious logic.

Required public methods:

```java
public static CashSession open(UUID storeId, UUID terminalId, UUID userId, Instant openedAt, BigDecimal openingFund)
public CashReconciliationAttempt registerAttempt(UUID userId, Instant at, BigDecimal declaredFund, BigDecimal expectedCash, BigDecimal tolerance)
public void close(UUID userId, Instant at, BigDecimal expectedCash, BigDecimal retainedFund, BigDecimal discrepancy)
public static CashMovement sessionMovement(...)
public static CashMovement betweenSessions(...)
public BigDecimal totalDenominations()
```

Use existing `Money.euros` for all amounts. `registerAttempt` closes on first attempt inside tolerance and always closes on second attempt.

- [ ] **Step 5: Add repositories**

Repositories need these methods:

```java
Optional<CashSession> findByTerminalIdAndStatus(UUID terminalId, CashSessionStatus status);
List<CashSession> findAllByTiendaIdAndOpenedAtBetweenOrderByOpenedAtDesc(UUID storeId, Instant from, Instant to);
List<CashMovement> findAllBySesionCajaId(UUID sessionId);
List<CashMovement> findAllByTerminalIdAndSesionCajaIsNullOrderByCreadoEnAsc(UUID terminalId);
boolean existsByDocumentoPagoId(UUID paymentId);
```

- [ ] **Step 6: Verify and commit**

Run:

```powershell
.\mvnw.cmd -Dtest=CashSessionServiceTest test
```

Expected: PASS for domain-only tests.

Commit:

```powershell
git add backend/src/main/java/com/tpverp/backend/cash backend/src/test/java/com/tpverp/backend/cash/CashSessionServiceTest.java
git commit -m "feat: add cash session domain"
```

## Task 3: Open Sessions, Entries And Withdrawals

**Files:**
- Create: `CashOpenRequest.java`, `CashEntryRequest.java`, `CashWithdrawalRequest.java`, `CashDenominationCommand.java`, `CashSessionView.java`, `CashMovementView.java`
- Create: `CashPermissionService.java`, `CashAmountCalculator.java`, `CashSessionService.java`
- Test: `backend/src/test/java/com/tpverp/backend/cash/CashSessionServiceTest.java`

- [ ] **Step 1: Add service tests**

Cover these cases only:

```java
@Test
void firstOpeningRequiresPreviousBetweenSessionEntry() { ... }

@Test
void opensWithPreviousRetainedFundPlusBetweenSessionMovements() { ... }

@Test
void sessionWithdrawalCannotExceedExpectedCash() { ... }

@Test
void entryDuringSessionRequiresAdminOrAccountingAuthorizer() { ... }
```

Use repository mocks or in-memory saved entities; do not start the full Spring context for these tests.

- [ ] **Step 2: Run failing tests**

Run:

```powershell
.\mvnw.cmd -Dtest=CashSessionServiceTest test
```

Expected: FAIL because `CashSessionService` does not exist or lacks methods.

- [ ] **Step 3: Implement `CashAmountCalculator`**

Public methods:

```java
public BigDecimal expectedCash(CashSession session, List<CashMovement> movements)
public BigDecimal nextOpeningFund(UUID terminalId)
public BigDecimal availableCash(CashSession session)
```

Rules:
- Expected cash = opening fund + cash collections - cash refunds + entries - withdrawals.
- Retained fund from last closed session is adjusted by between-session entries and withdrawals.
- Never include card, voucher or other non-cash payments.

- [ ] **Step 4: Implement `CashPermissionService`**

Public methods:

```java
public void requireSalesPermission(Authentication authentication)
public void requireAccountingPermission(Authentication authentication)
public boolean canSeeExpectedTotals(Authentication authentication)
public Usuario requireAuthorizer(String username, String password)
```

Use existing `UsuarioRepository`, password encoder and role/permission model. Accept authorizer only when user is `ADMIN` or has `GESTION_CUENTAS`.

- [ ] **Step 5: Implement `CashSessionService` operations**

Public methods:

```java
public CashSessionView status(UUID terminalId, Authentication authentication)
public CashSessionView open(UUID terminalId, Authentication authentication)
public CashMovementView entry(UUID terminalId, CashEntryRequest request, Authentication authentication)
public CashMovementView withdrawal(UUID terminalId, CashWithdrawalRequest request, Authentication authentication)
public CashMovementView betweenSessions(UUID terminalId, CashWithdrawalRequest request, Authentication authentication)
```

Important validations:
- Terminal must exist, belong to current store, be active and approved.
- Opening requires no other open session.
- First opening requires a prior between-session entry.
- Session entry requires mandatory comment and authorizer credentials.
- Withdrawal cannot exceed `availableCash`.
- Denomination sum must equal `amount` when denominations are supplied.
- If store config requires denomination breakdown for that operation, reject total-only requests.

- [ ] **Step 6: Verify and commit**

Run:

```powershell
.\mvnw.cmd -Dtest=CashSessionServiceTest test
```

Expected: PASS.

Commit:

```powershell
git add backend/src/main/java/com/tpverp/backend/cash backend/src/test/java/com/tpverp/backend/cash/CashSessionServiceTest.java
git commit -m "feat: manage cash openings and movements"
```

## Task 4: Blind Closing And Receipts

**Files:**
- Create: `CashCloseRequest.java`, `CashReceiptView.java`
- Create: `CashReceiptService.java`
- Modify: `CashSessionService.java`
- Test: `CashSessionServiceTest.java`

- [ ] **Step 1: Add closing tests**

Cover:

```java
@Test
void firstMismatchReturnsOpenSessionWithDiscrepancyOnly() { ... }

@Test
void secondMismatchClosesAndStoresDiscrepancyWithoutExplanation() { ... }

@Test
void sellerCloseViewDoesNotExposeExpectedCash() { ... }

@Test
void accountingCloseViewIncludesExpectedCash() { ... }
```

- [ ] **Step 2: Run failing tests**

Run:

```powershell
.\mvnw.cmd -Dtest=CashSessionServiceTest test
```

Expected: FAIL.

- [ ] **Step 3: Implement closing**

Add:

```java
public CashSessionView close(UUID terminalId, CashCloseRequest request, Authentication authentication)
```

Flow:
1. Lock current open session.
2. Register final withdrawal of type `RETIRADA_CIERRE` if request amount is greater than zero.
3. Calculate expected cash after the final withdrawal.
4. Save `CashReconciliationAttempt`.
5. If first mismatch is outside tolerance, return open session and `descuadre` only.
6. If inside tolerance or second attempt, close session.
7. Mark late close when close date differs from open date in server zone.

- [ ] **Step 4: Implement receipt views**

`CashReceiptService.withdrawalReceipt(movementId)` returns amount, denominations if present, date, user, terminal, session and two empty signature labels.

`CashReceiptService.closeReceipt(sessionId, authentication)` filters:
- Seller: declared fund, discrepancy, session identifiers and signature labels.
- Admin/accounting: full summary including expected cash.

- [ ] **Step 5: Verify and commit**

Run:

```powershell
.\mvnw.cmd -Dtest=CashSessionServiceTest test
```

Expected: PASS.

Commit:

```powershell
git add backend/src/main/java/com/tpverp/backend/cash backend/src/test/java/com/tpverp/backend/cash/CashSessionServiceTest.java
git commit -m "feat: close cash sessions with blind reconciliation"
```

## Task 5: Document Payment Integration

**Files:**
- Create: `CashPaymentRecorder.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentService.java`
- Test: `backend/src/test/java/com/tpverp/backend/cash/CashPaymentRecorderTest.java`
- Test: extend `backend/src/test/java/com/tpverp/backend/document/DocumentServiceTest.java`

- [ ] **Step 1: Add integration tests**

Required cases:

```java
@Test
void ticketCreationRequiresOpenCashSession() { ... }

@Test
void ticketCashPaymentCreatesCashMovement() { ... }

@Test
void mixedPaymentRecordsOnlyCashAmount() { ... }

@Test
void invoicePaymentRequiresOpenCashSessionAndRecordsCashOnly() { ... }
```

- [ ] **Step 2: Run failing tests**

Run:

```powershell
.\mvnw.cmd -Dtest=CashPaymentRecorderTest,DocumentServiceTest test
```

Expected: FAIL.

- [ ] **Step 3: Implement `CashPaymentRecorder`**

Public methods:

```java
public void requireOpenSession(UUID terminalId)
public void recordDocumentPayments(UUID terminalId, Documento document)
```

Rules:
- Determine cash method by `MetodoPago.nombre == "EFECTIVO"`.
- Positive cash payments create `COBRO_EFECTIVO`.
- Negative cash impact from refunds creates `DEVOLUCION_EFECTIVO`.
- Unique `documento_pago_id` prevents duplicates.
- Voucher/card payments create no cash movement.

- [ ] **Step 4: Modify `DocumentService`**

Inject `CashPaymentRecorder`.

Before `createTicket` confirms or parks payment-sensitive work, call:

```java
cash.requireOpenSession(organization.currentTerminalId(authentication));
```

After the document is saved with payments, call:

```java
cash.recordDocumentPayments(organization.currentTerminalId(authentication), saved);
```

If `CurrentOrganization` does not expose terminal id yet, add a small `CurrentTerminal` component that resolves it from the authenticated terminal/session context already used by terminal security.

For `payInvoice`, require an open session and record only cash movements after payments are persisted.

- [ ] **Step 5: Verify and commit**

Run:

```powershell
.\mvnw.cmd -Dtest=CashPaymentRecorderTest,DocumentServiceTest test
```

Expected: PASS.

Commit:

```powershell
git add backend/src/main/java/com/tpverp/backend/cash backend/src/main/java/com/tpverp/backend/document/DocumentService.java backend/src/test/java/com/tpverp/backend/cash/CashPaymentRecorderTest.java backend/src/test/java/com/tpverp/backend/document/DocumentServiceTest.java
git commit -m "feat: link cash sessions to document payments"
```

## Task 6: Cash API, Config And Reports

**Files:**
- Create: `CashController.java`
- Create: `CashReportService.java`
- Extend: `CashSessionService.java`
- Test: `backend/src/test/java/com/tpverp/backend/cash/CashControllerContractTest.java`

- [ ] **Step 1: Add controller contract tests**

Use `MockMvc` and cover:

```java
@Test
void sellerStatusDoesNotExposeExpectedTotals() { ... }

@Test
void accountingUserCanReadDailyReport() { ... }

@Test
void sellerCannotReadReports() { ... }

@Test
void adminCanUpdateCashConfig() { ... }
```

- [ ] **Step 2: Run failing tests**

Run:

```powershell
.\mvnw.cmd -Dtest=CashControllerContractTest test
```

Expected: FAIL because controller endpoints do not exist.

- [ ] **Step 3: Implement endpoints**

Base path: `/api/v1/cash`.

Endpoints:

```text
GET    /status?terminalId=
POST   /sessions/open
POST   /sessions/close
POST   /movements/entry
POST   /movements/withdrawal
POST   /movements/between-sessions
GET    /receipts/withdrawals/{movementId}
GET    /receipts/sessions/{sessionId}
GET    /reports?terminalId=&storeId=&from=&to=
GET    /config
PUT    /config
```

Filter expected totals in all views using `CashPermissionService.canSeeExpectedTotals`.

- [ ] **Step 4: Implement reports and config**

`CashReportService` returns totals by cash movement type, retained funds and discrepancies for terminal/store and date range. It requires `ADMIN` or `GESTION_CUENTAS`.

Config update rules:
- `tolerancia_descuadre >= 0`.
- Denomination booleans independent for entry, withdrawal and close.
- Only `ADMIN` or `GESTION_CUENTAS`.

- [ ] **Step 5: Verify and commit**

Run:

```powershell
.\mvnw.cmd -Dtest=CashControllerContractTest,CashSessionServiceTest test
```

Expected: PASS.

Commit:

```powershell
git add backend/src/main/java/com/tpverp/backend/cash backend/src/test/java/com/tpverp/backend/cash/CashControllerContractTest.java
git commit -m "feat: expose cash session api"
```

## Task 7: Final Verification And Cleanup

**Files:**
- Review all cash files.
- Review touched document/security/i18n files.

- [ ] **Step 1: Run focused backend tests**

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=MigrationV17ContractTest,CashSessionServiceTest,CashPaymentRecorderTest,CashControllerContractTest,DocumentServiceTest,CorePermissionBootstrapTest test
```

Expected: PASS.

- [ ] **Step 2: Run migration suite**

Run:

```powershell
.\mvnw.cmd -Dtest=PostgreSqlMigrationTest,MigrationV17ContractTest test
```

Expected: PASS.

- [ ] **Step 3: Run full tests only if focused tests pass**

Run:

```powershell
.\mvnw.cmd test
```

Expected: PASS. If this is too slow during development, keep it for the final verification commit only.

- [ ] **Step 4: Review comments and localization**

Check:

```powershell
rg "\"[^\"]*[A-Za-zÁÉÍÓÚáéíóúñÑ][^\"]*\"" backend/src/main/java/com/tpverp/backend/cash
rg "public .*\\(" backend/src/main/java/com/tpverp/backend/cash
```

Expected:
- User-facing errors are localized or mapped through existing legacy i18n.
- Public methods with non-obvious behavior have concise `//` comments.

- [ ] **Step 5: Commit final cleanup**

Commit only if Step 4 required edits:

```powershell
git add backend/src/main/java/com/tpverp/backend/cash backend/src/main/resources/i18n
git commit -m "refactor: polish cash session implementation"
```

## Self-Review

- Spec coverage: covered schema/config, sessions, movements, denominations, entries, withdrawals, blind closing, reports, permissions, receipt data and document payment integration.
- Deliberate test reduction: tests are focused on migrations, cash service behavior, document payment coupling and API permission filtering. Broad report permutations and printer implementation are not expanded because this block stores printable data, not a real printer driver.
- Known implementation dependency: the executor must resolve current terminal id from the existing terminal/session security context. If no reusable component exists, create `CurrentTerminal` in Task 5 and test it there.
- Placeholder scan: no task contiene instrucciones vagas; cada paso tiene ficheros, metodos publicos y comandos de verificacion concretos.
