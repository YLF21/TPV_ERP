# Márgenes equilibrados del cierre de Convertir ticket a factura

final result: passed

---

# Design QA — cierre en cruz de esPOS VENTA y GESTIÓN, 2026-10-04

- Patrón aprobado: X de 22 px centrada en una superficie de 44 × 44 px, márgenes superior y derecho de 3 px, sin marco normal y cuadro de radio 4 px al pasar el puntero o recibir foco visible. Variante oscura sobre cabeceras claras.
- Aplicado en 113 ubicaciones JSX de 78 componentes: 86 ubicaciones comunes y 27 propias de GESTIÓN. La lista de títulos y variantes está en `docs/ui/cierres-ventanas-espos.md`.
- Se conservaron handlers de cierre, bloqueos durante guardado/cobro, Escape, referencias de foco y traducciones. Los nuevos cierres de cabecera reutilizan la salida ya existente de la ventana. PDA conserva sus controles originales.
- Auditoría de propiedades de los 113 controles sin incidencias. Pruebas focalizadas de ventanas, estados ocupados, validación, navegación de teclado, foco inicial y devolución del foco correctas.
- TypeScript y compilación de producción correctos para VENTA, GESTIÓN y PDA. Presupuestos de bundles respetados; CSS de VENTA 458.319 / 460.000 bytes, con aviso de proximidad al límite.
- Verificación en navegador a 1920 × 1080: factura y ventana fiscal; variantes de cabeceras claras y oscuras; confirmación Excel; tarjeta manual y revisión de pago con tarjeta. Botón 44 × 44 px, icono 22 px, desfase del centro de la cruz 0 px y márgenes iguales.
- Los diálogos de tarjeta conservan el foco inicial en el campo de referencia o la acción inferior de revisión. Las pruebas visuales usan datos simulados y no efectúan cobros ni escrituras de negocio.
- Evidencia: `output/login-ui/window-close-invoice-hover.png`, `output/login-ui/window-close-invoice-header.png` y `output/login-ui/window-close-gestion-fiscal.png`.

final result: passed

- Referencia: `C:/Users/YLF/AppData/Local/Temp/codex-clipboard-3636b8c3-dd65-4335-bd03-3e381a172ab7.png`. Se conserva el cuadro translúcido que aparece con el ratón sobre el cierre.
- Cabecera exclusiva del diálogo: padding de 3 px arriba, abajo y a la derecha; el izquierdo conserva 16 px. Selector específico para prevalecer sobre la cabecera compartida. El área del cierre sigue en 44 × 44 px, con X de 22 px centrada por grid.
- Medido a 1920 × 1080 y 1366 × 720: margen superior 3 px, derecho 3 px y desviación del centro de la X 0 px en ambos ejes. La cabecera mide 51 px; a 720 px el diálogo permanece entre y=16 y y=704.
- Hover real comprobado: fondo `rgba(255, 255, 255, 0.12)`, sin borde ni foco de teclado activo. Se conservan comportamiento, estados deshabilitados y foco; no cambia el código TSX ni se realizan operaciones fiscales.
- Evidencia: `output/login-ui/invoice-close-balanced-hover-1920.jpg`, `invoice-close-balanced-hover-detail.jpg` y `invoice-close-balanced-hover-corner.jpg`. Vista previa aislada restaurada en 5185 y mantenida a 1920 × 1080.
- Compilaciones VENTA/GESTIÓN y presupuestos correctos. CSS principal VENTA sigue en 459951 / 460000 bytes. Se reutilizan las 7 pruebas válidas del diálogo, cuyo código funcional no cambia en este ajuste CSS.

# Cierre sin marco en Convertir ticket a factura

final result: passed

- Selección confirmada: opción 1 original sin marco; referencia `C:/Users/YLF/.codex/generated_images/01a107c9-8471-7702-ac0b-0be3cf26aaaa/exec-ec2f0a6c-26d2-46a0-8632-42d68bddf304.png`. Comparación limitada al botón, conservando el resto de la ventana.
- El cierre superior utiliza el icono X de Phosphor, blanco, de 22 px y peso bold. Fondo transparente, borde de 0 px, sin sombra y centro exacto dentro del área pulsable existente de 44 × 44 px.
- Estilos exclusivos de SaleTicketInvoiceDialog. Se conservan etiqueta accesible traducida, onClose, bloqueo busy y foco visible blanco de 2 px al navegar con teclado. El botón Cerrar del teclado y los cierres de otras ventanas conservan su estilo.
- Comprobado a 1920 × 1080: pulsar la X cierra el diálogo; volver a abrirlo permite navegar hasta el cierre con Shift+Tab. Vista previa aislada con datos ficticios. No se ejecuta la conversión a factura.
- Evidencia: `output/login-ui/invoice-close-borderless-1920.jpg`, `invoice-close-borderless-detail.jpg`, `invoice-close-borderless-comparison.jpg` y `invoice-close-keyboard-focus-1920.jpg`. La comparación normaliza únicamente el área del cierre frente al diseño elegido.
- 7 pruebas existentes del diálogo aprobadas. Compilaciones de VENTA y GESTIÓN y presupuestos correctos; CSS principal VENTA permanece en 459951 / 460000 bytes. Los nuevos estilos pertenecen al CSS separado del diálogo.

# Separación del buscador de cliente en Convertir ticket a factura

final result: passed

- Sin cliente seleccionado ni resultados, el input terminaba a 1 px del borde inferior del cuadro grande; el contorno de foco de 2 px con offset de 1 px se superponía a ese borde.
- Se añade exclusivamente `padding-bottom: 8px` al cuadro de cliente de SaleTicketInvoiceDialog en su CSS propio. La altura del cuadro vacío pasa de 125,98 a 133,98 px y la separación inferior del input pasa a 9 px, dejando también espacio para el contorno de foco.
- Comprobado con el buscador enfocado y teclado táctil a 1920 × 1080 y 1366 × 720. A 720 px, el diálogo permanece dentro de la pantalla y el input queda visible. No cambian el tamaño del input, búsqueda, selección, acciones ni colores.
- Capturas: `output/login-ui/invoice-search-spacing-before-1920.jpg`, `invoice-search-spacing-1920.jpg`, `invoice-search-spacing-1366.jpg` y `invoice-search-spacing-detail.jpg`. Datos ficticios de la vista previa aislada.
- Compilaciones de VENTA y GESTIÓN y presupuestos correctos; CSS principal VENTA permanece en 459951 / 460000 bytes. Se reutilizan las 7 pruebas funcionales válidas del diálogo, cuyo código no ha cambiado desde su ejecución anterior. `git diff --check` correcto.

# Colores de Crear factura y Cancelar

final result: passed

- Referencia: `C:/Users/YLF/AppData/Local/Temp/codex-clipboard-50ac36e1-8881-49ea-b73e-06b638830c5d.png`.
- En el diálogo Convertir ticket a factura, Crear factura utiliza fondo y borde `#0065FE`; Cancelar utiliza `#B42318`. Ambos conservan texto blanco y la atenuación existente cuando están deshabilitados.
- Estilos limitados a dos clases del pie de `SaleTicketInvoiceDialog`, en CSS propio del componente. No cambian las acciones, autorizaciones, conversión, impresión, cierre ni el resto de ventanas.
- Comprobación en el diálogo real abierto desde SaleScreen, con teclado táctil y respuestas ficticias de lectura en un HTML ignorado. No se ha pulsado Crear factura ni realizado escrituras en la BD. Colores calculados: `rgb(0, 101, 254)` y `rgb(180, 35, 24)`.
- Capturas: `output/login-ui/invoice-button-colors-1920.jpg` y recorte `invoice-button-colors-dialog.jpg`.
- 7 pruebas existentes de SaleTicketInvoiceDialog aprobadas. Compilaciones de VENTA y GESTIÓN y presupuestos correctos; el CSS principal VENTA permanece en 459951 / 460000 bytes, y los nuevos estilos se generan en un archivo CSS separado del diálogo. `git diff --check` correcto.

# Iconos de Home más centrados

final result: passed

- Referencia: `C:/Users/YLF/AppData/Local/Temp/codex-clipboard-602baece-bdb7-47b3-95f8-32885bd2f121.png`.
- Los cinco iconos se sitúan en el centro horizontal y al 45 % de la altura de cada tarjeta, manteniendo el tamaño original. Se fija la etiqueta en la segunda fila y se conserva la posición de los textos y las etiquetas F1–F5.
- Verificado a 1920 × 1080 y 1366 × 720: desviación horizontal 0 px; separación icono/texto 33/31 px en Venta y 30,1/29,1 px en las tarjetas secundarias. No hay solapamientos.
- Capturas: `output/login-ui/home-icons-centered-1920.jpg`, `home-icons-centered-1366.jpg` y recorte `home-icons-centered-cards.jpg`. Vista previa del componente real con los datos ficticios del HTML ignorado de Home.
- Compilaciones de VENTA y GESTIÓN y presupuestos correctos: CSS principal VENTA 459951 / 460000 bytes. Se reutilizan las 11 pruebas funcionales anteriores de Home, cuyo código no cambia. `git diff --check` correcto.

# Azul de los menús de venta y del botón Cobrar

final result: passed

