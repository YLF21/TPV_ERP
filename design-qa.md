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
