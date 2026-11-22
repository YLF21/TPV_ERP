# TPV ERP SaaS Backend

Backend central para licencias, vinculacion de instalaciones y eventos sincronizados desde tiendas.

El Resumen y los siete informes analíticos, sus fuentes, reglas de cómputo,
comparaciones, capturas de inventario y exportaciones CSV/XLSX se describen en
[Panel interno: estadísticas e informes](../frontend-saas/README.md).
Supervisión permanece apartada de la navegación en esta versión; sus APIs y
la recepción de eventos se conservan. El personal SaaS activo puede consultar el Resumen y los informes.

Los directorios de Empresas, Tiendas y Licencias permiten descargar sus tablas en
XLSX desde el panel interno. Las rutas `GET /api/v1/admin/directories/export/stores`
y `GET /api/v1/admin/directories/export/licenses` aceptan los filtros y el orden de
sus respectivos listados. `POST /api/v1/admin/directories/export/companies` recibe
`companyIds` en el orden filtrado de la tabla, `filters`, `sortBy` y `sortDirection`.
Admite `storeCount` y `licenseCount` como claves de orden y escribe ambos totales
como números en el libro.
El servidor vuelve a consultar los datos de esas empresas en una sola instantánea
de lectura. Una selección vacía produce un libro con cabeceras. Si una empresa
seleccionada ya no existe, la API responde con 409 para que se actualice el listado.

Las tres rutas aceptan `language=es|en|zh` y exportan hasta 50.000 filas filtradas.
Al superar el límite devuelven 422. El libro incluye todas las columnas de negocio
del directorio, una hoja de contexto con filtros y orden y fechas en UTC. Los códigos
y los importes decimales se conservan como texto exacto. Los saldos pendientes de
una empresa se presentan por moneda. Las descargas comparten con los informes
analíticos dos plazas concurrentes.

La version registrada del backend SaaS se define en `pom.xml`. La serie `0.x.x`
corresponde a pruebas; la serie `1.x.x` comienza con el despliegue. Maven incluye
esta version en `META-INF/build-info.properties` del artefacto. El endpoint
`/actuator/info` ya figura en la exposicion interna de Actuator.

La organización del panel, los accesos multitienda, los códigos internos y la supervisión se describen en [SaaS multitienda y supervisión](../docs/saas-multitienda-supervision-2026-09-20.md).

La captura, el contrato v1/v2, la consulta de fallos de aplicación y el orden de actualización V70/V252 se describen en [Recepción y consulta de fallos: fase 1](../docs/saas-failure-reporting-phase1-2026-09-22.md).

La reparación remota de entregas agotadas, el traspaso a soporte y la activación V71/V253 se describen en [Reparación remota y atención manual: fase 2](../docs/saas-remote-repair-phase2-2026-09-22.md).

La asistencia remota humana, el paso a intervención presencial y la migración V72 se describen en [Asistencia y atención presencial: fase 3](../docs/saas-support-interventions-phase3-2026-09-22.md).

## Seguimiento de incidencias (V73/V74)

Todos los tickets, incluidos los generales, usan el mismo seguimiento en
`/api/v1/admin/tickets/{ticketId}/interventions`. Las acciones comparan versión
y estado del ticket, registran un historial y admiten reintentos con el mismo
`requestId` y contenido. El cierre requiere solución, comprobaciones y persona
que confirma; cambiar únicamente el estado mediante el endpoint genérico no
permite saltarse esta verificación.

- `START_SAAS` permite diagnosticar y actuar desde el panel antes de solicitar
  comprobación, o pasar a asistencia remota/presencial si hace falta.
- `WAIT_CUSTOMER` y `WAIT_MATERIAL` requieren un motivo y `nextReviewAt` futuro.
  `RESUME` recupera la fase anterior; la espera no permite cerrar directamente.
- `SAVE_DETAILS` guarda `assigneeUserId` y `visitAt`. Admite cuentas activas del personal SaaS.
  Un identificador nulo desasigna el caso.
  Una visita necesita técnico, y una visita en curso conserva ambos datos.
- Los nombres históricos se conservan como texto hasta elegir un usuario real.
  El estado del fallo técnico permanece independiente del cierre del ticket.

