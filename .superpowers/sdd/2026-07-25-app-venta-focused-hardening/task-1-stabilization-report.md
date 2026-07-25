# Task 1 stabilization report

## Root cause

`SaleScreen` obtains the customer-dialog accessible name from the localized
`sale.customer.title` message. The payment-cleanup integration test instead
duplicated the Spanish text `Seleccionar cliente`, coupling the behavioral
accessibility check to one locale string.

## Minimal correction

The test now uses `createTranslator("es")` and queries the dialog by the
catalogue value for `sale.customer.title`, for both its appearance and
dismissal. The assertion continues to verify the real dialog role and its
accessible name; no application behavior changed.

## Verification

From `frontend`:

```text
npm.cmd test -- --run packages/app-common/src/components/SaleScreen.paymentCleanup.integration.test.tsx
Test Files  1 passed (1)
Tests  3 passed (3)
Duration  4.33s

npm.cmd test
Test Files  99 passed (99)
Tests  917 passed (917)
Duration  33.41s
```
