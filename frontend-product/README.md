# Web pública de esPOS

Aplicación comercial independiente del panel interno `frontend-saas`.
Conserva las imágenes y los idiomas español, inglés y chino en cinco secciones:
Inicio, Solución (incluye funcionalidades), Apps, Ventajas y Contacto.

La marca pública es `esPOS` en los tres idiomas. `src/brand.ts` centraliza el
nombre y las rutas de los activos: el wordmark y el favicon en `public/branding`
son copias sin modificar de `frontend/branding/espos-wordmark.png` y
`frontend/branding/app-venta.ico`. No se recrea el logotipo con tipografías o
iconos genéricos. Los identificadores técnicos, variables de entorno, rutas y
nombres de artefactos de publicación se conservan por compatibilidad.

## Solución unificada: presentación compacta

Solución reúne la explicación general, tres principios de funcionamiento y las
cuatro áreas de funcionalidades, sin eliminar información en ES/EN/ZH. Los
principios agrupan icono y texto; las funcionalidades usan tarjetas con cabecera,
descripción y tres capacidades. El diseño presenta dos columnas a partir de
960 px y una por debajo, con tamaños de lectura y alturas naturales. La llamada
a conocer las aplicaciones ocupa una banda final compacta con el enlace separado
del texto. No se ocultan explicaciones ni se fuerza una pantalla sin scroll.

`npm run test:a11y` comprueba contenido completo, jerarquía de títulos, listas,
IDs y relaciones ARIA mediante SSR. El E2E queda preparado para comprobar esta
pantalla a 320, 390, 768, 1024 y 1440 px en los tres idiomas: reflow, contenido
visible, ausencia de recortes/solapes y acceso al enlace de Apps. Solo se comprueba
su sintaxis mientras continúa el bloqueo conocido de acceso al navegador; no se
presentan estos contratos de código como validación visual ni de geometría real.

## Desarrollo

Desde esta carpeta:

```sh
npm ci
npm run dev
```

Web pública: http://127.0.0.1:5176/. La navegación funciona de forma independiente;
el formulario de demostración envía las solicitudes al backend SaaS.
El panel de administración sigue en http://127.0.0.1:5175/.

Para probar el envío local, inicia `backend-saas` en el puerto 8090. Vite
redirige únicamente `/api/v1/public/demo-requests` y sus subrutas hacia ese
servicio, tanto en desarrollo como en preview; no publica el API administrativo.
No hace falta abrir CORS para este acceso del mismo origen. Si el backend se
ejecuta detrás del gateway Compose DEV, configura `PRODUCT_API_PROXY_TARGET`
en `.env.local` con `http://127.0.0.1:8088`. Esta variable solo pertenece al
servidor local de Vite y no se incorpora al bundle.

Si se configura `VITE_SAAS_API_BASE_URL` con un origen distinto, el navegador
lo usa directamente y el backend debe permitir explícitamente el origen de la
web mediante `TPV_SAAS_CORS_ALLOWED_ORIGINS`; no usar comodines ni credenciales
de administración en la web pública.

## Compilación y publicación

```sh
npm run build
npm run preview
```

La carpeta `dist/` se puede publicar en un alojamiento estático independiente.
Define `VITE_SAAS_API_BASE_URL` durante el build si la API vive en otro dominio;
si se omite, las peticiones se envían al mismo origen que la web. El proxy de
Vite **no forma parte de `dist/`**: el alojamiento de producción debe dirigir
esa ruta pública a la API mediante HTTPS. No devolver `index.html` para rutas
de API; un HTML con estado 200 ya no se acepta como confirmación.
Define `VITE_PUBLIC_SITE_URL` únicamente con el origen HTTPS público definitivo,
sin ruta, query ni fragmento, para activar canonical, hreflang y tarjetas para
compartir. La indexación permanece desactivada hasta compilar además con
`VITE_PUBLIC_INDEXABLE=true`; esa decisión requiere revisar antes el despliegue
y los datos legales pendientes. No se inventa una dirección en local.

El centro de descargas usa las variables `VITE_DOWNLOAD_VENTA_URL` y
`VITE_DOWNLOAD_GESTION_URL`. Mientras estén vacías, muestra entrega asistida y
dirige al formulario comercial. Cuando se publiquen instaladores firmados,
pueden añadirse también sus enlaces `.sha256` y `VITE_DOWNLOAD_RELEASE_VERSION`.
No se muestran enlaces de descarga incompletos o de ejemplo.
La vista previa usa http://127.0.0.1:4176/.
Los enlaces nuevos usan `/{es|en|zh}/producto/{pantalla}/`. El build genera
15 directorios canónicos con su `index.html`, tres alias de compatibilidad de
`funcionalidades` y una portada raíz: 19 documentos HTML en total.
El alojamiento debe servir esos archivos y directorios completos desde la raíz
del dominio, junto con `/assets`, `/branding`, `/marketing` y `/contact`.
Las entradas antiguas `#/producto/inicio`, `#/producto/apps`, etc. siguen
funcionando; la navegación posterior usa URLs limpias. No configurar un fallback
HTML para la API ni para cualquier archivo inexistente. Las interacciones y el
envío del formulario requieren JavaScript.

### Publicación automatizada de instaladores

El workflow `.github/workflows/desktop-release.yml` publica una GitHub Release
inmutable al crear un tag `desktop-vX.Y.Z` que coincida exactamente con la
versión de `frontend/package.json`. También puede ejecutarse manualmente sobre
un tag ya existente.

