# Fase 9 Maestros ERP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add tenant-scoped ERP masters for customers, products, suppliers, and warehouses.

**Architecture:** Store masters in SaaS database tables keyed by `company_id`. Expose admin endpoints by company and tenant endpoints for the authenticated customer company. Reuse the existing React SaaS shell with a new Masters view and four compact tabs.

**Tech Stack:** Spring Boot, Flyway, JdbcTemplate, MockMvc tests, React, TypeScript, Vite.

---

### Task 1: Backend API and Database

**Files:**
- Create: `backend-saas/src/main/resources/db/migration/V10__saas_phase9_erp_masters.sql`
- Create response/request records in `backend-saas/src/main/java/com/tpverp/saas/admin/`
- Modify: `backend-saas/src/main/java/com/tpverp/saas/admin/AdminController.java`
- Modify: `backend-saas/src/main/java/com/tpverp/saas/admin/AdminService.java`
- Modify: `backend-saas/src/main/java/com/tpverp/saas/admin/AdminPermission.java`
- Modify: `backend-saas/src/main/java/com/tpverp/saas/admin/AdminAuthInterceptor.java`
- Modify: `backend-saas/src/main/java/com/tpverp/saas/tenant/TenantController.java`
- Modify: `backend-saas/src/main/java/com/tpverp/saas/tenant/TenantService.java`
- Test: `backend-saas/src/test/java/com/tpverp/saas/admin/AdminApiTest.java`

- [ ] Write failing tests for company isolation and tenant self-service.
- [ ] Add Flyway schema for the four master tables.
- [ ] Add request/response records.
- [ ] Add admin and tenant endpoints.
- [ ] Implement JdbcTemplate service methods.
- [ ] Add `MANAGE_ERP_MASTERS` permission for admin mutations.

### Task 2: Frontend API and Types

**Files:**
- Modify: `frontend-saas/src/lib/types.ts`
- Modify: `frontend-saas/src/lib/api.ts`

- [ ] Add TypeScript types for ERP master rows and tenant portal data.
- [ ] Add admin API calls by company.
- [ ] Add tenant API calls for own company masters.

### Task 3: Frontend Screens

**Files:**
- Modify: `frontend-saas/src/App.tsx`
- Modify: `frontend-saas/src/styles.css`

- [ ] Add `masters` view to navigation.
- [ ] Add admin masters screen with company selector, tabs, forms, and tables.
- [ ] Add tenant portal masters section.
- [ ] Add responsive styles consistent with the current SaaS UI.

### Task 4: Verification

**Files:**
- Verify changed backend and frontend files.

- [ ] Run frontend TypeScript checks.
- [ ] Try backend compile/tests; if Maven network is blocked, record the blocker.
- [ ] Run `git diff --check`.
