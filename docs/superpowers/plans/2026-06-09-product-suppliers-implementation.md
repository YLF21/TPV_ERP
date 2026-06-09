# Product Suppliers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gestionar los proveedores vinculados a cada producto y mantener automaticamente la ultima fecha de entrada al confirmar compras.

**Architecture:** El dominio vivira en `catalog` mediante una entidad y un `ProductSupplierService` separados de `CatalogService`. `DocumentService` invocara una interfaz pequena de registro de compras para no conocer detalles de persistencia; las restricciones de unicidad y opcionalidad quedaran tambien protegidas por PostgreSQL.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring Data JPA, Spring Security, Flyway, PostgreSQL 18, JUnit 5, Mockito, AssertJ.

---

## File Map

- Create `backend/src/main/resources/db/migration/V5__producto_proveedor.sql`: adapta referencia y sus indices.
- Create `backend/src/main/java/com/tpverp/backend/catalog/ProductSupplier.java`: entidad y reglas de la relacion.
- Create `backend/src/main/java/com/tpverp/backend/catalog/ProductSupplierRepository.java`: consultas aisladas por tienda y ordenadas por CIF/NIF.
- Create `backend/src/main/java/com/tpverp/backend/catalog/ProductSupplierService.java`: casos de uso manuales y actualizacion desde compras.
- Create `backend/src/main/java/com/tpverp/backend/catalog/ProductSupplierController.java`: API bajo `/api/v1/products/{productId}/suppliers`.
- Create `backend/src/main/java/com/tpverp/backend/document/ConfirmedPurchaseRecorder.java`: puerto minimo usado por documentos.
- Modify `backend/src/main/java/com/tpverp/backend/document/DocumentService.java`: valida proveedor activo y registra compras confirmadas.
- Modify `backend/src/main/java/com/tpverp/backend/party/SupplierRepository.java`: orden general por numero de documento.
- Modify `backend/src/main/java/com/tpverp/backend/party/SupplierService.java`: usa el nuevo orden.
- Test `backend/src/test/java/com/tpverp/backend/catalog/ProductSupplierTest.java`.
- Test `backend/src/test/java/com/tpverp/backend/catalog/ProductSupplierServiceTest.java`.
- Test `backend/src/test/java/com/tpverp/backend/catalog/ProductSupplierControllerContractTest.java`.
- Modify `backend/src/test/java/com/tpverp/backend/document/DocumentServiceTest.java`.
- Modify `backend/src/test/java/com/tpverp/backend/party/SupplierServiceTest.java`.
- Modify `backend/src/test/java/com/tpverp/backend/persistence/PostgreSqlMigrationTest.java`.

### Task 1: Migration And Domain Entity

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__producto_proveedor.sql`
- Create: `backend/src/main/java/com/tpverp/backend/catalog/ProductSupplier.java`
- Create: `backend/src/main/java/com/tpverp/backend/catalog/ProductSupplierRepository.java`
- Create: `backend/src/test/java/com/tpverp/backend/catalog/ProductSupplierTest.java`
- Modify: `backend/src/test/java/com/tpverp/backend/persistence/PostgreSqlMigrationTest.java`

- [ ] **Step 1: Write the failing domain tests**

Create tests that construct a relation, normalize an optional reference and keep
the greatest purchase date:

```java
@Test
void normalizesOptionalReference() {
    var link = new ProductSupplier(product, supplier, " ref-1 ");

    assertThat(link.getSupplierReference()).isEqualTo("REF-1");

    link.changeReference(" ");

    assertThat(link.getSupplierReference()).isNull();
}

@Test
void lastEntryDateNeverMovesBackwards() {
    var link = new ProductSupplier(product, supplier, null);

    link.registerEntry(LocalDate.of(2026, 6, 9));
    link.registerEntry(LocalDate.of(2026, 5, 1));

    assertThat(link.getLastEntryDate()).isEqualTo(LocalDate.of(2026, 6, 9));
}
```

- [ ] **Step 2: Run the domain test and verify RED**

Run:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProductSupplierTest test
```

Expected: compilation fails because `ProductSupplier` does not exist.

- [ ] **Step 3: Implement the minimal entity**

