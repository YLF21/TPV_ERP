# QA de Gestión · estado conjunto

Resultado visual original del 16/09/2026: aprobado. La validación integrada posterior tiene su estado actualizado en [Integración local de Resumen y Alertas](docs/gestion-integracion-2026-09-17.md).

Alertas de control y Resumen están implementados y su validación local final está cerrada. Resumen cuenta todas las operaciones lógicas válidas, incluidas devoluciones y rectificativas, y calcula la media sobre ventas netas. La evidencia original y la primera integración se conservan como históricas en este documento; los resultados finales figuran a continuación. No constituyen una comprobación del despliegue de tienda.

## Estado actual · continuación del 17/09/2026

- Estado integrado y evidencias vigentes: [gestion-integracion-2026-09-17.md](docs/gestion-integracion-2026-09-17.md). La instancia PostgreSQL temporal actual utiliza el puerto **15434**, migraciones hasta **V248** y manifiesto DEV `tpv-erp-dev-v248`, secuencia **14**. El puerto 55432 y V246 que figuran más abajo pertenecen a la primera integración histórica.
- El hallazgo de estado VERI*FACTU está corregido: lectura transaccional sin escrituras y valores efímeros si falta configuración; la UI distingue indisponibilidad. Validación de ese cambio: **9 pruebas unitarias y 2 PostgreSQL aprobadas**. No se modifican activación, permisos ni reglas fiscales.
- Resumen revisado a **1280 × 720** sin desbordamiento horizontal: área de trabajo de **1021 px** y `scrollWidth=1021`; filtros de **973 px** y `scrollWidth=973`; selector de almacén de **190 px**. La corrección permite reducir el ancho mínimo heredado del selector.
- El detalle de Alertas distingue fecha de eliminación y fecha de recepción del servidor, incluidas las líneas de evidencia. **36 pruebas aprobadas**, con textos ES/EN/ZH.
- Revisión CUA final con sesión real en 5184 a **1280 × 720**: cronología de 60 alertas y detalle de eliminación con ambas fechas y versión de regla legibles al desplazar el panel. La captura se emitió en la conversación, sin archivo exportado.
- La selección de la regla vigente al ocurrir el evento fue aprobada por el usuario y está implementada y verificada. El lote final de backend aprobó **172/172 pruebas**: 11 históricas, 14 de entrega PostgreSQL, 8 de contexto MVC, 4 de servicio, 17 del detector, 3 del servicio de alertas y 115 de documentos. Log: `.codex-tmp/gestion-fullstack-20260917/backend-deletion-historical-rules-tests-rerun.log`.
- Validación integrada final: **12/12 E2E de caja y 4/4 de Gestión aprobados**, más **10/10 repeticiones** del caso de ocho POST simultáneos. Caja cubre recuperación tras desconexión/cierre de pestaña, respuesta perdida sin duplicado, fallo local conservando carrito y regla histórica con umbral cambiado de 2 a 5 durante la desconexión. Logs: `.codex-tmp/gestion-fullstack-20260917/sale-e2e-final.log`, `gestion-e2e-final.log` y `sale-e2e-concurrency-final.log` en el mismo directorio. La prueba de persistencia Electron cubre tres procesos sin ventana; no acredita reinicio completo de la UI ni hardware físico.

## Primera integración real · 17/09/2026 · evidencia histórica

