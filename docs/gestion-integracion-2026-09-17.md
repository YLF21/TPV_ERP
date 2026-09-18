# Integración local de Resumen y Alertas

Rama `codex/gestion-control-alerts`. Se completa el recorrido APP VENTA → HTTP → Spring Boot → PostgreSQL, recuperación de comunicación, concurrencia y carga. Toda la validación usa una instalación temporal con datos y archivos aislados. Sin commit, push, despliegue ni modificaciones de la base de tienda.

## Comportamiento

- Resumen configurable por usuario: ventas netas, operaciones, importe medio, gráfica con comparación y diez productos por unidades netas. Los conflictos de escritura simultánea devuelven 409 localizado y conservan el borrador.
- Alertas con indicadores, cronología paginada, filtros, evidencia, acciones, historial y preferencias por usuario. Textos nuevos ES/EN/ZH.
- El umbral de precio considera únicamente bajadas estrictamente superiores al porcentaje. Una subida genera solo la regla general de cambio de precio. Los descuentos conservan su evidencia propia.
- Cada eliminación se guarda antes de quitar sus líneas. Si falla la escritura local, permanecen en el carrito. Si falla la red, el evento se conserva y se reenvía con el mismo UUID, usuario, tienda, terminal, contenido y fecha original.
- Electron utiliza archivos en userData separados por backend/contexto; navegador, IndexedDB. No se guardan tokens. El servidor compara el contexto con la identidad autenticada.
- Errores transitorios: reintento con espera creciente. Los 4xx conservan el evento y muestran sesión/rechazo. El GET inicial debe proporcionar un contexto fiable: si falla, la eliminación conserva el carrito y ofrece reintento.
- Fechas de eliminación y recepción independientes, visibles en la evidencia por línea. Cantidades negativas/fraccionarias y códigos vacíos se conservan.

Las eliminaciones recuperadas utilizan la versión de regla vigente al borrarlas, según la decisión confirmada. Se consulta el historial inmutable de reglas, incluidos sus periodos desactivados. Las entregas desordenadas reconstruyen únicamente la parte de la secuencia que ya había ocurrido en cada fecha; una eliminación posterior no cuenta para un umbral anterior. La fecha de creación de la alerta registra cuándo pudo evaluarse en el servidor. Once pruebas históricas en PostgreSQL verifican estos casos.

## Defectos corregidos

1. Manifiesto DEV desfasado respecto a Flyway: actualizado a V248, identidad `tpv-erp-dev-v248`, secuencia 14. Se mantienen el guard fiscal y el perfil de publicación de producción.
2. JDBC rechazaba parámetros Instant antes de llegar al detector. Se utilizan Timestamp con precisión canónica de microsegundos.
3. El registro rechazaba devoluciones, códigos vacíos y truncaba cantidades fraccionarias. V247 admite signo; V248 conserva numeric(19,3) y añade recepción. Validación exacta de precisión y pertenencia de productos a la tienda mediante una consulta.
4. La cadena de promesas de APP VENTA perdía eventos al cerrar/desconectar. La cola duradera diferencia guardar de confirmar entrega; fsync y rename en Electron.
5. El estado VeriFactu intentaba crear configuración sin transacción. Ahora realiza una lectura transaccional sin escrituras y usa valores efímeros cuando falta configuración. La UI distingue indisponibilidad. Activación, permisos y reglas fiscales intactos.
6. La licencia DEV usaba la fecha de documentos a las 10:00 UTC y podía no ser válida al arrancar temprano. Sus fechas operativas usan el reloj real, independientemente de la fecha histórica del fixture.
7. La consulta de alertas no propagaba la tienda al evento unido. El predicado explícito permite usar el índice existente de tienda/fecha sin cambiar resultados ni añadir índices.
8. El filtro de almacén del Resumen heredaba un ancho mínimo que desbordaba su contenedor; se permite reducirlo.
9. La prueba concurrente detectó una carrera entre la clave primaria y la unicidad compuesta del encabezado de eliminación. La inserción ahora contempla ambas restricciones; la secuencia se serializa por tienda, usuario, terminal y operación de venta. Los reintentos devuelven la evidencia original y un mismo UUID con contenido alterado sigue siendo rechazado. PostgreSQL confirma una sola eliminación, alerta e historial con ocho llamadas simultáneas, en tres rondas, y secuencias correctas con operaciones diferentes concurrentes.

