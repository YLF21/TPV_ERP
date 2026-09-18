# Resumen de APP GESTIÓN

Rama de trabajo: `codex/gestion-control-alerts`. Continuación del bloque de Alertas de control, conservando sus cambios y los archivos ajenos al alcance. Sin commit, publicación ni migración de la base de datos de tienda.

**Estado del bloque: implementación integrada y validación local final cerrada.** Interfaz, preferencias por usuario y endpoint agregado conectados. Este documento conserva la evidencia original del 16/09 y la primera integración del 17/09 como históricas; el estado integrado vigente se recoge en [gestion-integracion-2026-09-17.md](gestion-integracion-2026-09-17.md). El lote final también verifica la selección histórica de reglas de Alertas. No se ha desplegado ni comprobado la instalación de tienda.

## Diseño y comportamiento

Referencia visual: `exec-6856386e-a475-4a7b-9090-7bc718b011b6.png`, propuesta de personalización aprobada. Se mantiene el shell real del ERP, su navegación y sus tipografías.

- Una única cuadrícula aplica orden y dimensiones tanto durante la personalización como en la vista normal. La vista anterior reducía la configuración a un conjunto de bloques visibles y perdía esa geometría.
- Distribuciones iniciales equilibrada, ventas y productos. Bloques activables, orden, anchura y altura; evolución en líneas, barras o tabla; ranking con barras o tabla; densidad y comparación opcionales.
- Guardar y cancelar de forma explícita. Los errores de guardado conservan el borrador. Las configuraciones antiguas mantienen su distribución, sin añadir automáticamente bloques nuevos.
- Periodos de hoy, siete días, treinta días, mes actual y fechas personalizadas. Fechas de negocio de la tienda; comparación con el periodo inmediatamente anterior de igual duración. Rango máximo de 366 días.
- Al actualizar o elegir un periodo relativo se obtiene el día actual en la zona horaria de tienda, aunque la pantalla siga abierta después de medianoche. Los rangos personalizados se conservan. La comparación de un solo día muestra ambos puntos, incluso cuando sus valores coinciden.
- Traducciones mediante el catálogo existente en español, inglés y chino. Tabla accesible como alternativa a la gráfica. Indicadores y promociones conservan sus ámbitos propios, identificados en pantalla.

## Preferencias y compatibilidad

Se amplía `preferencia_dashboard` mediante `V246__dashboard_display_options.sql`, sin reescribir V74 ni cambiar las filas de distribución existentes. `options` contiene periodo inicial, representación del gráfico y ranking, densidad y visibilidad de la comparación, con validación en Java y PostgreSQL.

El contrato `GET/PUT /api/v1/gestion/dashboard/preference` conserva `widgets` y admite `options`. Un cliente antiguo que envía solo `widgets` conserva las opciones guardadas. La respuesta añade la fecha de negocio y zona horaria de la tienda. El usuario de destino se obtiene de la autenticación; no se acepta un identificador de usuario externo. Se mantienen las preferencias de bloques ocultados por pérdida de permisos.

## Datos de ventas

`GET /api/v1/gestion/dashboard/data/sales-overview?from=AAAA-MM-DD&to=AAAA-MM-DD&warehouseId=UUID` requiere acceso a Gestión y permiso de ventas, o ADMIN. El almacén es opcional y se valida contra la tienda autenticada. La API no acepta empresa o tienda de destino desde el cliente.

Las consultas utilizan agregación en servidor, sin descargar documentos históricos para calcular las gráficas. Siguen el total lógico de actividad de ventas: tickets, facturas de venta y rectificativas válidas; excluyen borradores y anulados, y evitan duplicar facturas derivadas de tickets. Las facturas derivadas de albaranes sí representan la venta facturada.

