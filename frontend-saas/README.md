# Panel interno SaaS de TPV ERP

Aplicación de administración de empresas, tiendas, licencias e incidencias.

## Usuarios del SaaS y auditoría

En **Administración → Usuarios SaaS**, el formulario crea cuentas del personal
sin roles. El listado muestra usuario, estado y fecha de alta en UTC. Todas las
cuentas de personal activas, tanto nuevas como anteriores, tienen las mismas
capacidades administrativas. El alta envía `{username, password}`; la respuesta
no contiene roles. El servidor mantiene `permissions` en `/api/v1/admin/me`
como contrato de compatibilidad con las pantallas, con la misma política para
todo el personal. No se permite desactivar la propia cuenta ni dejar al SaaS
sin ninguna cuenta de personal activa.

**Clientes → Usuarios cliente** (`#/client-users`) es una pantalla independiente.
Incluye las pestañas **Solicitudes** y **Usuarios cliente**. La primera permite
buscar y filtrar solicitudes, revisar los datos personales y las referencias
de tiendas, aprobar sin tiendas o seleccionar tiendas de varias empresas,
y rechazar con un motivo. La revisión comprueba la versión y registra al
personal que tomó la decisión, con fecha UTC. La selección preparada y el motivo
se conservan ante errores recuperables; antes de reintentar se consulta el estado.

El directorio (`#/client-users/users`) incluye también cuentas sin empresas ni
tiendas. Desde una fila se gestionan contraseña, desactivación y accesos, sin
volver a escribir el usuario. Las concesiones existentes se conservan; guardar
cero tiendas o revocar una empresa no altera las demás. `#/access` se mantiene
como enlace compatible al directorio. Las tablas reutilizan la selección,
columnas y teclado de las otras pantallas; búsqueda y ordenación son globales.

En `tienda.html#/request-access`, accesible desde **Solicitar acceso**, el cliente
rellena sus datos, elige sus credenciales y puede describir de cero a veinte
tiendas. No se publica el catálogo de empresas/tiendas. El formulario usa un
identificador de reintento, no guarda contraseñas en el navegador y solo confirma
el envío al recibir HTTP 202. La identidad autenticable se crea al aprobar;
las referencias declaradas nunca conceden acceso automáticamente. La asignación
inicial solo incluye las tiendas elegidas por el personal, sin permisos globales
de empresa. Una cuenta aprobada sin accesos puede entrar y consultar el estado
vacío.

En **Configuración técnica → Correo SMTP** (`#/smtp`), el personal conecta las
cuentas de salida. La pantalla muestra cada dirección, su estado y las acciones
de añadir o eliminar correo. El formulario y la ayuda del proveedor se abren
en un diálogo. Pulsar una dirección permite probar o revisar su conexión.
Una contraseña vacía conserva la guardada; cambiar servidor, puerto, TLS o
usuario requiere introducirla de nuevo. Cada cuenta mantiene sus propias
credenciales y se puede elegir como remitente al redactar.

Los **Avisos automáticos** se configuran desde **Comunicaciones**, con elección
expresa de cuenta y URL pública HTTPS del portal. Las pruebas y los correos
manuales funcionan con los avisos desactivados y sin URL pública. Conectar una
cuenta no activa avisos ni envía mensajes por sí solo.

El selector ofrece los mismos preajustes que Gestión: Gmail / Google Workspace,
Yahoo Mail, QQ Mail, iCloud Mail, NetEase 163 y Tencent Exmail, además de otro
servidor manual. Seleccionar uno rellena servidor, puerto y seguridad; no guarda
ni envía mensajes. Al cargar una cuenta se respetan su puerto y cifrado guardados.
La ayuda de contraseña incluye los enlaces oficiales y pasos en ES/EN/ZH. La
prueba requiere confirmar que la dirección de destino es propia o está autorizada.

También permite autorizar Google o Microsoft cuando el servidor tenga registrado
el cliente OAuth correspondiente. La autorización se abre en el proveedor y se
confirma al volver y recargar la configuración de esa cuenta. La autorización
solo afecta a la cuenta elegida. Eliminar una cuenta retira sus credenciales y
conserva el historial; no se permite mientras tenga envíos manuales pendientes
o en curso. Los tokens nunca se envían al navegador. Los requisitos de
instalación y la URI de retorno se documentan en `backend-saas/README.md`.

Al aprobar o rechazar una solicitud, se encola el aviso. El rechazo incluye el
motivo. La ficha muestra el estado del aviso, los intentos, la aceptación por el proveedor
con fecha UTC y un reintento para envíos fallidos. Los avisos pendientes de
configuración permanecen en cola hasta activar SMTP.

