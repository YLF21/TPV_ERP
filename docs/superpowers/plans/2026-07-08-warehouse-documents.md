# Warehouse Documents Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add creation and confirmation of warehouse input/output documents from `SalesReportScreen`, including Excel import, backend persistence, and stock movements.

**Architecture:** Reuse the existing `WarehouseOutput` backend for output documents. Add a symmetric `WarehouseInput` backend and database model for input documents. Keep the frontend modal in a separate component so `SalesReportScreen` only controls visibility, data loading, and refresh.

**Tech Stack:** Spring Boot, JPA, Flyway, JUnit, React, TypeScript, Vitest, CSS.

---

## File Structure

- Backend input document model in `backend/src/main/java/com/tpverp/backend/inventory/`.
- Flyway migration in `backend/src/main/resources/db/migration/`.
- Backend tests in `backend/src/test/java/com/tpverp/backend/inventory/`.
- Frontend modal component in `frontend/packages/app-common/src/components/WarehouseDocumentDialog.tsx`.
- Frontend parser/helper in `frontend/packages/app-common/src/components/warehouseDocumentImport.ts`.
- Frontend tests beside the component and helper.
- `SalesReportScreen.tsx` changes limited to loading `/warehouse-inputs`, showing the create button, opening the dialog, and refreshing data.
- `tpv.css` changes scoped to `.warehouse-document-*` and existing `.report-screen` styling.

## Task 1: Backend Warehouse Input Contract

**Files:**
- Test: `backend/src/test/java/com/tpverp/backend/inventory/WarehouseInputServiceTest.java`
- Create: `backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputStatus.java`
- Create: `backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputLineCommand.java`
- Create: `backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputCommand.java`
- Create: `backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputLine.java`
- Create: `backend/src/main/java/com/tpverp/backend/inventory/WarehouseInput.java`
- Create: `backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputRepository.java`
- Create: `backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputLineRepository.java`
- Create: `backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputService.java`

- [ ] **Step 1: Write failing service tests**

Write tests that create an input draft, confirm it, assert positive stock movement, and assert double confirmation is rejected.

- [ ] **Step 2: Run test to verify RED**

Run: `backend\mvnw.cmd -pl backend -Dtest=WarehouseInputServiceTest test`
Expected: FAIL because warehouse input classes do not exist.

- [ ] **Step 3: Implement model/service**

Implement the input document model mirroring `WarehouseOutput`, with provider/origin fields and positive stock movement on confirm.

- [ ] **Step 4: Run test to verify GREEN**

Run: `backend\mvnw.cmd -pl backend -Dtest=WarehouseInputServiceTest test`
Expected: PASS.

## Task 2: Backend API and Migration

**Files:**
- Test: `backend/src/test/java/com/tpverp/backend/inventory/WarehouseInputControllerContractTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/inventory/StockMovementType.java`
- Modify: `backend/src/main/java/com/tpverp/backend/inventory/StockMovement.java`
- Modify: `backend/src/main/java/com/tpverp/backend/inventory/StockMovementSyncPublisher.java`
- Create: `backend/src/main/java/com/tpverp/backend/inventory/WarehouseInputController.java`
- Create: `backend/src/main/resources/db/migration/V42__entrada_almacen_documentos.sql`

- [ ] **Step 1: Write failing controller contract test**

Assert `/api/v1/warehouse-inputs` supports list, create, update, delete, and confirm mappings.

- [ ] **Step 2: Run test to verify RED**

Run: `backend\mvnw.cmd -pl backend -Dtest=WarehouseInputControllerContractTest test`
Expected: FAIL because controller and mapping do not exist.

- [ ] **Step 3: Add API and DB migration**

Add `ENTRADA_ALMACEN`, `entrada_almacen_id`, the controller, and migration tables.

- [ ] **Step 4: Run backend target tests**

Run: `backend\mvnw.cmd -pl backend -Dtest=WarehouseInputServiceTest,WarehouseInputControllerContractTest test`
Expected: PASS.

## Task 3: Frontend Import Helper

**Files:**
- Test: `frontend/packages/app-common/src/components/warehouseDocumentImport.test.ts`
- Create: `frontend/packages/app-common/src/components/warehouseDocumentImport.ts`

- [ ] **Step 1: Write failing parser tests**

Cover product lookup by code/reference/barcode/name, quantity validation, and unknown product errors.

- [ ] **Step 2: Run test to verify RED**

Run from `frontend`: `npm test -- warehouseDocumentImport.test.ts --run`
Expected: FAIL because helper does not exist.

- [ ] **Step 3: Implement helper**

Implement row normalization and matching against existing products. Keep it independent of browser file APIs so it is easy to test.

- [ ] **Step 4: Run helper test**

Run from `frontend`: `npm test -- warehouseDocumentImport.test.ts --run`
Expected: PASS.

## Task 4: Frontend Warehouse Document Dialog

**Files:**
- Test: `frontend/packages/app-common/src/components/WarehouseDocumentDialog.test.tsx`
- Create: `frontend/packages/app-common/src/components/WarehouseDocumentDialog.tsx`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEs.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesEn.ts`
- Modify: `frontend/packages/app-common/src/i18n/MessagesZh.ts`

- [ ] **Step 1: Write failing dialog tests**

Assert the dialog renders output mode with cliente/destino, input mode with proveedor/origen, blocks invalid lines, and calls create then confirm.

- [ ] **Step 2: Run test to verify RED**

Run from `frontend`: `npm test -- WarehouseDocumentDialog.test.tsx --run`
Expected: FAIL because component does not exist.

- [ ] **Step 3: Implement dialog**

Use dense formal report styling, visible labels, keyboard-friendly controls, async submit feedback, and a horizontally scrollable line table.

- [ ] **Step 4: Run dialog test**

Run from `frontend`: `npm test -- WarehouseDocumentDialog.test.tsx --run`
Expected: PASS.

## Task 5: Wire SalesReportScreen

**Files:**
- Test: `frontend/packages/app-common/src/components/SalesReportScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SalesReportScreen.tsx`
- Modify: `frontend/packages/app-common/src/index.ts`

- [ ] **Step 1: Add failing report tests**

Assert `Crear documento` appears only for `Salida almacen` and `Entrada almacen`, opens the dialog with the right mode, and reloads data after confirm.

- [ ] **Step 2: Run test to verify RED**

Run from `frontend`: `npm test -- SalesReportScreen.test.tsx --run`
Expected: FAIL because the button/dialog wiring does not exist.

- [ ] **Step 3: Wire data and dialog**

Load `/warehouse-inputs`, map input document rows, add the button to the report toolbar, and refresh remote reports after confirm.

- [ ] **Step 4: Run report test**

Run from `frontend`: `npm test -- SalesReportScreen.test.tsx --run`
Expected: PASS.

## Task 6: Verification

**Files:** all changed files.

- [ ] **Step 1: Backend targeted verification**

Run: `backend\mvnw.cmd -pl backend -Dtest=WarehouseInputServiceTest,WarehouseInputControllerContractTest,WarehouseOutputServiceTest test`
Expected: PASS.

- [ ] **Step 2: Frontend targeted verification**

Run from `frontend`: `npm test -- warehouseDocumentImport.test.ts WarehouseDocumentDialog.test.tsx SalesReportScreen.test.tsx --run`
Expected: PASS.

- [ ] **Step 3: Static checks**

Run from `frontend`: `npm run build`
Expected: PASS.

- [ ] **Step 4: Review diff**

Run: `git diff --check`
Expected: no whitespace errors.
