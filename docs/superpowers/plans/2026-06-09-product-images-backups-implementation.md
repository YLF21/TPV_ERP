# Product Images And Backups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir una imagen WebP y una miniatura por producto, servirlas de forma autenticada y restaurarlas junto con PostgreSQL.

**Architecture:** El procesamiento binario, el almacenamiento de archivos y la orquestacion transaccional se separaran en componentes pequenos dentro de `catalog.image`. El backup cifrara un paquete versionado que contiene dump, manifiesto e imagenes, manteniendo compatibilidad de lectura con copias antiguas que contienen solo el dump.

**Tech Stack:** Java 25, Spring Boot 4.0.6, ImageIO, `org.sejda.imageio:webp-imageio`, Spring MVC multipart, SHA-256, ZIP, PostgreSQL custom dump, JUnit 5, AssertJ.

---

## File Map

- Modify `backend/pom.xml`: codec WebP reader/writer.
- Modify `backend/src/main/resources/application.yml`: raiz de imagenes y limite multipart.
- Create `backend/src/main/java/com/tpverp/backend/catalog/image/ProductImageConfiguration.java`: beans y propiedades.
- Create `backend/src/main/java/com/tpverp/backend/catalog/image/ProductImageProcessor.java`: validacion, orientacion, escalado, miniatura y exportacion.
- Create `backend/src/main/java/com/tpverp/backend/catalog/image/ProductImageStorage.java`: rutas confinadas y movimientos atomicos.
- Create `backend/src/main/java/com/tpverp/backend/catalog/image/ProductImageService.java`: metadatos, sustitucion y borrado.
- Create `backend/src/main/java/com/tpverp/backend/catalog/image/ProductImageController.java`: API autenticada.
- Modify `backend/src/main/java/com/tpverp/backend/catalog/Product.java`: metadatos de imagen como objeto inmutable y operaciones de dominio.
- Modify `backend/src/main/java/com/tpverp/backend/catalog/CatalogService.java`: borrado coordinado del archivo antes de eliminar producto.
- Create `backend/src/main/java/com/tpverp/backend/backup/application/BackupPackage.java`: paquete ZIP versionado y manifiesto.
- Modify `backend/src/main/java/com/tpverp/backend/backup/BackupService.java`: incluye y restaura imagenes.
- Add focused tests under `backend/src/test/java/com/tpverp/backend/catalog/image/`.
- Add backup tests under `backend/src/test/java/com/tpverp/backend/backup/application/`.

### Task 1: WebP Codec And Image Processor

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/tpverp/backend/catalog/image/ProductImageProcessor.java`
- Create: `backend/src/test/java/com/tpverp/backend/catalog/image/ProductImageProcessorTest.java`

- [ ] **Step 1: Add failing processor tests**

Generate images in memory during the test; do not add binary fixtures. Cover:

```java
@Test
void scalesLargeImageInside1600SquareWithoutEnlarging() {
    var input = png(3200, 1200, true);

    var result = processor.process(input);

    assertThat(result.width()).isEqualTo(1600);
    assertThat(result.height()).isEqualTo(600);
    assertThat(read(result.thumbnail())).satisfies(image -> {
        assertThat(image.getWidth()).isEqualTo(300);
        assertThat(image.getHeight()).isEqualTo(300);
        assertThat(image.getColorModel().hasAlpha()).isTrue();
    });
}