El portal cliente recibe un contrato público de tickets: fase de intervención,
próxima revisión y visita, sin asignados, identificadores técnicos ni evidencias
internas. En tickets derivados se omite la descripción técnica autogenerada.
Los comentarios compartidos aceptan `requestId` y devuelven esa clave tanto en
POST como en GET; repetir la misma petición no añade otro comentario. Cambiar
su autor o texto con la misma clave devuelve conflicto. El contador del cliente
excluye los tickets resueltos y cerrados.

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

- `POST /api/v1/auth/admin/login` autentica al personal SaaS y emite sesiones `admin`.
- `POST /api/v1/auth/tenant/login` autentica a los clientes y emite sesiones `tenant`.
- `POST /api/v1/auth/login` es el alias histórico de acceso cliente.
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `POST /api/v1/auth/logout-all`
- `POST /api/v1/admin/companies`
- `GET /api/v1/admin/companies`
- `GET/PUT /api/v1/admin/companies/{companyId}/profile`
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

El alta de empresa recibe nombre, NIF, tipo de obligado y
`companyAddress` (con `linea1`, `ciudad`, `codigoPostal`, `provincia` y `pais`),
más `owners` con al menos un propietario `{name,taxId,phone?,email?}`.
`name` y `taxId` (DNI/NIE válido) son obligatorios para cada propietario.
Puede incluir `contactName`, `contactEmail`, `contactPhone`, `supportStatus` y `notes`.
El perfil mayorista/minorista se configura en la tienda; el campo societario anterior
solo se conserva para compatibilidad histórica y puede ser nulo.
No crea tiendas, licencias ni usuarios automáticamente. `GET /api/v1/admin/companies`
incluye contactos, propietarios y los totales `storeCount` y `licenseCount`.
Los totales incluyen tiendas inactivas y licencias de todos los estados.
Las sociedades sin tiendas o licencias muestran cero en el total correspondiente.
`GET /profile` obtiene la ficha y `PUT /profile` guarda sociedad, contacto y propietarios
en una sola transacción, con `EDIT_COMPANY_DATA`. Conserva NIF, tipo de obligado y
datos comerciales históricos. Las sociedades históricas sin propietarios pueden consultarse,
pero el guardado completo exige completarlos. V67 añade los campos sin inferir titulares.

Después se crea la tienda mediante `POST /api/v1/admin/companies/{id}/stores`,
con su domicilio, zona horaria, `taxRegime`, `commercialProfile` (`MAYORISTA` o `MINORISTA`), `servicePrice` en EUR,
`billingPeriod` (`MONTHLY` o `ANNUAL`), `maxWindows`, `maxPda` y `validUntil`.
`POST /api/v1/admin/license-workspace` recibe solo `{storeId}` y genera el código
usando la configuración de esa tienda. Los nuevos códigos de esta ruta y de la
regeneración administrativa caducan a los 30 minutos (`PairingCodePolicy.VALIDITY`).
La respuesta de creación incluye `pairingExpiresAt` y `serverNow`; el contador no
depende del reloj del equipo. Se conservan las caducidades de códigos ya emitidos.
Los domicilios reales se conservan para
aprovisionar una instalación local; no se generan direcciones de relleno.
V68 conserva en cada tienda el perfil comercial de su sociedad histórica. Tanto vincular
como validar una instalación usa el perfil de esa tienda. En actualizaciones antiguas que
omiten `commercialProfile` se conserva el valor actual de la tienda.

`GET /api/v1/admin/stores` admite `companyId`, `q`, `active`, `page`, `size`,
`commercialConfiguration`, `sortBy` y `sortDirection` (`ASC`/`DESC`).
Con `commercialConfiguration=pending` aparecen las tiendas a las que les falta
perfil comercial, precio, periodicidad o caducidad. `complete` exige los cuatro
campos. Un precio de cero cuenta como configurado. Omitir el parámetro muestra
ambos estados. Las columnas permitidas son `internalCode`,
`companyName`, `code`, `name`, `active`, `taxRegime`, `commercialProfile`, `servicePrice`,
`billingPeriod`, `validUntil`, `maxWindows`, `maxPda`, `installations`,
`activeInstallations`, `lastSyncAt` y `createdAt`. La ordenación se aplica antes de
paginar, coloca los nulos al final y desempata por UUID; el orden predeterminado
conserva empresa, código local y UUID. `GET /api/v1/admin/stores/{storeId}` obtiene
la ficha actual para su consulta o gestión por el personal SaaS.

