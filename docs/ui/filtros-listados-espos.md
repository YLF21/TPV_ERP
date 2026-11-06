# Estándar de filtros y barras de acciones de esPOS

Versión 1 · Actualizado el 05/11/2026 · APP VENTA y APP GESTIÓN de escritorio.

Pendiente de reposición es la referencia del patrón definido en esta sesión. El objetivo es reutilizar su distribución compacta, controles y estados visuales en los demás listados. Cada pantalla conserva sus criterios de búsqueda, filtros disponibles, permisos y reglas de negocio. PDA, app móvil y SaaS quedan fuera de esta aplicación.

## Distribución del panel

La primera fila contiene la búsqueda y el botón **Filtrar** a la izquierda. A la derecha se agrupan las acciones del listado; Excel y PDF ocupan las dos últimas posiciones. Todos los controles se alinean por su borde inferior, a la altura del cuadro de búsqueda.

| Zona | Contenido y posición |
|---|---|
| Cabecera de pantalla | Título y botón Actualizar a su derecha. |
| Primera fila del panel | Buscar, Filtrar, espacio flexible, acciones de la pantalla, Excel y PDF. |
| Recuadro desplegable | Filtros compactos, de izquierda a derecha, debajo de la primera fila. |
| Encima de la tabla | «n seleccionadas» a la izquierda cuando el listado permite selección múltiple. |
| Margen inferior del listado | Filtros de fechas y resumen del periodo, debajo de la tabla y siempre visibles. |

En reposición, el orden de acciones es **Editar → Marcar pedido → Volver a pendiente → Eliminar → Excel → PDF**. En otros listados se conservan las acciones correspondientes a su función.

El botón Filtrar muestra u oculta el recuadro de criterios. Cerrar ese recuadro conserva los filtros aplicados. La barra no muestra el número de registros. Los totales que tengan significado de negocio se conservan en su ubicación correspondiente.

Los criterios se aplican automáticamente. Cada desplegable de criterios permite elegir varias opciones, que se combinan con OR dentro del campo y con AND entre campos. Pulsar una opción activa o la cruz de su etiqueta quita esa opción. **Limpiar todos** restablece los filtros del listado. Los rangos de fecha y los selectores de contexto conservan su función.

## Medidas y colores

Estas medidas constituyen el estándar de adopción. Las anchuras se refieren al control completo, incluida la flecha de los desplegables.

| Elemento | Estándar |
|---|---|
| Anchura de cada filtro ordinario | **180 px**. No repartir el ancho disponible entre cuatro controles de ancho variable. |
| Altura de campos y botones | **34 px**. |
| Anchura de búsqueda | Base **280 px**, mínimo habitual **260 px** y máximo **360 px**; limitada al espacio disponible. |
| Radio del panel exterior | **6 px**. |
| Radio del recuadro gris de filtros | **6 px**. |
| Radio de campos | **4 px**, con un único borde visible. |
| Radio de botones auxiliares | **3 px**. |
| Radio del menú de opciones | **6 px**; opciones con radio **4 px**. |
| Borde del panel y controles | Una línea de **1 px**, sin marcos interiores duplicados ni sombras añadidas. |
| Relleno del panel | **8–10 px** vertical y **12 px** horizontal. |
| Relleno del recuadro de filtros | **10 px**. |
| Separación entre filtros | **12 px**. |
| Separación entre controles de la primera fila | **8 px**; **6 px** entre botones de un grupo. |
| Fondo del recuadro de filtros | **#F4F7FB**. |
| Fondo de los campos editables | Blanco. |
| Fondo de botones auxiliares | Gris **#EEF1F4**, con borde gris y texto oscuro. |
| Texto de controles | Color oscuro de la aplicación, con **#172E4C** como referencia. |
| Resaltado al pasar el ratón por una opción | Fondo **#0065FE** y texto blanco. |

Los filtros mantienen su ancho estándar y pasan a otra fila cuando falta espacio. Dentro de una ventana más estrecha que un solo control, se ajustan al ancho disponible. Los filtros de fechas se mantienen en una barra en el margen inferior del listado, fuera del recuadro desplegable. Conservan el espacio necesario para sus periodos y rangos; no se comprimen sus campos ni se cambia su funcionamiento para encajarlos en 180 px. La tabla ocupa el espacio restante y tiene su propio desplazamiento, de modo que la barra de fechas permanezca visible.

Se utiliza la tipografía existente de la aplicación. Las etiquetas tienen **12 px** y peso destacado; los botones compactos, **11 px**. El texto orientativo del buscador tiene **11 px** y peso normal. Los iconos habituales miden **16 px**; la lupa y el icono Filtrar, **18 px**.