- Referencia: `C:/Users/YLF/AppData/Local/Temp/codex-clipboard-2c8740d8-9f3d-45e0-a630-3cfe335dfc33.png`.
- Los cinco menús (Sistema, Factura/Ticket, Documento, Producto y Visualización) abiertos o enfocados y las filas habilitadas con hover o foco de teclado utilizan `#0065FE` y texto blanco. Las marcas de las opciones de visualización heredan el color del texto.
- El botón principal Cobrar utiliza fondo y borde `#0065FE`; mantiene el fondo al pasar el ratón y durante la pulsación. Los estados deshabilitados conservan los colores grises existentes.
- Comprobación en el componente real `SaleScreen`, reutilizando el escenario aislado `frontend/e2e/touch-review/main.tsx` desde un HTML ignorado. Todas las peticiones están interceptadas, los datos son ficticios y no se ha realizado ningún cobro ni escritura en la BD.
- Verificado en el navegador: `rgb(0, 101, 254)` en los cinco menús abiertos, fila con `:hover` real, foco de teclado y Cobrar habilitado. Opciones deshabilitadas grises; navegación con flechas salta las opciones deshabilitadas.
- Capturas a 1920 × 1080: `output/login-ui/sale-menu-hover-cobrar-blue-1920.jpg` y `sale-menu-keyboard-blue-1920.jpg`.
- 12 pruebas existentes aprobadas de `SaleCommandMenuBar` y `SaleTouchControls`. Compilaciones de VENTA y GESTIÓN correctas; presupuestos correctos, CSS principal de VENTA 459864 / 460000 bytes. `git diff --check` correcto.

# Color de selección del idioma en el inicio de sesión

final result: passed

- Opción seleccionada del selector de idioma de VENTA con fondo `#0065FE`, comprobado en el navegador como `rgb(0, 101, 254)` y texto blanco.
- Captura completa `output/login-ui/login-language-selected-blue-full.jpg` y recorte `login-language-selected-blue.jpg`.
- Regla limitada a `.login-sale .language-picker button.selected` en `VentaLoginScreen.css`; conserva las opciones, la selección y los controles existentes.
- Compilación de VENTA y presupuestos correctos; CSS principal permanece en 459862 / 460000 bytes. Se reutilizan las pruebas funcionales anteriores porque solo se modifica una propiedad CSS. `git diff --check` correcto.

# Nombre del terminal y marca fija VENTAS en el inicio de sesión

final result: passed

- Referencia: `C:/Users/YLF/AppData/Local/Temp/codex-clipboard-9efda223-f238-4574-8fb3-88677c34eebf.png`, 1919 × 1082 (captura de escritorio con borde).
- Capturas de la implementación a 1920 × 1080: `output/login-ui/login-terminal-name-es.jpg`, `login-terminal-name-en.jpg` y `login-terminal-name-zh.jpg`.
- Comparación conjunta sin escalar: `output/login-ui/login-terminal-name-comparison.png`, referencia a la izquierda.
- Solo se muestra el nombre del terminal en la cabecera, tarjeta y pie del login de APP VENTA. Se conserva literalmente el nombre configurado, incluido `TERMINAL PRINCIPAL`; si falta, se utiliza el código sin prefijo.
- La marca `VENTAS` es un literal ajeno al catálogo de traducciones. Se retiran únicamente las entradas `login.salesLabel`, ya sin usos. Usuario, contraseña, botones y estado de conexión siguen traducidos.
- El pie compartido mantiene su formato anterior por defecto; la presentación con nombre y sin prefijo se activa únicamente desde el login de VENTA. Gestión, Home y los demás consumidores conservan su presentación.
- Vista previa del componente real con metadatos de presentación y consultas de conexión al backend local; no se ha iniciado sesión desde la vista previa. La aplicación de escritorio sigue usando su identidad protegida y recibe la actualización mediante Vite.
- 40 pruebas aprobadas (LoginScreen, ScreenContextFooter y SessionHomeScreen), incluyendo marca fija en ES/EN/ZH, nombre frente a código y formato compartido por defecto. TypeScript de VENTA correcto.
- Compilaciones Vite de VENTA y GESTIÓN y presupuestos correctos; CSS VENTA 459862 / 460000 bytes, con aviso de proximidad al límite. `git diff --check` correcto.

# Ajuste de las etiquetas F1–F5 de Home

final result: passed

- Etiquetas con radio de 10 px, centradas horizontalmente en cada botón y separadas 6 px del borde inferior. Se conserva el tamaño de 34 × 25 px.
- Capturas: `output/login-ui/home-shortcuts-bottom-1920.jpg` (1920 × 1080) y `home-shortcuts-bottom-1366.jpg` (1366 × 720).
- DOM verificado: desviación del centro 0 px en las cinco etiquetas; separación respecto a los nombres 12 px en las tarjetas secundarias a 1080 px de altura y 2 px a 720 px de altura, sin solapamiento.
- Cambio exclusivo de CSS; siguen siendo etiquetas decorativas `aria-hidden` y se conservan las acciones, permisos y atajos. Se reutiliza el resultado válido de las 11 pruebas de Home, cuyo código no ha cambiado.
- Compilaciones de VENTA y GESTIÓN y presupuestos correctos. CSS VENTA: 459862 / 460000 bytes, con aviso por proximidad al límite. `git diff --check` correcto.

# Validación visual de los botones de Home de esPOS VENTA

final result: passed (alcance: fondos y esquinas de los cinco botones)

## Referencias y evidencia

- Referencia: `C:/Users/YLF/Desktop/imagenes erp/Pantallas/图片_20261003230645_3_238.png`, 1920 × 1080.
- Capturas de la implementación: `output/login-ui/home-before-1920.jpg`, `home-rounded-1920.jpg` y `home-rounded-1366.jpg`.
- Comparación conjunta: `output/login-ui/home-comparison-full.png`, referencia e implementación con viewport de 1920 × 1080, sin escalar las entradas.
- Recorte conjunto de los botones: `output/login-ui/home-comparison-buttons.png`, referencia a la izquierda.
- Foco de teclado: `output/login-ui/home-focus-1920.jpg`.

## Comprobaciones

La vista previa utiliza el componente real `SessionHomeScreen` y el CSS compartido. La caja abierta y la conexión se simulan en el archivo ignorado `.codex-tmp/home.html`, con acciones de navegación vacías; no se ha iniciado sesión ni operado sobre la base de datos.

- Venta: fondo uniforme `rgb(0, 101, 254)` (`#0065FE`), sin gradiente, radio de 36 px y sin borde exterior.
- Gestión, Almacén, Informe y Configuración: fondo blanco uniforme, sin gradiente, radio de 14 px y sin borde exterior.
- El estado deshabilitado de Venta conserva el fondo gris y utiliza también un solo color.
- Comprobados los cinco controles en 1920 × 1080 y 1366 × 768. El foco de Tab y Shift+Tab permanece visible; Venta mantiene el azul al recibir foco.
- Se mantienen las dimensiones, separación, tipografía, textos, logo, iconos existentes y F1–F5. Las diferencias de estos elementos frente a la referencia quedan fuera del cambio solicitado de fondos y esquinas.
- Las 11 pruebas existentes de `SessionHomeScreen.test.tsx` pasan, incluidos acciones, atajos, permisos, guardas de teclado y bloqueo por estado de caja.
- Compilaciones Vite de APP VENTA y APP GESTIÓN correctas. Presupuestos correctos; CSS de APP VENTA: 459835 / 460000 bytes (aviso por proximidad al límite).
- `git diff --check` correcto.

La comparación visual confirma los colores uniformes y las esquinas redondeadas solicitadas. No quedan ajustes pendientes dentro de este alcance.

# Validación visual del inicio de sesión de esPOS VENTA

final result: passed

## Referencias y evidencia

- Composición: `C:/Users/YLF/Desktop/imagenes erp/Pantallas/图片_20261003230719_4_238.png` (1920 × 1080).
- Campos: `C:/Users/YLF/AppData/Local/Temp/codex-clipboard-028c71ba-6564-4867-8b85-62d598f32e3c.png` (482 × 233).
- Corrección del logo: `C:/Users/YLF/AppData/Local/Temp/codex-clipboard-2d8568ac-8076-466e-ab1e-c29f11f77355.png` (525 × 144).
- Implementación: `output/login-ui/login-online-1920.jpg`, viewport CSS y captura 1920 × 1080, densidad 1.
- Comparación conjunta: `output/login-ui/comparison-full.png` (3840 × 1080, referencia a la izquierda).
- Comparación de campos: `output/login-ui/comparison-fields.png`. El recorte de campos de referencia se normaliza de 433 a 470 píxeles de ancho para comparar igual anchura de control.
- Comparación del logo: `output/login-ui/comparison-logo.png`, referencia a la izquierda; la línea roja se utiliza únicamente como guía de alineación.
- Desconexión: `output/login-ui/login-offline-1920.jpg`; comprobaciones adicionales en `login-offline-1366.jpg` y `login-offline-1024.jpg`.

## Estados y comprobaciones

Capturas realizadas en el navegador integrado sobre la aplicación existente. El estado online utiliza el contexto de desarrollo. Para comprobar el arranque offline se utiliza un puente de escritorio simulado en `.codex-tmp/offline.html`, que devuelve únicamente metadatos de presentación y ninguna identidad válida. No se han cambiado la configuración de un terminal real ni los servicios de la copia principal.

- Tarjeta blanca de 570 × 570 px a 1920 × 1080, radio de 50 px y sombra de la referencia; centrada horizontalmente.
- Logo original esPOS encima de la tarjeta, ampliado a 200 × 64 px y con la base de sus letras alineada con VENTAS.
- Campos con etiquetas, iconos grises y botón de ojo; alternar mostrar/ocultar conserva el valor y no envía el formulario. La captura final tiene la contraseña oculta y el campo vacío.
- Botón Entrar con color calculado `rgb(0, 101, 254)` = `#0065FE`.
- Empresa, tienda y nombre del terminal en la cabecera; tienda y terminal en el borde superior de la tarjeta; sin logo en la esquina ni recuperación de contraseña.
- Configurar conexión aparece solo tras confirmar desconexión. Se ha abierto la pantalla de configuración existente y se ha vuelto al login. Reintentar conserva el bloqueo mientras no se verifica el terminal.
- En 1366 × 768 todos los botones quedan visibles; en 1024 × 600 el desplazamiento permite acceder al último botón. Sin desbordamiento horizontal.
- Consola: sin errores ni avisos en los dos recorridos inspeccionados.