El criterio seguido al continuar es contar cada documento lógico válido como una operación, incluidas devoluciones, rectificativas físicas o económicas e importes positivos, negativos o cero. La media es el total neto con impuestos dividido entre todas esas operaciones, con precisión decimal y redondeo HALF_UP a dos decimales. Si no hay operaciones la API devuelve cero y la interfaz muestra «—», sin comparación porcentual de la media.

El DTO contiene los límites de ambos periodos, zona horaria, moneda, métricas `current`/`previous`, series `daily`/`previousDaily` y `topProducts`. Ambas series se ordenan por fecha y completan con ceros todos los días sin actividad. Las dos consultas agregadas se realizan en una transacción de lectura REPEATABLE_READ; el periodo anterior tiene exactamente la misma duración. No se añaden dependencias ni índices sin una necesidad nueva demostrada.

El ranking muestra unidades netas de producto. No atribuye importes a cada producto cuando los descuentos y ajustes históricos de documento no tienen un reparto fiable guardado. No modifica documentos, cálculos de cobro ni datos fiscales históricos.

Las rectificativas económicas identificadas por `factura_rectificacion_venta.afecta_stock=false` afectan al importe, pero sus cantidades técnicas no alteran el ranking. El histórico sin esos metadatos mantiene las cantidades documentadas. Los importes se expresan en EUR conforme a la restricción existente de los documentos, aunque la configuración de tienda contenga otra moneda.

## Verificación original y primera integración · evidencia histórica

Los conteos y versiones siguientes pertenecen a sus lotes originales; no se sustituyen por los totales ni versiones de la continuación actual.

- Backend: 52 pruebas distintas aprobadas (27 de preferencias y 25 de ventas: 7 del servicio, 10 MVC, 5 de consultas PostgreSQL y 3 de integración servlet/servicio/PostgreSQL). El último lote de ventas terminó sin fallos ni pruebas omitidas. La prueba de migración siembra una preferencia real en V245 y aplica V246, conservando orden, dimensiones, versión y fechas. Se comprueba aislamiento por usuario, empresa, tienda y almacén; rechazos JSON; duplicados ticket/factura; rectificativas económicas; cantidades fraccionarias; ranking limitado a diez y periodos extremos. La integración verifica el JSON final, la ausencia de cambios en los documentos y un almacén real de otra tienda. Se repitieron únicamente los lotes afectados por correcciones.
- Frontend: 44 pruebas distintas aprobadas en `dashboardModel`, `GestionDashboard`, `GestionDashboardCustomization`, `GestionDashboardWidgets` y `ControlAlertsDashboardWidget`; las últimas pasadas ejecutaron los 22 casos afectados por los ajustes finales. Incluyen medianoche en la zona de tienda antes de cambiar el día UTC, cambio de mes, conservación de fechas personalizadas, marcador de comparación diaria y explicaciones ES/EN/ZH. TypeScript y Vite de APP GESTIÓN compilados correctamente después del último cambio.
- Navegador: guardar/cancelar, fallo de guardado y reintento, recuperación al recargar, otro usuario independiente, periodos y almacenes, fechas inválidas, error de lectura conservando datos, navegación a alertas, permisos restringidos y ES/EN/ZH. Ejes adaptados al espacio disponible y cifras decimales correctas en escalas pequeñas.
- La revisión visual se ejecuta en `frontend/e2e/dashboard-review` con respuestas ficticias interceptadas y sin conexión al backend de tienda. Comparación con referencia al mismo tamaño de 1487 × 1058; revisión adicional a 1280 × 900. Evidencia detallada en `design-qa.md`.
- Primera integración del 17/09: cuatro E2E aprobados contra la aplicación completa; incluyen autenticación, filtros de periodo/almacén, guardado explícito y recuperación de la personalización tras iniciar sesión, y cronología de alertas. Lectura HTTP adicional: 1363,70 EUR, 115 operaciones, media 11,86, 17 días y diez productos para aquel periodo de prueba. Estos valores proceden de documentos de prueba persistidos en PostgreSQL.
- En aquella primera integración, el manifiesto de desarrollo se alineó con V246, identidad `tpv-erp-dev-v246` y secuencia 12; 48 pruebas de manifiesto/guard aprobadas y arranque real correcto, sin debilitar controles. Esa versión no es la vigente en la continuación actual.
- No se ha migrado ni reiniciado el backend de tienda. La revisión integrada utiliza datos y almacenamiento temporales; no equivale a validar una sesión de producción.

