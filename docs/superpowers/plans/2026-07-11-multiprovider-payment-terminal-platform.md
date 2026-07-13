# Multiprovider Payment Terminal Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar una plataforma común de datáfonos con simuladores para Redsys TPV-PC, PAYTEF, PAYCOMET y Global Payments, operaciones recuperables, anulaciones, devoluciones, recibos, pagos divididos, secretos y stubs LIVE seguros.

**Architecture:** El backend separa dominio de operaciones, ledger append-only, orquestación y adaptadores `PaymentTerminalGateway`. APP VENTA y Configuración consumen capacidades dinámicas sin conocer detalles del proveedor; los adaptadores LIVE responden `SDK_NOT_INSTALLED` hasta recibir SDK oficiales.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Hibernate 7, PostgreSQL 18/Flyway, React, TypeScript, Vite y Vitest.

## Global Constraints

- No se inventan protocolos, códigos propietarios ni operaciones financieras externas.
- Nunca se almacenan PAN completo, PIN, CVV o secretos dentro de `provider_parameters`.
- Todas las operaciones financieras usan idempotency key y EUR.
- Un timeout se consulta; nunca se repite automáticamente el cargo.
- Cambios exclusivamente locales: ningún agente ejecutará `git commit`, `git push` ni creará PR.
- Cada tarea sigue RED-GREEN y preserva compatibilidad con el checkout Redsys existente.

---

### Task 1: Dominio común, capacidades y ledger PostgreSQL

**Files:**
- Create: `backend/src/main/resources/db/migration/V46__payment_terminal_operations.sql`
- Create: `backend/src/main/java/com/tpverp/backend/terminal/PaymentTerminalCapability.java`
- Create: `backend/src/main/java/com/tpverp/backend/terminal/PaymentTerminalOperationType.java`
- Create: `backend/src/main/java/com/tpverp/backend/terminal/PaymentTerminalOperation.java`
- Create: `backend/src/main/java/com/tpverp/backend/terminal/PaymentTerminalEvent.java`
- Create: repositories for both entities in the same package.
- Modify: `backend/src/main/java/com/tpverp/backend/terminal/PaymentTerminalOperationStatus.java`
- Test: migration contract and entity transition tests under `backend/src/test/java/com/tpverp/backend/terminal/`.

**Interfaces:**
- Produces `PaymentTerminalOperation.reserve(...)`, typed transition methods, immutable event append and repositories used by later tasks.

- [ ] Write migration tests asserting provider, mode, operation type, original-operation relation, idempotency uniqueness, refund totals, leases and append-only events.
- [ ] Run the migration tests and confirm failure because V46 and entities do not exist.
- [ ] Implement V46 and domain entities with explicit allowed transitions; migrate V45 checkouts without losing references or authorization codes.
- [ ] Run targeted PostgreSQL migration and domain tests; expect zero failures.

### Task 2: Gateway contract and four simulators

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/terminal/CardTerminalGateway.java`
- Create: normalized commands/results for pair, charge, query, void, refund, receipt and reconciliation.
- Refactor: `RedsysSimulatorGateway.java` onto a reusable simulator engine.
- Create: `PaytefSimulatorGateway.java`, `PaycometSimulatorGateway.java`, `GlobalPaymentsSimulatorGateway.java`.
- Create: `UnavailableLivePaymentTerminalGateway.java`.
- Test: common contract test executed for all four providers.

**Interfaces:**
- Consumes Task 1 enums and operation identity.
- Produces capability discovery and every normalized gateway operation.

- [ ] Write parameterized contract tests for APPROVED, DECLINED, TIMEOUT, ERROR, query, void, partial refund, receipt and reconciliation.
- [ ] Verify RED against the current charge-only gateway.
- [ ] Implement the expanded interface, reusable deterministic simulator and four provider beans.
- [ ] Implement LIVE stub returning code `SDK_NOT_INSTALLED`, never APPROVED.
- [ ] Run all gateway tests and existing Redsys tests; expect zero failures.

### Task 3: Multiprovider checkout orchestration and recovery

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/document/PosCardService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/PosCardTicketCreator.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/DocumentService.java`
- Modify: `backend/src/main/java/com/tpverp/backend/document/PosCardCheckout.java`
- Create: `backend/src/main/java/com/tpverp/backend/terminal/PaymentTerminalOperationService.java`
- Create: `backend/src/main/java/com/tpverp/backend/terminal/PaymentTerminalRecoveryWorker.java`
- Test: service, concurrency, replay and recovery tests.

**Interfaces:**
- Consumes Task 1 repository and Task 2 gateways.
- Produces create/query APIs without hardcoded Redsys and persists actual provider/config hash.

