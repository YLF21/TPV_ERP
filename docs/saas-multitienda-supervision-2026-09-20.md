# SaaS: tiendas, accesos y supervisión

Implementación del alcance aprobado el 20 de septiembre de 2026. **Este frontend es exclusivamente interno**: mantenimiento de clientes, facturación de nuestros servicios SaaS, generación/control de licencias y soporte. Los administradores de tiendas no acceden a este portal. La futura aplicación cliente consumirá las API tenant; no se crea esa aplicación en esta entrega.

## Apartados y responsabilidades

| Grupo | Apartado | Función |
| --- | --- | --- |
| Inicio | Resumen | Licencias, instalaciones, actividad y alertas. Los eventos de venta recibidos se distinguen de las ventas registradas directamente en SaaS. |
| Inicio | Informes | Agregados administrativos del SaaS; las cifras históricas de ventas e inventario proceden de registros centrales, no del historial completo de tiendas. |
| Clientes | Empresas | Tabla inicial con sociedad, contacto, teléfono, email y propietarios. Doble clic o Intro abre la ficha con guardado único de sociedad, contacto y propietarios, más instalaciones. «Alta nueva empresa» abre el formulario en una ventana; no crea tiendas, licencias ni usuarios. |
| Clientes | Tiendas | Buscar por código interno, empresa o nombre; datos de tienda, domicilio, IVA/IGIC, perfil mayorista/minorista, zona horaria, precio mensual/anual, cupos Windows/PDA, caducidad y actividad. |
| Clientes | Licencias activas | Tabla a toda altura con scroll interno y carga incremental de 25 filas, caducidad, días, últimas comunicaciones, instalaciones, cupos y situación de facturación de la empresa. El filtro de estado permite consultar el historial. Columnas movibles, ajustables y ocultables, ordenación en servidor y ficha vigente en ventana por doble clic o Intro. |
| Clientes | Crear licencia | Buscar empresa dentro del desplegable, consultar sus tiendas y generar un código por tienda. Los nuevos códigos duran 30 minutos, muestran contador y se recuperan mientras sigan vigentes al abrir o recargar. Los términos se leen de la ficha de tienda. |
| Mi empresa | Facturación | Facturas de nuestros servicios SaaS a clientes, pagos, saldos pendientes y conciliación. |
| Supervisión: 1. Revisar estado | Estado de clientes | Prioridad según licencias, actividad recibida, tickets y cobros. Sustituye el nombre poco claro «Pulso». |
| Supervisión: 2. Detectar fallos | Fallos de tiendas | Lista central de fallos de tiendas y servicio central, con origen, gravedad, estado, fechas y repeticiones. Abre diagnósticos; no repara directamente. |
| Supervisión: 3. Diagnosticar | Sincronización | Últimos eventos, ventas, existencias y cierres recibidos de tiendas y su procesamiento. La sección de procesos bloqueados corresponde a inicialización de miembros. |
| Supervisión: 3. Diagnosticar | Estado fiscal | Consulta del estado fiscal comunicado por las instalaciones cliente. |
| Supervisión: 4. Recuperar entregas | Recuperación de entregas | Reintentar o reconocer con motivo las entregas externas fallidas del SaaS. No reenvía colas locales de tiendas. |
| Supervisión: 5. Atender al cliente | Soporte | Tickets, prioridad y conversaciones. |
| Configuración técnica | Integraciones | Configuración e historial de intentos; no hay un canal externo de entrega activo en la implementación actual. |
| Configuración técnica | Activación global de VeriFactu | Calendario global separado del diagnóstico diario, con los permisos y auditoría existentes. |
| Administración | Usuarios | Cuentas administrativas y altas de usuarios cliente. |
| Administración | Accesos | Vinculación de una cuenta cliente a varias empresas y a tiendas concretas, con permisos explícitos. |
| Administración | Auditoría | Acciones administrativas. |

El portal usa `POST /api/v1/auth/admin/login`, que autentica únicamente cuentas internas. Una cuenta tenant válida es rechazada por esa ruta y sus tokens tampoco autorizan las API administrativas. El frontend rechaza y revoca defensivamente cualquier respuesta no administrativa antes de abrir sesión o pedir el cambio inicial de contraseña. La ruta de autenticación compatible y las API tenant se conservan para la futura aplicación cliente; no habilitan una interfaz cliente en este frontend.

Maestros y Operaciones se retiran del menú y de las rutas del portal interno. Sus tablas y API se conservan: eran maestros de empresas clientes y registros manuales centrales, no la facturación de nuestra empresa ni las operaciones locales de las tiendas. Tampoco se carga `TenantWorkspace` en la aplicación publicada. Las cuentas cliente y sus concesiones se siguen administrando internamente para la futura aplicación.

La revisión identificó límites preexistentes, separados de esta reorganización: ciertos listados de sincronización muestran una muestra de hasta 200 registros; Informes agrega monedas sin separarlas y sus ventas centrales incluyen todos los estados. No se cambian esos cálculos ni se presentan como contabilidad consolidada validada.

## Reglas de identidad y acceso

