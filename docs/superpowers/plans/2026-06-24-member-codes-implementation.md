# Member and Party Codes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the legacy `SOCIO` concept with `MEMBER` and add immutable, concurrency-safe business codes for customers, members, suppliers, and sales representatives.

**Architecture:** Flyway V16 transforms existing rows deterministically and installs database constraints. A focused `PartyCodeAllocator` owns number reservation: customer/member counters are scoped to the existing three-digit store code, while supplier/commercial numbers are scoped to company. Domain entities own membership invariants and services expose only user-editable fields.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, PostgreSQL/Flyway, JUnit 5, AssertJ, Mockito, Testcontainers.

---

### Task 1: PostgreSQL migration contract and data transformation

**Files:**
- Create: `backend/src/main/resources/db/migration/V16__members_y_codigos_comerciales.sql`
- Modify: `backend/src/test/java/com/tpverp/backend/persistence/PostgreSqlMigrationTest.java`
- Test: `backend/src/test/java/com/tpverp/backend/persistence/PostgreSqlMigrationTest.java`

- [ ] **Step 1: Write failing migration assertions**

Add assertions that migrated schemas contain `client_id`, `is_member`, `member_id`, `num_member`, `member_since`, `member_balance`, `supplier_id`, `commercial_id`, and `party_code_counter`; assert that no active column/table/check value uses `socio`, and seed two companies/stores/parties to verify deterministic backfill.

```java
assertThat(columns("cliente")).contains("client_id", "is_member", "member_id",
        "num_member", "member_since", "member_balance");
assertThat(single("select client_id from cliente where numero_documento='A1'"))
        .isEqualTo("C-001-000001");
```

- [ ] **Step 2: Run the migration test and verify RED**

Run: `./mvnw -Dtest=PostgreSqlMigrationTest test`
Expected: FAIL because V16 and the new columns do not exist.

- [ ] **Step 3: Add V16 transformation**

The migration must rename `saldo_socio` to `member_balance`, rename `movimiento_saldo_socio` to `member_balance_movement`, convert `SOCIO` values/checks to `MEMBER`, add/backfill codes with `row_number()` ordered by normalized document/name plus UUID, and install these key constraints:

```sql
unique (empresa_id, client_id);
unique (empresa_id, member_id);
unique (empresa_id, num_member);
check (client_id ~ '^C-[0-9]{3}-[0-9]{6}$');
check (member_id is null or member_id ~ '^M-[0-9]{3}-[0-9]{6}$');
check ((is_member and tarifa = 'MEMBER') or (not is_member and tarifa = 'VENTA'));
```

Create `party_code_counter(scope_id uuid, code_type varchar(16), last_number bigint)` with a composite primary key and seed CLIENT/MEMBER maxima for each store.

- [ ] **Step 4: Run migration tests and verify GREEN**

Run: `./mvnw -Dtest=PostgreSqlMigrationTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/resources/db/migration/V16__members_y_codigos_comerciales.sql backend/src/test/java/com/tpverp/backend/persistence/PostgreSqlMigrationTest.java
git commit -m "feat: migrate members and party codes"
```

### Task 2: Code allocation and domain invariants

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/party/PartyCodeType.java`
- Create: `backend/src/main/java/com/tpverp/backend/party/PartyCodeAllocator.java`
- Create: `backend/src/test/java/com/tpverp/backend/party/PartyCodeAllocatorTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/party/PartyContext.java`
- Modify: `backend/src/main/java/com/tpverp/backend/party/Customer.java`
- Modify: `backend/src/test/java/com/tpverp/backend/party/CustomerTest.java`

- [ ] **Step 1: Write failing allocator and membership tests**

Test exact formatting and first activation/reactivation:

```java
assertThat(allocator.nextClient(store)).isEqualTo("C-001-000001");
customer.activateMember(store, LocalDate.of(2026, 6, 24), "M-001-000001");
customer.deactivateMember();
customer.activateMember(store, LocalDate.of(2026, 7, 1), "M-001-000002");
assertThat(customer.getMemberId()).isEqualTo("M-001-000001");
```

- [ ] **Step 2: Run tests and verify RED**

Run: `./mvnw -Dtest=PartyCodeAllocatorTest,CustomerTest test`
Expected: FAIL because allocator and member API are absent.

- [ ] **Step 3: Implement minimal allocator and entity state**

Use `JdbcTemplate` with `INSERT ... ON CONFLICT ... DO UPDATE SET last_number = ... RETURNING last_number` so reservation is atomic. Add immutable assignment methods and normalize `numMember` with `PartyValues.optional`.

```java
public String nextClient(Tienda store) {
    return "C-" + store.getCodigoTienda() + "-" + six(next(store.getId(), CLIENT));
}
```

Expose `PartyContext.currentStore()` and make balance checks depend on `isMember`, not tariff alone.

- [ ] **Step 4: Run tests and verify GREEN**

Run: `./mvnw -Dtest=PartyCodeAllocatorTest,CustomerTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/party backend/src/test/java/com/tpverp/backend/party/PartyCodeAllocatorTest.java backend/src/test/java/com/tpverp/backend/party/CustomerTest.java
git commit -m "feat: allocate customer and member codes"
```

### Task 3: Customer service and API

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/party/CustomerService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/party/CustomerController.java`
- Modify: `backend/src/main/java/com/tpverp/backend/party/CustomerRepository.java`
- Modify: `backend/src/test/java/com/tpverp/backend/party/CustomerServiceTest.java`
- Modify: `backend/src/test/java/com/tpverp/backend/party/PartyControllerContractTest.java`

