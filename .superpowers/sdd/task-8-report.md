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