- APP GESTIÓN real en 5184, API en 18084 y PostgreSQL aislado en 55432. Sin interceptar respuestas ni conectar la UI a la base de tienda.
- Cuatro E2E aprobados: autenticación, alcance de filtros del Resumen, personalización guardada explícitamente y recuperada en otra sesión, cronología y periodo de siete días. Las pruebas preparan/restauran preferencias y admiten una distribución vacía válida.
- Lectura real de ventas: 1363,70 EUR, 115 operaciones y media 11,86; comparación diaria y diez productos obtenidos del servidor.
- Corregido el enlace JDBC de fechas que impedía registrar un vaciado de carrito. Petición HTTP genera exactamente una alerta; repetición idéntica no genera otra. La pantalla muestra fecha, importe, producto y detalle; revisión y comentario se conservan tras recargar e iniciar sesión.
- Captura de la aplicación completa a 1280 × 720 emitida por CUA: filtro, cronología y detalle visibles, con desplazamiento de tabla/detalle conforme al tamaño disponible. No se ha exportado un archivo de esa captura.
- 48 pruebas de manifiesto/guard y seis de registro/limpieza aprobadas; arranque con las 242 migraciones hasta V246 correcto. No se relajaron controles del guard fiscal.
- Hallazgo de aquella primera integración: la consulta de estado de VERI*FACTU podía devolver 500 al ejecutar `insertIfMissing` sin transacción. Se corrigió en la continuación posterior mediante lectura sin escrituras y tratamiento de indisponibilidad en UI; su validación actual figura arriba y en `docs/gestion-integracion-2026-09-17.md`.

## Resumen · revisión visual original del 16/09/2026 · evidencia histórica

- Referencia aprobada: `C:/Users/YLF/.codex/generated_images/01a0aaf4-b5c4-7c22-8e46-d1c9b4b78790/exec-6856386e-a475-4a7b-9090-7bc718b011b6.png`, 1487 × 1058. Abierta antes de implementar y comparada con la pantalla real en modo personalización.
- Escenario aislado: `http://127.0.0.1:5183/`. Comparación conjunta: `http://127.0.0.1:5183/?compare`; imagen e iframe de 1487 × 1058 reducidos por igual. Se abrió Personalizar dentro del iframe para comparar el mismo estado. Referencia copiada a `output/dashboard-review/reference.png`, ignorada por Git.
- Capturas completas de la implementación y comparación conjunta emitidas por CUA en esta tarea; la herramienta no devolvió un archivo exportado. Se revisaron también 1280 × 900, inglés, chino, estado vacío y perfil sin permiso de ventas.
- Se conserva el shell de Gestión y sus tipografías. La cuadrícula, panel lateral, selectores de representación y controles de guardado siguen la propuesta. El ranking usa unidades netas; la referencia simulada incluía importes por producto que no se pueden atribuir fielmente a los ajustes históricos. Se conservan promociones reales y alertas con su estado, sin controles ficticios de familia o formas de pago.
- P2 corregido: la gráfica inicialmente tenía 368 px de contenido para 262 px disponibles y ocultaba el eje inferior. Ahora mide el espacio con ResizeObserver; la captura final muestra 254 px disponibles y 254 px de contenido, con todos los ejes visibles. No se alteran los tamaños guardados para solucionar el recorte.
- P2 corregido: la escala de un periodo vacío redondeaba las marcas a 0/0/1/1/1. Ahora conserva decimales cuando el intervalo es pequeño. La media sin operaciones muestra un guion y no inventa una comparación porcentual.
- P2 corregido: el resumen de alertas devuelve actividad de todos los estados; el bloque se llama Alertas de control y distingue Actividad reciente con estados explícitos, en vez de presentar las cerradas como pendientes.
- Interacciones aprobadas: cancelar revierte barras, anchura y bloque añadido; guardar conserva barras y orden tras recargar; fallo de guardado conserva borrador y el segundo intento funciona; otro usuario mantiene configuración independiente; filtros de siete días y almacén cambian datos; error de lectura conserva cifras con aviso; reintento recupera; fechas invertidas se rechazan; acceso a alertas invoca la navegación.
- El catálogo del perfil sin permiso de ventas contiene solo alertas. ES/EN/ZH no muestran claves de traducción pendientes. Las barras, líneas y tabla tienen comportamiento real sobre las respuestas del escenario.
- Resultado visual: aprobado, sin P0/P1/P2 pendientes. Diferencias P3 respecto a la imagen: espaciado y tipografía ajustados al ERP existente; las tablas largas emplean desplazamiento dentro de los tamaños elegidos por el usuario.
- Revisión final: corregidos el periodo relativo congelado tras medianoche y el punto anterior invisible en una gráfica de un solo día. Regresiones automatizadas para «Hoy»/«Este mes» al cruzar medianoche local y mes, rango personalizado conservado y comparación de un día activada/desactivada. Captura final a 1280 × 900 confirma ambos marcadores visibles. Operaciones e Importe medio incluyen explicaciones accesibles ES/EN/ZH, manteniendo los valores de la API.
- 44 pruebas frontend y 52 backend distintas aprobadas; las últimas pasadas ejecutaron las pruebas afectadas. Build final TypeScript/Vite de Gestión correcto. El endpoint tiene 25 casos entre servicio, MVC, consultas e integración servlet/PostgreSQL. Revisión estática adicional sin defectos bloqueantes pendientes; detalle en `docs/gestion-resumen-2026-09-16.md`.

