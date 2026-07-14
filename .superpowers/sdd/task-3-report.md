# Task 3 report: direct card checkout

## Implemented

- Added an accessible, ephemeral manual-card reference dialog.
- Routed the direct **Tarjeta / F11** action to the configured integrated provider for the full sale total.
- Falls back to manual card entry only when no integrated provider is configured and manual card is enabled.
- Added a synchronous card guard so repeated clicks cannot create a second allocation.
- Preserved the persisted allocation attempt for timeout, 5xx, unknown outcomes, and finalization uncertainty; known safe pre-effect 4xx responses still release the guard and clear the attempt.
- Kept the Business Classic action layout, F10/F11/F12 labels, disabled customer-pending action, and the backend split-payment panel unchanged.
- Direct-card uncertainty is now shown as an alert while the stable attempt remains available for recovery.

## TDD evidence

- Initial focused run failed because the dialog module and direct card route did not exist (3 checkout failures plus the missing dialog import).
- Focused green run: `npm.cmd test -- ManualCardReferenceDialog.test.tsx SalePaymentCheckout.test.ts` — 18/18 tests passed.
- Full frontend suite: `npm.cmd test` — 47 files, 339 tests passed.
- Frontend build: `npm.cmd run build` — both `@tpverp/app-gestion` and `@tpverp/app-venta` built successfully.

## Safety coverage

- Integrated request uses `INTEGRATED_CARD`, full total `12.10`, and `GLOBAL_PAYMENTS`.
- Manual input rejects whitespace-only values and submits `" REF-1 "` as `"REF-1"`.
- Same-tick double card click creates one session/allocation attempt.
- A 5xx card outcome retains the stable allocation attempt and blocks a second charge.
