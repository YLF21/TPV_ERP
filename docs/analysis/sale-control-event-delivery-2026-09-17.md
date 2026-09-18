# Entrega de eventos de eliminación de APP VENTA

Actualizado: 17/09/2026. El guardado local previo y la recuperación de eventos están autorizados e implementados. Se verificaron la desconexión y recuperación en navegador, el reintento tras perder la respuesta de un commit real, la conservación del carrito al fallar IndexedDB y la persistencia del almacén de producción entre tres procesos reales de Electron.

El usuario también aprobó aplicar las reglas vigentes cuando ocurrió el evento. La selección histórica está implementada y verificada: el lote final aprobó 172 pruebas backend y 12 E2E de caja, incluidos el cambio de umbral durante la desconexión y la entrega concurrente. También aprobaron los cuatro E2E de Gestión y diez repeticiones del caso de ocho POST simultáneos. La validación local final está cerrada. Las secciones iniciales siguientes conservan el diagnóstico anterior a la implementación y no describen el comportamiento actual.

## Diagnóstico histórico, anterior a la implementación

La pérdida original era demostrable antes de que la API aceptara un evento: la UI eliminaba la línea y descartaba el envío fallido después de `console.warn`. La cola de promesas solo conservaba el orden mientras vivía esa instancia de `SaleScreen`. No reintentaba, no conservaba pendientes en disco ni informaba al operador.

La idempotencia del backend ya protegía un reenvío con el mismo `deletionOperationId` y el mismo ámbito autenticado. El frontend anterior no aprovechaba esa protección para reintentar. Una respuesta perdida después del commit no implicaba por sí sola pérdida en el servidor: dejaba un resultado desconocido que aquel cliente no conservaba para recuperarlo.

## Flujo y evidencia de la revisión inicial

Las referencias y números de línea de esta sección corresponden a la versión anterior. La implementación posterior se describe más abajo.

- `frontend/packages/app-common/src/components/SaleScreen.tsx`: `SaleDeletionControlSequence` (1496–1520), mutación de línea (3921–3937), envío (4322–4350), vaciado (4353–4375), cierre/sesión (3173–3201). El evento usa UUID de venta y eliminación; la secuencia cambia en las fronteras del carrito. No hay `occurredAt` en el cuerpo. El token queda capturado en el callback que se encola.
- `frontend/packages/app-common/src/api/client.ts`: `apiRequest` (68–140) ejecuta un único `fetch`. No establece plazo, reintento, `keepalive` ni renovación de sesión. El `X-Request-ID` es una referencia de diagnóstico, distinta de la clave de idempotencia del negocio. El proxy de Electron sí tiene un plazo de 120 segundos; no es un reintento.
- `frontend/apps/app-venta/src/main.tsx`: salir de ventas desmonta la pantalla; `handleLogout` (405) elimina la sesión local sin esperar a estos eventos. Los callbacks ya pendientes pueden terminar con el token original si el renderer continúa y ese token sigue válido. No se reutiliza automáticamente el token de otro usuario. Cerrar el proceso pierde callbacks y estado en memoria.
- `backend/src/main/java/com/tpverp/backend/document/SaleLineDeletionService.java`: transacción, ámbito derivado de sesión, `INSERT ... ON CONFLICT(id) DO NOTHING`, comprobación de cabecera repetida y retorno de líneas existentes. El segundo envío no genera otro evento si ya existe. La comprobación de repetición no compara todo el contenido de las líneas; el productor debe reenviar exactamente el snapshot original. Las cabeceras se purgan a los 365 días: la protección no debe presentarse como indefinida.
- Ese servicio y `ControlAlertDetectionService` asignan la fecha con el reloj del servidor al recibir; el detector consulta la configuración activa al procesar. Un reenvío tardío desplazaría fecha y podría aplicar otras reglas sin un cambio de contrato.

## Matriz histórica de escenarios