## Evidencia

- Lote inicial: 76 casos de estado fiscal, eliminaciones, manifiesto y preferencias. Un test registraba también una interacción de arranque de Spring; se acotó el fixture a la operación y la repetición pasó sin debilitar las aserciones de lectura.
- Lote adicional: 18/18, estado fiscal PostgreSQL 2, fixture DEV 6, lecturas PostgreSQL 7 y servicio de alertas 3.
- V248: 71/71, detector 17, contexto MVC 8, entrega PostgreSQL 14, servicio 4, manifiesto 4, guard 24. Incluye manipulación de contexto, decimal firmado, diferencias de payload, fechas, reintento y retención.
- Lote final tras seleccionar reglas históricas: **172/172**, sin fallos ni omisiones: detector 17, servicio de alertas 3, documentos 115, contexto MVC 8, histórico PostgreSQL 11, entrega PostgreSQL 14 y servicio de eliminaciones 4. Se corrigió un fixture que aún instanciaba el constructor anterior; todas las fuentes de pruebas compilan. Log `backend-deletion-historical-rules-tests-rerun.log`.
- Personalización: 28 pruebas de servicio/MVC/PostgreSQL, con carreras reales de primeras inserciones y actualizaciones y 409 en ES/EN/ZH.
- Entrega frontend: 300 escenarios cubiertos en lotes completos/focalizados: UI 235, emisor 21, almacén 9, preload 5, seguridad Electron 16 y aplicación/utilidades 14. TypeScript aprobado.
- Detalle de alertas 36/36, incluidas ambas fechas traducidas; estado operativo 9/9.
- Compilación TypeScript/Vite de APP VENTA y APP GESTIÓN aprobada.
- E2E reales finales: **12/12 en un único lote**, después de reiniciar con el backend definitivo. Cuatro variantes de precio, descuento manual, vaciado, desconexión con cierre de pestaña y recuperación, pérdida de respuesta después del commit, fallo de IndexedDB conservando carrito, eliminaciones consecutivas, ocho reintentos simultáneos y cambio de umbral 2→5 durante la desconexión conservando la regla original. Los fallos de transporte/almacenamiento se provocan expresamente; el resto utiliza backend y PostgreSQL reales. Log `sale-e2e-final.log`.
- Tres procesos reales Electron 43.4.1 sin ventanas: guardar, recuperar exactamente y retirar tras ACK, verificar vacío en tercer inicio. Comprueba aislamiento de contexto/backend. Directorios temporales verificados y eliminados. Verifica Electron y disco, no un reinicio interactivo completo de APP VENTA.
- Estabilidad del caso concurrente: **10/10 repeticiones**, ocho POST simultáneos por repetición, sin conflictos ni alertas duplicadas. Log `sale-e2e-concurrency-final.log`.
- Cuatro E2E de Gestión repetidos contra el backend definitivo: **4/4**, autenticación, filtros, personalización entre sesiones y cronología. Log `gestion-e2e-final.log`. Revisión/comentario permanecen tras recarga y nueva sesión, comprobados en la primera integración.
- Revisión CUA final a 1280 × 720 contra la instalación real: Resumen sin desbordamiento del filtro de almacén; cronología paginada, selección y detalle de una alerta real del lote. Se leen las fechas de eliminación/recepción y la versión de regla. La evidencia extensa permanece accesible mediante desplazamiento del panel. Capturas emitidas en la tarea, sin archivo exportado.

Logs: `.codex-tmp/gestion-fullstack-20260917/`. Informe Playwright: `output/playwright/report/`. Ambos ignorados por Git. El E2E exige `E2E_CONTROL_ALERTS_DISPOSABLE=true` y comprueba modo DEVELOPMENT; no debe ejecutarse contra tienda.

```powershell
# Desde frontend, usando las URLs E2E_* de la instalación temporal.
npm run test:e2e -- e2e/control-alerts-sale-flows.spec.ts
node scripts/check-sale-control-electron-restart.mjs
```

## Archivos principales

