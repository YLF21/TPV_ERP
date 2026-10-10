# Instalación conjunta del servidor TPV ERP en Windows

Esta guía describe la instalación coordinada del backend y del gateway HTTPS
de Caddy mediante `tools/Install-TpvServerWindows.ps1`. El instalador registra
los servicios `TPVERPBackend` y `TPVERPHTTPS`, prepara la configuración de
conexión del escritorio del servidor y conserva el plan de red para los
arranques y actualizaciones posteriores. Ejecútelo en Windows desde una sesión
de PowerShell elevada.

La preparación del bundle del backend, sus requisitos legales y la verificación
de su identidad siguen el procedimiento de
[despliegue productivo del backend](tpv-backend-windows-production-deployment.md).
La instalación conjunta no sustituye esa promoción ni declara por sí misma una
release apta para producción.

## Dependencias y archivos externos

Prepare antes de la instalación:

- Java 25 instalado en el servidor.
- Un bundle backend validado y su `ExpectedReleaseId` aprobado.
- WinSW proporcionado por el operador y el SHA-256 esperado de su ejecutable.
- Caddy proporcionado por el operador y el SHA-256 esperado de su ejecutable.
- Un certificado TLS y su clave privada en formato PEM, proporcionados para el
  nombre público elegido.
- La CA que emite el certificado ya debe ser confiable para Windows en el
  servidor. El instalador no genera certificados ni instala o confía en CA.
- El archivo de configuración Spring fuera del bundle. Los directorios de
  secretos VeriFactu y exportaciones también se aprovisionan fuera del bundle
  según la [guía de despliegue del backend](tpv-backend-windows-production-deployment.md).

El instalador verifica los hashes de WinSW y Caddy. El certificado, la clave,
el JAR, la configuración Spring y los secretos no se incorporan al bundle de
instalación conjunta. No incluya contraseñas, tokens ni claves en la línea de
comandos ni en esta tabla de parámetros.

## Orígenes y puertos

El backend escucha por HTTP en `127.0.0.1` por defecto, o en `::1` si se
configura expresamente. Caddy termina TLS en el origen público y reenvía al origen HTTP
loopback del backend. El instalador deriva ambos orígenes del plan guardado:

- Origen interno del backend: `http://127.0.0.1:<puerto-backend>`.
- Origen público HTTPS: `https://<PublicHost>:<puerto-HTTPS>`.
- La configuración `TPV_BACKEND_PUBLIC_URL` del backend y el anuncio DNS-SD
  / mDNS usan el origen HTTPS público.
- El upstream de Caddy usa el origen HTTP loopback del backend y su sitio usa
  el origen HTTPS público.
- `backend-config.json` del escritorio local del servidor usa el origen HTTP
  loopback. Si ya existe y apunta a otro origen, el instalador aborta en vez de
  sobrescribir la conexión.

El nombre `PublicHost` debe corresponder al certificado y ser alcanzable desde
los terminales. El anuncio mDNS permite descubrir candidatos; los terminales
siguen conectándose al origen HTTPS publicado.

Cuando la autoridad es una IP, Caddy selecciona el certificado de esa autoridad
también para clientes que no envían SNI. Esto permite la recuperación de conexión
existente: conectar a la nueva IP física conservando el Host, la autoridad y la
validación TLS originales. No se cambia el puerto durante esa recuperación.

En la primera fase `Register`, el instalador intenta primero los puertos
preferidos `8080` para backend y `8443` para HTTPS. Si están ocupados, prueba
estas listas, en este orden:

| Servicio | Orden de candidatos de reserva |
| --- | --- |
| Backend, ligado a loopback | `18080`, `28080`, `38080`, y después `18081`–`18180` |
| HTTPS, escucha pública | `18443`, `28443`, `38443`, y después `18444`–`18543` |

El backend y HTTPS no pueden compartir puerto. El primer `Register` persiste
las direcciones, puertos y URL calculados en
`<ServerRoot>\server-network.json`. Por defecto, `ServerRoot` es
`C:\ProgramData\TPV ERP\Server`. El archivo y su directorio quedan protegidos
por ACL explícitas con control total únicamente para Administrators y SYSTEM,
sin herencia. El archivo se guarda fuera del bundle.

