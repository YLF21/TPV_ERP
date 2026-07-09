# Promotions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add configurable sales promotions, promotional coupons, backend recalculation, and frontend management/preview support.

**Architecture:** Keep promotions in a new backend package `com.tpverp.backend.promotion`. The backend owns final calculation and document persistence; APP VENTA may preview but must accept backend recalculation at confirmation. Promotion discounts are stored as special negative document lines, and generated coupons are separate records created after document confirmation.

**Tech Stack:** Java 25, Spring Boot, JPA, Flyway, PostgreSQL, Maven Wrapper, React/TypeScript frontend packages already in repo.

---

## Execution Notes

- Work in a new branch or worktree before touching code:

```powershell
git switch -c codex/promotions
```

- Current repo already contains unrelated dirty backend/frontend changes. Do not stage them with promotion work.
- The next migration number in this checkout is `V44`; use `backend/src/main/resources/db/migration/V44__promociones.sql`.
- Run backend commands from `C:\Users\YLF\Documents\TPV ERP\backend` using `.\mvnw.cmd`.
- Keep commits small. Each task below ends with an isolated commit.

## File Map

Backend files to create:

- `backend/src/main/resources/db/migration/V44__promociones.sql`: tables, constraints, document-line promotion columns, permissions.
- `backend/src/main/java/com/tpverp/backend/promotion/Promotion.java`: promotion aggregate.
- `backend/src/main/java/com/tpverp/backend/promotion/PromotionTarget.java`: product/family/subfamily target rows.
- `backend/src/main/java/com/tpverp/backend/promotion/PromotionalCoupon.java`: generated coupon aggregate.
- `backend/src/main/java/com/tpverp/backend/promotion/PromotionalCouponAttempt.java`: failed coupon validation attempts.
- `backend/src/main/java/com/tpverp/backend/promotion/PromotionRepository.java`: promotion queries.
- `backend/src/main/java/com/tpverp/backend/promotion/PromotionalCouponRepository.java`: coupon queries.
- `backend/src/main/java/com/tpverp/backend/promotion/PromotionalCouponAttemptRepository.java`: attempt persistence.
- `backend/src/main/java/com/tpverp/backend/promotion/PromotionService.java`: CRUD, activation, deletion, duplication.
- `backend/src/main/java/com/tpverp/backend/promotion/PromotionEngine.java`: calculates promotion lines and coupon effects.
- `backend/src/main/java/com/tpverp/backend/promotion/PromotionalCouponService.java`: generate, validate, consume, cancel, reactivate.
- `backend/src/main/java/com/tpverp/backend/promotion/PromotionController.java`: APP GESTION API.
- `backend/src/main/java/com/tpverp/backend/promotion/PromotionalCouponController.java`: coupon admin and sale validation API.
- `backend/src/main/java/com/tpverp/backend/promotion/*Type.java`: enums listed in Task 2.

Backend files to modify:

- `backend/src/main/java/com/tpverp/backend/document/DocumentLine.java`: allow `PROMOTION` special lines without product.
- `backend/src/main/java/com/tpverp/backend/document/DocumentLineCommand.java`: carry line type and optional promotion metadata.
- `backend/src/main/java/com/tpverp/backend/document/CommercialDocument.java`: expose adding promotion lines and keep totals correct.
- `backend/src/main/java/com/tpverp/backend/document/DocumentService.java`: recalculate promotions before confirmation and generate coupons after confirmation.
- `backend/src/main/java/com/tpverp/backend/inventory/InventoryDocumentGateway.java`: ignore promotion lines for stock.
- `backend/src/main/java/com/tpverp/backend/verifactu/FiscalSnapshotFactory.java`: include promotion line metadata in fiscal snapshot.
- `backend/src/main/java/com/tpverp/backend/security/application/CorePermissionBootstrap.java`: add promotion permissions only if finer permissions are desired; first version can reuse `GESTION_VENTAS` and `VENTA`.
- `backend/src/test/java/com/tpverp/backend/persistence/PostgreSqlMigrationTest.java`: include new tables.

Frontend files to modify/create:

- `frontend/packages/app-common/src/api/*`: add promotion API client functions following existing API pattern.
- `frontend/packages/app-common/src/components/PromotionWizard.tsx`: APP GESTION promotion wizard.
- `frontend/packages/app-common/src/components/PromotionListScreen.tsx`: promotion listing and actions.
- `frontend/packages/app-common/src/components/PromotionalCouponScreen.tsx`: coupon admin list, reprint, cancel/reactivate.
- `frontend/packages/app-common/src/components/*Sale*`: show previewed promotions and generated coupon result where current sale flow lives.
- `frontend/packages/app-common/src/i18n/MessagesEs.ts`
- `frontend/packages/app-common/src/i18n/MessagesEn.ts`
- `frontend/packages/app-common/src/i18n/MessagesZh.ts`
- `frontend/packages/app-common/src/styles/tpv.css`

