# Empaquetado Windows productivo de TPV ERP

El frontend dispone de dos aplicaciones Electron independientes. La versión de aplicación procede de `frontend/package.json`; la versión exacta de Electron procede de `frontend/package-lock.json`, compartida por ambos destinos. El staging y la comprobación del ASAR usan esas mismas identidades, sin fijar otra versión en cada configuración. Antes de preparar el staging se rechaza un `node_modules/electron` que no coincida con el lock: se requiere ejecutar `npm ci` en el entorno de construcción autorizado. Los lanzadores DEV `app:venta` y `app:gestion` conservan su comportamiento y no quedan bloqueados por este control de release; una ejecución DEV con dependencias antiguas no acredita el runtime productivo.

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

## Instaladores NSIS: generación explícita

Los comandos anteriores conservan el paquete `win-unpacked` para pruebas; no constituyen un instalador ni una aceptación productiva. Validarlos con `npm run check:desktop`. La generación NSIS es opt-in, desde `frontend` en Windows:

```powershell
# Configurar previamente la firma con la custodia externa autorizada.
# Este valor público identifica el certificado autorizado; no es la contraseña ni el certificado.
$env:TPV_DESKTOP_SIGNER_THUMBPRINT = '<huella SHA-1 de 40 caracteres del certificado de firma autorizado>'
npm run package:installer:venta
npm run package:installer:gestion
npm run check:desktop:installer
```

Cada comando compila, prepara y genera NSIS x64 mediante electron-builder con `forceCodeSigning=true` y `--publish never`. No publica releases ni descarga/instala certificados. Sin un proveedor o certificado Authenticode válido, la generación no se considera exitosa. La firma se configura externamente según [electron-builder v26 para Windows](https://www.electron.build/v26/docs/win/), sin claves ni contraseñas en el repositorio. No desactivar la firma para sortear este control.

Los artefactos esperados son `frontend/output/desktop-production/<app>/TPV-ERP-APP-<VENTA|GESTION>-<version>-setup.exe` y su `.exe.sha256`. El hook calcula SHA-256 sobre el instalador terminado. La comprobación compara el checksum, valida el ASAR/entrypoint/versiones y exige Authenticode válido, firmante esperado y sello de tiempo tanto en el instalador como en el ejecutable `win-unpacked`. También puede ejecutarse para una aplicación: `node scripts/check-desktop-packages.mjs --installer venta`.

El checksum protege la integridad, no sustituye una firma. Los tests unitarios usan artefactos ficticios sin ejecución y no acreditan una firma real. La custodia/certificado, ejecución de NSIS firmado, instalación/actualización/desinstalación en Windows limpio, comprobación de impresora/hardware y publicación controlada siguen siendo puertas externas de aceptación. El instalador no configura automáticamente secretos fiscales ni el backend remoto: el técnico debe provisionar la configuración de `ProgramData` y aplicar la ACL documentada. Una desinstalación no debe usarse para borrar datos fiscales locales.
