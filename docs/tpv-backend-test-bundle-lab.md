# Backend 0.1 TEST_ONLY en VirtualBox

Este canal sirve para ensayar el flujo completo en máquinas VirtualBox aisladas. La identidad del paquete es `tpv-erp-test-only-0.1.0-v278-<12 caracteres del commit>`, `capability=DUAL`, esquema V278, secuencia de release 35 y build 1. Su marcador embebido dice `purpose=TEST_ONLY`, `runtime=SANDBOX`, `version=0.1.0` y `production.allowed=false`. El verificador imprime `TEST_ONLY_VERIFIED / NO PROMOTABLE`. El canal productivo 1.0 y su verificador rechazan cualquier JAR con marcador TEST_ONLY, incluso si tuviera un PDF.

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

En PowerShell elevado de A, con WinSW y Java 25 ya proporcionados y sus hashes comprobados, efectuar primero un preflight; después registrar el backend **detenido**. El instalador TEST exige que `TPVERPBackend` no exista previamente y utiliza `C:\ProgramData\TPV ERP\Backend`:

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
$installedRelease = "C:\ProgramData\TPV ERP\Backend\releases\$($args.ExpectedReleaseId)-r35-b1"
.\tools\Set-TpvBackendTestWindowsAcl.ps1 `
  -BundleDirectory $installedRelease -ConfigurationFile $args.ConfigurationFile
.\tools\Install-TpvBackendTestWindowsService.ps1 @args -Phase Start -Preflight
```

El helper aplica primero la ACL privada del servicio. Después concede a `Users` lectura y ejecución explícita, sin herencia, únicamente sobre los contenedores de acceso, WinSW/XML y la release inmutable con sus herramientas. No da acceso público a `config`, `logs`, secretos ni datos operativos. Esta lectura permite que la ventana de escritorio abra el asistente antes de UAC; las operaciones siguen ejecutándose elevadas.

La ACL privada usa las rutas efectivas del fichero instalado: claves de instalación, copias, imágenes, plantillas, secretos fiscales, exportaciones fiscales y directorio del diario de restauración. Protege `keys` aunque esté fuera del directorio de secretos. Si se eligen rutas fiscales distintas a las mostradas, páselas al instalador con `-SecretDirectory` y `-ExportDirectory`; deben coincidir exactamente con el fichero de configuración.

El XML de WinSW debe declarar `--spring.profiles.active=lab-bundle` y el JAR verificado. Para HTTPS, registrar el servicio real `TPVERPHTTPS` con `Install-TpvHttpsWindowsService.ps1` y un certificado de laboratorio, siguiendo sus dos fases de registro/inicio y sus verificaciones de hash/ACL. El asistente de recuperación requiere ese servicio HTTPS instalado y verifica su identidad. No crear XML de servicio a mano como sustituto de un servicio SCM.

Los accesos de APP VENTA y APP GESTIÓN en la VM apuntan al HTTPS de laboratorio. Ejecutar allí la copia, subida SaaS, solicitud, recepción y activación mediante las ventanas de mantenimiento. Las ventanas invocan los scripts incluidos en la release activa, elevan UAC, aplican las ACL y detienen/arrancan el servicio de verdad. Los scripts descubren el canal desde el JAR instalado; no aceptan un modo TEST indicado por la interfaz. La activación comprueba `/actuator/health/readiness` antes de informar éxito.
