# Pending Sale Tax Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose an authoritative fiscal product catalog to APP VENTA so customer-pending invoices and delivery notes quote with the configured tax percentage and IVA/IGIC regime.

**Architecture:** Add a sale-specific backend view and assembler that joins products with `StoreTax` and the active store license, exposed at `/api/v1/products/sale`. APP VENTA consumes that endpoint and refuses to build a pending document from incomplete fiscal data instead of inventing `0.00` and `GENERAL`.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, JUnit 5, Mockito, React, TypeScript, Vitest.

## Global Constraints

- Preserve the authoritative tax validation in `PromotionCatalogGateway`.
- Resolve `taxPercentage` from the product's `taxId` and `taxRegime` from the active license.
- Never silently substitute `0.00` or `GENERAL` for missing fiscal data.
- Do not change member-discount, checkout, or customer-debt rules.
- Preserve unrelated local migration and payment-session changes already present in the worktree.

---

### Task 1: Authoritative backend sale catalog

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/catalog/SaleProductView.java`
- Create: `backend/src/main/java/com/tpverp/backend/catalog/SaleProductCatalogService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/catalog/ProductController.java`
- Create: `backend/src/test/java/com/tpverp/backend/catalog/SaleProductCatalogServiceTest.java`
- Modify: `backend/src/test/java/com/tpverp/backend/catalog/ProductControllerContractTest.java`

**Interfaces:**
- Consumes: `CatalogService.products()`, `StoreTaxRepository.findAllById(...)`, `LicenseRepository.findByTiendaIdOrderByValidaDesdeDesc(UUID)` and `CurrentOrganization.currentStore()`.
- Produces: `List<SaleProductView> SaleProductCatalogService.products()` and `GET /api/v1/products/sale`.

- [ ] **Step 1: Write the failing service test**

Create a test whose product references `taxId`, whose `StoreTax` has `21.00`, and whose newest active license has `TaxRegime.IVA`:

```java
@Test
void exposesAuthoritativeTaxSnapshotForSaleProducts() {
    when(organization.currentStore()).thenReturn(store);
    when(catalog.products()).thenReturn(List.of(product));
    when(taxes.findAllById(List.of(taxId))).thenReturn(List.of(tax));
    when(licenses.findByTiendaIdOrderByValidaDesdeDesc(storeId)).thenReturn(List.of(license));
    when(license.isActiva()).thenReturn(true);
    when(license.getRegimenImpuesto()).thenReturn(TaxRegime.IVA);

    var result = service.products();

    assertThat(result).singleElement().satisfies(view -> {
        assertThat(view.taxPercentage()).isEqualByComparingTo("21.00");
        assertThat(view.taxRegime()).isEqualTo("IVA");
        assertThat(view.taxesIncluded()).isTrue();
    });
}
```

- [ ] **Step 2: Run the service test and verify RED**

Run `cd backend; mvn.cmd -Dtest=SaleProductCatalogServiceTest test`.

Expected: compilation failure because `SaleProductCatalogService` and `SaleProductView` do not exist.

- [ ] **Step 3: Implement the minimal sale view and assembler**

Define the sale contract with all fields consumed by `SaleProduct` plus the fiscal snapshot:

```java
public record SaleProductView(
        UUID id, boolean active, String code, String barcode, String barcode2,
        String name, BigDecimal salePrice, BigDecimal memberPrice,
        BigDecimal offerPrice, BigDecimal offerDiscountPercent,
        PriceUseMode priceUseMode, DiscountType discountType,
        boolean offerActive, LocalDate offerFrom, LocalDate offerUntil,
        boolean taxesIncluded, UUID taxId, BigDecimal taxPercentage,
        String taxRegime) {
}
```

Implement `SaleProductCatalogService.products()` so it reads the current store and products once, batch-loads taxes with `findAllById`, selects the newest active license, rejects missing or wrong-store data, and maps `tax.getPercentage()` plus `license.getRegimenImpuesto().name()`.

- [ ] **Step 4: Run the service test and verify GREEN**

Run the command from Step 2. Expected: `BUILD SUCCESS`, one passing test.

- [ ] **Step 5: Write the failing controller contract test**

Mock `SaleProductCatalogService.products()` with an IVA 21 % view and assert:

```java
mvc.perform(get("/api/v1/products/sale")
        .with(user("seller").authorities(() -> VENTA)))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$[0].taxPercentage").value(21.00))
    .andExpect(jsonPath("$[0].taxRegime").value("IVA"));
```

- [ ] **Step 6: Run the controller test and verify RED**

Run `cd backend; mvn.cmd -Dtest=ProductControllerContractTest test`.

Expected: `/api/v1/products/sale` is not served successfully.

- [ ] **Step 7: Add the sale endpoint**

Inject `SaleProductCatalogService saleCatalog` into `ProductController` and add:

```java
@GetMapping("/sale")
@PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('PRODUCTS_READ','GESTION_VENTAS','VENTA')")
public List<SaleProductView> saleList() {
    return saleCatalog.products();
}
```

Update the MVC slice with `@MockitoBean SaleProductCatalogService saleCatalog`.

- [ ] **Step 8: Run the focused backend tests**

Run `cd backend; mvn.cmd -Dtest=SaleProductCatalogServiceTest,ProductControllerContractTest test`.

Expected: all focused tests pass with `BUILD SUCCESS`.

### Task 2: Consume the authoritative sale catalog and fail closed

**Files:**
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`

