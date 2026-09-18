# Alertas de control · implementación

Rama: `codex/gestion-control-alerts`. Bloque de Alertas de control, integrado con el nuevo Resumen documentado en `gestion-resumen-2026-09-16.md`. La publicación de la rama y creación de PR se autorizaron el 17/09, condicionadas a la revisión final del informe de integración. El backend de tienda no se ha reiniciado ni desplegado.

Este documento conserva la evidencia original del 16/09 y la primera integración del 17/09 como históricas. El estado integrado actual, incluida la cola duradera, se recoge en [gestion-integracion-2026-09-17.md](gestion-integracion-2026-09-17.md). La selección histórica de reglas está aprobada, implementada y verificada; la validación local final está cerrada.

## Diagnóstico y cambios

- Backend: se perdía `documentDiscountPercent` y la intención manual original al congelar algunos cobros. Los snapshots v5 conservan descuentos manuales y precios original/aplicado, sin deducir acciones del operador a partir de promociones o fidelización.
- Backend: faltaba llamar al detector en la confirmación directa de ventas pendientes. La confirmación inmediata de albaranes/facturas también transmite ahora la evidencia original.
- Primera integración real del 17/09: `POST /api/v1/sale-line-deletions` fallaba antes de llamar al detector porque PostgreSQL no acepta inferir el tipo JDBC de `Instant`. Se corrigieron los parámetros temporales de inserción y limpieza mediante `Timestamp.from`, conservando instante y retención existentes. Fue una causa de alertas ausentes en la API, independiente del rediseño visual; la continuación amplía el contrato de entrega y sus fechas.
- Regla confirmada: `MANUAL_PRICE_CHANGE_OVER_PERCENT` solo detecta bajadas estrictamente superiores al umbral. `MANUAL_PRICE_CHANGED` detecta cualquier cambio manual real. Un descuento de línea no equivale a un cambio de precio.
- Las solicitudes de descuentos descartadas por el cálculo autoritativo no generan evidencia de descuento aplicado. Los canjes remapean posiciones y excluyen operaciones históricas/devoluciones ya identificadas.
- Lectura: cronología por fecha de operación con desempate estable por ID; orden por documento corregido; eventos cargados con la página; grupos y listado comparten filtros de estado, búsqueda, prioridad, responsable y vencimiento.
- Interfaz: cronología global agrupable por día, indicadores por tipo, detalle visual, acciones e historial, reglas en diálogo, errores con reintento y protección frente a respuestas desfasadas. Se mantienen filtros y paginación en servidor.
- Preferencias: indicadores, detalle lateral, agrupación, densidad, periodo inicial, refresco y orden temporal se guardan por el usuario autenticado. Las columnas emplean el mecanismo existente de preferencias de tablas, con clave propia para la nueva composición. No se borran las preferencias del listado anterior.
- Textos de APP GESTIÓN en español, inglés y chino. Las fechas se filtran según la zona horaria de la tienda.

## Archivos principales

Backend: `ControlAlertDetectionService`, `DocumentService`, `PosCashService`, `ApprovedCardTicketSnapshot`, `PosCardDocumentSnapshot`, `ControlAlertController`, `ControlAlertService`, `ControlAlertRepository`, nuevo `ControlAlertViewPreferenceController/Service` y getter de locale en `Store`.

Frontend: `ControlAlertsScreen.tsx`, `controlAlertsApi.ts`, `gestion.css`, `main.tsx`, `ControlMessages.ts`, pruebas asociadas y escenario visual `frontend/e2e/control-alerts-review`.

Migración de preferencias de este bloque original: `V245__control_alert_view_preference.sql`. Una fila JSON validada por usuario, FK a `usuario`, escritura mediante upsert atómico. Las rutas de preferencias exigen acceso a Gestión y permiso de lectura/gestión de alertas (o ADMIN); no aceptan un identificador de usuario de destino. La continuación del conjunto incorpora migraciones hasta V248.

## Validación backend original · evidencia histórica del 16/09

- `ControlAlertDetectionServiceTest`: 17 pruebas.
- `DocumentServiceTest`: 115 pruebas, incluida solicitud 20 % rechazada para producto protegido, con cálculo y detector reales.
- `PosCashServiceTest`: 32; `PosCardDocumentSnapshotTest`: 10; `CustomerPendingSaleServiceTest`: 40.
- `ControlAlertReadPostgreSqlTest`: 6 pruebas PostgreSQL reales, incluida paginación estable, fechas semiabiertas, filtros, ausencia de consultas por fila y aislamiento de preferencias entre usuarios.
- `ControlAlertViewPreferenceControllerTest`: 10; `ControlAlertViewPreferenceServiceTest`: 13; `ControlAlertServiceTest`: 3; `ControlControllerContractTest`: 1.

Resultado de aquellos lotes: 247 pruebas del backend aprobadas. En la primera ejecución PostgreSQL falló el nombre de usuario del fixture por no estar en mayúsculas; se corrigió el fixture y las seis pruebas pasaron sin modificar restricciones. Las 241 migraciones existentes hasta V245 se aplicaron correctamente en un esquema aislado de un PostgreSQL temporal. Aquella instancia temporal se detuvo al terminar. Estos conteos y esa versión se conservan como evidencia histórica; la BD de tienda no se migró.

Primera integración del 17/09, también histórica: seis pruebas de `SaleLineDeletionService` aprobadas (cuatro unitarias y dos PostgreSQL con detector/JPA reales). Verificaron fecha con microsegundos, precio de tres decimales, una única alerta/evento/historial tras reintento y limpieza que conserva el límite exacto de 365 días. La aplicación completa generó la alerta por HTTP, la mostró en la cronología y conservó revisión/comentario después de recargar e iniciar sesión. El estado posterior está en `gestion-integracion-2026-09-17.md`.