## Superficies de fidelidad

**Tipografía:** Arial en el login de Ventas, etiquetas de 26 px, título de 64 px y jerarquía de la referencia; traducciones ES/EN/ZH. Tamaños reducidos en ventanas menores.

**Espaciado:** dimensiones y separación de tarjeta, campos y botón contrastadas en las comparaciones conjuntas. El centrado real sustituye el desplazamiento horizontal del mock. La ausencia del enlace de recuperación responde a la petición del usuario.

**Colores:** fondo azul gris claro, tarjeta blanca, gris en controles, azul exacto en Entrar y rojo en desconexión. La entrada deshabilitada conserva la indicación visual de bloqueo.

**Imágenes:** se reutiliza el PNG original de la marca, sin recrearlo ni deformarlo. Los iconos pertenecen a la biblioteca Phosphor ya utilizada por el ERP.

**Contenido:** VENTAS, usuario, contraseña, nombres de empresa/tienda/terminal y aviso local coherentes con el alcance; sin textos de implementación dentro del producto.

## Historial de comparación

1. La primera captura reprodujo la tarjeta y los campos. El usuario pidió ampliar esPOS y alinear VENTAS con la base indicada en una nueva imagen.
2. Se aumentó el wordmark de 170 × 55 a 200 × 64 px, con ajuste vertical de 7 px; en ventanas pequeñas pasa a 146 × 47 px con ajuste de 5 px. La comparación de logo y la captura completa posteriores verifican esta corrección.
3. Las comprobaciones offline a 1920 × 1080, 1366 × 768 y 1024 × 600 no muestran controles inaccesibles ni pérdida de acceso a configuración.

## Hallazgos y límites

Sin hallazgos visuales P0/P1/P2 pendientes. Como diferencia P3 aceptada, la biblioteca existente ofrece iconos equivalentes, con pequeñas variaciones respecto al dibujo de la referencia.

Validación de código: 62 pruebas focalizadas de componentes, integración y configuración aprobadas; 26 pruebas del puente de vinculación aprobadas; TypeScript de APP VENTA, builds Vite de Ventas y Gestión, límites de bundles y compilación de fuentes y tests Java correctos. La integración Java con PostgreSQL no se ejecutó por ausencia de `TPV_ERP_TEST_DB_URL`.

Las identidades existentes incorporan el nombre de empresa tras contactar con un backend actualizado. Si nunca se ha guardado ese nombre, el login desconectado muestra un guion hasta poder obtenerlo.

## Checklist

- [x] Comparar composición y campos a igual viewport.
- [x] Aplicar y comprobar la corrección del logo solicitada.
- [x] Comprobar ojo, configuración y retorno.
- [x] Comprobar ventanas pequeñas y consola.
- [x] Mantener la identidad y autorización separadas de los nombres offline.

final result: passed

---

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

---

# Design QA — web comercial, dirección editorial 1, 2026-09-15

## Evidencia y normalización

- Verdad visual: `artifacts/marketing-redesign-option-1-source.png`, 1487 × 1058 px.
- Implementación principal: `artifacts/redesign-option1-home-desktop.png`, viewport y captura de 1440 × 1024 CSS px, deviceScaleFactor 1.
- Estados adicionales: `artifacts/redesign-option1-features-desktop.png`, `artifacts/redesign-option1-apps-desktop.png` y `artifacts/redesign-option1-home-mobile.png` a 390 × 844 CSS px, todos en estado inicial de su ruta.
- Comparación normalizada: `artifacts/redesign-option1-comparison-final.jpg`. La fuente se ajustó proporcionalmente a 720 × 512 y la implementación a 720 × 512 para una comparación conjunta de composición.
- Comparación focalizada: `artifacts/redesign-option1-hero-focus.jpg`. Fuente e implementación normalizadas a 1440 × 1024 y recortadas a los 720 × 800 px izquierdos para revisar marca, navegación, titular, cuerpo, CTA y señales de confianza.

## Hallazgos y superficies de fidelidad

- Tipografía: la implementación conserva una sans humanista con pesos, tracking y alturas de línea equivalentes al objetivo. El titular mantiene exactamente dos líneas en escritorio después de la corrección P2; la jerarquía de kicker, titular, promesa, explicación y acciones coincide. En móvil el titular se recompone sin truncamiento.
- Espaciado y composición: coinciden el reparto 50/50 del hero, la navegación centrada, el espacio editorial, el bloque de producto derecho y la franja de cuatro beneficios. Las páginas interiores heredan el mismo ritmo, radios y elevación moderada. No hay desbordamiento horizontal a 1440 ni 390 px.
- Colores y tokens: blanco cálido, tinta `#0e1626`, azul `#1264e8`, cian `#16c7d9` y azul hielo se corresponden con la dirección elegida. Contraste correcto en textos, navegación y acciones.
- Imagen: se reutiliza el activo raster real `public/marketing/hero-ecosystem.png`; no hay sustituciones mediante CSS art, SVG artesanal ni marcadores. El encuadre es ligeramente más contenido que el mock para preservar detalle y evitar recorte en ratios reales; diferencia aceptable P3.
- Iconos: Phosphor mantiene una sola familia visual, tamaños ópticos coherentes y estados hover/focus. No se introducen iconos dibujados a mano.
- Copia: se conserva el mensaje aprobado y la acción pública correcta `Descargar aplicación`, en lugar del acceso web que el producto no ofrece. El texto es más breve que el mock, sin inventar capacidades.
- Interacción y accesibilidad: navegación hash, menú móvil, CTA, pestañas, selección de producto y formulario siguen operativos; foco visible, etiquetas y textos alternativos permanecen. `prefers-reduced-motion` sigue respetado.
- Consola: sin errores en Inicio, Funcionalidades, Apps ni Inicio móvil durante la captura.

## Historial de comparación

- Primera pasada: P2 por ruptura del titular azul en una tercera línea en escritorio y P2 porque la imagen de producto no aparecía dentro del primer viewport móvil.
- Correcciones: hero cambiado a columnas 1:1, escala tipográfica máxima reducida a 64 px, activo ampliado dentro de su superficie y composición móvil comprimida; las señales repetidas se ocultan en móvil antes de la franja de beneficios.
- Evidencia posterior: `artifacts/redesign-option1-comparison-final.jpg` confirma titular en dos líneas y proporción editorial equivalente; `artifacts/redesign-option1-home-mobile.png` confirma que la imagen comienza dentro del primer viewport. No quedan P0, P1 o P2.

## Validación

- Compilación de producción correcta.
- 58 pruebas unitarias correctas.
- Rutas principales capturadas sin overflow horizontal ni errores de consola.
- P3 restante: el activo de producto tiene un encuadre algo más contenido que el concepto generado para proteger su legibilidad en responsive.

final result: passed

## Venta documental · opción 1 · 06/10/2026

Diseño seleccionado por el usuario, con el logo oficial esPOS únicamente en la esquina superior izquierda. Aplicado en la rama codex/venta-login-ui.

### Evidencia y comparación

- Referencia elegida: C:/Users/YLF/.codex/generated_images/01a107c9-8471-7702-ac0b-0be3cf26aaaa/exec-81d1196f-c1c6-4702-bfca-ccd39e8c6517.png (1672 × 941).
- Captura del componente real: .codex-tmp/document-design/final-1920x1080.jpg, viewport 1920 × 1080. Datos simulados; todos los fetch del fixture se interceptan sin fallback al backend.
- Comparación normalizada de referencia y render: .codex-tmp/document-design/comparison-final.png. Comparación focal de cabecera, contexto y primeras filas: .codex-tmp/document-design/header-comparison.png. Ambas inspeccionadas visualmente.
- Vista de revisión: http://127.0.0.1:5185/.codex-tmp/document-review.html?filled=1. Utiliza SalesDocumentScreen y sus estilos de producción; los cobros reales están bloqueados en la fixture.

### Resultado visual

- Una sola imagen de marca, el activo existente espos-wordmark.png, en una superficie blanca arriba a la izquierda. Título, selector Factura/Albarán y cierre comparten la cabecera azul oscuro.
- Panel derecho con total, entrada rápida y acciones de borradores/cobro; tabla de seis columnas ocupa la altura disponible. Azul #0065FE en selección del tipo de documento, Buscar y Confirmar y cobrar.
- Márgenes, división de paneles, tamaños de controles, radios pequeños, jerarquía y colores siguen la opción 1. Se mantiene la fuente empresarial existente.
- P2 corregidos: selección de fila que perdía el fondo frente al estilo alterno; líneas vacías irregulares por el patrón repetido; encabezado Cantidad truncado. Captura y mediciones posteriores confirman fondo #D8E9FB, líneas uniformes y seis títulos completos a 1920 y 1280 px.
- El cierre reutiliza WindowCloseButton: cruz blanca centrada en área 44 × 44, margen superior/derecho de 3 px y fondo visible al hover/foco.
- Se conservaron formatos y contenido reales: fecha ISO, importes, descuentos cero vacíos y preferencias de ancho/orden de columnas. Estas diferencias del ejemplo generado son P3, necesarias para conservar el funcionamiento existente.
- Sin P0, P1 o P2 pendientes en el alcance visual solicitado.

### Verificación

