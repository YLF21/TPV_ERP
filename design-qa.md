# Design QA — previsualizaciones de documentos A4

## Resultado esperado

- La pestaña **Factura · A4** muestra la captura real de la factura proporcionada por el usuario.
- La pestaña **Albarán · A4** muestra la captura real del albarán proporcionada por el usuario.
- Ambas imágenes mantienen la proporción A4, tienen un tamaño máximo estable y se reducen sin desbordar en pantallas estrechas.
- Las vistas previas existentes de Ticket 80 mm y los formatos sin imagen específica mantienen su comportamiento anterior.

## Comprobaciones realizadas

- Prueba de componente: 10 pruebas superadas, incluidas las nuevas comprobaciones de cambio entre Factura y Albarán.
- Compilación de producción: superada; Vite empaqueta ambas imágenes como recursos locales versionados.
- Inspección en navegador: la aplicación local carga correctamente, pero la pantalla objetivo requiere configurar/autenticar el terminal, por lo que no se realizó una captura final de esa ruta sin modificar el estado local del usuario.

## Estado final

blocked

La implementación y sus comprobaciones automáticas están completas. La única comprobación pendiente es la captura visual autenticada de la pantalla de configuración.

---

# Design QA — previsualización VALE_TICKET_80

- Source visual truth: `frontend/apps/app-gestion/src/assets/document-templates/vale-ticket-80.png`
- Browser evidence: `artifacts/vale-template-browser-blocked.png`
- Viewport: 1280 × 720 CSS px; device pixel ratio 1.5.
- Source dimensions: 576 × 774 px.
- Implementation dimensions: no disponibles; la ruta objetivo queda detrás de la configuración/autenticación local del terminal.
- State esperado: Configuración → Plantillas de documentos → Vale → Ticket 80 mm.

## Full-view comparison evidence

Bloqueada: el navegador local muestra «Configurar terminal servidor» antes de acceder a la pantalla objetivo. No se modificaron credenciales ni el estado local para eludirla.

## Focused-region comparison evidence

Bloqueada por el mismo motivo. La prueba de componente confirma que el `<img>` accesible para «Vale · Ticket 80 mm» utiliza el asset `vale-ticket-80` y que desaparece el resumen textual anterior.

## Fidelity surfaces

- Fonts and typography: la imagen se utiliza sin reconstruir ni alterar su tipografía.
- Spacing and layout rhythm: pendiente de captura autenticada; el contenedor reutiliza la variante existente `is-ticket`.
- Colors and visual tokens: la imagen conserva sus píxeles originales; el marco mantiene los tokens existentes.
- Image quality and asset fidelity: se usa la imagen exacta suministrada, sin recorte destructivo (`object-fit: contain`).
- Copy and content: la prueba verifica el texto alternativo «Vale · Ticket 80 mm» y la retirada del placeholder anterior.

## Findings

- No hay hallazgos P0/P1/P2 en las comprobaciones automatizadas.
- Bloqueador de QA visual: falta una sesión local configurada/autenticada que permita capturar la tarjeta final.

## Comparison history

- Primera iteración: asset integrado y prueba de componente añadida; 11 pruebas superadas.
- Comprobación de producción: Vite empaqueta `vale-ticket-80-*.png` correctamente.
- Evidencia posterior: navegador sin errores de consola, detenido antes de la ruta objetivo por la configuración del terminal.

final result: blocked

---

# Design QA — barra de acciones de informes

- Fuente visual aprobada: `C:\Users\xy656\.codex\generated_images\01a01b4d-7b3c-7791-add0-e2381757ad97\exec-2d62f0f8-1a03-41b6-ba91-a4a6bddcc535.png`
- Dimensiones de la fuente: 1486 × 1058 px.
- Captura de implementación: `E:\workspace\gitwork\TPV_ERP\artifacts\design-qa\sales-report-toolbar-ticket.png`
- Comparación conjunta: `E:\workspace\gitwork\TPV_ERP\artifacts\design-qa\toolbar-reference-vs-implementation.png`
- Entorno verificado: `http://localhost:5173`, viewport 1280 × 720 CSS px, DPR 1.5, captura 1280 × 720 px.
- Estado comparado: pantalla Ticket, período Hoy, tabla vacía, sin documento seleccionado; por ello F5 y Anular ticket aparecen deshabilitados en ambas intenciones visuales.

## Comparación

- Estructura: período a la izquierda, accesos de período, grupo F5/F6/F7, filtros, visualización, anulación, acciones adicionales y búsqueda en una sola barra.
- Comportamiento responsive: todos los controles permanecen dentro de la barra en 1280 px; en el viewport de referencia más ancho conservan espaciado adicional.
- Estilo: botones rectangulares compactos, azul marino para salidas, borde rojo para la acción destructiva, sin sombras decorativas ni bordes tipo píldora.
- Jerarquía: F5/F6/F7 son las acciones primarias; Anular ticket permanece visible y Convertir ticket a factura se aloja en Más acciones.
- Alcance: la misma barra fue comprobada en Ticket, Albarán, Factura, Salida almacén, Entrada factura, Entrada albarán y Entrada almacén. Anular ticket solo aparece en Ticket.

## Historial de iteración

