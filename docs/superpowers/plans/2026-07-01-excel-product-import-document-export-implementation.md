# Excel Product Import And Document Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add flexible Excel product import with preview/confirm and visible `.xlsx` export for tickets, invoices, and delivery notes.

**Architecture:** Keep Excel code isolated in `com.tpverp.backend.excel`. Import preview parses `.xls/.xlsx` into row decisions without writing. Confirm re-parses the uploaded file, applies product changes transactionally, and creates a purchase delivery note or purchase invoice draft through existing document commands.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Maven, Apache POI, JPA, PostgreSQL, existing document/catalog services.

---

## File Structure

- Modify `backend/pom.xml`: add Apache POI for `.xls` and `.xlsx`.
- Create `backend/src/main/java/com/tpverp/backend/excel/ExcelColumn.java`: convert Excel letters like `A`, `AB` to zero-based indexes.
- Create `backend/src/main/java/com/tpverp/backend/excel/ExcelCellReader.java`: safely read strings, numbers, dates and money from POI cells.
- Create `backend/src/main/java/com/tpverp/backend/excel/ProductImportMapping.java`: request mapping and update flags.
- Create `backend/src/main/java/com/tpverp/backend/excel/ProductImportPreviewRow.java`: row status, errors, and product changes.
- Create `backend/src/main/java/com/tpverp/backend/excel/ProductImportService.java`: preview and confirm logic.
- Create `backend/src/main/java/com/tpverp/backend/excel/ProductImportController.java`: `/api/v1/excel/product-import/preview` and `/confirm`.
- Create `backend/src/main/java/com/tpverp/backend/excel/DocumentExcelExportService.java`: write visible document `.xlsx`.
- Create `backend/src/main/java/com/tpverp/backend/excel/DocumentExcelExportController.java`: `/api/v1/excel/documents/{id}/export` and batch export.
- Modify `backend/src/main/java/com/tpverp/backend/catalog/CatalogService.java`: expose focused helpers for import if direct repository use would duplicate validation.
- Modify `backend/src/main/java/com/tpverp/backend/catalog/ProductSupplierService.java`: add reference-aware purchase upsert used on document confirmation.
- Create `backend/src/main/resources/db/migration/V24__metadatos_importacion_excel.sql`: persist supplier references from import draft until purchase confirmation.
- Create `backend/src/main/java/com/tpverp/backend/excel/ProductImportLineMetadata.java`: imported supplier reference per draft document/product.
- Create `backend/src/main/java/com/tpverp/backend/excel/ProductImportLineMetadataRepository.java`: read/delete metadata by document.
- Test under `backend/src/test/java/com/tpverp/backend/excel/`.

---

