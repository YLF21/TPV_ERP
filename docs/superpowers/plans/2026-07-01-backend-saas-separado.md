# Backend SaaS Separado Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Crear un backend SaaS independiente compatible con el backend local ya integrado.

**Architecture:** Nuevo proyecto Spring Boot en `backend-saas/backend-saas`, separado del backend local. Expone los endpoints que ya consume la tienda local para vinculacion, validacion de licencia y recepcion de eventos sync.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring MVC, Spring Data JPA, Flyway, PostgreSQL en runtime, H2 en tests.

---

### Task 1: Scaffold

**Files:**
- Create: `backend-saas/backend-saas/pom.xml`
- Create: `backend-saas/backend-saas/src/main/java/com/tpverp/saas/TpvErpSaasApplication.java`
- Create: `backend-saas/backend-saas/src/main/resources/application.yml`

- [x] Crear un proyecto Maven independiente con dependencias web, validation, data-jpa, flyway, postgresql y h2 para tests.
- [x] Copiar Maven wrapper desde `backend/` para poder ejecutar `.\mvnw.cmd test` dentro del nuevo proyecto.

### Task 2: License Link And Validation

**Files:**
- Create: `backend-saas/backend-saas/src/main/java/com/tpverp/saas/license/*`
- Create: `backend-saas/backend-saas/src/main/resources/db/migration/V1__saas_core.sql`
- Test: `backend-saas/backend-saas/src/test/java/com/tpverp/saas/license/LicenseApiTest.java`

- [x] Crear entidades SaaS para empresa, tienda, licencia, codigo de enlace e instalacion.
- [x] Implementar `POST /api/v1/license/link`.
- [x] Implementar `POST /api/v1/license/validate`.
- [x] Validar `X-TPV-Installation-Token` en llamadas autenticadas de instalacion.

### Task 3: Sync Events

**Files:**
- Create: `backend-saas/backend-saas/src/main/java/com/tpverp/saas/sync/*`
- Test: `backend-saas/backend-saas/src/test/java/com/tpverp/saas/sync/SyncEventApiTest.java`

- [x] Implementar `POST /api/v1/sync/events`.
- [x] Guardar eventos crudos por `eventId` de forma idempotente.
- [x] Rechazar eventos sin token valido.

### Task 4: Minimal Admin API

**Files:**
- Create: `backend-saas/backend-saas/src/main/java/com/tpverp/saas/admin/*`
- Test: `backend-saas/backend-saas/src/test/java/com/tpverp/saas/admin/AdminApiTest.java`

- [x] Implementar `POST /api/v1/admin/companies` para crear empresa, tienda, licencia y codigo de enlace.
- [x] Implementar bloqueo/desbloqueo manual de licencia.
- [x] Proteger `/api/v1/admin/**` con `X-TPV-SaaS-Admin-Key`.

### Task 5: Verification

- [x] Ejecutar `.\mvnw.cmd test` en `backend-saas/backend-saas`.
- [x] Revisar `git status --short` para asegurar que solo entra el nuevo proyecto y el plan.
