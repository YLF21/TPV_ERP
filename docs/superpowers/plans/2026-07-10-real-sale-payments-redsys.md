# Real Sale Payments and Redsys TPV-PC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist idempotent POS tickets with mixed cash, integrated Redsys card, other-method and customer-receivable amounts.

**Architecture:** Add authoritative `/pos/context`, `/pos/quotes` and `/pos/tickets` boundaries so every authorization uses a server-calculated, versioned total. Persist Redsys operations separately from physical terminals and consume an approved operation through a unique FK. Represent customer-pending amounts as accounts receivable, never as collected payments.

**Tech Stack:** Java 25, Spring Boot 4, Spring Data JPA, Flyway, PostgreSQL, React 19, TypeScript, Vitest and Testing Library.

## Global Constraints

- Work only in the current checkout; do not stage, commit or push.
- Never capture or store PAN, expiry or CVV.
- Keep `terminal_cobro_id` as the physical terminal FK; use `operacion_datafono_id` for the charge operation.
- Use integer cents in browser logic and two-decimal EUR strings on the wire.
- TEST is available only in the `dev` profile; only ADMIN selects a simulated result.
- LIVE remains disabled until the official Redsys protocol, endpoint and protected secret are available.
- A failed or repeated request must not duplicate a ticket, charge or receivable.
- `SENT/UNKNOWN` blocks a second authorization until reconciled; only a confirmed final rejection permits a new attempt.

---

### Task 1: Frontend payment domain

**Files:**
- Create: `frontend/packages/app-common/src/sale/payment.ts`
- Create: `frontend/packages/app-common/src/sale/payment.test.ts`

**Interfaces:**
- Produces `PaymentAllocation`, `paymentSummary`, allocation helpers and `buildPosTicketRequest`.
- Produces stable `authorizationAttemptId` per card allocation and separate quote/ticket request builders.
- Quote request is `{ checkoutId, customerId, lines }`; ticket request is `{ checkoutId, quoteId, quoteHash, payments, customerPendingAmount }`.

- [ ] Write failing tests for cash change, mixed payments, over-allocation, zero/negative values, two-decimal validation and remaining amount.
- [ ] Run `npm.cmd test -- payment.test.ts` and verify failure because the module is absent.
- [ ] Implement cent-based arithmetic. Round discounted totals per line using the same rule as backend; cover `0.1 + 0.2`, quantity and `33.33%`.
- [ ] Write failing quote tests proving lines contain only `productId`, `quantity` and `discount`, and ticket tests proving cash sends delivered/change, card sends only `paymentTerminalOperationId`, VALE sends `voucherCode`, pending stays outside payments and no `principal` flag is sent.
- [ ] Implement quote/ticket builders, serialize amounts as decimal EUR strings, preserve `authorizationAttemptId` across technical retries and never send price/tax/name/code snapshots.
- [ ] Run the focused tests and verify GREEN.

---

### Task 2: Database contracts for operations, checkout and receivables

**Files:**
- Create: `backend/src/main/resources/db/migration/V45__cobro_pos_redsys.sql`
- Create: `backend/src/test/java/com/tpverp/backend/persistence/MigrationV45ContractTest.java`
- Create: `backend/src/main/java/com/tpverp/backend/terminal/PaymentTerminalOperation.java`
- Create: `backend/src/main/java/com/tpverp/backend/terminal/PaymentTerminalOperationRepository.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/PosCheckout.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/PosCheckoutRepository.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/CustomerReceivable.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/CustomerReceivableRepository.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/PosQuote.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/PosQuoteRepository.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/PaymentMethod.java`
- Modify: `backend/src/main/java/com/tpverp/backend/terminal/TerminalPaymentConfiguration.java`
- Modify: `backend/src/main/java/com/tpverp/backend/terminal/PaymentTerminalOperationStatus.java`

**Interfaces:**
- Operation idempotency is unique by `(terminal_id, idempotency_key)`.
- Checkout idempotency is unique by `(terminal_id, checkout_key)` and stores request hash plus resulting document.
- `documento_pago.operacion_datafono_id` is a nullable unique FK; `terminal_cobro_id` remains unchanged.
- Quotes are terminal-scoped, hash-versioned, expiring, reservable by an authorization and consumable once by the same idempotent checkout.

