# Warehouse Document Printing Implementation Plan

> **For Codex:** Execute this plan locally without committing unless the user explicitly requests it.

**Goal:** Replace whole-application browser printing in warehouse documents with a clean A4 document, desktop printer integration, and a browser preview fallback.

**Architecture:** Build a pure A4 print request from the warehouse draft, then send it through the existing hardware bridge using the configured `REPORT` print route. In browser development, render the same request into an isolated printable HTML window so the application chrome and scrollable editor are never printed.

**Tech Stack:** React, TypeScript, Vitest, existing Electron hardware bridge.

---

### Task 1: Define and test the printable warehouse document

**Files:**
- Create: `frontend/packages/app-common/src/warehouse/warehouseDocumentPrinting.ts`
- Create: `frontend/packages/app-common/src/warehouse/warehouseDocumentPrinting.test.ts`

- [ ] Write failing tests for headings, warehouse/partner metadata, lines and totals.
- [ ] Implement the pure A4 request builder.
- [ ] Implement isolated HTML preview rendering with escaped content.
- [ ] Run the focused unit tests.

### Task 2: Integrate print and preview in the warehouse editor

**Files:**
- Modify: `frontend/packages/app-common/src/components/WarehouseDocumentDialog.tsx`
- Modify: `frontend/packages/app-common/src/components/WarehouseDocumentDialog.test.tsx`

- [ ] Write failing component tests for printer bridge and preview actions.
- [ ] Replace `window.print()` with the new A4 printing workflow.
- [ ] Show localized success/error status and prevent duplicate print actions.
- [ ] Run the focused component tests.

### Task 3: Localize and verify

**Files:**
- Modify: relevant files under `frontend/packages/app-common/src/i18n/`

- [ ] Add Spanish, English and Chinese print/preview messages.
- [ ] Run affected unit tests.
- [ ] Run the app-common or frontend build/typecheck.
- [ ] Inspect the final diff and preserve unrelated local changes.