- `saas_store.id` sigue siendo el UUID técnico. `code` conserva las tres cifras locales utilizadas por el ERP. No se modifica numeración fiscal.
- `internal_code` es texto de siete cifras, único y permanente. Para España se usa el prefijo postal `01` a `52`, seguido de cinco cifras asignadas transaccionalmente. Se conservan ceros iniciales.
- Todo código que comienza por `9` queda reservado. Las altas actuales requieren domicilio español compatible. Las tiendas históricas sin dirección compatible conservan código pendiente hasta corregir su ficha.
- La actividad de la tienda, la validez de la licencia y la evidencia de comunicación son estados distintos. El filtro «Con instalaciones activas» no afirma que una tienda esté conectada en ese momento.
- Una identidad cliente puede tener vínculos con empresas distintas. El código interno identifica una tienda; no concede acceso y no es una contraseña.
- Cada petición tenant valida en backend la pertenencia y el permiso actuales. No hay acceso implícito por el antiguo `company_id` de la cuenta. Con varias empresas se exige `X-TPV-Company-Id`; un contexto ajeno se rechaza.
- Las altas adicionales sin asignación expresa nacen sin tiendas ni permisos empresariales. El alta de sociedad no crea cuentas. Los usuarios se crean y vinculan expresamente desde Usuarios y Accesos. La migración conserva los accesos de las cuentas existentes, sin conceder automáticamente tiendas futuras.
- `READ_COMPANY`, `READ_BILLING`, `READ_MASTERS`, `WRITE_MASTERS` y `SUPPORT` son permisos sobre toda la empresa. Escribir maestros exige también lectura y rol OWNER o MANAGER. Los documentos, existencias e instalaciones se limitan a las tiendas concedidas.
- La deuda mostrada en una licencia pertenece a la empresa, no se reparte arbitrariamente por tienda y no suma monedas distintas.

## Flujo corregido de alta

1. Crear sociedad con sus datos básicos, contacto y al menos un propietario identificado por nombre y DNI/NIE. Sin datos de tienda ni impuestos de operación local.
2. Crear la tienda de esa sociedad y configurar mayorista/minorista, IVA/IGIC, precio en EUR y período mensual o anual, Windows/PDA y caducidad inicial explícita.
3. Elegir esa tienda en Crear licencia y generar el código. La referencia se genera en servidor; no se vuelven a solicitar términos comerciales.
4. En Licencias activas, abrir la configuración con doble clic o Intro. Cambiar fecha/cupos conserva los mismos valores en tienda y licencia y exige el permiso de renovación. Un cambio de términos no desbloquea licencias.

Una generación posterior reutiliza la licencia directa compatible; no crea otra licencia para evadir bloqueos ni modifica licencias históricas compartidas. Las tiendas históricas sin precio no reciben importes inventados ni un reparto del antiguo precio empresarial. Es necesario completar su tarifa expresamente. El resumen comercial muestra un equivalente mensual de los precios configurados (anual / 12), no un cargo automático ni un importe de factura. Los importes históricos se conservan.

Las preferencias de columnas se guardan por usuario y tabla en el navegador. Reproducen las interacciones de APP VENTA (arrastrar, Ctrl+flechas, ancho, menú de columnas) sin acoplar el SaaS al backend local de preferencias.

Empresas consulta el registro completo mediante `GET /api/v1/admin/companies`, incluyendo las sociedades que aún no tienen licencia. El modelo actual no tiene un estado independiente de contrato: la tabla no deduce ni inventa contratos activos a partir de las licencias. Permite buscar por nombre, NIF/CIF, contacto, propietarios, ciudad o provincia y ordenar. La tabla ocupa todo el espacio disponible del área de trabajo; las filas se consultan mediante desplazamiento interno, con encabezados fijos y sin paginación local. El alta correcta cierra la ventana y actualiza el listado. La ficha se carga al abrirla, conserva los permisos de edición y revocación existentes y devuelve el foco a la fila al cerrar. Escape y Cerrar se bloquean durante el guardado; los errores se muestran dentro de la ventana.

