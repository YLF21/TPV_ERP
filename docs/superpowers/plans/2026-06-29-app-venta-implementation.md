# APP VENTA Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the first runnable JavaFX frontend for **APP VENTA** with tested core sales rules and a keyboard-first ticket screen.

**Architecture:** Add a Maven multi-module project under `frontend/`. Put pure business/UI-state logic in `app-common` so it can be tested without JavaFX, and keep `app-venta` focused on the runnable JavaFX shell and FXML/CSS.

**Tech Stack:** Java 25, Maven Wrapper, JavaFX, JUnit 5.

---

### Task 1: Frontend Maven Scaffold

**Files:**
- Create: `frontend/pom.xml`
- Create: `frontend/app-common/pom.xml`
- Create: `frontend/app-venta/pom.xml`
- Copy: `frontend/mvnw`, `frontend/mvnw.cmd`, `frontend/.mvn/wrapper/maven-wrapper.properties`

- [ ] **Step 1: Create Maven wrapper and module poms**

Use the backend Maven wrapper files as-is for `frontend/`. Create a root pom with modules `app-common` and `app-venta`. Configure Java 25, JUnit 5, JavaFX 25.0.2, and the JavaFX Maven plugin for `app-venta`.

- [ ] **Step 2: Run baseline frontend tests**

Run from `frontend/`: `.\mvnw.cmd test`
Expected: build succeeds, even before tests are added.

### Task 2: Common Sales Rules

**Files:**
- Create: `frontend/app-common/src/main/java/com/tpverp/frontend/common/security/Permission.java`
- Create: `frontend/app-common/src/main/java/com/tpverp/frontend/common/security/PermissionRules.java`
- Create: `frontend/app-common/src/main/java/com/tpverp/frontend/common/sales/QuickCommand.java`
- Create: `frontend/app-common/src/main/java/com/tpverp/frontend/common/sales/SaleLine.java`
- Create: `frontend/app-common/src/main/java/com/tpverp/frontend/common/sales/TicketSale.java`
- Test: `frontend/app-common/src/test/java/com/tpverp/frontend/common/security/PermissionRulesTest.java`
- Test: `frontend/app-common/src/test/java/com/tpverp/frontend/common/sales/TicketSaleTest.java`

- [ ] **Step 1: Write tests for permissions and sale-line behavior**

Cover APP VENTA entry permissions, `GESTION_PRODUCTO` not granting entry alone, selected-line edits, discounts, package conversion, and totals.

- [ ] **Step 2: Implement minimal common classes**

Use records/enums and `BigDecimal`. Keep logic independent from JavaFX.

- [ ] **Step 3: Run common tests**

Run from `frontend/`: `.\mvnw.cmd -pl app-common test`
Expected: tests pass.

### Task 3: APP VENTA JavaFX Shell

**Files:**
- Create: `frontend/app-venta/src/main/java/com/tpverp/frontend/venta/AppVentaApplication.java`
- Create: `frontend/app-venta/src/main/java/com/tpverp/frontend/venta/AppVentaController.java`
- Create: `frontend/app-venta/src/main/resources/com/tpverp/frontend/venta/app-venta.fxml`
- Create: `frontend/app-venta/src/main/resources/com/tpverp/frontend/venta/styles/app-venta.css`
- Create: `frontend/app-venta/src/main/resources/com/tpverp/frontend/venta/i18n/messages_es.properties`
- Create: `frontend/app-venta/src/main/resources/com/tpverp/frontend/venta/i18n/messages_en.properties`
- Create: `frontend/app-venta/src/main/resources/com/tpverp/frontend/venta/i18n/messages_zh.properties`

- [ ] **Step 1: Build the FXML layout**

Create a dense operator layout: central table, right total panel, quick field, and lower summary/actions.

- [ ] **Step 2: Wire controller state**

Seed a few local sample products so the app is usable without backend. Implement quick-field handlers for Enter, Pause, `/`, `Ctrl+/`, `Ctrl+*`, `Av Pag`, `Ctrl+G`, and `Ctrl+F`.

- [ ] **Step 3: Style the UI**

Use industrial high-contrast styling. Selected line must have dark background, white bold text, and larger type.

### Task 4: Verify

**Files:**
- Modify as needed from previous tasks only.

- [ ] **Step 1: Run all frontend tests**

Run from `frontend/`: `.\mvnw.cmd test`
Expected: all tests pass.

- [ ] **Step 2: Compile APP VENTA**

Run from `frontend/`: `.\mvnw.cmd -pl app-venta -am test`
Expected: build succeeds.

- [ ] **Step 3: Check git diff**

Run: `git diff --check`
Expected: no whitespace errors.