**Clientes → Comunicaciones** (`#/communications`) permite redactar con el editor
visual o con código HTML, elegir remitente, guardar borradores y enviarse una
prueba del contenido. El interruptor de
avisos de solicitudes y la URL pública no son necesarios para un envío manual.
Antes de confirmar, la revisión muestra el contenido saneado, el remitente y
los destinatarios exactos, con los totales de omisiones y correos duplicados.
El selector incluye los contactos de las empresas, sus propietarios y las cuentas
cliente con su correo de solicitud aprobada. Las direcciones repetidas se agrupan
sin distinguir mayúsculas ni espacios exteriores, antes de buscar o paginar.
Cada fila muestra las empresas asociadas y los tipos de contacto; se puede buscar
por cualquiera de sus nombres, empresas o correo. Las cuentas cliente inactivas
y los registros sin dirección válida permanecen inhabilitados, salvo que la misma
dirección tenga otro origen disponible. No se exige que el contacto tenga tiendas.
La opción inicial es una selección vacía y «Todos» exige elegirla explícitamente.

El historial y las tablas de destinatarios conservan su altura aunque estén
vacíos y usan los controles, selección y estilo de las tablas del SaaS.
El historial conserva el resultado por destinatario y las fechas UTC. Permite
cancelar los pendientes y reintentar fallos; una entrega incierta exige confirmar
que se ha revisado antes de repetirla. El borrador avisa antes de salir con cambios.
`node e2e/client-communications.mjs` comprueba estos recorridos, ES/EN/ZH, HTML
hostil, vista previa bajo la política de seguridad de producción y navegación,
con API simulada y sin enviar correos reales.

El panel interno utiliza `/api/v1/auth/admin/login`, exclusivo del personal.
La aplicación cliente utiliza `/api/v1/auth/tenant/login`, exclusivo de clientes;
`/api/v1/auth/login` se conserva como alias de este último. Cada interfaz rechaza
y revoca una respuesta de sesión del otro ámbito antes de cargar datos.
Se conservan el cambio obligatorio de contraseña y la revocación de sesiones.

**Administración → Auditoría** consulta exclusivamente registros atribuibles a
personal autenticado del SaaS. Incluye usuario, acción, entidad, referencia,
detalles y fecha/hora UTC. Las acciones recibidas desde instalaciones, el portal
cliente y las tareas automáticas no se muestran como acciones del personal.
La migración V86 clasifica los registros anteriores solo cuando puede verificar
una cuenta administrativa y una acción conocida; los registros de origen
ambiguo se conservan sin inventar una atribución.

La consulta usa `GET /api/v1/admin/audit/search` con `from`, `to`, `username`,
`action`, `q`, `page`, `size`, `sortBy` y `sortDirection`. Los días inicial y final se incluyen en UTC;
sin fechas se consulta todo el historial. Usuario, código de acción y texto
aceptan coincidencias parciales literales sin distinguir mayúsculas. El servidor
devuelve `{items, page, size, total}`, con páginas de hasta 100 filas y orden
estable de fecha descendente e identificador por defecto. `sortBy` admite
`createdAt`, `username`, `action`, `targetType`, `targetId`, `details` e `id`,
con dirección `ASC`/`DESC`, desempate por ID en la misma dirección y detalles
nulos al final. La ordenación se aplica a todo el historial filtrado y al Excel.
La pantalla deja atrás el límite
de los últimos 100 registros; la API antigua `GET /audit` se conserva para
compatibilidad.

La pantalla usa la misma `SaasDataTable` que Empresas, Tiendas y Licencias:
columnas ocultables, reordenación por arrastre o Ctrl+flechas, anchos ajustables,
menú de tres puntos, ordenación por cabecera y preferencias de columnas por
usuario en el navegador. La selección se marca en negrita, admite flechas e
Inicio/Fin, y abre el detalle de solo lectura con doble clic o Intro. Cerrar el
detalle devuelve el foco a la fila. Las cabeceras se mantienen visibles al
desplazarse y la paginación permanece debajo de la tabla. El ID de auditoría
es una columna opcional, oculta inicialmente.

La auditoría es de solo lectura: no hay operaciones API de edición o borrado.
PostgreSQL también rechaza `UPDATE`, `DELETE` y `TRUNCATE` de sus registros.
Los cambios de negocio auditados y su registro comparten transacción. Los accesos,
salidas, cambios de contraseña, conciliaciones y exportaciones relevantes se
atribuyen a la identidad verificada; no se guardan contraseñas ni tokens.