Implement `ProductSupplier` mapped to `producto_proveedor`, with `UUID id`,
lazy `Product product`, lazy `Supplier supplier`, nullable
`supplierReference`, nullable `lastEntryDate`, and `@Version long version`.
Use these domain methods:

```java
public void changeReference(String reference) {
    supplierReference = reference == null || reference.isBlank()
            ? null
            : reference.trim().toUpperCase(Locale.ROOT);
}

// Conserva la fecha mas reciente aunque se confirme despues un documento antiguo.
public void registerEntry(LocalDate date) {
    Objects.requireNonNull(date, "fechaEntrada");
    if (lastEntryDate == null || date.isAfter(lastEntryDate)) {
        lastEntryDate = date;
    }
}
```

Expose getters for id, product id, supplier, reference and last entry date.

- [ ] **Step 4: Add the repository contract**

Create:

```java
public interface ProductSupplierRepository extends JpaRepository<ProductSupplier, UUID> {

    Optional<ProductSupplier> findByProductIdAndSupplierId(UUID productId, UUID supplierId);

    @Query("""
            select link
            from ProductSupplier link
            join fetch link.supplier supplier
            where link.product.id = :productId
              and link.product.storeId = :storeId
            order by supplier.documentNumber
            """)
    List<ProductSupplier> findForProduct(UUID productId, UUID storeId);
}
```

Add package-visible entity getters needed by JPQL and mapping without exposing
setters.

- [ ] **Step 5: Add and verify migration V5**

Create:

```sql
drop index if exists ux_producto_proveedor_referencia;

alter table producto_proveedor
    alter column referencia_proveedor drop not null;

alter table producto_proveedor
    drop constraint if exists producto_proveedor_referencia_proveedor_check;

alter table producto_proveedor
    add constraint ck_producto_proveedor_referencia
    check (
        referencia_proveedor is null
        or (
            referencia_proveedor = upper(trim(referencia_proveedor))
            and char_length(referencia_proveedor) > 0
        )
    );
```

Extend `PostgreSqlMigrationTest` to query `information_schema.columns` and
`pg_indexes`, asserting that `referencia_proveedor` is nullable and the old
unique index no longer exists.

Run with the configured local PostgreSQL credentials:

```powershell
$env:TPV_ERP_TEST_DB_URL='jdbc:postgresql://localhost:5432/tpv_erp_test'
$env:TPV_ERP_TEST_DB_USER='postgres'
$env:TPV_ERP_TEST_DB_PASSWORD='admin'
cd backend
.\mvnw.cmd -Dtest=PostgreSqlMigrationTest test
```

Expected: PASS. If the local database/user differs, use the existing
`TPV_ERP_TEST_DB_*` environment values without writing credentials to files.

- [ ] **Step 6: Run focused tests**

```powershell
cd backend
.\mvnw.cmd -Dtest=ProductSupplierTest,PostgreSqlMigrationTest test
```

Expected: all selected tests PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/resources/db/migration/V5__producto_proveedor.sql backend/src/main/java/com/tpverp/backend/catalog/ProductSupplier.java backend/src/main/java/com/tpverp/backend/catalog/ProductSupplierRepository.java backend/src/test/java/com/tpverp/backend/catalog/ProductSupplierTest.java backend/src/test/java/com/tpverp/backend/persistence/PostgreSqlMigrationTest.java
git commit -m "feat: add product supplier relation model"
```

### Task 2: Product Supplier Service

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/catalog/ProductSupplierService.java`
- Create: `backend/src/test/java/com/tpverp/backend/catalog/ProductSupplierServiceTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/party/SupplierRepository.java`
- Modify: `backend/src/main/java/com/tpverp/backend/party/SupplierService.java`
- Modify: `backend/src/test/java/com/tpverp/backend/party/SupplierServiceTest.java`

- [ ] **Step 1: Write failing service tests**

Cover these behaviors with Mockito:

```java
@Test
void linksOnlyActiveSuppliersFromTheCurrentCompany() {
    when(products.findById(product.getId())).thenReturn(Optional.of(product));
    when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
            .thenReturn(Optional.of(supplier));
    when(links.findByProductIdAndSupplierId(product.getId(), supplier.getId()))
            .thenReturn(Optional.empty());
    when(links.save(any())).thenAnswer(call -> call.getArgument(0));

    var result = service.link(product.getId(), supplier.getId(), " ref ");

    assertThat(result.supplierReference()).isEqualTo("REF");
}

@Test
void rejectsInactiveSupplier() {
    supplier.deactivate();
    when(products.findById(product.getId())).thenReturn(Optional.of(product));
    when(suppliers.findByIdAndCompanyId(supplier.getId(), company.getId()))
            .thenReturn(Optional.of(supplier));

    assertThatThrownBy(() -> service.link(product.getId(), supplier.getId(), null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("inactivo");
}

@Test
void listUsesRepositoryOrderByDocumentNumber() {
    when(links.findForProduct(product.getId(), store.getId()))
            .thenReturn(List.of(linkA, linkB));

    assertThat(service.list(product.getId()))
            .extracting(ProductSupplierService.ProductSupplierView::documentNumber)
            .containsExactly("A00000001", "B00000001");
}
```

Also test duplicate link conflict, reference update, unlink, product isolation by
current store and supplier isolation by current company.

- [ ] **Step 2: Run tests and verify RED**

```powershell
cd backend
.\mvnw.cmd -Dtest=ProductSupplierServiceTest,SupplierServiceTest test
```

Expected: compilation fails because `ProductSupplierService` and the new
repository ordering method do not exist.

- [ ] **Step 3: Implement manual relation use cases**

Create a Spring `@Service` with:

```java
@Transactional(readOnly = true)
public List<ProductSupplierView> list(UUID productId)

@Transactional
public ProductSupplierView link(UUID productId, UUID supplierId, String reference)

@Transactional
public ProductSupplierView updateReference(
        UUID productId, UUID supplierId, String reference)

@Transactional
public void unlink(UUID productId, UUID supplierId)
```

Resolve products with:

```java
private Product product(UUID productId) {
    var storeId = organization.currentStore().getId();
    return products.findById(productId)
            .filter(product -> product.getStoreId().equals(storeId))
            .orElseThrow(() -> new IllegalArgumentException("Producto no encontrado"));
}
```

Resolve suppliers through `findByIdAndCompanyId`. Reject inactive suppliers only
when creating a new manual link. Return an immutable record containing supplier
id, legal name, document type, document number, active state, reference and last
entry date.

- [ ] **Step 4: Change the general supplier ordering**

Replace:

```java
List<Supplier> findByCompanyIdOrderByLegalName(UUID companyId);
```

with:

```java
List<Supplier> findByCompanyIdOrderByDocumentNumberAsc(UUID companyId);
```

Update `SupplierService.list()` and its test to assert CIF/NIF ordering,
independent of purchase history.

- [ ] **Step 5: Run focused tests**

```powershell
cd backend
.\mvnw.cmd -Dtest=ProductSupplierServiceTest,SupplierServiceTest test
```

Expected: all selected tests PASS.

- [ ] **Step 6: Refactor without changing behavior**

Remove duplicated product/supplier lookup code only if the focused tests remain
green. Keep `ProductSupplierService` responsible solely for this relationship;
do not move these operations into `CatalogService` or `SupplierService`.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/catalog/ProductSupplierService.java backend/src/test/java/com/tpverp/backend/catalog/ProductSupplierServiceTest.java backend/src/main/java/com/tpverp/backend/party/SupplierRepository.java backend/src/main/java/com/tpverp/backend/party/SupplierService.java backend/src/test/java/com/tpverp/backend/party/SupplierServiceTest.java
git commit -m "feat: manage product suppliers"
```

### Task 3: Product Supplier API

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/catalog/ProductSupplierController.java`
- Create: `backend/src/test/java/com/tpverp/backend/catalog/ProductSupplierControllerContractTest.java`

- [ ] **Step 1: Write the failing controller contract**

Use reflection to assert the class root, mappings and permissions:

```java
@Test
void exposesProductSupplierEndpointsWithProductPermissions() throws Exception {
    var root = ProductSupplierController.class.getAnnotation(RequestMapping.class);
    assertThat(root.value()).containsExactly("/api/v1/products/{productId}/suppliers");

    assertPermission("list", PRODUCTS_READ, UUID.class);
    assertPermission("link", PRODUCTS_WRITE, UUID.class, LinkRequest.class);
    assertPermission("update", PRODUCTS_WRITE, UUID.class, UUID.class, LinkRequest.class);
    assertPermission("unlink", PRODUCTS_WRITE, UUID.class, UUID.class);
}
```