| Escenario | Comportamiento anterior al cambio | Evidencia / límite de aquella fase |
| --- | --- | --- |
| API disponible, una eliminación o vaciado | Registro y detección transaccionales; siguiente envío espera al anterior | Pruebas existentes de UI y secuencia. La transacción real la valida el trabajo backend separado |
| Desconexión antes de enviar | Línea eliminada; un intento; aviso en consola; evento descartado | Prueba UI existente y nueva prueba de desconexión/reconexión |
| Red vuelve tras el fallo | No se reenvía el evento perdido; una eliminación posterior es un evento distinto | Nueva prueba con `online` y avance del reloj |
| HTTP 400 / 401 / 403 / 409 / 423 / 503 | Mismo descarte, sin diferenciar espera de sesión, conflicto o fallo temporal | Seis casos nuevos de caracterización |
| Respuesta perdida después de posible commit | Resultado local desconocido; sin reintento. Puede existir ya la alerta | Nueva prueba de respuesta 200 cuyo cuerpo falla; no simula un commit real |
| Petición que no termina | Bloquea las posteriores en la cola en memoria | Nueva prueba: no hay plazo del cliente; Electron puede cortar por su proxy a los 120 s |
| Salir de pantalla / cerrar sesión | No esperaba a la cola; callbacks pendientes podían terminar con token original | Inspección del ciclo de vida. En esa fase no se había simulado cierre real de proceso |
| Token expira mientras espera | Al ejecutarse, la API puede devolver 401 y el evento se descarta | Token capturado + prueba HTTP 401 |
| Iniciar sesión como otro usuario | No hay recuperación. Cola previa, si sigue viva, conserva token anterior | Inspección; no hay reasignación automática del actor |
| Recargar, cerrar o reiniciar antes de entregar | Desaparecía la memoria; no había una fuente para recuperar pendientes | Inspección. En esa fase no se había ejecutado un cierre de Electron |
| Reenvío manual con igual UUID, venta, usuario, tienda, terminal y tipo | Devuelve registro existente; no duplica detección | Contrato del servicio; pruebas backend pertenecen al lote separado |
| Eliminar devolución manual con cantidad negativa | La UI podía enviarla; la validación entonces exigía cantidad positiva | Hallazgo inicial; el contrato backend se corrigió posteriormente |
| Producto sin código | La UI enviaba cadena vacía; la validación entonces rechazaba vacío | Hallazgo inicial; el contrato backend se corrigió posteriormente |

## Almacenamiento e identidad revisados antes del cambio

`pendingSaleRecovery.ts` y `cashCloseRecovery.ts` aportan un patrón útil: sobre versionado, validación estricta, errores de escritura propagados y datos corruptos conservados como estado bloqueado. Son recuperaciones específicas, no una cola reutilizable; sus claves por código de terminal no aíslan todos los ámbitos necesarios.

APP PDA dispone de `PdaDurableStorage.ts` (IndexedDB) y `PdaWorkQueue.ts` (reintentos). Sus wrappers absorben fallos de escritura tras emitir un evento y su cola se guarda completa. No sirven directamente como confirmación de que un evento de caja está persistido antes de modificar el carrito.

En escritorio, `frontend/desktop/loopback-server.cjs:198` usa `listen(0, "127.0.0.1")`: el origen tiene puerto aleatorio. `localStorage` e IndexedDB del renderer quedan asociados a ese origen y no son una base suficiente para recuperar la misma cola en un arranque posterior. `main.cjs` ya utilizaba `app.getPath("userData")`, validación IPC y escritura de identidad protegida por DPAPI, pero entonces no había un almacén genérico de eventos. La solución implementada añade el almacén de control en `userData`.

`LoginResult` Java y `UserSession` del frontend contienen `userId`, pero no `storeId` ni identidad de backend. `TerminalContext` y `ServerProvisioningResult` contienen `terminalId`, código, nombre de tienda y credencial; no deben persistirse completos en un evento porque contienen la credencial. El nombre de tienda y la URL loopback no identifican de forma suficiente el destino. La identidad de instalación fiscal no debe reutilizarse sin confirmar que representa correctamente este ámbito operativo.

## Decisiones aprobadas y contrato implementado

