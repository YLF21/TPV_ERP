# Fase 3: asistencia remota humana y atención presencial

## Flujo vigente desde V73 (1 de octubre de 2026)

Esta sección sustituye las reglas de cierre de V72 descritas más abajo como historial de la implementación.

- **Soporte → Gestionar incidencia** abre el mismo seguimiento que el detalle del fallo. Incluye tickets generales y empresas sin licencia.
- **Derivar a soporte manual** permite crear o recuperar el ticket aunque todavía exista un reintento automático disponible. Durante un comando activo se espera su resultado.
- **Guardar planificación** registra responsable y, si procede, fecha y hora de visita. La pantalla usa la zona horaria del navegador y el servidor conserva el instante UTC. Iniciar una visita exige haber guardado su fecha.
- La asistencia remota o presencial pasa primero a **Pendiente de comprobación**, con la solución aplicada. **Resolver intervención** exige comprobaciones realizadas y la persona que confirma. **No funciona: volver a diagnóstico** reabre el diagnóstico; también se puede derivar a visita desde la comprobación.
- La API genérica de actualización de tickets permite prioridad y actualizaciones que conservan el estado. Rechaza cambios de estado: todos los cierres y reaperturas pasan por el seguimiento con versión y estado esperado.
- Cerrar un ticket no cambia el fallo técnico. Se muestran por separado el último estado recibido del fallo y el estado de atención. Los cierres anteriores sin pruebas estructuradas se conservan y se identifican como tales.
- **Reabrir intervención** conserva el historial, responsable y evidencias históricas; limpia fecha de visita y comprobaciones del ciclo anterior. Iniciar otra asistencia remota con ID vacío elimina el ID vigente.

| Desde | Acción | Resultado |
| --- | --- | --- |
| Pendiente | Iniciar asistencia remota | Asistencia remota en curso |
| Pendiente / remota / comprobación | Derivar a visita presencial | Requiere visita |
| Requiere visita, con fecha guardada | Iniciar visita presencial | Visita en curso |
| Remota / visita en curso | Solución + pasar a comprobación | Pendiente de comprobación |
| Comprobación | Nota de fallo + volver a diagnóstico | Pendiente, ticket abierto |
| Comprobación | Pruebas + confirmado por + resolver | Resuelto |
| Resuelto | Motivo + reabrir | Pendiente, ticket abierto |

V73 añade responsable, visita y evidencia al estado y a los eventos, así como una huella del contenido de cada petición para detectar reutilizaciones incompatibles de su clave. Los tickets generales usan las mismas transacciones, permisos y controles de concurrencia que los vinculados. Las notas internas no se publican como comentarios del cliente.

Aplicar primero el backend y la migración **V73__complete_support_workflow.sql**, después el frontend. El backend de tienda no cambia. TeamViewer sigue siendo una herramienta externa; SaaS registra la actuación y la comprobación declarada por el técnico.

Verificación de esta ampliación: 16 pruebas PostgreSQL/HTTP de intervenciones y 15 de reparación correctas; un fixture opcional entre módulos omitido por no estar configurado. Frontend: 90 pruebas unitarias, compilación y recorridos de reparaciones, intervenciones y notificaciones correctos. Se cubren cierre sin evidencia, visita sin fecha, comprobación fallida, reapertura, ticket sin fallo/licencia, derivación directa, reintento de planificación y conservación de cierres antiguos. La validación operativa con una tienda real sigue fuera de estas pruebas.

## Historial de V72

## Alcance

Completa el recorrido iniciado en las fases [de diagnóstico](saas-failure-reporting-phase1-2026-09-22.md) y [de reparación limitada](saas-remote-repair-phase2-2026-09-22.md): cuando una reparación automática no es compatible o no resuelve el caso, el técnico trabaja en el ticket vinculado al fallo, registra la asistencia remota y, si hace falta, deriva el trabajo a una intervención presencial.

La entrega añade coordinación y trazabilidad dentro del SaaS. La ejecución de TeamViewer y el trabajo presencial siguen a cargo del técnico. No hay control de escritorio embebido ni confirmación automática de una conexión externa.

