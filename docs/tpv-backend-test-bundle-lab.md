# Backend 0.1 TEST_ONLY en VirtualBox

Este canal sirve para ensayar el flujo completo en máquinas VirtualBox aisladas. La identidad del paquete es `tpv-erp-test-only-0.1.0-v279-<12 caracteres del commit>`, `capability=DUAL`, esquema V279, secuencia de release 36 y build 1. Su marcador embebido dice `purpose=TEST_ONLY`, `runtime=SANDBOX`, `version=0.1.0` y `production.allowed=false`. El verificador imprime `TEST_ONLY_VERIFIED / NO PROMOTABLE`. El canal productivo 1.0 y su verificador rechazan cualquier JAR con marcador TEST_ONLY, incluso si tuviera un PDF.

V279 corrige el alta de instalaciones cuando los 30 dias atraviesan un cambio
horario: la duracion sigue siendo las 720 horas transcurridas que calcula Java.
La migracion no modifica fechas existentes; valida nuevas instalaciones y cambios
efectivos de sus fechas. Conserva actualizaciones ajenas a esas fechas en filas
historicas. No cambia el orden de arranque ni permite adoptar una base REAL
desde el control temprano TEST_ONLY. Si un primer arranque anterior fallo tras
Flyway, conservar esa base para diagnostico y usar una nueva base vacia del
laboratorio; nunca cambiar manualmente su marcador a SANDBOX.

La regresion PostgreSQL `InstallationDemoDurationPostgreSqlTest` requiere
`TPV_DEMO_TEST_DB_URL`, `TPV_DEMO_TEST_DB_USER` y `TPV_DEMO_TEST_DB_PASSWORD`
de una base desechable explicita. Crea un esquema aislado en una transaccion y
revierte todas sus escrituras. Cubre el fallo original, zonas con DST y medias
horas, conservacion de fechas historicas y rechazo de duraciones invalidas.

## Preparación del paquete

En un checkout limpio y confirmado en Git, ejecutar desde la raíz:

```powershell
.\tools\Prepare-TpvBackendTestBundle.ps1
.\tools\Test-TpvBackendTestBundle.ps1 -BundleDirectory '.\artifacts\backend-<release-id>'
```

El preparador comprueba que el perfil Maven dirige `clean` exclusivamente a `backend\target\test-bundle-build`, y después usa `-Ptest-bundle clean package -DskipTests`. No limpia `backend\target` general. Los tests focales se ejecutan antes de crear el paquete. El directorio publicado incluye JAR, sidecar SHA-256, manifiesto de hashes y scripts de mantenimiento/instalación. No incluye una declaración responsable de producto. Si ya existe un artefacto con el mismo nombre y otro hash, el preparador se detiene.

## Red y configuración de laboratorio

Ejemplo: VM A (Windows, backend origen) `10.77.23.10`, VM B (SaaS HTTPS de laboratorio) `10.77.23.20:58443`, ambas en la red interna VirtualBox sin salida a Internet, gateway ni DNS. PostgreSQL del backend usa loopback en A y una base nueva `lab_*`. No conectar ninguna de estas VM a datos o servicios del ERP físico.

Crear en A un fichero externo `C:\ProgramData\TPV ERP\config\application-lab.properties`. El instalador exige valores explícitos como los siguientes; completar las credenciales locales en ese fichero con ACL restringida:

```properties
tpv.test-bundle.lab-context=VIRTUALBOX_ONLY
spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/lab_tpv_a
spring.datasource.username=lab_user
spring.datasource.password=<secreto-local>
spring.sql.init.mode=never
tpv.license.saas-url=https://10.77.23.20:58443
tpv.sync.central-url=https://10.77.23.20:58443
tpv.sync.worker-enabled=false
tpv.installation.key-directory=C:/ProgramData/TPV ERP/keys
tpv.backup.default-directory=C:/ProgramData/TPV ERP/Backups
tpv.product-images.directory=C:/ProgramData/TPV ERP/product-images
tpv.document-templates.directory=C:/ProgramData/TPV ERP/document-templates
tpv.verifactu.secret-directory=C:/ProgramData/TPV ERP/secrets/verifactu
tpv.verifactu.export-job-directory=C:/ProgramData/TPV ERP/exports/fiscal
tpv.backup.restore-journal-path=C:/ProgramData/TPV ERP/restore/tpv-restore-journal.properties
tpv.test-bundle.tls-reprovisioned=false
tpv.verifactu.runtime-class=SANDBOX
tpv.verifactu.endpoint-environment=TEST
tpv.verifactu.endpoint-mode=TEST
tpv.verifactu.transport-mode=SIMULATED
tpv.verifactu.dev-sandbox.enabled=true
tpv.verifactu.production-enabled=false
tpv.verifactu.worker-enabled=false
tpv.verifactu.aeat-test-network-enabled=false
```

El instalador comprueba el hardware VirtualBox, el fichero `.properties`, el JDBC local `lab_*`, las banderas fiscales y el SaaS HTTPS en IP privada o loopback. Java vuelve a comprobar el runtime efectivo y la base vacía o marcada SANDBOX antes de escribir. Una URL pública o un runtime REAL bloquean el canal TEST_ONLY.

## Registro de servicios dentro de la VM

En PowerShell elevado de A, con WinSW 3.x y Java 25 ya proporcionados y sus hashes comprobados, efectuar primero un preflight; después registrar el backend **detenido**. El instalador TEST exige que `TPVERPBackend` no exista previamente y utiliza `C:\ProgramData\TPV ERP\Backend`:

```powershell
$args = @{
  BundleDirectory = 'D:\lab\backend-<release-id>'
  ExpectedReleaseId = '<release-id-verificado>'
  WinSwExecutable = 'D:\lab\WinSW-x64.exe'
  WinSwSha256 = '<SHA256_DE_WINSW>'
  JavaExecutable = 'C:\Program Files\Java\jdk-25\bin\java.exe'
  ConfigurationFile = 'C:\ProgramData\TPV ERP\config\application-lab.properties'
}
.\tools\Install-TpvBackendTestWindowsService.ps1 @args -Phase Register -Preflight
.\tools\Install-TpvBackendTestWindowsService.ps1 @args -Phase Register
$installedRelease = "C:\ProgramData\TPV ERP\Backend\releases\$($args.ExpectedReleaseId)-r36-b1"
.\tools\Set-TpvBackendTestWindowsAcl.ps1 `
  -BundleDirectory $installedRelease -ConfigurationFile $args.ConfigurationFile
