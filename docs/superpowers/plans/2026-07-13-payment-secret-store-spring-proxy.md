# Payment Secret Store Spring Proxy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permitir que Spring cree el proxy transaccional de `ProtectedPaymentSecretStore` y que el backend arranque con el perfil `dev`.

**Architecture:** Se conserva `PaymentSecretStore` como interfaz y `ProtectedPaymentSecretStore` como implementación transaccional. La implementación deja de ser `final` para que Spring pueda generar el proxy CGLIB; una prueba de contexto mínima protege esta condición.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring Framework 7.0.7, JUnit 5, AssertJ, Maven.

## Global Constraints

- No cambiar el formato de secretos, la base de datos, las migraciones, las APIs ni la configuración de proveedores.
- No cambiar la configuración global de proxies de Spring.
- Mantener DPAPI y las anotaciones `@Transactional` actuales.

---

### Task 1: Permitir el proxy transaccional del almacén de secretos

**Files:**
- Create: `backend/src/test/java/com/tpverp/backend/terminal/secrets/ProtectedPaymentSecretStoreProxyTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/terminal/secrets/ProtectedPaymentSecretStore.java`

**Interfaces:**
- Consumes: `PaymentSecretStore` y el constructor público de `ProtectedPaymentSecretStore`.
- Produces: un bean `PaymentSecretStore` que Spring puede envolver con un proxy transaccional.

- [ ] **Step 1: Escribir la prueba de regresión**

Crear un contexto con `@EnableTransactionManagement(proxyTargetClass = true)`, un `PlatformTransactionManager` mínimo y un bean `ProtectedPaymentSecretStore`. Comprobar con `AopUtils.isAopProxy(store)` que el bean se crea y queda proxificado.

- [ ] **Step 2: Ejecutar la prueba y confirmar el fallo**

Run:

```powershell
cd E:\workspace\gitwork\TPV_ERP\backend
mvn.cmd "-Dtest=ProtectedPaymentSecretStoreProxyTest" test
```

Expected: error de creación del contexto con `Cannot subclass final class ...ProtectedPaymentSecretStore`.

- [ ] **Step 3: Aplicar la implementación mínima**

Cambiar:

```java
public final class ProtectedPaymentSecretStore implements PaymentSecretStore {
```

por:

```java
public class ProtectedPaymentSecretStore implements PaymentSecretStore {
```

- [ ] **Step 4: Ejecutar las pruebas del componente**

Run:

```powershell
mvn.cmd "-Dtest=ProtectedPaymentSecretStoreProxyTest,PaymentSecretStoreTest,PaymentSecretAdministrationServiceTest" test
```

Expected: `BUILD SUCCESS`, sin fallos ni errores.

- [ ] **Step 5: Verificar el arranque con el perfil dev**

Run:

```powershell
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:TPV_DB_URL = "jdbc:postgresql://localhost:5432/tpv_erp_dev"
$env:TPV_DB_USERNAME = "tpv_erp"
$env:TPV_DB_PASSWORD = "admin"
$env:TPV_SERVER_PORT = "8080"
mvn.cmd spring-boot:run
```

Expected: `Tomcat started on port 8080` y `Started TpvErpBackendApplication`, sin error CGLIB.

- [ ] **Step 6: Crear el commit local**

```powershell
git add backend/src/main/java/com/tpverp/backend/terminal/secrets/ProtectedPaymentSecretStore.java backend/src/test/java/com/tpverp/backend/terminal/secrets/ProtectedPaymentSecretStoreProxyTest.java docs/superpowers/plans/2026-07-13-payment-secret-store-spring-proxy.md
git commit -m "fix: allow transactional payment secret proxy"
```