## Uso

1. Abrir **Supervisión → Fallos de tiendas**, seleccionar el fallo y crear o recuperar su ticket mediante **Derivar a soporte manual**.
2. En el ticket vinculado, registrar la nota de intervención y, opcionalmente, el ID numérico de TeamViewer. **Iniciar asistencia remota** registra que el técnico empieza a atender el caso.
3. Copiar el ID y abrir la página oficial de TeamViewer. El técnico establece la conexión en esa herramienta. Abrir la página no modifica el estado ni demuestra que haya conexión.
4. Si la reparación necesita presencia física o el acceso remoto no es posible, elegir **Requiere atención presencial** y explicar el motivo. Se puede derivar directamente desde pendiente si el diagnóstico ya indica que no es viable la conexión.
5. Registrar el inicio de la intervención presencial y, cuando se termina la atención, resolver con una nota de resultado. Una atención resuelta se puede reabrir explícitamente para otro seguimiento remoto.
6. Consultar en el mismo detalle la secuencia de acciones, autor, fecha y notas. Los comentarios generales del ticket siguen disponibles.

El cierre del ticket documenta la atención. No sustituye ni cierra automáticamente la evidencia técnica del fallo, ni acredita por sí solo que una impresora u otro dispositivo haya vuelto a funcionar.

## Estados y transiciones

| Estado actual | Acción | Estado siguiente |
| --- | --- | --- |
| REMOTE_PENDING | START_REMOTE | REMOTE_IN_PROGRESS |
| REMOTE_PENDING / REMOTE_IN_PROGRESS | REQUIRE_ONSITE, con motivo | ONSITE_REQUIRED |
| ONSITE_REQUIRED | START_ONSITE | ONSITE_IN_PROGRESS |
| REMOTE_IN_PROGRESS / ONSITE_IN_PROGRESS | RESOLVE, con resultado | RESOLVED |
| RESOLVED | REOPEN | REMOTE_PENDING |

El ticket se actualiza en la misma transacción: EN_CURSO mientras se atiende, RESUELTO al resolver y ABIERTO al reabrir el seguimiento remoto. El recorrido conserva los permisos de soporte existentes. La pantalla de soporte general sigue siendo compatible; sus cambios de estado invalidan las versiones anteriores del recorrido para evitar que una pestaña antigua sobrescriba una reapertura.

## Contrato y persistencia

- `GET /api/v1/admin/tickets/{ticketId}/interventions`: devuelve `ticketId`, `companyId`, `status`, `version`, `ticketStatus`, `teamViewerId` y `events`.
- `POST` en la misma ruta: `requestId`, `expectedVersion`, `expectedTicketStatus`, `action`, `note` y `teamViewerId` opcional. Las notas tienen de 5 a 2000 caracteres Unicode. Se rechazan NUL y secuencias UTF-16 no válidas antes de escribir en PostgreSQL. El identificador admite de 6 a 15 dígitos y sólo se modifica al iniciar asistencia remota; dejarlo vacío al iniciar elimina el ID vigente, sin modificar los eventos históricos.
- El servidor comprueba que el ticket esté asociado a un fallo de su misma empresa. La identidad y el autor salen del ticket y de la sesión administrativa.
- GET requiere `VIEW_ADMIN_DATA`; POST requiere `MANAGE_SUPPORT_TICKETS`.
- El bloqueo de la fila del ticket serializa las transiciones. Una versión o estado obsoletos producen conflicto sin guardar acciones parciales. Los cambios de estado por el soporte general también avanzan la versión, incluso antes de la primera intervención.
- Una clave de petición global identifica una única solicitud inmutable. La repetición del mismo autor y contenido devuelve el estado actual con su evento ya conservado; una reutilización diferente se rechaza. No se duplica la acción ni su auditoría.
- La migración **V72__support_interventions.sql** conserva el estado vigente y el historial de acciones. Las versiones del historial pueden tener saltos por cambios de estado en el soporte general.
- El navegador mantiene en memoria de la sesión las solicitudes sin confirmar y reintenta con la misma clave y contenido. La confirmación usa el requestId del evento. No se usa almacenamiento permanente del navegador para notas, credenciales o solicitudes.
- Las notas internas y el ID permanecen en el historial administrativo; no se copian automáticamente a los comentarios que puede consultar una cuenta tenant.