- 24/24 pruebas existentes de SalesDocumentScreen pasan. La aserción de marca se actualizó al logo solicitado.
- TypeScript y compilación de APP VENTA/GESTIÓN pasan. Vite de ambas apps repetido tras los últimos ajustes CSS.
- Presupuestos de bundles pasan: CSS global VENTA 450336 / 460000 bytes (aviso preventivo del checker); GESTIÓN 441749 / 520000 bytes. La hoja de esta ventana se carga con su componente y sustituye las reglas previas de tpv.css.
- Navegador: selección de línea, cambio Factura/Albarán, búsqueda de producto y apertura/cierre del selector de cliente comprobados. Consola sin errores ni avisos.
- A 1280 × 720, el documento mide exactamente 1280 × 720 sin desbordamiento de página; la tabla dispone de desplazamiento horizontal propio. Confirmar y cobrar termina en y=657, dentro de la ventana. Los seis títulos se leen completos.
- Vista final abierta a 1920 × 1080. Sin cambios de lógica fiscal, contratos, permisos, persistencia o backend; no se confirmaron documentos ni se realizaron cobros reales durante la revisión.

final result: passed

## Seleccionar cliente · ajuste de colores · 06/10/2026

- Fila seleccionada #D8E9FB con texto oscuro y indicador lateral #0065FE; se diferencia de la cabecera navy y de las demás filas.
- Seleccionar cliente #0065FE y Cancelar #B42318, ambos con texto blanco. El hover conserva los colores y el botón de confirmación mantiene el estado deshabilitado cuando no hay resultados.
- Eliminados el texto Navegar con sus flechas y el borde amarillo de la cabecera, únicamente en SalesDocumentCustomerDialog. Se conserva la navegación real con flechas, Enter/Insert y Escape.
- Comprobación del componente real en la fixture aislada, con dos clientes de ejemplo y teclado táctil. Estilos calculados: fondo seleccionado rgb(216,233,251), confirmar rgb(0,101,254), cancelar rgb(180,35,24), borde inferior de cabecera 0 px y cero indicaciones de navegación. ArrowDown/ArrowUp cambian la fila correctamente.
- Captura: .codex-tmp/document-design/customer-selector-final.jpg (1047 × 884); modal 1000 × 820 completamente visible. Consola sin errores ni avisos. Vista abierta para revisión.
- 8/8 pruebas existentes de SalesDocumentCustomerDialog pasan; Vite APP VENTA y APP GESTIÓN y presupuestos de bundle pasan. Sin cambios de lógica ni datos reales.

final result: passed

## Venta documental · retirada del bloque vacío · 06/10/2026

- Eliminados la fila con el mensaje Añade productos por código o abre el buscador y sus estilos de relleno. La cuadrícula vacía empieza inmediatamente bajo la cabecera (separación medida: 0 px).
- Vista real del componente con cero líneas, datos de demostración y botones de guardar/cobrar deshabilitados. Captura .codex-tmp/document-design/empty-table-final.jpg; se deja abierta para revisión.
- Se reutilizó la prueba existente de guardado y reinicio, sustituyendo sus dos aserciones del mensaje por la ausencia de filas de producto accesibles. Prueba focalizada: 1 pasa, 23 omitidas intencionalmente.
- Compilaciones Vite APP VENTA/GESTIÓN correctas. No cambian búsqueda, selección, guardado, cobro ni datos reales.

final result: passed

## Pantalla de carga · logo y avance · 06/10/2026

- Imagen facilitada por el usuario copiada sin cambios como esPOS VENTAS, a 504 × 84 px sobre la tarjeta. Fondo #E8EDF6 para integrarla con su fondo original.
- Tarjeta blanca de 600 px, esquinas redondeadas y sombra; eliminado el antiguo distintivo y el borde lateral de color.
- Barra de 36 px: avance #0065FE, parte pendiente #E1E5EB, porcentaje centrado con contraste blanco/oscuro sobre cada fondo. Transición suave y respeto de prefers-reduced-motion.
- El arranque no informa porcentaje real: avance estimado creciente, limitado a 95 % mientras sigue pendiente. El desmontaje limpia el intervalo. Se conservan fases, textos ES/EN/ZH y flujo de arranque.
- Dos pruebas focalizadas pasan: localización y logo, avance sin falsa finalización y limpieza del timer. TypeScript APP VENTA, Vite de ambas apps y presupuestos de bundle pasan; CSS VENTA 450689 / 460000 bytes (aviso preventivo ya existente).
- Vista del componente real con bootstrap pendiente en la pestaña de revisión. Fondo de tarjeta rgb(255,255,255), relleno rgb(0,101,254), pendiente rgb(225,229,235), logo cargado encima de la tarjeta y porcentaje animado comprobados. Consola sin errores ni avisos.
- Captura .codex-tmp/document-design/loading-redesign-final.jpg (1047 × 884); vista previa abierta para revisión.

final result: passed

## Devolución por ticket · botones · 06/10/2026

- Seleccionar todo el ticket y Añadir al carrito en negativo: fondo y borde #0065FE. Quitar selección y Cancelar: fondo y borde #B42318. Texto blanco; reglas acotadas a las herramientas y pie de TicketReturnDialog.
- Sin cambios del componente, validación, selección, valoración ni lógica de devolución. Los estados deshabilitados mantienen la opacidad existente 0.55.
- Fixture aislada del componente real, con ticket y valoración ficticios. Comprobado: añadir y quitar deshabilitados sin selección; habilitados al seleccionar todo; vuelven a deshabilitarse al quitar selección. Los fondos calculados coinciden con ambos colores solicitados. No se añadió al carrito ni se confirmó devolución real.
- Dos pruebas existentes focalizadas de selección pasan; seis omitidas intencionalmente. Vite APP VENTA/GESTIÓN y presupuestos de bundle pasan. CSS VENTA 450883 / 460000 bytes, con el aviso preventivo existente.
- Captura .codex-tmp/document-design/return-buttons-final.jpg (1047 × 884). Consola sin errores ni avisos; vista previa abierta.

final result: passed

## Devolución por ticket · esquinas de selección · 06/10/2026

- Seleccionar todo el ticket y Quitar selección usan el mismo radio compartido de 3 px y borde sólido de 1 px que Cancelar y Añadir al carrito en negativo. Comprobados los estilos calculados de los cuatro botones en la vista real.
- Vite APP VENTA pasa. Captura .codex-tmp/document-design/return-buttons-rounded-final.jpg; vista abierta para revisión.

final result: passed

## Botones de acción de ventanas · VENTA y GESTIÓN · 06/10/2026

- Patrón compartido en DialogActionButtons.css, importado en tpv.css: confirmar/aceptar/insertar/aplicar/guardar/seleccionar en #0065FE, cancelar/cerrar en #B42318, texto blanco, borde sólido de 1 px y radio compartido de 3 px. Se conservan en rojo las confirmaciones de acciones destructivas que ya estaban marcadas danger.
- Se conserva la cabecera #0065FE y el texto blanco acordados para Devolución por ticket.
- 307 ubicaciones JSX de botones, distribuidas en 80 componentes/pantallas de ambas aplicaciones. Inventario en docs/ui/botones-ventanas-espos.md. Incluye diálogos reutilizables y modales inline de venta, documentos, clientes, cobros, producto, stock/edición masiva, almacén, Excel, familias, permisos, alertas y fiscal. Un componente puede producir más de una ventana.
- Las ediciones TSX añaden clases sin cambiar handlers, estados disabled, validaciones, foco, textos o contratos. Las cruces de cabecera conservan su formato previo. Los selectores, pestañas, menús principales y acciones auxiliares del cuerpo quedan fuera del patrón.
- TypeScript APP VENTA/GESTIÓN correcto; diez pruebas existentes de números de serie, edición masiva y desbloqueo de grupos pasan. Compilaciones Vite de ambas aplicaciones y presupuestos de bundle correctos. Se mantiene el aviso preventivo CSS de APP VENTA, dentro de 460000 bytes.
- Navegador: colores y radio calculados correctos en componentes reales de Venta, Stock, Gestión y confirmación común. El foco permanece dentro del diálogo. Aplicar se habilita únicamente con dos dígitos válidos; el desbloqueo mantiene su estado deshabilitado y opacidad propios. Confirmación común revisada en ES/EN/ZH. Consola sin errores ni avisos.
- Capturas .codex-tmp/document-design/dialog-actions-venta.jpg, dialog-actions-stock.jpg, dialog-actions-gestion.jpg y dialog-actions-common-final.jpg. Vista interactiva de revisión abierta, sin conexión a datos ni operaciones reales.

final result: passed

## Números de serie del producto · estilo de ventana · 06/10/2026

- SaleSerialNumberDialog usa cabecera #0065FE, título y producto blancos, cruz compartida blanca, cuerpo blanco y pie gris separado. Mantiene Cancelar #B42318 y Aceptar #0065FE con radio de 3 px. Estilos acotados en SaleSerialNumberDialog.css; se retiran las reglas específicas anteriores de tpv.css conservando las compartidas con devoluciones.
- El cuerpo y los campos admiten desplazamiento; cabecera y pie quedan fuera del área desplazable. Se conservan teclado táctil, escáner, Escape/Enter, foco trampa, validación y confirmación de series sobrantes. El componente es común a venta y venta documental.
- Dos pruebas existentes de edición táctil/escáner y series distintas pasan. TypeScript APP VENTA correcto; Vite APP VENTA/GESTIÓN y presupuestos de bundle pasan (CSS VENTA 451012 / 460000 bytes, aviso preventivo existente).
- Vista del componente real en TOUCH y KEYBOARD, ES/EN/ZH. Treinta unidades desplazan los campos sin ocultar el pie a 1047 × 884. Aceptar se habilita con una serie válida; con series sobrantes requiere confirmar el checkbox, de 18 × 18 px. Cabecera, cruz y botones comprobados mediante estilos calculados; consola sin errores ni avisos. Fixture sin conexión a datos ni operaciones de venta.
- Captura .codex-tmp/document-design/serial-dialog-style-final.jpg; vista interactiva abierta en serial-review.html.