1. Construir un snapshot inmutable por eliminación: versión de esquema, `saleOperationId`, `deletionOperationId`, tipo/vaciado, líneas, instante del cliente y ámbito autenticado. Nunca almacenar token, contraseña ni credencial de terminal.
2. Persistir y confirmar escritura **antes de quitar las líneas**. En Electron: módulo IPC acotado en `userData/control-events`, fichero por ID, escritura temporal y publicación atómica, validación de esquema/tamaño/UUID/ruta y del emisor IPC, sin permitir rutas arbitrarias. En navegador: IndexedDB con transacción confirmada, mismo contrato lógico. No cambiar el puerto loopback para resolver esta necesidad.
3. Si falla esa persistencia, conservar el carrito y explicar el error con reintento. Esto bloquea esa eliminación, no toda la operación de venta. Esta decisión funcional fue aprobada y sustituye el comportamiento anterior de descartar el registro fallido.
4. Un emisor por sesión opera en orden, carga pendientes al entrar y al recuperar red, aplica un plazo explícito por envío y espera creciente para red/timeout/5xx. Guarda estado mínimo de intento; 401 espera sesión original; 403/423 requieren resolver su condición; 400/409 permanecen visibles para revisión. Nunca descartar por alcanzar un contador de reintentos.
5. Reenviar siempre el mismo ID y cuerpo. Quitar el registro local solo después de confirmar aceptación 2xx. Si se pierde respuesta o falla el borrado local posterior, repetir es seguro dentro del horizonte de deduplicación. Conservar errores de lectura/corrupción; no interpretar automáticamente corrupción como cola vacía.
6. Separar pendientes por identidad estable de backend/tienda/terminal/usuario. Reanudar únicamente con sesión actual coincidente; conservar los de otras identidades sin enviarlos ni mostrar sus detalles al nuevo usuario. Mostrar al operador su estado pendiente/fallido y una acción de reintento real. Reenviar sin que vuelva el usuario original exigiría otro contrato de autorización y no forma parte de esta opción mínima.
7. El contrato autenticado `GET /sale-line-deletions/context` devuelve `{ storeId, terminalId, userId, serverTime }`. No se añadió una identidad fiscal. El ámbito local combina los UUID operativos con el backend estable de la configuración Electron o la URL API normalizada en navegador. POST contrasta el ámbito con la sesión; los IDs del cuerpo no autorizan atribuir acciones a otro usuario.
8. Conservar `occurredAt` e instante de recepción separados. La fecha se basa en `serverTime` más el tiempo monotónico transcurrido, pero sigue siendo evidencia procedente del terminal. El usuario aprobó aplicar la regla vigente cuando ocurrió el evento y el backend implementa su selección histórica, verificada en el lote final. No se toma una configuración descargada por el terminal como autoridad histórica.

## Límites que debe conservar la especificación

- Una cola local reduce pérdidas por red y reinicio; no garantiza supervivencia frente a disco averiado, borrado del perfil, manipulación, desinstalación o expulsión del almacenamiento del navegador. Los fallos deben ser observables y nunca convertirse silenciosamente en aceptación.
- La escritura local confirmada es el punto de aceptación lógica de la eliminación en la solución aprobada, anterior a actualizar el carrito visual. Un crash en ese intervalo puede dejar el último frame mostrando las líneas aunque el evento ya esté aceptado y guardado. No hay transacción común con el estado React ni se añadió recuperación persistente de todo el carrito.
- En el diagnóstico inicial quedaba por definir cómo afectaba la retención a los eventos recibidos tarde. La implementación actual conserva el plazo existente de **365 días, contado desde la recepción del servidor (`receivedAt`)**, y mantiene el límite exacto de la limpieza. Un evento antiguo recién recibido no se purga por su fecha de ocurrencia. La idempotencia sigue limitada a la conservación de su registro; no se promete deduplicación indefinida tras su eliminación.
- Si el usuario original no vuelve a autenticarse, sus pendientes quedan conservados pero sin entregar bajo esta política mínima. No presentar conservación como entrega garantizada.
- Las pruebas de caracterización iniciales solo demostraban el código anterior. La evidencia posterior de persistencia y entrega integrada se detalla más abajo; no debe confundirse con las limitaciones de aquella primera fase.

## Validación de la fase inicial de diagnóstico

- `npm test -- packages/app-common/src/components/SaleScreen.test.tsx -t 'resets the deletion sequence|serializes deletion records|records the removed line|removes one line even when'`: **4 aprobadas**, 228 omitidas por filtro.
- `npm test -- packages/app-common/src/components/SaleDeletionControlDelivery.test.ts`: **9 aprobadas**. Durante esa fase inicial solo se añadieron ese archivo de pruebas y este documento, sin modificar producción. Después sí se implementó el contrato duradero descrito a continuación.

