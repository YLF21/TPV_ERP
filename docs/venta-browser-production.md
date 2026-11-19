# Venta desde el navegador

## Qué se prepara

El acceso de clientes abre Venta, separado del SaaS interno. Cada perfil de
navegador solicita un puesto de venta desde el 002, y un administrador lo aprueba
en la gestión de puestos existente. El puesto consume el cupo de terminales de
Venta de la licencia; no constituye una licencia adicional ni una excepción a
sus límites. El 001 queda reservado al servidor.

El navegador debe llegar al backend de **su instalación de tienda**. Este flujo
no selecciona tiendas a través del SaaS central ni descubre servidores de una
red privada desde Internet. Para varias instalaciones, publica un origen dedicado
por instalación y dirige al cliente al que le corresponde.

## Compilar y publicar

En `frontend`:

```powershell
npm run build:web:venta
```

Publica únicamente `frontend/apps/app-venta/dist-web`. Es un build separado del
de Electron: no carga `.env` ni `.env.local`, fuerza `/api/v1` en el mismo origen
y excluye las variables de identidad de desarrollo. No copies al servidor la
carpeta de fuentes, archivos `.env`, credenciales ni el perfil de otro navegador.

Configura el backend con el origen HTTPS exacto de Venta, sin ruta ni barra final:

```text
TPV_WEB_TERMINAL_ENABLED=true
TPV_WEB_TERMINAL_ALLOWED_ORIGIN=https://<host-real-de-la-instalacion>
TPV_WEB_TERMINAL_ALLOW_LOCAL_HTTP=false
```

El registro web está deshabilitado por defecto. Estos ajustes no cambian la
licencia, la aprobación ni los permisos de usuario. No requieren una migración
de base de datos: reutilizan las vinculaciones de puestos actuales.