Las etiquetas Almacén, Proveedor, Familia y Subfamilia —y sus equivalentes en otros listados— tienen **fondo transparente**, de forma que se vea el gris del panel. No se dibujan franjas blancas detrás de sus nombres. Esta regla corresponde a las etiquetas de los filtros; las cabeceras de las tablas conservan su estilo empresarial.

## Búsqueda

La búsqueda utiliza una lupa a la izquierda y un solo marco exterior. El cuadro interior no añade otro borde ni otra sombra. El texto dispone de margen interior y no se pega a la lupa.

El texto orientativo describe únicamente los campos que realmente busca esa pantalla. En reposición es **«Buscar por código, código de barras o nombre»**. Las demás pantallas usan sus criterios reales y las traducciones ES, EN y ZH.

La búsqueda filtra al escribir, conservando los plazos de espera y la protección frente a respuestas obsoletas que ya existan. Enter mantiene sus atajos de navegación, sin exigir una confirmación para aplicar el texto. Las acciones de búsqueda de datos externos que tengan una función propia conservan su botón Buscar.

## Desplegables

- Utilizar `ErpListSelect` para opciones de filtro de escritorio. El menú permanece abierto al activar o desactivar una opción.
- Dibujar un solo borde en el campo. Su contenedor no añade un segundo recuadro.
- Alinear el menú con el borde izquierdo del campo completo y limitarlo a su misma anchura. En controles editables, incluir también el cuadro de texto en el anclaje.
- Usar `maxPopoverWidth="trigger"` en `ErpSelect` y `ErpMultiSelect`. En `ErpSelect`, `popoverAnchor="control"` toma el campo completo como referencia. Los filtros de selección múltiple conservan sus valores y su lógica de combinación.
- Mantener las esquinas redondeadas y evitar que el menú quede recortado por el panel.
- La flecha apunta hacia abajo cuando está cerrado y hacia arriba cuando está abierto. Conserva el azul oscuro y el acento de foco o apertura existentes.
- Las opciones activas y la opción bajo el ratón utilizan **#0065FE** con texto blanco. El foco de teclado tiene su propio contorno.
- En documentos cliente, el menú presenta únicamente las opciones, sin búsqueda interna, casillas ni «Todos». El campo vacío muestra «Sin filtro». En documentos proveedor, el menú usa fondo blanco y opciones contiguas.
- Las opciones deshabilitadas conservan su aspecto y no reciben el resaltado de una opción disponible.

Escape cierra primero el menú abierto. Con el menú cerrado, Escape puede plegar el recuadro de filtros y devolver el foco al botón Filtrar, sin cerrar la pantalla. Tab y las flechas mantienen la navegación de los controles compartidos.

## Botones y estados

Los botones auxiliares de la barra utilizan fondo gris, borde, icono y texto visible. No basta con un icono sin etiqueta. Los deshabilitados mantienen la misma distribución, con menor énfasis, y no ejecutan acciones.

Excel y PDF conservan sus iconos y los atajos **F6** y **F7** donde ya están asignados. Los atajos usan los mismos manejadores, permisos y bloqueos de los botones, y no se ejecutan por detrás de una ventana modal. Al incorporar el estándar a una pantalla con otros atajos, se comprueban los conflictos antes de asignarlos.

Actualizar pertenece a la fila del título, a la derecha. No ocupa espacio entre los filtros y las exportaciones.

El formato gris de esta barra corresponde a acciones auxiliares de listados. Las confirmaciones, avisos y botones de ventanas mantienen las reglas de [botones de ventanas](botones-ventanas-espos.md) y [cierres de ventanas](cierres-ventanas-espos.md).

## Integración con la tabla

Estos ajustes se aprobaron junto con el panel de filtros y deben conservarse al trasladar el conjunto a otro listado con selección múltiple.

- Mostrar **«n seleccionadas»** a la izquierda, inmediatamente sobre la tabla. No repetir en ese lugar el título de la pantalla.
- Reservar **20 px** para esa línea y aproximadamente **4 px** de separación con el panel anterior. Evitar huecos verticales adicionales cuando no hay mensajes ni filtros aplicados visibles.
- Mantener **un único marco exterior** de 1 px con radio de **6 px**. La tabla interior no añade otro marco. La cabecera queda contenida en ese mismo redondeo.
- Usar una columna de selección de **32 px**, con casillas de **14 × 14 px**, centradas en el mismo eje en cabecera y filas.
- En listados que permiten selección múltiple, pulsar en una zona no interactiva de la fila selecciona o deselecciona solo esa fila. Pulsar su casilla cambia el estado una sola vez.
- Preservar la selección por identificador, el contador, Seleccionar todas y la navegación con teclado. Impedir cambios de selección durante las operaciones que ya la bloquean.
- Mantener el desplazamiento interno de la tabla y aprovechar la altura disponible, también con un listado vacío. Abrir filtros o seleccionar una fila no reinicia el desplazamiento.