# QA visual · Alertas de control

Resultado del escenario visual original del 16/09/2026: aprobado. Los conteos y capturas de esta sección corresponden a esa fase; la continuación del 17/09 se recoge en el estado actual superior y en el informe de integración.

## Referencia y evidencia

- Referencia seleccionada: `C:/Users/YLF/.codex/generated_images/01a0aaf4-b5c4-7c22-8e46-d1c9b4b78790/exec-b594ae22-caab-4758-9631-2ab7ab5b3588.png` (tercera imagen revisada).
- Dimensiones originales verificadas: 1487 × 1058 píxeles.
- Implementación: `http://127.0.0.1:5182/`, pantalla y shell reales dentro de un escenario sin conexión al backend de tienda.
- Comparación conjunta: `http://127.0.0.1:5182/?compare`; referencia e iframe de la aplicación al mismo ancho de 1487 CSS px y alto de 1058 CSS px, reducidos de manera equivalente para verse juntos. Captura conjunta final y capturas completas de regiones legibles realizadas con CUA en esta tarea.
- Capturas de implementación: CUA entregó las imágenes dentro de la conversación, sin ruta de archivo exportada. El escenario y la URL de comparación permiten reproducirlas. Copia local de la referencia para esa vista: `output/control-alerts-review/reference.png` (artefacto ignorado por Git).
- Viewports revisados: 1487 × 1058 y 1280 × 900. Densidad CSS 1:1 en las capturas completas; la comparación conjunta usa la misma reducción en ambos lados. Sin marco de dispositivo.
- Estado principal: español, usuario con permisos, ocho alertas ficticias, periodo de siete días, agrupación diaria y detalle de precio 10 → 12 EUR. Los identificadores, horas y días de las filas pertenecen al fixture; se compara estructura y presentación, no equivalencia de datos reales.

## Iteraciones y correcciones

1. Primera captura: clave `applyDates` sin traducir, resumen de precio genérico y reducción negativa para subidas. Se tradujo el botón, se mostró original → aplicado en la fila y variación +2 EUR / +20 % en el detalle.
2. El detalle repetía metadatos y desplazaba las acciones principales. Se eliminaron duplicados editables, se colocó el documento tras la evidencia, se plegaron vencimiento/motivo y se situó revisión antes del historial. Se aumentó a 13 px el texto operativo.
3. Se corrigió el marco del fixture para respetar la fila superior y evitar cortar la paginación. Se capturaron de nuevo ambos tamaños; los paneles y sus controles permanecen accesibles mediante desplazamiento propio cuando el espacio disminuye.
4. Comparación conjunta final con referencia abierta y captura completa de detalle/tabla: sin hallazgos P0/P1/P2 pendientes.

## Superficies verificadas