Una vez guardado, el plan es la autoridad para `Register` de actualización y
`Start`: el instalador reutiliza los mismos puertos y orígenes. Si un puerto
guardado está ocupado, o si `PublicHost` / `BackendAddress` no coincide con el
plan, la operación falla; no busca ni asigna otro puerto automáticamente en
arranques o actualizaciones. Para cambiar la red, gestione el cambio del plan
de forma explícita antes de continuar.

## Consulta de la conexión desde GESTIÓN

En **Seguridad > Terminales y PDA**, el bloque **Conexión del servidor** muestra
las IP privadas actuales del servidor, el puerto HTTPS configurado para los
terminales, el puerto interno donde está atendiendo el backend y el origen
HTTPS configurado. Incluye los puertos alternativos elegidos por el instalador;
no presupone que sean `8080` o `8443`.

Use el puerto **Terminales (HTTPS)** para configurar otros equipos de la tienda.
El puerto interno del backend corresponde al enlace local entre Caddy y Java.
**Actualizar** vuelve a consultar las interfaces de red y la información del
servidor. Si hay varios adaptadores privados, aparecen todas sus IP; elija la
red compartida con el terminal. La dirección configurada conserva la autoridad
TLS original, incluso cuando la recuperación conecta a una IP física nueva.

Si HTTPS no está configurado, el panel indica **Sin configurar**. Un fallo al
consultar esta información no bloquea las acciones de gestión de terminales.
La consulta requiere los mismos permisos y desbloqueo de Seguridad que la
gestión de terminales. El backend y GESTIÓN deben incluir esta versión para
mostrar el nuevo bloque.

## Preflight, registro y arranque

Use las mismas rutas y valores en las cuatro llamadas. El ejemplo emplea
marcadores para el identificador y los hashes; sustitúyalos por los valores
verificados. No añada secretos a la tabla:

```powershell
$serverArgs = @{
  BundleDirectory = 'D:\releases\backend-<release-id>'
  ExpectedReleaseId = '<release-id-aprobado>'
  WinSwExecutable = 'D:\provision\WinSW-x64.exe'
  WinSwSha256 = '<64-CARACTERES-HEX-SHA256-WINSW>'
  JavaExecutable = 'C:\Program Files\Java\jdk-25\bin\java.exe'
  ConfigurationFile = 'C:\ProgramData\TPV ERP\config\application-prod.yml'
  CaddyExecutable = 'D:\provision\caddy.exe'
  CaddySha256 = '<64-CARACTERES-HEX-SHA256-CADDY>'
  CertificateFile = 'D:\provision\tls\certificate.pem'
  PrivateKeyFile = 'D:\provision\tls\private-key.pem'
  PublicHost = 'tpv-servidor.example.local'
  ServerRoot = 'C:\ProgramData\TPV ERP\Server'
  BackendInstallRoot = 'C:\ProgramData\TPV ERP\Backend'
  HttpsInstallRoot = 'C:\ProgramData\TPV ERP\HTTPS'
  BackendAddress = '127.0.0.1'
  PreferredBackendPort = 8080
  PreferredHttpsPort = 8443
  SecretRoot = 'C:\ProgramData\TPV ERP\secrets'
  ExportRoot = 'C:\ProgramData\TPV ERP\exports'
}

# 1. Comprueba el registro sin guardar el plan ni modificar servicios.
.\tools\Install-TpvServerWindows.ps1 @serverArgs -Phase Register -Preflight

# 2. Guarda el plan de red, registra ambos servicios detenidos y aplica ACL.
.\tools\Install-TpvServerWindows.ps1 @serverArgs -Phase Register

# 3. Comprueba configuración, servicios, ACL y disponibilidad para arrancar.
.\tools\Install-TpvServerWindows.ps1 @serverArgs -Phase Start -Preflight

# 4. Arranca explícitamente backend y HTTPS.
.\tools\Install-TpvServerWindows.ps1 @serverArgs -Phase Start
```

`Register -Preflight` valida los dos instaladores y las reglas previstas sin
guardar `server-network.json`, crear servicios ni abrir el firewall. El primer
`Register` persiste el plan, registra o actualiza ambos servicios y los deja
detenidos con inicio `Manual`. También aplica las ACL necesarias y guarda el
origen loopback en la configuración del escritorio del servidor.