---

### Task 1: Migration And Persistence Contract

**Files:**
- Create: `backend/src/main/resources/db/migration/V44__promociones.sql`
- Modify: `backend/src/test/java/com/tpverp/backend/persistence/PostgreSqlMigrationTest.java`
- Test: `backend/src/test/java/com/tpverp/backend/persistence/MigrationV44ContractTest.java`

- [ ] **Step 1: Write the failing migration contract test**

Create `MigrationV44ContractTest.java`:

```java
package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

class MigrationV44ContractTest {

    @Test
    void promotionsTablesAndDocumentLineColumnsExist() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/tpv_erp_test", "postgres", "admin")) {
            assertThat(column(connection, "documento_linea", "tipo_linea")).isTrue();
            assertThat(column(connection, "documento_linea", "promocion_id")).isTrue();
            assertThat(table(connection, "promocion")).isTrue();
            assertThat(table(connection, "promocion_objetivo")).isTrue();
            assertThat(table(connection, "cupon_promocional")).isTrue();
            assertThat(table(connection, "cupon_promocional_intento")).isTrue();
        }
    }

    private static boolean table(Connection connection, String tableName) throws Exception {
        try (var rs = connection.getMetaData().getTables(null, null, tableName, null)) {
            return rs.next();
        }
    }

    private static boolean column(Connection connection, String tableName, String columnName) throws Exception {
        try (var rs = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
            return rs.next();
        }
    }
}
```

- [ ] **Step 2: Run test and verify it fails**

```powershell
cd backend
.\mvnw.cmd "-Dtest=MigrationV44ContractTest" test
```

Expected: FAIL because migration `V44__promociones.sql` does not exist yet.

- [ ] **Step 3: Add migration**

Create `V44__promociones.sql`:

```sql
alter table documento_linea
    add column tipo_linea varchar(16) not null default 'PRODUCT',
    add column promocion_id uuid,
    add column promocion_version_id uuid,
    add column cupon_promocional_id uuid,
    add column metadata_promocion jsonb;

alter table documento_linea
    alter column producto_id drop not null;

alter table documento_linea
    add constraint ck_documento_linea_tipo_linea
        check (tipo_linea in ('PRODUCT', 'PROMOTION', 'PROMOTIONAL_COUPON')),
    add constraint ck_documento_linea_producto_por_tipo
        check (
            (tipo_linea = 'PRODUCT' and producto_id is not null)
            or (tipo_linea in ('PROMOTION', 'PROMOTIONAL_COUPON') and producto_id is null)
        ),
    add constraint ck_documento_linea_promocion_negativa
        check (tipo_linea = 'PRODUCT' or total <= 0);

create table promocion (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    version_origen_id uuid references promocion(id),
    nombre varchar(160) not null,
    descripcion text,
    tipo varchar(40) not null,
    estado varchar(16) not null,
    segmento_cliente varchar(32) not null,
    member_category_id uuid references member_category(id),
    ambito varchar(24) not null,
    fecha_inicio date not null,
    fecha_fin date,
    minimo_importe numeric(19,2),
    minimo_cantidad numeric(19,3),
    compra_cantidad numeric(19,3),
    paga_cantidad numeric(19,3),
    descuento_importe numeric(19,2),
    descuento_porcentaje numeric(5,2),
    descuento_maximo numeric(19,2),
    precio_lote numeric(19,2),
    genera_cupon boolean not null default false,
    cupon_importe numeric(19,2),
    cupon_porcentaje numeric(5,2),
    cupon_descuento_maximo numeric(19,2),
    cupon_minimo_importe numeric(19,2),
    cupon_valido_desde_modo varchar(24),
    cupon_valido_desde_fecha date,
    cupon_valido_desde_dias integer,
    cupon_valido_hasta_fecha date,
    cupon_valido_dias integer,
    usada boolean not null default false,
    creado_en timestamptz not null default now(),
    actualizado_en timestamptz not null default now(),
    version bigint not null default 0,
    check (estado in ('DRAFT', 'ACTIVE', 'INACTIVE')),
    check (tipo in ('PURCHASE_THRESHOLD_COUPON', 'PURCHASE_THRESHOLD_DISCOUNT',
                    'BUY_X_PAY_Y', 'SECOND_UNIT_PERCENT', 'FIXED_PACK_PRICE',
                    'QUANTITY_DISCOUNT')),
    check (segmento_cliente in ('ALL', 'IDENTIFIED_CUSTOMERS', 'MEMBERS_ONLY', 'MEMBER_CATEGORY')),
    check (ambito in ('SALE', 'PRODUCT_LIST', 'FAMILY', 'SUBFAMILY')),
    check (fecha_fin is null or fecha_fin >= fecha_inicio),
    check (minimo_importe is null or minimo_importe >= 0),
    check (descuento_porcentaje is null or descuento_porcentaje between 0 and 100),
    check (cupon_porcentaje is null or cupon_porcentaje between 0 and 100)
);

create table promocion_objetivo (
    id uuid primary key,
    promocion_id uuid not null references promocion(id) on delete cascade,
    tipo varchar(16) not null,
    objetivo_id uuid not null,
    check (tipo in ('PRODUCT', 'FAMILY', 'SUBFAMILY')),
    unique (promocion_id, tipo, objetivo_id)
);

create table cupon_promocional (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    tienda_generado_id uuid not null references tienda(id),
    tienda_canjeado_id uuid references tienda(id),
    promocion_id uuid not null references promocion(id),
    documento_generado_id uuid not null references documento(id),
    documento_canjeado_id uuid references documento(id),
    cliente_id uuid references cliente(id),
    member_id uuid references miembro(id),
    codigo_hash varchar(128) not null,
    codigo_ultimos4 varchar(4) not null,
    estado varchar(16) not null,
    beneficio_tipo varchar(16) not null,
    importe numeric(19,2),
    porcentaje numeric(5,2),
    descuento_maximo numeric(19,2),
    minimo_importe numeric(19,2),
    valido_desde date not null,
    valido_hasta date not null,
    creado_en timestamptz not null default now(),
    usado_en timestamptz,
    cancelado_en timestamptz,
    cancelado_por uuid references usuario(id),
    motivo_cancelacion text,
    reactivado_en timestamptz,
    reactivado_por uuid references usuario(id),
    motivo_reactivacion text,
    version bigint not null default 0,
    unique (empresa_id, codigo_hash),
    check (estado in ('ACTIVE', 'USED', 'EXPIRED', 'CANCELLED')),
    check (beneficio_tipo in ('AMOUNT', 'PERCENT')),
    check (valido_hasta >= valido_desde)
);

create table cupon_promocional_intento (
    id uuid primary key,
    empresa_id uuid not null references empresa(id),
    tienda_id uuid not null references tienda(id),
    usuario_id uuid references usuario(id),
    terminal_id uuid references terminal(id),
    codigo_hash varchar(128),
    codigo_ultimos4 varchar(4),
    motivo varchar(48) not null,
    creado_en timestamptz not null default now(),
    check (motivo in ('NOT_FOUND', 'EXPIRED', 'CANCELLED', 'USED',
                      'CUSTOMER_MISMATCH', 'DOCUMENT_NOT_ELIGIBLE',
                      'MINIMUM_NOT_REACHED'))
);

create index ix_promocion_empresa_estado_fecha on promocion(empresa_id, estado, fecha_inicio, fecha_fin);
create index ix_promocion_objetivo_objetivo on promocion_objetivo(tipo, objetivo_id);
create index ix_cupon_empresa_estado_fecha on cupon_promocional(empresa_id, estado, valido_desde, valido_hasta);
create index ix_cupon_documento_generado on cupon_promocional(documento_generado_id);
create index ix_cupon_documento_canjeado on cupon_promocional(documento_canjeado_id);
```