**Exportar Excel** descarga todos los resultados de los filtros aplicados,
independientemente de la página visible, mediante `GET /api/v1/admin/audit/export`.
Requiere `VIEW_ADMIN_DATA`, igual que la consulta, y acepta `language=es|en|zh`.
El XLSX conserva cabeceras, bordes completos, filas alternas y las hojas de datos
y contexto; las fechas incluyen UTC. El límite es 50.000 filas: por encima
devuelve 413 y se debe acotar el periodo o los filtros. La exportación también
deja un registro de quién la realizó y cuántas filas preparó.

La prueba `node e2e/users-audit.mjs` usa datos y respuestas de API aislados para
comprobar separación de pantallas, creación de personal sin roles, consulta de más de 100 registros, filtros,
ausencia de acciones de edición, Excel, errores, reintento y ES/EN/ZH. Las pruebas
PostgreSQL del backend comprueban autorización, atribución, aislamiento de origen
y protección del historial.
`node e2e/user-portals.mjs` comprueba el acceso cliente y rechaza respuestas de
sesión de personal, incluso cuando requieren cambiar contraseña.
`node e2e/client-access-requests.mjs` verifica solicitud en ES/EN/ZH y móvil,
validaciones, reintentos, revisión simultánea, aprobación con cero o varias
tiendas, rechazo, recuperación ante errores y gestión posterior de accesos.

## Resumen estadístico y módulos aplazados

El Resumen consulta `GET /api/v1/admin/summary` con el permiso
`VIEW_ADMIN_DATA`. Las cifras acumuladas y del periodo se calculan en el servidor,
independientemente del tamaño de los listados recientes. Muestra empresas,
tiendas, licencias, eventos de sincronización, documentos procesados y referencias
de artículos observadas en sus líneas. Estas referencias no son el catálogo
completo de las tiendas. Los importes documentales se separan por moneda y
se omiten cuando faltan relaciones necesarias para evitar duplicidades. Los
documentos cuentan las últimas versiones disponibles en la proyección central;
los eventos antiguos o pendientes de procesar siguen incluidos en sincronización,
sin inventar sus documentos ni importes.

La cobertura, las fechas de alta de las licencias recientes y la fecha de
generación del resumen se muestran en UTC. La carga del Resumen es independiente
de las pantallas antiguas y ofrece reintento sin convertir un error en cifras cero.

El Resumen permite filtrar por fechas, empresa, tienda y provincia. Incluye altas
del periodo, actividad comercial y de sincronización, catálogo de las últimas
capturas completas y gráficos diarios por moneda. Los accesos a Informes
conservan el ámbito y requieren `VIEW_REPORTS`. Las opciones del Resumen usan
`/api/v1/admin/summary/options` con `VIEW_ADMIN_DATA`.

Supervisión queda apartada en esta versión: Estado de clientes, Fallos de
tiendas, Sincronización operativa, Estado fiscal, Recuperación de entregas y
Soporte no aparecen en el menú ni en su buscador. Sus rutas directas vuelven al
Resumen. Se conservan el código, los permisos del servidor y la recepción de
datos. La compilación solo habilita esas pantallas cuando se define explícitamente
`VITE_SAAS_SUPERVISION_ENABLED=true`, reservado para futuras versiones y sus
pruebas de regresión. Este indicador de interfaz no sustituye la autorización API.

## Informes comparativos

Informes consulta `GET /api/v1/admin/reports/statistics` y obtiene sus opciones
de empresa y tienda de `GET /api/v1/admin/reports/statistics/options`. Ambos
requieren `VIEW_REPORTS`; la pantalla se carga sin depender de los permisos ni
de las respuestas del dashboard antiguo. Integraciones conserva su pantalla.

Los periodos incluyen las fechas inicial y final. La selección principal y la
comparación personalizada admiten hasta 366 días; el año anterior conserva las
fechas del calendario y puede tener una duración distinta por los años bisiestos.
Se puede comparar con el periodo anterior, el año anterior o un intervalo
personalizado, y agrupar por día, semana, mes, empresa, tienda o provincia. Los totales se
calculan sobre todos los registros seleccionados, independientemente de la
página visible. La agrupación diaria compara posiciones desde el inicio de
cada periodo y conserva los días sin actividad.
Las semanas y meses usan sus límites de calendario. Un intervalo de distinta
duración conserva las posiciones que existen en un solo periodo como no
comparables; no las convierte en ceros. La variación porcentual tampoco se
inventa cuando la base es cero.

