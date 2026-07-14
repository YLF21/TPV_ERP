# Task 2 report — Frontend cleanup policy

## Implementation commit

- `d4b328a feat(payment): clear stale simulator checkout safely`

## Files

- `frontend/packages/app-common/src/components/SalePaymentCheckout.tsx`
- `frontend/packages/app-common/src/components/SalePaymentCheckout.test.ts`
- `frontend/packages/app-common/src/i18n/MessagesEs.ts`
- `frontend/packages/app-common/src/i18n/MessagesEn.ts`
- `frontend/packages/app-common/src/i18n/MessagesZh.ts`

`SessionTopControls` was not modified.

## Implemented behavior

- Added `prepareApplicationClose(): Promise<"READY" | "BLOCKED">`.
- Logout and application shutdown share the same strict exit preparation policy.
- Uncertain sessions use audited `simulator-discard`; shutdown/logout send `application_shutdown` and entry recovery sends `sale_entry_cleanup`.
- `READY` is returned only after authoritative hydration shows no active session, or a cancellation/discard response confirms `CANCELLED`; the common local cleanup then removes both storage keys, guards, dialogs, operation state and the server lock.
- A normal cancellation that returns a non-`CANCELLED` status cannot permit logout. The simulator discard is attempted and exit remains blocked unless that response confirms `CANCELLED`.
- An unsafe stale active session is auto-discarded once per session ID for the lifetime of the loaded frontend module. Backend rejection preserves recovery state and does not create a render loop.
- Added localized blocked/cleanup feedback in Spanish, English and Chinese.
- No delete endpoint is called and payment audit data is untouched.

## TDD and verification

- RED: `npm.cmd test -- SalePaymentCheckout.test.ts` — 4 expected failures (missing close handle, missing auto cleanup and missing translations), 53 passing.
- GREEN/final: `npm.cmd test -- SalePaymentCheckout.test.ts` — 57/57 passing, exit 0.
- `git diff --check` — exit 0; only Git's existing LF-to-CRLF notices were printed.
- Diff scope check found no `SessionTopControls` changes.

## Concerns / handoff

- Task 3 still needs to wire `prepareApplicationClose()` into `SaleScreen`/`SessionTopControls`; this task intentionally does not initiate navigation or window closing.
- The in-memory once-per-ID entry-attempt set resets on a full application reload, which is intentional: a new process may retry a stale simulated session, while a rejected live-terminal session is never retried in a render/remount loop within the current loaded application.