- Resumen: `frontend/apps/app-gestion/src/GestionDashboard.tsx`, `GestionDashboardWidgets.tsx`, `dashboardModel.ts`, `gestion-dashboard.css`; backend `ui/GestionSalesOverview*`, `DashboardOptions` y `DashboardPreferenceService`.
- Alertas: `frontend/apps/app-gestion/src/ControlAlertsScreen.tsx`, `controlAlertsApi.ts`, `gestion.css`; backend `control/ControlAlert*`, `ControlEvent`, `ControlRuleVersionRepository` y preferencias de vista.
- Entrega duradera: `frontend/packages/app-common/src/sale/saleControlDelivery.ts`, `saleControlOutboxStorage.ts`, `useSaleControlDelivery.ts`, `components/SaleScreen.tsx`, arranque de APP VENTA y `frontend/desktop/sale-control-outbox.cjs`, `main.cjs`, `preload.cjs`; backend `document/SaleLineDeletion*` y `SaleControlContextController`.
- Evidencia original de cobro: `document/DocumentService`, `PosCashService`, `ApprovedCardTicketSnapshot` y `PosCardDocumentSnapshot`. Estado operativo: `VerifactuAdminService`, `OperationalStatusCard`; fixture: `DevSampleDataSeeder`.
- Persistencia: migraciones nuevas `V245__control_alert_view_preference.sql`, `V246__dashboard_display_options.sql`, `V247__sale_line_deletion_signed_quantity.sql`, `V248__sale_line_deletion_delivery_evidence.sql`; manifiesto DEV y sus comprobaciones. Catálogos ES/EN/ZH afectados.
- Regresiones de las capas anteriores, `frontend/e2e/control-alerts-sale-flows.spec.ts`, `app-gestion-core-flows.spec.ts`, harness Electron en `frontend/scripts/check-sale-control-electron-restart.mjs`, escenarios visuales y medición SQL en `tools/performance/`.

## Volumen y límites

500.000 documentos, 1.500.000 líneas y 500.000 alertas sintéticas en esquema aislado de PostgreSQL 18.3. Resumen, dos consultas: 7 días ≈164 ms, 30 días ≈383 ms, año ≈1,52 s. Primera página anual de alertas: 384→0,25 ms en SQL, con resultados y orden equivalentes. Son medidas locales, no garantías de latencia HTTP. Ranking anual y páginas profundas OFFSET siguen siendo más costosos. Detalle reproducible en `gestion-sql-performance-2026-09-17.md`.

La API de preferencias detecta escrituras solapadas. Un cliente que envía después de finalizar otro guardado conserva la semántica previa de última escritura: el contrato no exige versión del cliente.

No se han probado periféricos, impresión física, envío a AEAT ni producción. Publicar una distribución y comprobarla en la instalación de destino requiere su flujo autorizado. La instalación habitual permanece intacta.

## Entrega para revisión

Implementación y validación local completadas, sin pruebas necesarias pendientes dentro del alcance acordado. La aplicación real de revisión utiliza `http://127.0.0.1:5184/` (Gestión), `http://127.0.0.1:5185/` (Venta), API en 18084 y PostgreSQL temporal en 15434. Estos procesos y sus datos son temporales; las direcciones funcionan mientras continúen en ejecución. Los escenarios visuales iniciales de 5182/5183 ya no son la revisión integrada.

La publicación de la rama y creación de PR se autorizaron posteriormente el 17/09, condicionadas a su revisión final. El despliegue permanece fuera de esta fase. El cambio incluye migraciones V245–V248 y snapshots de cobro v5: antes de volver a un binario anterior deben finalizarse o reconciliarse sesiones v5, según `gestion-control-alerts-2026-09-16.md`.

## Revisión posterior del 17/09 · comentarios y detalle en ventana

Decisiones confirmadas por el usuario: mantener dos alertas cuando una venta activa dos reglas, con revisión independiente; omitir la personalización de iconos y mostrar los iconos existentes también al configurar las reglas. El detector y los cálculos de la venta no cambian en esta revisión.