Las ventas usan la fecha comercial original del documento (`business_date`),
que es una fecha sin zona horaria. Los eventos de sincronización usan su fecha
de recepción en UTC. Las horas de generación también se muestran en UTC.
La provincia corresponde a la dirección actual de la tienda, usando país y
código postal; las direcciones incompletas o de otros países quedan agrupadas
sin provincia española. No se atribuye una dirección histórica que no existe.

Los documentos disponibles incluyen todos sus estados. Los importes, el importe
medio por documento computable y el promedio diario conservan el signo y se
separan por moneda. Excluyen anulados y orígenes sustituidos por facturas, aunque
la factura esté fuera del periodo. Si faltan relaciones en el ámbito de empresa
y tienda seleccionado, se omiten importes, promedios y recuentos computables
de ambos periodos: una relación pendiente fuera del intervalo también podría
afectar a sus documentos.
La facturación propia del SaaS y los registros manuales del informe antiguo no
se mezclan con estos datos sincronizados.

Las siete secciones son Ventas, Artículos, Movimientos, Stock, Promedios,
Provincias y Sincronización. Ventas y Artículos permiten consultar los documentos
que componen sus cifras, con sus estados y condición de cómputo. Artículos
clasifica productos por tienda y moneda, muestra cantidades vendidas/devueltas,
importe, participación y unidades por venta del producto. La participación usa
las líneas de producto computables; no distribuye descuentos globales ni otras
líneas entre artículos. El promedio de unidades excluye rectificaciones por
diferencia sin cantidad definida. No se suman unidades de productos heterogéneos.

Los promedios por tienda y moneda usan las tiendas con documentos computables
de esa moneda. El número administrativo de tiendas y licencias corresponde al
ámbito actual, no a un censo histórico reconstruido.

Movimientos consulta los eventos `STOCK_MOVEMENT` válidos realmente recibidos,
elimina duplicados por empresa, tienda e identidad y separa fecha del movimiento
de fecha de recepción UTC. Los conflictos, tipos o cantidades inválidos se
excluyen y se informa de su cobertura. Ofrece desglose por tipo, almacén, tienda,
provincia o día y un detalle ordenable con cantidades decimales exactas. No
reconstruye movimientos anteriores al inicio de la recepción central. En la
sección Sincronización, los eventos de stock siguen contando recepciones, no
movimientos únicos ni existencias físicas.

## Consultas, columnas y exportaciones

### Presentación compacta del Resumen y los informes

La opción visual «Gestión compacta» conserva la cabecera UTC, la navegación y los
controles empresariales de esPOS. El Resumen presenta primero empresas, tiendas,
licencias y artículos recibidos; después, la actividad del período, la evolución
de ventas, la sincronización por tipo y las licencias recientes. El desglose por
tipo identifica expresamente su alcance acumulado. Las cifras adicionales y la
cobertura completa se consultan en un detalle desplegable.

Ventas y Provincias muestran la comparación por moneda antes del gráfico y la
tabla. El pie de la tabla contiene el total de todo el período, aunque se navegue
por páginas; el gráfico indica que representa los grupos de la página actual.
Los recuentos adicionales y la cobertura permanecen accesibles por teclado. Si
faltan relaciones necesarias para calcular importes, se mantiene un aviso visible
antes de los resultados. Un valor desconocido no se convierte en cero.

Las series usan números aproximados únicamente para dibujar su geometría; al
consultar un punto se conserva el decimal exacto de la API. Cada gráfico tiene
una sola parada de tabulación y permite recorrer los puntos con las flechas,
Inicio y Fin. Los ejes adaptan su densidad al ancho disponible. Los nombres
abreviados del eje conservan su texto completo al consultar el punto y en la tabla.
Esta presentación mantiene filtros, consultas guardadas, columnas, ordenación,
paginación, consulta de documentos, exportaciones y traducciones ES/EN/ZH.

Desde y Hasta comparten un calendario de intervalo en Resumen e Informes,
también para la comparación personalizada. Elegir el inicio pasa a seleccionar
el fin en la misma ventana y resalta el período completo. Aceptar actualiza ambos
campos; Cancelar, Escape o salir del calendario descartan la selección pendiente.
Los filtros se consultan al pulsar Aplicar filtros. Se pueden recorrer días con
flechas, semanas con Inicio/Fin y meses con Re Pág/Av Pág. Las fechas conservan
su valor de calendario, sin desplazamientos por la zona horaria del navegador.