## TeamViewer

La interfaz ofrece un enlace fijo a [TeamViewer Remote](https://web.teamviewer.com/) y copia del ID. Sigue el flujo documentado de [conexión por ID de TeamViewer](https://www.teamviewer.com/en/global/support/knowledge-base/teamviewer-remote/remote-control/connect-via-id-and-password/), consultado el 22/09/2026. La autenticación de la conexión se realiza allí. Este módulo no solicita ni almacena la contraseña remota y no necesita claves de API de TeamViewer.

## Despliegue

Actualizar primero backend SaaS hasta **V72**, después frontend SaaS. Se mantienen las migraciones anteriores V70/V71. Esta fase no requiere otra migración local: el ejecutor del backend de tienda sigue en V253 y conserva su activación explícita descrita en fase 2.

No se ha desplegado esta entrega ni se han establecido conexiones con equipos de clientes. Las comprobaciones usan datos sintéticos y PostgreSQL aislado.

## Verificación

Para repetir la verificación, usar JDK 25 y PostgreSQL nuevo y exclusivo de pruebas. Configurar `TPV_TEST_DB_URL`, `TPV_TEST_DB_USERNAME`, `TPV_TEST_DB_PASSWORD` y `TPV_TEST_DB_DRIVER=org.postgresql.Driver`. Las pruebas de administración usan datos fijos: iniciar una base nueva al repetir la suite completa.

Desde `backend-saas`:

```powershell
$env:TPV_PHASE2_RESULT_FIXTURE = (Resolve-Path '..\backend\target\phase2-local-repair-result.json').Path
$env:TPV_PHASE1_CAPTURED_PAYLOAD = (Resolve-Path '..\backend\target\phase1-application-payload.json').Path
.\mvnw.cmd '-Dtest=SupportInterventionPostgreSqlTest,StoreRepairPostgreSqlTest,StoreFailurePostgreSqlTest,AdminApiTest,AdminServicePairingLockTest,AdminUserActivationServiceTest' test
```

Los fixtures deben generarse mediante las pruebas locales descritas en fase 2 si aún no existen; no contar las pruebas cruzadas como verificadas cuando se omiten por ausencia de esos archivos.

Desde `frontend-saas`:

```powershell
npm.cmd test
npm.cmd run build
$env:PLAYWRIGHT_CHANNEL = 'msedge'
node e2e/failure-diagnostics-smoke.mjs
node e2e/failure-repairs-smoke.mjs
node e2e/support-interventions-smoke.mjs
```

Las pruebas del servidor utilizan PostgreSQL real y MockMvc; los recorridos de navegador usan API simulada. No equivalen a una prueba de conexión remota con un dispositivo real.

### Validación inicial del 22/09/2026

- **Backend SaaS: 83 pruebas distintas correctas, cero omisiones.** Incluye 8 nuevas de intervención, 15 de reparación remota, 11 de diagnósticos, 44 de administración y 5 unitarias de administración afectadas por la dependencia nueva. La migración V72 se ejecutó contra PostgreSQL real en una base exclusiva.
- La primera tanda encontró una aserción que contaba eventos de otros tickets. Se corrigió para comprobar sólo el ticket del caso, conservando el esperado cero. Las 8 pruebas nuevas se repitieron y pasaron con la validación final de ID; las otras 75 ya habían pasado. No se suman las repeticiones como pruebas distintas.
- Se mantuvieron activos los dos fixtures reales de integración entre módulos, sin omitir las comprobaciones.
- **Frontend: 90 pruebas correctas**, compilación TypeScript/Vite y **tres recorridos Edge correctos**: diagnóstico, reparación de fase 2 y asistencia de fase 3. Se conserva el aviso de tamaño de bundle de Vite; la compilación termina correctamente.
- El nuevo recorrido verifica remoto → presencial → resolución → reapertura, derivación directa, permisos, notas/ID, copia y enlace oficial, respuestas perdidas y vacías, reintentos con la misma petición, conflicto de versión, versiones con saltos, consultas lentas, navegación y cambio de sesión. Los tests de la fase 2 conservan sus aserciones de resolución/reapertura mediante las nuevas acciones.
- Revisión independiente: bloqueo de lectura/escritura coherente, protección de cierre/reapertura concurrentes, respuestas HTTP confirmadas antes de descartar peticiones, refrescos sin bucle, separación de notas internas y diagnóstico técnico. Se corrigió también la validación de longitud Unicode para que las entradas inválidas produzcan 400 en vez de error SQL.
- `git diff --check` correcto. Se revisó visualmente la [captura de asistencia y visita presencial](../output/playwright/saas-support-interventions.png). Los servidores de prueba se cerraron.

Evidencia backend: `backend-saas/target/phase3-saas-test.log` y `backend-saas/target/phase3-saas-final-intervention-test.log`. La primera contiene el fallo de aislamiento del test ya corregido; la segunda confirma el pase final de las 8 nuevas pruebas.

No se ejecutó toda la batería histórica del repositorio ni se desplegó esta entrega. El backend local no cambia en esta fase. No se ha realizado una conexión TeamViewer con un cliente ni una intervención física; los recorridos Edge usan datos y respuestas de API sintéticos.

## Revisión profunda posterior

Se revisó también la interacción con la pantalla general de soporte y las peticiones que siguen en vuelo después de cerrar una vista. Se localizaron y corrigieron problemas adicionales:

- **Actualizaciones desde una pantalla antigua de soporte:** bloquear la fila sólo serializaba el servidor; no rechazaba una decisión tomada sobre datos antiguos. Los tickets asociados a fallos exponen `interventionVersion` en la respuesta administrativa. Su actualización genérica de estado requiere `expectedInterventionVersion` y `expectedTicketStatus`; si faltan o no coinciden, devuelve 409 sin escribir. Los tickets normales conservan el contrato anterior y las actualizaciones sólo de prioridad no restauran estados antiguos. La interfaz envía únicamente el estado solicitado, sin reenviar una prioridad obsoleta.
- **Auditoría de cambios generales:** las entradas de actualización conservan los estados anterior/nuevo y la versión, de modo que una resolución y reapertura fuera del panel específico se puedan distinguir aunque el historial de intervenciones tenga saltos de versión.
- **ID de un dispositivo anterior:** comenzar otra intervención con el campo vacío ya no hereda silenciosamente el ID previo. Los eventos antiguos conservan su evidencia.
- **Respuestas tardías después de volver a abrir el módulo:** una vista desmontada no puede eliminar la petición pendiente compartida con la vista nueva. La confirmación de la vista activa usa la lectura o el recibo de la petición original.
- **Comentarios con respuestas inválidas o rechazo definitivo:** se valida la identidad y contenido de la confirmación antes de descartar la clave pendiente. Una respuesta vacía o incoherente conserva la posibilidad de reintentar; un rechazo confirmado permite recuperar la edición después de actualizar los datos.
- **Texto Unicode y PostgreSQL:** las capas del contrato acuerdan la longitud por caracteres Unicode y rechazan los valores que PostgreSQL no puede guardar. Se evita tanto el error 500 por NUL como el rechazo incorrecto de una nota válida con caracteres suplementarios.

### Resultado final de la revisión profunda

- **Backend SaaS: 90 pruebas distintas correctas, cero fallos, errores u omisiones**: AdminApi 44, PairingLock 3, AdminUserActivation 2, StoreFailure 11, StoreRepair 16 y SupportIntervention 14. Los dos fixtures reales entre módulos siguieron activos.
- Los nuevos casos de PostgreSQL/HTTP comprueban CAS desde el soporte general, ausencia de escritura o auditoría tras conflicto, cambios de estado mientras se espera un bloqueo, lectura coherente tras un commit, rollback completo cuando falla la auditoría al confirmar la transacción, conservación de la clave al reintentar y texto Unicode de 2000 caracteres guardado sin alteración. El diagnóstico y los comentarios visibles al tenant siguen independientes.
- Dos fixtures nuevos enviaban caracteres que Java/MockMvc sustituía antes de llegar al servidor. Se corrigió el envío para construir escapes JSON ASCII en tiempo de ejecución y comprobar explícitamente el cuerpo. Se repitieron las 30 pruebas de sus dos suites, todas correctas; las otras 60 ya habían pasado con el mismo código de producción. Las repeticiones no se cuentan como pruebas distintas.
- **Frontend: 90 pruebas, compilación de producción y tres recorridos Edge correctos**. Se añadieron verificaciones de respuestas tardías tras cerrar/reabrir el detalle tanto en reparaciones como en intervenciones/comentarios; una primera respuesta no puede borrar la segunda solicitud pendiente. También se cubren 204/JSON erróneo/otro ticket, recuperación después de rechazo 409, comentarios GET inválidos, Unicode, vaciado del ID y la pantalla general de soporte con una versión obsoleta.
- El fixture del navegador reproduce la transición del backend: avanzar versión sólo cuando cambia el estado, volver a REMOTE_PENDING sólo al salir de RESUELTO y conservar el estado de intervención en las demás actualizaciones generales.
- La consulta por ticket/empresa del vínculo manual dispone de un índice en V72. `git diff --check` correcto. Se cerraron los servidores de prueba y PostgreSQL aislado.

Informes: `backend-saas/target/phase3-deep-saas-test.log` y `backend-saas/target/phase3-deep-final-saas-test.log`; los XML finales de Surefire contienen el resultado actualizado por suite. Los logs incluyen los errores provocados deliberadamente por las pruebas negativas y de rollback.

No se desplegó la entrega ni se realizó una conexión TeamViewer con clientes. El navegador usa API simulada y los tests HTTP del servidor usan MockMvc con PostgreSQL real; sigue pendiente la validación operativa en una tienda real.

## Continuidad del trabajo y flujo realista — 02/10/2026 (V74)

La revisión se implementó en tres fases coordinadas: continuidad de borradores y navegación, fases operativas y asignación, y seguimiento unificado. Las migraciones V73 y V74 se aplican en ese orden; V74 amplía las tablas existentes sin sustituir el historial.

### Fase 1: conservar el contexto y el trabajo

- Guardar responsable y visita modifica únicamente la planificación. Conserva las notas, solución y comprobaciones todavía sin enviar.
- Los borradores se conservan en memoria durante la sesión y entre ambas pantallas del mismo ticket. Cerrar sesión o recargar completamente el navegador los descarta; no se almacenan notas privadas en almacenamiento persistente del navegador.
- Abrir soporte desde un fallo selecciona empresa y ticket, abre su seguimiento y ofrece volver al fallo original.
- Los comentarios usan el mismo componente en ambas pantallas. Un resultado incierto conserva el identificador y contenido originales; reintentar o actualizar permite confirmar el envío sin duplicarlo. Las respuestas tardías de otra pantalla o sesión no cambian el trabajo actual.

### Fase 2: atención, esperas y técnicos

1. Desde pendiente de diagnóstico, elegir **Trabajar desde SaaS** para revisar o ajustar la configuración. La acción registra el trabajo; el ajuste debe realizarse en el módulo correspondiente.
2. Si hace falta acceder al equipo, iniciar asistencia remota. TeamViewer abre fuera del panel; registrar la fase no establece una conexión.
3. Si hace falta una visita, derivar a presencial, elegir un técnico activo con permiso de soporte, guardar la fecha y registrar el inicio.
4. En cualquier fase activa, esperar al cliente o al material requiere una nota y una próxima revisión futura. Retomar devuelve a la fase anterior, conservando la planificación y solución.
5. Después de actuar, registrar la solución y pasar a comprobación. Para cerrar se exigen comprobaciones y la persona que confirma. Si la prueba falla, volver a diagnóstico o coordinar una visita.

La asignación utiliza el identificador real del usuario. Los nombres antiguos siguen visibles como información histórica, pero una nueva asignación exige seleccionar un usuario autorizado. Las colas **Mis incidencias**, **Sin asignar**, **Visitas pendientes** y **Esperas por revisar** se aplican a la empresa seleccionada. Las fechas de revisión sirven para seguimiento en la cola; no envían recordatorios externos.

### Fase 3: seguimiento y cierre claros

El panel destaca la fase actual y la siguiente actuación. La cronología combina intentos automáticos, intervenciones internas y comentarios del ticket, indicando su distinta visibilidad. El cierre muestra **Atención cerrada · recuperación técnica pendiente** cuando el fallo técnico continúa abierto, junto con la fecha de la última información recibida. No se inventa recuperación de la tienda al cerrar el ticket. Los avisos de tickets urgentes dejan de mostrarse al resolver y pueden volver al reabrir.

### Pruebas de esta entrega

- PostgreSQL aislado: 21 pruebas de intervención correctas; 15 pruebas de reparación correctas y 1 omitida por no disponer del fixture opcional del trabajador local.
- Frontend: 90 pruebas unitarias y compilación TypeScript/Vite correctas.
- El nuevo recorrido `e2e/incident-realistic-flow-smoke.mjs` cubre resolución completa desde SaaS, preservación de borradores, técnicos reales, ambas esperas, retorno a la fase anterior, colas de trabajo, navegación al ticket correcto, vuelta al fallo y reintento idempotente de comentarios generales.
- Pasaron los cuatro recorridos de navegador: `support-interventions-smoke.mjs`, `failure-repairs-smoke.mjs`, `support-notifications-smoke.mjs` e `incident-realistic-flow-smoke.mjs`. Se conservaron las comprobaciones de permisos, concurrencia, respuestas inválidas y cambios de sesión de los recorridos anteriores.
- El servidor local se actualizó a V74; salud `UP`, acceso administrativo y seguimiento consultados contra el backend real. La comprobación visual no modificó incidencias locales.

Las pruebas de navegador usan API simulada y no sustituyen una prueba operativa con una tienda real. El backend local y el frontend de desarrollo permiten continuar las pruebas manuales.

## Correcciones del canal de tienda — 02/10/2026

El portal de clientes tiene una entrada independiente: `http://127.0.0.1:5175/tienda.html`. El login interno enlaza a esta entrada, pero mantiene la autenticación administrativa. El portal admite únicamente usuarios de tienda; para conceder acceso, crear un usuario cliente con rol `MANAGER`, acceso a su empresa/tiendas y privilegio `SUPPORT` desde la administración. No reutilizar una cuenta ADMIN como gestor. La compilación genera `index.html` y `tienda.html` con sus respectivas entradas.

Los comentarios de tienda utilizan una clave `requestId` estable: el servidor devuelve el mismo comentario en un reintento y rechaza reutilizar la clave con otro texto o autor. La interfaz conserva el mensaje pendiente por sesión, empresa y ticket, valida el recibo y permite reintentar o reconciliar con una consulta. El contador de abiertos excluye `RESUELTO` y el estado histórico `CERRADO`.

La respuesta pública del ticket expone fase, próxima revisión y visita prevista. No incluye asignados, evidencias de intervención, identificadores técnicos ni la descripción interna generada al derivar un fallo. Los comentarios enviados expresamente al ticket continúan visibles para la tienda.

Validación: 96 pruebas frontend, compilación de las dos entradas y 6 pruebas backend específicas correctas. Se repitió `tools/saas-manager-simulation.mjs` contra el backend y las interfaces reales, con la impresora considerada operativa. El gestor creó una consulta desde su portal; un comentario cuya respuesta se perdió se reintentó sin duplicarse; vio la fase de espera y revisión; el cierre exigió comprobaciones y el contador volvió de 2 a 1. El usuario temporal quedó desactivado. Caso de evidencia: `SIMULACIÓN GESTOR 9903bd77`, ticket `abbc53fd-8d24-4328-a3b8-feb1e76cdb1a` en la empresa DEMO. No se ejecutó ninguna conexión o reparación sobre una impresora real.