`Start -Preflight` comprueba los servicios instalados, el plan guardado, los
archivos y la configuración requeridos para el arranque. No inicia los
servicios. `Start` configura ambos servicios con inicio `Automatic`, inicia el
backend y espera su estado de salud `UP`; después inicia Caddy y verifica HTTPS
con un certificado confiable para el origen configurado. Cuando ambas
verificaciones pasan, habilita las reglas de entrada:

- TCP en el puerto HTTPS elegido, para `caddy.exe`, perfil `Domain,Private` y
  origen remoto `LocalSubnet`.
- UDP `5353` para Java/mDNS, perfil `Domain,Private` y origen remoto
  `LocalSubnet`.

Las reglas se registran deshabilitadas. Sólo se habilitan después de que el
backend esté sano y el gateway responda por TLS. Si `Start` falla, los servicios
iniciados durante ese intento se detienen; se restauran los tipos de inicio y
el estado previo de las reglas del firewall. Los servicios que ya estaban
funcionando se mantienen activos.

El instalador conjunto y las dos entradas de servicio comparten un bloqueo de
instalación. Una ejecución simultánea se rechaza antes de modificar servicios o
configuración. Las llamadas internas del mismo instalador pueden adquirirlo de
forma anidada.

El instalador escribe `deployment-state.json` en `ServerRoot` para registrar la
fase y el estado del proceso. Ante un error de instalación guarda el estado
`Failed` cuando puede hacerlo y conserva el plan de puertos. Corrija la causa y
repita la fase indicada con la misma configuración. En particular, reintente
`Register` usando los mismos argumentos y el plan ya guardado; no cambie los
puertos para intentar resolver un fallo. El proceso coordina dos instaladores
con operaciones de recuperación propias, pero no constituye una transacción
atómica conjunta ni garantiza revertir todos los efectos externos.

Esta guía documenta el flujo y sus requisitos; no acredita que se haya
ejecutado una instalación en el equipo objetivo ni que el servidor esté listo
para producción. Complete las comprobaciones de promoción, configuración fiscal
y aceptación descritas en la guía de despliegue productivo antes de aceptar
tráfico real.

## Validación del 10 de octubre de 2026

Windows PowerShell 5.1: **82 pruebas correctas, cero fallos u omisiones** en las
seis suites de puertos, HTTPS, coordinación del servidor, despliegue productivo,
ACL del backend y configuración del escritorio. Cubren puertos ocupados,
persistencia y corrupción del plan, IPv6, contención entre procesos, fases sin
mutaciones, adopción de servicios anteriores, reglas de firewall, reutilización
de puertos y reversión del arranque ante fallos de TLS o de escritura del estado.

Desde la raíz del repositorio:

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -Command "Invoke-Pester -Script 'tools/TpvServerPorts.Tests.ps1','tools/TpvHttpsWindowsService.Tests.ps1','tools/TpvServerWindowsInstaller.Tests.ps1','tools/TpvBackendProductionDeployment.Tests.ps1','tools/TpvBackendWindowsAcl.Tests.ps1','tools/TpvDesktopBackendConfigAcl.Tests.ps1' -EnableExit"
```

Además, una fixture aislada con Caddy 2.11.7, cuyo ZIP se verificó contra el
SHA-512 oficial antes de ejecutarlo, ocupó ambos puertos preferidos y comprobó
la elección de `18080` y `18443`. Se validó el Caddyfile productivo generado y
se realizó una petición HTTPS real hasta un backend de prueba con respuesta
`UP`, CA explícita y comprobación del nombre del certificado.

La variante con autoridad `127.0.0.1`, conexión física a `127.0.0.2` y SNI vacío
falló al omitir `default_sni` y pasó con el ajuste actual. Conserva la autoridad
original y la validación TLS; no modifica adaptadores ni el router. Las
evidencias locales están en
`C:\Users\YLF\.codex\tmp\server-installer-validation-20261010`.

Estas pruebas no registraron servicios reales de Windows, no aplicaron reglas
al firewall y no cambiaron la conexión de la tienda demo. Queda pendiente la
aceptación del flujo completo con WinSW y un bundle productivo aprobado en un
equipo de prueba elevado.

Referencias técnicas: [opciones globales de Caddy](https://caddyserver.com/docs/caddyfile/options),
[TLS manual](https://caddyserver.com/docs/caddyfile/directives/tls) y
[configuración XML de WinSW](https://github.com/winsw/winsw/blob/v3/docs/xml-config-file.md).