Las nueve caracterizaciones iniciales fueron sustituidas por pruebas del contrato duradero al implementarlo; no se conserva una suite que exija el antiguo descarte.

## Implementación frontend autorizada

- `saleControlDelivery.ts`: contexto verificado por sesión, hora UTC basada en `serverTime` más tiempo monotónico de sesión, snapshot e ID persistidos antes de modificar carrito. Emisor con plazo de transporte de 30 s, reintento progresivo 2–60 s para fallos temporales, bloqueo visible de 4xx y conservación hasta confirmación. Nunca se cambia el token de un emisor existente por el de otra sesión. Se drena un lote leído, evitando relecturas cuadráticas.
- `saleControlOutboxStorage.ts`: IndexedDB del navegador con transacciones confirmadas; adaptador del bridge nativo. No hay fallback silencioso a memoria cuando no puede persistirse.
- `desktop/sale-control-outbox.cjs`: archivos versionados en `userData/control-events/v1`, ámbitos separados por hash de backend y UUID de tienda/terminal/usuario, un archivo por UUID de evento. La escritura usa temporal, `fsync` y renombrado. Valida campos, tamaño, ruta, identidad y contenido; rechaza campos adicionales como credenciales y conserva errores/corrupción como errores. IPC usa el registrador privilegiado existente, restringido a la ventana principal y su origen.
- `SaleScreen`: espera la escritura local antes de eliminar una línea o vaciar; mientras escribe impide otra mutación o cierre normal. Un fallo conserva el carrito. Estado de pendientes/error y reintento en ES/EN/ZH. Si todavía no existe contexto autenticado válido, rechaza inmediatamente la eliminación y muestra el motivo; no espera bloqueando la caja a que responda un GET de red.
- `apps/app-venta/main.tsx`: emisor durante la sesión, también al salir de ventas hacia inicio; se detiene al cerrar sesión. La recuperación de otros usuarios queda retenida hasta autenticarse con su identidad original.
- Cantidades negativas y fraccionarias de hasta tres decimales, y código vacío, se conservan sin redondeo. El contrato y esquema backend también están adaptados, sin cambiar los cálculos de venta.

Validación frontend: lote completo de ocho archivos con **296 pruebas aprobadas**, incluidas las 235 de `SaleScreen`, 18 iniciales del emisor, 8 iniciales del almacén, preload/seguridad Electron y APP VENTA. Después se añadieron tres pruebas de contexto (401, 403 y respuesta pendiente) y una de directorio enlazado; el total actual es 300 escenarios, con los lotes modificados reejecutados. TypeScript APP VENTA pasó con `tsc --noEmit`; la compilación Vite y las pruebas E2E reales se coordinan en el trabajo principal y no se deducen de estas pruebas unitarias.

## Integración real en navegador · primeras ejecuciones históricas

La tarea principal ejecutó los escenarios de `frontend/e2e/control-alerts-sale-flows.spec.ts` contra el entorno integrado aislado. Las primeras ejecuciones acreditaron estos tres escenarios; sus logs se conservan como evidencia histórica anterior al lote final completo:

| Escenario | Evidencia verificada | Log |
| --- | --- | --- |
| Eliminación sin conexión y cierre de pestaña | El pendiente sobrevive, se recupera y se entrega una sola vez | `.codex-tmp/gestion-fullstack-20260917/sale-e2e-recovery.log` |
| Respuesta perdida después del commit del backend | El evento permanece localmente; el reintento no duplica la alerta | `.codex-tmp/gestion-fullstack-20260917/sale-e2e-recovery.log` |
| Fallo de IndexedDB | Los productos permanecen en el carrito y no se envía la eliminación | `.codex-tmp/gestion-fullstack-20260917/sale-e2e-recovery-final.log` |

Aquellos logs no representaban lotes completos aprobados. El primer lote acreditó los dos primeros escenarios y se detuvo por una comprobación del texto del error de almacenamiento; la reejecución aprobó el escenario de almacenamiento. El segundo log se detuvo posteriormente en otro caso de eliminaciones consecutivas. Esas incidencias de validación quedaron resueltas antes del lote final siguiente; no son fallos pendientes del estado actual.