- Tipografía: se conserva Segoe UI / Microsoft YaHei UI del ERP. Jerarquía visible de título, listado, tipo de alerta, precio y metadatos; texto operativo de 13 px. Nombres largos se ajustan y los resúmenes conservan acceso al detalle. Chino e inglés revisados en navegador.
- Espaciado y composición: barra superior, fechas/filtros, franja de tipos y distribución aproximada 62/38 entre lista y detalle. A 1280 px las dos barras se apilan y la tabla conserva desplazamiento horizontal para respetar los anchos de usuario. Paginación fija dentro del panel. Detalle con desplazamiento propio.
- Colores: azul marino empresarial, paneles blancos, selección azul clara, iconos semánticos rojos/azules/grises y estados legibles. Se mantiene el shell existente del ERP; no se sustituye su navegación por la del prototipo.
- Activos: iconos vectoriales de Phosphor existentes, sin dependencias ni imágenes decorativas nuevas. El diseño es interfaz; no necesita fotografías ni ilustraciones rasterizadas. Referencia y captura conservan nitidez suficiente en revisión completa.
- Contenido: evidencia de precio real, regla y versión, estado, fechas y usuario; traducciones ES/EN/ZH. La barra de datos ficticios pertenece solo al escenario aislado y no se añade al producto.

## Interacciones verificadas

- Filtro de precio muestra tres filas y vuelve al conjunto global.
- Revisión de alerta actualiza estado y conserva comentario en el historial; revisión repetida queda deshabilitada.
- Documento relacionado se abre y puede cerrarse.
- Configuración admite el umbral 12,5 % y lo refleja al guardar.
- Personalización se guarda, se recupera al recargar y mantiene preferencias distintas para otro usuario ficticio; el aislamiento real se verifica en PostgreSQL.
- Perfil lector en chino conserva consulta/personalización y oculta gestión/reglas/documentos sin permiso.
- Estado vacío en inglés con mensajes y paginación coherentes.
- Fallo de API simulado: aviso y reintento, ocho filas conservadas; recuperación elimina el aviso.
- Consola revisada: las advertencias de montaje duplicado aparecieron durante HMR del fixture y se corrigieron añadiendo limpieza al desmontar. No se registraron nuevos errores tras la recarga final. Ningún error de consola de la pantalla pendiente.

## Validación complementaria y límites

Validación original del 16/09: 41 pruebas frontend aprobadas y compilación TypeScript/Vite de esa fase correcta después del ajuste de vencimiento a la zona de tienda. 247 pruebas backend aprobadas en aquel lote; no son el conteo actual de la continuación. Detalles históricos en `docs/gestion-control-alerts-2026-09-16.md` y estado vigente en `docs/gestion-integracion-2026-09-17.md`.

El escenario visual inicial intercepta todas las peticiones; la integración del 17/09 se documenta por separado arriba. No se verificaron hardware de cobro, datos de producción ni despliegue. El marco ERP conserva su mínimo global de 1024 px y Electron su mínimo de 1050 px. El fixture elimina solo para su página ese mínimo heredado para poder visualizarlo en un panel de navegador menor; no modifica el mínimo del producto. En pantallas estrechas se respeta el desplazamiento del ERP; no se ha diseñado una aplicación móvil nueva. La revisión del nuevo Resumen figura en su apartado propio.

Al cerrar el escenario visual original no quedaban correcciones visuales bloqueantes. Como refinamiento P3 se identificó la posibilidad de compactar el nombre de algunos tipos largos, conservando el nombre completo en configuración y detalle.

## Alertas · revisión del 17/09 con detalle en ventana

La decisión posterior del usuario reemplaza la composición lateral descrita arriba. El listado ocupa todo el ancho y añade «Comentario de revisión» al final. Un clic selecciona; doble clic o Enter abre una ventana con evidencia, revisión e historial. Los iconos se muestran en las tarjetas de reglas y en el editor; no se incorpora personalización de iconos.

CUA revisó la aplicación real en 5184, PostgreSQL temporal 15434, a 1280 × 720: venta de prueba 001-260917-01051, dos reglas de descuento, una Revisada con comentario y otra Nueva; detalle con nombre/código de producto y terminal SERVIDOR PRUEBAS; Escape devuelve el foco a la fila. Configurar reglas y Modificar regla muestran los iconos correspondientes. Ventanas con desplazamiento propio y cierre visible; la tabla mantiene desplazamiento y preferencias de columnas. Capturas emitidas en la conversación, sin archivo exportado ni override de viewport añadido en esta revisión.