Antes de usarlo, configura estos secretos del repositorio:

- `TPV_DESKTOP_CSC_LINK`: certificado de firma compatible con electron-builder.
- `TPV_DESKTOP_CSC_KEY_PASSWORD`: contraseña del certificado.
- `TPV_DESKTOP_SIGNER_THUMBPRINT`: huella del firmante esperado.

El flujo falla si falta la firma, el sellado de tiempo, un checksum, el tag no
coincide o la release ya existe. Publica los dos `.exe`, sus `.sha256`, un
`desktop-release.json` y `TPV-ERP-PRODUCT-WEB-X.Y.Z.zip`; este último contiene
la web compilada con URLs exactas de esa release. El despliegue de ese ZIP en
el alojamiento público sigue siendo una operación separada.

## Verificación

```sh
npx playwright install chromium
npm run test:e2e
```

La prueba arranca una vista previa temporal en el puerto 5186 y comprueba
navegación y reflow sin scroll horizontal, imágenes, idiomas, menú móvil, estados
de éxito/error del formulario, centro de descargas, recorridos de las cuatro
aplicaciones y la interacción accesible de la FAQ.

El recorrido muestra una captura propia de cada aplicación en
`public/marketing/tour-{venta,gestion,pda,saas}.png`, con la interfaz completa,
sin oscurecerla ni colocar texto encima. Son interfaces reales en español con
datos ficticios, no información de clientes: Venta y Gestión se capturan desde
sus fixtures aislados de `frontend/e2e/touch-review` y
`frontend/e2e/dashboard-review`; PDA y el portal cliente SaaS usan respuestas
de API simuladas. La leyenda y los textos alternativos siguen el idioma de la
web. La prueba verifica cuatro imágenes diferentes, su carga y el cambio de
imagen al seleccionar otra pestaña.

Cada uno de los tres pasos incluye acciones concretas, un ejemplo práctico
y un beneficio cualitativo, definidos en `tourExamples.ts` para español,
inglés y chino. El panel de contexto aclara la plataforma y modalidad de uso
de cada aplicación. Los textos no prometen sincronización instantánea,
integraciones de pago no configuradas ni acceso público al portal cliente.
La matriz E2E comprueba las 36 combinaciones de aplicación, paso e idioma,
su contenido y geometría en escritorio y móvil: no debe haber recortes y se
permite desplazamiento vertical para mantener la lectura en ventanas cortas.

La web incluye además contenido comercial específico para comercio minorista,
alimentación, moda y franquicias. En pantallas altas estos casos se integran en
la portada para aprovechar el viewport sin dejar franjas vacías, mientras que
la composición compacta se conserva en portátiles. Las solicitudes conservan de forma limitada la ruta de
entrada, el referente y `utm_source`, `utm_medium` y `utm_campaign` para medir
campañas sin depender de campos ocultos editables.

Los metadatos, Open Graph y JSON-LD se actualizan según la pantalla y el idioma.
La prueba E2E comprueba también estas señales, el contenido sectorial, la
atribución, las descargas y que los diálogos caben en escritorio.

## Mejora por fases · Fase 1: fiabilidad de captación

La comprobación inicial `GET /api/v1/public/demo-requests/availability` requiere
JSON explícito `{ "available": true }`. Si no responde o devuelve HTML, el
formulario lo indica, deshabilita el envío y ofrece reintentar o contactar por
WeChat. Es una comprobación básica de acceso/esquema, no una garantía de que
una solicitud se haya guardado. Solo un recibo válido del POST confirma el alta.

El POST exige una confirmación JSON con `id` UUID y `receivedAt` ISO. El frontend
acota a 18 segundos también la lectura del cuerpo y distingue validación,
conflicto, exceso de intentos, timeout, red y servicio. Los datos permanecen
en el formulario ante error; no se almacenan datos personales ni recibos en
localStorage/sessionStorage. La atribución de campaña existente conserva su
alcance previo, sin almacenar los campos del formulario.

Cada intento lleva `Idempotency-Key`. Una caché de hasta tres cuerpos normalizados
conserva las claves solo en memoria, por lo que reintentar sin cambios —incluidos
espacios eliminados al normalizar y ediciones reversibles— no crea otra alta.
Un contenido diferente usa otra clave. La protección persiste en el backend
mediante la migración V78 y cubre peticiones concurrentes con la misma clave.
Recargar o abandonar la página descarta los intentos de esa sesión: conservar
la referencia confirmada y no interpretar un timeout como recepción garantizada.

El equipo puede atender solicitudes desde el panel SaaS: **Clientes → Solicitudes
de demo** (`#/demo-requests`). La lectura requiere `VIEW_ADMIN_DATA`; marcar
contactada, cerrar o reabrir requiere `MANAGE_OPERATIONS`. Hay paginación,
filtros y búsqueda, sin el límite operativo de las 100 entradas históricas.
El seguimiento es manual: guardar o cambiar el estado **no envía un correo,
WeChat ni una notificación externa**. Debe asignarse un responsable de revisar
esta bandeja antes de usarla para captación real.

La comparativa ahora orienta por arquitectura sin atribuir carencias universales
a otros proveedores. Portada y FAQ distinguen venta local de sincronización y
servicios externos que requieren conexión. No se inventan precios, certificaciones,
plazos de respuesta ni garantías de implantación.

Por decisión del responsable, el aviso de privacidad y los datos corporativos
permanecen pendientes. No se ha publicado texto provisional ni un enlace vacío.
Esta fase técnica **no acredita que la web esté lista para captar datos en
producción** mientras no se confirmen esos contenidos y la configuración real.