- [ ] **Step 2: Run the test and verify RED**

```powershell
cd backend
.\mvnw.cmd -Dtest=ProductSupplierControllerContractTest test
```

Expected: compilation fails because the controller does not exist.

- [ ] **Step 3: Implement the controller**

Create endpoints:

```java
@GetMapping
@PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PRODUCTS_READ + "')")
public List<ProductSupplierView> list(@PathVariable UUID productId)

@PostMapping
@PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PRODUCTS_WRITE + "')")
public ProductSupplierView link(
        @PathVariable UUID productId, @Valid @RequestBody LinkRequest request)

@PutMapping("/{supplierId}")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PRODUCTS_WRITE + "')")
public ProductSupplierView update(
        @PathVariable UUID productId,
        @PathVariable UUID supplierId,
        @Valid @RequestBody LinkRequest request)

@DeleteMapping("/{supplierId}")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PRODUCTS_WRITE + "')")
public ResponseEntity<Void> unlink(
        @PathVariable UUID productId, @PathVariable UUID supplierId)
```

Use:

```java
public record LinkRequest(@NotNull UUID supplierId, String supplierReference) {
}
```

For update, ignore no identifiers from the body: define a separate
`ReferenceRequest(String supplierReference)` so the supplier identity only comes
from the path.

- [ ] **Step 4: Run controller tests**

```powershell
cd backend
.\mvnw.cmd -Dtest=ProductSupplierControllerContractTest,CatalogControllerContractTest test
```

Expected: all selected tests PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/catalog/ProductSupplierController.java backend/src/test/java/com/tpverp/backend/catalog/ProductSupplierControllerContractTest.java
git commit -m "feat: expose product supplier API"
```

### Task 4: Automatic Updates From Confirmed Purchases

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/document/ConfirmedPurchaseRecorder.java`
- Modify: `backend/src/main/java/com/tpverp/backend/catalog/ProductSupplierService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentService.java`
- Modify: `backend/src/test/java/com/tpverp/backend/catalog/ProductSupplierServiceTest.java`
- Modify: `backend/src/test/java/com/tpverp/backend/document/DocumentServiceTest.java`

- [ ] **Step 1: Write failing catalog tests for automatic recording**

Add tests:

```java
@Test
void confirmedPurchaseCreatesMissingLinksWithNullReference() {
    when(links.findByProductIdAndSupplierId(product.getId(), supplier.getId()))
            .thenReturn(Optional.empty());

    service.record(
            supplier.getId(),
            LocalDate.of(2026, 6, 9),
            List.of(product.getId()));

    verify(links).save(argThat(link ->
            link.getSupplierReference() == null
            && link.getLastEntryDate().equals(LocalDate.of(2026, 6, 9))));
}

@Test
void confirmedPurchaseUpdatesExistingLinkWithoutMovingDateBackwards() {
    var existing = new ProductSupplier(product, supplier, "REF");
    existing.registerEntry(LocalDate.of(2026, 6, 9));
    when(links.findByProductIdAndSupplierId(product.getId(), supplier.getId()))
            .thenReturn(Optional.of(existing));

    service.record(
            supplier.getId(),
            LocalDate.of(2026, 5, 1),
            List.of(product.getId()));

    assertThat(existing.getLastEntryDate()).isEqualTo(LocalDate.of(2026, 6, 9));
}
```

Also assert duplicate product ids are processed once and inactive suppliers are
rejected before any relation is written.

- [ ] **Step 2: Write failing document integration tests**

Add a `@Mock ConfirmedPurchaseRecorder purchaseRecorder` to
`DocumentServiceTest`, pass it to the constructor and verify:

```java
verify(purchaseRecorder).record(
        supplier.getId(),
        note.getFecha(),
        note.getLineas().stream().map(DocumentoLinea::getProductoId).toList());
```

Required cases:

- Confirmed `ALBARAN_COMPRA` records the purchase.
- Direct `FACTURA_COMPRA` records the purchase.
- Non-direct `FACTURA_COMPRA` does not record it again.
- `RECTIFICATIVA_COMPRA` does not alter product-supplier data.
- Sales documents do not call the recorder.
- Inactive supplier prevents confirmation before numbering and stock.

- [ ] **Step 3: Run tests and verify RED**

```powershell
cd backend
.\mvnw.cmd -Dtest=ProductSupplierServiceTest,DocumentServiceTest test
```

Expected: compilation fails because `ConfirmedPurchaseRecorder` and `record`
are missing.

- [ ] **Step 4: Implement the port and service adapter**

Create:

```java
@FunctionalInterface
public interface ConfirmedPurchaseRecorder {
    void record(UUID supplierId, LocalDate date, Collection<UUID> productIds);
}
```

Make `ProductSupplierService` implement it. Its public `record` method must:

1. Resolve an active supplier from the current company.
2. Deduplicate product ids while retaining deterministic order.
3. Resolve every product from the current store.
4. Create a relation with null reference when absent.
5. Apply `registerEntry(date)` and save.

Add a `//` comment to this public orchestration method because its idempotency
and non-decreasing date rule are not obvious.

- [ ] **Step 5: Integrate purchase recording into DocumentService**

Inject `ConfirmedPurchaseRecorder`. Before assigning a number, validate purchase
suppliers using `findByIdAndCompanyId` and `Supplier::isActive`.

Capture whether the draft is a direct purchase before `confirm` changes state:

```java
boolean recordsPurchase = document.getTipo() == TipoDocumento.ALBARAN_COMPRA
        || (document.getTipo() == TipoDocumento.FACTURA_COMPRA
            && document.isOrigenStock());
```

After stock confirmation and before returning:

```java
if (recordsPurchase) {
    purchaseRecorder.record(
            document.getProveedorId(),
            document.getFecha(),
            document.getLineas().stream()
                    .map(DocumentoLinea::getProductoId)
                    .distinct()
                    .toList());
}
```

An `ALBARAN_COMPRA` must require a supplier when confirmed, matching purchase
invoice behavior.

- [ ] **Step 6: Run focused tests**

```powershell
cd backend
.\mvnw.cmd -Dtest=ProductSupplierServiceTest,DocumentServiceTest test
```

Expected: all selected tests PASS and no unexpected Mockito interactions.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/document/ConfirmedPurchaseRecorder.java backend/src/main/java/com/tpverp/backend/catalog/ProductSupplierService.java backend/src/main/java/com/tpverp/backend/document/DocumentService.java backend/src/test/java/com/tpverp/backend/catalog/ProductSupplierServiceTest.java backend/src/test/java/com/tpverp/backend/document/DocumentServiceTest.java
git commit -m "feat: update product suppliers from purchases"
```

### Task 5: Full Verification And Refactoring

**Files:**
- Modify only files from Tasks 1-4 when a verified issue requires it.

- [ ] **Step 1: Run all backend unit tests**

```powershell
cd backend
.\mvnw.cmd test
```

Expected: BUILD SUCCESS with zero failures and zero errors.

- [ ] **Step 2: Run PostgreSQL integration verification**

```powershell
$env:TPV_ERP_TEST_DB_URL='jdbc:postgresql://localhost:5432/tpv_erp_test'
$env:TPV_ERP_TEST_DB_USER='postgres'
$env:TPV_ERP_TEST_DB_PASSWORD='admin'
cd backend
.\mvnw.cmd verify
```

Expected: BUILD SUCCESS. Migration V5 applies to an empty schema and all
integration-tagged tests pass.

- [ ] **Step 3: Review comments and class boundaries**

Check that:

- `CatalogService` did not gain supplier-link behavior.
- Public orchestration and non-obvious date logic have concise `//` comments.
- Trivial getters, constructors and repository methods have no comments.
- No class mixes controller, persistence and document orchestration concerns.

- [ ] **Step 4: Check formatting and accidental changes**

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors. Existing unrelated worktree changes remain
untouched.

- [ ] **Step 5: Commit verified cleanup if necessary**

Only when Step 3 required changes:

```powershell
git add backend/src/main backend/src/test
git commit -m "refactor: tighten product supplier workflow"
```

If no cleanup was required, do not create an empty commit.
