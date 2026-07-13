# Cash Payment Calculator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Quote and persist a complete cash POS ticket from a calculator dialog.

**Architecture:** A focused POS cash service builds authoritative document commands from product IDs, current store catalog, default warehouse and customer. Quote and submit endpoints share that builder; submit delegates final confirmation/payment/stock/cash movement to `DocumentService`. Frontend calculator works in integer cents and retains checkout state on failure.

**Tech Stack:** Spring Boot 4, JPA/PostgreSQL, React 19, TypeScript, Vitest.

## Constraints

- Local changes only; no stage, commit or push.
- Backend owns product, price, tax, member benefit and final total.
- Cash submit is idempotent by terminal plus checkout key.
- Ticket state is cleared only after confirmed success.

### Task 1: Calculator domain and dialog

**Files:**
- Create: `frontend/packages/app-common/src/sale/cashCalculator.ts`
- Create: `frontend/packages/app-common/src/sale/cashCalculator.test.ts`
- Create: `frontend/packages/app-common/src/components/CashPaymentDialog.tsx`
- Create: `frontend/packages/app-common/src/components/CashPaymentDialog.test.tsx`

- [ ] Write failing cent arithmetic/keypad tests and verify RED.
- [ ] Implement input, shortcuts, change and validation; verify GREEN.
- [ ] Write failing interaction/accessibility tests and verify RED.
- [ ] Implement the modal with disabled double submission and verify GREEN.

### Task 2: Authoritative cash POS API

**Files:**
- Create: `backend/src/main/resources/db/migration/V45__pos_cash_checkout.sql`
- Create: `backend/src/main/java/com/tpverp/backend/document/PosCashCheckout.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/PosCashCheckoutRepository.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/PosCashService.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/PosCashController.java`
- Create: `backend/src/test/java/com/tpverp/backend/document/PosCashControllerContractTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/PaymentMethodRepository.java`

- [ ] Write failing endpoint/security contract tests and verify RED.
- [ ] Add terminal-scoped checkout idempotency migration/entity.
- [ ] Implement shared command builder from current store products/taxes/default warehouse.
- [ ] Add read-only ticket quote to `DocumentService` using the same member/promotion logic as create.
- [ ] Implement quote and cash-submit endpoints; resolve EFECTIVO server-side and delegate final persistence to `createTicket`.
- [ ] Verify endpoint tests and backend compilation.

### Task 3: SaleScreen integration and verification

**Files:**
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

- [ ] Write failing SaleScreen tests for opening quote, successful clearing and failed-state preservation.
- [ ] Wire Efectivo to quote, calculator and submit with stable checkout key.
- [ ] Run focused tests and build.
- [ ] Start backend/frontend, create a real cash ticket and verify document/payment/cash movement in PostgreSQL.
- [ ] Run `git diff --check`; do not commit or push.