`GET /api/v1/admin/license-workspace` admite `companyId` para consultar todas las
licencias de una empresa y `storeId` para limitar el listado a los vínculos directos
e históricos de una tienda. Ambos filtros se combinan cuando se envían juntos.
Omitir `status` incluye todos los estados.

`GET /api/v1/admin/license-workspace/{licenseId}` obtiene la ficha vigente por UUID,
con el mismo `LicenseRow` del listado. Incluye estado
efectivo, caducidad, cupos, conexiones, tiendas y saldos separados por moneda.
No aplica los filtros del listado: una licencia puede seguir abierta tras bloquearla
o renovarla. El directorio conserva filtros y paginación en servidor; el frontend
carga bloques de 25 al desplazarse y usa este detalle independiente para gestionar.

`GET /api/v1/admin/license-workspace/activation-codes` recupera los códigos
vigentes con `page`, `size` y filtro `companyId` opcional. Requiere una sesión del personal SaaS,
al igual que la pantalla Crear licencia. Devuelve `{items,page,size,total,totalPages,serverNow}` con empresa,
tienda, referencia, código y `pairingExpiresAt`. Excluye códigos consumidos/caducados,
licencias bloqueadas/vencidas y tiendas inactivas. La respuesta utiliza `no-store`;
el frontend mantiene los secretos solo en memoria y vuelve a consultarlos al abrir.

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

Las cuentas de personal SaaS activas comparten las capacidades del panel interno, incluidas las de
empresas, tiendas, licencias y gestión del personal. Esta regla se aplica también a las cuentas con
asignaciones históricas. `GET /api/v1/admin/me` devuelve el mismo conjunto de capacidades para cada
cuenta de personal. `POST /api/v1/admin/users` recibe `username` y `password`. Los clientes antiguos
que envíen `roleName` pueden seguir creando cuentas y ese campo se ignora. El alta y el listado de
personal devuelven `username`, `active` y `createdAt`. Se protege la propia cuenta y la última
cuenta activa frente a la desactivación. Las API de cliente aplican sus concesiones por empresa y tienda.

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

Las cuentas de personal SaaS activas pueden inspeccionar fallos sin exponer
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

## Captación comercial esPOS: recepción y bandeja (fase 1)

La migración V78 conserva una clave de reintento junto a la solicitud. Debe
aplicarse antes de publicar el frontend que comprueba disponibilidad.

- `GET /api/v1/public/demo-requests/availability` devuelve `{ "available": true }`
  únicamente si la tabla y sus columnas son accesibles. Es una comprobación
  básica de lectura, no una garantía de escritura, envío de email ni atención.
  Ante un fallo de base devuelve 503 sin detalles internos. El recibo del POST
  es la confirmación de persistencia, salvo el descarte neutro del honeypot.
- `POST /api/v1/public/demo-requests` mantiene el contrato anterior y admite
  `Idempotency-Key: <UUID>` opcional. La misma clave con el mismo contenido
  normalizado devuelve el mismo `{ id, receivedAt }`, incluso tras reiniciar.
  La misma clave con contenido diferente devuelve 409 `IDEMPOTENCY_CONFLICT`.
  El cliente debe conservar clave y contenido al reintentar una respuesta
  perdida, y generar otra clave cuando el usuario cambie la solicitud.
- Los errores controlados usan ProblemDetail con `code` y, cuando corresponde,
  `fields: { "email": "INVALID" }`. Los códigos de campo son `REQUIRED`,
  `TOO_LONG`, `INVALID` y `MUST_ACCEPT`, sin reflejar datos personales.
  Un 429 usa `RATE_LIMITED` y `Retry-After`; un fallo de almacenamiento usa
  503 `SERVICE_UNAVAILABLE`. Todas estas respuestas llevan `Cache-Control: no-store`.
