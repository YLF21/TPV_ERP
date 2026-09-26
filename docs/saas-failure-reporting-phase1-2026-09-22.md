# Fase 1: recepción y consulta de fallos de tiendas

Actualización: esta fase se integra ahora con [Reparación remota y atención manual, fase 2](saas-remote-repair-phase2-2026-09-22.md). Para desplegar el conjunto actual se aplican también V71/V253 y el manifiesto local vigente es V253; las referencias V70/V252 siguientes describen el alcance original de esta fase.

## Resultado y alcance

El backend local conserva fallos de aplicación y los publica mediante el outbox de sincronización existente. El SaaS los recibe con la identidad de la instalación autenticada y los presenta en Supervisión → Fallos de tiendas. No se añaden acciones remotas, cambios manuales de estado, tickets automáticos ni integración TeamViewer en esta fase.

Se conservan las alertas de control y los fallos de sincronización ya soportados. La nueva captura cubre excepciones MVC no resueltas y errores HTTP 5xx de controladores locales, dentro de una sesión operativa autenticada. Los errores normales de validación/autorización y conflictos HTTP 4xx no se convierten en incidencias técnicas. Los fallos técnicos identificados por PrintRenderingException sí se capturan aunque el contrato histórico devuelva HTTP 409. La preparación de impresión que falla después de confirmar una venta se registra expresamente sin cambiar el resultado de la venta; también se cubre la confirmación de borradores, el renderizado de tickets que vuelve a un resultado alternativo, los recibos de cobro y las reimpresiones con error técnico.

No es un capturador universal de todas las aplicaciones: no observa por sí mismo fallos físicos de impresora comunicados solamente a Electron, excepciones exclusivas del navegador ni un proceso local completamente detenido. Esos orígenes requieren sus propios adaptadores. Si la base de datos local no está disponible, la captura registra un aviso local y conserva el error original; no puede prometer persistencia en una base inaccesible.

## Recorrido de los datos

1. `ApplicationFailureRecorder` obtiene la tienda de `OperationalSessionContext` y la instalación/licencia del servidor, nunca de parámetros de la petición.
2. Guarda una evidencia técnica en `local_application_failure` dentro de una transacción independiente. Un fallo comercial que se revierte no borra la evidencia.
3. La huella de empresa, tienda, instalación, módulo, versión, excepción y ubicación agrupa repeticiones. Se conservan primera/última detección, contador, revisión y el último traceId.
4. Cada ocurrencia de aplicación se guarda y añade al outbox en la misma transacción independiente, conservando su traceId aunque haya más errores antes del próximo envío. `StoreFailureScheduler`, con el worker habilitado, recoge las otras fuentes y recupera informes cada 60 segundos por defecto. El transporte utiliza las credenciales existentes de la instalación.
5. Una desconexión mantiene los eventos para los reintentos existentes. Si un informe de aplicación agota los intentos, el escáner reabre el evento original en lotes limitados, incluyendo revisiones anteriores, conservando su identidad y su traceId. Los fallos del propio informe no generan informes recursivos.
6. SaaS valida procedencia y contrato, aplica revisiones crecientes y conserva el resultado en `saas_store_failure`. Un duplicado no aumenta el contador; una revisión antigua no sustituye la actual, pero conserva su referencia en el historial para encontrar también el primer error. Cada revisión conserva su hash; reenviarla con otro contenido se rechaza.
7. El panel permite buscar por empresa, tienda, instalación, referencia, módulo o traceId. La empresa se obtiene del directorio completo, incluidas empresas sin licencia en el listado actual.

## Información diagnóstica

El contrato `STORE_FAILURE` de versión 1 sigue admitido sin cambios. La versión 2 añade exactamente cinco claves (admiten null): `module`, `appVersion`, `traceId`, `exceptionType` y `errorLocation`. `traceId` conserva el mismo valor devuelto al cliente por el filtro de correlación; no se reemplazan las referencias `web-...` por otro UUID. La nueva fuente es `LOCAL_APPLICATION`, código `APPLICATION_ERROR`, gravedad `DANGER`, estado `OPEN`.

Los campos contienen identificadores técnicos limitados: módulo conocido, versión, identificador de seguimiento de 8 a 128 caracteres seguros (UUID o referencia web histórica), clase de excepción y `clase.metodo:linea`. No se envían mensajes crudos de excepción, contenido de peticiones, SQL, rutas de archivos, credenciales ni datos comerciales. La clase/ubicación y el traceId sirven para localizar el código y correlacionar el diagnóstico local. Cuando una fuente no tiene datos, el panel muestra que no fueron comunicados.