## Validación integrada final · 17/09/2026

El lote final aprobó **172/172 pruebas backend**, sin errores, fallos ni omisiones: 11 de reglas históricas PostgreSQL, 14 de entrega PostgreSQL, 8 de contexto MVC, 4 de servicio de eliminaciones, 17 del detector, 3 del servicio de alertas y 115 de documentos. Log: `.codex-tmp/gestion-fullstack-20260917/backend-deletion-historical-rules-tests-rerun.log`.

La ejecución completa de caja aprobó **12/12 E2E** en `.codex-tmp/gestion-fullstack-20260917/sale-e2e-final.log`: cuatro casos de precio, descuento manual, vaciado de carrito, desconexión con cierre de pestaña y recuperación, respuesta perdida después del commit, fallo de almacenamiento local, eliminaciones consecutivas, ocho POST simultáneos y selección de regla histórica. El último caso conserva el umbral 2 vigente al borrar aunque se cambie a 5 durante la desconexión; la recuperación no aplica retroactivamente el nuevo umbral.

El caso de ocho POST simultáneos pasó además **10/10 repeticiones** (`sale-e2e-concurrency-final.log`) y los recorridos de Gestión pasaron **4/4** contra el backend final (`gestion-e2e-final.log`), ambos logs en el mismo directorio. Las repeticiones verifican estabilidad del caso concurrente y no se cuentan como diez escenarios funcionales nuevos. El estado conjunto y las demás evidencias están en [gestion-integracion-2026-09-17.md](../gestion-integracion-2026-09-17.md).

## Persistencia entre procesos reales de Electron

Harness explícito: `frontend/scripts/check-sale-control-electron-restart.mjs`, con fixture `frontend/scripts/fixtures/sale-control-electron-restart.cjs`. Se ubica fuera de `desktop` para que el empaquetado de la aplicación no incluya el fixture. Usa el módulo de producción `desktop/sale-control-outbox.cjs` y datos sintéticos; no importa el arranque de APP VENTA ni módulos de hardware.

Comando ejecutado desde `frontend`:

```text
node scripts/check-sale-control-electron-restart.mjs
```

Resultado aprobado con Electron **43.4.1** y tres procesos independientes. Cada uno informó `processType=browser`, `windowsCreated=0` e `isolatedUserData=true`.

| Proceso | PID | Comprobación | Pendientes al salir |
| --- | --- | --- | --- |
| `write` | 44468 | Guarda el evento y verifica su contenido | 1 |
| `recover-and-ack` | 27784 | Recupera exactamente el evento; comprueba aislamiento por usuario, tienda, terminal y backend; ejecuta el borrado local correspondiente al ACK | 0 |
| `verify-empty` | 41748 | Un tercer inicio confirma que el borrado persiste | 0 |

Los tres informes emitieron el mismo SHA-256 del payload: `7f180e60d36710e1c08e88064e636561ff02299b0448881aa7f7aa17a0ce3bcd`.

El harness fijó `userData`, `sessionData`, `logs` y `crashDumps` en un directorio temporal verificado mediante ubicación y marcador. No utilizó configuración real, ventanas, hardware ni servidores. El directorio temporal se eliminó al terminar, comprobando de nuevo su frontera y marcador.

Esta evidencia demuestra persistencia del almacén real en disco a través de procesos Electron. No demuestra un reinicio completo de la interfaz APP VENTA ni crea una ventana con el nuevo origen loopback. El ACK de este harness es la llamada al borrado local; el commit y reintento reales del backend están cubiertos por el E2E de navegador descrito arriba.

## Cierre de validación y límites

La validación local final de la entrega y de la selección histórica de reglas está cerrada con los 172 casos backend y los 12 E2E completos descritos arriba. Se mantiene separada de los 300 escenarios frontend y de las primeras ejecuciones parciales. Los resultados de compilación Vite, compatibilidad y el resto de recorridos integrados se documentan en [gestion-integracion-2026-09-17.md](../gestion-integracion-2026-09-17.md).

El harness cubre procesos Electron y disco, no el ciclo completo de cierre y reapertura de la UI ni pruebas con hardware físico. Se conservan los límites de disco, perfil, reloj y autenticación descritos arriba; no se promete entrega universal ni persistencia invulnerable.