**Interfaces:**
- Consumes: `GET /products/sale` returning `SaleProduct[]` with required `taxId`, `taxPercentage`, and `taxRegime`.
- Produces: `pendingSaleDraftForCustomer(...)` that either returns a fiscally complete draft or throws a visible catalog error.

- [ ] **Step 1: Write the failing catalog endpoint test**

In the existing `SaleScreen` API mock, return a product with `taxId`, `taxPercentage: 21`, and `taxRegime: "IVA"` only for `/products/sale`, render the screen, and assert:

```ts
expect(apiPaths).toContain("/products/sale");
expect(apiPaths).not.toContain("/products");
```

- [ ] **Step 2: Run the focused frontend test and verify RED**

Run `cd frontend; npm.cmd test -- --run packages/app-common/src/components/SaleScreen.test.tsx`.

Expected: failure because `SaleScreen` still requests `/products`.

- [ ] **Step 3: Switch APP VENTA to the sale endpoint**

Make these `SaleProduct` properties required:

```ts
taxId: string;
taxesIncluded: boolean;
taxRegime: "IVA" | "IGIC";
taxPercentage: number | string;
```

Change the request to `apiRequest<SaleProduct[]>("/products/sale", { token: session.accessToken })`.

- [ ] **Step 4: Run the endpoint test and verify GREEN**

Run the command from Step 2. Expected: the endpoint assertion passes.

- [ ] **Step 5: Write failing draft validation tests**

```ts
expect(pendingSaleDraftForCustomer(validLines, customer, "warehouse-1", now, "checkout-1")
  .lines[0]).toMatchObject({ taxPercentage: "21.00", taxRegime: "IVA" });

expect(() => pendingSaleDraftForCustomer(
  [{ ...validLines[0], product: { ...validLines[0].product, taxPercentage: undefined as never } }],
  customer, "warehouse-1", now, "checkout-1",
)).toThrow("Producto sin porcentaje fiscal v谩lido");

expect(() => pendingSaleDraftForCustomer(
  [{ ...validLines[0], product: { ...validLines[0].product, taxRegime: "GENERAL" as never } }],
  customer, "warehouse-1", now, "checkout-1",
)).toThrow("Producto sin r茅gimen fiscal v谩lido");
```

- [ ] **Step 6: Run the draft tests and verify RED**

Run the focused frontend command. Expected: invalid products still produce `0.00` or `GENERAL` instead of throwing.

- [ ] **Step 7: Add fail-closed fiscal normalization**

```ts
export function saleProductFiscalSnapshot(product: SaleProduct) {
  const percentage = Number(product.taxPercentage);
  if (!Number.isFinite(percentage) || percentage < 0 || percentage > 100) {
    throw new Error("Producto sin porcentaje fiscal v谩lido");
  }
  if (product.taxRegime !== "IVA" && product.taxRegime !== "IGIC") {
    throw new Error("Producto sin r茅gimen fiscal v谩lido");
  }
  return {
    taxesIncluded: product.taxesIncluded,
    taxPercentage: percentage.toFixed(2),
    taxRegime: product.taxRegime,
  };
}
```

Spread this result into every pending draft line and remove `?? 0` and `?? "GENERAL"`.

- [ ] **Step 8: Show the validation error in the pending-sale flow**

Wrap draft creation in `openCustomerPendingSale`, set the existing pending-sale error state to the thrown message, and do not open `CustomerPendingSaleDialog` when no valid draft exists.

- [ ] **Step 9: Run the complete SaleScreen test file**

Run the command from Step 2. Expected: all tests in `SaleScreen.test.tsx` pass.

### Task 3: Regression verification

**Files:**
- Modify only if a regression is exposed: files already listed in Tasks 1-2.

**Interfaces:**
- Consumes: backend `/products/sale` and frontend fiscal snapshot mapping.
- Produces: verified customer-pending quote flow for IVA/IGIC without weakening server validation.

- [ ] **Step 1: Run relevant backend tests**

Run `cd backend; mvn.cmd -Dtest=SaleProductCatalogServiceTest,ProductControllerContractTest,DocumentServiceTest,PromotionCatalogGatewayTest test`.

Expected: `BUILD SUCCESS` and no tax-snapshot validation regression.

- [ ] **Step 2: Run the full frontend unit suite**

Run `cd frontend; npm.cmd test -- --run`.

Expected: all tests pass.

- [ ] **Step 3: Build both applications**

Run:

```powershell
cd frontend
npm.cmd run build --workspace @tpverp/app-venta
npm.cmd run build --workspace @tpverp/app-gestion
```

Expected: both Vite/TypeScript builds complete successfully.

- [ ] **Step 4: Review the working tree**

Run:

```powershell
cd E:\workspace\gitwork\TPV_ERP
git diff --check
git status --short
```

Expected: no whitespace errors; only the intended fiscal-contract changes plus the pre-existing unrelated local changes are present.