## Continuación del 17/09 · integración anterior a la revisión de ventanas

La instancia PostgreSQL temporal actual utiliza el puerto **15434**, migraciones hasta **V248** y manifiesto DEV `tpv-erp-dev-v248`, secuencia **14**. El puerto 55432 y V246 corresponden a la primera integración, no a esta instalación actual. La evidencia vigente se centraliza en [gestion-integracion-2026-09-17.md](gestion-integracion-2026-09-17.md).

El detalle muestra por separado la fecha de eliminación y la fecha de recepción del servidor, también por línea de evidencia. **36 pruebas aprobadas** verifican el detalle, incluidos esos textos en ES/EN/ZH. La selección de la regla vigente cuando ocurrió el evento está aprobada, implementada y verificada.

La revisión CUA final con sesión real en 5184 a **1280 × 720** mostró una cronología de 60 alertas y el detalle de eliminación con ambas fechas y versión de regla legibles al desplazar el panel. La captura se emitió en la conversación, sin archivo exportado.

El lote backend final aprobó **172/172 pruebas**: 11 de reglas históricas, 14 de entrega PostgreSQL, 8 de contexto MVC, 4 de servicio, 17 del detector, 3 del servicio de alertas y 115 de documentos. Evidencia: `.codex-tmp/gestion-fullstack-20260917/backend-deletion-historical-rules-tests-rerun.log`.

La integración final aprobó **12/12 E2E de caja** y **4/4 de Gestión**. Incluye desconexión con cierre de pestaña, recuperación, respuesta perdida sin duplicado, fallo de almacenamiento que conserva el carrito, eliminaciones consecutivas y aplicación del umbral original de 2 aunque cambie a 5 durante la desconexión. El caso de ocho POST simultáneos también pasó **10/10 repeticiones**. Logs en `.codex-tmp/gestion-fullstack-20260917/`: `sale-e2e-final.log`, `gestion-e2e-final.log` y `sale-e2e-concurrency-final.log`. Estos resultados finales no sustituyen los conteos de las fases históricas.

## Revisión de ventanas del 17/09 · estado actual

Actualización posterior: «Reabrir alerta» permite volver a NEW desde REVIEWED, CLOSED o DISMISSED conservando evidencia, comentarios e historial; requiere permisos de gestión y versión vigente. La última columna visible ocupa el ancho disponible sin un espacio que simule una columna adicional. Reapertura, persistencia y alineación con comentarios largos están verificadas en integración real; véase «Reapertura y ancho de la última columna» en el informe de integración.

La revisión posterior sustituye el panel lateral por un detalle modal mediante doble clic o Enter, añade el último comentario de revisión como columna final y muestra nombres legibles en lugar de UUID. Mantiene los identificadores en el contrato y la evidencia original. La configuración muestra los iconos del sistema, sin personalización de iconos, según la última decisión del usuario. Un descuento puede conservar dos alertas, revisables por separado, si activa dos reglas.

Validación actual de este bloque: 43 pruebas de pantalla, 8 de API y 15 backend aprobadas; E2E real 8,20 → 4,00 con dos alertas, comentario persistente y documento relacionado aprobado, además de los cuatro recorridos de Gestión. Compilación y revisión visual aprobadas. El detalle de consultas, compatibilidad y logs está en la sección «Revisión posterior del 17/09» del informe de integración. Las referencias anteriores al panel lateral son evidencia histórica de la primera composición.

## Compatibilidad y límites

- Los snapshots v5 leen versiones 1–4 y mantienen las validaciones obligatorias introducidas en v4. Un binario anterior no puede leer una sesión v5: antes de un rollback deben finalizarse o reconciliarse esas sesiones.
- No se recalculan importes históricos ni se inventan acciones pasadas sin evidencia. Los snapshots antiguos sin intención original y los borradores guardados que se confirman posteriormente sin reemplazo conservan solo el porcentaje manual que puede demostrarse. Recuperar un evento real pendiente mantiene su fecha original y la selección histórica aprobada; no equivale a recalcular documentos antiguos ni a deducir descuentos o cambios de precio que no quedaron registrados.
- La continuación del 17/09 incorpora cola duradera de eliminaciones: archivos en Electron e IndexedDB en navegador, guardado antes de borrar, recuperación por identidad y reintento idempotente. V247/V248 conservan devoluciones, cantidades fraccionarias y fechas de eliminación/recepción. Véanse `gestion-integracion-2026-09-17.md` y `analysis/sale-control-event-delivery-2026-09-17.md` para la evidencia posterior y sus límites.
- La revisión visual inicial utiliza respuestas ficticias interceptadas; la comprobación adicional del 17/09 conecta la aplicación completa a PostgreSQL temporal. Ninguna equivale a una prueba de cobro con terminal físico ni a una verificación del despliegue de tienda.

Frontend original del 16/09: 41 pruebas focalizadas aprobadas (`ControlAlertsScreen.test.tsx`: 33; `controlAlertsApi.test.ts`: 8). Incluyeron respuestas fuera de orden, conservación de filas al fallar una lectura, preferencias, permisos, umbrales decimales y cambios de horario de 23/25 horas. `npm run build:desktop:gestion` (TypeScript y Vite) finalizó correctamente al cerrar aquella fase y `git diff --check` de ese alcance no presentó errores. Los resultados posteriores no sustituyen estos conteos históricos y se documentan en el informe de integración.

La evidencia visual se recoge en `design-qa.md`. Se probaron filtros, transición con comentario e historial, apertura de documento, cambio de umbral decimal, guardado/recuperación de preferencias y error/reintento con filas conservadas. Se revisaron español, inglés, chino, estado vacío y permisos de solo lectura.