- `GET /api/v1/admin/demo-requests` conserva la lista de las últimas 100 para
  compatibilidad. La bandeja completa usa `GET /api/v1/admin/demo-requests/page`
  con `page` desde 0, `size` entre 1 y 100 (20 por defecto), `status` opcional
  `NEW|CONTACTED|CLOSED` y `query` opcional de hasta 160 caracteres.
  Responde `{ items, total, page, size }`, ordenado por fecha e ID descendentes.
  Busca texto literal en nombre, empresa y email. Si `query` es una UUID
  canónica, busca exclusivamente esa referencia exacta (manteniendo los filtros),
  sin mezclar coincidencias del UUID en otros campos de contacto.
  La lectura requiere acceso de personal SaaS.
- `PUT /api/v1/admin/demo-requests/{id}/status` recibe
  `{ "status": "CONTACTED", "expectedStatus": "NEW" }`. El personal SaaS puede actualizar el estado y recibe el registro actualizado. Si otro
  operador cambió el estado esperado, devuelve 409 `STATUS_CONFLICT`; actualizar
  la bandeja antes de volver a decidir. Repetir un cambio ya aplicado al mismo
  destino devuelve el registro sin duplicar el evento de auditoría. La auditoría
  conserva referencia, operador y estado, no el mensaje ni los datos de contacto.

La bandeja es un circuito **manual**: un operador autorizado debe revisarla y
marcar las solicitudes atendidas/cerradas. Esta fase no configura SMTP, webhooks
ni notificaciones externas, y no promete que una solicitud guardada ya esté atendida.

### Protección y despliegue

El limitador en memoria admite por defecto 120 nuevas solicitudes por dirección
remota en 10 minutos y 5 por email en una hora. Las claves son hashes salados
por proceso: no conserva IP/email en claro ni los escribe en logs. La memoria
se limita a 10 000 contadores y descarta los caducados. Los reintentos de una
solicitud ya guardada con la misma clave no consumen la cuota de envíos.

`tpv.saas.marketing.ip-request-limit` (1–10 000; por defecto 120) permite ajustar
la cuota agregada cuando un proxy comparte dirección. No se confía ciegamente
en `X-Forwarded-For`. En despliegues con proxy o varias instancias, configurar
además un límite en el borde con una política explícita de proxies confiables;
los contadores de esta protección local se reinician con el proceso y no son
un limitador distribuido. Configurar CORS solo para los orígenes públicos reales.

### Verificación aislada

`mvn "-Dtest=DemoRequest*Test" test` ejecuta contrato, validación, permisos,
cuotas, reintentos y pruebas PostgreSQL con todas las migraciones. El perfil
`test` usa por defecto PostgreSQL desechable mediante Testcontainers (requiere
Docker). Como alternativa, configurar `TPV_TEST_DB_URL`, `TPV_TEST_DB_DRIVER`,
`TPV_TEST_DB_USERNAME` y `TPV_TEST_DB_PASSWORD` para una base **exclusiva de pruebas**;
nunca apuntarlos a una base de operación. Cada ejecución crea un esquema aleatorio
`marketing_test_*` y solo introduce contactos sintéticos, sin notificaciones.

## Solicitudes de cuenta de cliente

El formulario envía `POST /api/v1/public/client-access-requests` con un
`submissionId` UUID, usuario, contraseña, nombre completo, documento, correo,
teléfono, dirección, código postal, ciudad, provincia, país, una lista de hasta
20 referencias de tiendas escritas por el solicitante y notas opcionales.
Puede enviar la lista vacía. La contraseña admite un mínimo de 12 caracteres y
un máximo de 72 bytes UTF-8. La recepción devuelve HTTP 202 sin cuerpo. Los
reintentos con el mismo UUID conservan el primer envío. Cada instancia limita
los nuevos envíos a 30 por dirección remota en diez minutos y cinco por correo
en una hora.

El personal del SaaS consulta `GET /api/v1/admin/client-access-requests` y
`GET /api/v1/admin/client-access-requests/{id}`. La bandeja admite `status`,
`q`, `page` (desde cero), `size` (1 a 100), `sort` y `direction`.
La solicitud pendiente conserva el hash bcrypt separado de las identidades
autenticables. El operador contrasta los datos y elige las tiendas reales.