El régimen de una tienda ya licenciada permanece protegido porque se entregó a su instalación. Los impuestos locales de la tienda no determinan automáticamente el tratamiento fiscal de la factura del servicio SaaS. Las nuevas facturas sin régimen conocido permanecen pendientes y requieren elegirlo en la revisión fiscal; no pueden cobrarse hasta completar la decisión. El régimen ya registrado en una factura no se sustituye. Referencias de ámbito tributario: [AEAT: territorio de aplicación del IVA](https://sede.agenciatributaria.gob.es/Sede/ayuda/manuales-videos-folletos/manuales-practicos/manual-iva-2025/capitulo-02-introduccion/territorio-que-se-aplica-impuesto.html) y [ATC: IGIC](https://www3.gobiernodecanarias.org/tributos/atc/w/igic-impuesto-general-indirecto-canario-). No se cambian tipos impositivos ni cálculos históricos.

## Organización del código

- `frontend-saas/src/app`: sesión, rutas, navegación declarativa agrupada y actualización de recursos.
- `frontend-saas/src/features`: módulos separados por responsabilidad; el antiguo App monolítico queda como punto de entrada.
- `frontend-saas/src/features/companies/CompaniesView.tsx` administra listado y alta; `CompanyDetail.tsx` concentra la ficha de sociedad y contacto. Empresas y Licencias reutilizan `shared/WorkspaceDialog.tsx` para foco, cierre y presentación de ventanas.
- `frontend-saas/src/shared`: controles, tablas, contraseña, formato y tipos de presentación reutilizados.
- `frontend-saas/src/lib`: transporte autenticado y contratos de API. El contexto de empresa acompaña también a las operaciones CSV tenant.
- `frontend-saas/src/i18n`: diccionarios independientes ES/EN/ZH y textos de las pantallas nuevas. La cobertura de claves se verifica automáticamente.
- `frontend-saas/src/styles`: capas ordenadas de estilos que conservan la apariencia empresarial existente.
- `backend-saas/.../access`, `stores`, `supervision`: servicios y controladores de permisos, administración de tiendas/licencias y monitorización.
- `backend/.../supervision`: publicación local de evidencias mediante la cola durable existente.

## API principales para web y futura aplicación móvil

| Operación | Ruta |
| --- | --- |
| Leer/guardar la ficha completa de sociedad en una transacción | `GET/PUT /api/v1/admin/companies/{companyId}/profile` |
| Accesos de la identidad | `GET /api/v1/tenant/access` |
| Sesión en empresa | `GET /api/v1/tenant/me` |
| Tiendas autorizadas | `GET /api/v1/tenant/stores` |
| Documentos filtrados/paginados | `POST /api/v1/tenant/documents/page` |
| Existencias de una tienda | `GET /api/v1/tenant/stores/{storeId}/stock` |
| Estado de sincronización | `GET /api/v1/tenant/stores/{storeId}/sync-status` |
| Conversación de soporte | `GET/POST /api/v1/tenant/tickets/{id}/comments` |
| Consultar permisos de una cuenta | `GET /api/v1/admin/tenant-users/{username}/access` |
| Asignar/revocar una empresa y tiendas | `PUT/DELETE /api/v1/admin/tenant-users/{username}/access/companies/{companyId}` |
| Tiendas con filtros y paginación | `GET /api/v1/admin/stores` |
| Licencias con filtros / alta | `GET/POST /api/v1/admin/license-workspace` |
| Ficha vigente de licencia | `GET /api/v1/admin/license-workspace/{licenseId}` |
| Códigos de activación vigentes | `GET /api/v1/admin/license-workspace/activation-codes` (`ADD_COMPANY`) |
| Fallos con filtros / detalle | `GET /api/v1/admin/supervision/failures[/{id}]` |

## Retirada de suscripciones

Se eliminan menú, pantalla, llamadas y endpoints de suscripciones, sus métricas y el permiso `MANAGE_SUBSCRIPTIONS`. Se conservan tablas históricas y auditoría. Continúan las licencias, facturas, pagos y límites de planes que usan otros flujos reales. No se borran datos comerciales históricos.

## Migraciones y activación

1. Actualizar primero el servicio central con V61 (accesos), V62 (identidad/actividad de tiendas y vínculo de licencia), V63 (fallos recibidos), V64 (retirada del permiso de suscripciones), V65 (configuración fiscal y comercial por tienda) y V66 (régimen pendiente en revisión de facturas).
2. Actualizar el backend local con V251 (evidencia duradera de fallos). No se reescriben migraciones anteriores.
3. El reporter local utiliza la vinculación de instalación existente y `tpv.sync.worker-enabled`. Escanea por defecto cada 60 segundos, tras 30 segundos iniciales, en lotes limitados. Reutiliza `sync_outbox` y su política de reintentos.
4. Publicar el frontend compatible y verificar con una cuenta administrativa y otra con concesiones limitadas. El 20/09/2026 se actualizaron los contenedores centrales DEV de backend y frontend en `http://127.0.0.1:8088`, con V65/V66 aplicadas. El backend local de tienda requiere su propia actualización para activar V251.

La lista reúne alertas de control, fallos de entrega local, errores persistidos de proyección y fuentes centrales actuales. Conserva evidencia de fallos locales aunque se recuperen entre escaneos. Los estados revisado/reconocido no se presentan como resuelto. No captura excepciones arbitrarias del ordenador ni reconstruye evidencias antiguas que ya no existen. Los registros centrales reencolados o completados dejan de aparecer como fallo actual según el estado de su fuente.

## Validación reproducible

Desde `frontend-saas`: `npm test`, `npm run build` y `npm run test:e2e`. Este último ejecuta los recorridos de navegador: general, administración multitienda, exclusividad del portal interno, alta de sociedades/tiendas, tabla/configuración de licencias y desplazamiento/carga incremental de tiendas.

Desde `backend-saas`: `./mvnw.cmd test`. El perfil de pruebas limita cada pool a cuatro conexiones y no mantiene conexiones inactivas: evita que los múltiples contextos Spring agoten las conexiones del PostgreSQL de Testcontainers. No modifica la configuración de producción.

Las pruebas del backend local cubren `StoreFailureEvidenceTest`, `StoreFailurePublisherPostgreSqlTest`, `SyncOutboxServiceTest`, `SyncOutboxWorkerTest` y `SyncEventTest`. Se utilizan esquemas y datos sintéticos aislados. Las pruebas de navegador verifican interacciones con respuestas API sintéticas; la autorización y persistencia se comprueban por separado en pruebas PostgreSQL/HTTP de backend. No equivalen a una prueba de despliegue sobre tiendas reales.

Evidencia de esta entrega: la suite SaaS completa, incluyendo `AdminApiPostgresIT`, superó 689 pruebas en 87 suites. Tras la revisión final se corrigieron dos carreras de edición de sociedad y dirección fiscal, y se ejecutaron 52 pruebas focalizadas sin fallos, incluidas dos regresiones nuevas: 691 casos únicos superados en total. También se verificaron 19 pruebas focalizadas del backend local en la fase de supervisión, 73 pruebas frontend, compilación frontend y los cinco recorridos E2E. La suite SaaS omite 10 casos de recuperación documental preexistentes que exigen `TPV_TEST_DB_URL=jdbc:postgresql:...`; no se habilitan en la ejecución con el contenedor JDBC automático. Las pruebas nuevas de tiendas, accesos, migraciones, facturación y supervisión sí se ejecutan.

La comprobación posterior al reinicio DEV verificó salud `UP`, V65/V66 correctas, las cuatro API de empresas/tiendas/licencias/fallos y los formularios reales, incluida la apertura de configuración mediante doble clic. No modificó registros comerciales. Los conteos de empresas, tiendas, licencias y facturas coinciden antes y después. La copia previa se conserva como respaldo local fuera del repositorio, con archivo PostgreSQL, índice legible y SHA256. No se ha verificado una restauración completa ni un despliegue productivo.

La revisión posterior de portal interno y navegación por fases se validó con 19 pruebas focalizadas de autenticación, 75 pruebas frontend, compilación y los cinco recorridos E2E. Se comprobó que una cuenta tenant válida recibe 401 en `auth/admin/login`, que sus tokens no autorizan las API administrativas y que una respuesta tenant inesperada tampoco abre el portal ni la pantalla de cambio obligatorio. Se conserva el ciclo de contraseña de las cuentas internas. Las capturas y la navegación por teclado se revisaron a 1280 y 1600 píxeles.

Backend y frontend DEV se actualizaron también con esta revisión, sin nuevas migraciones. La comprobación con el servicio real verificó acceso interno, Facturación en Mi empresa, fases según permisos, separación de consulta fiscal/configuración y los formularios empresa/tienda/licencia. No hubo errores JavaScript ni modificaciones de registros comerciales.

La revisión de Empresas como tabla inicial se validó con 75 pruebas frontend, compilación local y Docker, E2E de empresas/tiendas y regresión de tabla/configuración de licencias. Se verificaron alta en ventana, errores y reintento sin perder el formulario, bloqueo de cierre durante guardado, búsqueda/ordenación, doble clic/Intro, permisos de consulta, respuestas tardías, devolución del foco y conservación de contacto/notas pendientes al guardar la sociedad. Las capturas de tabla, alta y ficha se revisaron a 1280 y 1600 píxeles; la tabla desplaza sus columnas sin sacar el botón de alta de la pantalla. Se actualizó únicamente el contenedor frontend DEV y se repitió la comprobación con el servicio real sin modificar datos comerciales. No requirió cambios de backend ni de esquema.

## Revisión de ficha de empresa del 21/09/2026

- Las flechas de ordenación y los menús de columnas aparecen al pasar el ratón, dar foco con el teclado o abrir el menú. En dispositivos táctiles permanecen accesibles. El ancho del encabezado no cambia al mostrarlos.
- Se elimina el perfil comercial de la tabla y los formularios de sociedad. Se amplía el listado con contacto, teléfono, email y nombres de propietarios; los DNI/NIE se consultan en la ficha, no se añaden como columna.
- La ficha tiene un solo `Guardar ficha`. `PUT /profile` valida y guarda nombre/domicilio, contacto, soporte, notas y propietarios en una transacción, conservando datos comerciales históricos de operaciones. No encadena dos guardados independientes. NIF/CIF y tipo de obligado se preservan; la actividad reciente deja de aparecer, mientras que las instalaciones se conservan.
- El alta y la actualización completa exigen al menos un propietario con nombre y DNI/NIE válido. Teléfono y email son opcionales. Se permiten varios propietarios. Los históricos quedan con una lista vacía si no existía esa información y deben completarla al guardar; no se inventan titulares a partir del contacto o la razón social.
- V67 añade propietarios y teléfono de contacto, y conserva el perfil societario histórico permitiendo que las nuevas sociedades no tengan ninguno. V68 incorpora el perfil por tienda y copia los valores existentes de la sociedad. Alta nueva de tienda elige explícitamente mayorista/minorista; clientes antiguos pueden heredar solo un valor societario conocido. La vinculación y validación de instalaciones leen el perfil de su tienda. Una licencia histórica compartida no tiene un único perfil comercial en su resumen.
- El selector de provincia reúne los 52 códigos del [INE](https://www.ine.es/daco/daco42/codmun/cod_provincia.htm): 50 provincias y las ciudades autónomas de Ceuta y Melilla. Las capturas aportadas cubren todas. Se muestran nombres naturales y equivalencias conocidas; un texto histórico desconocido se conserva como valor actual hasta una selección explícita.
- Los datos de propietarios quedan bajo los permisos administrativos existentes. La auditoría registra la operación y el identificador de empresa, sin copiar DNI ni contactos. Se consultaron los principios de [minimización, integridad y confidencialidad de la AEPD](https://www.aepd.es/preguntas-frecuentes/2-tus-obligaciones-como-responsable-del-tratamiento/4-los-principios-del-tratamiento/FAQ-0207-que-principios-debo-cumplir); esto no equivale a una auditoría jurídica completa.

Dependencia preexistente fuera de esta revisión: el backend local configura un impuesto predeterminado al vincular la instalación; actualizar el perfil por validación no recalcula ese impuesto predeterminado. Esta entrega traslada la fuente del perfil a la tienda, sin cambiar cálculos, documentos históricos ni la política de actualización fiscal local.

Copia anterior a V67/V68: respaldo local fuera del repositorio, con archivo PostgreSQL, índice y SHA256.

Validación del 21/09: 79/79 pruebas frontend, compilaciones local/Docker, E2E completo de empresas/tiendas y regresión de licencias, con capturas a 1280/1600. La suite backend terminó con 719 casos, cero errores/fallos y 10 omisiones de recuperación documental condicionadas por el entorno (709 ejecutados). Después del ajuste de validación sin datos personales en logs se verificaron 11 casos focalizados, incluidos los 2 de `AdminApiPostgresIT`: 712 casos únicos superados en total. Las pruebas nuevas cubren transacción/rollback, permiso de edición, propietarios y DNI/NIE, conservación de datos comerciales, migraciones y perfil independiente por tienda en vinculación/validación. Los errores de alta y actualización de ficha se validan en el servicio para no entregar los valores personales rechazados al logger de validación MVC.

Backend y frontend DEV actualizados y saludables, con V67/V68 aplicadas. La comprobación real verificó tabla, propietario obligatorio, selector de provincias, guardado único, ausencia de perfil comercial/actividad reciente en sociedad y perfil por tienda. No ejecutó escrituras comerciales: se conservaron 3 empresas, 3 tiendas, 3 licencias y 0 facturas. Las 3 empresas históricas siguen sin propietarios inventados, y los 3 perfiles de tienda coinciden con los valores anteriores. No se ha desplegado en producción ni probado una restauración completa de la copia.

La tabla de Empresas utiliza ahora todo el alto y ancho disponibles del área de trabajo, conserva los encabezados al desplazar las filas y elimina la paginación local de 25 registros. Se validó con 82 empresas sintéticas, filtros de dos filas y SIN DATOS a 1280×900, 1600×1000 y 1366×768, incluyendo navegación hasta la última fila por teclado y rueda sin desplazar la página. Pasaron las 79 pruebas frontend, las compilaciones local/Docker y el E2E de empresas/tiendas. Se actualizó únicamente el frontend DEV y se comprobó el servicio real sin escrituras comerciales. No requiere cambios de API ni migraciones.

Tiendas comparte el ajuste al área de trabajo, con scroll interno y cabeceras fijas. Se retiran los botones de página; la API mantiene sus bloques de 25 y el frontend solicita el siguiente al acercarse al final, conservando las filas anteriores. Cambiar filtros, ordenación, actualizar o guardar reinicia la consulta; las respuestas antiguas se descartan. Un fallo conserva las filas cargadas y permite reintentar el bloque fallido sin un bucle automático. El alta y la ficha de gestión se abren en ventanas independientes; no cambian las reglas de tienda.

Validación de Tiendas: 79 pruebas frontend, compilaciones local/Docker y recorridos de empresas/tiendas, administración multitienda y scroll incremental. El nuevo recorrido cubre 82/2/0 filas a 1280×900, 1600×1000 y 1366×768, filtros, error/reintento de una página y descarte de respuestas tardías. Se actualizaron los datos de prueba antiguos de tiendas para incluir el perfil comercial obligatorio y seleccionar provincia mediante el desplegable existente. Frontend DEV actualizado y comprobado con el servicio real sin modificar registros comerciales; el backend no requiere reinicio ni migraciones.

## Organización de columnas y ficha de tienda

La tabla utiliza `SaasDataTable` con clave `stores`, igual que Empresas y Licencias: mover columnas por arrastre o teclado, ajustar ancho, ocultar/mostrar y restablecer; las preferencias se conservan por usuario. Se separan código local, nombre y cupos Windows/PDA, y se muestra la última sincronización. Los iconos del encabezado aparecen al pasar el ratón o dar foco. La ordenación de filas es global en servidor mediante `sortBy`/`sortDirection`, con columnas permitidas y desempate estable; no se limita a los bloques ya cargados.

Doble clic o Intro abre `StoreDialog`, que obtiene la ficha vigente mediante `GET /api/v1/admin/stores/{id}`. Alta, edición y actividad se gestionan en la ventana con un solo Guardar, errores internos y bloqueo de cierre durante una escritura. Las cuentas de consulta pueden abrir la ficha con controles deshabilitados, sin guardar ni activar/desactivar. El refresco global del listado no desmonta el editor ni pierde el borrador o un guardado pendiente. Se conservan las restricciones de impuestos/licencia y la precisión de precio/caducidad anteriores.

Validación: 23 pruebas PostgreSQL de tiendas (incluidas ordenación numérica/fechas entre páginas, nulos, rechazo de claves inválidas y permisos del detalle), 79 pruebas frontend, compilaciones local/Docker y E2E de tiendas, empresas/tiendas, administración multitienda y regresión de licencias. Se comprobó el listado de 82 filas en tres resoluciones, preferencias, teclado, reintentos y la carrera de refresco global durante un guardado. Esta revisión actualiza backend y frontend DEV y no introduce migraciones.

Comprobación posterior en DEV correcta: servicios saludables, ordenación remota, ficha actual por doble clic/Intro, devolución del foco y alta en ventana. No se modificaron registros comerciales durante esta comprobación ni hubo errores JavaScript.

## Tabla y ficha de licencias activas

Licencias ocupa el área disponible con cabeceras fijas y desplazamiento interno. Conserva mover, ajustar, ocultar/mostrar y restablecer columnas con preferencias por usuario; los controles del encabezado aparecen con ratón o foco. Sustituye los botones de página por carga incremental en bloques de 25, compartiendo `usePagedDirectory` con Tiendas. Filtros y ordenación se aplican en servidor; se descartan respuestas antiguas y se conservan las filas anteriores al fallar una página, con reintento explícito.

`LicenseConfiguration` obtiene una ficha vigente por UUID al abrir. Un refresco del listado no pierde el borrador ni cierra la ventana cuando la licencia sale del filtro de activas tras bloquearla. Los errores y reintentos aparecen dentro de la ficha; las escrituras mantienen sus permisos y bloquean el cierre mientras se completan. Renovar conserva segundos/milisegundos cuando solo cambian los cupos. La revocación aplica inmediatamente la respuesta canónica a la instalación aunque el refresco general tarde o falle. La vista mantiene saldos separados por moneda. No requiere migraciones ni cambia precios, impuestos o reglas de licencia.

Validación: 79 pruebas frontend y 25 pruebas PostgreSQL superadas, compilaciones local/Docker correctas y recorridos de licencias, tiendas y administración multitienda. Se comprobó scroll con 82/2/0 filas en tres resoluciones, filtros/ordenación global, preferencias de columnas, permisos, recuperación ante fallos de páginas/detalle/guardado y conservación del borrador. La revocación conserva la última sincronización conocida cuando su respuesta no incluye esa proyección. Backend y frontend DEV actualizados y saludables; el recorrido real confirma el tamaño de la tabla, cabeceras fijas, ficha vigente por doble clic/Intro y devolución del foco, sin errores JavaScript ni escrituras comerciales.

## Creación y recuperación de códigos de activación

`CreateLicenseView` separa el alta del directorio de licencias activas. `CompanyPicker` permite escribir en el propio desplegable, buscar por nombre/NIF sin distinguir acentos y seleccionar con ratón o teclado. El texto libre no selecciona una empresa. Se listan sus tiendas con carga incremental de 25 y un botón de generación por fila; las inactivas se muestran sin permitir generar.

Los nuevos códigos caducan a los 30 minutos tanto al crear como al regenerar desde la ficha. La regla está centralizada en `PairingCodePolicy`; no se alteran fechas ya emitidas. La lista paginada de códigos vigentes aparece al abrir sin necesitar seleccionar empresa, con empresa, tienda, referencia, código, vencimiento y contador. Los códigos solo permanecen en memoria del navegador; la recuperación procede del backend mediante `ADD_COMPANY`, con respuestas sin caché. No se entregan a lectores generales ni a usuarios cliente.

El contador usa la hora del servidor y tiempo monotónico desde el inicio de la solicitud, de forma conservadora ante latencia. Un segundo código sustituye al anterior según la regla existente. Los datos anteriores a esa emisión no pueden volver a mostrar el código invalidado, y solo una consulta iniciada después de recibir el POST confirma el estado nuevo. Se reconsulta cada 30 segundos y al recuperar foco/visibilidad, sin peticiones simultáneas; consumo o bloqueo se aplican siempre en el backend al intentar vincular. Un 403 retira los códigos mostrados. No hay migraciones ni cambios de precio, impuestos, cupos o caducidad de licencia.

Validación de esta revisión: 81 pruebas frontend, 32 backend focalizadas y compilaciones local/Docker correctas. Los recorridos E2E de creación, licencias, administración multitienda y tiendas pasan. El nuevo recorrido cubre 31 tiendas, 27 códigos, búsqueda/teclado, paginación, errores, emisión única durante una solicitud pendiente, recarga sin volver a emitir, reloj del equipo desajustado, vencimiento, consumo, sustitución con respuestas tardías y retirada de secretos tras 403. Capturas sintéticas revisadas a 1280 y 1600 px. Backend y frontend DEV actualizados y saludables; comprobación real de empresa→tiendas y consulta de códigos sin errores JavaScript, sin generar códigos ni modificar registros comerciales.

## Filtros del directorio de empresas

Empresas tiene un único bloque de filtros dentro del panel. Se retira el buscador global de esta pestaña y la ficha recibe todas sus instalaciones, sin heredar una búsqueda oculta de otras pestañas. Los criterios principales son Empresa/NIF, Provincia, Tipo y Fecha de alta; Más filtros muestra contacto, teléfono, email, propietario (nombre o DNI/NIE), ciudad y código postal. Los criterios se combinan con AND y se representan con etiquetas que se quitan individualmente; Limpiar todos restablece el directorio.

El intervalo incluye los días inicial y final según el calendario local y muestra un error si está invertido. Las provincias reconocidas comparten su código INE; los valores históricos desconocidos siguen siendo seleccionables sin reescribir direcciones. El filtro por propietario consulta todos los propietarios, mientras que la columna Propietario 1 muestra y ordena exclusivamente por el primero. La ficha mantiene la lista completa.

No hay botón Columnas. Se conservan las opciones de mover, ajustar y ocultar desde los encabezados, las preferencias por usuario, el scroll interno y la apertura por doble clic/Intro. El filtrado utiliza el directorio ya cargado por la API existente y no modifica el backend, el esquema ni los permisos. Los filtros son temporales y no se guardan datos personales de búsqueda en el navegador.

Validación: 90 pruebas frontend superadas, compilaciones local/Docker y E2E de filtros, alta/ficha de empresa-tienda y portal interno correctos. Se verificaron combinaciones, provincias equivalentes, teléfono, propietario 2 por nombre/DNI, fechas inclusivas y cambios de horario, etiquetas eliminables, ficha completa e instalaciones pese a búsquedas globales anteriores. Las capturas sintéticas de 1280/1600 se revisaron y los paneles desplegados conservaron filas visibles a 1366×768 y 1280×720. Frontend DEV actualizado; comprobación con el servicio real sin errores JavaScript ni modificaciones comerciales. Docker mantiene un aviso de tamaño del paquete JavaScript (aprox. 508 kB sin comprimir); no bloquea la compilación. No se ha publicado en producción.

## Propietarios y contacto de la ficha

El alta y la ficha de empresa muestran Propietarios antes de Contacto; el campo de persona de contacto se llama Nombre. Usar datos de propietario copia su nombre, teléfono y email en el borrador, reemplazando también los valores vacíos para no arrastrar datos de otra persona. Si falta teléfono o email, se indica y exige completarlo antes de guardar mientras esté seleccionado.

La selección enlaza los campos del propietario y del contacto únicamente durante la edición, con una indicación visible. Completar o editar sus datos actualiza ambos en el mismo borrador y se conserva el único guardado transaccional existente. Al quitar al propietario seleccionado se mantiene el contacto como independiente; al eliminar un propietario anterior se ajusta la selección. Cargar una ficha canónica nueva reinicia esa selección temporal. No se crea una relación permanente ni se modifican contratos, permisos o tablas del backend; los demás propietarios conservan teléfono y email opcionales.

Las mayúsculas se aplican a toda la ventana de empresa, incluidos alta, ficha, controles y mensajes, mediante estilos locales. Se conservan los valores originales enviados al servidor, especialmente el email; no se reescriben datos históricos por un cambio visual. La tabla, sus filtros y los otros módulos mantienen su presentación.

Validación: 90 pruebas frontend, compilaciones local/Docker y los tres E2E de contacto, filtros y alta/ficha de empresa-tienda superados. Los escenarios nuevos cubren copia, ausencia de ambos datos o solo email, teléfono con espacios, validación liberada al volver a contacto independiente, edición en ambas direcciones, único PUT, eliminación y cambio de índices, reinicio tras respuesta canónica, cancelación, alta y permisos de consulta. Captura sintética de 1280 revisada. Frontend DEV actualizado y servicios saludables; comprobación real de orden, selector, etiquetas y mayúsculas limitada a lectura, sin errores JavaScript ni escrituras comerciales. No se modifica el backend ni requiere migraciones.

## Selector de país de empresa

País es un desplegable en el alta y la ficha de empresa. España aparece primero y sigue siendo el valor inicial; los demás nombres se ordenan alfabéticamente según el idioma ES/EN/ZH. Se mantienen las mayúsculas de la ventana y se envían los códigos de dos letras del contrato existente, no los nombres traducidos. Un país histórico fuera del catálogo permanece disponible como valor actual y no se sustituye automáticamente.

El catálogo utiliza un ámbito europeo amplio: los 46 miembros del [Consejo de Europa](https://www.coe.int/en/web/portal/members-states), más Bielorrusia, Rusia, Kazajistán, Ciudad del Vaticano y Kosovo. Los códigos se contrastaron con [ONU M49](https://unstats.un.org/unsd/methodology/m49/overview); XK es el código de uso asignado que recoge [Publicaciones de la UE](https://op.europa.eu/en/web/eu-vocabularies/countries-and-territories), no un código ISO oficial. Son 51 opciones y no se limita el catálogo a miembros de la Unión Europea ni se incluyen territorios dependientes como países separados.

En direcciones de empresa con país distinto de ES, Provincia permite texto libre; al volver a ES se recupera el selector español conservando el valor anterior. El cambio se activa expresamente en las dos fichas de empresa mediante `AddressFields.countryOptions`; no cambia las reglas españolas de tiendas, NIF, impuestos ni licencias y no requiere migraciones.

Validación: compilaciones local/Docker y 90 pruebas frontend superadas. El E2E de la ficha comprueba primera opción ES/España, 51 países, valor inicial ES, cambio FR/provincia libre y envío FR, conservación de un país histórico US y controles deshabilitados en consulta. Frontend DEV actualizado y comprobación real de alta, selector y cambio de provincia superada, sin escrituras comerciales ni errores JavaScript.

Se retira el selector Soporte del alta y la ficha de empresa. Contacto queda distribuido en tres campos: Nombre, Teléfono y Email contacto. Se conserva el valor histórico de `supportStatus` en el contrato de guardado, sin cambios de backend ni borrados de datos.

## Etiquetas de filtros en Tiendas y Licencias activas

Ambas vistas reutilizan `DirectoryFilters` para mostrar los criterios aplicados como etiquetas con cruz individual y Limpiar todos. Más filtros despliega los criterios secundarios sin borrarlos al cerrar el panel; sus etiquetas siguen visibles. Los textos están disponibles en ES/EN/ZH y, al eliminar una etiqueta con teclado, el foco pasa a la siguiente o al buscador.

Tiendas mantiene búsqueda y empresa como principales, con Estado dentro de Más filtros. Licencias mantiene búsqueda, empresa y estado, con caducidad, instalaciones activas y facturación en Más filtros. Se reutilizan exclusivamente los criterios existentes de la API, combinados en servidor. El estado inicial de licencias es VALIDA y su etiqueta se puede quitar; Limpiar todos retira también ese criterio y consulta todos los estados.

Las consultas conservan bloques de 25, ordenación global y descarte de respuestas antiguas al cambiar filtros. Los nombres de empresas se conservan durante recargas sin mostrar UUID en las etiquetas; cada sesión reinicia sus filtros y caché. La tabla ocupa el espacio restante y mantiene una altura mínima utilizable cuando se despliegan filtros. No se añaden botones Columnas ni se cambian las fichas, los permisos, el backend o el esquema.

Validación: 90 pruebas frontend, compilaciones local/Docker y cuatro recorridos E2E (tiendas, licencias, administración multitienda y alta/ficha empresa-tienda) superados. Se verificaron filtros AND enviados a servidor, eliminación individual y conjunta, estado inicial VALIDA eliminable, criterios conservados con el panel cerrado, scroll/paginación y respuestas tardías. Capturas sintéticas a 1280/1600 revisadas. Frontend DEV actualizado; comprobación real de filtros, tablas y fichas sin errores JavaScript ni modificaciones de registros comerciales.

## Empresa con búsqueda en el desplegable y revisión de estados

El filtro Empresa de Tiendas y Licencias activas reutiliza `shared/companies/CompanyPicker`, extraído del módulo Crear licencia. Busca por nombre o NIF en el propio cuadro, admite selección con teclado y muestra Todas para retirar el criterio. El filtro solo cambia al seleccionar una opción; mientras se escribe otro nombre se conserva la empresa aplicada. Escape, Tab, clic exterior y cerrar la flecha cancelan el borrador. La cruz de Empresa y Limpiar todos retiran la selección, conservando el funcionamiento de las otras etiquetas. Se mantiene un nombre de respaldo si el catálogo no incluye temporalmente la empresa seleccionada.

Crear licencia conserva la selección obligatoria: escribir un nombre no equivale a seleccionar una empresa y no habilita su emisión. No se modifican endpoints, permisos ni datos comerciales. Los tres consumidores comparten el mismo componente y estilos; las etiquetas existentes siguen traducidas a ES/EN/ZH.

Validación del cambio: compilaciones local/Docker, 90 pruebas frontend y cinco E2E (tiendas, licencias, alta/ficha empresa-tienda, administración multitienda y creación de licencia) superados. Los casos nuevos comprueban búsqueda por nombre/NIF, teclado, cancelación sin nuevas consultas ni pérdida de filas, Todas, etiquetas, limpieza y conservación del nombre al refrescar. Capturas sintéticas de ambos desplegables revisadas a 1280/1600. Frontend DEV actualizado y saludable; comprobación real de búsqueda, selección, cancelación y limpieza en ambas vistas sin escrituras comerciales ni errores JavaScript.

### Resultado de la revisión de estados (sin modificaciones contables)

- **Historial de licencias:** la opción `allLicenses` de `i18n/workspace.ts` envía estado vacío. `LicenseWorkspaceService.list` omite entonces el predicado de estado; devuelve todas las licencias actuales, incluidas válidas, caducadas y bloqueadas. No consulta un historial de cambios. Se recomienda llamarla Todos los estados.
- **Estado de empresa manual:** `AdminService.writeCompanyProfileFields` inicializa `saas_company_operations.billing_status` con PENDIENTE. `updateCompanyOperations` acepta PAGADO, PENDIENTE, VENCIDO e IMPAGADO. No se encontró un consumidor actual de su edición en las pantallas; crear facturas, registrar cobros y conciliar pagos no sincroniza este campo.
- **Estado y saldo pueden discrepar:** `LicenseWorkspaceService` lee y filtra aquel estado manual, mientras calcula la deuda por empresa y moneda con facturas menos pagos. Puede mostrar PAGADO con deuda o PENDIENTE sin saldo pendiente. Sin facturas, la columna saldo muestra Pagado, aunque el estado inicial de la empresa es Pendiente. Esto describe escenarios posibles en el código, no una auditoría de saldos reales.
- **Etiquetas duplicadas:** `shared/lib.tsx::billingStatusLabel` traduce tanto VENCIDO como IMPAGADO con `overdue` (Vencido en español). Las dos opciones aplican filtros distintos pese a mostrar la misma etiqueta.
- **Indicadores dependientes:** las notificaciones, contadores y puntuación de salud también usan el estado manual. `AdminService.billingCompany` marca vencimiento adicionalmente por la antigua fecha de renovación de empresa, no por la fecha de vencimiento de las facturas. Empresas sin fila de operaciones reciben PENDIENTE en algunos resúmenes, mientras Licencias presenta estado nulo y no las incluye en ese filtro.

Las facturas tienen su propio cálculo PAGADA/PARCIAL/VENCIDA/PENDIENTE en `AdminService`; las pruebas existentes cubren pagos de factura y edición manual de empresa por separado, sin probar una sincronización entre ambos circuitos. La revisión de facturación fue estática, limitada a servicios, contratos, migraciones, consumidores y pruebas existentes. No se alteraron cobros, facturas, saldos ni reglas de vencimiento.

La corrección recomendada es definir una única proyección del estado de empresa a partir de facturas y cobros para filtros e indicadores, distinguir empresas sin facturas y concretar si Impagado será una incidencia manual diferenciada. Esa definición y su implementación constituyen un cambio funcional posterior; no forman parte del cambio autorizado del desplegable.

## Copiar, eliminar y evitar códigos de activación duplicados

Crear licencia muestra Copiar y Eliminar junto al código, tanto en la tabla como en el resultado recién emitido. Copiar utiliza la utilidad compartida de portapapeles, confirma el resultado sin repetir el secreto en avisos y comprueba la caducidad al pulsar. El fallback retira su selección temporal incluso si el navegador rechaza la copia y devuelve el foco al control anterior. Los textos están disponibles en ES/EN/ZH.

Eliminar requiere `REGENERATE_PAIRING_CODE` y llama a `DELETE /api/v1/admin/license-workspace/activation-codes/{codeId}`. Devuelve 204 para un código conocido, incluido uno ya consumido, caducado o revocado; un UUID inexistente devuelve 404. Se revoca exclusivamente el código indicado y se conserva su fila, vencimiento original y auditoría. No se modifica la licencia ni la instalación. `CreatedLicense` añade `pairingCodeId` para que el resultado inmediato use la identidad correcta, distinta de la licencia.

La interfaz impide escrituras simultáneas, muestra los errores sin retirar otras filas y descarta respuestas antiguas que contengan un código ya eliminado. La sustitución al generar se controla por tienda; la eliminación se controla por UUID de código, por lo que una ventana antigua no puede ocultar ni revocar un código posterior de la misma tienda. Se mantienen la paginación, el contador de 30 minutos y la recuperación al recargar.

La migración V69 incorpora fecha y motivo de revocación y un índice único por tienda para códigos no consumidos y no revocados. Un código caducado puede conservar ese lugar hasta que una emisión posterior lo sustituye. Tanto la emisión desde Crear licencia como la ruta antigua revocan los pendientes anteriores de esa tienda y vacían los cambios antes de insertar el nuevo código, dentro de la misma transacción. Se conserva también la unicidad del texto del código. Las operaciones coordinan sus bloqueos con el consumo para mantener la recuperación autenticada de instalaciones.

V69 conserva todas las filas históricas; si existen varios pendientes de una tienda, prioriza el candidato utilizable y marca los restantes como sustituidos. La ruta antigua rechaza licencias bloqueadas o caducadas antes de invalidar ningún código. Se añaden regresiones de persistencia, permisos, borrado idempotente exacto, concurrencia entre rutas, consumo frente a revocación, rollback y migración de datos históricos.

El backend y V69 deben desplegarse juntos: el binario anterior no conoce la revocación ni libera el lugar reservado por el índice. No se debe volver a esa versión sobre el esquema nuevo; para DEV se preparó y verificó un respaldo previo a la migración fuera del repositorio.

Validación: 108 pruebas backend sobre PostgreSQL aislado, 90 pruebas frontend, compilaciones local/Docker y tres E2E (creación, tabla de licencias y administración multitienda) superados, sin fallos ni omitidas en las suites ejecutadas. El navegador cubre copia correcta, fallback rechazado, limpieza/foco, caducidad al pulsar, errores de eliminación, un único envío, respuesta tardía, código recién emitido, regeneración desde otra sesión y permisos. Portapapeles simulado en las pruebas; capturas sintéticas revisadas a 1280/1600.

Backend y frontend DEV actualizados y saludables. V69 aplicada correctamente; comprobado el índice y cero tiendas con varios códigos pendientes no revocados. La comprobación del portal real fue de lectura, sin generar, copiar ni eliminar códigos ni alterar registros comerciales, y no presentó errores JavaScript. No se ha publicado en producción.
