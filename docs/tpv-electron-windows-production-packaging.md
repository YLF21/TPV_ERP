# Empaquetado Windows productivo de TPV ERP

El frontend dispone de dos aplicaciones Electron independientes, ambas en versión `4.2.0`:

- `APP VENTA`: `npm run package:desktop:venta`
- `APP GESTION`: `npm run package:desktop:gestion`

Cada comando compila su workspace, crea un staging mínimo y ejecuta `electron-builder --dir`. Los artefactos quedan separados en `frontend/output/desktop-production/venta/win-unpacked` y `frontend/output/desktop-production/gestion/win-unpacked`. El staging contiene únicamente el `dist` de la aplicación, el código Electron necesario y un `package.json` mínimo; el código fuente, pruebas, Vite, mapas y secretos no se distribuyen. `asar` está habilitado.

En producción Electron arranca un servidor HTTP efímero ligado exclusivamente a `127.0.0.1` y sirve la UI compilada. `TPV_DESKTOP_APP_URL` no puede sustituir ese renderer local en un paquete. Las solicitudes `/api/v1` se retransmiten en streaming al backend.

El backend productivo por defecto es `http://127.0.0.1:8080`. La configuración administrada por el instalador debe estar en:

```text
C:\ProgramData\TPV ERP\desktop\backend-config.json
```

El formato remoto es, por ejemplo, `{ "backendUrl": "https://servidor-interno:8443", "allowedHosts": ["servidor-interno"] }`. HTTP solo se admite para loopback; un backend remoto debe usar HTTPS y su hostname debe coincidir exactamente con `allowedHosts`. Nunca se aceptan credenciales, consulta, fragmento ni ruta en la URL. El lector Node rechaza el fichero y sus ancestros si no son regulares o si se exponen como enlaces simbólicos; el script de provisión comprueba además `FileAttributes.ReparsePoint` en toda la cadena controlada. El instalador debe aplicar una ACL que permita modificarlo únicamente al administrador o servicio de provisión y lectura al proceso Electron; no debe quedar modificable por usuarios operativos.

Después de copiar/provisionar el fichero, un administrador puede aplicar la ACL incluida en el repositorio:

```powershell
.\tools\Set-TpvDesktopBackendConfigAcl.ps1
```

El script usa la ruta fija y rechaza ficheros/directorios con `FileAttributes.ReparsePoint`. En el padre `C:\ProgramData\TPV ERP` aplica `DirectorySecurity` sin herencia (Administrators/SYSTEM con control total y `Authenticated Users` con lectura/ejecución para atravesar la carpeta); no modifica `C:\ProgramData` ni propaga ACE a sus hijos. En `desktop` aplica la ACL heredable para `Users` solo con lectura/ejecución. Si ya está instalado el backend, conserva además una ACE explícita de lectura/ejecución para `NT SERVICE\TPVERPBackend`; ese SID no se presupone miembro de `BUILTIN\Users`. El fichero mantiene una `FileSecurity` independiente, sin escritura para usuarios operativos.

Durante desarrollo explícito se pueden usar `TPV_DESKTOP_BACKEND_URL`, `TPV_DESKTOP_BACKEND_ALLOWED_HOSTS` y `%APPDATA%/<app>/backend-config.json`. Esas fuentes se ignoran en un paquete productivo. Si existe una configuración legacy en `%APPDATA%/<app>/backend-config.json`, copia manualmente sus valores válidos al fichero de `ProgramData`, añade la allowlist para cualquier host remoto, aplica la ACL y elimina o deja inutilizada la copia legacy antes de distribuir el paquete. No se realiza migración automática desde una ubicación modificable por el usuario.

La navegación está limitada al origen loopback de la sesión y las ventanas auxiliares conservan el aislamiento (`contextIsolation`, `sandbox`, sin `nodeIntegration`). El proxy aplica límites de solicitud de 50 MiB, timeouts, cabeceras CSP, `nosniff`, `no-store` y protección contra traversal.

Validar los directorios generados con `npm run check:desktop`. La firma Authenticode no se inventa ni se realiza en este entorno: requiere un certificado y una custodia/proceso de firma externo. Antes de distribuir instaladores NSIS debe añadirse ese paso, además de la verificación de firma y publicación controlada.