1. Primera captura: el bloque de período quedó fuera del viewport debido a la anchura heredada de los botones.
2. Segunda captura: se redujeron anchos, pero la búsqueda todavía excedía el borde derecho.
3. Resultado final: se compactaron iconos, atajos y búsqueda; la medición confirmó que el contenido termina dentro del límite de la barra.

final result: passed

---

# Design QA — separación de Licencias y empresas

## Evidencia

- Fuente visual: C:\Users\xy656\AppData\Local\Temp\codex-clipboard-4335a801-c875-4743-94b5-859b56756689.png.
- Fuente: 3840 × 1907 px; captura de escritorio suministrada por el usuario.
- Implementación esperada: http://127.0.0.1:5175/#/licenses/companies.
- Captura de implementación: no disponible.
- Viewport CSS y densidad de implementación: no disponibles.
- Estado objetivo: sesión ADMIN, módulo Licencias y empresas, pantalla Empresas.

## Comparación de vista completa

Bloqueada. La fuente muestra toda la política VeriFactu, el alta de empresa y el inicio de la tabla de licencias en una misma página. La implementación divide ese contenido en tres destinos independientes —Empresas, Licencias y VeriFactu—, pero el controlador del navegador local terminó inesperadamente tanto en el navegador integrado como en Chrome antes de poder capturar el resultado renderizado.

## Comparación enfocada

Bloqueada por el mismo fallo del controlador. Las comprobaciones de código confirman que solo se monta el contenido correspondiente a la opción activa, que las rutas internas admiten historial y que seleccionar una empresa desde Licencias abre su gestión.

## Superficies de fidelidad

- Tipografía: reutiliza las fuentes, pesos y jerarquía existentes del SaaS; pendiente de inspección renderizada.
- Espaciado y composición: navegación de tres opciones en cuadrícula de escritorio y una columna por debajo de 850 px; pendiente de captura.
- Colores: reutiliza los tokens existentes de navegación, bordes, fondo y foco.
- Imágenes y recursos: la pantalla de referencia no contiene imágenes funcionales que deban recrearse.
- Contenido: conserva todos los formularios y acciones, repartidos en Empresas, Licencias y VeriFactu.

## Hallazgos

- No hay fallos funcionales detectados por las pruebas automatizadas.
- Bloqueador de QA visual: el controlador de navegador local se cierra antes de devolver el estado o una captura.

## Historial

1. Se separaron política fiscal, alta/gestión de empresa y licencias/instalaciones.
2. Se añadieron rutas internas y compatibilidad con Atrás/Adelante.
3. Se validaron 55 pruebas y la compilación de producción.
4. Se intentó capturar primero el navegador integrado y después Chrome; ambos intentos terminaron por fallo del controlador.

final result: blocked

---

# Design QA — acciones alineadas a la derecha

- Fuente: `C:\Users\xy656\AppData\Local\Temp\codex-clipboard-8c5e7179-9909-47b4-8069-45aed9813743.png` (3840 × 1907).
- Implementación: `E:\workspace\gitwork\TPV_ERP\artifacts\design-qa\toolbar-actions-aligned-right-1920.png`.
- Alcance: el bloque formado por Imprimir, Excel, PDF, Filtrar, Visualización, Más acciones y Buscar se desplaza en conjunto hacia el borde derecho.
- Medición a 3840 px: margen flexible de 2211 px antes del bloque; buscador terminado en x=3821 y barra terminada en x=3833.
- Medición a 1920 px: margen flexible de 291 px antes del bloque; buscador terminado en x=1901 y barra terminada en x=1913.
- No se modifican el período seleccionado ni los filtros rápidos, que permanecen alineados a la izquierda.
- En resoluciones de hasta 1100 px se elimina el margen automático para conservar la distribución responsiva en cuadrícula.
- Resultado: cero solapamiento y cero desbordamiento horizontal.
- Verificación técnica: 47 pruebas del componente superadas y compilaciones de Gestión y Venta completadas correctamente.

final result: passed

---

# Design QA — mejora de interfaz de la barra de informes

- Fuente visual anterior: `E:\workspace\gitwork\TPV_ERP\artifacts\design-qa\toolbar-before-ui-improvement.png`
- Implementación mejorada: `E:\workspace\gitwork\TPV_ERP\artifacts\design-qa\toolbar-after-ui-improvement.png`
- Comparación conjunta: `E:\workspace\gitwork\TPV_ERP\artifacts\design-qa\toolbar-ui-before-vs-after.png`
- Viewport y estado: 1280 × 720 CSS px, DPR 1.5, pantalla Ticket, período Hoy y tabla sin selección.

## Comparación

- La barra anterior comprimía la búsqueda y reducía el alcance de F5/F6/F7 a texto de 8 px.
- La implementación distribuye período, exportaciones, búsqueda y utilidades en una cuadrícula de dos niveles para anchos de hasta 1500 px.
- El período usa una superficie neutra y acento azul; la barra deja de parecer un estado de error.
- Los períodos rápidos muestran estado activo real mediante `aria-pressed`; los no seleccionados dejan de competir visualmente con F5/F6/F7.
- La búsqueda dispone de 436 px en el viewport verificado y un estado de foco visible.
- La ayuda de impresión/exportación sube a 10 px y cambia según exista o no una fila imprimible.
- Ticket conserva Anular ticket visible; las otras seis pantallas no muestran esa acción.

