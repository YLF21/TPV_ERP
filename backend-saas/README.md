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
El override activa los perfiles `dev,local`: `.env.example` contiene únicamente
credenciales de laboratorio y no exige bootstrap ni webhooks productivos. El
acceso administrativo del Compose DEV es `ADMIN` / `0000`.

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
- `TPV_SAAS_BOOTSTRAP_ADMIN_PASSWORD`: password temporal robusto para sustituir la credencial seed de `admin` en el primer arranque productivo; se conserva exclusivamente en el gestor de secretos y debe rotarse después del bootstrap.
- `TPV_SAAS_SECURITY_WEBHOOK_URL` / `SECRET` y `TPV_SAAS_INTEGRATION_WEBHOOK_URL` / `SECRET`: contrato reservado para los futuros canales autorizados. La imagen actual no registra esos proveedores y definir las variables no habilita entregas ni readiness.
- `TPV_SAAS_WEBHOOK_CONNECT_TIMEOUT` y `TPV_SAAS_WEBHOOK_REQUEST_TIMEOUT`: valores reservados para esos proveedores; por defecto `PT3S` y `PT10S`.
- `TPV_SAAS_SECURITY_PAYLOAD_RETENTION`, `TPV_SAAS_INTEGRATION_PAYLOAD_RETENTION`, `TPV_SAAS_PAYLOAD_PURGE_BATCH_SIZE` y `TPV_SAAS_SECURITY_PAYLOAD_PURGE_DELAY`: retencion de payloads cifrados terminales, tamano de lote y frecuencia del job de purga; por defecto `P30D`, `P30D`, 500 filas y 24 horas. Nunca purga filas `PENDING`, `PROCESSING` o `FAILED`.
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

Los usuarios seed son solo para el laboratorio. En producción deben tener
credenciales nuevas o quedar inactivos. Si cualquiera conserva una credencial
conocida, el servidor no arranca. Los perfiles `prod` y `local` son mutuamente
excluyentes.

Con el perfil `local`, el acceso administrativo es `ADMIN` / `0000`. La política
solicitada permite crear, activar y cambiar usuarios con contraseñas de al menos
cuatro caracteres. En producción se mantiene el bootstrap inicial mediante
secreto, pero los usuarios pueden elegir posteriormente una contraseña de cuatro
caracteres.

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
- `GET /api/v1/admin/outbox/failures`
- `POST /api/v1/admin/outbox/security/{id}/requeue`
- `POST /api/v1/admin/outbox/security/{id}/acknowledge`
- `POST /api/v1/admin/outbox/integrations/{id}/requeue`
- `POST /api/v1/admin/outbox/integrations/{id}/acknowledge`
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
# Edita .env.production y define passwords, una clave AES-256 aleatoria,
# el bootstrap inicial y el origen HTTPS real. Las variables webhook quedan
# vacías hasta que exista un proveedor expresamente autorizado.
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

V59 revoca la credencial corta histórica antes de aceptar tráfico. En una base
nueva o actualizada, el primer arranque con `prod` exige
`TPV_SAAS_BOOTSTRAP_ADMIN_PASSWORD`: rota el usuario `admin`, obliga a cambiar
la contraseña en el primer login y mantiene inactivo el usuario `viewer` seed.
Después del bootstrap se retira el secreto temporal. El guard no escribe
usuarios, contraseñas ni hashes en el log de rechazo.

La entrega externa continúa deliberadamente fail-closed. Antes de registrar los
proveedores se deben aprobar destinos HTTPS concretos o una allowlist, el envío del
token de recuperación de un solo uso y de los payloads de integración, el esquema
HMAC, idempotencia, timeouts y tratamiento de errores. Hasta incorporar y probar esos
proveedores, `/actuator/health` queda fuera de servicio y Compose no abre el panel;
rellenar las variables reservadas no cambia este estado.

Los administradores con `MANAGE_OPERATIONS` pueden inspeccionar fallos sin exponer
payloads ni secretos mediante
`GET /api/v1/admin/outbox/failures?channel=SECURITY|INTEGRATION&limit=1..100&cursor=<opaco>`;
la respuesta paginada contiene `items` y `nextCursor`. Un fallo puede
reintentarse con `POST /api/v1/admin/outbox/{security|integrations}/{id}/requeue`
o reconocerse como resuelto con la ruta equivalente `/acknowledge`; ambos cuerpos
requieren `{"reason":"motivo operativo verificable"}` y generan auditoria. Requeue
conserva la misma clave de idempotencia; acknowledge es terminal y no reenvia datos.