### Verificación de esta fase

```sh
npm run build
npm run test:contract
```

Las pruebas de contrato no usan navegador ni servicios externos y cubren timeout,
cancelación, rechazo de HTML/recibos inválidos, errores y claves de reintento.
`e2e/product-smoke.mjs` mantiene pruebas de interfaz con API simulada: no sustituye
la prueba de backend con PostgreSQL ni la aceptación integral en el despliegue
de destino. El backend incluye `DemoRequestPostgreSqlTest` sobre base exclusiva
de prueba y esquema aleatorio. No ejecutar esa prueba contra datos de trabajo.

Validación local realizada el 15/10/2026: 47 pruebas de backend (incluidas cinco
con PostgreSQL real), siete resultados de contrato del formulario y 104 pruebas
del panel SaaS aprobadas; ambos frontends compilan. En un entorno PostgreSQL
exclusivo se verificó también desde el navegador el paquete completo, el recibo,
la búsqueda de su referencia y su cambio a «Contactada», confirmado al consultar
de nuevo la bandeja. No se enviaron mensajes externos ni se usaron datos reales.
Las capturas quedan en `output/phase1-verification/` desde la raíz del repositorio.
La comprobación visual se realizó en escritorio; no se da por validada la fase
móvil/accesibilidad ni se ha ejecutado el script E2E completo en esta entrega.

La ampliación comercial de la fase 2 y la implementación móvil/accesibilidad
de la fase 3 se documentan a continuación. La aceptación visual/interactiva
de estas fases sigue pendiente. La implementación de adquisición, SEO y peso
medido de la fase 4 se documenta también a continuación, sin publicación.

## Fase 2: información para elegir cada aplicación

La pestaña **Apps** incluye un explorador de Venta, Gestión, PDA y SaaS. La
aplicación elegida tiene imagen real diferenciada y datos de demostración
identificados; no se inventan clientes, testimonios o cifras. Sus cuatro
secciones separan tareas, un ejemplo cotidiano de tres pasos, requisitos y dos
preguntas específicas. Cambiar de aplicación vuelve al resumen; cambiar de
idioma conserva la aplicación y sección elegidas.

`applicationGuides.ts` centraliza el contenido completo ES/EN/ZH con un contrato
tipado. `ApplicationExplorer.tsx` organiza la información con dos grupos de
pestañas accesibles por flechas, Inicio y Fin. El contenido no se recorta para
forzar una altura: en ventanas pequeñas se permite desplazamiento natural.
El botón de demo preselecciona la aplicación en el formulario existente; el
recorrido anterior y la selección de paquete completo siguen disponibles.

El alcance se contrasta con la documentación operativa y el código del proyecto:

- Venta y Gestión necesitan acceso al servicio de tienda; instalación local no
  equivale a poder operar sin ese servicio. Véanse `docs/backend-connection-recovery.md`
  y `docs/terminal-linking.md` desde la raíz del repositorio.
- PDA es un cliente web para un dispositivo compatible autorizado desde Gestión.
  Comprobar un documento confirmado no duplica entradas de stock; véase
  `docs/pda-warehouse-operations.md` y sus pantallas de inventario y reposición.
- SaaS describe el portal cliente `frontend-saas/tienda.html`, no el panel interno.
  Consulta documentos recibidos y stock/sincronización por tienda, según permisos;
  las facturas citadas son las del servicio. Véanse `TenantPortal.tsx` y
  `TenantSupervision.tsx` en `frontend-saas/src/features/tenant`.

La orientación de inicio permite solicitar una app, varias o todo el paquete.
Compatibilidad, módulos, integraciones y condiciones se confirman para cada
negocio; no se añaden precios, fechas, certificaciones ni garantías universales.
El aviso de privacidad y los datos corporativos continúan pendientes por
decisión del responsable.

Comprobaciones locales:

```sh
npm run build
npm run test:guides
npm run test:render
npm run test:contract
```

Las pruebas de contenido cubren las 12 combinaciones de aplicación e idioma,
estructura equivalente y ausencia de textos provisionales. Las de renderizado
comprueban el componente sin navegador ni API. El script E2E se adapta al nuevo
selector, pero no se ejecuta en esta fase: el acceso del navegador integrado
quedó bloqueado. El diseño visual y la interacción real de esta entrega quedan
pendientes de esa verificación; no se sustituyen por capturas de fases anteriores.

## Fase 3: móvil, accesibilidad y navegación

Se conserva la identidad visual y el contenido comercial. La estructura tiene
un único `main` con título por pantalla, separado de cabecera y pie. El enlace
«Saltar al contenido» aparece al recibir foco y no cambia la ruta hash. Al
cambiar de página se cierra el menú y el foco pasa al contenido, sin forzarlo
en la primera carga ni al cambiar de idioma. El menú puede cerrarse con Escape,
que devuelve el foco a su botón. Las etiquetas de navegación, menú y recorrido
están traducidas en `marketingUiI18n.ts` para ES/EN/ZH.

Descargas, recorrido, FAQ y QR usan la misma capa modal, fuera del contenedor
de fondo. Al abrirse, el fondo queda `inert` y oculto para tecnologías de
asistencia; el diálogo mantiene Tab dentro de sus controles disponibles y se
cierra con Escape. Al cerrar se restauran el scroll y los atributos anteriores,
y se devuelve el foco al origen si sigue disponible o al contenido en su defecto.
Las pestañas del recorrido admiten flechas, Inicio y Fin. La comparativa
conserva encabezados de columna y de fila también en su presentación móvil.