.\tools\Install-TpvBackendTestWindowsService.ps1 @args -Phase Start -Preflight
```

El helper aplica primero la ACL privada del servicio. Después concede a `Users` lectura y ejecución explícita, sin herencia, únicamente sobre los contenedores de acceso, WinSW/XML y la release inmutable con sus herramientas. No da acceso público a `config`, `logs`, secretos ni datos operativos. Esta lectura permite que la ventana de escritorio abra el asistente antes de UAC; las operaciones siguen ejecutándose elevadas.

La ACL privada usa las rutas efectivas del fichero instalado: claves de instalación, copias, imágenes, plantillas, secretos fiscales, exportaciones fiscales y directorio del diario de restauración. Protege `keys` aunque esté fuera del directorio de secretos. Si se eligen rutas fiscales distintas a las mostradas, páselas al instalador con `-SecretDirectory` y `-ExportDirectory`; deben coincidir exactamente con el fichero de configuración.

El XML de WinSW debe declarar `--spring.profiles.active=lab-bundle` y el JAR verificado. Para HTTPS, registrar el servicio real `TPVERPHTTPS` con `Install-TpvHttpsWindowsService.ps1` y un certificado de laboratorio, siguiendo sus dos fases de registro/inicio y sus verificaciones de hash/ACL. El asistente de recuperación requiere ese servicio HTTPS instalado y verifica su identidad. No crear XML de servicio a mano como sustituto de un servicio SCM.

Los accesos de APP VENTA y APP GESTIÓN en la VM apuntan al HTTPS de laboratorio. Ejecutar allí la copia, subida SaaS, solicitud, recepción y activación mediante las ventanas de mantenimiento. Las ventanas invocan los scripts incluidos en la release activa, elevan UAC, aplican las ACL y detienen/arrancan el servicio de verdad. Los scripts descubren el canal desde el JAR instalado; no aceptan un modo TEST indicado por la interfaz. La activación comprueba `/actuator/health/readiness` antes de informar éxito.

## Actualización de una recuperación ya completada

`Install-TpvBackendTestWindowsService.ps1 -Phase Update` admite una nueva release
TEST_ONLY verificada sobre un servicio TEST_ONLY existente y detenido. Backend y
HTTPS deben estar detenidos; se conservan la cuenta virtual, configuración,
base de datos, identidad y perfil de escritorio. El instalador comprueba ambas
releases, rechaza retrocesos y deja el servicio detenido en Manual con el XML
anterior en `rollback`. `Register` sigue reservado a instalaciones nuevas.

Si una recuperación anterior terminó en `COMPLETE` pero el arranque rechazó la
herencia de permisos de sus claves, ejecutar desde la nueva release verificada
`tools/Invoke-TpvDisasterRecovery.ps1 -Action protect-secrets -Execute`, con los
argumentos habituales de bundle, release, Java y directorio de mantenimiento.
El entorno debe identificar la base recuperada y la cuenta real del backend.
La operación exige que el diario y la base coincidan en recuperación,
instalación, copia y huella, y que ambos estén en `COMPLETE`.

Esta operación valida primero las seis claves esperadas y su directorio:
únicamente admite los permisos canónicos existentes del servicio, SYSTEM y
Administradores. Protege la herencia sin reescribir el contenido ni cambiar su
propietario. Rechaza archivos ausentes, enlaces y permisos distintos; no vuelve
a autorizar ni activar la recuperación. Después se aplican las ACL oficiales
de la nueva release y se ejecutan `-Phase Start -Preflight` y `-Phase Start`.
Ante cualquier rechazo se conserva el estado detenido y la evidencia; no se
debe sustituir esta comprobación por un arranque directo del servicio.

## Ensayo completado el 24 de octubre de 2026

Se probó el flujo con datos sintéticos entre dos Windows independientes en
VirtualBox y un SaaS aislado de laboratorio, sin acceder a la base de datos ni
cambiar servicios, configuración o bloqueos del servidor físico.

- Cuatro copias creadas mediante la API del origen y subidas automáticamente.
  El catálogo de SaaS y sus archivos físicos conservaban exactamente las tres
  más recientes; la primera estaba retirada.
- Solicitud generada desde Gestión y autorización real con rol `SUPPORT`.
  Los accesos anónimos y de un usuario sin permisos devolvieron 401/403;
  aprobar antes de recibir la copia devolvió 409 y no cambió su estado.
- Recepción desde el asistente visual, revisión por soporte, aprobación,
  activación central y estado local final `COMPLETE`. HTTP y HTTPS confirmaron
  `UP`. La consola se abrió desde Electron y la consulta final interpretó
  correctamente el JSON de Actuator recibido como bytes en PowerShell 5.
- Comprobación de identidad de instalación, empresa, tienda y servidor `001`,
  archivos, certificado fiscal y contador de copias. Quedaron cero sesiones
  activas: tres fueron revocadas por la recuperación y una conservó su motivo
  histórico de revocación.
- El verificador TEST_ONLY aceptó el paquete y el productivo lo rechazó.
  Las VM se apagaron con PostgreSQL detenido limpiamente y se conservaron
  instantáneas del origen y del destino recuperado.

El ensayo usó modo fiscal `SANDBOX`; no acredita un despliegue productivo ni
reconstruye documentos posteriores a la copia desde SaaS. El origen sintético
no tenía una vinculación física previa de `001`, por lo que no se ensayó su
revinculación. Los paquetes generados, las credenciales y los archivos privados
del laboratorio se conservan fuera de Git.
