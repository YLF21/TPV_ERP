# Fase 2: reparación remota y atención manual

Continuación: [Asistencia remota humana y atención presencial, fase 3](saas-support-interventions-phase3-2026-09-22.md) añade el seguimiento del ticket y V72 en SaaS; el backend local se mantiene en V253.

## Alcance

La primera acción remota permitida es `RETRY_SYNC_OUTBOX`: volver a intentar una entrega local agotada. Se inicia desde el detalle de Fallos de tiendas. No ejecuta scripts, SQL recibido, comandos del sistema, reinicios ni cambios fiscales. Los fallos de impresión y las demás causas sin acción compatible pasan a atención manual mediante un ticket vinculado al fallo.

La operación recupera la **entrega del evento**. `SUCCEEDED / SYNC_DELIVERED` prueba que el outbox local está ENVIADO, no que cualquier problema comercial o físico haya desaparecido. Una proyección central fallida sigue teniendo su propio diagnóstico. La evidencia de fallo y el estado del ticket de soporte son independientes.

## Recorrido

1. Un administrador con `MANAGE_OPERATIONAL_INCIDENTS` solicita el reintento con un motivo y un `requestId` idempotente. El servidor deriva instalación, empresa, tienda, evento y versión de la evidencia existente; no acepta estos campos de la pantalla.
2. Sólo admite un fallo `LOCAL_SYNC`, `OPEN`, gravedad `DANGER` (reintentos agotados) y una instalación activa. Se conserva quién lo solicitó, cuándo, por qué y su resultado. Sólo puede existir un comando activo por fallo. Después de una entrega confirmada no se permite repetir la misma revisión antigua; se espera un nuevo diagnóstico de la tienda.
3. La instalación consulta SaaS por HTTP saliente autenticado con su token existente. No es necesario abrir un puerto de acceso entrante en la tienda.
4. El ejecutor compara empresa y tienda SaaS con las identidades locales de la licencia. En una transacción registra el comando en un libro durable y reabre exclusivamente el evento DEAD_LETTER de la versión esperada. Comprueba de nuevo el vencimiento después de adquirir el bloqueo del evento. No cambia sus datos comerciales ni su identidad.
5. `RUNNING / RETRY_QUEUED` indica que se ha solicitado otra entrega. El worker normal conserva el transporte y la deduplicación ya existentes. Sólo cuando el evento queda ENVIADO se comunica éxito; volver a DEAD_LETTER comunica fallo.
6. Una repetición del comando o la pérdida de su respuesta no vuelve a ejecutar la modificación. El libro local conserva los resultados para su reenvío.
7. Un comando pendiente o en ejecución caduca a los 15 minutos. SaaS no transforma en éxito un resultado que llega después de esa caducidad. Una operación que ya empezó puede finalizar después: un timeout no demuestra que no haya habido efectos.
8. Cuando no hay reparación compatible o se necesita intervención, `MANAGE_SUPPORT_TICKETS` permite crear o recuperar el ticket único asociado. El técnico puede consultar el ticket, añadir comentarios, marcarlo en curso, resolverlo y reabrirlo desde el propio detalle del fallo. El ticket incluye la referencia de diagnóstico y el resultado del último intento remoto.

## Contrato entre instalación y SaaS

- `POST /api/v1/sync/repairs/claim`, token `X-TPV-Installation-Token`, cuerpo `{installationId}`. Devuelve hasta diez comandos con `commandId`, `companyId`, `storeId`, `action`, `eventId`, `expectedVersion`, `expiresAt`.
- `POST /api/v1/sync/repairs/{commandId}/result`, mismo token, cuerpo `{installationId,status,resultCode}`. Los códigos son limitados; no se envían mensajes de excepción ni comandos libres.
- La instalación autenticada sólo puede reclamar y confirmar sus propios comandos. Los resultados terminales no retroceden. Un resultado tardío recibe el estado EXPIRED ya conservado.

## Activación y despliegue

Aplicar primero el backend SaaS con V70 y V71, luego el backend local con V252 y V253, y actualizar el frontend SaaS. La primera fase todavía no se ha desplegado desde esta tarea. No ignorar checksums de versiones intermedias de migraciones que alguien haya aplicado por separado.

En cada tienda que deba aceptar reparaciones activar:

```text
TPV_SYNC_WORKER_ENABLED=true
TPV_REMOTE_REPAIR_ENABLED=true
```

Se necesita la URL SaaS y la vinculación/token existentes. El interruptor remoto está desactivado por defecto; el perfil `saas-dev` lo activa por defecto para la integración de desarrollo. Desactivar el interruptor evita que este proceso siga reclamando/ejecutando tareas; no revierte una entrega ya reabierta.

V253 actualiza la identidad y secuencia de release para mantener el guard de versión local. No lanzar un binario antiguo sobre un esquema actualizado.

## Límites operativos