Las etiquetas de filtros aplicados utilizan `ErpFilterChips` donde exista esa función. Se conservan el borrado individual, Limpiar filtros y la restauración del foco; su presencia puede aumentar la altura necesaria sobre la tabla.

## Cabeceras y alineación

El panel se alinea con la cabecera y la tabla del módulo. En Almacén, Traspasos es la referencia de márgenes: **14 px** arriba, **18 px** a los lados y **10 px** abajo en el contenido de Gestión. La fila del título de módulo mide **42 px**, con texto de **23 px**; la fila del título de pantalla tiene una altura mínima de **48 px**, con texto de **22 px**. Las líneas separadoras y los márgenes deben coincidir también cuando una pantalla no tenga botón Crear.

Cuando la pantalla está dentro de una ventana que ya muestra el título del módulo, no se añade otra copia. En los demás módulos se conserva su cabecera aprobada y se alinea el panel con ella.

## Aplicación al resto de pantallas

La referencia visual se encuentra en [ReplenishmentScreen.tsx](../../frontend/packages/app-common/src/components/ReplenishmentScreen.tsx) y [ReplenishmentScreen.css](../../frontend/packages/app-common/src/components/ReplenishmentScreen.css).

La implementación común es [ErpListFilterPanel.tsx](../../frontend/packages/app-common/src/components/ErpListFilterPanel.tsx), con [ErpListFilterPanel.css](../../frontend/packages/app-common/src/components/ErpListFilterPanel.css). La pantalla proporciona los controles, las acciones y sus manejadores; el panel gestiona únicamente la distribución y el plegado. Los criterios permanecen montados cuando se ocultan y conservan sus valores.

El panel reutiliza [ErpSelect](../../frontend/packages/app-common/src/components/ErpSelect.tsx), [WarehouseSearchFrame](../../frontend/packages/app-common/src/components/WarehouseSearchFrame.tsx), [ErpFilterChips](../../frontend/packages/app-common/src/components/ErpFilterChips.tsx) y el comportamiento existente de atajos. Los filtros de fechas utilizan su barra inferior existente, independiente de `ErpListFilterPanel`. `erp-list-filter-table` identifica exclusivamente el contenedor de la tabla de listado con un marco único. `erp-list-filter-host` evita un marco adicional en paneles de documentos que ya contienen la barra y la tabla.

### Listados de Almacén adaptados

| Listado | Implementación y alcance |
|---|---|
| Entradas, salidas, albaranes y facturas de entrada | [WarehouseOperationsPanel.tsx](../../frontend/packages/app-common/src/components/WarehouseOperationsPanel.tsx), compartido por Ventas y Gestión. Búsqueda, estado, fechas y acciones existentes. Albaranes y facturas de entrada incorporan Proveedor y Almacén, también respetados en Excel/PDF. |
| Comprobación de mercancía | [GoodsCheckPanel.tsx](../../frontend/packages/app-common/src/components/GoodsCheckPanel.tsx), compartido por Ventas y Gestión. Búsqueda, tipo de documento, Proveedor, Almacén y acción de iniciar comprobación. |
| Traspasos | [WarehouseTransferScreen.tsx](../../frontend/apps/app-gestion/src/WarehouseTransferScreen.tsx), en Gestión. Búsqueda, estado, origen, destino, fechas y acciones existentes. |
| Historial de ajustes | [WarehouseOperationsScreen.tsx](../../frontend/apps/app-gestion/src/WarehouseOperationsScreen.tsx), en Gestión. Búsqueda, almacén y fechas; conserva su desplazamiento y carga incremental. |
| Inventarios físicos | [StockCountScreen.tsx](../../frontend/apps/app-gestion/src/StockCountScreen.tsx), en Gestión. Búsqueda, almacén, estado, fechas y acciones existentes. |

Los rangos de fecha existentes se mantienen en el margen inferior, debajo de la tabla. Los periodos predefinidos y los rangos manuales válidos se aplican automáticamente. Una fecha incompleta o un rango inválido permanece en edición y no lanza una consulta inválida. Abrir o cerrar Filtrar no oculta las fechas. Enter en la búsqueda de entradas y salidas sigue conduciendo al filtro de estado, abriendo el recuadro si estaba plegado. Los listados con selección simple mantienen esa selección; no incorporan selección múltiple por adoptar este patrón.

