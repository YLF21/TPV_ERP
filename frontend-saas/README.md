# Panel interno SaaS de TPV ERP

Aplicación de administración de empresas, tiendas, licencias e incidencias.

La versión registrada en `package.json` se muestra en los portales interno y de
tiendas. Durante las pruebas se utiliza `0.x.x`, actualmente `0.1.0`; el primer
despliegue iniciará la serie `1.x.x` con `1.0.0`. El registro debe coincidir con
la versión raíz de `package-lock.json`.

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

## Bandeja comercial de solicitudes de demo

Tras iniciar sesión con una cuenta interna, abre **Clientes → Solicitudes de
demo** (`#/demo-requests`). Requiere `VIEW_ADMIN_DATA` para consultar y
`MANAGE_OPERATIONS` para cambiar el estado. No está disponible para cuentas
de tienda y no usa la API pública para consultar datos personales.

La bandeja muestra páginas de 20 solicitudes, ordenadas por recepción más reciente,
con filtros Nueva / Contactada / Cerrada y búsqueda por referencia UUID, nombre,
empresa o email. La validación de sesión y acceso a la bandeja son independientes
de la disponibilidad del resumen general.
El detalle conserva todas las aplicaciones solicitadas, mensaje, contacto, fecha,
idioma y consentimiento. Se puede marcar una solicitud como contactada, cerrarla
o reabrirla; la interfaz solo confirma el cambio después de la respuesta del servidor.
Cada cambio envía el estado leído como `expectedStatus`: si otro operador lo ha
modificado, se bloquean los botones hasta actualizar y revisar el detalle. No se
reintenta una escritura en conflicto automáticamente. GET y PUT tienen un plazo
de 20 segundos que incluye la lectura completa del cuerpo de la respuesta.

El seguimiento es manual: estos cambios de estado **no envían emails ni mensajes**.
El equipo debe revisar la bandeja y contactar fuera de ella. No se ha configurado
un proveedor de notificaciones ni se han añadido destinatarios comerciales.

El backend debe exponer `GET /api/v1/admin/demo-requests/page` y
`PUT /api/v1/admin/demo-requests/{id}/status`. La API de consulta admite `page`
(base cero), `size`, `status` opcional y `query` opcional (máximo 160 caracteres).
Las credenciales siguen el transporte Bearer y el vencimiento de sesión existentes.