- [ ] Write failing domain tests for `PENDING -> SENT -> final`, terminal-state immutability and positive EUR amounts.
- [ ] Write a failing V45 contract test for FKs, unique constraints, checks and indexes.
- [ ] Add `operacion_datafono` with provider, positive amount, fixed EUR currency, status/timestamp checks, configuration hash/version, references and optimistic version.
- [ ] Add `operacion_datafono_id` to `documento_pago` with FK and UNIQUE.
- [ ] Add POS checkout storage with canonical request hash and resulting document FK.
- [ ] Add customer receivable storage with ticket/customer FKs, original/outstanding amount, status and timestamps; add movement storage for later collection/audit.
- [ ] Add stable payment-method `code/kind` with checks and backfill for system methods. Backfill every pre-existing custom method as `kind=OTHER` with a normalized code unique per company before adding NOT NULL/unique constraints; include a migration fixture with a custom method.
- [ ] Add quote storage with request/result hashes, calculated snapshot, total, expiry, reservation checkout/timestamp and consumed document FK. Reservation created while valid permits later consumption only by the same checkout/payload.
- [ ] Add `UNKNOWN` to the operation enum and operation-table check only; `documento_pago` continues to allow only approved integrated operations.
- [ ] Add `last_connection_test_config_hash` to terminal payment configuration and invalidate it whenever configuration changes.
- [ ] Run domain and migration tests and verify GREEN.

---

### Task 3: Redsys gateway, pairing and authorization

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/terminal/PaymentTerminalGateway.java`
- Create: `backend/src/main/java/com/tpverp/backend/terminal/PaymentTerminalSecretResolver.java`
- Create: `backend/src/main/java/com/tpverp/backend/terminal/RedsysTpvPcGateway.java`
- Create: `backend/src/main/java/com/tpverp/backend/terminal/PaymentTerminalOperationService.java`
- Create: `backend/src/main/java/com/tpverp/backend/terminal/PaymentTerminalOperationController.java`
- Create: `backend/src/test/java/com/tpverp/backend/terminal/RedsysTpvPcGatewayTest.java`
- Create: `backend/src/test/java/com/tpverp/backend/terminal/PaymentTerminalOperationServiceTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/terminal/TerminalPaymentConfigurationController.java`
- Modify: `backend/src/main/java/com/tpverp/backend/terminal/TerminalPaymentConfigurationService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/terminal/TerminalPaymentConfigurationView.java`

**Interfaces:**
- `POST /api/v1/payment-terminal/operations` accepts `{ checkoutId, quoteId, amount, idempotencyKey }` and returns trusted operation status. The operation persists checkout ID explicitly.
- `GET /api/v1/payment-terminal/operations/{id}?checkoutId=...` returns current status only when authenticated terminal, requested checkout and persisted checkout all match.
- `/terminal-configuration/payment/connection-test` accepts no caller-provided result; backend runs `gateway.testConnection`.
- A dev/ADMIN-only endpoint controls the next simulated outcome.

- [ ] Write failing tests for approved, declined, simulated final timeout, transport `UNKNOWN`, late approval, TEST outside dev, missing endpoint/secret and server-side connection tests.
- [ ] Implement an allow-listed redacted parameter view and `PaymentTerminalSecretResolver`; never return/log the reference or resolved secret.
- [ ] Persist `PENDING`, commit `SENT`, execute external I/O outside a database transaction, then persist the final result in a second transaction. Transport timeout becomes `UNKNOWN`, not a final rejection.
- [ ] Implement replay comparison for terminal, amount, EUR, provider and configuration hash; mismatch returns 409.
- [ ] Handle concurrent insert with DB uniqueness and conflict recovery; implement polling/reconciliation scoped by authenticated terminal plus requested/persisted checkout, with a rejection test for another checkout on the same terminal. Reserve the quote atomically for the explicit checkout before sending and block every substitute authorization for that checkout, not only the same allocation.
- [ ] Require a recent successful server-side test for the exact configuration hash before LIVE activation; invalidate the hash on change. LIVE still throws `live_not_configured` until the official protocol client exists.
- [ ] Run gateway, service, controller and concurrency tests and verify GREEN.

---

### Task 4: Authoritative and idempotent POS backend

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/document/PosSaleController.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/PosSaleService.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/PosSaleRequest.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/PosQuoteRequest.java`
- Create: `backend/src/test/java/com/tpverp/backend/document/PosSaleServiceTest.java`
- Create: `backend/src/test/java/com/tpverp/backend/document/PosSaleControllerContractTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/PaymentRequest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentPayment.java`

**Interfaces:**
- `GET /api/v1/pos/context` derives company/store from authentication and returns default active warehouse, active methods with stable kind/capabilities, and redacted terminal configuration.
- `POST /api/v1/pos/quotes` returns quote ID/hash/expiry, authoritative total and calculated lines.
- `POST /api/v1/pos/tickets` accepts checkout ID, quote ID/hash, real payments and a separate pending amount; it never accepts browser-calculated lines.

