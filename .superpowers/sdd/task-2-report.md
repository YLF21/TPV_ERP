# Task 2 report: authoritative sale catalog and fail-closed fiscal data

## Outcome

`SaleScreen` now loads the fiscal sale catalog from `GET /products/sale`. Pending-sale drafts carry a validated fiscal snapshot for each line and reject invalid fiscal percentages or regimes before a dialog can open. The existing pending-sale preparation `try/catch` displays the thrown validation message through `pendingError` and does not set a draft when validation fails.

## RED/GREEN evidence

### Catalog endpoint

- RED: `cd frontend && npm.cmd test -- --run packages/app-common/src/components/SaleScreen.test.tsx`
  - Result: 90 passed, 1 failed.
  - Expected failure: `apiPaths` contained `/api/v1/products` rather than `/products/sale`.
- GREEN: the same command after changing the request and mocks.
  - Result: 91 passed, 0 failed.

### Fiscal draft validation

- RED: `cd frontend && npm.cmd test -- --run packages/app-common/src/components/SaleScreen.test.tsx`
  - Result: 91 passed, 1 failed.
  - Expected failure: malformed `taxPercentage` did not throw; the old fallback generated a draft.
- GREEN: the same command after adding `saleProductFiscalSnapshot`.
  - Result: 92 passed, 0 failed.

### Final verification

- `cd frontend && npm.cmd test -- --run packages/app-common/src/components/SaleScreen.test.tsx`
  - Result: 92 passed, 0 failed.
- `cd frontend && npm.cmd run build`
  - Result: both `@tpverp/app-gestion` and `@tpverp/app-venta` completed TypeScript checks and production builds successfully.
- `git diff --check`
  - Result: clean.

## Changed files

- `frontend/packages/app-common/src/components/SaleScreen.tsx`
  - Requires the sale-catalog fiscal fields, requests `/products/sale`, and validates the pending draft's tax percentage/regime without silent defaults.
- `frontend/packages/app-common/src/components/SaleScreen.test.tsx`
  - Covers the new endpoint and fail-closed fiscal behavior; updates endpoint mocks and typed fixtures to the authoritative catalog contract.

## Commit

- `98abd4c34dcf3787c1a2f6baca3fad68e54d7495` — `feat: validate pending sale fiscal catalog`

## Self-review

- Confirmed `SaleProduct` makes `taxId`, `taxesIncluded`, `taxRegime`, and `taxPercentage` required.
- Confirmed the runtime fiscal guard rejects non-finite/out-of-range percentage values and regimes outside `IVA`/`IGIC`.
- Confirmed fiscal snapshot values are spread into every pending-draft line, with no `0.00` or `GENERAL` fallback remaining in that path.
- Confirmed the already-existing pending-sale error handler catches draft-construction errors, preserves the lack of a new draft, and renders `pendingError` as an alert.
- Kept payment checkout, debt behavior, member discounts, and backend code unchanged.

## Concerns

None for this task. The build emits pre-existing Vite warnings about large chunks/dynamic import chunking; it succeeds and these warnings are unrelated to the change.

## Review fix

### RED/GREEN evidence

- RED: `cd frontend && npm.cmd test -- --run packages/app-common/src/components/SaleScreen.test.tsx`
  - Result: 92 passed, 2 failed.
  - Expected failures: an empty tax percentage did not throw, and a whitespace percentage reached the pending-sale quote flow instead of rendering the fiscal catalog error.
- GREEN: the same command after validating the raw percentage before `Number(...)` conversion.
  - Result: 94 passed, 0 failed.
- Final verification: `cd frontend && npm.cmd run build`
  - Result: both `@tpverp/app-gestion` and `@tpverp/app-venta` completed TypeScript checks and production builds successfully.

### Changes

- Rejects `null`, `undefined`, non-number/non-string values, and blank strings before numeric fiscal normalization.
- Adds regression coverage for empty string, whitespace, and `null` tax percentages.
- Adds an integration-style SaleScreen test that confirms invalid catalog fiscal data renders the validation alert and does not open `CustomerPendingSaleDialog`.

### Commit

- `179665b77d2daa80dfe9d9a06b8f83af818ac46d` — `fix: reject blank pending sale tax rates`

## Review fix: tax inclusion flag

### RED/GREEN evidence

- RED: `cd frontend && npm.cmd test -- --run packages/app-common/src/components/SaleScreen.test.tsx`
  - Result: 93 passed, 2 failed.
  - Expected failures: missing/`undefined`, `null`, and string `taxesIncluded` values produced a draft, and the visible pending-sale test reached the quote flow rather than displaying the fiscal error.
- GREEN: the same command after adding the boolean guard.
  - Result: 95 passed, 0 failed.
- Final verification: `cd frontend && npm.cmd run build`
  - Result: both `@tpverp/app-gestion` and `@tpverp/app-venta` completed TypeScript checks and production builds successfully.

### Changes

- Fails closed unless `taxesIncluded` is strictly a boolean, with `Producto sin configuración de impuestos válida`.
- Covers missing, `undefined`, `null`, and non-boolean tax-inclusion values.
- Exercises the visible alert/no-dialog behavior with `taxesIncluded: null`.

### Commit

- `5d03529ea9647bf2d09b3095130a4b62fa551dce` — `fix: validate pending sale tax inclusion`