- `POST /api/v1/admin/client-access-requests/{id}/approve` recibe
  `{ "version": 0, "grants": [], "reason": "..." }`. El motivo es opcional.
  Cada entrada en `grants` contiene una empresa (`companyId`) y la lista de
  tiendas elegidas (`storeIds`). Se valida la pertenencia de cada tienda y
  la capacidad del plan. Una lista vacía crea una cuenta que puede iniciar
  sesión y consultar su lista de accesos vacía.
- `POST /api/v1/admin/client-access-requests/{id}/reject` recibe
  `{ "version": 0, "reason": "..." }` con motivo obligatorio.
- Cada decisión registra el operador, fecha y resultado y elimina el hash de la
  solicitud. La aprobación crea en la misma transacción la cuenta de cliente y
  las asignaciones de tiendas con acceso de consulta y sin privilegios
  empresariales. Un conflicto de versión o un usuario ocupado devuelve HTTP 409.
- `GET /api/v1/admin/tenant-users` pagina las cuentas de todas las empresas,
  incluidas las históricas y las que tienen cero asignaciones. Admite los
  mismos parámetros de consulta salvo `status`. Para gestionar los accesos
  después de aprobar, se utilizan las rutas de `/tenant-users/{username}/access`.

La revisión es manual. Al aprobar o rechazar, la misma transacción registra un
aviso de correo destinado al solicitante. El formulario acepta el campo opcional
`locale` con valores `es`, `en` o `zh`. En su ausencia se utiliza español.
Las aprobaciones incluyen el usuario y el enlace público del portal. Los
rechazos incluyen el motivo indicado por el operador. Las notas internas de
aprobación y las contraseñas se excluyen del mensaje. El personal SaaS y las
integraciones autorizadas pueden seguir utilizando
`POST /api/v1/admin/companies/{companyId}/tenant-users` para el alta manual
de cuentas de cliente con asignaciones explícitas.

La validación con PostgreSQL 17 desechable comprende siete pruebas en
`ClientAccessRequestsPostgreSqlTest` y ocho en `TenantStoreAccessPostgreSqlTest`.
Cubren la migración V86→V87, aprobación y rechazo simultáneos, colisiones de
usuario, credenciales, búsqueda y aislamiento de tiendas, incluidas la asignación
y la revocación durante una sesión abierta.

### Correo SMTP del servidor central

El personal del SaaS administra varias cuentas desde
`GET` y `POST /api/v1/admin/smtp-settings/accounts`. Cada cuenta dispone de
`GET`, `PUT` y `DELETE /api/v1/admin/smtp-settings/accounts/{id}`; el borrado
exige `?version=N`. La configuración parte desactivada. Requiere un servidor, puerto, TLS implícito
(`SSL`) o `STARTTLS` obligatorio y dirección de remitente. La URL HTTPS pública del
portal es obligatoria solo al activar los avisos automáticos. Admite nombre de remitente y dirección de respuesta. Para autenticación
se indica usuario y contraseña. La clave de instalación
`TPV_SAAS_SECRET_ENCRYPTION_KEY` protege la contraseña con AES-GCM. La lectura
devuelve `hasPassword`, `configured`, `testReady`, `cryptoReady` y `version`. `testReady`
indica que el transporte y el remitente permiten una prueba; `configured` exige además
la URL del portal para los avisos automáticos. El guardado exige
la versión leída. Omitir la contraseña o enviar una cadena vacía conserva la
existente. Para sustituir el servidor, el puerto, el modo TLS o el usuario
con contraseña guardada, debe introducirse la nueva contraseña o borrarse con
`clearPassword: true`.

`POST /api/v1/admin/smtp-settings/test` acepta `{"recipient":"correo@dominio.es"}`
y envía una prueba con el remitente y la conexión guardados (`testReady`). No exige
URL pública ni activar avisos: permite comprobar Gmail desde localhost dejando
`portalUrl` vacío y `enabled: false`. La prueba no modifica esos valores, no incluye
enlace al portal y no consume ni entrega avisos pendientes. El límite compartido
es global: una prueba cada 30 segundos. Un rechazo del servidor SMTP devuelve HTTP
502 y un código de error sin credenciales ni la respuesta del proveedor.