Los estilos eliminan alturas rígidas y columnas mínimas que podían recortar
texto. Se apilan tarjetas y secciones en anchos pequeños, se usan campos de
16 px y zonas principales de interacción de al menos 44 px. El menú y los
diálogos disponen de desplazamiento cuando no cabe todo en altura, con unidades
de viewport dinámico y márgenes de seguridad del dispositivo. El foco tiene
contorno sólido y halo sobre fondos claros/oscuros; se respeta movimiento
reducido y se añaden reglas para colores forzados. El scroll vertical natural
es intencional: no se oculta información para imponer una única pantalla.

Verificación local realizada el 15/10/2026:

```sh
npm run build
npm run test:a11y
npm run test:render
npm run test:guides
npm run test:contract
```

Compilación y las cuatro suites aprobadas. La suite nueva cubre 18 renders
(seis rutas por tres idiomas), relaciones ARIA, IDs únicos, etiquetas nativas,
estado inicial seguro del formulario y funciones de índices/filtrado de foco.
Se ejecuta sin navegador, hidratación, API ni servidor HTTP; las funciones de
foco usan dobles de métodos explícitos, no una comprobación de foco real.

El E2E queda adaptado para usar selectores de idioma alcanzables (cerrando
primero el diálogo), reflow a 320/640 px, salto al contenido, cierre del menú,
aislamiento modal y retorno de foco. Solo se ha comprobado su sintaxis; no se
ha ejecutado porque sigue vigente el bloqueo de acceso del navegador integrado.
Quedan pendientes la validación visual, efectos reales de foco/`inert`, zoom
real, dispositivos y lectores de pantalla. Estas pruebas de código no acreditan
conformidad WCAG ni sustituyen esa aceptación. No se publican capturas antiguas
como evidencia de la fase 3.

No se modifican la API, las condiciones comerciales ni los datos legales
pendientes. WeChat, selección múltiple/paquete completo, recibos y recuperación
de errores del formulario se conservan.

## Fase 4: adquisición, SEO y peso medido

### Enlaces e indexación

Las cinco pantallas tienen una URL distinta en cada idioma. El idioma explícito
de la URL prevalece sobre la preferencia guardada; cambiar de idioma conserva
la pantalla y la query. La navegación cliente usa History API y respeta los
clics modificados/abrir en otra pestaña. Los enlaces antiguos con hash se
interpretan, pero ya no se usan como canonical. El prerender de compilación
incluye contenido semántico, título y metadatos específicos en 19 HTML:
15 páginas canónicas, tres alias de compatibilidad y la portada raíz.
`/{idioma}/producto/funcionalidades/` y `#/producto/funcionalidades` muestran
directamente la nueva Solución completa, sin redirección ni pérdida de la query.
Los metadatos de esos alias apuntan a `solucion`; el sitemap incluye únicamente
las 15 rutas canónicas. El explorador de Apps conserva su resumen
inicial de Venta; cambiar sus pestañas sigue siendo una interacción JavaScript.