`deploy/venta-web/Caddyfile.example` sirve el build con TLS y hace proxy de
`/api/v1` al backend. Define `VENTA_HOST`, `VENTA_WEB_ROOT` (ruta absoluta a
`dist-web`) y `VENTA_BACKEND` (por ejemplo `127.0.0.1:8080`). Valida el archivo
con Caddy antes de instalarlo. El ejemplo bloquea APIs de adopción/instalación
en el host público; conserva su acceso por el canal administrativo local.
El backend debe escuchar en una interfaz privada o loopback y aceptar tráfico
público solamente a través del proxy. No reescribas la cabecera `Origin` ni
añadas CORS con comodines. Marketing y el SaaS interno deben usar otros orígenes.
El ejemplo limita a 16 KB el cuerpo de los endpoints de registro, sin reducir
el límite de las subidas de imágenes de Venta, mediante
[`request_body` de Caddy](https://caddyserver.com/docs/caddyfile/directives/request_body).

En el build de marketing, configura `VITE_CLIENT_PORTAL_URL` con ese origen de
Venta. No se publica automáticamente ningún dominio con estos cambios.

## Primer uso y recuperación

1. Abrir Venta en un navegador actualizado sobre HTTPS con soporte de Web Locks
   (`navigator.locks`), necesario para coordinar el registro entre pestañas. Si
   falta, la pantalla bloquea el registro y pide utilizar un navegador compatible.
   No utilizar modo privado para un puesto permanente.
2. Elegir un puesto disponible, escribir un nombre reconocible y solicitarlo.
3. En Gestión, un administrador con permisos de terminales revisa y aprueba
   esa solicitud. El cliente no necesita su contraseña administrativa.
4. La pantalla actualiza el estado. Una vez aprobado, el empleado inicia sesión
   con su usuario habitual y entra al TPV, sujeto a sus permisos.
5. Cerrar sesión revoca la sesión del empleado; el registro del navegador
   permanece para el siguiente inicio de sesión.

Las solicitudes pendientes caducan según la reserva configurada (15 minutos por
defecto). Cancelar una pendiente permite volver a elegir puesto. Desactivar o
liberar un puesto activo sigue siendo una acción administrativa. No basta con
borrar cookies para liberar su cupo.

La cookie de registro se renueva al consultar el estado y dura 90 días desde su
última renovación. Borrar los datos del sitio, cambiar de perfil/origen o dejar
caducar la cookie pierde la prueba del navegador: un administrador debe liberar
el puesto anterior y aprobar una nueva solicitud. Un fallo de red no borra el
registro. No hay venta sin conexión al backend en este flujo web.

## Credenciales y protección

La credencial del terminal no se devuelve a JavaScript, no se guarda en
localStorage/IndexedDB y no aparece en URLs. La cookie de producción es
`__Host-espos-terminal`, `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/`, sin
`Domain`. Solo los endpoints web la interpretan: el resto de la API conserva sus
sesiones bearer y sus comprobaciones de licencia, vinculación y permisos.

Los POST web exigen `Origin` exacto, JSON y `X-espos-web-terminal: 1`. Se rechazan
orígenes distintos, peticiones sin esa protección y HTTP remoto. Las respuestas
son `no-store`; el proxy añade CSP, prohibición de iframes y cabeceras de
seguridad. Los límites de peticiones y el [límite de 16 KB para cuerpos del registro](https://caddyserver.com/docs/caddyfile/directives/request_body)
reducen el abuso del registro público; este límite no afecta a las subidas de imágenes del TPV. No
registres cuerpos, cookies ni contraseñas en el proxy o en herramientas de
analítica. HttpOnly protege la lectura del secreto, pero no sustituye a la
prevención de XSS ni a la protección del equipo del cliente.

## Dispositivos

Este registro habilita la identidad de Venta en navegador; no instala los
puentes nativos de Electron. La impresión automática de tickets, apertura del
cajón y visor requieren la integración nativa correspondiente. Verifica los
dispositivos y la operación real del comercio antes de sustituir un puesto de
escritorio. No se añade funcionamiento offline ni homologación de datáfonos.

## Prueba local del build web

Usa un proceso backend de prueba con estos ajustes adicionales de proceso:

```text
TPV_WEB_TERMINAL_ENABLED=true
TPV_WEB_TERMINAL_ALLOWED_ORIGIN=http://127.0.0.1:5177
TPV_WEB_TERMINAL_ALLOW_LOCAL_HTTP=true
```

Después, desde `frontend`, `npm run preview:web:venta`. Abre
`http://127.0.0.1:5177/`. La excepción HTTP solo admite direcciones loopback
literales y utiliza una cookie local distinta; no es configuración productiva.
No amplíes el cupo ni reutilices un secreto DEV para forzar la prueba. Si la
licencia no tiene un puesto disponible, la pantalla debe indicarlo.

## Verificación antes de habilitar clientes

Comprueba HTTPS y cabeceras en el proxy real; registro y aprobación con un
puesto de prueba autorizado; recarga; pérdida temporal de red; caducidad;
cancelación; cuota completa; desactivación/liberación y rechazo del bearer
anterior. Prueba un POST con origen ajeno: debe ser rechazado sin crear un puesto.
Inspecciona que el secreto no aparece en la respuesta JSON ni en el almacenamiento
accesible a JavaScript. Completa también una venta de prueba autorizada y las
comprobaciones fiscales y de dispositivos de la instalación.

## Verificación local realizada · 2026-11-19

- 85 pruebas focales de backend y 19 de PostgreSQL aislado aprobadas, sin
  omisiones. El clúster desechable fue detenido; no se modificaron las bases de
  aplicación para estas pruebas ni se añadió una migración.
- 71 pruebas focales de frontend aprobadas; build web y comprobación de ausencia
  de la identidad de desarrollo y mapas de fuentes aprobados.
- Comprobación HTTP del build: estado `UNREGISTERED` con puestos disponibles,
  cookie HttpOnly, `Cache-Control: no-store`, origen ajeno rechazado con 403 y
  backend con salud `UP`. Esta consulta no reserva puestos.
- Revisión con navegador en 320 × 740, 390 × 844 y 1280 × 720, ES/EN/ZH,
  sin desbordamiento horizontal en la entrada y con controles de 44 px. Capturas
  en `output/web-terminal-20261119/registro-movil.jpg` y
  `registro-escritorio.jpg`. No se aprobó un terminal ni se efectuó una venta real.

El certificado, dominio y configuración Caddy de una instalación pública no
han sido desplegados ni validados en este entorno local. Su aceptación y las
pruebas de operación real anteriores siguen siendo necesarias al publicar.
