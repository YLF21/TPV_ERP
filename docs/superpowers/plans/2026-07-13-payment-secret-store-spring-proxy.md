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

---

### Task 1 extension: Remove the optional bridge's unconditional JSON dependency

**Approved scope:** The initial `dev` startup check exposed an unrelated missing `ObjectMapper` bean after the payment-secret proxy was fixed. The user approved extending Task 1 to remove that startup blocker without adding a global bean or dependency.

**Files:**
- Create: `backend/src/test/java/com/tpverp/backend/terminal/bridge/PaymentTerminalBridgeConfigurationTest.java`
- Modify: `backend/src/main/java/com/tpverp/backend/terminal/bridge/PaymentTerminalBridgeConfiguration.java`

**Interfaces:**
- Consumes: `PaymentTerminalBridgeClient`, `UnavailablePaymentTerminalBridgeClient`, `HttpPaymentTerminalBridgeClient`, and the existing bridge URL/token properties.
- Produces: an unavailable client without requiring an `ObjectMapper` bean when the bridge is not configured; a configured HTTP client with a local module-aware `JsonMapper` otherwise.

- [ ] **Step 1: Write the failing configuration test**

Create an `AnnotationConfigApplicationContext` containing only `PaymentTerminalBridgeConfiguration` and assert that its `PaymentTerminalBridgeClient` is an `UnavailablePaymentTerminalBridgeClient`.

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
cd backend
mvn.cmd "-Dtest=PaymentTerminalBridgeConfigurationTest" test
```

Expected: context creation fails because the factory method requires an `ObjectMapper` bean even though URL/token are empty.

- [ ] **Step 3: Apply the minimum implementation**

Remove `ObjectMapper` from the `@Bean` method arguments. Keep the early unavailable-client return, and only after URL/token are known to be present construct `JsonMapper.builder().findAndAddModules().build()` for `HttpPaymentTerminalBridgeClient`.

- [ ] **Step 4: Verify bridge and secret tests**

Run:

```powershell
mvn.cmd "-Dtest=PaymentTerminalBridgeConfigurationTest,HttpPaymentTerminalBridgeClientTest,LocalPaymentTerminalBridgeClientTest,ProtectedPaymentSecretStoreProxyTest,PaymentSecretStoreTest,PaymentSecretAdministrationServiceTest,PaymentSecretControllerContractTest" test
```

Expected: `BUILD SUCCESS`, 17 tests, no failures or errors.

- [ ] **Step 5: Verify bounded dev startup**

Run with the existing `dev` environment until both `Tomcat started on port 8080` and `Started TpvErpBackendApplication` appear. Send Ctrl+C immediately afterward and verify Spring reports graceful shutdown.

- [ ] **Step 6: Commit the approved extension**

```powershell
git add backend/src/main/java/com/tpverp/backend/terminal/bridge/PaymentTerminalBridgeConfiguration.java backend/src/test/java/com/tpverp/backend/terminal/bridge/PaymentTerminalBridgeConfigurationTest.java docs/superpowers/plans/2026-07-13-payment-secret-store-spring-proxy.md
git commit -m "fix: make terminal bridge JSON optional"
```
