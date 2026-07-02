# TPV ERP SaaS Backend

Backend central para licencias, vinculacion de instalaciones y eventos sincronizados desde tiendas.

## Arranque local

```powershell
Copy-Item .env.example .env
docker compose --env-file .env up -d
.\mvnw.cmd spring-boot:run
```

Por defecto escucha en `http://localhost:8090`.

## Variables

- `TPV_SAAS_PORT`: puerto HTTP.
- `TPV_SAAS_DB_URL`: JDBC PostgreSQL.
- `TPV_SAAS_DB_USERNAME`: usuario PostgreSQL.
- `TPV_SAAS_DB_PASSWORD`: password PostgreSQL.
- `TPV_SAAS_ADMIN_DEFAULT_ALLOWED`: permite arrancar con perfil `prod` usando `admin/admin` si vale `true`.
El usuario inicial de administracion es `admin` con password `admin`.
En produccion debe cambiarse esa password tras el primer arranque. Si sigue activa con perfil `prod`, el servidor no arranca salvo override temporal con `TPV_SAAS_ADMIN_DEFAULT_ALLOWED=true`.

## Endpoints base

- `POST /api/v1/admin/companies`
- `PUT /api/v1/admin/companies/{companyId}`
- `POST /api/v1/admin/licenses/{reference}/renew`
- `POST /api/v1/admin/licenses/{reference}/block`
- `POST /api/v1/admin/licenses/{reference}/unblock`
- `POST /api/v1/admin/licenses/{reference}/pairing-codes`
- `GET /api/v1/admin/users`
- `POST /api/v1/admin/users`
- `PUT /api/v1/admin/users/{username}/password`
- `DELETE /api/v1/admin/users/{username}`
- `GET /api/v1/admin/audit`
- `POST /api/v1/license/link`
- `POST /api/v1/license/validate`
- `POST /api/v1/sync/events`

Los endpoints `/api/v1/admin/**` usan HTTP Basic Auth.

## Tests PostgreSQL reales

```powershell
.\mvnw.cmd "-Dtest=AdminApiPostgresIT" test
```

Requiere Docker. Si Docker no esta disponible, Testcontainers marca el test como omitido.

## Test E2E HTTP licencia

```powershell
.\mvnw.cmd "-Dtest=LicenseHttpE2ETest" test
```

Levanta el SaaS en un puerto aleatorio y valida por HTTP el flujo: crear empresa, vincular instalacion local, validar licencia y bloquearla manualmente.