- [ ] **Step 4: Run migration contract**

```powershell
.\mvnw.cmd "-Dtest=MigrationV44ContractTest,PostgreSqlMigrationTest" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/resources/db/migration/V44__promociones.sql backend/src/test/java/com/tpverp/backend/persistence/MigrationV44ContractTest.java backend/src/test/java/com/tpverp/backend/persistence/PostgreSqlMigrationTest.java
git commit -m "feat: add promotions schema"
```

---

### Task 2: Domain Entities And Repositories

**Files:**
- Create: backend promotion entities, enums, repositories listed in File Map.
- Test: `backend/src/test/java/com/tpverp/backend/promotion/PromotionDomainTest.java`

- [ ] **Step 1: Write failing domain tests**

Create tests for:

```java
@Test
void usedPromotionCannotBeChangedDirectly() {
    var promotion = Promotion.draft(companyId, "3x2 Agua", PromotionType.BUY_X_PAY_Y, LocalDate.now());
    promotion.markUsed();
    assertThatThrownBy(() -> promotion.rename("2x1 Agua"))
            .hasMessageContaining("promotion.used_requires_new_version");
}

@Test
void cancelledCouponCanReactivateOnlyIfNotExpired() {
    var coupon = PromotionalCoupon.amount(companyId, storeId, promotionId, documentId,
            null, null, "HASH", "ABCD", new BigDecimal("5.00"),
            LocalDate.of(2026, 7, 15), LocalDate.of(2026, 7, 30));
    coupon.cancel(userId, "error de impresion", Instant.parse("2026-07-10T10:00:00Z"));
    coupon.reactivate(userId, "reimpreso", LocalDate.of(2026, 7, 20),
            Instant.parse("2026-07-10T10:01:00Z"));
    assertThat(coupon.getStatus()).isEqualTo(PromotionalCouponStatus.ACTIVE);
}
```

- [ ] **Step 2: Add enums**