### Persistencia y descarga

Las consultas guardadas y columnas visibles se conservan por usuario y sección
en este navegador. No almacenan credenciales ni resultados de informes. Las
tablas conservan sus cabeceras sin datos; los totales se calculan antes de paginar.

La interfaz exporta únicamente Excel (XLSX), con cabeceras azul oscuro, filas
alternas, filtros y primera fila inmovilizada en las hojas de datos y contexto.
Los formatos «Informe legible» y «Detalle completo» mantienen todas las filas
filtradas, con su orden, contexto de fechas,
cobertura y totales, independientemente de la página visible. Mantienen las
monedas separadas y los valores desconocidos vacíos. Los decimales y códigos que
Excel podría redondear se conservan como texto exacto. Requieren `VIEW_REPORTS`.

Empresas, Tiendas y Licencias activas incluyen también «Exportar Excel» junto a
las acciones de su cabecera. Exportan todas las filas filtradas, en el orden
aplicado, y todas las columnas de la tabla, incluidas las ocultas. El archivo
usa el mismo formato visual, con bordes completos y hojas Datos/Contexto,
etiquetas ES/EN/ZH y fechas UTC. Estos directorios requieren `VIEW_ADMIN_DATA`,
igual que su consulta, sin exigir permisos de edición ni `VIEW_REPORTS`.
Tiendas y Licencias se recorren en el servidor, sin limitar la descarga a las
filas cargadas por el desplazamiento de la tabla. Empresas envía los IDs de
su lista completa ya filtrada y ordenada; el servidor relee los campos
autorizados, sin aceptar valores de filas enviados por el navegador. Cambiar
filtros, idioma, sesión o pantalla cancela la descarga pendiente para evitar
entregar un archivo correspondiente a otra vista.

Los contadores de Tiendas y Licencias de Empresas proceden del servidor e incluyen
todos los estados; abren los listados filtrados por empresa. NIF y datos de contacto
se pueden copiar con confirmación. Tiendas prioriza código y nombre, ofrece enlaces
a la empresa y a sus licencias (incluido el historial) y permite filtrar configuración
comercial pendiente o completa: faltan perfil, precio, periodicidad o vencimiento;
un precio cero y un vencimiento pasado siguen siendo datos configurados.
Licencias adapta su título al estado y ofrece vencimientos en los próximos 7 o 30
días desde el instante actual UTC. Renovar abre la sección existente y requiere
`RENEW_LICENSE`; no realiza una escritura hasta enviar el formulario. Los enlaces
internos y Volver conservan filtros, orden, desplazamiento y foco durante la sesión.
Las nuevas posiciones por defecto de columnas respetan las reordenaciones del usuario.

El servidor mantiene una lectura consistente, recorre páginas acotadas y genera
un archivo temporal que elimina tras la descarga o un error. Admite dos
exportaciones concurrentes y un máximo de 50.000 filas por archivo; si se supera
el límite se solicita reducir los filtros, sin entregar un archivo truncado.

## Catálogo y existencias

`#/reports/inventory` abre la sección Stock de Informes, con el mismo permiso
`VIEW_REPORTS`. Consulta `/api/v1/admin/reports/inventory/overview`, `/history`,
`/products`, `/warehouses` y `/balances`. Los totales de cobertura y catálogo se
calculan en el servidor antes de paginar. Los productos se cuentan por tienda;
no se deducen identidades comunes entre catálogos de tiendas distintas.

Los datos proceden de capturas completas de `producto`, `almacen` y `existencia`
en la base local. Se incluyen artículos sin movimientos e inactivos, y saldos
registrados con signo y tres decimales. Una posición ausente no se presenta como
cero ni se suman cantidades de productos heterogéneos como si fueran una misma
unidad. La captura no recalcula ni modifica las existencias operativas.

La tienda publica `INVENTORY_SNAPSHOT` mediante su outbox habitual, con hasta
500 filas por fragmento y un máximo de 10.000 fragmentos o 5.000.000 de filas
por captura. Cada fragmento repite la identidad, fecha y recuentos
de la captura. El SaaS mantiene la última captura completa mientras llega otra;
solo publica la nueva tras validar todos los fragmentos, recuentos y referencias.
La captura vacía es explícita. El emisor exige `PROJECTED` para cada fragmento
almacenado y reintenta si un receptor antiguo todavía lo clasifica `IGNORED`.
Tras confirmar la entrega se compacta el contenido del fragmento en el outbox
local, conservando sus metadatos de envío. Los fragmentos pendientes o fallidos
mantienen el contenido necesario para reintentarlos.

