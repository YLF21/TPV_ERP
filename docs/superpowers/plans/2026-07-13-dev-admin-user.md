# Dev ADMIN User Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Crear de forma idempotente el usuario local `ADMIN/0000` cuando está activo el perfil `dev`.

**Architecture:** `DevSampleDataSeeder` ampliará su bloque de seguridad con UUID deterministas para el rol y usuario ADMIN. Una prueba PostgreSQL comprobará datos, contraseña BCrypt, asociación con la tienda e idempotencia real.

**Tech Stack:** Java 25, Spring Boot 4.0.6, PostgreSQL 18, JdbcTemplate, Spring Security PasswordEncoder, JUnit 5, AssertJ, Maven.

## Global Constraints

- El usuario conocido solo puede generarse mediante el seeder del perfil `dev`.
- Mantener `VENDEDOR/0000` sin cambios.
- No añadir ni modificar migraciones Flyway.
- Rol y usuario ADMIN deben ser protegidos, activos y estar asociados a la tienda demo.
- No almacenar `0000` como texto plano en PostgreSQL.

---

### Task 1: Sembrar y validar ADMIN/0000

**Files:**
- Modify: `backend/src/main/java/com/tpverp/backend/dev/DevSampleDataSeeder.java`
- Modify: `backend/src/test/java/com/tpverp/backend/dev/DevSampleDataSeederPostgreSqlTest.java`

**Interfaces:**
- Consumes: `JdbcTemplate`, `PasswordEncoder` y tablas `rol`, `usuario`, `usuario_tienda`.
- Produces: rol `ADMIN` y usuario `ADMIN` asociados a la tienda demo.

- [ ] **Step 1: Escribir la prueba PostgreSQL fallida**

Inyectar `PasswordEncoder` en `DevSampleDataSeederPostgreSqlTest`. Añadir una prueba que consulte el rol y usuario `ADMIN`, compruebe `protegido=true`, `activo=true`, que `passwordEncoder.matches("0000", password_hash)` sea verdadero y que exista exactamente una asociación en `usuario_tienda`. Ejecutar `new DevSampleDataSeeder(jdbc, passwordEncoder).seed()` una segunda vez y verificar que los tres recuentos continúan siendo uno.

- [ ] **Step 2: Confirmar RED**

```powershell
cd E:\workspace\gitwork\TPV_ERP\backend
$env:TPV_TEST_DB_URL = "jdbc:postgresql://localhost:5432/tpv_erp_test"
$env:TPV_TEST_DB_USERNAME = "tpv_erp_test"
$env:TPV_TEST_DB_PASSWORD = "admin"
mvn.cmd "-Dtest=DevSampleDataSeederPostgreSqlTest" test
```

Expected: la prueba falla porque no existe el rol o usuario `ADMIN`.

- [ ] **Step 3: Implementar el seeding mínimo**

Añadir UUID deterministas `ROLE_ADMIN` y `USER_ADMIN`. En `seedSecurity()` insertar el rol `ADMIN` con `protegido=true`, insertar/actualizar el usuario `ADMIN` con `user_id='ADMIN'`, `user_name='Administrador'`, hash generado mediante `passwordEncoder.encode("0000")`, rol ADMIN, `protegido=true`, `activo=true`, idioma `ES`, y crear su fila `usuario_tienda`. Usar `ON CONFLICT` para conservar la idempotencia.

- [ ] **Step 4: Confirmar GREEN y regresión de autenticación**

```powershell
mvn.cmd "-Dtest=DevSampleDataSeederPostgreSqlTest,DevSampleDataSeederTest,AuthenticationServiceTest" test
```

Expected: `BUILD SUCCESS`, sin fallos ni errores.

- [ ] **Step 5: Validar en la base dev actual**

Reiniciar el backend con `SPRING_PROFILES_ACTIVE=dev`. Esperar `Started TpvErpBackendApplication` y enviar una petición al endpoint de login con usuario `ADMIN`, contraseña `0000` y el terminal demo. Expected: respuesta HTTP satisfactoria con sesión administrativa, no `401`.

- [ ] **Step 6: Commit local**

```powershell
git add backend/src/main/java/com/tpverp/backend/dev/DevSampleDataSeeder.java backend/src/test/java/com/tpverp/backend/dev/DevSampleDataSeederPostgreSqlTest.java docs/superpowers/plans/2026-07-13-dev-admin-user.md
git commit -m "feat: seed dev admin user"
```