43 pruebas UI cubren además idiomas ES/EN/ZH, permisos, detalle anidado y retorno de foco/desplazamiento. El E2E integrado confirma 8,20 → 4,00, evidencia 51,22 %, ausencia de UUID de producto/terminal en el detalle, documento relacionado y persistencia del comentario tras otra sesión. Compilación de Gestión y presupuesto de paquetes aprobados. No quedan hallazgos visuales bloqueantes en el alcance de esta revisión.

### Corrección posterior: reapertura y espacio final

La última columna visible se estira desde el mínimo guardado por usuario. Todas las filas comparten un mínimo calculado con las columnas visibles: los comentarios largos se ajustan sin ensanchar una fila respecto a la cabecera. El E2E real a 1920 × 1080 verifica cinco columnas, margen final menor de 2 px y diferencia de anchura entre cabecera/fila menor de 2 px, también con comentario superior a 400 caracteres. Esta aserción reprodujo el defecto de ancho intrínseco y pasó tras la corrección.

CUA a 1280 × 720 verificó tabla y detalle con «Reabrir alerta» como primera acción para una alerta Revisada. El historial y el comentario siguen accesibles con desplazamiento propio. E2E verifica además reapertura de Revisada/Cerrada/Descartada, conservación del historial y persistencia tras otra sesión. El guard de enfoque inicial evita que un frame pendiente desplace el foco al abrir rápidamente el documento hijo. Nuevos textos en ES/EN/ZH; pruebas UI/API 62/62 y compilación aprobadas. Sin cambios de preferencias ni viewport durante esta comprobación visual.


## Promociones · corrección visual del 26/09/2026

### Referencia y evidencia

- Referencia aprobada adjunta a la conversación: codex-clipboard-68ea768f-7824-44bf-b2d4-645ad6261e52.png.
- Implementación: http://127.0.0.1:5187/, componentes reales PromotionListScreen y PromotionWizard dentro de AppFrame, GestionShell y gestion-module-stage. Todos los datos y las mutaciones del fixture son locales en memoria.
- Capturas: frontend/output/promotions-review/listado.png y crear.png.
- Comparaciones simultáneas: frontend/output/promotions-review/comparacion-listado.png, comparacion-crear.png y detalle-comparacion.png.
- Presentación conjunta del resultado: frontend/output/promotions-review/promociones-corregidas.png.
- Las capturas citadas son evidencia local y no se versionan. El fixture incluido se abre ejecutando `npx vite e2e/promotions-review --config e2e/promotions-review/vite.config.ts` desde `frontend`.
- Fuente: 2103 × 748 píxeles, dos ventanas. Capturas: 1090 × 756 píxeles, escala 1. Se compararon áreas de contenido de 845 × 717 y 846 × 716, sin escalado; se excluyó el menú lateral y la barra superior, cuya versión actual difiere de la ilustración.
- Estados: listado con 7 promociones, cuatro vigentes y tres caducadas, yogures seleccionados y 327 usos; creación de 3×2, vigencia 01/10/2026–31/10/2026, sin productos aún seleccionados.

### Comparaciones y correcciones

1. P1 inicial: encabezado duplicado, campos técnicos en dos columnas y formulario sin resumen lateral. Corregido con listado estrecho, franja de vigencia/tipo/usos, condiciones numeradas y formulario de seis secciones.
2. P1 en primera captura: el fixture sustituía las nuevas hojas CSS por tpv.css. Se retiraron los alias; la revisión utiliza exactamente los estilos de los componentes.
3. P2: las reglas globales imponían tabla azul oscura y disparadores de selectores grandes. Se aplicaron reglas exclusivas de Promociones y selectores compactos con iconos Phosphor.
4. P2: espaciado duplicado, parte inferior del formulario recortada y resumen corto. Se ajustaron márgenes, alturas y columna lateral; las seis secciones del ejemplo quedan visibles en la captura final.
5. P2: tipografía de condiciones y resumen demasiado pequeña. Se ajustaron tamaños, interlineado y altura de la franja de datos. La comparación final de detalle muestra títulos, condiciones y datos legibles.
6. Se corrigió la distribución de columnas de Promociones por debajo de 950 px. El ERP conserva su ancho mínimo global de 1024 px: no se declara compatibilidad móvil. A 1024 × 768, el módulo mide 780 px y no hay desbordamiento horizontal del documento ni del módulo. Evidencia: crear-1024.png.