La captura es consistente y periódica en tiendas vinculadas con credencial
propia. Requiere el worker de sincronización y su URL central configurados.
`tpv.sync.inventory-snapshot.enabled` permite desactivarla; el valor por defecto
es `true`. La cadencia predeterminada es una hora
(`tpv.sync.inventory-snapshot.cadence-ms=3600000`), con comprobación cada minuto
(`check-delay-ms=60000`) y primera comprobación a los 30 segundos
(`initial-delay-ms=30000`). No genera otra captura mientras haya fragmentos
reintentables pendientes de la misma instalación. Respeta el cierre de tareas
durante un traslado de servidor.

La pantalla distingue la fecha UTC de captura de la fecha UTC de recepción
completa. Una tienda sin captura de la instalación actual se identifica como
sin datos; no se inventan cifras cero. El historial solo contiene capturas
completas realmente recibidas y señala las de instalaciones anteriores.
Las pestañas, filtros y páginas conservan la misma captura durante la consulta.
«Consultar última captura» busca la más reciente; «Actualizar» hace lo mismo
salvo que se haya seleccionado expresamente una captura del historial.
No reconstruye existencias históricas anteriores a la activación ni certifica
que una captura antigua siga representando las existencias en este momento.

La comparación elige dos capturas completas de una misma instalación y muestra
altas, bajas y cambios del catálogo y de sus posiciones de stock. Una posición
ausente permanece desconocida; cambiar el tipo de unidad impide calcular una
diferencia de cantidades. Los cambios globales del catálogo se refieren a ambas
capturas completas. Provincias también muestra la cobertura de catálogo y
existencias por provincia actual de las tiendas.

Esta fase requiere las migraciones aditivas V85 del SaaS y V290 del backend
local (secuencia de release 45). Debe actualizarse primero el receptor central
y después el emisor local.
Los contratos de captura se conservan en
`contracts/sync/inventory-snapshot-v1.json`.

La versión registrada en `package.json` se muestra en los portales interno y de
tiendas. Durante las pruebas se utiliza `0.x.x`, actualmente `0.1.0`; el primer
despliegue iniciará la serie `1.x.x` con `1.0.0`. El registro debe coincidir con
la versión raíz de `package-lock.json`.

```sh
npm ci
npm run dev
```

Abre http://127.0.0.1:5175/: muestra directamente el acceso de administración.
Las llamadas `/api` usan el backend SaaS del puerto 8090.
Configuración del backend y despliegue: [backend-saas/README.md](../backend-saas/README.md).

La web comercial está separada en [frontend-product](../frontend-product/README.md)
y usa el puerto 5176. No se incluye en el build del panel.

Los antiguos enlaces `#/producto` en el puerto del panel pasan al acceso
de administración. Para consultar el producto, utiliza la dirección de la web pública.

Verificación: `npm test`, `npm run build` y `npm run test:e2e`.

## Presentación del acceso de clientes · 2026-11-19

`tienda.html` utiliza `TenantLoginScreen`, con el logotipo oficial, una cabecera
adaptable y el selector de idioma alineado a la derecha. El selector compartido
conserva su comportamiento; las clases `tenant-login-*` adaptan su presentación
al acceso de clientes sin alterar la barra lateral ni la entrada administrativa.
El formulario muestra usuario de tienda y contraseña, conserva el autocompletado
y explica el uso del portal en ES, EN y ZH. La descripción de funciones indica
que su disponibilidad depende de los permisos de la cuenta.

El contenido se distribuye en dos columnas en escritorio y una en móvil. Se
eliminan para esta pantalla el ancho mínimo de 1024 px y el posicionamiento
absoluto del formulario. El desplazamiento vertical permite acceder a los
controles cuando aparece un aviso o disminuye la altura disponible. Se mantienen
el login de cliente, el rechazo y revocación de sesiones internas y el cambio
obligatorio de contraseña. No se modificaron endpoints, credenciales ni datos.

Compilación de ambas entradas aprobada y 18 comprobaciones existentes de
autenticación, seguridad, contraseñas e idiomas correctas. Verificación visual
con Cua en 1280 × 720, 821 × 800 y 390 × 844; cambio de idioma, menú, Escape y
foco visible. Un harness aislado del componente real permitió revisar un aviso
simulado en 320 × 740 y 740 × 390, sin enviar credenciales ni llamar a la API:
sin desbordamiento horizontal, aviso anterior al formulario y botón alcanzable
con Tab. Las capturas están en `output/tenant-login-20261119/` del repositorio;
el harness está fuera del build de producción y su servidor temporal se detiene
al terminar la revisión.