## Continuación del 17/09 · estado actual

La instalación aislada actual utiliza PostgreSQL temporal en **15434**, migraciones hasta **V248** y manifiesto DEV `tpv-erp-dev-v248`, secuencia **14**. El puerto 55432 y V246 identifican la primera integración histórica. Los lotes posteriores y su estado se recogen en [gestion-integracion-2026-09-17.md](gestion-integracion-2026-09-17.md).

Revisión de Resumen a **1280 × 720**: área de trabajo de **1021 px** con `scrollWidth=1021`, filtros de **973 px** con `scrollWidth=973`, y selector de almacén de **190 px**. No hay desbordamiento horizontal en esas medidas; el selector ya puede reducir su ancho mínimo heredado. La evidencia visual está en [design-qa.md](../design-qa.md).

Contra el backend final se aprobaron **4/4 E2E de Gestión**, incluidos filtros y persistencia de personalización (`.codex-tmp/gestion-fullstack-20260917/gestion-e2e-final.log`). El cierre conjunto añade **172/172 pruebas backend**, incluidas reglas históricas, **12/12 E2E de caja** y **10/10 repeticiones** del caso de ocho POST simultáneos. Son resultados de la continuación final, separados de los conteos históricos anteriores; su detalle está en el informe de integración.

## Archivos principales

- `backend/src/main/java/com/tpverp/backend/ui/GestionSalesOverviewController.java`, `GestionSalesOverviewService.java` y `GestionSalesOverviewRepository.java`: endpoint, reglas agregadas y consultas acotadas.
- `backend/src/main/java/com/tpverp/backend/ui/DashboardOptions.java`, `DashboardPreference.java` y `DashboardPreferenceService.java`: opciones y persistencia autenticada, con compatibilidad de clientes anteriores.
- `backend/src/main/resources/db/migration/V246__dashboard_display_options.sql`: opciones JSONB con validación.
- `frontend/apps/app-gestion/src/GestionDashboard.tsx`, `GestionDashboardWidgets.tsx`, `dashboardModel.ts` y `gestion-dashboard.css`: distribución personalizable, gráficas y consumo de la API.
- `frontend/packages/app-common/src/i18n/DashboardMessages.ts` y catálogos existentes: ES/EN/ZH.
- Pruebas de esas capas y `frontend/e2e/dashboard-review`: validación funcional y escenario visual reproducible.

## Publicación

La implementación de Resumen está integrada y la evidencia se recoge en `gestion-integracion-2026-09-17.md`, incluida la revisión previa a publicación autorizada el 17/09. Commit, push y creación de PR están autorizados; el despliegue requiere una fase posterior. Al desplegar el conjunto de esta rama deberán aplicarse las migraciones nuevas **hasta V248** mediante el arranque habitual —V246 corresponde específicamente a las opciones del dashboard— y comprobarse la pantalla con la sesión y datos de la instalación de destino.

## Límites existentes

Los guardados simultáneos mantienen las restricciones de unicidad y versión existentes. La continuación del 17/09 traduce tanto la primera inserción concurrente como la actualización solapada a un conflicto 409 localizado; 28 pruebas de servicio/MVC/PostgreSQL lo verifican. La interfaz conserva el borrador para permitir reintento. El contrato compatible no exige versión del cliente: una escritura que empieza después de finalizar otra conserva la semántica anterior de última escritura.

Los informes diarios anteriores tienen diferencias de criterio respecto a la actividad de ventas; sus consultas no se cambian como parte de este bloque. El endpoint antiguo de productos diarios se conserva por compatibilidad.