- La cronología ocupa todo el ancho. Un clic selecciona; doble clic o Enter abre el detalle en una ventana modal. Escape y el botón de cierre devuelven el foco a la fila sin perder el desplazamiento. El documento relacionado se abre por encima; Escape cierra únicamente la ventana superior.
- «Comentario de revisión» es la última columna por defecto. Muestra el último comentario no vacío de las transiciones, con orden estable por fecha e identificador; los motivos de asignación no sustituyen el comentario. Conserva las preferencias existentes de tabla. La antigua preferencia `showDetail` se mantiene compatible en la API, pero ya no controla un panel lateral ni aparece como opción de vista.
- Terminal, responsable, autores del historial y cliente tienen etiquetas legibles; las ausencias usan textos traducidos en vez de UUID. Los nombres y códigos de las evidencias de productos proceden de las líneas históricas del documento. El nombre del cliente procede del registro actual de la misma empresa, porque el documento no contiene una copia histórica de ese nombre.
- La API añade campos opcionales sin eliminar identificadores ni alterar los eventos persistidos. La consulta de comentarios/nombres trabaja por lote de alertas y respeta tienda/empresa; no carga historiales completos por fila. No hay migración nueva.
- «Gestión operativa» incluye una explicación de responsable, prioridad y vencimiento. No modifica la venta ni la regla aplicada. Los iconos del sistema aparecen en las tarjetas de reglas y en su editor, sin selector ni preferencias nuevas. Textos ES/EN/ZH.

Validación de esta revisión:

- 43/43 pruebas de pantalla y 8/8 de cliente API: apertura, cierre, foco, diálogo anidado, columna añadida a preferencias antiguas, permisos y etiquetas sin UUID en ES/EN/ZH.
- 15/15 backend (12 PostgreSQL + 3 servicio): comentario en la misma respuesta de revisión, blancos/empates, nombres, aislamiento, evidencia inmutable y página de 30 alertas sin cargar todos los historiales. Log `backend-alert-human-labels-tests.log`.
- E2E real: precio inicial 8,20 €, precio final deseado 4,00 € mediante RePág, descuento registrado 51,22 %, exactamente dos alertas y ninguna de cambio manual de precio. Revisar una conserva la otra como Nueva; el comentario aparece en el listado y persiste tras iniciar otra sesión. También comprueba detalle, nombres de producto/terminal, documento relacionado e importe 4,00 €. **1/1 aprobado**, log `alerts-review-sale-e2e.log`.
- Cuatro recorridos de Gestión contra el backend definitivo: **4/4 aprobados**, log `alerts-review-gestion-e2e.log`.
- TypeScript/Vite de Gestión y control de tamaño de paquetes aprobados. APP VENTA conserva su aviso anterior de CSS superior al 90 % del presupuesto, sin superar el límite.
- Revisión CUA real a 1280 × 720: listado con comentario, detalle de la venta probada sin UUID de producto/terminal y retorno de foco, iconos visibles en Configurar reglas y Modificar regla. Capturas emitidas en la conversación, sin archivo exportado. Revisión independiente del código sin hallazgos pendientes.

Durante la preparación del nuevo E2E se corrigieron dos condiciones del propio test: usar el atajo de precio final para reproducir 8,20 → 4,00 y esperar a que terminase el guardado antes de enviar Escape. No se cambió la lógica comercial para hacer pasar la prueba.

## Reapertura y ancho de la última columna · 17/09

La petición posterior autoriza reabrir revisiones hechas por error. «Reabrir alerta» devuelve REVIEWED, CLOSED o DISMISSED a NEW y añade una transición al historial con usuario, fecha y comentario opcional. No elimina decisiones anteriores ni modifica evidencia, responsable, prioridad o vencimiento. Una alerta que ya está NEW no admite otra reapertura. La acción usa los mismos permisos de gestión y el mismo control de versión que las demás transiciones.

API aditiva: `POST /api/v1/control/alerts/{id}/reopen`, cuerpo `{version, comment?}` y respuesta `AlertDetailView`. Sin migraciones. La UI muestra «Reabierta» en el historial y «Nueva» como estado actual, con traducción ES/EN/ZH; conserva los comentarios si falla una petición y bloquea envíos repetidos mientras guarda.

La última columna visible ocupa el espacio sobrante de la tabla, conservando el orden, la visibilidad y el ancho mínimo guardados por usuario. Se mantiene la columna «Comentario de revisión» y desaparece el área vacía que parecía otra columna. Se ajustó también el enfoque inicial del modal para que un frame pendiente no robe el foco si ya se abrió un documento relacionado.

Pruebas focalizadas: 62/62 frontend (53 pantalla y 9 API) y 47 casos backend (8 dominio, 3 servicio, 18 MVC, 1 contrato y 17 PostgreSQL). Dos fixtures PostgreSQL intentaban guardar una asignación sin cambios; se corrigieron únicamente esos fixtures y ambos casos pasaron en su repetición focalizada. La concurrencia real confirma un cambio y una entrada de historial frente a dos intentos simultáneos. Logs `alerts-reopen-frontend-tests.log`, `backend-alert-reopen-tests.log` y `backend-alert-reopen-pg-rerun.log`. Compilación TypeScript/Vite y presupuesto de paquetes aprobados.

