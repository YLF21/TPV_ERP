# Task 8 verification report

Date: 2026-07-13

## Scope and safety

- All payment-provider scenarios used the deterministic in-process simulators.
- No LIVE endpoint, SDK, merchant credential or external financial service was contacted.
- LIVE adapters remain fail-closed with `SDK_NOT_INSTALLED`.

## PostgreSQL migrations

Added `PaymentPlatformMigrationPostgreSqlTest`, which resolves either
`TPV_TEST_DB_*` or the historical `TPV_ERP_TEST_DB_*` family and creates a
throwaway schema for each case.

Evidence with PostgreSQL 18.4 and `TPV_TEST_DB_*`:

- empty schema -> 59 migrations -> V60;
- V45 schema -> V46..V60;
- both paths verified all ten payment/configuration/session tables;
- result: 2 tests, 0 failures, 0 errors, 0 skipped.

The existing full empty-schema migration test also passed: 1 test, V60.

## Backend

The first full run exposed a Spring constructor-selection failure in
`SalePaymentSessionService` (1133 tests, 11 cascading context errors). The
production constructor is now explicitly selected with `@Autowired`.

The historical-variable run then exposed three outdated integration-test
assumptions and two suite-level concurrency/resource defects. The fixes were:

- supply the pricing/promotion collaborators required by document rollback;
- commit setup data before concurrent payment finalization and always close its executor;
- assert the current V49 purchase-price model instead of a removed column;
- keep test-only Hikari pools at maximum 2/minimum idle 0 so cached Spring
  contexts cannot exhaust PostgreSQL;
- serialize product-supplier UPSERTs by product with a transaction-scoped
  advisory lock, preventing a race with the partial unique "last supplier"
  index;
- retry PostgreSQL setup/cleanup only for SQLSTATE `53300`, with five bounded
  attempts and short backoff. Other SQL errors fail immediately.

Final full-suite results:

- `TPV_TEST_DB_*`: 1133 tests, 0 failures, 0 errors, 25 skipped;
- `TPV_ERP_TEST_DB_*`: 1133 tests, 0 failures, 0 errors, 0 skipped;
- `FiscalChainPostgreSqlTest`: 8 tests, 0 failures, 0 errors;
- `ProductSupplierRepositoryPostgreSqlTest`: 3 tests passed in the focal run,
  then the complete class passed three additional consecutive runs.

## Simulator scenarios

`PaymentTerminalGatewayContractTest` is the local integration-equivalent
scenario suite for all four providers: Redsys TPV-PC, PAYTEF, PAYCOMET and
Global Payments. For every provider it covers APPROVED, DECLINED, TIMEOUT,
timeout/query resolution, void, partial refund, receipt and reconciliation.
It also verifies terminal outcomes cannot be overwritten and LIVE adapters
never approve.

Focused result: 49 tests, 0 failures, 0 errors, 0 skipped.

An interactive browser run was not used because Task 8 had no dedicated local
backend/frontend instance bound to this isolated worktree. The deterministic
gateway contract exercises the same normalized provider boundary without any
external financial operation.

## Frontend

- `npm.cmd test`: 45 files, 305 tests passed.
- `npm.cmd run build --workspace @tpverp/app-venta`: success.
- `npm.cmd run build --workspace @tpverp/app-gestion`: success.

## Documentation

- Backend README documents both PostgreSQL environment-variable families,
  empty/V45 migration verification, simulator outcomes and LIVE safety.
- Backend `.env.example` contains placeholders only.
- Frontend README documents the correct working directory, local commands,
  dynamic provider configuration and the prohibition on browser-side secrets.

## Result

Task 8 verification is complete. Both PostgreSQL configuration families,
empty/V45 migration paths, backend suites, frontend suites/builds and all
simulated provider outcomes are green. No external payment endpoint was used.

## Global-review corrections

The post-implementation review identified payment-session and configuration
edge cases. They were corrected without contacting LIVE providers:

- uncertain integrated allocations now force `COMPENSATION_REQUIRED` on
  cancellation and a cancelled session cannot be reopened by a late result;
- finalization locks and revalidates every integrated charge, rejecting
  voided/refunded/already-documented operations;
- void/refund management is confined to compensation handling;
- authorization uses an ephemeral password dialog instead of browser prompts;
- pairing identity and status are persisted in the existing configuration JSON,
  exposed by `current()` and restored by the frontend after reload;
- simulated pairing status rejects identifiers that were never initiated;
- `simulatorQueryOutcome` is validated, normalized, described and safely
  rehydrated for deterministic timeout-to-query scenarios;
- LIVE secret references and versions are managed as opaque metadata; secret
  values are never returned or placed in `VITE_*` variables;
- all split-payment UI messages are present in Spanish, English and Chinese;
- the frontend represents `COMPENSATION_REQUIRED` explicitly and offers no new
  payment allocations while compensation is pending.

Fresh verification after these corrections:

- backend focused integration: 98 tests, 0 failures, 0 errors;
- frontend focused payment/i18n: 34 tests, 0 failures;
- frontend complete suite: 45 files, 310 tests passed;
- both `app-venta` and `app-gestion` production builds succeeded;
- local backend health at `127.0.0.1:8081`: `UP`;
- login through the Vite proxy at `127.0.0.1:5173`: successful;
- configuration/pairing smoke for Redsys TPV-PC, PAYTEF, PAYCOMET and Global
  Payments: SIMULATED mode, timeout/query outcome, pairing start, reload identity
  recovery and pairing query all succeeded.

The browser runtime available to this worker reported no browser instances, so
the worker did not claim a visual interaction result. The local processes were
left running for the main task, whose in-app browser can perform the final UI
walkthrough. The HTTP smoke and all provider contract tests remained entirely
local and simulated.

## Browser acceptance evidence (2026-07-13)

- Configured `GLOBAL_PAYMENTS` in `SIMULATED` mode from the frontend; pairing persisted across reload and its status query returned paired.
- Exercised timeout -> `Consultar estado` -> `APPROVED`, with zero remaining and a simulator authorization/reference.
- Finalized real local ticket `001-260713-00001`, total `12,10` EUR, through the document pipeline. The completed checkout closes and the empty sale returns to its initial disabled-payment state.
- Walkthrough defects corrected: premature operation FK linkage, oversized request hash, non-idempotent approved query, legacy recovery degrading split operations to review, hard-coded Redsys validation, snapshot placeholder/method mismatch, missing finalize retry after reload, stale completed checkout UI, missing translations, and stale alerts.
- One failed browser-only session was removed surgically: session `cb5d5e1c-7b97-4d4d-82b6-5ad404450347`, its single allocation, operation `ea7bca94-29a1-417b-8c9b-fc3ee58b6665`, and that operation's events. The transaction first rejected document/payment links and child void/refund operations; no unrelated data was modified.

## Final verification commands

```powershell
$env:TPV_ERP_TEST_DB_URL='jdbc:postgresql://localhost:5432/tpv_erp_dev'
$env:TPV_ERP_TEST_DB_USER='tpv_erp_test'
$env:TPV_ERP_TEST_DB_PASSWORD='admin'
$env:TPV_TEST_DB_URL='jdbc:postgresql://localhost:5432/tpv_erp_test'
$env:TPV_TEST_DB_USERNAME='tpv_erp_test'
$env:TPV_TEST_DB_PASSWORD='admin'
mvn.cmd test

npm.cmd test
npm.cmd run build --workspace @tpverp/app-venta
npm.cmd run build --workspace @tpverp/app-gestion
```
