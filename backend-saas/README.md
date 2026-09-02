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
- `TPV_SAAS_SECRET_ENCRYPTION_KEY`: clave AES-256 en Base64 (exactamente 32 bytes) para cifrar credenciales de integraciones. El valor del `.env.example` solo es válido para DEV local.
- `TPV_SAAS_LEGACY_BASIC_AUTH_ENABLED`: compatibilidad temporal con HTTP Basic; por defecto `false`.
- `TPV_SAAS_SESSION_LIFETIME`: duración fija de cada token Bearer; por defecto `PT8H`.
- `TPV_SAAS_CORS_ALLOWED_ORIGINS`: origenes web permitidos, separados por coma. Vacio no abre CORS; el override DEV limita el acceso a `127.0.0.1:8088` y `localhost:8088`.
- `TPV_SAAS_WEB_PORT`: puerto publicado del panel web (por defecto `8088`).
- `TPV_SAAS_FORWARD_HEADERS_STRATEGY`: estrategia de cabeceras proxy. Por defecto `framework`.
- `TPV_SAAS_{POSTGRES,BACKEND,FRONTEND}_{CPU,MEMORY,PIDS}_LIMIT`: límites
  de recursos de los tres contenedores. Compose incluye valores conservadores
  que deben contrastarse con una prueba de carga antes de producción.
- `TPV_SAAS_LOG_MAX_SIZE` y `TPV_SAAS_LOG_MAX_FILES`: rotación local del driver
  `json-file`; por defecto `10m` y tres archivos por contenedor.

Los usuarios seed `admin` y `viewer` son solo para el laboratorio. En
produccion deben tener credenciales nuevas o quedar inactivos. Si cualquiera
conserva una credencial seed conocida, incluido `ADMIN` / `0000`, el servidor
no arranca. Los perfiles `prod` y `local` son mutuamente excluyentes.

Con el perfil `local`, el acceso administrativo local es `ADMIN` / `0000`,
igual que en APP VENTA y APP GESTION. Esta credencial no se carga en producción ni añade datos operativos demo.

## Endpoints base

- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `POST /api/v1/auth/logout-all`
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

El login intercambia usuario y password por un token Bearer opaco persistido
como hash en PostgreSQL. La renovación rota el token anterior y `logout-all`
revoca todas las sesiones del usuario. Los endpoints protegidos comprueban en
cada petición que el usuario siga activo. HTTP Basic solo debe habilitarse durante una migración
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
La publicación se considera bloqueada si falta cualquier secreto obligatorio;
los valores de los archivos `*.example` nunca son credenciales de producción.

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
El contenedor Nginx sirve HTTP deliberadamente y solo publica en loopback: el
proxy exterior es el contrato de terminación TLS. Antes de abrir tráfico debe
aportar un certificado válido, redirección HTTP a HTTPS, HSTS emitido únicamente
sobre HTTPS, límites de tamaño y frecuencia y sobrescritura de cabeceras
`Forwarded`/`X-Forwarded-*`. No se debe publicar directamente el puerto interno.

En una base nueva, el bootstrap debe hacerse sin tráfico público: cambie directamente
las credenciales seed de `admin` y `viewer` (o desactive la cuenta que no se use)
antes de arrancar con `prod`. No existe override para credenciales inseguras. El guard no escribe
usuarios, passwords ni hashes en el log de rechazo.

## Puerta de release

Una entrega solo es apta para producción cuando se cumplen todos estos puntos:

1. El commit de release contiene todas las migraciones y fuentes necesarias y
   no incluye `.env`, claves, dumps ni evidencias generadas bajo `audits/`.
2. Se completa `RELEASE_CHECKLIST.md`. CI termina verde, incluido el E2E SaaS
   con PostgreSQL real, build de imágenes fijadas por digest, auditoría de
   dependencias, SBOM y migraciones Flyway aplicadas desde cero.
3. `docker compose ... config --quiet` se ejecuta con variables productivas
   inyectadas por el gestor de secretos, nunca desde el repositorio.
4. Las migraciones se ensayan primero sobre una restauración reciente y se
   conserva un backup verificable previo al despliegue.
5. Tras arrancar, `/actuator/health` y `/actuator/saasSecurity` responden 2xx
   desde la red interna. El proxy HTTPS supera además una comprobación de
   certificado, HSTS, cabeceras de seguridad y rate limiting.

Comprobación TLS mínima desde una máquina exterior al despliegue:

```powershell
$publicUrl = 'https://saas.example.com/'
$response = Invoke-WebRequest -Uri $publicUrl -MaximumRedirection 0
if ($response.BaseResponse.RequestMessage.RequestUri.Scheme -ne 'https') { throw 'La URL pública no termina en HTTPS' }
if (-not $response.Headers['Strict-Transport-Security']) { throw 'Falta HSTS en el borde TLS' }
if ($response.Headers['Server']) { Write-Warning 'El borde publica la cabecera Server; revisar ocultamiento' }
```

La evidencia del proxy debe incluir además la redirección HTTP→HTTPS, renovación
del certificado y una prueba controlada del límite de peticiones. HSTS se emite
en el proxy TLS, nunca en el Nginx HTTP interno.

## Backup y restore PostgreSQL

Estos comandos usan el usuario y base ya inyectados dentro del contenedor; no
intentan pasar la URL JDBC a herramientas PostgreSQL ni muestran passwords.
Crear el dump, verificar que PostgreSQL puede leerlo y generar su checksum:

```powershell
$stamp = Get-Date -Format yyyyMMdd_HHmmss
$backup = ".\backups\tpv_erp_saas_$stamp.dump"
New-Item -ItemType Directory -Force .\backups | Out-Null
docker compose --env-file .env.production -f docker-compose.yml exec -T postgres sh -c 'pg_dump --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --format=custom --file=/tmp/tpv-erp-saas.dump'
docker compose --env-file .env.production -f docker-compose.yml exec -T postgres sh -c 'pg_restore --list /tmp/tpv-erp-saas.dump >/dev/null'
docker compose --env-file .env.production -f docker-compose.yml cp postgres:/tmp/tpv-erp-saas.dump $backup
(Get-FileHash -Algorithm SHA256 $backup).Hash | Set-Content -Encoding ascii "$backup.sha256"
```

El restore es destructivo. Ejecútelo en ventana de mantenimiento, con tráfico
detenido, y ensáyelo antes sobre una base aislada. El backend se detiene para
drenar su pool de conexiones; si `pg_restore` falla, manténgalo detenido y
restaure el backup anterior antes de reabrir tráfico:

```powershell
$backup = ".\backups\tpv_erp_saas_YYYYMMDD_HHMMSS.dump"
$expected = (Get-Content "$backup.sha256" -Raw).Trim()
$actual = (Get-FileHash -Algorithm SHA256 $backup).Hash
if ($actual -ne $expected) { throw 'Checksum SHA-256 del backup no válido' }
docker compose --env-file .env.production -f docker-compose.yml stop saas-frontend saas-backend
docker compose --env-file .env.production -f docker-compose.yml cp $backup postgres:/tmp/tpv-erp-saas-restore.dump
docker compose --env-file .env.production -f docker-compose.yml exec -T postgres sh -c 'pg_restore --single-transaction --clean --if-exists --no-owner --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" /tmp/tpv-erp-saas-restore.dump'
docker compose --env-file .env.production -f docker-compose.yml up -d saas-backend saas-frontend
docker compose --env-file .env.production -f docker-compose.yml exec -T saas-backend curl --fail --silent http://127.0.0.1:8090/actuator/health
docker compose --env-file .env.production -f docker-compose.yml exec -T saas-backend curl --fail --silent http://127.0.0.1:8090/actuator/saasSecurity
```

Un archivo creado no es un backup validado hasta que `pg_restore --list` pasa y
una restauración aislada arranca la aplicación y supera ambos healthchecks.

### Retención, cifrado y objetivos

El dump y su `.sha256` deben copiarse inmediatamente a almacenamiento cifrado,
con control de acceso y una segunda ubicación. No se almacenan junto a
`.env.production` ni a la clave AES de la aplicación. Política mínima inicial:
siete copias diarias, cuatro semanales y doce mensuales; ajústese a las
obligaciones contractuales y legales.

Antes de producción se registran y aprueban dos objetivos medidos:

- **RPO**: pérdida máxima aceptable de datos. El intervalo de backup debe ser
  menor o igual al RPO; para menor pérdida se requiere archivado WAL/PITR.
- **RTO**: tiempo máximo de recuperación. Se mide desde la parada hasta que los
  dos healthchecks y el smoke funcional vuelven a estar verdes.

Cada trimestre se ensaya una restauración en infraestructura aislada, se anota
duración, checksum, versión de imagen y resultado Flyway, y se revisan las
copias caducadas conforme a la política de retención.