## Validación responsive

- Ticket, Albarán, Factura, Salida almacén, Entrada factura, Entrada albarán y Entrada almacén usan la cuadrícula mejorada.
- Las siete pantallas mantienen F5/F6/F7 y la búsqueda sin desbordamiento; la medición dejó 13 px libres respecto al borde derecho.
- Por debajo de 1100 px la barra adopta tres niveles para conservar controles operables.

## Historial de iteración

1. Se agrupó la barra, pero una regla heredada con `display: flex !important` impedía activar la cuadrícula.
2. Se elevó la prioridad de la regla responsive y se confirmó la distribución de dos niveles.
3. Se amplió el bloque F5/F6/F7 y se acortó la ayuda para mantenerla en una sola línea.

final result: passed

---

# Design QA — barra compacta aprobada para informes

## Evidencia

- Fuente visual: `C:\Users\xy656\AppData\Local\Temp\codex-clipboard-fc9a6e72-604e-4e94-9101-7219a5473316.png`.
- Implementación: `E:\workspace\gitwork\TPV_ERP\artifacts\design-qa\toolbar-approved-version-ticket.png`.
- Comparación conjunta: `E:\workspace\gitwork\TPV_ERP\artifacts\design-qa\toolbar-approved-reference-vs-implementation.png`.
- Estado: Ticket, periodo Hoy, sin fila seleccionada, acciones F5/F6/F7 visibles y Anular ticket deshabilitado.
- Viewport de implementación: 1280 × 720 CSS px, densidad 1; captura 1280 × 720 px.
- Fuente: 1107 × 549 px. La aplicación está incrustada y reducida dentro de la captura, por lo que la comparación se normalizó con un recorte completo de la aplicación y otro recorte específico de la barra, conservando la proporción de cada artefacto.

## Superficies revisadas

- Tipografía: mantiene Segoe UI y la jerarquía compacta del sistema; rango, acciones y buscador permanecen legibles sin saltos de línea.
- Espaciado y composición: periodo, filtros rápidos, F5/F6/F7, utilidades y buscador comparten una sola fila a 1280 px; la barra no presenta desbordamiento horizontal.
- Colores: filtros rápidos y salidas usan el azul marino existente; Anular ticket conserva su semántica roja; estados deshabilitados mantienen contraste diferenciado.
- Imágenes e iconos: se reutilizan los recursos reales de filtro, visualización y búsqueda del producto; no se añadieron sustitutos dibujados.
- Contenido: se conservan las etiquetas y atajos aprobados. F5 afecta a la fila seleccionada y F6/F7 a las líneas visibles.
- Comportamiento: Esta semana y Este mes actualizan el periodo, el rango y `aria-pressed`; el buscador mantiene foco visible y Más acciones conserva su menú.
- Alcance compartido: Ticket, Albarán, Factura, Salida almacén, Entrada factura, Entrada albarán y Entrada almacén muestran la misma barra. Anular ticket existe únicamente en Ticket.
- Responsive: a 1280 px la composición es de una fila; por debajo de 1100 px se mantiene la cuadrícula responsive existente para evitar colisiones.

## Historial de comparación

1. [P2] La primera implementación responsive dividía la barra en dos niveles a 1280 px. Se sustituyó por una composición flex compacta y se confirmó `scrollWidth === clientWidth`.
2. [P2] El rango del periodo y el bloque F5/F6/F7 excedían su ancho interno. Se redistribuyeron anchos, tamaños y espacios; la revisión posterior mostró ambos bloques completos.
3. [P2] Los filtros rápidos aparecían blancos frente al azul marino de la referencia. Se alinearon al token azul y se preservó un estado activo diferenciado.
4. La comparación final conjunta no muestra diferencias P0, P1 o P2 pendientes. La impresión deshabilitada sin selección es una restricción funcional intencionada.

final result: passed

---

# Design QA — corrección de solapamiento F7/Filtrar

## Evidencia

- Captura del defecto: `C:\Users\xy656\AppData\Local\Temp\codex-clipboard-d547fb57-5ad2-43db-ae38-769cad1943b9.png` (3404 × 1826 px).
- Implementación corregida: `E:\workspace\gitwork\TPV_ERP\artifacts\design-qa\toolbar-overlap-fixed.png` (1280 × 720 px, viewport CSS 1280 × 720, densidad 1).
- Comparación enfocada normalizada: `E:\workspace\gitwork\TPV_ERP\artifacts\design-qa\toolbar-overlap-before-vs-after.png`.
- Estado: Ticket, periodo Hoy, tabla sin selección.

## Hallazgo y corrección

1. [P1] El ancho base de `.report-output-cluster` era menor que el contenido de Imprimir, Excel y PDF; PDF/F7 invadía el botón Filtrar en la rama de pantalla ancha.
2. Se aumentó la reserva base de 218 px a 260 px y se hizo que los tres botones repartan el ancho disponible con `flex: 1 1 0`.
3. En la rama compacta se conserva un ancho de 220 px y tamaños tipográficos reducidos.
4. La medición posterior confirma: bloque 220 px, `scrollWidth` 220 px, tres botones de 71 px, separación de 8 px con Filtrar, cero solapamiento y cero desbordamiento de la barra.