E2E integrado aprobado contra API y PostgreSQL reales: revisión, reapertura desde los tres estados, historial anterior conservado, comentario y estado NEW recuperados tras una nueva sesión. A 1920 × 1080 comprueba cinco columnas sin espacio final y la misma anchura en cabecera/fila con un comentario de más de 400 caracteres. Detectó inicialmente que `min-width: max-content` permitía ensanchar la fila; se sustituyó por un mínimo común calculado a partir de las columnas visibles. La repetición completa pasó en 15,4 s, log `alerts-reopen-e2e.log`.

Revisión CUA final a 1280 × 720: tabla con la columna de comentarios al final y ventana de detalle con «Reabrir alerta» junto a revisión/cierre/descarte e historial. Sin override de viewport ni cambios a datos de tienda. Las capturas se emitieron en la conversación. Sin pendientes de implementación o validación local en esta corrección; publicación sigue fuera del alcance autorizado.

## Responsables con acceso efectivo a alertas · 17/09

El selector anterior admitía usuarios activos de la empresa con acceso a la tienda, aunque no pudieran entrar en APP GESTIÓN. Se corrige según la petición del usuario: deben cumplir además el mismo criterio de acceso de la API de alertas, rol ADMIN o APP_GESTION_ACCESS junto con CONTROL_ALERTS_READ o CONTROL_ALERTS_MANAGE. Se comprueban permisos reales; el nombre VENDEDOR no es una exclusión por sí mismo.

GET `/control/alerts/assignees` y PUT `/control/alerts/{id}/work` comparten `ControlAlertReadRepository.eligibleAssignees`. La consulta respeta empresa, tienda propia o acceso explícito a tienda y usuario activo; evita consultas por cada candidato. El guardado vuelve a verificar la elegibilidad, por lo que una lista antigua no permite asignar a alguien que ya perdió acceso. El rechazo no cambia la alerta ni añade historial. No se modifican permisos, asignaciones previas ni esquema.

La UI recarga candidatos al actualizar. Un responsable histórico que ya no cumple los requisitos conserva su nombre como opción deshabilitada y muestra una explicación ES/EN/ZH. Se puede elegir otro responsable válido o «Sin asignar»; no se permite guardar otros cambios operativos conservando ese responsable no elegible. La revisión y el historial de la alerta permanecen disponibles.

Archivos de esta corrección: `ControlAlertService.java`, `ControlAlertReadRepository.java`, catálogos de errores backend ES/EN/ZH, `ControlAlertsScreen.tsx`, `ControlMessages.ts` y sus pruebas. Validación:

- 58/58 pruebas de pantalla, incluidas las tres traducciones del responsable no disponible y la actualización de candidatos. Log `alerts-assignees-ui-tests.log`.
- 26/26 backend: 23 PostgreSQL y 3 servicio. Matriz de permisos, aislamiento entre tiendas y empresas, usuarios inactivos, revocación de acceso después de cargar opciones, rechazo sin escritura, nombres históricos y desasignación. La lista realiza una sola consulta SQL. Log `backend-alert-assignee-eligibility-tests.log`.
- 1/1 E2E contra la API y PostgreSQL temporales: VENDEDOR sin acceso queda excluido; un PUT forzado devuelve 400 traducido y deja el detalle intacto; asignar ADMIN, cerrar/abrir detalle y desasignar conservan dos entradas nuevas de historial. Log `alerts-assignees-e2e.log` (9,7 s de escenario).
- Compilación TypeScript/Vite de Gestión y presupuesto de paquetes aprobados. Logs `alerts-assignees-build.log` y `alerts-assignees-bundle.log`. Se mantiene el aviso anterior del presupuesto CSS de APP VENTA, sin superar su límite.
- Revisión CUA a 1280 × 720 en Gestión 5184: detalle con las opciones «Sin asignar» y «ADMIN (ADMIN)», sin VENDEDOR. API aislada 18084 reiniciada con las fuentes verificadas; instalación de tienda intacta.

Sin pendientes de implementación o validación local en esta corrección. La revisión previa a commit, push y PR se documenta a continuación; el despliegue no está incluido.

## Revisión previa a commit, push y PR · 17/09

