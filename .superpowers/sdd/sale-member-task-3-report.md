# Task 3 report: frontend member pricing preview

## Implemented

- Member prices are used only for active members, `MEMBER_PRICE` products, and positive valid `memberPrice` values.
- Existing normal and date-bound offer pricing remains the fallback for non-members and invalid member prices.
- Active tier percentages are attached to every product line.
- The effective line discount remains the greater of the operator-entered discount and member tier discount.
- Totals, ticket-line unit prices/subtotals, and product-search prices use the selected customer's active-member state.
- Existing manual-discount blocking and benefit display behavior is preserved.

## TDD evidence

- RED: `npm.cmd test -- SaleScreen.test.tsx` — 4 expected failures (member price eligibility, offer helper signature, all-line tier application, customer selected after product).
- GREEN: `npm.cmd test -- SaleScreen.test.tsx` — 81/81 passed.

## Verification

- `npm.cmd test` — 59 files, 531/531 tests passed.
- `npm.cmd run build` — both workspace applications built successfully. Vite emitted the pre-existing chunk-size advisory for the management application.
- `git diff --check` — no whitespace errors (Git only reported the repository's LF-to-CRLF conversion warning).

## Self-review

- Confirmed offer pricing still checks active status and date range before using an explicit price or percentage.
- Confirmed manual discounts are still stored separately, submitted as operator discounts, and blocked for `discountType: NONE` as before.
- Confirmed selecting or clearing a customer recomputes tier metadata without overwriting the manual percentage.

## Concerns

- None blocking. Server quote/finalization remains authoritative; this task changes only the frontend preview and keeps the existing request payload semantics.