Create:

```java
public enum PromotionType { PURCHASE_THRESHOLD_COUPON, PURCHASE_THRESHOLD_DISCOUNT, BUY_X_PAY_Y, SECOND_UNIT_PERCENT, FIXED_PACK_PRICE, QUANTITY_DISCOUNT }
public enum PromotionStatus { DRAFT, ACTIVE, INACTIVE }
public enum PromotionCustomerSegment { ALL, IDENTIFIED_CUSTOMERS, MEMBERS_ONLY, MEMBER_CATEGORY }
public enum PromotionScope { SALE, PRODUCT_LIST, FAMILY, SUBFAMILY }
public enum PromotionTargetType { PRODUCT, FAMILY, SUBFAMILY }
public enum PromotionalCouponStatus { ACTIVE, USED, EXPIRED, CANCELLED }
public enum PromotionalCouponBenefitType { AMOUNT, PERCENT }
public enum CouponRejectReason { NOT_FOUND, EXPIRED, CANCELLED, USED, CUSTOMER_MISMATCH, DOCUMENT_NOT_ELIGIBLE, MINIMUM_NOT_REACHED }
public enum DocumentLineType { PRODUCT, PROMOTION, PROMOTIONAL_COUPON }
```

- [ ] **Step 3: Add entities with minimal behavior**

`Promotion` must include methods:

```java
public void activate() {
    requireComplete();
    estado = PromotionStatus.ACTIVE;
}

public void deactivate() {
    estado = PromotionStatus.INACTIVE;
}

public void markUsed() {
    usada = true;
}

public void rename(String name) {
    requireNotUsed();
    nombre = required(name, "nombre");
}

private void requireNotUsed() {
    if (usada) {
        throw new IllegalStateException("message.promotion.used_requires_new_version");
    }
}
```

`PromotionalCoupon` must include methods:

```java
public void use(UUID storeId, UUID documentId, Instant usedAt) {
    if (estado != PromotionalCouponStatus.ACTIVE) {
        throw new IllegalStateException("message.coupon.not_active");
    }
    tiendaCanjeadoId = Objects.requireNonNull(storeId, "storeId");
    documentoCanjeadoId = Objects.requireNonNull(documentId, "documentId");
    usadoEn = Objects.requireNonNull(usedAt, "usedAt");
    estado = PromotionalCouponStatus.USED;
}

public void cancel(UUID userId, String reason, Instant cancelledAt) {
    if (estado == PromotionalCouponStatus.USED) {
        throw new IllegalStateException("message.coupon.used_cannot_cancel");
    }
    canceladoPor = Objects.requireNonNull(userId, "userId");
    motivoCancelacion = required(reason, "motivo");
    canceladoEn = Objects.requireNonNull(cancelledAt, "cancelledAt");
    estado = PromotionalCouponStatus.CANCELLED;
}
```

- [ ] **Step 4: Run tests**

```powershell
.\mvnw.cmd "-Dtest=PromotionDomainTest" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/promotion backend/src/test/java/com/tpverp/backend/promotion/PromotionDomainTest.java
git commit -m "feat: add promotion domain model"
```

---

### Task 3: Document Promotion Lines

**Files:**
- Modify: `DocumentLine.java`, `DocumentLineCommand.java`, `CommercialDocument.java`, `InventoryDocumentGateway.java`, `FiscalSnapshotFactory.java`
- Test: `backend/src/test/java/com/tpverp/backend/document/DocumentPromotionLineTest.java`

- [ ] **Step 1: Write failing tests**

Tests:

```java
@Test
void promotionLineHasNoProductAndDoesNotAffectStock() {
    var document = fixture.saleDocument();
    document.addLine(fixture.productLine(document, productId, "Agua", new BigDecimal("3.00")));
    document.addLine(DocumentLine.promotion(document, 2, "PROMOCION 3x2 Agua",
            new BigDecimal("-1.00"), true, "IVA", new BigDecimal("21.00"),
            promotionId, null));
    assertThat(document.getTotal()).isEqualByComparingTo("2.00");
    assertThat(document.getLineas().get(1).getProductoId()).isNull();
    assertThat(document.getLineas().get(1).getLineType()).isEqualTo(DocumentLineType.PROMOTION);
}
```

- [ ] **Step 2: Modify `DocumentLine` minimally**

Add fields:

```java
@Enumerated(EnumType.STRING)
@Column(name = "tipo_linea", nullable = false, length = 16)
private DocumentLineType lineType = DocumentLineType.PRODUCT;

@Column(name = "promocion_id")
private UUID promotionId;

@Column(name = "promocion_version_id")
private UUID promotionVersionId;

@Column(name = "cupon_promocional_id")
private UUID promotionalCouponId;
```

Add factory:

```java
public static DocumentLine promotion(
        CommercialDocument document,
        int position,
        String name,
        BigDecimal amount,
        boolean taxesIncluded,
        String taxRegime,
        BigDecimal taxPercent,
        UUID promotionId,
        UUID couponId) {
    var line = new DocumentLine();
    line.id = UUID.randomUUID();
    line.documento = Objects.requireNonNull(document, "documento");
    line.productoId = null;
    line.posicion = position;
    line.cantidad = BigDecimal.ONE.setScale(3);
    line.codigo = couponId == null ? "PROMOTION" : "PROMO_COUPON";
    line.nombre = required(name, "nombre");
    line.tarifa = null;
    line.precioUnitario = Money.euros(amount);
    line.descuento = BigDecimal.ZERO.setScale(2);
    line.impuestosIncluidos = taxesIncluded;
    line.regimenImpuesto = taxRegime(taxRegime);
    line.porcentajeImpuesto = Money.validPercentage(taxPercent);
    line.lineType = couponId == null ? DocumentLineType.PROMOTION : DocumentLineType.PROMOTIONAL_COUPON;
    line.promotionId = promotionId;
    line.promotionalCouponId = couponId;
    line.calculateAmounts();
    return line;
}
```

Adjust `nonNegative` so it only applies to `PRODUCT` lines. Promotion lines must allow negative `precioUnitario`.

- [ ] **Step 3: Ignore special lines in stock**

In `InventoryDocumentGateway`, filter lines:

```java
document.getLineas().stream()
        .filter(line -> line.getLineType() == DocumentLineType.PRODUCT)
```

- [ ] **Step 4: Include line type in fiscal snapshot**

In `FiscalSnapshotFactory`, add:

```java
value.put("tipoLinea", line.getLineType().name());
value.put("promocionId", line.getPromotionId());
value.put("cuponPromocionalId", line.getPromotionalCouponId());
```

- [ ] **Step 5: Run focused tests**

```powershell
.\mvnw.cmd "-Dtest=DocumentPromotionLineTest,DocumentServiceTest,FiscalSnapshotFactoryTest" test
```

Expected: PASS. If `FiscalSnapshotFactoryTest` does not exist, run `DocumentServiceTest` and Verifactu document tests already present.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/document backend/src/main/java/com/tpverp/backend/inventory backend/src/main/java/com/tpverp/backend/verifactu backend/src/test/java/com/tpverp/backend/document/DocumentPromotionLineTest.java
git commit -m "feat: support promotion document lines"
```

---

### Task 4: Promotion Engine

**Files:**
- Create: `PromotionEngine.java`, calculation DTOs in `com.tpverp.backend.promotion`
- Modify: `PromotionRepository.java`, `ProductRepository.java` if a missing query is needed.
- Test: `backend/src/test/java/com/tpverp/backend/promotion/PromotionEngineTest.java`

- [ ] **Step 1: Write failing engine tests**

Cover:

```java
@Test
void buyXPayYDiscountsCheapestEligibleItem() {
    var result = engine.preview(commandWithLines(
            line(productA, "2.00"),
            line(productB, "1.00"),
            line(productC, "3.00")),
            activeBuy3Pay2PromotionForProducts(productA, productB, productC));
    assertThat(result.discountTotal()).isEqualByComparingTo("1.00");
}

@Test
void conflictingPromotionsKeepBestCustomerBenefit() {
    var result = engine.preview(commandWithThreeSameProducts(),
            List.of(activeBuy3Pay2(), activeSecondUnitPercent("50")));
    assertThat(result.appliedPromotions()).hasSize(1);
    assertThat(result.discountTotal()).isEqualByComparingTo("1.00");
}

@Test
void nonConflictingPromotionsAccumulate() {
    var result = engine.preview(commandWithWaterAndMilk(),
            List.of(activeBuy3Pay2Water(), activeSecondUnitPercentMilk("50")));
    assertThat(result.appliedPromotions()).hasSize(2);
}
```

- [ ] **Step 2: Implement `PromotionEngine` public API**

```java
@Service
public class PromotionEngine {

    public PromotionPreview preview(PromotionEvaluationRequest request) {
        var candidates = eligiblePromotions(request);
        var productBenefits = bestNonConflictingProductBenefits(request, candidates);
        var couponBenefit = validateCoupon(request);
        return PromotionPreview.from(productBenefits, couponBenefit);
    }