La prueba de una cuenta concreta usa `POST /smtp-settings/accounts/{id}/test`.
La migración V91 conserva en la misma fila la configuración original, su versión
y los secretos cifrados, asignándole un UUID estable. Los tokens OAuth y las
comunicaciones existentes quedan vinculados a ese UUID. Añadir una cuenta no
activa avisos. Eliminarla retira sus secretos, conserva el histórico y falla
si tiene destinatarios manuales pendientes o en curso, o si es la cuenta de
avisos y tiene avisos pendientes o en curso. Si era la cuenta de
avisos, los desactiva; no elige otra cuenta automáticamente.

`GET` y `PUT /api/v1/admin/smtp-settings` permanecen como compatibilidad para
la cuenta predeterminada de avisos. La interfaz separa los avisos de la conexión
y usa `PUT /api/v1/admin/smtp-settings/notices` con `accountId`, `enabled`,
`portalUrl`, `version`, `defaultAccountId` y `defaultVersion`. La selección
comprueba las versiones de ambas cuentas. Cambiar de remitente con avisos
pendientes requiere además `confirmPending: true`. Los PUT de una cuenta
concreta conservan la activación y la URL de avisos.

La migración V88 empieza con la cola vacía y conserva las decisiones anteriores.
Un proceso independiente recoge los nuevos avisos cada 15 segundos. Con SMTP
desactivado, incompleto o con una contraseña indescifrable, los avisos quedan
pendientes sin consumir intentos. Los errores de entrega se reintentan después
de 1, 2, 4 y 8 minutos. Tras cinco intentos el estado pasa a `FAILED`. El detalle
de la solicitud devuelve `delivery` con estado, número de intentos del ciclo,
próximo intento, fecha de aceptación por el relay y último código de error.
`POST /api/v1/admin/client-access-requests/{id}/retry-email` inicia otro ciclo
para un aviso fallido y registra al operador en la auditoría.

Cada solicitud tiene una sola entrada de cola y un `Message-ID` estable.
Si el proveedor acepta el mensaje y se pierde la confirmación antes de registrar
la entrega, SMTP puede volver a recibirlo tras recuperarse el proceso. El estado
`DELIVERED` indica la aceptación por el relay. La acreditación de recepción en
el buzón depende del proveedor.

### Correo con autorización de Google y Microsoft

Las cuentas de salida están disponibles para el personal del servidor central.
Se puede conectar una cuenta de Google mediante Gmail API o una cuenta de Microsoft
mediante SMTP con XOAUTH2 y STARTTLS obligatorio. El envío de prueba y los
avisos de aprobación y rechazo utilizan la cuenta seleccionada para cada función.

Para habilitar un proveedor, configura una aplicación web en ese proveedor y
registra la URI externa exacta del SaaS en `TPV_SAAS_MAIL_OAUTH_REDIRECT_URI`.
La ruta debe ser `/api/v1/mail-oauth/callback` bajo HTTPS. Para desarrollo
local se admite HTTP con la dirección de loopback literal. Configura también
`TPV_SAAS_SECRET_ENCRYPTION_KEY` y las variables correspondientes al proveedor.

| Proveedor | Variables del registro | Permisos |
| --- | --- | --- |
| Google | `TPV_SAAS_MAIL_OAUTH_GOOGLE_CLIENT_ID`, `TPV_SAAS_MAIL_OAUTH_GOOGLE_CLIENT_SECRET` | Habilitar Gmail API, solicitar `https://www.googleapis.com/auth/gmail.send` y `https://www.googleapis.com/auth/userinfo.email` con acceso sin conexión. |
| Microsoft | `TPV_SAAS_MAIL_OAUTH_MICROSOFT_CLIENT_ID`, `TPV_SAAS_MAIL_OAUTH_MICROSOFT_CLIENT_SECRET` | Autorizar `offline_access` y `https://outlook.office.com/SMTP.Send`. El buzón debe admitir SMTP AUTH mediante OAuth. |

