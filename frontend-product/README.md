# Web pública de TPV ERP

Aplicación comercial independiente del panel interno `frontend-saas`.
Conserva las seis secciones, las imágenes y los idiomas español, inglés y chino.

## Desarrollo

Desde esta carpeta:

```sh
npm ci
npm run dev
```

Web pública: http://127.0.0.1:5176/. La navegación funciona de forma independiente;
el formulario de demostración envía las solicitudes al backend SaaS.
El panel de administración sigue en http://127.0.0.1:5175/.

Para probar el envío local, copia `.env.example` a `.env.local`, inicia
`backend-saas` en el puerto 8090 y añade `http://127.0.0.1:5176` a
`TPV_SAAS_CORS_ALLOWED_ORIGINS`.

## Compilación y publicación

```sh
npm run build
npm run preview
```

La carpeta `dist/` se puede publicar en un alojamiento estático independiente.
Define `VITE_SAAS_API_BASE_URL` durante el build si la API vive en otro dominio;
si se omite, las peticiones se envían al mismo origen que la web.
Define `VITE_PUBLIC_SITE_URL` únicamente con el dominio público definitivo para
activar las URLs canonical y Open Graph sin inventar una dirección en local.

El centro de descargas usa las variables `VITE_DOWNLOAD_VENTA_URL` y
`VITE_DOWNLOAD_GESTION_URL`. Mientras estén vacías, muestra entrega asistida y
dirige al formulario comercial. Cuando se publiquen instaladores firmados,
pueden añadirse también sus enlaces `.sha256` y `VITE_DOWNLOAD_RELEASE_VERSION`.
No se muestran enlaces de descarga incompletos o de ejemplo.
La vista previa usa http://127.0.0.1:4176/.
Las rutas actuales `#/producto/inicio`, `#/producto/apps`, etc. se conservan
dentro de este sitio; no se requiere reescritura de rutas en el servidor.

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
navegación sin scroll en escritorio, imágenes, idiomas, menú móvil, estados
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
y un beneficio cualitativo, definidos en `tour.stepDetails` para español,
inglés y chino. El panel de contexto aclara la plataforma y modalidad de uso
de cada aplicación. Los textos no prometen sincronización instantánea,
integraciones de pago no configuradas ni acceso público al portal cliente.
La matriz E2E comprueba las 36 combinaciones de aplicación, paso e idioma,
su contenido y geometría en escritorio y móvil: en escritorio no debe haber
recortes; en móvil se permite desplazamiento vertical para mantener la lectura.

La web incluye además contenido comercial específico para comercio minorista,
alimentación, moda y franquicias. En pantallas altas estos casos se integran en
la portada para aprovechar el viewport sin dejar franjas vacías, mientras que
la composición compacta se conserva en portátiles. Las solicitudes conservan de forma limitada la ruta de
entrada, el referente y `utm_source`, `utm_medium` y `utm_campaign` para medir
campañas sin depender de campos ocultos editables.

Los metadatos, Open Graph y JSON-LD se actualizan según la pantalla y el idioma.
La prueba E2E comprueba también estas señales, el contenido sectorial, la
atribución, las descargas y que los diálogos caben en escritorio.

## Alcance

Tiene su propio package.json, lockfile, dependencias, estilos, recursos y build.
No importa código del panel SaaS.
El idioma usa la preferencia `tpv-product-language`.
El formulario de contacto permite elegir varias aplicaciones mediante casillas
o seleccionar las cuatro con «Paquete completo». Las tarjetas y las casillas
comparten la selección; los accesos desde recorridos y descargas siguen
preseleccionando su aplicación. La API recibe `products` con la lista completa
y mantiene `product` como primer producto para compatibilidad. La migración
SaaS V77 conserva también la aplicación de las solicitudes anteriores.
Las solicitudes comerciales se validan y guardan en el backend SaaS, y pueden
consultarse desde su endpoint administrativo protegido.