## Superficies revisadas

- Layout y espaciado: separación estable entre PDF/F7 y Filtrar.
- Tipografía y contenido: las tres etiquetas y sus atajos permanecen completos.
- Colores, iconos e imágenes: sin cambios respecto a la versión aprobada.
- Accesibilidad y comportamiento: se conservan estados deshabilitados, foco y atajos F5/F6/F7.
- Alcance: la corrección está en el componente visual compartido y cubre las siete pantallas.

final result: passed

---

# Design QA — confirmación del resto de pantallas

- Fuente reportada en Albarán: `C:\Users\xy656\AppData\Local\Temp\codex-clipboard-5d53b162-3a87-43c0-85b8-bbd895df4c97.png`.
- Captura corregida de Albarán: `E:\workspace\gitwork\TPV_ERP\artifacts\design-qa\toolbar-overlap-fixed-albaran.png`.
- Comparación conjunta: `E:\workspace\gitwork\TPV_ERP\artifacts\design-qa\toolbar-albaran-overlap-before-vs-after.png`.
- Pantallas comprobadas: Albarán, Factura, Salida almacén, Entrada factura, Entrada albarán y Entrada almacén.
- Resultado común: bloque F5/F6/F7 de 220 px, contenido interno de 220 px, separación de 8 px con la siguiente acción, cero solapamiento y cero desbordamiento de la barra.
- Las etiquetas, iconos, colores, buscador, menú Más acciones y comportamiento por teclado permanecen sin cambios.

final result: passed

---

# Design QA — búsqueda compacta y separación de acciones

- Fuente reportada: `C:\Users\xy656\AppData\Local\Temp\codex-clipboard-540ed789-a75b-4ddc-acef-0c89a376feba.png` (3840 × 1907).
- Captura corregida: `E:\workspace\gitwork\TPV_ERP\artifacts\design-qa\toolbar-relaxed-spacing.png`.
- Comparación antes/después: `E:\workspace\gitwork\TPV_ERP\artifacts\design-qa\toolbar-spacing-before-vs-after.png`.
- Hallazgo: el buscador ocupaba todo el espacio libre y comprimía visualmente las acciones.
- Corrección: buscador limitado a 420 px en escritorio amplio y a 200 px en el modo compacto; separación base de 10 px entre grupos y separación visual medida de al menos 8 px en las siete pantallas.
- Pantallas comprobadas: Ticket, Albarán, Factura, Salida almacén, Entrada factura, Entrada albarán y Entrada almacén.
- Resultado: sin solapamientos ni desbordamiento horizontal; Ticket permite reducir el buscador hasta 137 px para conservar visible la acción de anulación.
- Verificación técnica: 47 pruebas del componente superadas y compilaciones de Gestión y Venta completadas correctamente.

final result: passed

---

# Design QA — tablas configurables de APP VENTA

final result: passed

## Fuente de verdad

- Vídeo de referencia: `D:/xwechat_files/wxid_1491564915814_a00c/temp/RWTemp/2026-08/2f2f98c34fcef295646dc8647a3fcaa4/dac1bf21bb6c998dd53623e9fd3978d9.mp4`
- Fotogramas analizados: `.codex-video-analysis/contact_sheet_small.jpg`
- Implementación validada: APP VENTA en `http://127.0.0.1:5173/`
- Viewport: 1920 × 1080

## Evidencias

- Estado inicial: `output/design-qa/app-venta-table-default.png`
- Menú de columna: `output/design-qa/app-venta-table-menu.png`
- Movimiento y redimensionado: `output/design-qa/app-venta-table-interactions.png`
- Comparación conjunta: `output/design-qa/table-comparison-small.jpg`

## Comprobaciones

- El encabezado conserva el lenguaje visual azul de APP VENTA y adopta el patrón operativo del vídeo.
- Cada columna dispone de un tirador visual para arrastrar y un menú de opciones independiente.
- El menú ofrece ordenar, mover a izquierda/derecha y ocultar cuando la columna lo permite.
- `Escape` cierra el menú y devuelve el foco a su botón.
- El separador permite ampliar o reducir el ancho y también admite teclado.
- El orden de las columnas cambia sin solapamientos ni recortes en la barra de herramientas o la tabla.
- Las preferencias se mantienen mediante la infraestructura existente de usuario y tabla.

## Validación automatizada

- Playwright visual/interactivo: 1 prueba superada.
- Vitest de pantallas afectadas: 332 pruebas superadas.
- Compilación de `@tpverp/app-venta`: superada.
- `git diff --check`: sin errores de espacios.

---

# Design QA — barra lateral reajustable de APP GESTIÓN

final result: passed

## Fuente de verdad

- Diseño combinado aprobado: `C:/Users/YLF/.codex/generated_images/01a06234-1037-70f2-b947-4b195ac8c5c1/exec-765bf1b9-3132-4bd8-8ad0-a5122fb4aa0d.png`.
- Captura de la implementación: `artifacts/design-qa/gestion-sidebar-resizable.png`.
- Comparación conjunta: `artifacts/design-qa/gestion-sidebar-reference-vs-implementation.png`.
- Previsualización local: `http://127.0.0.1:5174/dashboard-preview.html`.