Este enfoque sigue las recomendaciones oficiales de Google sobre
[History API y enlaces rastreables](https://developers.google.com/search/docs/crawling-indexing/javascript/javascript-seo-basics)
y [URLs diferenciadas por idioma](https://developers.google.com/search/docs/specialty/international/managing-multi-regional-sites).
No implica que Google indexe o posicione automáticamente la web.

Canonical, alternativas recíprocas `hreflang`, Open Graph y tarjetas sociales
solo reciben URLs absolutas con un origen HTTPS público válido configurado.
Sin ese dato, no se generan URLs falsas. `noindex, follow` es el valor seguro
por defecto, también si hay dominio pero no autorización explícita. El sitemap
solo se genera con dominio válido y `VITE_PUBLIC_INDEXABLE=true`. `robots.txt`
permite leer las páginas y sus etiquetas `noindex`: no es una barrera de acceso.
Los datos estructurados describen la página y, en Inicio, el software. No se
atribuye una identidad societaria no confirmada ni se publica una FAQ global;
no se añaden ofertas, precios ni reseñas.

### Solicitudes compartibles y contexto de campaña

Contacto acepta únicamente las aplicaciones conocidas en la query:

```text
/es/producto/contacto/?apps=venta
/es/producto/contacto/?apps=gestion,pda
/es/producto/contacto/?apps=all
```

La selección se conserva al recargar un enlace. Los botones específicos de Apps,
recorridos y descargas llevan el producto correspondiente. La demo de Apps es
un enlace real, también utilizable en otra pestaña. La query no confirma una
solicitud ni modifica el contrato del backend. La navegación interna y el historial
sin una selección explícita conservan la última elección; una visita nueva sin
selección válida comienza en Venta. Un cambio de configuración descarta el
recibo, errores y respuesta local pendientes de la configuración anterior, pero
conserva los campos escritos. Cancelar la espera local no anula un alta que el
servicio ya haya recibido.

No se añaden cookies, eventos de analytics ni rastreadores externos. Se conserva
solo el contexto de campaña que ya acompañaba al formulario: primera ruta de
entrada sin query libre, referente sin credenciales/query/fragmento y las tres
UTM conocidas, acotadas y saneadas. Se rechazan datos personales o secretos
obvios y parámetros duplicados. Este filtro conservador no garantiza anonimizar
cualquier texto libre; no deben introducirse datos personales en una campaña.
Los campos del formulario y los recibos no se escriben en almacenamiento web.

### Rendimiento: medición reproducible, no una puntuación de navegador

Los cuatro diálogos se separan en un módulo bajo demanda, con estados traducidos
de carga/error, reintento y cierre, manteniendo el aislamiento y foco de la fase
3. Las imágenes tienen dimensiones reservadas; la portada prioriza su imagen
principal y las vistas secundarias usan carga/decodificación diferidas cuando
corresponde. El QR y los activos originales no se recortan ni recomprimen.

`performance-budget.json` define límites explícitos de peso, referencias
locales y número de páginas. La compilación los comprueba después del prerender;
una regresión que supere esos límites falla el comando. También se puede obtener
un informe JSON local ignorado por Git:

```sh
npm run check:performance
node scripts/performance-budget.mjs --output artifacts/performance-budget.json
```

Medición local del 15/10/2026, bytes reales y gzip nivel 9 por archivo:

| Medida | Antes de fase 4 | Fase 4 |
| --- | ---: | ---: |
| HTML generados | 1 | 19 |
| JS inicial, sin comprimir | 397.140 | 393.361 |
| JS inicial, gzip | 119.689 | 120.611 |
| CSS, gzip | 16.414 | 16.426 |
| JS diferido de diálogos, sin comprimir | integrado | 14.206 |

El JS comprimido inicial no disminuye respecto a la fase anterior: el coste de
rutas, captación y estados de recuperación compensa la división de los diálogos.
No se presenta esta medición como una mejora de velocidad demostrada. El build
completo ocupa 4.090.920 bytes, incluyendo todas las páginas y activos; ese total
no es lo que descarga cada visita. El informe sigue señalando dos imágenes
grandes: `contact-ecosystem.png` (1.821.981 bytes) y `hero-ecosystem.png` (777.623).
La segunda no está referenciada por las pantallas actuales. Esta medición es
anterior a la optimización de imágenes documentada más abajo.

No se ha medido transferencia HTTP, caché del alojamiento, LCP, INP, CLS,
Lighthouse ni Core Web Vitals. El presupuesto analiza archivos y dependencias
estáticas, excluye imports dinámicos del código inicial e inventaría las imágenes
sin asumir que se descargan. Las métricas reales requieren una sesión de
navegador permitida y, posteriormente, el alojamiento final.

### Verificación

```sh
npm run build
npm run test:seo
npm run test:campaign
npm run test:navigation
npm run test:performance
npm run test:a11y
npm run test:render
npm run test:guides
npm run test:contract
```

Las suites pasan con SSR y pruebas puras, sin navegador, HTTP ni API real.
Una prueba de symlinks/junctions se omite porque esta cuenta Windows no permite
crearlos; se mantienen los demás casos de rutas y referencias inseguras. Los
dobles de History API prueban llamadas, no Atrás/Adelante reales. El script E2E
queda adaptado a las URLs limpias y a la selección por enlace, pero solo se
valida su sintaxis. Siguen pendientes la validación visual/interactiva y las
métricas de navegador por el bloqueo conocido. No se publica la web, no se
habilita indexación y no se completan datos corporativos o de privacidad.

## Seguimiento de fase 4: imágenes optimizadas

Los dos PNG originales se conservan, con el mismo SHA-256, en
`assets/marketing-sources/`. Se trasladan fuera de `public/` para que las copias
pesadas no formen parte del despliegue. No se redibujan, recortan ni cambian de
composición. Son maestros recuperables, no archivos eliminados sin respaldo.
La imagen `hero-ecosystem` sigue sin activarse en ninguna pantalla.

`public/marketing/optimized/` contiene 13 representaciones generadas y un
manifest con hashes, tamaños, dimensiones y versiones/configuración del
codificador. Contacto dispone de AVIF y WebP en 480, 768, 1024 y 1536 px, más
JPEG progresivo de respaldo en 768 y 1536 px. El hero sin uso tiene AVIF, WebP
y JPEG en sus 865 px originales. Se conserva la proporción y no se amplía ningún
maestro. La compresión tiene pérdida controlada; no se afirma igualdad de píxeles
en los derivados. Los originales sí permanecen byte a byte idénticos.

Pesos medidos a resolución completa, en bytes:

| Imagen | PNG original | AVIF | WebP | JPEG de respaldo |
| --- | ---: | ---: | ---: | ---: |
| Contacto, 1536 × 1024 | 1.821.981 | 100.388 (−94,5 %) | 180.778 (−90,1 %) | 340.134 |
| Hero, 865 × 795 | 777.623 | 44.217 (−94,3 %) | 70.308 (−91,0 %) | 137.083 |

Contacto en AVIF pesa 24.709 bytes a 480 px y 43.627 bytes a 768 px. El navegador
elige un formato y un tamaño según soporte, ancho mostrado y densidad de píxeles;
no se suman todas las variantes como si fueran una descarga. Los derivados y su
manifest suman 1.364.608 bytes frente a los 2.599.604 de ambos maestros. El build
completo pasa de 4.090.920 a 2.863.142 bytes, aproximadamente un 30 % menos. El peso
total del build tampoco equivale a los bytes transferidos por cada visita.

`MarketingImage` produce `<picture>` con `srcset`, `sizes`, respaldo JPEG y
dimensiones explícitas. Inicio conserva carga inmediata y prioridad alta en su
imagen principal. El fondo decorativo de Contacto mantiene carga diferida y
prioridad baja. Las demás rutas no referencian esa escena. No se añaden preloads
de varios formatos: las fuentes ya están en el HTML generado, siguiendo la
[guía de imágenes responsive de web.dev](https://web.dev/learn/design/responsive-images).
El JPEG para tarjetas sociales también usa el nuevo archivo optimizado.

Se revisaron localmente AVIF decodificado y WebP de las dos imágenes. La
composición y el detalle se mantienen visualmente en esa inspección; no es una
comparación de capturas del sitio ni una medición de LCP o velocidad en navegador.
El QR original, el wordmark, el icono y las cuatro capturas de aplicaciones
conservan sus hashes anteriores. El JPEG de respaldo de 340.134 bytes continúa
generando un aviso informativo por superar 300 KB; no se oculta esa advertencia.

Los límites del build se reducen a 3.500.000 bytes totales y 400.000 por activo,
para evitar reintroducir los PNG públicos pesados. No se cambian indexación,
dominio, API, formularios ni condiciones comerciales en esta optimización.

### Regeneración y verificación

La compilación normal consume los derivados incluidos en el repositorio y no
necesita Sharp ni una conexión de red. Para regenerarlos, el script usa Sharp
0.35.5; puede resolver una instalación local o recibir `--sharp-module` con la
ruta absoluta a una instalación confiable. No se descarga ni instala por su cuenta.

```sh
npm run optimize:images
npm run optimize:images -- --check
npm run test:images
npm run build
```

Cuando Sharp esté fuera del proyecto, añadir `--sharp-module` y su ruta a los dos
primeros comandos. `--check` vuelve a codificar y compara los 13 binarios y el
manifest byte a byte con la misma versión del codificador. Esa comprobación se
realizó correctamente con la herramienta instalada en el entorno de trabajo.

La suite nueva verifica 13 archivos reales, sus cabeceras/dimensiones, hashes,
proporciones y presupuestos; también los siete activos protegidos y las 18
rutas renderizadas en ES/EN/ZH. No usa navegador, listener HTTP ni API. El E2E
queda preparado para comprobar el formato seleccionado y la prioridad del hero,
pero solo se comprueba su sintaxis por el bloqueo de acceso conocido. La elección
real de variante, ausencia de descargas duplicadas, layout y tiempos de carga
siguen pendientes de una sesión de navegador permitida.

## Diseño compacto en las cinco pantallas

Inicio, Solución, Apps, Ventajas y Contacto comparten una densidad más compacta,
sin exigir que toda la información quepa en una sola altura de ventana. Los
contratos SSR conservan un único `h1` por ruta e idioma, los cuatro beneficios y
cuatro ejemplos de sectores de Inicio, las acciones enlazables, las cuatro
aplicaciones y cuatro pestañas informativas, todas las filas y celdas de la
comparativa y el formulario con selección múltiple, paquete completo, consentimiento
y acceso al QR original de WeChat. El envío sigue bloqueado hasta comprobar la
disponibilidad real del servicio; renderizar el formulario no envía datos.

El E2E queda preparado para reflujo en 320, 390, 768, 1024 y 1440 px en los tres
idiomas. Comprueba contenido conservado, controles alcanzables mediante scroll
vertical natural, ausencia de solapamientos y desbordamiento horizontal, sin
imponer una altura máxima de pantalla. En esta entrega solo se comprueba la
sintaxis del E2E: no se inicia navegador ni se realizan peticiones HTTP por el
bloqueo de acceso conocido. SSR y sintaxis no certifican el diseño móvil o de
escritorio, el foco real ni las métricas de carga; esas comprobaciones visuales
e interactivas siguen pendientes de una sesión de navegador permitida.

## Alcance

Tiene su propio package.json, lockfile, dependencias, estilos, recursos y build.
No importa código del panel SaaS.
El idioma usa la URL explícita o, para entradas antiguas, la preferencia
`tpv-product-language` como alternativa.
El formulario de contacto permite elegir varias aplicaciones mediante casillas
o seleccionar las cuatro con «Me interesa el conjunto completo». Las tarjetas y las casillas
comparten la selección; los accesos desde recorridos y descargas siguen
preseleccionando su aplicación. La API recibe `products` con la lista completa
y mantiene `product` como primer producto para compatibilidad. La migración
SaaS V77 conserva también la aplicación de las solicitudes anteriores.
Las solicitudes comerciales se validan y guardan en el backend SaaS, y pueden
consultarse desde su endpoint administrativo protegido.

Contacto muestra también la cuenta comercial de WeChat facilitada por el
equipo. El nombre y el ID públicos se mantienen en `src/contact.ts`; los
textos explicativos se traducen en español, inglés y chino. «Copiar ID» solo
accede al portapapeles al pulsar el botón y nunca envía el formulario. Si el
navegador lo impide, selecciona el ID para copiarlo manualmente. El QR real
facilitado por el equipo se conserva, sin recortes ni recompresión, en
`public/contact/wechat-espos.jpg`; su ruta está definida en `src/contact.ts`.
La miniatura abre una vista ampliada con la imagen completa, cierre mediante
botón o Escape y devolución del foco al botón de origen. No se inventan enlaces
de WeChat. El formulario sigue disponible como alternativa. La prueba E2E cubre
la integridad del JPEG original, su carga y ampliación en los tres idiomas,
además de la copia correcta, denegada y sus mensajes.

## Información para elegir aplicaciones y entender la conexión

La página Apps incorpora una guía desplegable con cuatro escenarios orientativos:
caja y administración (Venta + Gestión), almacén (Gestión + PDA), consulta central
de instalaciones existentes (SaaS) y el conjunto de las cuatro aplicaciones.
Cada escenario explica sus dependencias y enlaza a Contacto con la selección
correspondiente. Los enlaces conservan su destino nativo al abrirlos en otra pestaña.
Las combinaciones expresan interés para una demostración: no son paquetes
comerciales, reglas de licencia ni una promesa de despliegue independiente.

Contacto aclara junto a las casillas que la selección no supone una compra y que
los módulos, licencias, dispositivos y condiciones se concretan para cada negocio.
El aviso se asocia al grupo mediante `aria-describedby`; el control de selección
de las cuatro aplicaciones y el contrato de envío de `products` no cambian.

Solución muestra antes de las funcionalidades un resumen visible de servicio de
tienda, internet y consulta sincronizada. Apps mantiene también una explicación
de conexión por aplicación visible fuera de sus pestañas de detalle. Los textos de
Inicio, Ventajas, recorridos y FAQ distinguen operativa local de sincronización y
definen SaaS como consulta de documentos y stock recibidos, licencias, facturas del
servicio y soporte según permisos, no edición remota completa. Descargas especifica
Android y acceso autorizado al servicio de tienda para PDA, igual que su guía.

El contenido está disponible en español, inglés y chino. `npm run test:information`
valida traducciones, combinaciones, dependencias, enlaces, preselección, semántica
del conjunto completo y presencia de los límites de conexión mediante contratos
de datos y renderizado SSR, sin navegador, HTTP ni envío de solicitudes reales.
Estas pruebas no sustituyen la comprobación visual o interactiva en un navegador
autorizado; esa verificación sigue pendiente por el bloqueo de acceso conocido.

## Implantación, compatibilidad y soporte: información orientativa

Apps añade tres desplegables nativos cerrados inicialmente, con el mismo estilo
compacto y contenido ES/EN/ZH. No constituyen una oferta comercial ni ejecutan
instalaciones o cambios en las aplicaciones del negocio:

- `ImplementationJourney.tsx` y `implementationContent.ts` describen cuatro
  etapas posteriores a la demo: alcance y responsabilidades, muestra de datos y
  equipos, configuración y pruebas, y preparación de formación y arranque si se
  acuerdan. Cada etapa distingue qué preparar y qué confirmar. Se conserva la
  guía anterior de tres pasos para preparar la demostración.
- `CompatibilityChecklist.tsx` y `compatibilityContent.ts` reutilizan los
  requisitos de las guías existentes y separan las comprobaciones pendientes
  por instalación: versiones y recursos, periféricos, terminal de pago, Excel,
  red, autorización PDA, permisos y sincronización SaaS. No se publican modelos
  certificados ni mínimos inventados. Aparece también en «Qué necesitas» de
  cada app y en el centro de descargas, conservando el idioma del modal.
- `SupportInformation.tsx` y `supportContent.ts` explican el contacto existente
  de WeChat, el soporte SaaS según permisos, la información técnica necesaria
  para comunicar incidencias y las cuestiones por acordar sobre cobertura,
  formación, respuesta, copias y restauración. Contacto incorpora este bloque y
  la guía de implantación. El botón de WeChat reutiliza el QR existente; el enlace
  de Apps abre Contacto. No se inventan enlaces a WeChat, correos ni horarios.

Los textos generales y las FAQ distinguen las capacidades técnicas de permisos,
auditoría y trazabilidad de un servicio de copias o acompañamiento cuya inclusión
debe confirmarse. No se fijan precios, plazos, niveles de servicio ni exclusiones
comerciales. La referencia de recepción confirma un registro, no una cita ni un
correo enviado automáticamente. Ver el QR o copiar el ID no envía el formulario.
Se pide compartir solo información técnica y muestras anonimizadas o ficticias,
nunca contraseñas, tarjetas o bases de datos de clientes. No se alteran la política
de privacidad pendiente, el contrato de solicitudes, las bases de datos ni el backend.

`npm run test:implementation` comprueba contenido, idiomas, responsabilidades,
requisitos reutilizados, listas por app, semántica de los desplegables, enlaces,
callbacks sin ejecutar e integración SSR sin navegador, HTTP ni solicitudes reales.
La presencia en las pestañas de configuración y en el modal de descargas se
comprueba mediante contratos de código; no equivale a probar su interacción o
geometría en móvil y escritorio. La revisión visual sigue pendiente de acceso
autorizado al navegador. Los presupuestos existentes de carga no se amplían.

## Revisión editorial ES/EN/ZH

Los textos de páginas, guías, combinaciones, conexión, compatibilidad,
implantación, soporte, recorridos, descargas, estados del formulario y metadatos
se revisan con el mismo alcance funcional. La PDA comprueba documentos ya
confirmados sin duplicar entradas; SaaS consulta documentos y datos de existencias
recibidos, además de información del servicio, sin prometer gestión remota completa.
Las facturas del servicio no se presentan como contabilidad consolidada. Los
requisitos de red, servicio local, permisos y sincronización se conservan.

Se distingue la información de publicación de una verificación de firma o SHA-256
realizada por el usuario. El pipeline de publicación mantiene sus controles
existentes; la interfaz no afirma comprobar automáticamente un instalador.
El estado de disponibilidad del formulario tampoco afirma nada sobre solicitudes
previas ni fija una espera no definida para reintentar. No se cambian el transporte
de solicitudes, el acceso a WeChat ni las condiciones comerciales o de privacidad.

Para reducir repetición sin ocultar requisitos: «Qué necesitas» muestra su lista
una vez y conserva el resumen permanente de conexión; el desplegable contextual
de compatibilidad utiliza `includeRequirements={false}` y presenta las
comprobaciones pendientes. Las versiones completas de Apps y Descargas mantienen
requisitos y comprobaciones. En Contacto, `showAccount={false}` evita repetir el
ID de WeChat dentro de Soporte, ya que la tarjeta principal lo muestra y permite
copiarlo. El soporte independiente conserva la cuenta por defecto. Los estilos,
las rutas y los controles compactos no cambian.

El inglés sigue la variante británica (`en_GB`) y el chino simplificado utiliza
el trato «您». Las capturas de aplicación se identifican como interfaz en español
con datos de demostración, también al cambiar el idioma de los textos de la web.
El HTML inicial español y los metadatos generados comparten título y descripción;
los cambios editoriales no activan la indexación ni publican una nueva URL.

`npm run test:editorial` comprueba los conjuntos de textos, metadatos y
deduplicación mediante contratos de datos y SSR. No inicia un navegador ni envía
solicitudes reales, y no certifica geometría ni interacción de los desplegables.

## Precisión comercial: cobros, comparativa y acciones

Esta revisión se realiza en tres fases, manteniendo las estructuras compactas y
los textos en español, inglés y chino:

1. Los ejemplos de pago ordinario se separan de los cobros pendientes. Facturas
   y albaranes admiten saldo pendiente; el cobro unificado también contempla
   tickets pendientes con cliente identificado y las autorizaciones necesarias.
   El saldo pendiente no representa un ingreso ni un medio de pago. No se
   publican plazos de crédito ni condiciones comerciales nuevas.
2. La comparativa distingue capacidades documentadas de aspectos por confirmar
   durante la implantación. Una capacidad técnica no implica que su licencia,
   configuración o un servicio estén incluidos en una oferta.
3. La solicitud de demo mantiene la aplicación elegida en Contacto. La acción
   de preparación de la implantación abre la guía existente en Apps, sin crear
   otra solicitud ni duplicar su contenido.

La restricción de tickets del inicio de `docs/customer-pending-sales-operations.md`
no describe todo el flujo actual. Para los textos de la web se han contrastado
`CommercialDocument.isReceivableDocument`, el cierre del cobro unificado en
`SalePaymentSessionService` y sus pruebas de tickets pendientes, además del flujo
de facturas y albaranes. Esta revisión no cambia esos procesos del negocio.

`npm run test:clarity` agrupa los contratos de estas tres fases. Se complementa
con la compilación y las pruebas de guías, renderizado, información, implantación,
accesibilidad, navegación y coherencia editorial. Las comprobaciones de código y
SSR no sustituyen una revisión visual; el acceso al navegador sigue pendiente.

## Recorridos prácticos y capturas por paso: estado de esta fase

Los tres pasos de cada aplicación conservan la composición compacta de acciones,
ejemplo y resultado en ES/EN/ZH. Los ejemplos son ilustrativos: las cantidades y
los casos descritos no representan operaciones de clientes ni necesariamente los
datos visibles en la captura de referencia. Se mantienen las condiciones de
conexión y autorización y se revisa el resultado antes de aplicar cambios.
Los ejemplos detallados viven en `tourExamples.ts`, importado desde el diálogo
de carga diferida: no se añaden a la descarga inicial de las páginas.

`TourStepPreview.tsx` muestra solo la imagen del paso activo y permite ampliar su
original en otra pestaña. `tourStepMedia.ts` separa una captura específica
(`step-capture`) de una vista general (`application-overview`). Cada paso tiene
contexto, texto alternativo y leyenda traducidos; una vista general se identifica
expresamente como referencia, no como prueba visual de la operación descrita.

Actualmente siguen disponibles únicamente las cuatro capturas generales
verificadas: ticket de Venta antes del cobro, resumen de ventas en Gestión,
consulta de precio y existencias en PDA y documentos recibidos en el portal
cliente SaaS. Se conservan sin modificación, con datos ficticios y la procedencia
ya descrita arriba. No se han publicado imágenes antiguas con datos sin validar,
pantallas de administración interna ni duplicados renombrados como nuevos pasos.

Las doce referencias específicas permanecen pendientes en
`tourStepCaptureAssets`. Para completarlas se necesitan capturas reales con datos
ficticios de los siguientes estados:

- Venta: cobro y cambio, consultas de stock/deudas y revisión operativa local.
- Gestión: existencias/movimientos, revisión e importación Excel y permisos.
- PDA: recuento y diferencias, comprobación de documento confirmado y reposición.
- SaaS cliente: consulta de documentos/existencias, supervisión de sincronización
  y licencias/facturas del servicio/soporte según los permisos correspondientes.

El acceso al navegador continúa bloqueado; en esta fase no se ejecutan las
herramientas de captura existentes ni se generan pantallas simuladas para
presentarlas como imágenes del producto. Al incorporar una captura verificada,
hay que indicar su ruta, dimensiones y procedencia y comprobar la correspondencia
con el paso. El ejemplo comercial por sí solo no acredita lo mostrado en la imagen.

`npm run test:tour` comprueba las 36 combinaciones de paso, aplicación e idioma,
la existencia y dimensiones de los archivos, la distinción de procedencia y el
enlace al original mediante contratos de datos y SSR. No ejecuta un navegador ni
certifica la geometría o la interacción real en móvil y escritorio.