final result: passed

## Números de serie · cabecera azul oscuro · 06/10/2026

- Corrección solicitada: cabecera #0B2E59, con título y cruz blancos. Colores de acciones conservados.
- Color calculado confirmado en navegador (rgb 11, 46, 89); Vite APP VENTA correcto. Captura .codex-tmp/document-design/serial-dialog-dark-header.jpg y vista abierta.

final result: passed

## Ventas guardadas · cabecera y botones de fila · 06/10/2026

- Se retira el borde amarillo únicamente de la cabecera de ParkedSalesDialog y se elimina el párrafo de explicación de Flechas/Enter, junto con su CSS específico. Se conservan descripción, navegación, recuperación, confirmaciones y autorizaciones.
- Los botones Eliminar de cada fila usan fondo blanco, borde sólido de 1 px y texto #B42318, con radio compartido de 3 px. Eliminar todo y Cerrar conservan su formato vigente.
- Seis pruebas existentes de ParkedSalesDialog pasan, incluidos selección con flechas, Enter, recuperación y confirmaciones. Vite APP VENTA/GESTIÓN, presupuestos y diff --check correctos. CSS VENTA 450977 / 460000 bytes; permanece el aviso preventivo.
- Vista real con seis ventas ficticias y solicitudes interceptadas: cabecera con border-bottom 0 px, texto de ayuda ausente y colores/radio calculados correctos. Flechas arriba/abajo cambian la selección; consola sin errores ni avisos. No se realizan recuperaciones ni eliminaciones reales.
- Captura .codex-tmp/document-design/parked-sales-style-final.jpg; vista abierta en parked-sales-review.html.

final result: passed

## Comentario interno de la venta · contador en el campo · 06/10/2026

- Cabecera del comentario sin borde amarillo. Se retira la explicación Visible en GESTIÓN / No se imprime y se coloca longitud/500 dentro de la esquina inferior derecha del textarea, en un wrapper posicionado. Padding inferior de 28 px y separación del tirador de redimensionado para evitar solapamiento.
- Se conservan valor, ref, onChange, teclado táctil, maxLength 500, validación, Cancelar y Guardar. El contador usa la misma longitud existente y funciona en todos los idiomas sin cambiar traducciones.
- Prueba existente focalizada de comentario por venta y ámbitos de borrado pasa (una ejecutada, 240 omitidas). TypeScript APP VENTA y Vite APP VENTA/GESTIÓN correctos. Presupuestos y diff --check correctos; CSS VENTA 451186 / 460000 bytes, con aviso preventivo existente.
- Vista del SaleScreen real en TOUCH, con solicitudes interceptadas: borde inferior de cabecera 0 px y contador contenido en el rectángulo del textarea. Con 500 caracteres indica 500/500 y la tecla Q no añade caracteres; con Prueba + Q indica 7/500 y el foco permanece en textarea. Vista final vacía muestra 0/500. No se guarda comentario ni se realiza una operación real.
- Captura .codex-tmp/document-design/sale-comment-counter-final.jpg; vista abierta.

final result: passed

## Inicio de sesión · historial de usuarios · 06/10/2026

- La imagen aclara que el historial solicitado es el desplegable de nombres del login. No se modifica el historial de ventas F6 inspeccionado inicialmente.
- Solo en el login de escritorio de APP VENTA, se sustituye el datalist nativo por LoginUsernameHistory: panel blanco, borde suave y sombra, esquinas redondeadas y opción activa #0065FE con texto blanco. La lista filtra nombres sin distinguir mayúsculas y mantiene su orden; conserva las entradas ADMIN/admin tal como están guardadas. GESTIÓN y accesos embebidos mantienen su campo previo.
- Combobox/listbox accesible: flechas mueven la opción activa, Enter elige sin enviar formulario, Escape/Tab/pulsación fuera cierran; ratón elige y devuelve foco al usuario. No cambia contraseña, autenticación ni rememberUser: historial de sesión, máximo ocho nombres, únicamente tras autenticación correcta. Etiqueta del desplegable traducida ES/EN/ZH.
- Diecisiete pruebas de LoginScreen pasan, incluidas dos nuevas de selección por teclado/puntero, cierre y ausencia de envío o escritura del historial hasta autenticación correcta. TypeScript APP VENTA, Vite APP VENTA/GESTIÓN, presupuestos y diff --check correctos. CSS VENTA 451186 / 460000 bytes; aviso preventivo existente.
- Vista del componente real en pestaña de revisión independiente, con usuarios ficticios y todas las solicitudes interceptadas. Fondo calculado blanco, activo rgb(0,101,254), navegación/cierre/foco y etiquetas de los tres idiomas correctos. Consola sin errores ni avisos; no se realiza autenticación real.
- Captura .codex-tmp/document-design/login-username-history-final.jpg; vista abierta en login-history-review.html.

final result: passed

## Consulta de precio · búsqueda automática y escaneos consecutivos · 06/10/2026

- Se elimina Buscar de TOUCH y se consulta al cambiar el código, con una pausa de 300 ms que agrupa los caracteres. Enter sigue completando el escaneo inmediatamente; no se bloquea la entrada durante una consulta. Placeholder coherente en ES/EN/ZH.
- Un código encontrado se selecciona completo para que el siguiente código físico o táctil lo sustituya. Enter vacía la captura y conserva el código consultado en la presentación. La detección de ráfagas ya existente permite consultar el nuevo escaneo completo aunque quede un código anterior desconocido en el campo. Un identificador manual incompleto con 404 permanece editable.
- Cada edición cancela el temporizador y aborta/invalida la consulta anterior inmediatamente; las respuestas antiguas no pueden mostrar productos ni errores. Cerrar cancela temporizadores/solicitudes. Se conservan API autenticada, precios del backend, tipos de oferta e imagen con su liberación de URL.
- Catorce pruebas pasan: teclado táctil sin Buscar, debounce, entrada manual, escaneos con/sin Enter y durante carga, sustitución tras desconocido, respuestas 200/404 antiguas, cierre, autenticación de miniatura y precios especiales. TypeScript APP VENTA, Vite APP VENTA/GESTIÓN, presupuestos y diff --check correctos. CSS VENTA sigue en 451186 / 460000 bytes, con el aviso preventivo existente.
- Vista de SaleScreen real en TOUCH con API de demostración interceptada: 8410000000011 muestra PAPEL DE REGALO / 1,50 € sin Enter; el siguiente 8410000000028 sustituye el anterior y muestra BOLSA DE REGALO / 1,00 €. Verificados también Enter, captura vacía después del escaneo y foco. Consola sin errores ni avisos; sin operaciones de venta ni uso de datos reales.
- Captura .codex-tmp/document-design/price-consultation-auto-search-final.jpg; vista abierta en price-consultation-review.html. Lecturas simuladas en navegador y pruebas; no se ha usado un lector físico.

final result: passed

## Consulta de precio · captura sin cuadro visible · 06/10/2026

- Se utiliza la captura visualmente oculta existente también en TOUCH. El campo permanece enfocable y accesible para el lector, teclado físico y teclado táctil; se conserva la búsqueda automática y la sustitución entre códigos.
- La presentación existente muestra el código desde el primer carácter en lugar de ESCANEA PARA CONSULTAR PRECIO. No se modifica consulta, cancelación, precios, imagen ni teclado.
- Catorce pruebas pasan, incluida entrada táctil en captura oculta y sustitución inmediata del mensaje por Q1. TypeScript APP VENTA y Vite APP VENTA/GESTIÓN correctos; presupuestos y diff --check correctos. Sin CSS adicional.
- En SaleScreen TOUCH real con datos ficticios: cuadro ausente, mensaje inicial visible; tecla 0 muestra 0 inmediatamente; 000101 muestra PAPEL DE REGALO y el siguiente 8410000000028 muestra BOLSA DE REGALO sin limpiar. Consola sin errores ni avisos.
- Capturas .codex-tmp/document-design/price-consultation-hidden-prompt.jpg y price-consultation-hidden-capture-final.jpg. Vista abierta para revisión.

final result: passed

## Consulta de precio · tamaño fijo y espera de un segundo · 06/10/2026

- Contenedor flex con dimensiones fijas por modo: 720 × 430 px en KEYBOARD y 1000 × 700 px en TOUCH, limitadas al espacio disponible del viewport. El modificador TOUCH permanece aunque se oculte su teclado. Cabecera fija, formulario flexible con desplazamiento interior y sin min-height distinto según resultado.
- La búsqueda automática espera 1000 ms desde la última edición; cada nueva tecla reinicia la espera. Enter conserva la consulta inmediata. Prueba de debounce actualizada para verificar ausencia de solicitudes a los 999 ms y consulta al completar el segundo; las esperas de pruebas de entrada automática contemplan el nuevo intervalo.
- Catorce pruebas pasan. TypeScript APP VENTA, Vite APP VENTA/GESTIÓN, presupuestos y diff --check correctos. CSS VENTA 451398 / 460000 bytes, con aviso preventivo existente.
- En navegador TOUCH, vacío/producto/error conservan width 1000, height 700, x 29.5 e y 100. Código 000101 consultado sin Enter: producto visible aproximadamente 1091 ms después de terminar la entrada, incluida respuesta ficticia y observación. Captura sin cuadro visible, teclado y precios conservados.
- Captura .codex-tmp/document-design/price-consultation-fixed-delay-final.jpg; revisión abierta con PAPEL DE REGALO / 1,50 € y datos ficticios.

final result: passed

## Historial de ventas F6 · tamaño fijo, teclado inferior y selección · 06/10/2026