## Comprobaciones

- Se mantienen Resumen y Alertas de control con icono y texto centrados.
- Los grupos conservan el formato horizontal, con mayor contraste, separadores y marcador cian para el grupo activo.
- Los destinos muestran icono centrado sobre el texto; Control fiscal conserva el formato compacto solicitado.
- El scrollbar es estrecho, integrado en la paleta azul y sin canal blanco del sistema.
- El menú se reajusta entre 210 px y 420 px mediante arrastre; también admite flechas, Inicio y Fin desde teclado.
- La anchura elegida se guarda por usuario en almacenamiento local.
- Se eliminó completamente el bloque inferior con el usuario y `Base local de tienda`.
- La captura no presenta errores de consola y mantiene el contenido operativo sin solapamientos.

## Validación automatizada

- Vitest focalizado: 2 archivos y 10 pruebas superadas.
- Compilación de APP GESTIÓN: superada.

---

# Design QA — APP VENTA táctil, 2026-09-12

## Fuente y entorno

- Rama: `codex/sale-touch-layout`.
- Directorio de imágenes aprobadas: `C:/Users/YLF/.codex/generated_images/01a06d6c-6a96-7540-b3af-881b70f7494f/`.
- Vacío: `exec-eeeea327-607b-490b-a386-9b5afcb1427d.png`.
- Promoción: `exec-6a544b5d-2886-45b1-8fd9-cc15dcae2cd0.png`.
- Más opciones: `exec-b8ca3d5f-cdc5-454d-b772-19314f40954c.png`.
- Bloque protegido: `C:/Users/YLF/AppData/Local/Temp/codex-clipboard-24f20453-3928-443b-be42-8843ca1f91bd.png`.
- Implementación: `http://127.0.0.1:5181/`, escenario aislado que monta los componentes reales. Datos DEMO y respuestas controladas; ninguna conexión a BD.
- Implementation screenshot path: capturas inline en esta tarea de Codex mediante CUA; la API utilizada devuelve bytes/imagen y no proporciona ruta persistente. No se inventa un archivo PNG local.
- Fuente: 1586 × 992 píxeles. Comparación principal: viewport CSS 1586 × 992, DPR 1; capturas a igual tamaño, sin marco de dispositivo ni reescalado CSS.
- Comprobaciones adicionales: 1600 × 1000 y 1280 × 720, DPR 1.

## Evidencias conjuntas y estados

- 13:10:16: fuente vacía y captura renderizada emitidas juntas. Se verificaron las dimensiones por DOM: cuerpo 1586 × 992; controles inferiores de 64 px y Cobrar de 134 px.
- 13:11:12: fuente con promoción y captura juntas; dos artículos DEMO, cantidades 2 y 1, total simulado 3,40 y ahorro 0,60. A continuación se comparó Más opciones abierto con su fuente en la misma entrada visual.
- 13:15:03: captura y medición de la corrección responsive con cliente, documento, deuda y mensaje de búsqueda. Los cuatro botones laterales terminan en y=685 dentro de un viewport de 720 px, sin scroll de acciones.
- 13:16:13: región Precio ampliada; teclado cuadrado y botones independientes de 54 px bajo el teclado. Ambos permanecen visibles.
- 13:12: autorización de precio temporal con campo de contraseña enmascarado y teclado alfanumérico; confirmar deshabilitado sin credenciales. No se enviaron contraseñas.
- 13:16: buscador de productos mediante las teclas P/A/P; devuelve y selecciona PAPEL DE REGALO. Teclado y botón Añadir al ticket visibles a 1280 × 720.
- 13:19: captura del modo KEYBOARD; medición confirma cero barras táctiles y cero teclados alfanuméricos montados, sin errores de consola.
- También se capturaron comentarios, selector de clientes y estados sin selección. Consola: sin errores de aplicación en las consultas realizadas.

## Historial de hallazgos y correcciones

1. P2: quedaba un marco vacío del componente de cobro en el lateral. Se oculta únicamente cuando no tiene elementos; el componente sigue montado para hidratar sesiones y mostrar sus diálogos. Verificado sin marco después del cambio.
2. P2: acciones nuevas pequeñas y teclado numérico redondeado. Se aumentaron fuente/altura, se aplicaron controles cuadrados y cabecera ERP al diálogo Precio. Recaptura confirma botones de 54 px.
3. P2: un cliente con datos y deuda desplazaba Más opciones por debajo de 720 px. En pantallas de hasta 800 px de alto y con cliente seleccionado, los cuatro botones nuevos se distribuyen 2 × 2; sin cliente se conservan las cuatro filas. No se recortan los datos del cliente ni se cambia la lógica de deuda. Medición final: y=685, altura de cada botón aproximadamente 68 px.
4. P2: la etiqueta del menú era Descuento de compra. Se usa Descuento documento y cambia a Eliminar descuento documento, con ES/EN/ZH y pruebas actualizadas.

## Superficies de fidelidad