### Superficies de fidelidad

- Tipografía: Segoe UI/Arial del entorno de escritorio, jerarquía de títulos 22/16/12 px y texto operativo 11–12 px.
- Espaciado: listado/detalle 35/65, formulario/resumen 1.8/1, bordes finos, controles de 30–32 px y separaciones compactas.
- Colores: azul marino, superficies blancas, fondo azul gris claro, selección azul clara y caducadas en gris.
- Iconos: biblioteca Phosphor y controles existentes. La referencia no contiene fotografías u otros recursos raster que deban incorporarse al producto.
- Contenido: condiciones derivadas de los campos reales, fechas e importes localizados y contador de usos proporcionado por la API.

### Diferencias funcionales conservadas

La imagen incluía Editar, canal, todas las tiendas, acumulación configurable y precio promocional unitario. Esas capacidades no están expuestas por el contrato actual del formulario/listado. No se añadieron controles sin función ni se inventó un precio unitario para promociones dependientes de cantidades. Los campos de condición y beneficio representan las opciones reales de cada tipo; la navegación exterior actual se conserva.

### Validación

- 33 pruebas focalizadas aprobadas: modelo del formulario, validación/envío, listado, búsqueda por acentos, selección, filtro de estado, acciones, caducadas, productos y guard de traducciones de Gestión.
- APP GESTIÓN y APP VENTA compilan; compilación Vite de Gestión repetida después de los ajustes visuales.
- Browser: búsqueda y limpieza, apertura, fechas mediante teclado, selección/aplicación de un producto, guardado de un borrador 3×2 y regreso a su detalle con fecha correcta y un producto.
- Consola de una sesión limpia: sin errores ni advertencias. Se corrigió la reutilización de createRoot del fixture para las recargas en desarrollo.
- git diff --check correcto. La validación visual no conecta con la base de datos de tienda.
- Sin diferencias visuales P0/P1/P2 pendientes dentro del alcance de escritorio y de los contratos existentes. Pulido P3: singular/plural de algunos contadores.

final result: passed

## Productos con promociones · pantalla compartida · 26/09/2026

### Alcance corregido

La ruta stock.promotions renderiza StockPromotionGroups desde StockScreen tanto en APP VENTA como en APP GESTIÓN. La entrega anterior había modificado únicamente el listado de administración y el formulario. Esta corrección alcanza la pantalla compartida pendiente.

- Lista sin tabla de promociones, con búsqueda y estado, selección estable y navegación con flechas/Home/End.
- Promociones de todos los estados; las caducadas se ordenan al final y se muestran en gris claro.
- Detalle con vigencia, tipo y veces utilizada; condiciones numeradas antes de la tabla de productos.
- PVP y stock junto al nombre; familia y subfamilia conservadas, con desplazamiento horizontal, ordenación y preferencias de columnas.
- Se conservan los filtros de artículos y las acciones existentes de Stock. El informe Excel existente conserva su contrato anterior.
- StockScreen completa la paginación de productos en esta ruta y evita el filtro cliente de etiquetas ACTIVE, que ocultaba los productos vinculados solo a promociones inactivas. Las peticiones de páginas se cancelan al cambiar de consulta; un fallo evita mostrar un detalle incompleto.
- Los textos de presentación comunes se extraen a promotionPresentation.ts, conservando los exports previos de PromotionListScreen.

### Comparación visual

Referencia: codex-clipboard-68ea768f-7824-44bf-b2d4-645ad6261e52.png, opción aprobada. Lista estrecha con selección azul, fichas blancas sobre fondo azul gris, fechas/tipo/usos, condiciones ordenadas y tabla de cabecera clara. Se conserva la navegación real de cada app y los controles de stock, ausentes de la ilustración.