- Se conserva el tamaño máximo de 1840 × 1000 px, limitado al viewport, en búsqueda vacía, resultados e historial. El contenedor flex distribuye el espacio interior sin cambiar el ancho al abrir u ocultar el teclado táctil. Cabecera, buscador y pie no se encogen; teclado en el borde inferior. El contenido dispone de desplazamiento interior y la tabla conserva espacio mínimo para filas en ventanas más bajas.
- Las flechas arriba/abajo cambian el producto resaltado, con límites en ambos extremos y desplazamiento hacia la fila activa. Enter abre únicamente su historial. Funcionan desde el buscador y desde una fila enfocada; escribir o limpiar reinicia la selección. Se conserva la búsqueda exacta por código/barcode, orden, filtros, exportación, API SaaS y cierre existentes.
- Nueve pruebas del diálogo y veintisiete del panel pasan (36 total). TypeScript APP VENTA y Vite APP VENTA/GESTIÓN correctos. Presupuestos y diff --check correctos; CSS VENTA 452089 / 460000 bytes, con aviso preventivo existente.
- Revisión con SaleScreen real y API ficticia interceptada: ↑/↓ seleccionan BOLSA DE REGALO entre dos resultados y Enter muestra su código 000102 y tres líneas de historial. Vacío/listado/historial y ocultación del teclado mantienen 1840 × 1000 px a 1920 × 1080; teclado a 1 px del borde inferior. También comprobado 1027 × 868 px a viewport 1059 × 900. Consola sin errores ni avisos; sin operaciones ni datos reales.
- Capturas .codex-tmp/document-design/product-sales-history-selection-final.jpg y product-sales-history-fixed-final.jpg. Vista de historial abierta a 1920 × 1080 para revisión.

final result: passed

## Teclados cerrables · etiqueta Cerrar teclado · 06/10/2026

- El botón compartido de SaleTouchKeyboardScope utiliza la etiqueta específica Cerrar teclado / Close keyboard / 关闭键盘. Se aplica a todos los teclados contextuales alfanuméricos y numéricos de las ventanas donde se monta el scope.
- El control de Cobro en efectivo que oculta su teclado propio utiliza la misma etiqueta. Se conserva el cambio de modo, el botón Mostrar teclado táctil y todos los callbacks, validaciones y controles de la ventana. Inventario de instancias directas de teclados en ambos frontends: no hay otros cierres independientes; los botones que cierran diálogos completos conservan su texto.
- Nueve pruebas existentes del scope, diecinueve de CashPaymentDialog y una focalizada de SalePaymentCheckout pasan (29 total). Se actualizan únicamente los localizadores/assertions existentes del cierre del teclado de efectivo. Vite APP VENTA/GESTIÓN, presupuestos y diff --check correctos. CSS VENTA 452089 / 460000 bytes, sin cambios en estilos.
- En el historial real con datos ficticios, Cerrar teclado oculta el teclado y mantiene la ventana abierta; pulsar el buscador vuelve a mostrarlo. El texto cabe en el botón, consola sin avisos ni errores. Captura .codex-tmp/document-design/keyboard-close-label-final.jpg; revisión abierta a 1920 × 1080.

final result: passed

## Acciones de ventana · cierre a la izquierda y confirmación a la derecha · 06/10/2026

- Distribución compartida en DialogActionButtons.css: fila flex con alineación vertical central; el botón marcado erp-dialog-dismiss queda a la izquierda y las acciones de confirmación o auxiliares a la derecha. Se reordena el JSX para que Cerrar/Cancelar preceda a confirmar en el recorrido de teclado. Los resúmenes conservan su caja y los grupos anidados de botones se integran en la fila. Los pies de contenedores grid abarcan todas sus columnas.
- Marcado de 124 ubicaciones JSX de cierre/cancelación y 120 filas en 70 archivos de componentes comunes, APP VENTA y APP GESTIÓN. Son ubicaciones de código, no un recuento de ventanas únicas. Inventario y alcance en docs/ui/botones-ventanas-espos.md. Incluye historial, clientes, documentos, cobros, impresión, producto, stock, almacén, seguridad y ventanas fiscales. Se conservan callbacks, condiciones, disabled, refs, formularios y traducciones.
- Eliminar, Descartar y otras confirmaciones destructivas permanecen a la derecha con el color previo. No se modifican cruces de cabecera, Cerrar teclado, selectores de fecha ni acciones internas de formularios. Los cierres existentes sin fila en estados de error y resultado de impresión reciben un contenedor de acciones.
- Nueve archivos de pruebas existentes pasan, 73 pruebas en total: historial, números de serie, ventas guardadas, referencia de tarjeta, resultado de cobro, selección de cliente, desbloqueo de grupos, ventana fiscal y familias. TypeScript y Vite de APP VENTA/GESTIÓN correctos. Presupuestos correctos: CSS GESTIÓN 444054 / 520000 bytes y CSS VENTA 452641 / 460000 bytes; continúa el aviso preventivo de CSS VENTA.
- Verificación visual con componentes reales y datos ficticios: Cancelar/Desbloquear, Cancelar/Aceptar y Cancelar/Aplicar quedan en extremos opuestos con el mismo eje vertical. En selección de cliente, Cancelar y Seleccionar cliente mantienen altura de 44 px, el resumen permanece entre ambos y Tab pasa de Cancelar a Seleccionar cliente. En resultado de impresión, Cerrar y Reintentar impresión se alinean en una fila de 376 px. En historial F6, Cerrar queda junto al borde izquierdo del pie y Cerrar teclado conserva su posición.
- Vista final recargada sin nuevos errores ni avisos de consola. Durante edición del fixture hubo avisos históricos de HMR por createRoot duplicado; no se reproducen tras recarga. No se ejecutan cobros, impresiones, eliminaciones ni cambios de datos reales.
- Capturas .codex-tmp/document-design/dialog-actions-left-right-final.jpg y history-close-left-final.jpg. Vistas interactivas de selección de cliente e historial abiertas para revisión.

final result: passed

## Teclado plegable y acciones de Convertir ticket a factura · 06/10/2026

- TouchKeyboardToggle compartido: botón gris #E0E5EB con icono de teclado y triángulo relleno hacia abajo abierto / hacia arriba cerrado. Sin texto visible; nombre accesible, título y aria-expanded conservan la explicación traducida. Se usa en SaleTouchKeyboardScope y en el teclado propio de CashPaymentDialog.
- El scope conserva su destino mientras el teclado está oculto; el botón permanece en la cabecera inferior para volver a abrirlo. El teclado oculto permanece montado y sus teclas quedan deshabilitadas para conservar selección y borradores numéricos sin entrar en el recorrido de Tab. Se mantienen exclusiones del escáner principal, fechas, campos readonly/disabled y data-touch-keyboard=off. Enter/Espacio sobre el botón no llegan a los manejadores de confirmación de la ventana.
- En Convertir ticket a factura se elimina Cancelar del pie; Crear factura usa #07254F para fondo y borde, texto blanco y radio de 3 px. Cruz de cabecera y Escape mantienen el cierre y el bloqueo busy. No cambia conversión, autorización, impresión ni API.
- Cuarenta pruebas pasan: once del scope (incluidas ocultación/reapertura con cursor y borrador decimal), diecinueve de cobro en efectivo, nueve del historial F6 y una focalizada del flujo F12 que cierra por la cruz y comprueba ausencia de Cancelar. TypeScript y Vite de APP VENTA/GESTIÓN correctos. Presupuestos correctos: CSS VENTA 453151 / 460000 bytes, con aviso preventivo existente; CSS GESTIÓN 444054 / 520000 bytes.
- Navegador con datos ficticios interceptados: Crear factura rgb(7,37,79), radio 3 px; botón de teclado rgb(224,229,235), sin texto visible y triángulo correcto en ambos estados. Enter oculta el teclado y mantiene la factura abierta; Tab desde el botón plegado vuelve a la cruz, sin teclas ocultas habilitadas. El mismo botón vuelve a abrir el teclado. Consola sin errores ni avisos; no se crea ni imprime una factura ni se realizan cobros.
- Capturas .codex-tmp/document-design/invoice-keyboard-toggle-final.png e invoice-keyboard-collapsed-final.png. Vista de revisión de factura abierta con el teclado desplegado.

final result: passed

## Convertir ticket a factura · fondo gris, recuadros blancos y lista de clientes · 06/10/2026

- Fondo #E7EDF3, igual al pie de Crear factura, en cuerpo y teclado; se eliminan las líneas de separación de búsqueda, pie y teclado. Campo y resumen del ticket quedan juntos en un recuadro blanco. El recuadro Cliente fiscal, su cabecera y el cliente activo seleccionado usan blanco. Se conservan cabecera azul oscuro, Crear factura #07254F, cruz y botón plegable del teclado.
- Lista de clientes abre un selector con SaleCustomerList, el buscador y la tabla extraídos del listado de Fin. SaleScreen conserva carga, selección, ordenación, miembro, acciones, permisos, creación, edición y cobros. El selector de factura utiliza el mismo endpoint autenticado con q vacío y limit=50 para la apertura, búsqueda con pausa de 180 ms y las mismas siete columnas. Inactivos visibles pero deshabilitados; flechas saltan esos clientes y Enter/Insert o Seleccionar cliente devuelven un activo a la factura sin crearla.
- El selector es hermano de la ventana de factura; el fondo queda inert y aria-hidden. Escape cierra únicamente el selector y restaura el foco en Lista de clientes. Al seleccionar, vuelve al campo fiscal. Las respuestas de búsquedas anteriores se invalidan al cambiar consulta o cerrar. Se mantienen autorización, validación, conversión e impresión existentes.
- Veintisiete pruebas pasan: diez de factura (tres nuevas de listado, selección activa y cierre anidado), once del scope táctil, una de distribución del listado y cinco focalizadas de Fin/F12, incluidos foco, Escape, Insert, creación, edición y cobro de deuda. TypeScript APP VENTA/GESTIÓN correcto tras corregir una opción de tipo de los localizadores de las pruebas; Vite de ambas aplicaciones y presupuestos correctos. CSS VENTA 453151 / 460000 bytes, aviso preventivo existente; CSS GESTIÓN 444054 / 520000 bytes.
- Revisión con datos ficticios interceptados: cuerpo, pie y teclado rgb(231,237,243), pie/teclado sin borde de separación; recuadros y cabecera fiscal blancos. Lista de clientes muestra las siete columnas; un cliente inactivo queda bloqueado. Flecha abajo pasa al segundo cliente activo e Insert devuelve OTRO CLIENTE DEMO a la factura. Consola sin errores ni avisos después de recarga. No se crean facturas, imprimen documentos ni ejecutan cobros.
- Capturas .codex-tmp/document-design/invoice-white-cards-final.png e invoice-fin-customer-list-final.png. Vista de factura abierta para revisión.