Base comprobada tras actualizar el remoto: `backend/main`, commit `a727e27e`, coincidente con el inicio de `codex/gestion-control-alerts`. Revisión independiente de backend, Resumen/Alertas y entrega de APP VENTA. Los cambios ajenos de SaaS, reparaciones, otros informes y capturas temporales quedan fuera del commit.

Se corrigieron dos incidencias encontradas durante esta revisión:

- La carga fallida de responsables se ocultaba. Ahora se muestra un error ES/EN/ZH y un reintento específico, se conserva el borrador y se bloquea la asignación hasta obtener una lista válida. Un resultado vacío se distingue de un fallo; revisar, cerrar o reabrir la alerta siguen disponibles. 62/62 pruebas de pantalla, incluidos los nuevos casos de recuperación y la espera correcta del estado del formulario histórico.
- El emisor de eliminaciones podía arrancar antes de aprobar compatibilidad. APP VENTA vincula la aprobación al token de la sesión actual: no lee ni envía la cola mientras comprueba la compatibilidad o esta se rechaza. Las pruebas con hook y emisor reales cubren aprobación, incompatibilidad, 503, desconexión, cambio de sesión y ACK tardío; 41/41 casos en `main.test.tsx` y `saleControlDelivery.test.ts`.

Backend sin nuevos hallazgos confirmados en esta revisión; se releen los lotes de 172 y 26 casos aprobados sin sumar resultados que se solapan. Las compilaciones TypeScript/Vite de Gestión y Venta, la sintaxis de Electron y sus 14 pruebas de cola/preload pasan. El presupuesto de paquetes pasa con el aviso previo de CSS de APP VENTA por encima del 90 %, aún dentro del límite. Dos E2E finales con API/PostgreSQL temporales pasan: asignación válida/rechazo de VENDEDOR y recuperación única tras desconexión y cierre de pestaña (16,9 s).

La primera suite completa de frontend detectó dos esperas prematuras en los nuevos tests de responsable histórico, corregidas, y dos fallos de esperas en pruebas de cobros no modificadas. Estas últimas pasaron en su repetición focalizada (17/17), sin cambios de código ni ampliación de sus timeouts. La regresión final `npm test -- --maxWorkers=4` aprobó **237 archivos y 2.540/2.540 pruebas** en 149,65 s, sin fallos ni omitidas. Se limitó la concurrencia para reducir la contención local; no se cambiaron los timeouts ni las aserciones de las pruebas ajenas.

La revisión final no deja defectos confirmados pendientes dentro del alcance. Se autoriza la publicación técnica de esta rama como PR con los límites de producción y recuperación ya documentados.

Logs de esta revisión: `prepublish-frontend-final.log`, `prepublish-receivables-rerun.log`, `prepublish-electron-tests.log`, `prepublish-gestion-build.log`, `prepublish-venta-build.log`, `prepublish-bundle.log` y `prepublish-e2e-final.log`, todos en `.codex-tmp/gestion-fullstack-20260917/`. Los logs y datos de prueba no se publican. Crear la PR no despliega ni fusiona el cambio; la CI de GitHub debe evaluarse sobre el commit publicado.

## Corrección de CI de la PR #169 · 17/09

La primera ejecución de GitHub sobre `fc24de9f` aprobó diez checks y falló dos de Quality. Backend ejecutó 4.133 casos y detectó una aserción de infraestructura desactualizada: `DocumentSyncPublisherPostgreSqlTest` esperaba que la última migración fuera V244, aunque esta rama incorpora V245–V248. Se actualiza la versión esperada a V248, conservando la comparación exacta y todas las comprobaciones de publicación, pagos, relaciones, concurrencia y recuperación. La clase completa pasa **13/13 casos sin omitir ninguno** contra PostgreSQL temporal; log `pr169-backend-fix-test.log`.

Quality frontend falló al esperar la solicitud de segunda página en `SalesReportWarehousePurchases.test.tsx`. Tanto la prueba como `SalesReportScreen.tsx` son idénticos a la base `a727e27e`. El job JavaScript de CI aprobó la suite completa en el mismo commit y el focalizado local pasa **9/9**: es un fallo intermitente cuya causa exacta no se ha demostrado. No se modifican esa prueba, sus timeouts, las dependencias ni los workflows. La nueva ejecución de GitHub debe confirmar todos los checks tras publicar la corrección de backend.