## Estilo compartido del acceso interno · 2026-11-19

El acceso interno y el cambio obligatorio de contraseña utilizan ahora
`PortalLoginLayout`, con la misma cabecera, fondo, tarjeta y comportamiento
adaptable del acceso de clientes. La presentación compartida conserva las clases
visuales `tenant-login-*`; el estado y las llamadas de autenticación siguen en
cada aplicación. Se mantienen las clases de formulario usadas por los E2E y el
identificador `password-change-title`. El reloj permanece en el pie, dejando
espacio al selector de idioma en la cabecera móvil.

Los campos conservan el autocompletado y, junto al botón principal, alcanzan 48 px de altura;
la salida del cambio de contraseña tiene al menos 44 px. Durante una petición
se deshabilitan los campos y se evita el reenvío del formulario. El acceso
inicial no enfoca automáticamente un campo para evitar abrir el teclado móvil
al cargar. Los avisos conservan `alert/status`, los controles de credenciales y
la cancelación del cambio obligatorio conservan sus callbacks. No se habilita
la recuperación de contraseña que actualmente no tiene controles públicos.

El destino para clientes se aclara como **Venta TPV web**, no el portal tenant
de SaaS. El enlace del acceso interno utiliza el mismo validador puro que la web
informativa (`frontend-product/src/portalAccess.ts`): en local abre
`http://127.0.0.1:5173/`; para publicar, configura `VITE_CLIENT_PORTAL_URL` en
ambos frontends. Sin un destino válido fuera de localhost no se ofrece un
enlace falso. El acceso interno permanece en `http://127.0.0.1:5175/#/login`.

La prueba local de Venta utiliza su configuración de terminal existente y el
backend de tienda en 8080; SaaS mantiene su backend en 8090. La distribución web
de Venta registra cada navegador como un puesto y exige aprobación de la tienda.
No utiliza las credenciales DEV ni cambia cupos o permisos. La publicación HTTPS
y el registro se describen en [Venta desde el navegador](../docs/venta-browser-production.md).

Comprobación de esta fase: ambas entradas SaaS compiladas, 18 contratos de
autenticación/seguridad/contraseñas/idiomas aprobados y 34 comprobaciones de
destinos de acceso aprobadas en la web informativa. Revisión Cua de la entrada
interna en 1280 × 720, 390 × 844 y 821 × 800, con ES/EN/ZH. El fixture aislado
permitió comprobar avisos, carga y cambio obligatorio en 320 × 740 y 740 × 390:
sin desbordamiento horizontal, controles de salida y envío alcanzables con Tab,
campos deshabilitados durante la petición y menú de idioma dentro de la vista.
No se enviaron credenciales ni se cambiaron contraseñas durante la revisión.
Capturas en `output/internal-login-20261119/`; servidor de fixture detenido.
Se verificó por navegación real que el acceso comercial llega al login de Venta
en 5173, con el backend de tienda conectado. La base local ya estaba en V289 y
el arranque no aplicó migraciones ni restableció credenciales.

## Bandeja comercial de solicitudes de demo

Tras iniciar sesión con una cuenta interna, abre **Clientes → Solicitudes de
demo** (`#/demo-requests`). Requiere `VIEW_ADMIN_DATA` para consultar y
`MANAGE_OPERATIONS` para cambiar el estado. No está disponible para cuentas
de tienda y no usa la API pública para consultar datos personales.

La bandeja muestra páginas de 20 solicitudes, ordenadas por recepción más reciente,
con filtros Nueva / Contactada / Cerrada y búsqueda por referencia UUID, nombre,
empresa o email. La validación de sesión y acceso a la bandeja son independientes
de la disponibilidad del resumen general.
El detalle conserva todas las aplicaciones solicitadas, mensaje, contacto, fecha,
idioma y consentimiento. Se puede marcar una solicitud como contactada, cerrarla
o reabrirla; la interfaz solo confirma el cambio después de la respuesta del servidor.
Cada cambio envía el estado leído como `expectedStatus`: si otro operador lo ha
modificado, se bloquean los botones hasta actualizar y revisar el detalle. No se
reintenta una escritura en conflicto automáticamente. GET y PUT tienen un plazo
de 20 segundos que incluye la lectura completa del cuerpo de la respuesta.

