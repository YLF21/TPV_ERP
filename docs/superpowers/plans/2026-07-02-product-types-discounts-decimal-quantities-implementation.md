# Product Types, Discount Types, And Decimal Quantities Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add product type, discount type, product comments, decimal document quantities, and decimal stock support.

**Architecture:** Extend the existing catalog/document/inventory model with minimal new enums and one Flyway migration. Keep pricing automatic behavior in the frontend for now; backend only validates quantity rules and stock movement rules.

**Tech Stack:** Java 25, Spring Boot, JPA, Flyway, PostgreSQL, JUnit.

---

### Task 1: Product Fields

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/catalog/ProductType.java`
- Create: `backend/src/main/java/com/tpverp/backend/catalog/DiscountType.java`
- Create: `backend/src/main/resources/db/migration/V29__tipos_producto_cantidades_decimales.sql`
- Modify: `backend/src/main/java/com/tpverp/backend/catalog/Product.java`
- Modify: `backend/src/main/java/com/tpverp/backend/catalog/CatalogService.java`
- Test: `backend/src/test/java/com/tpverp/backend/catalog/CatalogServiceTest.java`

- [ ] Add `ProductType` enum with `UNIT`, `SERVICE`, `WEIGHT`.
- [ ] Add `DiscountType` enum with `NONE`, `NORMAL`, `MEMBER_DISCOUNT`, `MEMBER_PRICE`, `DISCOUNT_PRICE`.
- [ ] Add columns `product_type`, `discount_type`, `comments` to `producto`.
- [ ] Default existing products to `UNIT`, `NORMAL`, `null`.
- [ ] Add fields to `Product` and `CatalogService.ProductRequest`.
- [ ] Validate `DISCOUNT_PRICE` requires `offerPrice`, `offerActive=true`, `offerFrom`.
- [ ] Run `.\mvnw.cmd "-Dtest=CatalogServiceTest" test`.
- [ ] Commit: `feat: add product type and discount type`.

### Task 2: Decimal Document Quantities

**Files:**
- Modify: `backend/src/main/resources/db/migration/V29__tipos_producto_cantidades_decimales.sql`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentLine.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentLineCommand.java`
- Modify: document tests using integer quantities.
- Test: `backend/src/test/java/com/tpverp/backend/document/DocumentRulesTest.java`
- Test: `backend/src/test/java/com/tpverp/backend/document/DocumentServiceTest.java`

- [ ] Change `documento_linea.cantidad` to numeric scale 3.
- [ ] Change line quantity Java type to `BigDecimal`.
- [ ] Keep amount calculations using quantity decimal.
- [ ] Preserve compatibility in tests by using `BigDecimal.ONE` or helper methods.
- [ ] Run `.\mvnw.cmd "-Dtest=DocumentRulesTest,DocumentServiceTest" test`.
- [ ] Commit: `feat: support decimal document quantities`.

### Task 3: Quantity Validation By Product Type

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/catalog/ProductRepository.java`
- Test: `backend/src/test/java/com/tpverp/backend/document/DocumentServiceTest.java`

- [ ] Resolve products used by document lines when creating/updating documents.
- [ ] Reject decimal quantities for `UNIT`.
- [ ] Allow scale up to 3 for `SERVICE` and `WEIGHT`.
- [ ] Keep negative quantities allowed where current document flow allows them.
- [ ] Run `.\mvnw.cmd "-Dtest=DocumentServiceTest" test`.
- [ ] Commit: `feat: validate document quantities by product type`.

### Task 4: Decimal Stock And Service Exclusion

**Files:**
- Modify: `backend/src/main/resources/db/migration/V29__tipos_producto_cantidades_decimales.sql`
- Modify: `backend/src/main/java/com/tpverp/backend/inventory/StockLevel.java`
- Modify: `backend/src/main/java/com/tpverp/backend/inventory/StockMovement.java`
- Modify: `backend/src/main/java/com/tpverp/backend/inventory/InventoryDocumentGateway.java`
- Modify: inventory tests.
- Test: `backend/src/test/java/com/tpverp/backend/inventory/InventoryDocumentGatewayTest.java`
- Test: `backend/src/test/java/com/tpverp/backend/inventory/InventoryServiceTest.java`

- [ ] Change stock quantities and movement quantities to decimal scale 3.
- [ ] Skip stock movement for lines whose product is `SERVICE`.
- [ ] Preserve integer checks for `UNIT`.
- [ ] Allow decimal stock for `WEIGHT`.
- [ ] Run `.\mvnw.cmd "-Dtest=InventoryDocumentGatewayTest,InventoryServiceTest" test`.
- [ ] Commit: `feat: support decimal stock quantities`.

### Task 5: Excel Import Compatibility

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/excel/ProductImportService.java`
- Modify: Excel import tests.
- Test: `backend/src/test/java/com/tpverp/backend/excel/ProductImportServiceTest.java`

- [ ] Read imported quantities as decimals with scale 3.
- [ ] Create imported products as `UNIT` and `NORMAL` by default.
- [ ] Do not add new Excel mapping fields in this block.
- [ ] Run `.\mvnw.cmd "-Dtest=ProductImportServiceTest" test`.
- [ ] Commit: `feat: align excel import with product types`.

### Task 6: Final Verification

- [ ] Run `.\mvnw.cmd "-Dtest=CatalogServiceTest,DocumentRulesTest,DocumentServiceTest,InventoryDocumentGatewayTest,InventoryServiceTest,ProductImportServiceTest,TpvErpBackendApplicationTests" test`.
- [ ] Check `git status --short --branch`.
- [ ] Merge only after tests pass.
