# Dev test cash final review fix report

Date: 2026-07-14

Base commit: `2f5a409 feat(sale): expose dev test cash button`

## Scope

- Clear `testCashRequired`, `testCashStatus`, and stale `error` after successful ticket finalization.
- Derive the test-cash requirement on every allocation/finalization failure so unrelated failures hide the offer.
- Preserve the offer after `/cash/sessions/open` fails, allowing an explicit retry.
- Strengthen deterministic PostgreSQL seeder assertions without changing seed behavior.
- Prove APP GESTION never enables the development-only test-cash action.
- Prove the opening request forwards the access token.

## TDD RED evidence

Command (working directory `frontend`):

```powershell
npm.cmd test -- packages/app-common/src/components/SalePaymentCheckout.test.ts packages/app-common/src/components/SaleScreen.test.tsx
```

Result: exit code `1`; 2 test files executed, 117 tests total, exactly 2 expected regression failures:

- `hides the test cash offer after a later unrelated finalization error`: the stale `Abrir caja de prueba` button remained in the document.
- `does not leak opened test cash status or stale errors into the next checkout`: the stale `Caja de prueba abierta...` status remained in the subsequent checkout.

The opening-failure retry and APP GESTION negative wiring tests passed at RED, documenting already-correct behavior.

Backend RED command (working directory `backend`, using the temporary Maven-home junction):

```powershell
$env:MAVEN_USER_HOME='E:\workspace\gitwork\TPV_ERP\.superpowers\sdd\m2-link'
cmd.exe /d /c "mvnw.cmd -Dtest=DevSampleDataSeederPostgreSqlTest test"
```

Result: exit code `1` at test compilation for the intentionally referenced but not-yet-created package-private `DevSampleDataSeeder.cashSessionHistoryId()` accessor. This demonstrated that the strengthened test was bound to the deterministic seed row rather than an arbitrary zero-valued row.

## Minimal implementation

- `finish(...)` now clears all test-cash auxiliary state and stale error on a ticket-number success.
- `markTestCashRequirement(...)` now assigns the complete derived boolean for every add/retry failure and clears obsolete success status.
- `/cash/sessions/open` retains its independent catch path, so opening failure does not clear the retry offer.
- `DevSampleDataSeeder` exposes only a package-private static accessor for its existing deterministic history UUID; seed SQL and runtime behavior are unchanged.

## GREEN evidence

Focused frontend command:

```powershell
npm.cmd test -- packages/app-common/src/components/SalePaymentCheckout.test.ts packages/app-common/src/components/SaleScreen.test.tsx
```

Result: exit code `0`; 2 test files passed, 117/117 tests passed.

Focused backend command:

```powershell
$env:MAVEN_USER_HOME='E:\workspace\gitwork\TPV_ERP\.superpowers\sdd\m2-link'
cmd.exe /d /c "mvnw.cmd -Dtest=DevSampleDataSeederPostgreSqlTest,CashSessionServiceTest test"
```

Result after correcting the test query to the schema's real `usuario.user_id` column: `CashSessionServiceTest` 33/33 and `DevSampleDataSeederPostgreSqlTest` 3/3 passed (36/36 total). The seeder test asserts the deterministic ID, CLOSED status, four zero monetary fields, demo terminal/user identities, no OPEN row, and one row after reseeding.

APP VENTA production build command:

```powershell
npm.cmd run build --workspace @tpverp/app-venta
```

Result: exit code `0`; TypeScript compilation and Vite production build completed, 142 modules transformed.

Diff hygiene command:

```powershell
git diff --check
```

Result: exit code `0`; no whitespace errors (only repository line-ending conversion warnings).

## Notes

- The first sandboxed Maven wrapper run could not access Maven Central (`Permission denied: getsockopt`); the approved network rerun resolved dependencies.
- Maven emitted existing JDK/Mockito dynamic-agent and native-access forward-compatibility warnings; there were no test failures from them.
- The temporary `.superpowers/sdd/m2-link` junction is removed after final backend verification and is not committed.