Gestión de almacenes conserva sus tarjetas y formularios: no dispone de una barra de filtros que adaptar. Los editores de documentos, el registro de mercancía, las operaciones de ajuste y el editor de inventario conservan su funcionamiento. PDA y app móvil mantienen su presentación anterior.

### Resto de listados de Ventas y Gestión

| Área | Listados que utilizan el estándar |
|---|---|
| Productos | Artículos, edición masiva y listas guardadas, promociones, grupos de promociones, productos más vendidos e historial de ventas de un artículo. |
| Terceros | Clientes, miembros y proveedores, documentos de un cliente, representantes de proveedores y categorías de miembros. |
| Documentos e informes | Listados de informes, actividad de ventas y documentos comerciales. |
| Cobros | Deudas abiertas e histórico de cobros de clientes. |
| Caja | Actividad, histórico y alertas de apertura. |
| Control | Listado de alertas y sus criterios adicionales. |
| Administración | Listados de usuarios y roles; búsquedas compartidas de impuestos, métodos de pago, seguridad de operaciones, canales de fidelización e historial de licencia. |
| Fiscal | Registros fiscales, cola VeriFactu y registros defectuosos, conservando su borrador y aplicación explícita. |

Las ventanas de criterios también aplican automáticamente cada selección válida, conservando el diálogo, su cierre y su navegación. Los borradores se utilizan para fechas o valores incompletos que todavía no pueden aplicarse. Las exportaciones usan los criterios válidos aplicados; sus permisos y bloqueos se conservan. Los botones que guardan documentos, confirman operaciones o aplican cambios de edición masiva mantienen su confirmación: no son botones de filtro.

Una pantalla que sólo permite buscar utiliza el mismo cuadro y marco, sin añadir un botón Filtrar vacío. Una pantalla sin búsqueda conserva sus criterios sin inventar un buscador. Los componentes compartidos aplican el patrón únicamente en Ventas y Gestión.

El estándar corresponde a filtros de listados. Los selectores de configuración de plantillas, árboles de familias, cuadros de mando y formularios de edición no se convierten en barras de listado. Sus campos editan configuración, seleccionan un contexto o controlan indicadores. Observabilidad de PDA es una pantalla de Gestión y conserva sus acciones operativas; las interfaces de PDA y móvil permanecen fuera del alcance.

Las tablas auxiliares de selección de clientes y proveedores, y la búsqueda de tickets por número de serie, utilizan el mismo marco y buscador de escritorio. Su selección y sus acciones propias se mantienen.

Tickets, albaranes de venta y facturas de venta utilizan el panel de criterios integrado en el listado de documentos de clientes. No repiten el selector Período en ese panel: las fechas se controlan únicamente desde la barra inferior. Vales emitidos de Gestión utiliza también el panel integrado y mantiene Limpiar todos.

### Marco de campos de escritorio

[DesktopFieldControls.css](../../frontend/packages/app-common/src/styles/DesktopFieldControls.css) se importa únicamente desde Ventas y Gestión. Todos los desplegables y cuadros de texto de esas aplicaciones, incluidos formularios y ventanas, utilizan radio de **4 px**. Los menús mantienen **6 px**. Los controles compuestos conservan un único marco continuo y los buscadores no añaden un borde al campo interior. Se mantienen las alturas, anchuras, tamaños de letra, estados de validación y controles de selección de cada formulario.

El estilo del menú de selección múltiple se comparte entre los filtros de listado. Los selectores de formularios mantienen sus reglas de foco y selección.

### Comprobación de cada pantalla

1. Conservar los filtros y permisos disponibles, los valores por defecto y los criterios reales de búsqueda.
2. Aplicar la distribución, las medidas y los colores comunes; comprobar ventanas anchas y estrechas, con filtros abiertos y cerrados.
3. Comprobar el ancho del menú, el marco único, las etiquetas transparentes y el azul persistente de cada opción activa.
4. Comprobar teclado, Escape, foco, estados deshabilitados y atajos existentes.
5. Cuando haya selección múltiple, comprobar clic en fila, clic en casilla sin duplicar el cambio, Seleccionar todas y contador.
6. Comprobar tabla vacía y con datos, desplazamiento, marco único y esquinas redondeadas.
7. Verificar exportaciones y traducciones ES, EN y ZH, sin modificar consultas, cálculos, stock, cobros, fiscalidad ni arquitectura local o SaaS como parte del cambio visual.
8. Comparar la pantalla con Pendiente de reposición antes de dar por finalizada su adaptación.
