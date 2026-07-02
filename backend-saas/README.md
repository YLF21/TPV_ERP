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
- `TPV_SAAS_ADMIN_KEY`: clave temporal para endpoints `/api/v1/admin/**`.

## Endpoints base

- `POST /api/v1/admin/companies`
- `POST /api/v1/admin/licenses/{reference}/renew`
- `POST /api/v1/admin/licenses/{reference}/block`
- `POST /api/v1/admin/licenses/{reference}/unblock`
- `POST /api/v1/admin/licenses/{reference}/pairing-codes`
- `POST /api/v1/license/link`
- `POST /api/v1/license/validate`
- `POST /api/v1/sync/events`