El seguimiento es manual: estos cambios de estado **no envían emails ni mensajes**.
El equipo debe revisar la bandeja y contactar fuera de ella. No se ha configurado
un proveedor de notificaciones ni se han añadido destinatarios comerciales.

El backend debe exponer `GET /api/v1/admin/demo-requests/page` y
`PUT /api/v1/admin/demo-requests/{id}/status`. La API de consulta admite `page`
(base cero), `size`, `status` opcional y `query` opcional (máximo 160 caracteres).
Las credenciales siguen el transporte Bearer y el vencimiento de sesión existentes.

### Responsable, notas y próxima acción

La tabla añade una sola columna de seguimiento: responsable, resumen de próxima
acción y fecha, con indicación de vencimiento en solicitudes abiertas. El texto
completo se consulta en el detalle. El formulario usa dos columnas y pasa a una
en pantallas pequeñas; las notas históricas permanecen en un desplegable cerrado.

El responsable se elige entre cuentas internas activas con `MANAGE_OPERATIONS`.
Se puede dejar sin asignar o retirar la asignación. Consultar requiere
`VIEW_ADMIN_DATA`; guardar el seguimiento requiere además `MANAGE_OPERATIONS`.
El permiso histórico del cambio de estado no se modifica.

La próxima acción admite hasta 500 caracteres y una fecha opcional. Una fecha
necesita una descripción de la acción. La entrada usa la hora local del equipo
del operador y se transmite como un instante UTC; se conserva la precisión de la
fecha guardada si no se edita. El indicador de vencimiento se revisa cada minuto,
usa el reloj del dispositivo y no es una alerta automática ni una cita reservada.

Cada nota interna admite hasta 2.000 caracteres y se añade con autor y fecha del
servidor. No sustituye al mensaje del cliente ni a notas anteriores. El detalle
muestra las 100 más recientes y avisa cuando hay otras anteriores; no se borran
por ese límite de presentación. No se han añadido edición ni borrado de notas.

El guardado envía `expectedVersion` para el seguimiento, independiente del estado
de la solicitud. Un conflicto 409 impide otra escritura hasta actualizar y revisar
el detalle. El borrador se conserva para poder leerlo o copiarlo; descartarlo es
una acción explícita. No hay reintento automático de una escritura en conflicto,
ni guardado en almacenamiento local del navegador. Guardar el seguimiento o
registrar una solicitud no envía mensajes al cliente.

Endpoints adicionales, con respuestas `no-store`:

- `GET /api/v1/admin/demo-requests/assignees`: nombres de usuario elegibles,
  sin credenciales ni datos de configuración de las cuentas.
- `GET /api/v1/admin/demo-requests/{id}/follow-up`: solicitud y notas internas.
- `PUT /api/v1/admin/demo-requests/{id}/follow-up`: responsable, próxima acción,
  fecha, nota opcional y versión esperada. La actualización, la nueva nota y la
  auditoría se confirman en una misma transacción. La auditoría general contiene
  identificadores y tipo de cambio, sin el texto de la acción, nota o contacto.

Este código requiere la migración versionada
`backend-saas/src/main/resources/db/migration/V82__marketing_demo_followup.sql`
y el backend actualizado. Preparar la migración no significa que esté aplicada a
la base de la aplicación. La revisión visual en navegador continúa pendiente por
el bloqueo de acceso; las pruebas de código no certifican la geometría real.

`node --test test/demo-follow-up.test.mjs test/demo-request-inbox.test.mjs`
comprueba fechas locales/UTC, cambios de horario, vencimientos, traducciones y
contratos de sesión, guardado y borradores. Se complementa con `npm test`, la
compilación y las pruebas backend de permisos, validación y concurrencia.

Validación de esta fase: `npm test` 111/111 y `npm run build` aprobados. En backend,
62 pruebas unitarias, de controlador, repositorio y permisos, más los 8 casos de
`DemoRequestPostgreSqlTest`, aprobados sin omisiones. La integración usó un clúster
PostgreSQL 18.4 desechable en `127.0.0.1:65432`, una base de pruebas y un esquema
aleatorio; no se usó `tpv_erp_saas` ni se cargó el `.env` de la aplicación. Se
comprobaron las migraciones hasta V82, la lectura de los campos, la conservación
del seguimiento al cambiar estado, un único ganador y una única nota ante dos
editores, el reintento con versión antigua y la reversión completa si falla la
inserción de una nota. El servidor temporal se detuvo al terminar. El backend y
la base de la aplicación no se han reiniciado ni migrado en esta fase.
