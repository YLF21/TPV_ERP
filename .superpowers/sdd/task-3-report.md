# Task 3 report

## Status

Changed the visible split-payment presentation to pending-payment wording in Spanish, English, and Chinese while retaining the existing `payment.split.*` keys and all recovery behavior.

## TDD evidence

- RED: `npm.cmd test -- PaymentAllocationPanel.test.tsx SalePaymentCheckout.test.ts` exited 1 with four expected failures: the ES/EN/ZH panels still rendered `Cobro dividido`, `Split payment`, and `分拆支付`, and the uncertain-recovery heading was not `Cobro pendiente`.
- GREEN focused: `npm.cmd test -- PaymentAllocationPanel.test.tsx SalePaymentCheckout.test.ts SaleScreen.test.tsx` exited 0 with 3 files and 94 tests passed.
- Full frontend suite: `npm.cmd test` exited 0 with 47 files and 377 tests passed.
- Frontend build: `npm.cmd run build` exited 0; both `@tpverp/app-gestion` and `@tpverp/app-venta` compiled and produced Vite bundles.
- Diff hygiene: `git diff --check` exited 0 with no whitespace errors; Git emitted only the repository's LF-to-CRLF working-copy notices.

## Self-review

- Only the visible values for `payment.split.title` and `payment.split.start` changed; keys, request paths, types, and payment operations are untouched.
- Tests cover the pending-payment title and start copy in ES/EN/ZH and reject the legacy title.
- The uncertain recovery test proves `Consultar estado`, `Gestionar operación`, and `Cancelar sesión de cobro` remain available while cash, manual card, provider, and amount controls remain absent.
- Existing exceptional-state coverage continues to prove finalization and compensation controls are available in their corresponding states.
- Strengthened the Task 2 same-tick logout regression: the second click now reopens the menu and invokes the live menu item rather than clicking a detached DOM node; one preparation and one logout are still asserted.

## Concerns

None identified within Task 3 scope.