## Puerta de release

Una entrega solo es apta para producción cuando se cumplen todos estos puntos:

1. El commit de release contiene todas las migraciones y fuentes necesarias y
   no incluye `.env`, claves, dumps ni evidencias generadas bajo `audits/`.
2. Se completa `RELEASE_CHECKLIST.md`. CI termina verde, incluidos AdminApi y
   permisos outbox con PostgreSQL, el E2E autónomo fiscal/outbox y el smoke de
   readiness/autenticación contra backend real, build de imágenes fijadas por
   digest, auditoría de dependencias, SBOM y migraciones Flyway desde cero.
3. `docker compose ... config --quiet` se ejecuta con variables productivas
   inyectadas por el gestor de secretos, nunca desde el repositorio.
4. Las migraciones se ensayan primero sobre una restauración reciente y se
   conserva un backup verificable previo al despliegue.
5. Tras arrancar, `/actuator/health` y `/actuator/saasSecurity` responden 2xx
   desde la red interna. El proxy HTTPS supera además una comprobación de
   certificado, HSTS, cabeceras de seguridad y rate limiting.

CI publica `saas-release-evidence` con el SHA del candidato, hashes de Compose,
Dockerfiles, Nginx y V49-V52, además del inventario local de imágenes. Si se
configura la variable de repositorio `TPV_SAAS_PUBLIC_URL`, el gate exige HTTPS y
HSTS reales. `TPV_SAAS_APPROVED_RPO` y `TPV_SAAS_APPROVED_RTO` se registran solo
como entradas aprobadas; CI declara explícitamente que no ejecuta restore ni
rollback y que tampoco convierte esos valores en evidencia medida.

El artefacto distingue también el alcance: la UI fiscal/outbox se prueba de forma
autónoma y reproducible; AdminApi, persistencia y permisos outbox se validan en el
job PostgreSQL; el navegador contra backend real cubre readiness y autenticación.
No se etiqueta ese último smoke como E2E fiscal/outbox real.

### Ventana de migración V49-V52

V49 reconstruye los índices activos de entrega y V50 reemplaza constraints y
actualiza claims huérfanos, por lo que ambos se ensayan con el volumen real y una
ventana de bloqueo medida. V51 y V52 contienen exclusivamente `CREATE INDEX CONCURRENTLY`:
Flyway la ejecuta fuera de transacción y no se debe envolver manualmente en
`BEGIN/COMMIT` ni mezclar con DDL transaccional. Durante V51/V52 se monitorizan
`pg_stat_progress_create_index` y `pg_index.indisvalid`. Si una interrupción deja
el índice inválido, se elimina mediante `DROP INDEX CONCURRENTLY IF EXISTS
idx_saas_security_outbox_terminal_retention` antes de reparar/reintentar Flyway.

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

Los backups creados antes de la purga pueden conservar payloads cifrados durante
toda su propia retención. Por ello la política de copias no puede superar la
retención legal acordada sin una excepción documentada. El job de aplicación
tombstonea `encrypted_payload` solo en eventos terminales `DELIVERED` o
`ACKNOWLEDGED` con antigüedad mayor que `TPV_SAAS_SECURITY_PAYLOAD_RETENTION`;
nunca purga `PENDING`, `PROCESSING` o `FAILED`, y cada lote queda auditado.

Antes de producción se registran y aprueban dos objetivos medidos:

- **RPO**: pérdida máxima aceptable de datos. El intervalo de backup debe ser
  menor o igual al RPO; para menor pérdida se requiere archivado WAL/PITR.
- **RTO**: tiempo máximo de recuperación. Se mide desde la parada hasta que los
  dos healthchecks y el smoke funcional vuelven a estar verdes.

Cada trimestre se ensaya una restauración en infraestructura aislada, se anota
duración, checksum, versión de imagen y resultado Flyway, y se revisan las
copias caducadas conforme a la política de retención.