La hora `receivedAt` indica cuándo recibió SaaS la revisión; no prueba que la tienda esté conectada ahora. Una excepción de aplicación permanece abierta: la ausencia de nuevos errores no acredita su recuperación. Las alertas y los fallos de sincronización mantienen los estados comunicados por sus respectivas fuentes.

## Activación

Actualizar primero SaaS con la migración **V70**, después el backend local con **V252**, y servir el frontend SaaS actualizado. El SaaS anterior rechaza el nuevo contrato v2, por lo que debe respetarse este orden. Las migraciones son aditivas; no se reescriben las anteriores.

V252 actualiza también el manifiesto de release local y sus secuencias para que la protección de versión permita avanzar desde V251. No se debe lanzar un binario antiguo sobre un esquema nuevo.

La tienda debe estar vinculada al SaaS, la sesión debe estar asociada a una terminal y debe estar activo `TPV_SYNC_WORKER_ENABLED=true`. El perfil local `saas-dev` ya habilita ese worker por defecto y dirige licencia/sincronización al gateway local. En producción se mantiene la configuración explícita existente.

No se introduce un endpoint público para provocar fallos. La validación utiliza instalaciones, empresas y errores sintéticos en bases/esquemas de prueba separados.

## Verificación

Las pruebas locales cubren captura segura, ausencia de ámbito autenticado, persistencia independiente del rollback comercial, agrupación, revisiones y publicación. Las pruebas SaaS usan PostgreSQL real, validan contrato v1/v2, autorización por instalación, duplicados, revisiones retrasadas, rechazo de campos inseguros y consulta administrativa.

El test local genera `backend/target/phase1-application-payload.json` con datos sintéticos a partir del publicador real. El test SaaS `actualLocalPublisherPayloadSurvivesAuthenticatedHttpAndAdminQuery` lo consume cuando `TPV_PHASE1_CAPTURED_PAYLOAD` apunta al archivo; sustituye únicamente la identidad de instalación para usar la instalación autenticada creada por el test. Este paso verifica la compatibilidad entre módulos a través de los controladores HTTP de Spring (MockMvc), no un despliegue productivo ni dos servidores de tienda y SaaS en ejecución.

El test de navegador `frontend-saas/e2e/failure-diagnostics-smoke.mjs` usa respuestas sintéticas y comprueba diagnósticos, filtros, datos históricos sin los campos nuevos y copia del traceId. Admite `PLAYWRIGHT_CHANNEL=msedge` para utilizar Edge instalado.

### Resultado inicial del 22/09/2026

- Backend local: 108 pruebas, cero fallos/errores/omitidas; incluye captura, persistencia, impresión, contratos de venta, API de errores, transporte/reintentos y guard de release V252.
- Backend SaaS: 64 pruebas distintas, todas correctas (44 de administración y 20 de sincronización/supervisión en la ejecución final). La ejecución final incluye el payload real del publicador y no omite ninguna prueba.
- Frontend SaaS: 90 pruebas correctas, compilación TypeScript/Vite correcta y recorrido E2E específico correcto con Edge. Se comprobó ausencia de errores JavaScript, diagnósticos, filtros, copia correcta/rechazada, compatibilidad histórica y ausencia de desbordamiento horizontal.
- PostgreSQL 18.4 independiente en loopback, con datos sintéticos. No se usaron bases comerciales. Los tests `SyncEventApiTest` requieren una base nueva para repetir la suite: reutilizar sus identificadores fijos provoca conflictos de fixtures, no un fallo del nuevo contrato.
- La comprobación cruzada utiliza MockMvc con PostgreSQL real. El E2E de navegador usa API simulada; no se afirma una prueba de dos servidores desplegados ni una prueba con impresora física.
- No se desplegó ni se modificaron registros del entorno comercial. La captura de hardware exclusivamente en Electron permanece fuera del alcance de estos hooks de backend.

### Repetir las comprobaciones

Configurar una base PostgreSQL **nueva y exclusiva de pruebas**, con `TPV_ERP_TEST_DB_URL`, `TPV_ERP_TEST_DB_USER`, `TPV_ERP_TEST_DB_PASSWORD` para el backend local y `TPV_TEST_DB_URL`, `TPV_TEST_DB_USERNAME`, `TPV_TEST_DB_PASSWORD`, `TPV_TEST_DB_DRIVER=org.postgresql.Driver` para SaaS. Usar JDK 25.

Desde `backend`:

```powershell
.\mvnw.cmd '-Dtest=ApplicationFailureRecorderTest,ApplicationFailureWebConfigurationTest,SalesDocumentPrintFailureReportingTest,StoreFailurePublisherPostgreSqlTest,StoreFailureEvidenceTest,SalesDocumentCheckoutControllerContractTest,SalesDocumentCheckoutJsonContractTest,FiscalReleaseBuildProfileContractTest,FiscalRuntimeGuardInitializerTest,SyncOutboxWorkerTest,SyncOutboxServiceTest,HttpSyncEventSenderTest,ApiExceptionHandlerTest,PrintFailureReporterTest,PrintRenderingFailureTest,DocumentServiceTest,CustomerReceivablePrintServiceTest,InvoiceJasperRendererTest,TicketJasperRendererTest,OperationalReceiptJasperRendererTest,SalesActivityJasperRendererTest,TicketCancellationJasperRendererTest,CustomerModel347JasperRendererTest' test
```

Después, desde `backend-saas` (la variable del fixture es obligatoria para verificar el recorrido entre módulos):

```powershell
$env:TPV_PHASE1_CAPTURED_PAYLOAD = (Resolve-Path '..\backend\target\phase1-application-payload.json').Path
.\mvnw.cmd '-Dtest=StoreFailurePostgreSqlTest,SyncEventApiTest,AdminApiTest' test
```

Desde `frontend-saas`:

```powershell
npm.cmd test
npm.cmd run build
$env:PLAYWRIGHT_CHANNEL = 'msedge' # O Chromium de Playwright, omitiendo esta variable.
node e2e/failure-diagnostics-smoke.mjs
```

El recorrido específico también está incluido al final de `npm run test:e2e`.
## Segunda revisión profunda

Se reprodujeron y corrigieron los siguientes huecos que las primeras pruebas no cubrían:

- **Errores de impresión omitidos:** ciertos servicios devolvían una alternativa tras capturar el error; otros convertían un fallo Jasper real en HTTP 409. Se observa ahora el punto que absorbe el fallo y se identifica expresamente el fallo técnico de renderizado, manteniendo las respuestas de venta y la exclusión de errores de negocio normales.
- **Referencias de cliente perdidas:** la agrupación previa al envío conservaba sólo la última referencia. Se encola cada ocurrencia atómicamente y SaaS mantiene recibos por revisión con su hash y referencia. La llegada fuera de orden conserva todos los identificadores recibidos sin reabrir ni sustituir un estado posterior. La recuperación de entregas agotadas conserva también los eventos antiguos.
- **Referencias web incompatibles:** se conserva el texto seguro que el cliente ya recibe, incluyendo el fallback `web-...`, en lugar de generar un UUID sin relación.
- **Mutación entre empresas por eventId repetido:** el receptor verifica empresa, tienda e instalación propietarias antes de registrar un conflicto. Una instalación ajena no puede modificar ni el evento original ni sus referencias.
- **Fechas fuera del rango operativo:** se rechazan fechas anteriores a 1970 como solicitud inválida, antes de intentar persistirlas.
- **Selector de tienda y detalle:** el texto visible de tienda se separa de la consulta de búsqueda; se resuelven etiquetas pegadas cuando llega la respuesta. Abrir detalle en una lista larga mueve el foco y lo devuelve a la fila al cerrar. El E2E usa filtros efectivos, 108 tiendas y 55 incidencias.

Las migraciones V70/V252 se ajustaron porque esta implementación sigue sin desplegar y dichas migraciones son nuevas en esta tarea. Si alguien hubiese aplicado una versión intermedia fuera de estos tests, debe tratar ese entorno expresamente; no se debe intentar ignorar una discrepancia de checksum de Flyway.

La captura sigue siendo de mejor esfuerzo cuando falla su propia base de datos. El timeout de transacción no constituye un límite total de latencia: adquirir una conexión del pool puede esperar más tiempo cuando está saturado. No se ha realizado una prueba de carga del pool ni una prueba física de impresora en esta revisión.
### Resultado de la segunda revisión

- Backend local: 270 pruebas distintas de 23 suites, cero fallos, errores u omisiones en los informes finales. La primera ejecución detectó un mock fiscal omitido en un test nuevo; se corrigió la preparación y se repitieron las 116 pruebas de DocumentServiceTest, todas correctas. No hizo falta cambiar código productivo por ese fallo de fixture.
- Backend SaaS: 23 pruebas de recepción y supervisión correctas, cero omisiones; cubren historial de traceId, referencias web, revisiones antiguas/conflictivas, aislamiento entre empresas y compatibilidad de contrato.
- Frontend SaaS: 90 pruebas correctas, compilación TypeScript/Vite y E2E en Edge correctos. El recorrido ampliado usa 108 tiendas y 55 incidencias con filtros efectivos, navegación por teclado y recuperación de foco.
- Los 44 tests de administración pasaron en la verificación inicial; no se cuentan como repetidos en esta segunda ejecución.

La última comprobación cruzada volvió a ejecutar StoreFailurePostgreSqlTest con el fixture regenerado por el backend local final: 11/11 pruebas correctas, sin omisiones. Estas 11 pruebas son parte de las 23 anteriores, no adicionales.