- Tipografía: se conserva Segoe UI y los fallbacks existentes; no se copia la tipografía sintética de la imagen generada. Nuevas acciones entre 15 y 20 px, con icono y texto. Los bloques originales conservan su densidad.
- Composición: menús y Total/Buscar/Cantidad/Cliente preservados; Factura/albarán junto a Calculadora; acciones fijas abajo; promociones sin espacio reservado cuando no existen. La tabla mantiene las columnas/preferencias reales, aunque la maqueta simplificaba su inventario.
- Color: azul marino, fondo claro, foco visible y rojo para acciones destructivas. Los botones no disponibles se distinguen por estado disabled. Se mantiene el indicador fiscal original, ausente en la maqueta.
- Iconos: Phosphor existente, sin dependencias nuevas ni SVG artesanal. Tamaños coherentes y sin recursos raster estirados.
- Contenido: los textos operativos se traducen a ES/EN/ZH. Se omite la leyenda MAQUETA en producción; el escenario de pruebas se identifica como aislado.
- Interacción: selección de filas, +1/−1, confirmación de cantidad cero, dos operaciones de Precio, permisos, teclado alfanumérico, descuento documental y escáner cubiertos por pruebas. Los permisos y cálculos existentes no se sustituyen por lógica visual.

## Validación y límites

- 394 pruebas distintas de 11 archivos relevantes superadas: 272 en el conjunto de pantalla/controles/i18n y 122 adicionales de SalePaymentCheckout. La última ejecución conjunta de pantalla/autorización/cobro pasó 354/354.
- Compilaciones APP VENTA y APP GESTIÓN correctas.
- Presupuesto de bundle respetado; diccionarios de idioma separados en chunks cacheables. No se aumentaron límites. CSS VENTA mantiene el aviso de proximidad al límite.
- No se ejecutaron cobros reales, impresiones físicas ni cambios de BD. Falta la comprobación operativa en el dispositivo táctil y con sus periféricos reales; la revisión descrita es visual y automatizada en navegador.
- No quedan hallazgos P0/P1/P2 en los estados y resoluciones revisados.

final result: passed

---

# Design QA — teclado alfanumérico con números a la derecha, 2026-09-12

## Fuente y alcance

- Source visual truth: `C:/Users/YLF/AppData/Local/Temp/codex-clipboard-6dd0f6c3-03df-4330-99cf-08d75a6cb43c.png` (1942 × 809 px). La última instrucción modifica la maqueta: `@` se traslada a `#+=` y el espacio liberado amplía Espacio.
- Implementación real: `TouchAlphaKeyboard.tsx` y `TouchAlphaKeyboard.css`, compartidos por los diálogos táctiles. No se cambian API, cálculos, permisos ni datos.
- URL de revisión aislada: `http://127.0.0.1:5181/?promotions`. Usa SaleScreen y los diálogos reales con datos DEMO y red interceptada, sin BD.
- Implementation screenshot path: imágenes inline capturadas mediante CUA en esta tarea; la herramienta devuelve imágenes/bytes, no una ruta de archivo. No se inventa una ruta PNG.
- Viewports: 1600 × 1000 y 1280 × 720 CSS px, DPR 1. Capturas completas de iguales dimensiones; fuente es una ilustración del componente aislado, no una captura de toda la pantalla. Se compara su región de teclado ignorando márgenes blancos y el resto del diálogo real, sin inferir diferencias por esos márgenes.
- Normalización: la fuente contiene un teclado de aproximadamente 1856 × 578 px; a 920 px de ancho equivale aproximadamente a 287 px de alto. El componente de escritorio mide 920 × 296 CSS px; la variante a 720 px de altura usa 920 × 241,56 px para conservar controles y tabla visibles. Diferencia responsive intencionada.

## Comparación conjunta y fidelidad

- Fuente y captura renderizada completa emitidas juntas a las 14:33, estado letras y mayúsculas. Una captura con clip que devolvió una región incorrecta fue descartada y sustituida por la captura completa.
- Se examina específicamente el teclado dentro de las capturas, con texto legible y mediciones DOM complementarias; no se necesitan detalles raster adicionales. Se comprueban también símbolos y autorización a 1280 × 720.
- Tipografía: familia heredada del ERP, letras de 20 px en escritorio, peso 700, etiquetas centradas y sin recortes. No se introduce una tipografía nueva para imitar artefactos del texto generado.
- Composición: cuatro filas, QWERTY español escalonado, Shift y borrar en la tercera, Espacio ampliado, pad derecho 7–9/4–6/1–3/0 y coma. El cero ocupa dos columnas; ambas capas conservan la misma altura y el pad permanece estable.
- Color: fondo `#e7edf4`, teclas blancas, texto `#102e51`, activo `#12365e` con texto blanco, bordes cuadrados y foco visible. No se reproducen sombras/gradientes sintéticos del mockup.
- Iconos: ArrowFatUp y Backspace de Phosphor ya instalado, sin dependencias nuevas ni dibujos manuales.
- Contenido: `@` no existe como tecla en la capa de letras; aparece en símbolos. Se conserva todo el repertorio anterior, incluidas tildes y minúsculas. Los textos operativos reutilizan las claves ES/EN/ZH existentes.

