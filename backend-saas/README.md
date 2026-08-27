# TPV ERP SaaS Backend

Backend central para licencias, vinculacion de instalaciones y eventos sincronizados desde tiendas.

## Arranque local

```powershell
cd ..
.\tools\start-saas-dev.cmd
```

El panel queda disponible en `http://127.0.0.1:8088` (tambien se admite
`http://localhost:8088` en DEV) y el backend solo se
expone dentro de la red de Compose en `8090`. El override DEV publica PostgreSQL
unicamente en `127.0.0.1:5433`; el compose base de produccion no publica la base
de datos. Para desarrollo sin contenedor web se puede ejecutar
`.\mvnw.cmd spring-boot:run` y `npm.cmd run dev` por separado.
El lanzador `.cmd` evita depender de la politica de ejecucion de scripts de
PowerShell y comprueba la salud del backend antes de anunciar el panel.

Para conectar una instalacion local del ERP al SaaS publicado por Compose,
arranca el backend local con los perfiles `dev,saas-dev` (y
`fiscal-dev` cuando se quiera probar el laboratorio fiscal). El perfil
`saas-dev` usa por defecto `http://127.0.0.1:8088`, que es el gateway web del
Compose; se puede cambiar con `TPV_LICENSE_SAAS_URL`.

## Variables

- `TPV_SAAS_PORT`: puerto HTTP.
- `TPV_SAAS_DB_URL`: JDBC PostgreSQL.
- `TPV_SAAS_DB_USERNAME`: usuario PostgreSQL.
- `TPV_SAAS_DB_PASSWORD`: password PostgreSQL.
- `TPV_SAAS_ADMIN_DEFAULT_ALLOWED`: override temporal para arrancar `prod` con alguna credencial seed conocida todavia activa.
- `TPV_SAAS_SECRET_ENCRYPTION_KEY`: clave AES-256 en Base64 (exactamente 32 bytes) para cifrar credenciales de integraciones. El valor del `.env.example` solo es válido para DEV local.
- `TPV_SAAS_LEGACY_BASIC_AUTH_ENABLED`: compatibilidad temporal con HTTP Basic; por defecto `false`.
- `TPV_SAAS_CORS_ALLOWED_ORIGINS`: origenes web permitidos, separados por coma. Vacio no abre CORS; el override DEV limita el acceso a `127.0.0.1:8088` y `localhost:8088`.
- `TPV_SAAS_WEB_PORT`: puerto publicado del panel web (por defecto `8088`).
- `TPV_SAAS_FORWARD_HEADERS_STRATEGY`: estrategia de cabeceras proxy. Por defecto `framework`.

Los usuarios seed `admin` y `viewer` son solo para el laboratorio. En
produccion deben tener credenciales nuevas o quedar inactivos. Si cualquiera
conserva una credencial seed conocida, el servidor no arranca salvo override
temporal con `TPV_SAAS_ADMIN_DEFAULT_ALLOWED=true`.

## Endpoints base

- `POST /api/v1/auth/login`
- `POST /api/v1/auth/logout`
- `POST /api/v1/admin/companies`
- `PUT /api/v1/admin/companies/{companyId}`
- `GET /api/v1/admin/fiscal-status`
- `GET /api/v1/admin/fiscal-status/companies`
- `GET /api/v1/admin/verifactu-activation-policies`
- `PUT /api/v1/admin/verifactu-activation-policies/{taxpayerType}`
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

El alta de empresa acepta `companyAddress` y `storeAddress` como objetos con
`linea1`, `ciudad`, `codigoPostal`, `provincia` y `pais`. Se conservan en el
SaaS para que una instalacion que parte sin datos locales pueda crear la
empresa y la tienda fiscales al vincular el codigo; no se generan direcciones
de relleno.

La licencia incorpora la fecha obligatoria VERI*FACTU y la version de la
politica aplicable al tipo de obligado. La instalacion local aplica esa fecha
automaticamente antes de emitir y comunica su modalidad efectiva al SaaS. La
vista **Estado fiscal** es deliberadamente de solo lectura: muestra el modo
real por empresa, tienda e instalacion sin permitir que el panel central
reescriba una cadena fiscal local.

El login intercambia usuario y password por un token Bearer opaco de ocho horas.
Los endpoints protegidos aceptan ese token y comprueban en cada peticion que el
usuario siga activo. HTTP Basic solo debe habilitarse durante una migracion
controlada.

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

## Puesta en produccion

Variables minimas:

```powershell
Copy-Item .env.production.example .env.production
# Edita .env.production y define passwords, una clave AES-256 aleatoria y el origen HTTPS real.
```

El Compose base fuerza siempre el perfil `prod`; solo
`docker-compose.dev.yml` puede sustituirlo por `dev`. El dominio de ejemplo de
`.env.production.example` es deliberadamente inutilizable y debe cambiarse.

La imagen desplegable se construye con `backend-saas/Dockerfile` y
`frontend-saas/Dockerfile`. El frontend sirve la SPA y enruta `/api` al
backend en la red interna; publica el puerto web solo sobre loopback. Se debe colocar delante
un proxy HTTPS con dominio, limites de peticiones, HSTS y certificados
gestionados fuera del repositorio.

El despliegue usa exclusivamente el compose base, sin el override DEV:

```powershell
docker compose --env-file .env.production -f docker-compose.yml up -d --build
```

PostgreSQL permanece accesible solo por la red interna de Compose. Si se
necesita administracion remota, debe hacerse mediante una red privada, un tunel
autenticado o una sesion administrativa temporal; no se debe publicar `5432`.

Ejecutar detras de un proxy HTTPS. El Nginx incluido sustituye cualquier
`X-Forwarded-For` enviado por el cliente por la direccion de su interlocutor
inmediato y el backend limita los intentos por cuenta y por codigo de
emparejamiento, sin confiar exclusivamente en esa cabecera. Si existe un proxy
HTTPS exterior, los limites por IP cliente deben aplicarse en ese borde de
confianza, que es donde se conoce la IP real; la aplicacion seguira viendo la
direccion del proxy. `TPV_SAAS_FORWARD_HEADERS_STRATEGY=framework` solo debe
mantenerse cuando el backend siga aislado detras del Nginx del Compose.

En una base nueva, el primer arranque debe hacerse sin trafico publico y con
`TPV_SAAS_ADMIN_DEFAULT_ALLOWED=true` solo durante el bootstrap. Cambiar las
credenciales de `admin` y `viewer` (o desactivar la cuenta que no se use),
volver a `false` y reiniciar antes de admitir trafico. El guard no escribe
usuarios, passwords ni hashes en el log de rechazo.

## Backup y restore PostgreSQL

Backup:

```powershell
pg_dump --format=custom --file ".\backups\tpv_erp_saas_$(Get-Date -Format yyyyMMdd_HHmmss).dump" "$env:TPV_SAAS_DB_URL"
```

Restore en una base vacia:

```powershell
pg_restore --clean --if-exists --dbname "$env:TPV_SAAS_DB_URL" ".\backups\tpv_erp_saas_YYYYMMDD_HHMMSS.dump"
```

Probar un restore completo antes de considerar validada una estrategia de backup.
