# Task 2 report — Frontend cleanup policy

## Implementation commit

- Original implementation: `d4b328a feat(payment): clear stale simulator checkout safely`.
- Review corrections: included in the current task commit.

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
- A normal cancellation has three explicit outcomes. Only `CANCELLED` permits exit; both a successful non-`CANCELLED` response and any HTTP/network exception return `BLOCKED` immediately and cannot issue a second cleanup request.
- Every stale active session hydrated on sale entry, including empty sessions and sessions containing only `DECLINED`, `ERROR` or `CANCELLED` allocations, is sent once to audited `simulator-discard` with `sale_entry_cleanup`. A live-mode rejection preserves recovery state, reserved total and lock.
- Entry cleanup is scoped to the ID returned by authoritative entry hydration, so sessions created later during the current sale are never mistaken for stale sessions.
- The once-per-ID guard is covered by a real unmount/remount test; rejected cleanup does not duplicate requests or loop.
- Added localized blocked/cleanup feedback in Spanish, English and Chinese.
- No delete endpoint is called and payment audit data is untouched.

## TDD and verification

- Review RED: `npm.cmd test -- SalePaymentCheckout.test.ts` — 6 expected failures: forbidden fallback after cancellation error, four stale `AUTO_CANCEL` variants skipped on entry, and missing real-remount coverage.
- Review regression caught during GREEN: broad entry cleanup also targeted a newly created payment session; scoping cleanup to the authoritatively hydrated ID removed that regression.
- Review GREEN/final focused suite: `npm.cmd test -- SalePaymentCheckout.test.ts` — 63/63 passing, exit 0.
- Critical re-review RED: shared logout/application-close cases returned `READY` after `NOT_CANCELLED` because they invoked simulator fallback.
- Critical re-review GREEN: shared exit preparation accepts only `CANCELLED`; unit and HTTP-level tests cover `NOT_CANCELLED` for both callers, verifying exactly one cancel call and no subsequent discard. Final focused suite: 67/67 passing.

- RED: `npm.cmd test -- SalePaymentCheckout.test.ts` — 4 expected failures (missing close handle, missing auto cleanup and missing translations), 53 passing.
- GREEN/final: `npm.cmd test -- SalePaymentCheckout.test.ts` — 57/57 passing, exit 0.
- `git diff --check` — exit 0; only Git's existing LF-to-CRLF notices were printed.
- Diff scope check found no `SessionTopControls` changes.

## Concerns / handoff

- Task 3 still needs to wire `prepareApplicationClose()` into `SaleScreen`/`SessionTopControls`; this task intentionally does not initiate navigation or window closing.
- The in-memory once-per-ID entry-attempt set resets on a full application reload, which is intentional: a new process may retry a stale simulated session, while a rejected live-terminal session is never retried in a render/remount loop within the current loaded application.