- La disponibilidad del botón no equivale a conexión en vivo. Una tienda antigua, offline o con el interruptor desactivado no ejecutará la tarea y ésta caducará.
- No hay integración con TeamViewer. La intervención presencial o por una herramienta externa la realiza el técnico y la documenta en el ticket.
- No se modifica automáticamente la evidencia técnica por cerrar un ticket manual.
- Las pruebas utilizan datos sintéticos y PostgreSQL independiente; no se han modificado bases comerciales ni desplegado estos cambios.

## Repetir la verificación

Usar JDK 25 y una base PostgreSQL nueva y exclusiva de pruebas. Backend local utiliza `TPV_ERP_TEST_DB_URL`, `TPV_ERP_TEST_DB_USER`, `TPV_ERP_TEST_DB_PASSWORD`; SaaS utiliza `TPV_TEST_DB_URL`, `TPV_TEST_DB_USERNAME`, `TPV_TEST_DB_PASSWORD`, `TPV_TEST_DB_DRIVER=org.postgresql.Driver`.

Para el arranque real de Spring local, configurar también `TPV_TEST_DB_URL`, `TPV_TEST_DB_USERNAME` y `TPV_TEST_DB_PASSWORD` apuntando a esa base exclusiva. El helper de ese test usa su contraseña por defecto si recibe un valor vacío; en una base aislada con autenticación trust se puede proporcionar un valor sintético no vacío. No usar una base comercial.

Desde `backend`, ejecutar los tests locales para generar el cuerpo HTTP real de resultado:

```powershell
.\mvnw.cmd '-Dtest=StoreRemoteRepairPostgreSqlTest,HttpRemoteRepairClientTest,StoreRemoteRepairSchedulerTest,FiscalReleaseBuildProfileContractTest,FiscalRuntimeGuardInitializerTest,SyncOutboxWorkerTest,SyncOutboxServiceTest,StoreFailurePublisherPostgreSqlTest,SyncOutboxIncidentServiceTest,PostgreSqlMigrationTest,TpvErpBackendApplicationTests' test
```

Desde `backend-saas`, consumir ese resultado (sólo se sustituye la instalación sintética por la autenticada por el test):

```powershell
$env:TPV_PHASE2_RESULT_FIXTURE = (Resolve-Path '..\backend\target\phase2-local-repair-result.json').Path
$env:TPV_PHASE1_CAPTURED_PAYLOAD = (Resolve-Path '..\backend\target\phase1-application-payload.json').Path
.\mvnw.cmd '-Dtest=StoreRepairPostgreSqlTest,StoreFailurePostgreSqlTest,AdminApiTest' test
```

Desde `backend`, consumir ahora el claim real generado por el controlador SaaS:

```powershell
$env:TPV_PHASE2_CENTRAL_CLAIM = (Resolve-Path '..\backend-saas\target\phase2-central-repair-claim.json').Path
.\mvnw.cmd '-Dtest=HttpRemoteRepairClientTest' test
```

Este último paso ejecuta la comprobación cruzada que queda omitida en la primera ejecución si todavía no existe un claim capturado. La aceptación final exige ejecutarla, no contarla como validada sin fixture.

Desde `frontend-saas`:

```powershell
npm.cmd test
npm.cmd run build
$env:PLAYWRIGHT_CHANNEL = 'msedge'
node e2e/failure-diagnostics-smoke.mjs
node e2e/failure-repairs-smoke.mjs
```

Los tests PostgreSQL comprueban persistencia, aislamiento, idempotencia, concurrencia y rollback. El transporte local utiliza un servidor HTTP de prueba; la recepción SaaS se verifica con MockMvc. El navegador utiliza API simulada. Este conjunto no representa una prueba en una tienda comercial ni dos servidores productivos desplegados.

## Validación inicial del 22/09/2026 (antes de la revisión profunda)

- Backend local: **63 pruebas distintas de 9 suites**, cero errores, fallos u omisiones. Incluye 17 nuevas pruebas de ejecución/HTTP/scheduler, aislamiento, concurrencia, rollback, recuperación tras reinicio y comprobación del vencimiento después de esperar por un bloqueo; además de regresión de sincronización, publicación y guard de release V253.
- Backend SaaS: **64 pruebas distintas** correctas (44 AdminApi, 11 StoreFailure, 9 StoreRepair), sin omisiones. Tras añadir la protección de una revisión ya entregada se repitieron las 9 pruebas de reparación, todas correctas; no se cuentan como 9 adicionales.
- Los dos sentidos del contrato fueron comprobados: el cliente local consume el claim real de SaaS y el controlador SaaS recibe el cuerpo HTTP real generado por el cliente local. Sólo se adapta la identidad sintética de instalación en el segundo paso.
- Frontend: **90 pruebas**, compilación TypeScript/Vite y dos recorridos Edge correctos (`failure-diagnostics-smoke.mjs` y `failure-repairs-smoke.mjs`). Cubren permiso insuficiente, pérdida de respuesta antes/después de aceptar el comando, misma clave de reintento, espera sin falso éxito, fin del polling, expiración, cambio de selección y ticket manual con comentarios/resolución/reapertura.
- Se corrigieron fixtures que inicialmente violaban restricciones ya existentes de revocación/reasignación de instalación. Los informes finales no contienen fallos ni pruebas omitidas.
- Captura con datos sintéticos: [Detalle de reparación y atención manual](../output/playwright/saas-failure-repairs.png).
- No se ha realizado una intervención en una tienda comercial ni una prueba de impresora física. El nuevo canal todavía requiere el despliegue y la activación indicados arriba.

