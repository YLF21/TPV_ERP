# Sale Desktop Close, Shortcuts and Demo Members Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make desktop shutdown complete safely, wire every advertised sale shortcut to its real action, and seed Bronze/Silver/Gold demo members.

**Architecture:** Keep shutdown orchestration in `SessionTopControls` and inject a browser fallback owned by the screen. Add one keyboard dispatcher in `SaleScreen` that delegates to the existing button callbacks and guards modal/busy/disabled state. Extend the dev-only JDBC seeder with deterministic categories, customers and memberships so pricing remains backend-authoritative.

**Tech Stack:** React 18, TypeScript, Vitest, Testing Library, Spring Boot, JdbcTemplate, PostgreSQL, JUnit 5, AssertJ.

## Global Constraints

- Do not change production seed data; demo members are loaded only by the `dev` profile.
- Do not bypass existing payment locks or modal guards.
- Browser shutdown fallback logs out to the login screen; Electron shutdown closes the process.
- Preserve unrelated migration renumbering changes already present in the worktree.

---

### Task 1: Safe desktop and browser shutdown

**Files:**
- Modify: `frontend/packages/app-common/src/components/SessionTopControls.tsx`
- Modify: `frontend/packages/app-common/src/components/SessionTopControls.test.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`

**Interfaces:**
- Consumes: `onPrepareShutdown(): Promise<boolean>` and Electron `window.tpvDesktop.closeApplication()`.
- Produces: optional `onBrowserClose(): void | Promise<void>` fallback invoked after successful preparation when Electron is absent.

- [ ] **Step 1: Write failing tests** proving successful browser preparation invokes `onBrowserClose`, Electron still invokes its bridge, and blocked preparation invokes neither.
- [ ] **Step 2: Run the focused test** with `npm.cmd test --workspace @tpverp/app-common -- SessionTopControls.test.tsx`; expect the browser fallback test to fail because the prop does not exist.
- [ ] **Step 3: Implement the minimal fallback** in `SessionTopControls` and connect it in `SaleScreen` to the existing logout transition.
- [ ] **Step 4: Run the focused tests** and expect all shutdown cases to pass.

### Task 2: Functional sale keyboard shortcuts

**Files:**
- Modify: `frontend/packages/app-common/src/components/SaleScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/SaleScreen.test.tsx`

**Interfaces:**
- Consumes: existing search, quantity, customer, discount, cancel-line, cash, card and pending callbacks.
- Produces: one window `keydown` listener mapping `F2`, `F5`, `F6`, `F7`, `Delete`, `F10`, `F11`, and `F12` to those callbacks.

- [ ] **Step 1: Write failing tests** dispatching each supported key and asserting the same observable result as clicking the corresponding control.
- [ ] **Step 2: Add guard tests** for modal-open, payment-locked, disabled action and repeated key events.
- [ ] **Step 3: Run the focused test** with `npm.cmd test --workspace @tpverp/app-common -- SaleScreen.test.tsx`; expect failures because no global dispatcher exists.
- [ ] **Step 4: Implement the minimal dispatcher** using the existing callbacks and state guards, calling `preventDefault()` only for handled keys.
- [ ] **Step 5: Run the focused tests** and expect them all to pass.

### Task 3: Bronze, Silver and Gold dev members

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/dev/DevSampleDataSeeder.java`
- Modify: `backend/src/test/java/com/tpverp/backend/dev/DevSampleDataSeederPostgreSqlTest.java`

**Interfaces:**
- Consumes: `cliente`, `member_category`, and `miembro` schema from Flyway migrations V30/V33.
- Produces: deterministic demo categories with discounts 5/10/15 and one active member customer in each category.

- [ ] **Step 1: Write a failing PostgreSQL test** selecting demo customers through `miembro` and `member_category`, asserting names, category codes and percentages, then invoking `seeder.seed()` again and asserting no duplicates.
- [ ] **Step 2: Run the focused backend test** with `mvn.cmd -Dtest=DevSampleDataSeederPostgreSqlTest test`; expect the new assertion to fail because only one non-member client exists.
- [ ] **Step 3: Implement deterministic category/customer/member helpers** with `on conflict` upserts that preserve the requested demo values.
- [ ] **Step 4: Run the focused backend test** and expect it to pass.

### Task 4: Integrated verification

**Files:**
- Verify only; no additional production files expected.

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces: evidence that frontend and backend behavior remain compatible.

- [ ] **Step 1: Run app-common tests** with `npm.cmd test --workspace @tpverp/app-common` and expect zero failures.
- [ ] **Step 2: Build the frontend** with `npm.cmd run build --workspace @tpverp/app-venta` and expect a successful Vite build.
- [ ] **Step 3: Run relevant backend tests** with `mvn.cmd -Dtest=DevSampleDataSeederTest,DevSampleDataSeederPostgreSqlTest test` and expect zero failures.
- [ ] **Step 4: Review `git diff`** to confirm only the approved behavior, tests, seed data and documentation changed, leaving unrelated migration work untouched.