- [ ] Write failing tests for zero/no/multiple default warehouses, company isolation and method capabilities (`reference`, `voucherCode`, cash drawer, card).
- [ ] Implement POS context derived exclusively from authenticated organization; do not require a browser-supplied company ID.
- [ ] Write failing quote tests for server-resolved product name/code/price/tax/regime, customer benefit, promotions, per-line rounding, expiry, reservation before expiry, consumption after expiry by the same checkout and rejection by another checkout.
- [ ] Implement server-side quote calculation from product/tax/promotion repositories; reject missing/inactive products and nonpositive totals. Require ADMIN or `APLICAR_DESCUENTO` for any discount above zero.
- [ ] Write failing tests for approved card operation, declined/mismatched/other-terminal/reused operation, and removal of provider/status/auth fields from public input.
- [ ] Lock and validate quote plus approved operation, require exact quote/amount/terminal/checkout match, permit an expired quote only when it was reserved while valid by that checkout, copy trusted metadata and persist unique quote/operation links atomically with the ticket.
- [ ] Write failing tests for `real payments + pending = total`, pending requiring customer, mixed pending, reports excluding debt and receivable creation.
- [ ] Persist pending as `CustomerReceivable`, never `DocumentPayment`; keep collected-payment and cash reports based only on real payments. Assign `principal=true` server-side only to the first real payment; a fully receivable ticket has none.
- [ ] Write failing checkout replay tests for identical request before/after response loss and 409 on a changed payload.
- [ ] Implement canonical hash, unique scoped checkout insert and replay of the existing ticket. Recheck `APLICAR_DESCUENTO`/ADMIN at confirmation from the quote snapshot before consuming anything. Add PostgreSQL tests for two concurrent requests and two tickets consuming one operation.
- [ ] Run POS, document, receivable, report and cash tests and verify GREEN.

---

### Task 5: Accessible checkout and Redsys pairing UI

**Files:**
- Create: `frontend/packages/app-common/src/components/SalePaymentDialog.tsx`
- Create: `frontend/packages/app-common/src/components/SalePaymentDialog.test.tsx`
- Create: `frontend/packages/app-common/src/components/RedsysPairingDialog.tsx`
- Create: `frontend/packages/app-common/src/components/RedsysPairingDialog.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
- Modify: `frontend/packages/app-common/src/api/client.ts`
- Modify: `frontend/packages/app-common/src/types.ts`
- Modify: `frontend/packages/app-common/src/styles/tpv.css`

**Interfaces:**
- Checkout loads `/pos/context`, refreshes `/pos/quotes` after line/discount/customer changes, authorizes against that quote and submits `/pos/tickets` with stable checkout ID.
- Structured `ApiError` retains HTTP status and backend error code.

- [ ] Write failing Testing Library/userEvent tests for cash, change, mixed allocations, pending with/without customer, reference, VALE code and card remaining amount.
- [ ] Implement the dialog with assigned/remaining/change live announcements and cent-based helpers.
- [ ] Write failing tests for quote refresh after line/discount/customer change, member/promotional totals, quote expiry, loading, double click, cancellation/unmount, 400/403/409/network errors and preservation of approved operations.
- [ ] Implement an explicit state machine; allocations use the authoritative quote total, discounts require ADMIN/`APLICAR_DESCUENTO`, concurrent authorization/submit is disabled, and backend codes map to concrete actions.
- [ ] Write failing tests for `SENT/UNKNOWN` polling, late approval after quote expiry, prohibition of any substitute attempt, blocked line/discount/customer editing after `SENT` or `APPROVED`, and rotation of `authorizationAttemptId` only after explicit retry following a final rejection.
- [ ] Implement operation polling with bounded backoff. Freeze all commercial editing from `SENT` through `UNKNOWN/APPROVED`; preserve the same attempt ID across network retries; unfreeze and create a new ID only after confirmed final non-approval.
- [ ] Write failing pairing tests for ADMIN/config permission versus VENTA-only, redacted parameters, backend connection test and dev simulated outcomes.
- [ ] Add `CONFIGURACION_TERMINAL` to permission parsing, implement pairing without exposing secrets, and block LIVE based on backend validation.
- [ ] Add focus trap, initial focus, Escape, focus restoration, `aria-describedby`, `role=alert`, `aria-live` and allocation-specific accessible names.
- [ ] Wire `SaleScreen`: empty tickets cannot open payment; line/discount/customer changes obtain a fresh quote; success displays ticket/change and clears state; every failure retains lines, customer, quote, checkout ID, allocations and approved operation.
- [ ] Run `npm.cmd test -- payment.test.ts SalePaymentDialog.test.tsx RedsysPairingDialog.test.tsx SaleScreen.test.tsx` and verify GREEN.

---

### Task 6: Full verification

**Files:** Modify only files needed for failures found during verification.

- [ ] Run all focused backend tests, then the complete backend suite using the existing local Maven repository. Report any nonzero exit honestly.
- [ ] Run `npm.cmd test` and `npm.cmd run build` in `frontend`; distinguish pre-existing failures from regressions.
- [ ] Start PostgreSQL/backend/venta locally and manually verify authoritative quote changes, cash with change, approved/declined/UNKNOWN card, late reconciliation, mixed cash/card, VALE/reference, pending with customer and blocked pending without customer.
- [ ] Repeat the same checkout after simulated response loss and confirm the same ticket ID and one card operation.
- [ ] Inspect PostgreSQL for ticket, real payments, terminal operation, unique operation link, cash movement, receivable and report totals.
- [ ] Run `git diff --check` and `git status --short`. Do not stage, commit or push.
