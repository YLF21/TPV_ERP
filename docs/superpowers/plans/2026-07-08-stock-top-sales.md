# Stock Top Sales Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `Top ventas` inside APP VENTA `StockScreen`, backed by a stock endpoint that returns product rankings by net sold units for a moving date range.

**Architecture:** Add a backend `StockTopSalesService` in the inventory module that calculates ranges, aggregates sale document lines, excludes zero-or-negative net unit rows, and enriches rows with catalog/supplier/current-stock context. Expose it through `StockController` at `/api/v1/stock/top-sales`. Extend `StockScreen` to load top-sales data by period/date and apply family, subfamily, supplier, and text filters locally.

**Tech Stack:** Spring Boot, JPA repositories, Mockito/JUnit backend tests, React/TypeScript, Vitest static-render and helper tests, existing `tpv.css`.

---

### Task 1: Backend Service And Endpoint

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/inventory/StockTopSalesPeriod.java`
- Create: `backend/src/main/java/com/tpverp/backend/inventory/StockTopSalesSupplierView.java`
- Create: `backend/src/main/java/com/tpverp/backend/inventory/StockTopSalesRow.java`
- Create: `backend/src/main/java/com/tpverp/backend/inventory/StockTopSalesService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/CommercialDocumentRepository.java`
- Modify: `backend/src/main/java/com/tpverp/backend/inventory/StockLevelRepository.java`
- Modify: `backend/src/main/java/com/tpverp/backend/inventory/StockController.java`
- Test: `backend/src/test/java/com/tpverp/backend/inventory/StockTopSalesServiceTest.java`
- Test: `backend/src/test/java/com/tpverp/backend/inventory/StockControllerContractTest.java`

- [ ] Write failing service tests for moving ranges, net-unit aggregation, excluding draft/cancelled documents via repository input, excluding zero-net rows, and sorting by net units.
- [ ] Run `backend/mvnw.cmd -pl backend -Dtest=StockTopSalesServiceTest test` and confirm failure because the service does not exist.
- [ ] Add the period enum and response records.
- [ ] Add repository helpers: fetch current-store sale documents by date range with lines, and sum current stock by product.
- [ ] Implement `StockTopSalesService` with frontend-free filters: it accepts only period/date, aggregates all rows for the range, and returns enriched rows.
- [ ] Add `GET /top-sales` to `StockController` with the same read permissions as stock listing.
- [ ] Update the controller contract test to assert the endpoint mapping and method security.
- [ ] Run `backend/mvnw.cmd -pl backend -Dtest=StockTopSalesServiceTest,StockControllerContractTest test` and confirm pass.

### Task 2: Frontend Top-Sales Helpers

**Files:**
- Modify: `frontend/packages/app-common/src/components/StockScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/StockScreen.test.tsx`

- [ ] Write failing tests for local top-sales filtering by family, subfamily, supplier, and text.
- [ ] Write a failing test that `StockScreen` renders `Top ventas`, `Semana`, date controls, and top-sales columns by default.
- [ ] Run `npm test -- StockScreen.test.tsx --run` and confirm failure.
- [ ] Add TypeScript types for top-sales rows and suppliers.
- [ ] Add helper functions for ISO date defaults, period labels, and local filtering.
- [ ] Update `StockScreen` default view to `stock.topSales`.
- [ ] Load `/stock/top-sales?period=week&date=<date>` for the selected date and period.
- [ ] Render the top-sales toolbar, local filters, and ranking table.
- [ ] Run `npm test -- StockScreen.test.tsx --run` and confirm pass.

### Task 3: Styling And Verification

**Files:**
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

- [ ] Add compact styles for the top-sales controls and table within the existing stock/work-screen visual language.
- [ ] Run `npm test -- --run`.
- [ ] Run `npm run build`.
- [ ] Run `backend/mvnw.cmd -pl backend -Dtest=StockTopSalesServiceTest,StockControllerContractTest test`.
- [ ] Run `git diff --check`.