@Test
void rejectsPayloadThatClaimsToBeAnImageButCannotBeDecoded() {
    assertThatThrownBy(() -> processor.process("not-an-image".getBytes(UTF_8)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("imagen");
}
```

Also test empty input, input larger than 5 MiB, JPG, PNG, WebP, transparent
pixels and no enlargement of a 200 x 100 image.

- [ ] **Step 2: Run and verify RED**

```powershell
cd backend
.\mvnw.cmd -Dtest=ProductImageProcessorTest test
```

Expected: compilation fails because `ProductImageProcessor` does not exist.

- [ ] **Step 3: Add the WebP dependency and upload limit**

Add:

```xml
<dependency>
    <groupId>org.sejda.imageio</groupId>
    <artifactId>webp-imageio</artifactId>
    <version>0.1.6</version>
</dependency>
```

Configure:

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 5MB
      max-request-size: 5MB

tpv:
  product-images:
    directory: ${TPV_PRODUCT_IMAGE_DIRECTORY:${user.home}/.tpv-erp/product-images}
```

Before accepting the dependency, run a smoke test on Java 25 that reads and
writes WebP. If native loading fails on Windows x64, replace only the codec
dependency with a Java-25-compatible ImageIO reader/writer that passes the same
test; do not change the `ProductImageProcessor` API.

- [ ] **Step 4: Implement processing**

Use constants:

```java
static final int MAX_BYTES = 5 * 1024 * 1024;
static final int MAX_DIMENSION = 1600;
static final int THUMBNAIL_SIZE = 300;
static final float WEBP_QUALITY = 0.85f;
```

Return:

```java
public record ProcessedImage(
        byte[] image,
        byte[] thumbnail,
        int width,
        int height,
        String sha256) {
}
```

Decode through an `ImageReader` selected from actual bytes. Apply EXIF
orientation when metadata provides values 2-8. Draw the main image with
`RenderingHints.VALUE_INTERPOLATION_BICUBIC`. Draw the thumbnail centered in a
transparent 300 x 300 ARGB canvas. Encode both to WebP quality `0.85`.

- [ ] **Step 5: Add export tests and implementation**

Test:

- WebP export returns stored bytes.
- PNG preserves alpha.
- JPG paints transparent pixels white.
- Unsupported format is rejected.

Implement:

```java
public byte[] export(byte[] storedWebp, ExportFormat format)
```

with enum `JPG`, `PNG`, `WEBP`.

- [ ] **Step 6: Run focused tests and commit**

```powershell
cd backend
.\mvnw.cmd -Dtest=ProductImageProcessorTest test
git add pom.xml src/main/resources/application.yml src/main/java/com/tpverp/backend/catalog/image/ProductImageProcessor.java src/test/java/com/tpverp/backend/catalog/image/ProductImageProcessorTest.java
git commit -m "feat: process product images as webp"
```

Expected: tests PASS before committing.

### Task 2: Confined Atomic Image Storage

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/catalog/image/ProductImageStorage.java`
- Create: `backend/src/test/java/com/tpverp/backend/catalog/image/ProductImageStorageTest.java`

- [ ] **Step 1: Write failing storage tests**

Using `@TempDir`, verify:

```java
@Test
void storesMainAndThumbnailUnderStoreAndProductIds() {
    storage.write(storeId, productId, imageId, image, thumbnail);

    assertThat(storage.read(storeId, productId, imageId, false)).isEqualTo(image);
    assertThat(storage.read(storeId, productId, imageId, true)).isEqualTo(thumbnail);
}

@Test
void failedReplacementLeavesOldFilesUntouched() {
    storage.write(storeId, productId, oldId, oldImage, oldThumbnail);

    assertThatThrownBy(() -> storage.writeWithFailureForTest(
            storeId, productId, newId, newImage, newThumbnail))
            .isInstanceOf(IOException.class);

    assertThat(storage.read(storeId, productId, oldId, false)).isEqualTo(oldImage);
}
```

Do not add a production `writeWithFailureForTest` method. Inject a small
`AtomicFileMover` interface and make the test implementation fail on the second
move.

- [ ] **Step 2: Run and verify RED**

```powershell
cd backend
.\mvnw.cmd -Dtest=ProductImageStorageTest test
```

Expected: compilation fails because storage types are missing.

- [ ] **Step 3: Implement confined paths and atomic writes**

Resolve paths only from UUID values:

```java
private Path productDirectory(UUID storeId, UUID productId) {
    Path resolved = root.resolve(storeId.toString())
            .resolve(productId.toString())
            .normalize();
    if (!resolved.startsWith(root)) {
        throw new IllegalStateException("Ruta de imagen fuera del almacenamiento");
    }
    return resolved;
}
```

Write both files to UUID-named temporaries, then move with
`ATOMIC_MOVE, REPLACE_EXISTING`. On failure remove new temporaries and any new
final file created by this attempt.

Expose `write`, `read`, `delete`, `deleteProductDirectory`, `root` and
`replaceTree` for backup restoration.

- [ ] **Step 4: Run tests and commit**

```powershell
cd backend
.\mvnw.cmd -Dtest=ProductImageStorageTest test
git add src/main/java/com/tpverp/backend/catalog/image/ProductImageStorage.java src/test/java/com/tpverp/backend/catalog/image/ProductImageStorageTest.java
git commit -m "feat: add atomic product image storage"
```

### Task 3: Product Image Metadata And Service

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/catalog/Product.java`
- Create: `backend/src/main/java/com/tpverp/backend/catalog/image/ProductImageService.java`
- Create: `backend/src/main/java/com/tpverp/backend/catalog/image/ProductImageConfiguration.java`
- Create: `backend/src/test/java/com/tpverp/backend/catalog/image/ProductImageServiceTest.java`

- [ ] **Step 1: Write failing service tests**

Cover upload, read, replace, delete and rollback compensation:

```java
@Test
void uploadStoresFilesAndUpdatesProductMetadata() {
    when(products.findById(product.getId())).thenReturn(Optional.of(product));
    when(processor.process(input)).thenReturn(processed);

    var result = service.upload(product.getId(), input);

    verify(storage).write(store.getId(), product.getId(), result.imageId(),
            processed.image(), processed.thumbnail());
    assertThat(product.getImageHash()).isEqualTo(processed.sha256());
}
```

For replacement, assert old files are deleted only after new files and metadata
are successfully saved. For failure, assert old metadata/files remain.

- [ ] **Step 2: Run and verify RED**

```powershell
cd backend
.\mvnw.cmd -Dtest=ProductImageServiceTest test
```

Expected: compilation fails because service and product image operations are
missing.

- [ ] **Step 3: Add Product domain operations**

Add:

```java
public ImageMetadata imageMetadata()

public void replaceImage(UUID imageId, long size, String sha256)

public void clearImage()
```

Map `imagen_id` as `String` for schema compatibility but expose it as UUID after
strict parsing. `replaceImage` always sets MIME type to `image/webp`.

- [ ] **Step 4: Implement service orchestration**

Create public methods:

```java
@Transactional
public ImageView upload(UUID productId, byte[] input)

@Transactional(readOnly = true)
public ImageContent read(UUID productId, boolean thumbnail)

@Transactional(readOnly = true)
public ImageContent export(UUID productId, ExportFormat format)

@Transactional
public void delete(UUID productId)

public void deleteForProduct(Product product)
```

Register after-commit cleanup with Spring transaction synchronization so the
old image is deleted only after the new metadata commits. If DB persistence
fails, delete the newly written files and keep the previous image.

- [ ] **Step 5: Run tests and commit**

```powershell
cd backend
.\mvnw.cmd -Dtest=ProductImageServiceTest,ProductImageStorageTest,ProductImageProcessorTest test
git add src/main/java/com/tpverp/backend/catalog/Product.java src/main/java/com/tpverp/backend/catalog/image src/test/java/com/tpverp/backend/catalog/image
git commit -m "feat: manage product image lifecycle"
```

### Task 4: Authenticated Image API And Product Deletion

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/catalog/image/ProductImageController.java`
- Create: `backend/src/test/java/com/tpverp/backend/catalog/image/ProductImageControllerContractTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/catalog/CatalogService.java`
- Modify: `backend/src/test/java/com/tpverp/backend/catalog/CatalogServiceTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/shared/api/ApiExceptionHandler.java`

- [ ] **Step 1: Write failing API and deletion tests**

Assert exact mappings, `PRODUCTS_READ` for GET and `PRODUCTS_WRITE` for PUT and
DELETE. Add a catalog test proving image cleanup occurs before product deletion
and missing files do not block deletion.

- [ ] **Step 2: Run and verify RED**

```powershell
cd backend
.\mvnw.cmd -Dtest=ProductImageControllerContractTest,CatalogServiceTest test
```

Expected: compilation fails for the missing controller and cleanup dependency.

- [ ] **Step 3: Implement endpoints**

Use:

```java
@PutMapping(path = "/{productId}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public ImageView upload(@PathVariable UUID productId, @RequestPart("file") MultipartFile file)

@GetMapping("/{productId}/image")
public ResponseEntity<byte[]> image(@PathVariable UUID productId, @RequestHeader IF_NONE_MATCH ...)

@GetMapping("/{productId}/image/thumbnail")
public ResponseEntity<byte[]> thumbnail(...)

@GetMapping("/{productId}/image/export")
public ResponseEntity<byte[]> export(
        @PathVariable UUID productId, @RequestParam ExportFormat format)

@DeleteMapping("/{productId}/image")
public ResponseEntity<Void> delete(@PathVariable UUID productId)
```

Set `Content-Type`, quoted `ETag`, conditional `304` and attachment filename for
exports. Convert missing image to `404` through a dedicated
`ResourceNotFoundException` handled by `ApiExceptionHandler`.

- [ ] **Step 4: Integrate product deletion**

Inject `ProductImageService` into `CatalogService`. Immediately before deleting
a product with no history, invoke `deleteForProduct(product)`. Keep existing
history restrictions unchanged.

- [ ] **Step 5: Run tests and commit**

```powershell
cd backend
.\mvnw.cmd -Dtest=ProductImageControllerContractTest,CatalogServiceTest test
git add src/main/java/com/tpverp/backend/catalog src/main/java/com/tpverp/backend/shared/api/ApiExceptionHandler.java src/test/java/com/tpverp/backend/catalog
git commit -m "feat: expose authenticated product images"
```

### Task 5: Versioned Backup Package

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/backup/application/BackupPackage.java`
- Create: `backend/src/test/java/com/tpverp/backend/backup/application/BackupPackageTest.java`

- [ ] **Step 1: Write failing package tests**

Test package creation, extraction, checksum rejection and legacy detection:

```java
@Test
void packageRoundTripPreservesDumpAndImages() {
    packages.create(dump, imageRoot, archive);
    var extracted = packages.extract(archive, restoreDirectory);

    assertThat(Files.readAllBytes(extracted.databaseDump())).isEqualTo(dumpBytes);
    assertThat(extracted.imagesDirectory().resolve(relativeImage)).hasSameBinaryContentAs(image);
}

@Test
void tamperedEntryIsRejectedBeforeRestore() {
    tamperZipEntry(archive, "images/store/product/image.webp");

    assertThatThrownBy(() -> packages.extract(archive, restoreDirectory))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("integridad");
}
```

- [ ] **Step 2: Run and verify RED**

```powershell
cd backend
.\mvnw.cmd -Dtest=BackupPackageTest test
```

Expected: compilation fails because `BackupPackage` does not exist.

- [ ] **Step 3: Implement deterministic package format**

Create a ZIP with:

```text
manifest.properties
database.dump
images/<relative product image paths>
```

Manifest keys:

```text
format=TPV_ERP_BACKUP_PACKAGE
version=1
database.sha256=<hex>
images.<encoded-relative-path>.sha256=<hex>
```

Reject absolute paths, `..`, duplicate entries, unsupported versions and
checksum mismatches. Extract only inside the supplied temporary directory.

- [ ] **Step 4: Run tests and commit**

```powershell
cd backend
.\mvnw.cmd -Dtest=BackupPackageTest test
git add src/main/java/com/tpverp/backend/backup/application/BackupPackage.java src/test/java/com/tpverp/backend/backup/application/BackupPackageTest.java
git commit -m "feat: package database and product images"
```

### Task 6: Backup Service Integration And Legacy Restore

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/backup/BackupService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/backup/BackupConfiguration.java`
- Modify: `backend/src/test/java/com/tpverp/backend/backup/BackupServiceContractTest.java`
- Create: `backend/src/test/java/com/tpverp/backend/backup/BackupServiceImageTest.java`

- [ ] **Step 1: Write failing orchestration tests**

Verify:

- `executeNow` dumps PostgreSQL, packages dump plus image root, then encrypts.
- Restore decrypts, validates package, restores DB, then atomically replaces the
  image tree.
- Failed image replacement restores the previous tree.
- A decrypted legacy PostgreSQL custom dump bypasses package extraction and
  leaves images untouched.
- Temporary dump, package and extraction directories are removed.

- [ ] **Step 2: Run and verify RED**

```powershell
cd backend
.\mvnw.cmd -Dtest=BackupServiceContractTest,BackupServiceImageTest test
```

Expected: failures show `BackupService` still encrypts only the dump.

- [ ] **Step 3: Integrate packaging before encryption**

Inject `BackupPackage` and `ProductImageStorage`. In `executeNow`:

```java
commands.dump(dump);
packages.create(dump, imageStorage.root(), packageFile);
fileCrypto.encrypt(packageFile, encrypted, brk);
```

Record package bytes and image count in execution metadata.

- [ ] **Step 4: Integrate package and legacy restore**

After decryption, inspect the plaintext:

- ZIP with valid manifest: extract, restore `database.dump`, then call
  `imageStorage.replaceTree(extracted.imagesDirectory())`.
- PostgreSQL custom dump signature: call existing `commands.restore` and do not
  alter images.
- Any other content: reject before invoking `pg_restore`.

Keep `restore` non-transactional while external tools run.

- [ ] **Step 5: Run tests and commit**

```powershell
cd backend
.\mvnw.cmd -Dtest=BackupServiceContractTest,BackupServiceImageTest,BackupPackageTest,AtomicBackupFileTest test
git add src/main/java/com/tpverp/backend/backup src/test/java/com/tpverp/backend/backup
git commit -m "feat: include product images in backups"
```

### Task 7: Full Verification

**Files:**
- Modify only files from Tasks 1-6 when a verified failure requires it.

- [ ] **Step 1: Run all backend tests**

```powershell
cd backend
.\mvnw.cmd test
```

Expected: BUILD SUCCESS with no failures.

- [ ] **Step 2: Run PostgreSQL verification**

```powershell
$env:TPV_ERP_TEST_DB_URL='jdbc:postgresql://localhost:5432/tpv_erp_test'
$env:TPV_ERP_TEST_DB_USER='postgres'
$env:TPV_ERP_TEST_DB_PASSWORD='admin'
cd backend
.\mvnw.cmd verify
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Run a real WebP smoke test**

Execute the focused processor test on Windows Java 25 and verify that native
codec loading produces no warning or link error:

```powershell
cd backend
.\mvnw.cmd -Dtest=ProductImageProcessorTest test
```

- [ ] **Step 4: Review security and filesystem boundaries**

Confirm:

- No endpoint exposes a local path.
- No original filename participates in storage.
- All storage and ZIP extraction paths remain under configured roots.
- GET uses `PRODUCTS_READ`; PUT and DELETE use `PRODUCTS_WRITE`.
- Failed replacements preserve prior image and failed restores preserve the
  prior image tree.
- Public orchestration and non-obvious atomic operations have concise `//`
  comments only.

- [ ] **Step 5: Check diff and commit cleanup if needed**

```powershell
git diff --check
git status --short
```

Commit only verified cleanup; do not include unrelated pre-existing changes.