El primer render mostró el encabezado GESTIÓN duplicado y estilos globales que reservaban una fila vacía tras recargar. Se corrigieron con selectores limitados a stock-promotions-screen y se repitió la captura tras recarga completa. PVP y stock se adelantaron en el orden inicial para quedar visibles antes de familia/subfamilia. Las preferencias existentes del usuario se conservan.

Evidencia del componente y contenedores reales, usando datos simulados sin conexión al backend de tienda:

- frontend/output/promotions-review/compartida-gestion.png (1280 × 800).
- frontend/output/promotions-review/compartida-venta.png (1280 × 800).
- frontend/output/promotions-review/compartida-caducada.png: promoción INACTIVE caducada con un producto de la segunda página.
- Preview: http://127.0.0.1:5187/?view=stock&app=gestion y app=venta.

### Comprobaciones

- 122 pruebas pasan en 8 archivos: StockPromotionGroups, StockScreen.promotions, StockScreen, filtros y ordenación de StockScreen, listado de promociones y sus filtros, y guard de traducciones de Gestión.
- TypeScript y compilación de APP GESTIÓN/APP VENTA correctos. Vite de ambas apps repetido con las últimas hojas CSS.
- Navegador: selección de una promoción caducada muestra el helado de la segunda página; Home devuelve a la primera promoción.
- Ancho de 1024 px: documento sin desbordamiento horizontal en ambas apps; panel de Gestión 742 px y Venta 820 px. La tabla tiene su propio desplazamiento para las columnas adicionales.
- Consola de ambas vistas sin errores ni advertencias tras recarga. El fixture inicial requirió reiniciar Vite después de añadir una hoja CSS nueva.
- Validación con datos simulados; no se realizó una prueba de volumen ni una consulta contra la base de datos real.

final result: passed

## Exportación de productos con promoción · 26/09/2026

- En la pantalla compartida, Exportar promoción (F6) envía SELECTED y el identificador de la promoción elegida. Incluye todos sus productos, incluso si la promoción está caducada o inactiva.
- Exportar todas las activas envía ACTIVE y obtiene productos de promociones ACTIVE cuya vigencia incluye la fecha de creación del informe. El agregado de promociones conserva una fila por producto.
- Ambas exportaciones omiten los filtros de artículos de la pantalla y conservan el almacén para los valores de stock. Las columnas y la ordenación proceden de la tabla de productos; ACTIVE añade promoción, tipo y vigencia.
- El servidor valida la promoción seleccionada dentro de la empresa de la tienda y conserva el nombre del archivo al crear el trabajo. Una petición distinta no reutiliza el Excel de otro trabajo en curso.
- Nombres por vista, promoción e idioma ES/EN/ZH, con fecha local y caracteres válidos en Windows. El cliente utiliza Content-Disposition UTF-8 tanto al guardar en escritorio como al descargar en navegador, con un nombre equivalente de respaldo.
- Ejemplos ES: productos-promocion-3x2-2026-09-26.xlsx y productos-promociones-activas-2026-09-26.xlsx. Las vistas de stock, ofertas, precio de miembro, sin descuento y top ventas tienen nombres propios.

### Evidencia y validación

- Capturas reales del componente con datos simulados: frontend/output/promotions-review/exportaciones-gestion.png y exportaciones-venta.png.
- A 1024 px los botones pasan a una segunda línea y permanecen visibles: exportaciones-gestion-1024.png.
- 110 pruebas existentes de Stock y traducciones, 25 de nombres Excel y 4 de integración frontend pasan. Se cubren selección caducada, filtros, almacén, columnas, orden, F6, sondeo del trabajo y nombre UTF-8.
- 38 pruebas backend pasan, incluidas las comprobaciones de ámbito, parámetros SQL, vigencia, trabajos en curso y nombres localizados.
- TypeScript y compilación de APP VENTA y APP GESTIÓN correctos con todos los cambios de exportación.
- El filtrado SQL se comprueba mediante mocks de JDBC; no se ejecutó contra PostgreSQL real. Las capturas utilizan el fixture aislado de promociones.