- [ ] **Step 1: Write failing service/API tests**

Assert that create assigns a client code, `isMember=true` assigns member code/date and MEMBER rate, duplicate `numMember` is rejected, deactivation preserves history, and request JSON cannot set automatic fields.

```java
assertThat(created.clientId()).isEqualTo("C-001-000001");
assertThat(created.isMember()).isTrue();
assertThat(created.memberId()).isEqualTo("M-001-000001");
```

- [ ] **Step 2: Run tests and verify RED**

Run: `./mvnw -Dtest=CustomerServiceTest,PartyControllerContractTest test`
Expected: FAIL because DTOs and service orchestration lack member fields.

- [ ] **Step 3: Implement service/API changes**

Change customer commands to accept `boolean member` and `String numMember`; derive rate internally. Add automatic fields only to `CustomerView`, use `Clock` for `memberSince`, and query uniqueness by company/normalized member number.

- [ ] **Step 4: Run tests and verify GREEN**

Run: `./mvnw -Dtest=CustomerServiceTest,PartyControllerContractTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/party backend/src/test/java/com/tpverp/backend/party
git commit -m "feat: expose member lifecycle in customer api"
```

### Task 4: Supplier and commercial codes

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/party/Supplier.java`
- Modify: `backend/src/main/java/com/tpverp/backend/party/SupplierService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/party/SalesRepresentative.java`
- Modify: `backend/src/main/java/com/tpverp/backend/party/SalesRepresentativeService.java`
- Modify: `backend/src/test/java/com/tpverp/backend/party/SupplierServiceTest.java`
- Modify: `backend/src/test/java/com/tpverp/backend/party/SupplierSalesRepresentativeTest.java`

- [ ] **Step 1: Write failing code exposure tests**

```java
assertThat(service.create(command).supplierId()).isEqualTo("S-000001");
assertThat(representativeService.create(command).commercialId()).isEqualTo("CO-000001");
```

- [ ] **Step 2: Run tests and verify RED**

Run: `./mvnw -Dtest=SupplierServiceTest,SupplierSalesRepresentativeTest test`
Expected: FAIL because code fields do not exist.

- [ ] **Step 3: Implement immutable codes and bounded retry**

Allocate company-scoped numbers, assign before save, catch unique-constraint collisions in an isolated retry transaction, recalculate, and retry at most three times. Expose codes in views but not request records.

- [ ] **Step 4: Run tests and verify GREEN**

Run: `./mvnw -Dtest=SupplierServiceTest,SupplierSalesRepresentativeTest test`
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/party backend/src/test/java/com/tpverp/backend/party
git commit -m "feat: add supplier and commercial codes"
```

### Task 5: Rename all active SOCIO terminology

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/party/CustomerRate.java`
- Modify: `backend/src/main/java/com/tpverp/backend/catalog/PriceTier.java`
- Modify: `backend/src/main/java/com/tpverp/backend/catalog/Product.java`
- Modify: `backend/src/main/java/com/tpverp/backend/catalog/CatalogService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/party/MemberBalanceMovement.java`
- Modify: `backend/src/main/resources/i18n/messages_es.properties`
- Modify: `backend/src/main/resources/i18n/messages_en.properties`
- Modify: `backend/src/main/resources/i18n/messages_zh.properties`
- Modify: affected tests under `backend/src/test/java`

- [ ] **Step 1: Add a failing terminology scan test**

Extend the persistence/API contract to reject active Java/resources occurrences outside historical migrations:

```java
assertThat(activeSourceText).doesNotContain("SOCIO").doesNotContain("socio");
```

- [ ] **Step 2: Run affected tests and verify RED**

Run: `./mvnw test -Dtest=CatalogDomainTest,CatalogServiceTest,PartyControllerContractTest`
Expected: FAIL while legacy enum values remain.

- [ ] **Step 3: Rename values, mappings, messages and fixtures**

Use `MEMBER`, `memberPrice`, `member_balance_movement`, and member wording consistently. Preserve unrelated in-progress i18n edits while changing only matching legacy keys/values.

- [ ] **Step 4: Run targeted tests and verify GREEN**

Run: `./mvnw test -Dtest=CatalogDomainTest,CatalogServiceTest,PartyControllerContractTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java backend/src/main/resources/i18n backend/src/test/java
git commit -m "refactor: replace socio terminology with member"
```

### Task 6: Full verification

**Files:**
- Modify only files required by failures attributable to this feature.

- [ ] **Step 1: Scan active sources**

Run: `rg -n -i "socio" backend/src/main/java backend/src/test backend/src/main/resources --glob '!db/migration/V[1-9]*__*.sql'`
Expected: no active legacy terminology.

- [ ] **Step 2: Run formatting/diff checks**

Run: `git diff --check`
Expected: exit 0.

- [ ] **Step 3: Run the complete backend suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS with zero failures and errors.

- [ ] **Step 4: Review scope**

Run: `git status --short` and `git diff --stat HEAD~5`
Expected: feature files are identifiable; pre-existing certificate-management changes remain uncommitted and untouched except for unavoidable shared migration/i18n test integration.
