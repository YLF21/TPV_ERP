# Revisión general de APP GESTIÓN

Fecha: 1 de agosto de 2026
Modo: auditoría combinada de UX, diseño y riesgos de accesibilidad.

## Alcance

Revisión representativa de la experiencia de APP GESTIÓN: Resumen, navegación lateral, listado de Stock, filtros, Alertas de control y creación de reglas. Se contrastó la evidencia visual con la estructura del frontend, el estado local de las pantallas y los patrones CSS compartidos.

## Objetivo del usuario

Encontrar rápidamente una función administrativa, consultar datos densos, aplicar filtros sin perder contexto y actuar sobre excepciones con confianza, también mediante teclado y en resoluciones de escritorio reducidas.

## Pasos revisados

1. **Entrada en Resumen — salud: buena con problemas de reflow.** La jerarquía nueva es clara y los accesos de cada panel son visibles. A 1280 × 720 aparecen simultáneamente scroll en la página y en la navegación lateral; parte de Actividad operativa queda fuera de la primera vista.
2. **Navegación lateral — salud: aceptable.** La búsqueda global y los permisos reducen opciones irrelevantes. La lista es larga y mezcla consultas, catálogos y operaciones; Stock y Almacén requieren conocer previamente la estructura interna.
3. **Consulta de Stock — salud: necesita mejoras.** Presenta mucha información y permite configurar columnas, pero la tabla exige desplazamiento horizontal extenso, usa texto pequeño y ofrece poca orientación sobre las columnas ocultas fuera del viewport.
4. **Filtrado de Stock — salud: buena tras la corrección actual.** La captura conserva el estado anterior en el que la vista se duplicaba dentro del modal. Esa sección ya fue retirada del código: ahora el menú izquierdo cambia la vista y el modal solo filtra.
5. **Supervisión de alertas — salud: aceptable.** La separación entre periodo, métricas y acciones es clara. El error de carga ocupa una zona grande con un mensaje genérico y no ofrece reintento contextual, causa probable ni referencia técnica.
6. **Añadir regla — salud: necesita mejoras.** Cuando todas las reglas están configuradas, el diálogo mantiene una altura grande con mucho espacio vacío. Debería transformarse en un estado informativo compacto con una única acción de cierre o acceso a reglas existentes.

## Fortalezas

- Identidad visual consistente: navegación azul marino, superficies blancas, bordes sobrios y botones primarios reconocibles.
- Navegación calculada por permisos; los usuarios no reciben todas las funciones indiscriminadamente.
- Stock usa carga paginada por cursor y filtros de servidor, una base adecuada para catálogos grandes.
- Existen personalización de columnas, edición masiva con confirmaciones, deshacer y atajos, y estados diferenciados para datos vacíos y errores.
- Alertas incorpora prioridad, responsable, vencimiento, historial y acceso al documento relacionado.
- La localización española, inglesa y china está ampliamente cubierta y la captura china no muestra desbordamientos graves en la navegación.

## Riesgos de UX

1. **P1 — Estado perdido al cambiar de sección.** APP GESTIÓN monta `StockScreen` con `key={stockSelection.key}`. Al saltar entre vistas, el componente se recrea y puede perder búsqueda, filtros, posición y selección. Guardar el contexto por vista haría la navegación mucho más eficiente.
2. **P1 — Tabla de Stock demasiado ancha y densa.** Hay demasiadas columnas simultáneas, nombres truncados y desplazamiento horizontal prolongado. Fijar código/nombre, crear presets de columnas por tarea y mostrar claramente que existen columnas fuera de pantalla reduciría errores.
3. **P1 — Recuperación débil ante errores.** Alertas muestra «No se pudieron cargar las alertas», pero no una acción de reintento junto al error, diagnóstico básico, hora del último dato válido o referencia para soporte.
4. **P1 — Doble desplazamiento en ventanas bajas.** En Resumen, la página y el menú lateral pueden desplazarse a la vez. Debe existir un único contenedor vertical principal y una navegación estable.
5. **P2 — Arquitectura de navegación extensa.** Conviene separar dentro de Stock las consultas frecuentes de las operaciones administrativas, y distinguir «Productos con promoción» de «Gestión de promociones».
6. **P2 — Filtros sin un patrón global único.** Las pantallas usan modales, barras, resúmenes activos y selectores distintos. Un mismo patrón de filtros con contador, chips activos, limpiar, aplicar y persistencia sería más predecible.
7. **P2 — Resumen todavía poco accionable.** Muestra números y estados, pero puede añadir hora de actualización, comparación temporal, filtros por tienda/terminal y accesos desde cada métrica al listado ya filtrado.
8. **P2 — Búsqueda global limitada a navegación.** Puede evolucionar a un lanzador que busque módulos, productos, clientes, documentos y acciones recientes, manteniendo `Ctrl+K`.

