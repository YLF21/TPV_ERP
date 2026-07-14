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

## Important review fixes

- Allocation recovery metadata is retained while a covered session is awaiting a ticket, including across finalization failure and component reload. It is cleared only after ticket finalization succeeds, a known-safe pre-effect rejection, a resolved non-covered allocation, or safe session cancellation.
- Manual references are sent only in the allocation request. The local recovery record contains the stable allocation ID plus a sanitized fingerprint (`kind`, `amountCents`, and optional provider), never the manual reference.
- Successful session cancellation now clears the persisted attempt, card guard, cash guard/metadata, and any open manual-card dialog so a subsequent checkout can start normally.
- Added regression tests for finalization failure/reload recovery, sensitive manual-reference persistence, and card reuse after cancellation.
- Review-fix verification: focused checkout tests 20/20 passed; full frontend suite 342/342 passed; both frontend applications built successfully.
- Compensation acknowledgement now performs the same safe card-state cleanup as successful cancellation after the backend returns `CANCELLED`: persisted recovery attempt, synchronous card guard, cash metadata/guard, and manual-card dialog state are cleared. A regression test proves a new direct card checkout can start afterward. Final verification: focused checkout tests 21/21 passed; full frontend suite 343/343 passed; both frontend applications built successfully.
- Successful allocation responses with terminal `DECLINED` or `ERROR` now clear safe attempt metadata and release only the corresponding synchronous method guard. Cash also clears its ephemeral received metadata and closes the failed dialog, while integrated/manual card releases the card guard. `PENDING` and `TIMEOUT` behavior is unchanged. Regression coverage proves F11 retries after a declined integrated card and fresh cash attempts after both declined and errored cash allocations. Final verification: focused checkout tests 36/36 passed; full frontend suite 358/358 passed; both frontend applications built successfully.
- Query resolution now matches recovery allocations by either allocation ID or operation ID. A queried `DECLINED`/`ERROR` clears stable attempt metadata and the matching method guard; cash additionally clears received metadata and closes its dialog, while card closes any manual dialog. Queried `PENDING`/`TIMEOUT` retains the attempt and recovery-only UI. Regression coverage exercises card and cash terminal retries plus both uncertain statuses. Final verification: focused checkout tests 42/42 passed; full frontend suite 364/364 passed; both frontend applications built successfully.