    public List<DocumentLine> promotionLines(CommercialDocument document, PromotionPreview preview) {
        return PromotionLineFactory.lines(document, preview);
    }
}
```

Use simple in-memory grouping. Do not add a rules engine dependency.

- [ ] **Step 3: Implement conflict rule**

Represent each benefit with affected source line ids or positions:

```java
record PromotionBenefit(UUID promotionId, Set<Integer> affectedPositions, BigDecimal amount) {
    boolean conflictsWith(PromotionBenefit other) {
        return affectedPositions.stream().anyMatch(other.affectedPositions::contains);
    }
}
```

Sort benefits by amount descending and keep the first benefit that does not conflict with already selected benefits.

- [ ] **Step 4: Run tests**

```powershell
.\mvnw.cmd "-Dtest=PromotionEngineTest" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/promotion backend/src/test/java/com/tpverp/backend/promotion/PromotionEngineTest.java
git commit -m "feat: calculate promotion discounts"
```

---

### Task 5: Promotion CRUD API

**Files:**
- Create/modify: `PromotionService.java`, `PromotionController.java`
- Test: `backend/src/test/java/com/tpverp/backend/promotion/PromotionServiceTest.java`
- Test: `backend/src/test/java/com/tpverp/backend/promotion/PromotionControllerContractTest.java`

- [ ] **Step 1: Write failing service tests**

Cover:

```java
@Test
void activeUsedPromotionIsVersionedInsteadOfEdited() {
    var original = savedUsedPromotion();
    var draft = service.duplicate(original.getId());
    service.activate(draft.id());
    assertThat(repository.findById(original.getId()).orElseThrow().getStatus())
            .isEqualTo(PromotionStatus.INACTIVE);
}

@Test
void draftPromotionCanBeDeletedWhenUnused() {
    var draft = savedDraftPromotion();
    service.delete(draft.getId());
    assertThat(repository.findById(draft.getId())).isEmpty();
}
```

- [ ] **Step 2: Add controller endpoints**

Use `/api/v1/promotions`:

```java
@GetMapping
@PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
public List<PromotionView> list() { return service.list(); }

@PostMapping
@PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
public PromotionView create(@Valid @RequestBody PromotionRequest request) { return service.create(request); }

@PostMapping("/{id}/duplicate")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
public PromotionView duplicate(@PathVariable UUID id) { return service.duplicate(id); }

@PostMapping("/{id}/activate")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
public PromotionView activate(@PathVariable UUID id) { return service.activate(id); }
```

- [ ] **Step 3: Run tests**

```powershell
.\mvnw.cmd "-Dtest=PromotionServiceTest,PromotionControllerContractTest" test
```

Expected: PASS.

- [ ] **Step 4: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/promotion backend/src/test/java/com/tpverp/backend/promotion
git commit -m "feat: add promotion management api"
```

---

### Task 6: Promotional Coupon Service

**Files:**
- Create/modify: `PromotionalCouponService.java`, `PromotionalCouponController.java`
- Test: `backend/src/test/java/com/tpverp/backend/promotion/PromotionalCouponServiceTest.java`

- [ ] **Step 1: Write failing coupon tests**

Cover:

```java
@Test
void generatedCodeIsRandomAndOnlyHashIsStored() {
    var issued = service.issueFromDocument(document, promotion);
    assertThat(issued.code()).startsWith("PROMO-");
    assertThat(repository.findByCodeHash(hasher.hash(issued.code()))).isPresent();
    assertThat(repository.findAll().getFirst().getCodeLast4()).hasSize(4);
}

@Test
void couponIsConsumedCompletelyWithoutRemainingBalance() {
    var coupon = activeAmountCoupon("5.00");
    var result = service.validateForDocument(coupon.fullCode(), saleWithTotal("4.00"));
    assertThat(result.discount()).isEqualByComparingTo("4.00");
    service.consume(coupon.fullCode(), confirmedDocument);
    assertThat(couponRepository.findById(coupon.id()).orElseThrow().getStatus())
            .isEqualTo(PromotionalCouponStatus.USED);
}
```

- [ ] **Step 2: Add secure code generator**

```java
@Component
public class PromotionalCouponCodeGenerator {
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        var value = new StringBuilder("PROMO-");
        for (int i = 0; i < 12; i++) {
            value.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return value.toString();
    }
}
```

- [ ] **Step 3: Add SHA-256 hasher**

```java
@Component
public class PromotionalCouponCodeHasher {
    public String hash(String code) {
        var digest = MessageDigest.getInstance("SHA-256").digest(normalize(code).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    public String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }
}
```

- [ ] **Step 4: Add validation attempt persistence**

On every rejected validation, save `PromotionalCouponAttempt` with reason and last 4 characters only.

- [ ] **Step 5: Run tests**

```powershell
.\mvnw.cmd "-Dtest=PromotionalCouponServiceTest" test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/promotion backend/src/test/java/com/tpverp/backend/promotion/PromotionalCouponServiceTest.java
git commit -m "feat: add promotional coupons"
```

---

### Task 7: Integrate Promotions Into Document Confirmation

**Files:**
- Modify: `DocumentService.java`
- Test: `backend/src/test/java/com/tpverp/backend/document/DocumentPromotionIntegrationTest.java`

- [ ] **Step 1: Write failing integration tests**

Cover:

```java
@Test
void ticketAppliesProductPromotionBeforePaymentValidation() {
    var command = ticketWithThreeWatersTotal("3.00");
    var payment = cashPayment("2.00");
    var ticket = service.createTicket(command, List.of(payment), authentication);
    assertThat(ticket.getTotal()).isEqualByComparingTo("2.00");
    assertThat(ticket.getLineas()).anyMatch(line -> line.getLineType() == DocumentLineType.PROMOTION);
}

@Test
void couponIsGeneratedAfterConfirmedDocumentMeetsThreshold() {
    var ticket = service.createTicket(ticketAboveThreshold(), List.of(cashPayment("50.00")), authentication);
    verify(promotionalCoupons).issueFromDocument(ticket);
}
```

- [ ] **Step 2: Inject services**

In `DocumentService` constructor:

```java
private final PromotionEngine promotions;
private final PromotionalCouponService promotionalCoupons;
```

- [ ] **Step 3: Apply promotion lines inside `createDraft`**

After normal product lines are added:

```java
if (isSalesDocument(document.getTipo())) {
    var preview = promotions.preview(PromotionEvaluationRequest.from(document, command.clienteId()));
    promotions.promotionLines(document, preview).forEach(document::addLine);
}
```

- [ ] **Step 4: Generate coupons after confirmed save**

After `documents.save(...)` in ticket and confirm flows:

```java
promotionalCoupons.issueFromDocument(saved);
```

Only issue from `TICKET`, `FACTURA_VENTA`, `ALBARAN_VENTA`.

- [ ] **Step 5: Validate payment total against promoted total**

No extra code if promotion lines are added before `requirePaymentTotal`; existing payment validation will use `ticket.getTotal()`.

- [ ] **Step 6: Run tests**

```powershell
.\mvnw.cmd "-Dtest=DocumentPromotionIntegrationTest,DocumentServiceTest" test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/document/DocumentService.java backend/src/test/java/com/tpverp/backend/document/DocumentPromotionIntegrationTest.java
git commit -m "feat: apply promotions to sales documents"
```

---

### Task 8: Preview And Coupon APIs For APP VENTA

**Files:**
- Create/modify: `PromotionController.java`, `PromotionalCouponController.java`
- Test: `backend/src/test/java/com/tpverp/backend/promotion/PromotionPreviewApiTest.java`

- [ ] **Step 1: Add preview endpoint**

```java
@PostMapping("/preview")
@PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA')")
public PromotionPreviewView preview(@Valid @RequestBody DocumentRequest request) {
    return service.preview(request);
}
```

- [ ] **Step 2: Add coupon validation endpoint**

```java
@PostMapping("/coupons/validate")
@PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA')")
public PromotionalCouponValidationView validate(@Valid @RequestBody CouponValidationRequest request) {
    return coupons.validate(request);
}
```

- [ ] **Step 3: Add admin coupon endpoints**

Use `/api/v1/promotional-coupons`:

```java
@GetMapping
@PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
public List<PromotionalCouponView> list() { return coupons.list(); }

@PostMapping("/{id}/cancel")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
public PromotionalCouponView cancel(@PathVariable UUID id, @RequestBody CouponStateRequest request) {
    return coupons.cancel(id, request.reason());
}

@PostMapping("/{id}/reactivate")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
public PromotionalCouponView reactivate(@PathVariable UUID id, @RequestBody CouponStateRequest request) {
    return coupons.reactivate(id, request.reason());
}
```

- [ ] **Step 4: Run API tests**

```powershell
.\mvnw.cmd "-Dtest=PromotionPreviewApiTest,PromotionControllerContractTest" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/tpverp/backend/promotion backend/src/test/java/com/tpverp/backend/promotion
git commit -m "feat: expose promotion preview and coupon api"
```

---

### Task 9: Frontend APP GESTION Promotion Wizard

**Files:**
- Create: `frontend/packages/app-common/src/components/PromotionWizard.tsx`
- Create: `frontend/packages/app-common/src/components/PromotionListScreen.tsx`
- Modify: frontend API and i18n files.
- Test: `frontend/packages/app-common/src/components/PromotionWizard.test.tsx`

- [ ] **Step 1: Write focused UI tests**

Test that wizard renders steps and submits draft:

```tsx
it("creates a draft promotion from wizard fields", async () => {
  render(<PromotionWizard api={fakeApi} />);
  await user.type(screen.getByLabelText("Nombre"), "3x2 Agua");
  await user.click(screen.getByText("Siguiente"));
  await user.click(screen.getByText("3x2 / 2x1"));
  await user.click(screen.getByText("Guardar borrador"));
  expect(fakeApi.createPromotion).toHaveBeenCalledWith(expect.objectContaining({
    name: "3x2 Agua",
    type: "BUY_X_PAY_Y",
    status: "DRAFT",
  }));
});
```

- [ ] **Step 2: Implement wizard minimally**

Use existing form styling. Steps:

1. Basic data.
2. Promotion type.
3. Scope.
4. Conditions.
5. Benefit.
6. Coupon.
7. Summary.

- [ ] **Step 3: Add list actions**

Actions:

- create;
- edit draft/inactive unused;
- duplicate;
- activate;
- deactivate;
- delete unused draft/inactive.

- [ ] **Step 4: Run frontend tests**

```powershell
cd frontend
npm test -- PromotionWizard.test.tsx
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add frontend/packages/app-common/src/components/PromotionWizard.tsx frontend/packages/app-common/src/components/PromotionWizard.test.tsx frontend/packages/app-common/src/components/PromotionListScreen.tsx frontend/packages/app-common/src/i18n frontend/packages/app-common/src/api
git commit -m "feat: add promotion management ui"
```

---

### Task 10: Frontend APP VENTA Promotion Preview

**Files:**
- Modify current sale document/ticket components.
- Create if needed: `frontend/packages/app-common/src/components/PromotionPreviewPanel.tsx`
- Test: `frontend/packages/app-common/src/components/PromotionPreviewPanel.test.tsx`

- [ ] **Step 1: Write UI test**

```tsx
it("shows applied promotions and generated coupon after confirmation", () => {
  render(<PromotionPreviewPanel preview={{
    applied: [{ name: "PROMOCION 3x2 Agua", amount: "-1.00" }],
    generatedCoupon: { code: "PROMO-K7M9Q2XR8T5A", amount: "5.00" },
  }} />);
  expect(screen.getByText("PROMOCION 3x2 Agua")).toBeInTheDocument();
  expect(screen.getByText("PROMO-K7M9Q2XR8T5A")).toBeInTheDocument();
});
```

- [ ] **Step 2: Call preview when sale changes**

Trigger preview on:

- product added;
- product removed;
- quantity changed;
- customer changed;
- coupon code changed.

- [ ] **Step 3: Show final backend result after confirmation**

After ticket/factura/albaran confirmation response, show:

- applied promotion lines;
- coupon used;
- generated coupon;
- button to reprint current generated coupon if print fails.

- [ ] **Step 4: Run frontend tests**

```powershell
cd frontend
npm test -- PromotionPreviewPanel.test.tsx
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add frontend/packages/app-common/src/components frontend/packages/app-common/src/i18n frontend/packages/app-common/src/api
git commit -m "feat: preview promotions in sales"
```

---

### Task 11: Final Backend Verification

**Files:**
- No new files unless tests expose gaps.

- [ ] **Step 1: Run focused backend suite**

```powershell
cd backend
.\mvnw.cmd "-Dtest=MigrationV44ContractTest,PromotionDomainTest,PromotionEngineTest,PromotionalCouponServiceTest,PromotionServiceTest,PromotionPreviewApiTest,DocumentPromotionLineTest,DocumentPromotionIntegrationTest,DocumentServiceTest" test
```

Expected: PASS.

- [ ] **Step 2: Run full backend tests if focused suite passes**

```powershell
.\mvnw.cmd test
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit fixes only if needed**

```powershell
git status --short
git add backend/src/main/java/com/tpverp/backend/promotion backend/src/main/java/com/tpverp/backend/document backend/src/main/java/com/tpverp/backend/inventory backend/src/main/java/com/tpverp/backend/verifactu backend/src/test/java/com/tpverp/backend/promotion backend/src/test/java/com/tpverp/backend/document
git commit -m "test: verify promotions flow"
```

Only create this commit if Step 1 or Step 2 required fixes.

---

### Task 12: Final Frontend Verification

**Files:**
- No new files unless tests expose gaps.

- [ ] **Step 1: Run frontend tests**

```powershell
cd frontend
npm test
```

Expected: PASS.

- [ ] **Step 2: Run frontend build**

```powershell
npm run build
```

Expected: build completes without TypeScript errors.

- [ ] **Step 3: Commit fixes only if needed**

```powershell
git status --short
git add frontend/packages/app-common/src/api frontend/packages/app-common/src/components frontend/packages/app-common/src/i18n frontend/packages/app-common/src/styles/tpv.css
git commit -m "test: verify promotions ui"
```

Only create this commit if Step 1 or Step 2 required fixes.

---

## Self-Review

Spec coverage:

- Promotion types: Tasks 1, 2, 4.
- Product/family/subfamily/list targeting: Tasks 1, 4, 9.
- Customer/member segmentation: Tasks 1, 4, 9.
- Accumulation/conflict rules: Task 4.
- Promotion negative lines and tax split: Tasks 3, 4, 7.
- Coupon generation, secure code, states, attempts: Tasks 1, 2, 6.
- Document integration for tickets/facturas/albaranes: Task 7.
- APP GESTION wizard: Task 9.
- APP VENTA preview: Task 10.
- Fiscal snapshot and no stock impact: Task 3.
- Permissions: Tasks 5, 8.
- Verification: Tasks 11, 12.

Known implementation constraint:

- This plan keeps the first backend implementation simple: no external rules engine, no payment-method promotions, no per-store promotion targeting, no partial coupon balance.