## Revisión profunda posterior

Se revisaron las carreras de concurrencia, los fallos de transporte y la recuperación de operaciones sin confirmación. Se corrigieron huecos que no estaban cubiertos en la primera validación:

- **Cola de más de diez tareas:** los primeros comandos RUNNING podían ocultar indefinidamente los posteriores QUEUED. La entrega prioriza ahora los pendientes nuevos y sigue permitiendo repetir los ya iniciados.
- **Identidad que cambia durante una espera:** después del bloqueo se vuelven a leer el token, la revocación, el ámbito de instalación y el estado de tienda. La creación vuelve a leer también el fallo para no usar una revisión que cambió mientras esperaba. El ejecutor local vuelve a comprobar y bloquear la licencia y su relación con la tienda antes de modificar el outbox.
- **Comando repetido con contenido distinto:** se rechaza sin enviar un resultado falso que pudiera cerrar como fallida la reparación legítima.
- **Confirmación HTTP insuficiente:** un 2xx vacío, HTML o el resultado de otro comando ya no confirma el recibo. El cliente comprueba commandId y la pareja status/resultCode, admitiendo explícitamente la caducidad conservada por SaaS.
- **Acumulación de timeouts:** la primera indisponibilidad de transmisión de resultados termina las llamadas de esa instalación en el ciclo actual, conservando la evidencia y dando paso a otras instalaciones. El siguiente ciclo vuelve a intentarlo.
- **Interfaz con datos obsoletos:** la lectura pendiente o fallida desactiva nuevas escrituras; respuestas antiguas no modifican otra selección o sesión, ni borran la clave de una solicitud posterior. Las solicitudes sin confirmar permanecen en memoria durante la navegación de esa sesión, sin guardar credenciales ni notas en almacenamiento del navegador.
- **Comentarios duplicados por pérdida de respuesta:** la API administrativa admite requestId opcional para comentarios. Para el mismo ticket/clave, el mismo autor y texto recuperan el comentario existente; otro contenido se rechaza. El detalle conserva la clave y permite reintentar el comentario sin duplicarlo. Las llamadas históricas sin clave mantienen su comportamiento.

El requestId de comentario se devuelve en las respuestas administrativas para reconciliar por identidad, sin inferir igualdad por texto. Las nuevas columnas e índices se incluyen en V71, todavía no desplegada. El cierre o reapertura manual del ticket continúa siendo una decisión explícita del técnico; repetir la solicitud de asociación no reabre automáticamente un caso cerrado.

Resultados finales de la revisión profunda:

- Backend local: **73 pruebas distintas de 11 suites**, cero fallos, errores u omisiones. Incluye 24 pruebas de reparación, migración real completa a V253, arranque Spring y las regresiones de sincronización y release. El último pase de las tres suites de reparación confirmó además el timeout real de bloqueo y rollback.
- Backend SaaS: **70 pruebas distintas**, cero fallos, errores u omisiones: StoreRepair 15, AdminApi 44 y StoreFailure 11. Incluye carreras controladas de revocación, rotación de token, cambio de tienda y versión del fallo, cola de 11 comandos, concurrencia e idempotencia de comentarios.
- Frontend: **90 pruebas**, compilación TypeScript/Vite y ambos recorridos Edge correctos. Los nuevos escenarios cubren lecturas lentas/fallidas, navegación, cambio de sesión, respuestas tardías y comentarios cuya respuesta se pierde.
- Los fixtures HTTP reales de las dos direcciones se mantuvieron activos; las comprobaciones cruzadas no se omitieron. La regresión de recepción de diagnósticos también consumió el payload local real de la primera fase.
- `git diff --check` correcto. No se ejecutó toda la batería histórica del repositorio: se seleccionaron las suites de la funcionalidad modificada y sus regresiones relevantes.
- El test Spring existente emite un error de su scheduler de limpieza de pagos al eliminar el schema durante el cierre; sus aserciones pasan. Es un problema del orden de cierre del test, fuera del flujo de reparación, y no se presenta como ausencia total de errores en logs.

Evidencia: `backend/target/phase2-local-review.log`, `backend/target/phase2-local-review-final.log`, `backend-saas/target/phase2-review-repair-test.log` y `backend-saas/target/phase2-review-regression-test.log`. Las pruebas usaron PostgreSQL aislado; no se desplegó ni se intervino una tienda comercial. La interfaz se verificó con API simulada; sigue pendiente la validación de dos servidores desplegados y una instalación real.
