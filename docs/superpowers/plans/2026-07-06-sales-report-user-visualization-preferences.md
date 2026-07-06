# Sales Report User Visualization Preferences Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist sales report visualization columns per user in backend so the same user sees the same report columns on every terminal.

**Architecture:** Add a backend JPA table, repository, service, and controller under the document/report area. Add frontend API helpers and sanitization utilities, then wire `SalesReportScreen` to load preferences on open and save them whenever columns change.

**Tech Stack:** Spring Boot, Spring Data JPA, Flyway, PostgreSQL JSONB, React, TypeScript, Vitest.

---

### Task 1: Backend Preference Domain And Service

**Files:**
- Create: `backend/src/main/resources/db/migration/V23__preferencias_visualizacion_informes.sql`
- Create: `backend/src/main/java/com/tpverp/backend/document/ReportVisualizationPreference.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/ReportVisualizationPreferenceRepository.java`
- Create: `backend/src/main/java/com/tpverp/backend/document/ReportVisualizationPreferenceService.java`
- Test: `backend/src/test/java/com/tpverp/backend/document/ReportVisualizationPreferenceServiceTest.java`

- [ ] **Step 1: Write failing service tests**

Cover: list filters by current user and app, save inserts, save updates same user/app/report, blank data rejected, other user not returned.

- [ ] **Step 2: Run service tests and verify RED**

Run: `backend\mvnw.cmd -pl backend -Dtest=ReportVisualizationPreferenceServiceTest test`

- [ ] **Step 3: Implement entity, repository, migration, and service**

Use current authenticated `UserAccount` from `CurrentOrganization.currentUser(authentication)`. Store `visibleAttributes` as JSONB with a JPA converter.

- [ ] **Step 4: Run service tests and verify GREEN**

Run: `backend\mvnw.cmd -pl backend -Dtest=ReportVisualizationPreferenceServiceTest test`

### Task 2: Backend API

**Files:**
- Create: `backend/src/main/java/com/tpverp/backend/document/ReportVisualizationPreferenceController.java`
- Test: `backend/src/test/java/com/tpverp/backend/document/ReportVisualizationPreferenceControllerContractTest.java`

- [ ] **Step 1: Write failing controller contract test**

Assert route `/api/v1/sales-reports/visualization-preferences` and method security exist.

- [ ] **Step 2: Run controller test and verify RED**

Run: `backend\mvnw.cmd -pl backend -Dtest=ReportVisualizationPreferenceControllerContractTest test`

- [ ] **Step 3: Implement GET and PUT controller**

GET returns preferences for authenticated user and requested app. PUT upserts one report preference using authenticated user; request body must not include user or terminal.

- [ ] **Step 4: Run controller test and verify GREEN**

Run: `backend\mvnw.cmd -pl backend -Dtest=ReportVisualizationPreferenceControllerContractTest test`

### Task 3: Frontend Preference API And Sanitization

**Files:**
- Create: `frontend/packages/app-common/src/components/salesReportVisualizationPreferences.ts`
- Test: `frontend/packages/app-common/src/components/salesReportVisualizationPreferences.test.ts`

- [ ] **Step 1: Write failing frontend helper tests**

Cover: sanitizes saved attributes, drops invalid/duplicates, preserves required `total`, falls back to defaults, fetch uses token and app, save body excludes terminal.

- [ ] **Step 2: Run frontend helper tests and verify RED**

Run: `npm test -- salesReportVisualizationPreferences.test.ts`

- [ ] **Step 3: Implement helper module**

Export API types, `sanitizeVisibleAttributes`, `applySavedVisualizationPreferences`, `loadReportVisualizationPreferences`, and `saveReportVisualizationPreference`.

- [ ] **Step 4: Run frontend helper tests and verify GREEN**

Run: `npm test -- salesReportVisualizationPreferences.test.ts`

### Task 4: Wire SalesReportScreen

**Files:**
- Modify: `frontend/packages/app-common/src/components/SalesReportScreen.tsx`
- Modify: `frontend/packages/app-common/src/components/SalesReportScreen.test.tsx`

- [ ] **Step 1: Write failing screen tests**

Cover static render still works and authenticated session with token triggers preference load path without terminal data in helper calls where practical.

- [ ] **Step 2: Run screen tests and verify RED**

Run: `npm test -- SalesReportScreen.test.tsx`

- [ ] **Step 3: Wire loading and saving**

Load preferences in `useEffect` when session token/app changes. Save inside a shared `updateVisibleAttributes` helper used by add/remove/move.

- [ ] **Step 4: Run screen tests and verify GREEN**

Run: `npm test -- SalesReportScreen.test.tsx`

### Task 5: Final Verification

**Files:**
- All changed backend/frontend files.

- [ ] **Step 1: Run backend targeted tests**

Run: `backend\mvnw.cmd -pl backend -Dtest=ReportVisualizationPreferenceServiceTest,ReportVisualizationPreferenceControllerContractTest test`

- [ ] **Step 2: Run frontend tests**

Run: `npm test -- --run`

- [ ] **Step 3: Run frontend build**

Run: `npm run build`

- [ ] **Step 4: Review diff**

Run: `git diff --stat` and `git diff --check`.