final result: passed

## Factura: tamaño fijo y buscador fiscal compacto · 06/10/2026

- Convertir ticket a factura conserva 1000 × 900 px, limitados al viewport menos 32 px. Cabecera y acciones permanecen fuera de la región desplazable; el cuerpo usa filas de altura natural y el teclado mantiene su posición inferior. El tamaño exterior no cambia entre búsquedas, selección, ausencia de resultados ni al plegar el teclado.
- Los resultados del cliente fiscal son una capa absoluta dentro de la ventana, fuera del cuerpo desplazable. Se alinean bajo el campo y se limitan por el borde inferior real de Crear factura; ResizeObserver, resize y scroll en captura actualizan sus medidas. La lista tiene desplazamiento interno y conserva cabecera, selección y bloqueo de clientes inactivos. El espacio del cliente seleccionado permanece reservado. Escape o pulsación exterior cierran únicamente esta lista; Lista de clientes abre el selector Fin existente.
- Buscador fiscal más estrecho, Lista de clientes a su derecha, ambos de 34 px y separados por 12 px. Se elimina Máximo 25 resultados de la vista y se reduce el espaciado de la cabecera. Debounce, límite de 25, endpoints, autorización, conversión e impresión se conservan.
- Veintidós pruebas correctas: once de factura (incluida nueva comprobación de Escape/pulsación exterior sin cierre ni conversión) y once del scope táctil. Se repiten las once de factura tras mover el botón. TypeScript y Vite de ambas aplicaciones correctos. Presupuestos correctos: CSS VENTA 453151 / 460000 bytes, aviso preventivo existente; CSS GESTIÓN 444054 / 520000 bytes.
- Navegador con datos ficticios: antes y después de 25 resultados, ventana 1000 × 688 px y mismas posiciones de recuadro, campo y Crear factura. Desplegable de 804 px de ancho, 272 px de altura máxima y borde inferior 639 px, igual al de Crear factura; contenido de 1001 px con desplazamiento propio. Flecha arriba llega al cliente 25 e Insert lo selecciona sin mover el contenido. Sin resultados conserva medidas. Con teclado abierto el desplegable termina a 423 px, igual al botón, sin cubrir el teclado. Consola sin errores ni avisos; no se crea ni imprime ninguna factura ni se accede a datos reales.
- Capturas .codex-tmp/document-design/invoice-compact-customer-final.png e invoice-fixed-customer-dropdown-final.png; revisión de factura abierta.

final result: passed

## Factura: recuadro de cliente hasta Crear factura · 06/10/2026

- El recuadro exterior blanco de Cliente fiscal incluye el pie con Crear factura y termina justo encima del botón plegable del teclado. El ticket conserva su recuadro independiente. El contenido fiscal y la autorización tienen desplazamiento interior; las acciones permanecen dentro del recuadro y fuera del scroll. El teclado sigue como hijo directo del diálogo, debajo del panel.
- Se conserva el tamaño fijo exterior y el desplegable absoluto, limitado al borde inferior de Crear factura. La medición y ResizeObserver siguen ahora el cuerpo interior del cliente. No cambian validación, búsqueda, selección de activos, credenciales, conversión, impresión ni callbacks.
- Veintidós pruebas correctas de factura y scope táctil; TypeScript y Vite de APP VENTA/GESTIÓN correctos. Revisión visual con datos ficticios: recuadro 962 × 399 px con teclado plegado y 962 × 183 px con teclado abierto en este viewport; ventana 1000 × 688 px en ambos casos. Crear factura queda completamente dentro del borde, que termina 6 px encima del botón del teclado. Desplegable sigue limitado al botón y flechas/Insert seleccionan un cliente. Consola sin errores ni avisos; no se crea ni imprime una factura ni se accede a datos reales.
- Captura .codex-tmp/document-design/invoice-customer-panel-includes-create-final.png. Vista abierta para revisión.

final result: passed

## Factura: teclado blanco, redondeado y Helvetica · 06/10/2026

- Cambio CSS limitado al teclado alfanumérico de Convertir ticket a factura. Fondo blanco, marco y separador vertical sin borde, radio exterior 8 px y sombra 0 3px 10px rgb(7 37 79 / 12%). Teclas con borde #D4DBE3 y radio 4 px. Fuente Helvetica, Arial, sans-serif en el teclado y su cabecera; no se cambian tamaño, distribución, foco, cursor ni manejadores.
- Verificación en navegador: fondo rgb(255,255,255), borde 0 px, radio 8 px, sombra 12%; teclas con radio 4 px, fuente computada Helvetica/Arial/sans-serif y tamaño 20 px. No hay Helvetica registrada en HKLM/HKCU ni fuentes incluidas en el frontend; el equipo utiliza Arial como alternativa. Consola sin errores ni avisos. Captura .codex-tmp/document-design/invoice-white-rounded-keyboard-final.png.
- Vite de APP VENTA y APP GESTIÓN correcto. Se conservan las validaciones funcionales anteriores de 22 pruebas, ya que solo cambia CSS. No se crean facturas, imprimen documentos ni alteran datos reales.

final result: passed

## Teclados compartidos y botón permanente en modo táctil · 06/10/2026

- Estilo de factura aplicado a todos los teclados virtuales existentes: alfanumérico, numérico, efectivo, panel de cobro, calculadora y recuento de efectivo. Superficie blanca sin marco azul, radio 8 px, sombra suave, teclas con borde #D4DBE3 y radio 4 px; se conservan distribución, dimensiones y colores funcionales de acciones especiales. Helvetica, Arial, sans-serif; el equipo mantiene Arial como alternativa disponible.
- TouchKeyboardPanel mantiene un botón blanco con icono de teclado y triángulo abajo/arriba. Al plegar, las teclas permanecen montadas, ocultas y deshabilitadas para conservar cursor, mayúsculas/símbolos y borrador numérico. Los teclados explícitos disponen de su propio control; los del scope usan collapsible=false para evitar duplicación.
- SaleTouchKeyboardScope ofrece un botón plegado antes de enfocar un campo, incluyendo portales. Reasigna el campo al desaparecer o bloquearse y sigue la ventana activa; excluye campos nativos de fecha, readonly, disabled, data-touch-keyboard=off, ventanas inertes/ocultas y el escáner principal. No escribe al mostrar el botón ni desencadena envíos. El panel de cobro conserva su botón entre edición de importe, referencia, vale, comentario y fecha; reabrir desde fecha elige un campo editable.
- Aplicación comprobada en factura, consulta de precios, historial de ventas, devoluciones y diálogos contextuales del scope; buscador y selección de clientes, números de serie, autorización, comentario, nombre temporal y numéricos explícitos; cobro/efectivo, calculadora y recuento de caja. No se encontraron teclados virtuales propios adicionales en APP GESTIÓN o SaaS; no se añaden nuevas pantallas ni flujos.
- 247 pruebas focalizadas correctas en 12 archivos, incluyendo edición/foco/decimales, persistencia del botón, cambio y cierre de ventanas, portales, cobro y regresiones de buscador/cliente/serie/precios. TypeScript y Vite de APP VENTA/GESTIÓN correctos. Presupuestos dentro de límites; se mantiene el aviso preventivo CSS de APP VENTA.
- Revisión visual con datos ficticios e interceptación de peticiones: factura conserva ventana 1000 × 688 px y el botón blanco al cerrar; buscador mantiene panel de 920 px centrado y su pie visible; numérico y calculadora muestran blanco, borde 0, radio 8 px, sombra 12%, teclas radio 4 px/borde gris. Plegar y reabrir calculadora conserva el resultado. No se crearon facturas ni se ejecutaron cobros o escrituras reales.
- Evidencias .codex-tmp/document-design/shared-keyboard-open-final.png, shared-keyboard-closed-final.png, shared-product-keyboard-final.png, shared-numeric-keyboard-final.png y shared-calculator-keyboard-final.png.

final result: passed

## Teclado en el inicio de sesión de APP VENTA · 06/10/2026