## Historial de correcciones y evidencia final

1. P2: Limpiar quedaba estrecho en la capa de símbolos. Se amplió solo ese control; recaptura y medición final no muestran texto desbordado.
2. P2: el hover de una tecla activada podía dejar texto blanco sobre fondo claro. Se igualó la especificidad de los estilos de estado. Verificado sobre Mayúsculas activado bajo el puntero: fondo `rgb(18, 54, 94)` y texto blanco.
3. Verificación posterior a ambas correcciones, 14:34: teclas de símbolos de al menos 46,79 × 50,39 px en el buscador a 1280 × 720, sin desbordamientos; letras y números permanecen a ambos lados del divisor.
4. Autorización, 14:35: teclado de 962 × 241,56 px, anchura mínima en símbolos 48,87 px, Confirmar/Cancelar visibles, contraseña de tipo password. Se cancela sin introducir credenciales ni enviar la operación.

## Validación y límites

- 253 pruebas distintas correctas: 9 del teclado y 244 de SaleScreen, buscador, autorización y números de serie. Casos: selección, cursor, escáner simulado, textarea, readonly, disabled, contraseña enmascarada, cambio de capa, `@`, signos y pad persistente.
- Prueba en navegador: escribir `@7,` conserva foco y cursor; cambiar a letras y escribir PAP encuentra y selecciona el producto DEMO. Cambiar de capa no mueve ni oculta el pad numérico.
- Compilaciones APP VENTA y APP GESTIÓN correctas. Se corrigió un error de tipos en las nuevas pruebas antes de la compilación final.
- Presupuestos de bundle respetados. Permanece el aviso anterior del CSS principal de VENTA (445757 / 460000 bytes); no se aumentan límites.
- Consola de aplicación sin errores durante la revisión. Sin reiniciar backend, sin cambios de BD, commit ni push.
- Pendiente únicamente prueba física con la pantalla táctil y escáner del terminal; la revisión actual es de navegador y pruebas automatizadas, no certificación del hardware.
- No quedan hallazgos visuales P0/P1/P2 en los estados revisados.

final result: passed

---

# Design QA — teclados contextuales en venta táctil, 2026-09-12

## Alcance implementado

- `SaleTouchKeyboardScope` integra el teclado del campo activo en los diálogos de venta, documentos y utilidades auxiliares. No añade un teclado fijo al escáner principal. Respeta campos bloqueados, de solo lectura, contraseñas y teclados gestionados por cada ventana.
- Los formularios conservan sus validaciones, permisos y acciones de guardado. El teclado genera eventos de entrada, no envíos de formularios ni llamadas de negocio.
- Cobro: teclado alfanumérico para referencia, comentario y vale; numérico para importe. Calculadora: sus teclas editan el porcentaje cuando ese campo está activo. Cobro en efectivo conserva su teclado propio sin duplicarlo.
- Selección numérica: sustituir el valor inicial, editar en el cursor y alternar teclado físico/táctil conservando decimales. Consulta de precios muestra campo y botón Buscar en modo táctil.

## Evidencia y correcciones

- Vista aislada `http://127.0.0.1:5181/?promotions`, datos ficticios y sin conexión a BD. Revisión a 1280 × 720; tamaño del navegador restaurado al terminar.
- Cliente: el cuerpo del formulario desplaza sus campos y reserva espacio separado para acciones y teclado. Devolución: código escrito mediante teclas en pantalla, Buscar/Cancelar visibles, sin ejecutar la devolución.
- Cobro: comentario escrito con teclado alfanumérico; al volver al importe queda un solo teclado numérico. Se cancela sin añadir pagos. Se corrige la capa del total principal que se superponía a la ventana y se mantienen los métodos en dos filas.
- Calculadora: porcentaje 21 → 7 con las teclas existentes, sin cambiar el operando; restaurado a 21 antes de cerrar. Consola sin errores en la revisión final.
- Pruebas focalizadas de pantalla de venta, documentos, ventanas auxiliares/IPC, cobro, calculadora, teclado contextual, consulta de precios e idiomas superadas. Las pruebas comprueban escritura en el campo activo, selección, cancelación, readonly/disabled, portales y ausencia de envíos automáticos.
- Compilaciones APP VENTA y APP GESTIÓN correctas después de los últimos ajustes. `git diff --check` correcto. Presupuestos de bundle respetados; persiste el aviso de proximidad del CSS de VENTA (450659 / 460000 bytes), sin elevar límites.
- No se han reiniciado backend ni aplicaciones reales, realizado cobros, modificado BD ni publicado commits. Pendiente la prueba física en el terminal táctil y con sus periféricos; las utilidades Electron se han validado mediante pruebas de integración/IPC, no con una ventana nativa real.

---

# Design QA — teclado numérico y edición de línea táctil, 2026-09-12

## Objetivo y alcance

