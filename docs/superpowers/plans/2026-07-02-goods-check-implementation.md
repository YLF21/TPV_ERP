# Goods Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build backend goods-check support for purchase delivery notes and purchase invoices.

**Architecture:** Add a small `goodscheck` package with JPA entities, repository, service, controller, and one Flyway migration. Reuse existing document/product identifiers and existing product edit endpoints.

**Tech Stack:** Java 25, Spring Boot, JPA, Flyway, PostgreSQL, MockMvc/JUnit.

---

### Task 1: Schema And Domain

**Files:**
- Create: `backend/src/main/resources/db/migration/V28__comprobacion_mercancia.sql`
- Create: `backend/src/main/java/com/tpverp/backend/goodscheck/GoodsCheckStatus.java`
- Create: `backend/src/main/java/com/tpverp/backend/goodscheck/GoodsCheck.java`
- Create: `backend/src/main/java/com/tpverp/backend/goodscheck/GoodsCheckLine.java`
- Create: `backend/src/main/java/com/tpverp/backend/goodscheck/GoodsCheckRepository.java`

- [ ] Add tables `comprobacion_mercancia` and `comprobacion_mercancia_linea`.
- [ ] Add unique partial index for one open check per document.
- [ ] Add entities with methods to add registered quantity and close.
- [ ] Test negative total is rejected.
- [ ] Commit: `feat: add goods check domain`

### Task 2: Service

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/goodscheck/GoodsCheckService.java`
- Create: `backend/src/main/java/com/tpverp/backend/goodscheck/GoodsCheckView.java`
- Create: `backend/src/main/java/com/tpverp/backend/goodscheck/GoodsCheckScanRequest.java`

- [ ] Start only from confirmed `ALBARAN_COMPRA` or `FACTURA_COMPRA`.
- [ ] Build expected quantities grouped by product from document lines.
- [ ] Scan only by product id/code/barcode present in the document.
- [ ] Return `todos`, `faltantes`, `registrados`.
- [ ] Close as `COMPLETA` or `CON_DIFERENCIAS`.
- [ ] Publish sync event `GOODS_CHECK`.
- [ ] Test main service rules.
- [ ] Commit: `feat: add goods check service`

### Task 3: API

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/goodscheck/GoodsCheckController.java`
- Create: `backend/src/test/java/com/tpverp/backend/goodscheck/GoodsCheckControllerContractTest.java`

- [ ] Add endpoints under `/api/v1/goods-checks`.
- [ ] Secure endpoints for `ADMIN`, `GESTION_PRODUCTO`, `DELIVERY_NOTES_READ`, `DELIVERY_NOTES_WRITE`, `INVOICES_READ`, `INVOICES_WRITE`.
- [ ] Add compact MockMvc authorization test.
- [ ] Commit: `feat: expose goods check api`

### Task 4: Verify

- [ ] Run: `.\mvnw.cmd "-Dtest=GoodsCheck*Test,TpvErpBackendApplicationTests" test`
- [ ] Run migration contract if needed.
- [ ] Merge to `main` only after tests pass.