`GET /api/v1/admin/mail-oauth/status` indica qué registros están disponibles y
qué cuenta está vinculada. `POST /api/v1/admin/mail-oauth/connect` recibe
`provider` (`GOOGLE` o `MICROSOFT`), `email`, `version`, `enabled`,
`fromName`, `replyTo` y `portalUrl`. Abre la `authorizationUrl` devuelta.
Los endpoints de estado y desconexión aceptan `accountId` en la consulta y el
de conexión en el cuerpo; al omitirlo se usa la cuenta predeterminada por
compatibilidad. Cada flujo y sus tokens pertenecen exclusivamente a esa cuenta.
La cuenta previa continúa operativa hasta que el proveedor confirma la nueva
identidad. El servidor comprueba la versión y el operador antes de aplicar
los nuevos datos y sustituir la contraseña o los tokens anteriores. La
autorización caduca en diez minutos y es de un solo uso. Para conectar con el
envío activo se necesita la URL HTTPS del portal. Para preparar una cuenta
desactivada se puede dejar esa URL vacía.

La configuración SMTP informa de `oauthProvider`. Una cuenta conectada admite
cambios de activación, nombre visible, respuesta y portal mediante el PUT
habitual. `DELETE /api/v1/admin/mail-oauth/connection?version=N` cancela la
autorización pendiente, elimina los tokens y desactiva el remitente OAuth.
Las acciones de conexión y desconexión se registran con la identidad del
operador. Los tokens y el verificador PKCE se cifran con AES-GCM.

Si se pierde la respuesta final de SMTP o Gmail después de que el proveedor
haya podido aceptar el envío, el aviso pasa a `FAILED` con
`SMTP_DELIVERY_UNCERTAIN`. Comprueba la entrega antes de solicitar un reintento
manual. Las respuestas explícitas de rechazo mantienen el ciclo de reintentos.

### Comunicaciones manuales a clientes

El personal del SaaS puede guardar borradores y consultar el historial mediante
`/api/v1/admin/communications`. Cada edición requiere la versión que abrió el
operador. El servidor conserva el HTML saneado y genera una alternativa en
texto que incluye los destinos de los enlaces. La vista previa, la prueba y el
envío utilizan ese contenido. Cada borrador guarda `senderAccountId`; la prueba
y el envío usan exclusivamente esa cuenta, sin sustitución automática. La prueba
de un borrador comparte la espera global de 30 segundos con las pruebas de conexión.

La audiencia incluye el contacto de `saas_company_operations`, los propietarios
de `saas_company.owners` y los usuarios del portal con correo de solicitud aprobada,
incluso si todavía carecen de tiendas. El directorio agrupa por correo recortado y
sin distinguir mayúsculas, antes de buscar o paginar; conserva empresas y tipos
de origen agregados. Los registros sin dirección válida no se pueden seleccionar;
un buzón compartido está disponible si conserva al menos un origen habilitado.
La revisión admite contactos seleccionados o el directorio completo y cuenta
las omisiones y las direcciones duplicadas. El operador puede consultar la
lista paginada de direcciones exactas antes de confirmar. El envío exige
el identificador de revisión, la versión del borrador y el número revisado
de destinatarios. V92 conserva los orígenes habilitados de cada buzón al revisarlo.
Si ninguno conserva esa dirección, se exige revisar de nuevo; un origen añadido
después no sustituye a los revisados. Antes de entregar cada mensaje se bloquea y
comprueba su origen para proteger cambios concurrentes. Los sobres anteriores
mantienen la comprobación de su usuario original. Un cambio de configuración del
remitente anterior a la confirmación también requiere otra revisión.

El trabajador procesa un sobre individual por dirección, con identificador
estable y un ritmo compartido de una reserva por segundo. Cada sobre utiliza
el transporte de salida guardado y requiere un remitente disponible. El nombre
y dirección del remitente y la dirección de respuesta deben coincidir con los
revisados. Ante `SMTP_SENDER_CHANGED`, restablece la identidad revisada antes
de reintentar. El interruptor de avisos de solicitudes y la URL del portal
solo afectan a dichos avisos.

`SENT` registra la aceptación por el proveedor. Una desconexión ambigua o una
interrupción del proceso con un envío en curso deja el sobre en `UNCERTAIN`.
Después de dos minutos el trabajador recupera las reservas abandonadas en ese
estado. El operador puede consultar los resultados individuales, comprobar la
entrega y confirmar el riesgo de duplicado antes de reintentar. Los rechazos
explícitos quedan en `FAILED` y también se reintentan de forma manual. Cancelar
detiene los envíos pendientes y conserva el resultado de los ya iniciados.
