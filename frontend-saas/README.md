# Panel interno SaaS de TPV ERP

Aplicación de administración de empresas, tiendas, licencias e incidencias.

```sh
npm ci
npm run dev
```

Abre http://127.0.0.1:5175/: muestra directamente el acceso de administración.
Las llamadas `/api` usan el backend SaaS del puerto 8090.
Configuración del backend y despliegue: [backend-saas/README.md](../backend-saas/README.md).

La web comercial está separada en [frontend-product](../frontend-product/README.md)
y usa el puerto 5176. No se incluye en el build del panel.

Los antiguos enlaces `#/producto` en el puerto del panel pasan al acceso
de administración. Para consultar el producto, utiliza la dirección de la web pública.

Verificación: `npm test`, `npm run build` y `npm run test:e2e`.