## Riesgos de accesibilidad

- Varias etiquetas y celdas usan tamaños de 11–12 px; a zoom alto la densidad puede afectar legibilidad y reflow.
- Algunos controles de personalización miden aproximadamente 25 px, por debajo de un objetivo táctil cómodo de 44 × 44 px.
- Los diálogos de filtros declaran `role="dialog"` y `aria-modal="true"`, pero no se observa un patrón común de foco inicial, trampa de foco, cierre con Escape y devolución del foco al botón que los abrió. Edición masiva sí contiene manejo parcial de Escape, lo que evidencia inconsistencia.
- Las tablas densas necesitan comprobación completa con teclado y lector de pantalla: orden de lectura, encabezados, anuncio de selección, redimensionado de columnas y navegación por filas.
- El estado de error debería anunciarse con una región de estado adecuada y conservar el foco en un punto útil para recuperarse.

## Mejoras recomendadas por fase

### Fase 1 — impacto alto y esfuerzo contenido

1. Conservar búsqueda, filtros, columna seleccionada, scroll y paginación por cada vista de Stock.
2. Unificar el patrón de filtros y mostrar chips/contador de filtros activos en todas las pantallas.
3. Añadir recuperación contextual a errores: reintentar, última actualización, conexión y referencia de soporte.
4. Corregir el doble scroll del Resumen y validar 1280 × 720, 1366 × 768 y zoom al 200 %.
5. Compactar los diálogos vacíos y deshabilitar acciones imposibles antes de abrirlos cuando sea viable.

### Fase 2 — eficiencia operativa

6. Añadir presets de columnas en Stock: Operativa, Precios, Proveedores, Promociones y Personalizada.
7. Fijar Código y Nombre, incorporar indicador de columnas fuera de pantalla y ofrecer densidad compacta/cómoda.
8. Convertir métricas del Resumen y Alertas en accesos al listado correspondiente con filtros preaplicados.
9. Ampliar `Ctrl+K` a búsqueda de entidades y acciones frecuentes.
10. Añadir vistas guardadas por usuario y posibilidad de restaurar valores predeterminados.

### Fase 3 — calidad transversal

11. Crear un gestor de diálogos común con foco inicial, Escape, trampa de foco y restauración del foco.
12. Dividir `StockScreen.tsx` (más de 7.200 líneas) en módulos por vista y flujo.
13. Modularizar `tpv.css` (casi 19.800 líneas) y usar nombres de clase por dominio. Esto evitaría colisiones como la detectada anteriormente con `.promotion-summary`.
14. Añadir pruebas visuales de Resumen, filtros, tablas, diálogos y los tres idiomas en resoluciones objetivo.
15. Ejecutar una auditoría específica con teclado, lector de pantalla y contraste antes de afirmar conformidad WCAG.

## Límites de la evidencia

- Las capturas de Stock fueron proporcionadas por el usuario y muestran la aplicación real en chino; la captura del filtro representa el estado previo a la corrección ya aplicada.
- Resumen y Alertas se capturaron en vistas previas locales. El preview de Alertas no pudo obtener datos y permitió auditar su estado de error, no el listado poblado completo.
- Las conclusiones sobre foco y lectores de pantalla se basan parcialmente en la estructura del código; requieren una sesión dedicada con teclado y tecnología asistiva.
- No se revisaron en profundidad todos los formularios de Ventas, Almacén, Clientes, Socios, Proveedores, Seguridad y Configuración.