### Task 1: Apache POI Dependency And Column Utility

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/tpverp/backend/excel/ExcelColumn.java`
- Test: `backend/src/test/java/com/tpverp/backend/excel/ExcelColumnTest.java`

- [ ] **Step 1: Add failing column tests**

```java
package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class ExcelColumnTest {

    @Test
    void convertsLettersToZeroBasedIndex() {
        assertThat(ExcelColumn.index("A")).isZero();
        assertThat(ExcelColumn.index("B")).isEqualTo(1);
        assertThat(ExcelColumn.index("AA")).isEqualTo(26);
    }

    @Test
    void rejectsInvalidColumn() {
        assertThatThrownBy(() -> ExcelColumn.index("1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("columna");
    }
}
```

- [ ] **Step 2: Run failing test**

Run: `.\mvnw.cmd "-Dtest=ExcelColumnTest" test` from `backend/`.

Expected: compilation fails because `ExcelColumn` does not exist.

- [ ] **Step 3: Add POI dependency**

Add this dependency to `backend/pom.xml`:

```xml
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.4.1</version>
</dependency>
```

- [ ] **Step 4: Implement column utility**

```java
package com.tpverp.backend.excel;

final class ExcelColumn {

    private ExcelColumn() {
    }

    static int index(String letters) {
        if (letters == null || letters.isBlank()) {
            throw new IllegalArgumentException("columna obligatoria");
        }
        int result = 0;
        for (char value : letters.trim().toUpperCase().toCharArray()) {
            if (value < 'A' || value > 'Z') {
                throw new IllegalArgumentException("columna no valida");
            }
            result = result * 26 + (value - 'A' + 1);
        }
        return result - 1;
    }
}
```

- [ ] **Step 5: Verify and commit**

Run: `.\mvnw.cmd "-Dtest=ExcelColumnTest" test`.

Expected: `Tests run: 2, Failures: 0, Errors: 0`.

Commit:

```powershell
git add backend/pom.xml backend/src/main/java/com/tpverp/backend/excel/ExcelColumn.java backend/src/test/java/com/tpverp/backend/excel/ExcelColumnTest.java
git commit -m "feat: add excel column mapping"
```

---

### Task 2: Excel Cell Reader

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/excel/ExcelCellReader.java`
- Test: `backend/src/test/java/com/tpverp/backend/excel/ExcelCellReaderTest.java`

- [ ] **Step 1: Add failing tests**

```java
package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExcelCellReaderTest {

    @Test
    void readsTextAndMoney() {
        var workbook = new XSSFWorkbook();
        var row = workbook.createSheet().createRow(0);
        row.createCell(0).setCellValue(" ABC ");
        row.createCell(1).setCellValue(12.345);

        assertThat(ExcelCellReader.text(row, "A")).isEqualTo("ABC");
        assertThat(ExcelCellReader.money(row, "B")).isEqualByComparingTo("12.35");
    }

    @Test
    void emptyColumnReturnsNull() {
        var workbook = new XSSFWorkbook();
        var row = workbook.createSheet().createRow(0);

        assertThat(ExcelCellReader.text(row, null)).isNull();
        assertThat(ExcelCellReader.money(row, "")).isNull();
    }
}
```

- [ ] **Step 2: Run failing test**

Run: `.\mvnw.cmd "-Dtest=ExcelCellReaderTest" test`.

Expected: compilation fails because `ExcelCellReader` does not exist.

- [ ] **Step 3: Implement reader**

```java
package com.tpverp.backend.excel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;

final class ExcelCellReader {

    private static final DataFormatter FORMATTER = new DataFormatter();

    private ExcelCellReader() {
    }

    static String text(Row row, String column) {
        var cell = cell(row, column);
        if (cell == null) {
            return null;
        }
        var value = FORMATTER.formatCellValue(cell).trim();
        return value.isBlank() ? null : value;
    }

    static BigDecimal money(Row row, String column) {
        var value = text(row, column);
        if (value == null) {
            return null;
        }
        return new BigDecimal(value.replace("%", "").replace(",", "."))
                .setScale(2, RoundingMode.HALF_UP);
    }

    static Integer integer(Row row, String column) {
        var value = text(row, column);
        return value == null ? null : new BigDecimal(value.replace(",", ".")).intValueExact();
    }

    private static Cell cell(Row row, String column) {
        if (row == null || column == null || column.isBlank()) {
            return null;
        }
        return row.getCell(ExcelColumn.index(column));
    }
}
```

- [ ] **Step 4: Verify and commit**

Run: `.\mvnw.cmd "-Dtest=ExcelCellReaderTest" test`.

Expected: pass.

Commit:

```powershell
git add backend/src/main/java/com/tpverp/backend/excel/ExcelCellReader.java backend/src/test/java/com/tpverp/backend/excel/ExcelCellReaderTest.java
git commit -m "feat: add excel cell reader"
```

---

### Task 3: Product Import Preview

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/excel/ProductImportMapping.java`
- Create: `backend/src/main/java/com/tpverp/backend/excel/ProductImportPreviewRow.java`
- Create: `backend/src/main/java/com/tpverp/backend/excel/ProductImportService.java`
- Test: `backend/src/test/java/com/tpverp/backend/excel/ProductImportServiceTest.java`

- [ ] **Step 1: Add preview test**

Create a unit test that builds an in-memory workbook with:

```text
A codigo, B codigoBarras, C nombre, D coste, E venta, F cantidad
ABC, 123, Producto Excel, 10.00, 15.00, 2
```

The test should mock repositories so no existing product is found and assert:

```java
assertThat(result.rows()).hasSize(1);
assertThat(result.rows().getFirst().status()).isEqualTo(ProductImportPreviewRow.Status.NEW_PRODUCT);
assertThat(result.rows().getFirst().errors()).isEmpty();
```

- [ ] **Step 2: Run failing test**

Run: `.\mvnw.cmd "-Dtest=ProductImportServiceTest" test`.

Expected: compilation fails because import types do not exist.

- [ ] **Step 3: Implement request and row records**

```java
public record ProductImportMapping(
        String codigo,
        String codigoBarras,
        String nombre,
        String descripcion,
        String precioCompra,
        String precioVenta,
        String precioMayorista,
        String precioMiembro,
        String impuesto,
        String cantidad,
        String referenciaProveedor,
        int startRow,
        boolean updateName,
        boolean updateDescription,
        boolean updatePurchasePrice,
        boolean updateSalePrice,
        boolean updateWholesalePrice,
        boolean updateMemberPrice) {

    public int firstRowIndex() {
        return Math.max(startRow, 1) - 1;
    }
}
```

```java
public record ProductImportPreviewRow(
        int rowNumber,
        Status status,
        UUID productId,
        List<String> errors,
        List<ProductChange> changes) {

    enum Status {
        NEW_PRODUCT,
        UPDATE_PRODUCT,
        PRODUCT_ONLY,
        SKIPPED,
        ERROR
    }

    public record ProductChange(String campo, String valorActual, String valorExcel) {
    }
}
```

- [ ] **Step 4: Implement preview only**

`ProductImportService.preview(InputStream input, ProductImportMapping mapping)` must:

- Open workbook with `WorkbookFactory.create(input)`.
- Read the first sheet.
- Start from `mapping.firstRowIndex()`.
- Resolve product by codigo or codigoBarras through `ProductIdentifierRepository.findByStoreIdAndValor`.
- Build `NEW_PRODUCT`, `UPDATE_PRODUCT`, `PRODUCT_ONLY`, `SKIPPED`, or `ERROR`.
- Validate new products require codigo or codigoBarras, nombre, and precioCompra.
- Parse impuesto percentage if present; otherwise allow default tax resolution in confirm.
- Do not write to repositories.

- [ ] **Step 5: Verify and commit**

Run: `.\mvnw.cmd "-Dtest=ProductImportServiceTest" test`.

Expected: preview test passes.

Commit:

```powershell
git add backend/src/main/java/com/tpverp/backend/excel backend/src/test/java/com/tpverp/backend/excel/ProductImportServiceTest.java
git commit -m "feat: preview excel product import"
```

---

### Task 4: Product Import Confirm Creates Draft Purchase Document

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/excel/ProductImportService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/catalog/CatalogService.java`
- Test: `backend/src/test/java/com/tpverp/backend/excel/ProductImportServiceTest.java`

- [ ] **Step 1: Add confirm tests**

Add tests for:

- new product with quantity creates product and one draft line
- new product without quantity creates product and no draft line
- existing product updates only fields enabled by mapping

Assert document type is `ALBARAN_COMPRA` or `FACTURA_COMPRA`, status is `BORRADOR`, and no stock is moved.

- [ ] **Step 2: Run failing tests**

Run: `.\mvnw.cmd "-Dtest=ProductImportServiceTest" test`.

Expected: fail because confirm is not implemented.

- [ ] **Step 3: Add catalog helper**

Add a public method to `CatalogService`:

```java
@Transactional
public Product createOrUpdateFromImport(ProductRequest request, UUID existingProductId) {
    return existingProductId == null
            ? createProduct(request)
            : updateProduct(existingProductId, request);
}
```

- [ ] **Step 4: Implement confirm**

`ProductImportService.confirm(...)` should:

- Re-run preview inside a transaction.
- Reject if any row status is `ERROR`.
- Create or update products via `CatalogService.createOrUpdateFromImport`.
- Build `DocumentLineCommand` only for rows with positive quantity.
- Create `DocumentCommand` with selected supplier, warehouse, date, external number, zero global discount, `directo=false`.
- Call `DocumentService.createDeliveryNote` for `ALBARAN_COMPRA` or `DocumentService.createInvoice` for `FACTURA_COMPRA`.

- [ ] **Step 5: Verify and commit**

Run: `.\mvnw.cmd "-Dtest=ProductImportServiceTest,CatalogServiceTest" test`.

Expected: pass.

Commit:

```powershell
git add backend/src/main/java/com/tpverp/backend/excel backend/src/main/java/com/tpverp/backend/catalog/CatalogService.java backend/src/test/java/com/tpverp/backend/excel/ProductImportServiceTest.java
git commit -m "feat: confirm excel product import"
```

---

### Task 5: HTTP Import Endpoints

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/excel/ProductImportController.java`
- Test: `backend/src/test/java/com/tpverp/backend/excel/ProductImportControllerContractTest.java`

- [ ] **Step 1: Add controller contract test**

Use MockMvc to assert:

- `POST /api/v1/excel/product-import/preview` exists.
- `POST /api/v1/excel/product-import/confirm` exists.
- Both accept multipart file plus JSON mapping/request part.

- [ ] **Step 2: Run failing test**

Run: `.\mvnw.cmd "-Dtest=ProductImportControllerContractTest" test`.

Expected: 404 or compilation failure.

- [ ] **Step 3: Implement controller**

Create controller with:

```java
@RestController
@RequestMapping("/api/v1/excel/product-import")
class ProductImportController {

    @PostMapping("/preview")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_PRODUCTO','PRODUCTS_WRITE')")
    ProductImportPreview preview(@RequestPart MultipartFile file,
            @RequestPart ProductImportMapping mapping) throws IOException {
        return service.preview(file.getInputStream(), mapping);
    }

    @PostMapping("/confirm")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_PRODUCTO','PRODUCTS_WRITE')")
    DocumentView confirm(@RequestPart MultipartFile file,
            @RequestPart ProductImportConfirmRequest request,
            Authentication authentication) throws IOException {
        return DocumentView.from(service.confirm(file.getInputStream(), request, authentication));
    }
}
```

- [ ] **Step 4: Verify and commit**

Run: `.\mvnw.cmd "-Dtest=ProductImportControllerContractTest,ProductImportServiceTest" test`.

Expected: pass.

Commit:

```powershell
git add backend/src/main/java/com/tpverp/backend/excel/ProductImportController.java backend/src/test/java/com/tpverp/backend/excel/ProductImportControllerContractTest.java
git commit -m "feat: expose excel product import api"
```

---

### Task 6: Persist Import Supplier References Until Confirmation

**Files:**
- Create: `backend/src/main/resources/db/migration/V24__metadatos_importacion_excel.sql`
- Create: `backend/src/main/java/com/tpverp/backend/excel/ProductImportLineMetadata.java`
- Create: `backend/src/main/java/com/tpverp/backend/excel/ProductImportLineMetadataRepository.java`
- Modify: `backend/src/main/java/com/tpverp/backend/excel/ProductImportService.java`
- Test: `backend/src/test/java/com/tpverp/backend/excel/ProductImportMetadataTest.java`

- [ ] **Step 1: Add failing migration/entity test**

Create a test that saves metadata for a draft document/product and reads it by document id:

```java
var metadata = new ProductImportLineMetadata(documentId, productId, "REF-1");
repository.save(metadata);

assertThat(repository.findByDocumentId(documentId))
        .extracting(ProductImportLineMetadata::supplierReference)
        .containsExactly("REF-1");
```

- [ ] **Step 2: Run failing test**

Run: `.\mvnw.cmd "-Dtest=ProductImportMetadataTest" test`.

Expected: compilation failure because metadata entity does not exist.

- [ ] **Step 3: Add migration**

```sql
create table producto_importacion_excel_linea (
    id uuid primary key,
    documento_id uuid not null references documento(id) on delete cascade,
    producto_id uuid not null references producto(id) on delete cascade,
    referencia_proveedor varchar(128),
    version bigint not null default 0,
    unique (documento_id, producto_id)
);

create index ix_producto_importacion_excel_linea_documento
    on producto_importacion_excel_linea(documento_id);
```

- [ ] **Step 4: Add entity and repository**

```java
@Entity
@Table(name = "producto_importacion_excel_linea")
public class ProductImportLineMetadata {
    @Id
    private UUID id = UUID.randomUUID();
    @Column(name = "documento_id", nullable = false)
    private UUID documentId;
    @Column(name = "producto_id", nullable = false)
    private UUID productId;
    @Column(name = "referencia_proveedor", length = 128)
    private String supplierReference;
    @Version
    private long version;

    protected ProductImportLineMetadata() {
    }

    public ProductImportLineMetadata(UUID documentId, UUID productId, String supplierReference) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.productId = Objects.requireNonNull(productId, "productId");
        this.supplierReference = supplierReference == null || supplierReference.isBlank()
                ? null : supplierReference.trim().toUpperCase(Locale.ROOT);
    }

    public UUID productId() {
        return productId;
    }

    public String supplierReference() {
        return supplierReference;
    }
}
```

```java
public interface ProductImportLineMetadataRepository
        extends JpaRepository<ProductImportLineMetadata, UUID> {

    List<ProductImportLineMetadata> findByDocumentId(UUID documentId);

    void deleteByDocumentId(UUID documentId);
}
```

- [ ] **Step 5: Save metadata in import confirm**

After `ProductImportService.confirm` creates the draft document, save one metadata row for each imported line with `referenciaProveedor`.

- [ ] **Step 6: Verify and commit**

Run: `.\mvnw.cmd "-Dtest=ProductImportMetadataTest,ProductImportServiceTest" test`.

Expected: pass.

Commit:

```powershell
git add backend/src/main/resources/db/migration/V24__metadatos_importacion_excel.sql backend/src/main/java/com/tpverp/backend/excel backend/src/test/java/com/tpverp/backend/excel
git commit -m "feat: persist excel import metadata"
```

---

### Task 7: Reference-Aware Product Supplier Update On Confirmation

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/catalog/ProductSupplierRepository.java`
- Modify: `backend/src/main/java/com/tpverp/backend/catalog/ProductSupplierService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/excel/ProductImportLineMetadataRepository.java`
- Test: `backend/src/test/java/com/tpverp/backend/catalog/ProductSupplierServiceTest.java`
- Test: `backend/src/test/java/com/tpverp/backend/document/DocumentServiceTest.java`

- [ ] **Step 1: Add repository/service test**

Assert that confirming a purchase can upsert supplier link with reference `REF-1` and last entry date.

- [ ] **Step 2: Run failing test**

Run: `.\mvnw.cmd "-Dtest=ProductSupplierServiceTest" test`.

Expected: fail because reference-aware upsert does not exist.

- [ ] **Step 3: Add repository method**

Add native upsert that sets `referencia_proveedor = coalesce(:reference, current_link.referencia_proveedor)`.

- [ ] **Step 4: Add service method**

Add:

```java
@Transactional
public void recordWithReferences(UUID supplierId, LocalDate date, Map<UUID, String> referencesByProductId)
```

It validates supplier and product store ownership like existing `record(...)`, then calls the reference-aware upsert.

- [ ] **Step 5: Wire document confirmation**

In `DocumentService.confirm`, after `purchaseRecorder.record(...)`, read `ProductImportLineMetadataRepository.findByDocumentId(document.getId())`.

If metadata exists and document type is `ALBARAN_COMPRA` or `FACTURA_COMPRA`, call `ProductSupplierService.recordWithReferences(...)` with product ids and references, then delete metadata for that document. Keep existing `purchaseRecorder.record(...)` for normal purchases without import metadata.

- [ ] **Step 6: Verify and commit**

Run: `.\mvnw.cmd "-Dtest=ProductSupplierServiceTest" test`.

Expected: pass.

Commit:

```powershell
git add backend/src/main/java/com/tpverp/backend/catalog/ProductSupplierRepository.java backend/src/main/java/com/tpverp/backend/catalog/ProductSupplierService.java backend/src/main/java/com/tpverp/backend/document/DocumentService.java backend/src/main/java/com/tpverp/backend/excel/ProductImportLineMetadataRepository.java backend/src/test/java/com/tpverp/backend/catalog/ProductSupplierServiceTest.java backend/src/test/java/com/tpverp/backend/document/DocumentServiceTest.java
git commit -m "feat: update supplier references on purchases"
```

---

### Task 8: Document Excel Export

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/excel/DocumentExcelExportService.java`
- Create: `backend/src/main/java/com/tpverp/backend/excel/DocumentExcelExportController.java`
- Test: `backend/src/test/java/com/tpverp/backend/excel/DocumentExcelExportServiceTest.java`
- Test: `backend/src/test/java/com/tpverp/backend/excel/DocumentExcelExportControllerContractTest.java`

- [ ] **Step 1: Add export service test**

Create a `CommercialDocument` with one line and assert generated workbook contains:

- document type and number
- date
- product code/name
- quantity, price, tax, total
- no UUID text

- [ ] **Step 2: Run failing test**

Run: `.\mvnw.cmd "-Dtest=DocumentExcelExportServiceTest" test`.

Expected: compilation failure.

- [ ] **Step 3: Implement exporter**

Use `XSSFWorkbook`, one sheet per document. Keep it visual:

- title row
- header rows
- line table
- totals section

Return `byte[]`.

- [ ] **Step 4: Implement controller**

Endpoints:

- `GET /api/v1/excel/documents/{documentId}/export`
- `POST /api/v1/excel/documents/export`

Return `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`.

- [ ] **Step 5: Verify and commit**

Run: `.\mvnw.cmd "-Dtest=DocumentExcelExportServiceTest,DocumentExcelExportControllerContractTest" test`.

Expected: pass.

Commit:

```powershell
git add backend/src/main/java/com/tpverp/backend/excel/DocumentExcelExportService.java backend/src/main/java/com/tpverp/backend/excel/DocumentExcelExportController.java backend/src/test/java/com/tpverp/backend/excel
git commit -m "feat: export documents to excel"
```

---

### Task 9: Focused Integration Verification

**Files:**
- Modify tests only if a real integration gap appears.

- [ ] **Step 1: Run focused suite**

Run:

```powershell
$env:TPV_TEST_DB_USERNAME='postgres'
$env:TPV_TEST_DB_PASSWORD='admin'
.\mvnw.cmd "-Dtest=ExcelColumnTest,ExcelCellReaderTest,ProductImportServiceTest,ProductImportControllerContractTest,ProductImportMetadataTest,DocumentExcelExportServiceTest,DocumentExcelExportControllerContractTest,CatalogServiceTest,ProductSupplierServiceTest,DocumentServiceTest,TpvErpBackendApplicationTests" test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Inspect git status**

Run: `git status --short --branch`.

Expected: only intended Excel/import/export files are modified or no changes remain after commits.

- [ ] **Step 3: Final commit if needed**

If verification required small fixes:

```powershell
git add backend/pom.xml backend/src/main/java/com/tpverp/backend backend/src/test/java/com/tpverp/backend
git commit -m "test: verify excel import export"
```

---

## Self-Review

- Spec coverage: import `.xls/.xlsx`, mapping by letters, preview, confirm, purchase draft, optional document lines, supplier selection, deferred supplier-reference update on purchase confirmation, visible document export, and focused tests are covered.
- Placeholder scan: no TBD/TODO/fill-in placeholders.
- Type consistency: plan uses current `CatalogService`, `DocumentService`, `ProductSupplierService`, `CommercialDocument`, `DocumentCommand`, and `DocumentLineCommand` names.
