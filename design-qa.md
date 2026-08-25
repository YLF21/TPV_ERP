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