- Variante 1 elegida, con cero debajo de 1–2 y coma debajo de 3. Referencia revisada: `C:/Users/YLF/.codex/generated_images/01a06d6c-6a96-7540-b3af-881b70f7494f/exec-9160d77a-9cbc-4958-9fe0-e95921254922.png`.
- Cantidad y Descuento reutilizan la cabecera azul marino y la banda de producto de Precio. Se conserva cada operación y sus validaciones/permisos. El texto explicativo de sustitución se retira a petición del usuario, pero las tres ventanas seleccionan todo el valor al abrir.
- Teclado compartido de cuatro columnas. Con signo: retroceso, limpiar y signo doble; sin signo: retroceso y limpiar de doble altura. Coma deshabilitada para UNIT; WEIGHT y SERVICE conservan precisión de tres decimales. No se modifican reglas de devolución ni cálculos.
- Los estilos numéricos se trasladan al componente compartido y se eliminan los estilos antiguos de tres columnas, incluida la coma de ancho completo.

## Revisión visual y funcional

- Vista aislada `http://127.0.0.1:5181/?promotions`, con componentes reales y datos ficticios; ninguna petición alcanza backend/BD. Se añade un producto WEIGHT únicamente a esta fixture.
- Se compararon referencia y captura del navegador en la misma entrada de revisión. Se adaptó la maqueta de 995 px a una ventana de 500 px, conservando geometría, jerarquía, banda de producto, utilidades laterales y la disposición de 0/coma. Se mantiene el título existente de cada operación y el aviso de devolución de Cantidad; no el texto de sustitución descartado.
- Cantidad, Precio y Descuento revisados a 1280 × 720: cabecera, campo, teclado y acciones visibles sin scroll. Teclas numéricas de 108,5 × 57,59 px; cero de 225 px. Cantidad revisada también a 1600 × 1000: ventana de 500 × 621,48 px, sin desbordamiento; altura de teclas 64 px. Tamaño temporal del navegador restaurado al terminar.
- Corregida durante QA la prioridad del estilo global que pintaba Cancelar azul: ahora tiene fondo blanco y borde, mientras Guardar y las dos operaciones de Precio permanecen azules.
- Verificada selección completa en las tres ventanas. Teclado virtual: 1 → 0.125 y guardado de la cantidad de peso; Precio 2.5 → 8 sin guardar cambio. Teclado físico: Descuento 0 → 12,5, guardado como 12,5 %. Solo cambios en el carrito ficticio, sin cobrar ni enviar autorizaciones.
- Consola sin errores/avisos durante la revisión final. No quedan diferencias visuales P0/P1/P2 en los estados revisados.

## Validación

- 343 pruebas distintas correctas: 14 del teclado, 326 de SaleScreen/Scope/PaymentAllocation/Calculator y 3 de idiomas. Nueve pruebas antiguas TOUCH esperaban spinbutton; se adaptaron a textbox y se reejecutaron solo esas nueve, todas correctas.
- Cobertura nueva: selección/reemplazo inicial, edición en cursor, readonly/disabled, disposición de las teclas, coma y tres decimales en WEIGHT, rechazo de cuarto decimal, UNIT sin fracciones y descuento físico con coma.
- Compilaciones APP VENTA y APP GESTIÓN correctas; presupuesto de bundle y git diff --check correctos. Se mantiene el aviso de proximidad del CSS VENTA: 450949 / 460000 bytes, sin aumentar límites.
- Pendiente únicamente la comprobación física en el terminal táctil. No se reinician backend ni aplicaciones, no se escribe en BD ni se hace commit/push.

final result: passed

---

# Validación previa a publicación — venta táctil, 2026-09-12

- Ajustes finales aprobados: Cambiar precio a la izquierda con estilo secundario, Aplicar descuento a la derecha como acción principal; confirmación de Anular línea con cabecera marino, banda de producto y acción destructiva diferenciada.
- Corregido el teclado alfanumérico en campos nativos de correo: no invoca la API de selección cuando el control no la admite. Conserva el foco, edición, borrado, límite de longitud y validación nativa de email, sin cambiar el tipo de campo.
- Tres regresiones de correo reprodujeron `InvalidStateError` antes del arreglo; después pasan las 20 pruebas completas de TouchAlphaKeyboard y SaleTouchKeyboardScope.
- La prueba de paginación de compras ahora solicita la segunda página mediante scroll, después de cargar la primera fila. Se conservan las aserciones contra el cursor reutilizado y la respuesta obsoleta, sin ampliar timeouts ni modificar el informe de producción. Archivo completo: 9/9 correcto.
- Suite frontend final: 233 archivos, 2.405 pruebas correctas. Compilaciones GESTIÓN, PDA y VENTA correctas; sintaxis Electron y `git diff --check` correctos. Las pruebas de traducciones ES/EN/ZH e IPC están incluidas en la suite.
- Presupuestos respetados: GESTIÓN JS 403036/600000 y CSS 445154/520000; VENTA JS 363658/800000 y CSS 450949/460000 bytes. Persiste el aviso de proximidad de CSS VENTA y el aviso de chunk grande de PDA; no se elevan límites.
- Validación local con Node 20.20.2 y Vitest 4.1.11 instalados; no se sustituyen dependencias. Estos resultados no equivalen a CI remoto ni a prueba física del terminal y sus periféricos.
- Publicación limitada a la rama `codex/sale-touch-layout`. Se excluyen el cambio ajeno de Docker, capturas temporales, documentos anteriores y herramientas de reparación. Sin cambios de BD, backend ni reglas de cálculo.