- El acceso de APP VENTA incorpora el teclado alfanumérico compartido blanco, redondeado y con sombra. El botón blanco con icono y triángulo permanece disponible antes de autenticar y arranca plegado: el modo de interfaz por terminal se consulta actualmente después de iniciar sesión. No se añade persistencia de preferencias ni lectura de configuración sin token.
- Escribe en Usuario o Contraseña según el último campo enfocado; conserva selección, cursor, texto y estado de mayúsculas/símbolos al plegar. La contraseña continúa enmascarada, y el teclado no almacena ni muestra el valor introducido. Teclas y botón se bloquean mientras se autentica o se muestra la confirmación de apagado. El teclado queda fuera del formulario y no envía credenciales al abrirlo o pulsar sus teclas.
- El teclado permanece en la parte inferior, separado del pie. Al abrirlo se reserva espacio para el formulario; en alturas menores se compacta y, si aparecen acciones de desconexión o errores, el formulario dispone de desplazamiento interior sin superponer el teclado. Gestión y acceso PDA embedded conservan su presentación.
- 34 pruebas correctas en LoginScreen y TouchAlphaKeyboard: edición de usuario/contraseña, cursor/foco, plegado/reapertura, estado ocupado y envío explícito; regresiones existentes de historial, conexión y contraseña. TypeScript y Vite de APP VENTA/GESTIÓN correctos; presupuestos dentro de límites, con el aviso preventivo CSS existente de Venta.
- Revisión visual en 1280 × 720 y 1920 × 1080: Entrar y teclado visibles sin desplazamiento en el estado conectado. En desconexión, Configurar conexión y Reintentar accesibles con desplazamiento del formulario. Consola sin errores ni avisos. Datos ficticios y peticiones de autenticación interceptadas en la vista previa; no se usaron credenciales reales.
- Capturas .codex-tmp/document-design/login-touch-keyboard-final.png y login-touch-keyboard-1920.png. Vista previa de acceso abierta con el teclado desplegado.

final result: passed

## Acceso: botón de teclado dentro del cuadro blanco · 06/10/2026

- Botón blanco movido al espacio entre el campo Contraseña y Entrar, alineado a la derecha. Se elimina la cabecera del teclado con el nombre del campo activo; se conservan las etiquetas de los campos del formulario. Se ajusta el espacio reservado y la separación de las acciones.
- Se mantienen campo activo, foco/cursor, contraseña enmascarada, plegado, estado ocupado y traducciones. El botón es type=button; usarlo dentro del formulario no envía credenciales. El teclado sigue en la parte inferior.
- 20 pruebas del acceso correctas, incluidas apertura con Enter, edición y autenticación únicamente desde Entrar. Compilaciones Vite de APP VENTA y APP GESTIÓN correctas; presupuestos dentro de límites con el aviso CSS preexistente. Revisión visual de teclado abierto/cerrado y posición entre contraseña y acceso, sin peticiones reales de autenticación. Captura .codex-tmp/document-design/login-keyboard-button-between-fields-final.png.

final result: passed

## Ventanas: retirar cierres inferiores normales y confirmar en azul oscuro · 06/10/2026

- Aplicado a los diálogos del inventario de Venta y Gestión. Las cruces de cabecera permanecen; se retiran del DOM los botones inferiores Cerrar/Cancelar normales mediante DialogDismissButton. Los avisos, confirmaciones y resultados mantienen su salida inferior en rojo #B42318. PDA, teclado, calendario y cancelaciones internas conservan sus controles.
- Inventario actualizado en docs/ui/cierres-ventanas-espos.md: 61 componentes, 86 ubicaciones normales, 3 dependientes del estado y 2 avisos conservados expresamente por el helper. Los avisos existentes que siguen usando un botón nativo no forman parte de este recuento. Se revisan los estados de series sobrantes, caja obligatoria, rectificación registrada y retirada registrada. Importar/sustituir certificado usa la X; la confirmación de eliminarlo conserva Cancelar.
- DialogActionButtons.css aplica #07254F a aceptar, insertar, guardar, aplicar y equivalentes, con radio de 3 px. Se conservan callbacks y bloqueos. La X del editor de línea de almacén utiliza closeLineEditor para conservar el retorno de foco.
- Pruebas focales correctas: helper de salida (DOM, ref, disabled, type=button y PDA), 202 de venta pendiente/checkout, 151 de Gestión más 6 de certificado, diálogos comunes y suites de SaleScreen, StockScreen, informes, seriales y caja. Las expectativas de cierre usan la X y mantienen verificaciones de bloqueo, limpieza de credenciales y ausencia de mutaciones. Algunas suites de stock emiten avisos de claves React duplicadas; las comprobaciones pasan.
- TypeScript y Vite de APP VENTA/GESTIÓN correctos; diff --check sin errores. Presupuestos dentro del límite: Gestión JS 429249 y CSS 444054 bytes; Venta JS 368335 y CSS 454982 bytes (aviso preventivo CSS, límite 460000).
- Revisión visual de componentes reales con datos ficticios: ventana normal sin Cancelar inferior, Aceptar computado rgb(7,37,79), radio 3 px; aviso con Cancelar rgb(180,35,24); ventana de desbloqueo de Gestión sin Cancelar inferior y positivo azul oscuro. Se verifica cerrar por X y retorno al selector de revisión. No se realizaron cobros, importaciones de certificados ni escrituras reales.
- Evidencias: .codex-tmp/document-design/dialog-dismiss-normal.png y dialog-dismiss-notice.png. Vista de revisión de acciones abierta con un diálogo normal en español.

final result: passed

## Calculadora: teclado siempre visible y cero ancho · 06/10/2026

- Se elimina el plegado y su botón de teclado solo en la calculadora. El teclado permanece visible y habilitado en modo TOUCH y KEYBOARD. Se conservan el estilo blanco con sombra, los radios y la distribución de cuatro columnas.
- % se mueve a la derecha de CE; 0 ocupa dos celdas en la última fila. Retroceso permanece disponible junto al visor con icono SVG y etiqueta traducida. Se conservan los cálculos, porcentajes, teclado físico, cierre y preferencias del terminal.
- 17 pruebas existentes/adaptadas correctas, incluidos ambos modos de interfaz, aritmética, porcentajes, edición del porcentaje y conservación de su cursor. TypeScript de APP VENTA y compilaciones Vite de APP VENTA/GESTIÓN correctos; diff --check y presupuestos dentro del límite.
- Revisión visual del componente real: sin toggle, CE y % en la misma fila, 0 con grid-column span 2. Se comprueba 70 → Retroceso → 7 → % → 0,07 y edición del porcentaje con Retroceso conservando el foco. Sin operaciones de negocio ni peticiones al backend.
- Captura .codex-tmp/document-design/calculator-fixed-keypad.png; vista .codex-tmp/calculator-review.html abierta para revisar.

final result: passed

## Calculadora: retirar el botón de borrar del visor · 06/10/2026

- Se elimina el icono de Retroceso del visor y sus estilos. Se mantienen C, CE, teclado siempre visible, 0 ancho y % junto a CE. La tecla física Backspace conserva el comportamiento existente.
- 17 pruebas focales correctas y compilaciones Vite de Venta/Gestión correctas. Revisión visual del componente real: el visor contiene cero botones y el 0 conserva grid-column span 2. Sin operaciones de negocio.
- Evidencia .codex-tmp/document-design/calculator-no-delete-button.png.

final result: passed

## Teclado plegado: ocultar el texto del campo activo · 06/10/2026

- TouchKeyboardPanel y SaleTouchKeyboardScope muestran su título solo con el teclado desplegado. El botón blanco de teclado permanece visible y alineado a la derecha. El encabezado de la denominación activa también se oculta al plegar el teclado del recuento de caja.
- 57 pruebas existentes de teclados y recuento correctas; conservan texto, cursor, borradores y ausencia de envío del formulario. Compilaciones Vite de Venta/Gestión correctas y diff --check sin errores.
- Revisión del componente real de factura con datos ficticios: al plegar desaparece el texto Buscar cliente fiscal, el botón permanece a la derecha y al reabrir vuelve el texto y el mismo campo conserva el foco. Sin crear factura ni escribir en el backend.
- Evidencia .codex-tmp/document-design/keyboard-collapsed-no-field-label.png.

final result: passed

## Teclados: Limpiar todo junto al plegado y tecla Enter · 06/10/2026

- Limpiar todo se mueve a la izquierda del botón de ocultar en los teclados compartidos, contexto de campos, login, efectivo y cobro repartido; solo aparece abierto y borra el campo activo. Se mantienen las acciones independientes de eliminar pagos y C/CE de la calculadora.
- Enter se incorpora al teclado alfanumérico y numérico, reutilizando keydown/keyup del campo y el envío implícito normal de formularios cuando corresponde. Se respetan preventDefault, validación y submit deshabilitado; textarea inserta salto de línea. El recuento conserva el avance existente y un único Enter; los cobros conservan sus confirmaciones y bloqueos.
- 454 pruebas distintas correctas: 81 de teclados/login/recuento, 132 de efectivo/cobro repartido y 241 de SaleScreen. Tras ajustar el icono numérico se vuelven a comprobar sus 28 pruebas de teclado/recuento. TypeScript y Vite de Venta/Gestión correctos, con presupuestos dentro de los límites y diff --check sin errores.
- Revisión visual aislada de factura, login y recuento: Limpiar todo queda a la izquierda del toggle, desaparece plegado, la reapertura conserva el campo activo y Enter consulta el ticket simulado sin generar factura. El login limpia el usuario de demostración sin autenticar. Se evita solapar títulos en el recuento compacto. Sin escrituras en BD ni cobros reales.
- Evidencia .codex-tmp/document-design/keyboard-clear-and-enter.png; vista de factura disponible para revisar.

final result: passed

## Teclados: Enter y Shift activo en azul · 06/10/2026

- Enter usa fondo y borde #0065FE con texto o icono blanco en los teclados alfanumérico, numérico, efectivo y cobro repartido. Shift activo utiliza el mismo azul; se conserva el aspecto inactivo y los estados deshabilitados.
- Verificación visual en factura con datos ficticios: Enter y Shift activo computan rgb(0, 101, 254) y texto blanco; al apagar Shift desaparece el azul. Captura .codex-tmp/document-design/keyboard-enter-shift-blue.png.
- Compilaciones Vite de Venta/Gestión y diff --check correctos. Presupuestos dentro del límite: CSS Venta 455702 / 460000 bytes y CSS Gestión 444054 / 520000 bytes. Se reutilizan las pruebas funcionales de teclados, cuyo código no cambia.

final result: passed
