# Web pública de TPV ERP

Aplicación comercial independiente del panel interno `frontend-saas`.
Conserva las seis secciones, las imágenes y los idiomas español, inglés y chino.

## Desarrollo

Desde esta carpeta:

```sh
npm ci
npm run dev
```

Web pública: http://127.0.0.1:5176/. No necesita el backend ni una base de datos.
El panel de administración sigue en http://127.0.0.1:5175/.

## Compilación y publicación

```sh
npm run build
npm run preview
```

La carpeta `dist/` se puede publicar en un alojamiento estático independiente.
La vista previa usa http://127.0.0.1:4176/.
Las rutas actuales `#/producto/inicio`, `#/producto/apps`, etc. se conservan
dentro de este sitio; no se requiere reescritura de rutas en el servidor.

## Verificación

```sh
npx playwright install chromium
npm run test:e2e
```

La prueba arranca una vista previa temporal en el puerto 5186 y comprueba
navegación, imágenes, idiomas y menú móvil con las peticiones API bloqueadas.

## Alcance

Tiene su propio package.json, lockfile, dependencias, estilos, recursos y build.
No importa código del panel SaaS ni configura un proxy a sus APIs.
El idioma usa la preferencia `tpv-product-language`.
Se conserva el formulario comercial existente: prepara una solicitud local;
el envío real y el destinatario comercial siguen pendientes de configuración.