- [ ] Add failing tests proving provider routing, real `testMode` propagation, hash conflict, single charge, no retry after timeout and ticket resume after restart.
- [ ] Remove hardcoded Redsys checks while retaining store/provider capability validation.
- [ ] Persist result/event before ticket creation and link the approved payment to the actual provider.
- [ ] Implement leased recovery with bounded backoff and `REVIEW_REQUIRED` fallback.
- [ ] Run card orchestration and recovery tests; expect zero failures.

### Task 4: API for status, void, refund, receipt and reconciliation

**Files:**
- Create: `PaymentTerminalOperationController.java` and request/view records.
- Modify: shared API exception mapping and permissions constants.
- Create: service methods for query, void, refund, receipt and reconciliation.
- Test: controller contracts, authorization and service rules.

**Interfaces:**
- Consumes Task 3 operation service.
- Produces `/api/v1/payment-terminal/operations/**` endpoints with Problem Details codes.

- [ ] Write failing controller tests for status, void, partial refund, receipt, event history and reconciliation.
- [ ] Add service tests preventing over-refund, invalid void and duplicate financial requests.
- [ ] Implement endpoints, stable error codes and permission checks.
- [ ] Verify transport errors are distinct from financial results and access denial.
- [ ] Run API/service tests; expect zero failures.

### Task 5: Secret store and local bridge contract

**Files:**
- Create package `backend/src/main/java/com/tpverp/backend/terminal/secrets/` with `PaymentSecretStore`, DPAPI implementation, entity/repository and admin service.
- Create package `backend/src/main/java/com/tpverp/backend/terminal/bridge/` with authenticated client contract and SDK-not-installed stub.
- Add Flyway migration V48 for encrypted secret references (V47 is used by receipts/reconciliation).
- Modify terminal configuration DTO to expose only secret presence/version.
- Test encryption round-trip, rotation, deletion, redaction and bridge timeout.

**Interfaces:**
- Produces opaque `secretReference` resolution for future LIVE gateways.

- [ ] Write failing tests proving APIs never return secret material and provider parameters reject secret-like keys.
- [ ] Implement encrypted versioned references using existing machine protection abstraction.
- [ ] Implement create/rotate/delete endpoints with audit and administrative permission.
- [ ] Implement bridge health/capabilities/pair/operation contract restricted to local transports and a safe unavailable stub.
- [ ] Run secret/bridge/security tests; expect zero failures.

### Task 6: Dynamic provider configuration frontend

**Files:**
- Modify: `frontend/packages/app-common/src/components/PaymentTerminalSettings.tsx`
- Modify: corresponding tests and i18n files.
- Modify backend configuration view/validation to return provider descriptors and capabilities.

**Interfaces:**
- Consumes capability/configuration API from Tasks 2 and 5.
- Produces dynamic provider/mode selection, pairing, test and safe secret-reference management.

- [ ] Write failing component tests rendering all allowed providers and disabling unavailable LIVE mode with `SDK no instalado`.
- [ ] Add provider descriptors and validated non-sensitive field schemas.
- [ ] Render simulator outcome for every simulated provider, pairing state and connection test independent of test mode.
- [ ] Ensure secret values are write-only and never rehydrated.
- [ ] Run component tests and app-common TypeScript build; expect zero failures.

### Task 7: Sale flow, split payments and operation management UI

**Files:**
- Refactor: `frontend/packages/app-common/src/components/SaleScreen.tsx` into focused payment orchestration components.
- Modify: `CardPaymentDialog.tsx` and tests.
- Create split-payment, operation history, refund/void and receipt components.
- Modify API client error typing and i18n.

**Interfaces:**
- Consumes Tasks 3-4 endpoints.
- Produces safe query/retry, split tender, void/refund actions and HardwareBridge receipt printing.

- [ ] Write failing tests for multiple card providers, timeout query without duplicate charge, split cash/cards, intermediate decline and approved-operation visibility.
- [ ] Implement payment allocation state machine and stable idempotency keys per allocation.
- [ ] Implement operation status/history, capability-gated void/refund and sanitized receipt reprint.
- [ ] Map Problem Details correctly so HTTP errors are not mislabeled as uncertain financial outcomes.
- [ ] Run frontend tests and APP VENTA build; expect zero failures.

### Task 8: Integration, migration and whole-project verification

**Files:**
- Update relevant backend/frontend READMEs and local configuration examples.
- Test existing V39/V45 compatibility plus V46/V47/V48 on PostgreSQL.
- No production protocol or credential examples.

**Interfaces:**
- Validates all earlier deliverables as one system.

- [ ] Run PostgreSQL migration tests from empty schema and upgraded V45 schema.
- [ ] Run backend `mvn.cmd test` with both PostgreSQL test environment-variable families.
- [ ] Run all frontend tests and builds for APP VENTA and APP GESTIÓN.
- [ ] Perform a local browser scenario for each simulated provider: approve, decline, timeout/query, void and refund.
- [ ] Request independent whole-change review; resolve every critical/important finding and repeat affected tests.
- [ ] Record final evidence without committing or pushing.
