# Despliegue productivo del backend TPV ERP en Windows

Este procedimiento prepara y verifica un backend con capacidad `VERIFACTU_ONLY`.
La version, identidad y secuencias se leen del perfil `production-release` de
`backend/pom.xml`; el esquema se lee de `META-INF/tpv-erp-release.properties`.
No hay un numero de migracion duplicado en los scripts. No descarga WinSW ni
escribe secretos. Registrar, aplicar ACL y arrancar son fases separadas; un
preflight no instala ni arranca servicios.

## Requisitos de promoción

El bundle debe contener un único fat JAR, su sidecar `JAR.sha256`,
`META-INF/tpv-erp-release.properties` y el PDF legal versionado dentro del
JAR en `META-INF/fiscal/declaracion-responsable-4.2.0.pdf`. El manifiesto debe
tener identidad/secuencias aprobadas para esa publicacion,
`capability=VERIFACTU_ONLY`, un `schema.version`, un `commit.hash`,
`declaration.hash` y un `manifest.hash` coherente. El verificador compara todos
los hashes y la firma `%PDF-`; nunca imprime contraseñas, tokens ni contenido
del PDF. Ademas exige `PropertiesLauncher` y `OfflineRestoreCli` dentro del
fat JAR: la recuperacion no depende de clases de desarrollo no distribuidas.

La ausencia del PDF legal/identidad, un manifiesto sin filtrar, un sidecar
incorrecto o cualquier versión `DEV` bloquean la promoción. En el estado
actual del checkout no hay PDF legal productivo embebido: hay que obtenerlo y
registrarlo según el proceso legal antes de ejecutar el empaquetado.
El parámetro opcional `-DeclarationPdf` solo acepta esa ruta de recurso
versionado; no se calcula el hash de un PDF externo que no vaya a quedar dentro
del JAR.

## Preparar y verificar

Desde la raíz del repositorio, en un checkout limpio:

```powershell
.\tools\Prepare-TpvBackendProductionBundle.ps1
```

El script calcula el commit de Git y los hashes de declaración/manifiesto,
pasa explícitamente `ExpectedReleaseId` y `ExpectedVersion` a las propiedades
Maven del perfil, invoca `backend\mvnw.cmd -Pproduction-release clean package` y publica el JAR y su
sidecar en `artifacts\backend-<release-id>`. No se deben usar credenciales Maven ni
variables con secretos en la línea de comandos. Para verificar un bundle ya
preparado sin compilar:

```powershell
.\tools\Test-TpvBackendProductionBundle.ps1 `
  -BundleDirectory '.\artifacts\backend-<release-id>' `
  -ExpectedReleaseId '<release-id-aprobado>'
```

`ExpectedReleaseId` puede cambiar para una nueva identidad manteniendo la misma
versión pública `4.2.0`. El nombre del JAR depende del `<version>` de
`backend\pom.xml`; el preparador exige que `ExpectedVersion` coincida con ese
`project.version` y no cambia el nombre del artefacto Maven.

Sin expectativas explicitas, el verificador comprueba la integridad e identidad
del propio bundle, no su autorizacion operativa. El instalador y el CLI exigen
siempre `ExpectedReleaseId` confirmado por el operador. Si cambia el orden de publicación, pase también
`-ExpectedReleaseSequence` y `-ExpectedBuildSequence` tanto al preparador como
al verificador; ambas secuencias forman parte del hash canónico.

`-NoBuild` permite copiar un artefacto ya compilado desde `backend\target`,
pero no evita la verificación final. El preparador rechaza siempre un checkout
sucio, también con `-NoBuild`; una release solo puede salir de un checkout
limpio.

## Preflight e instalación del servicio

WinSW debe ser proporcionado por el operador desde un canal oficial y su hash
debe conocerse. El script no lo descarga ni lo instala desde Internet. La
configuración Spring y el directorio de secretos deben estar fuera del bundle;
el fichero de configuración se referencia por ruta y nunca se copia dentro de
la release.

En un host nuevo, prepare el fichero externo sin secretos en la linea de
comandos. Ejecute como administrador el preflight de **registro**; no requiere
un SID virtual que aun no existe ni directorios fiscales aprovisionados:

```powershell
$installArgs = @{
  BundleDirectory = 'D:\releases\backend-<release-id>'
  ExpectedReleaseId = '<release-id-aprobado>'
  WinSwExecutable = 'D:\provision\WinSW-x64.exe'
  WinSwSha256 = '<SHA256_DE_WINSW>'
  JavaExecutable = 'C:\Program Files\Java\jdk-25\bin\java.exe'
  ConfigurationFile = 'C:\ProgramData\TPV ERP\config\application-prod.yml'
}
.\tools\Install-TpvBackendWindowsService.ps1 @installArgs -Phase Register -Preflight
.\tools\Install-TpvBackendWindowsService.ps1 @installArgs -Phase Register
# Servicio registrado, detenido y con inicio Manual; ahora existe su SID.
.\tools\Set-TpvBackendWindowsAcl.ps1 -Phase Apply `
  -ConfigurationFile $installArgs.ConfigurationFile
.\tools\Install-TpvBackendWindowsService.ps1 @installArgs -Phase Start -Preflight
# Solo despues de validar configuracion fiscal, certificado y pruebas de aceptacion:
.\tools\Install-TpvBackendWindowsService.ps1 @installArgs -Phase Start
```

`Register` registra o actualiza `TPVERPBackend` mediante WinSW, siempre sin
arrancarlo y con inicio Manual. `Start` es la accion explicita que habilita
inicio Automatic y arranca; exige release/hash/XML/configuracion/cuenta
coherentes y ACL completas, y revierte a Manual si WinSW falla. El bind obligatorio es
loopback (`127.0.0.1:8080` por defecto; también se admite `::1`). No se permite
exponer el backend directamente en otra interfaz. Se recomienda la cuenta
virtual `NT SERVICE\TPVERPBackend` (predeterminada); se conservan las cuentas
integradas para compatibilidad, sin contraseñas serializadas. El aplicador
general de ACL requiere la cuenta virtual.

`-WhatIf` se puede usar en lugar del preflight para simular las operaciones de
escritura. Cada release se conserva de forma inmutable en
`releases\<release-id>-r<release-sequence>-b<build-sequence>`: si ya existe con
otro hash, la operación aborta y no se sobrescribe. Al actualizar, se guarda la
configuración WinSW anterior y el ejecutable wrapper en `rollback`. Si `refresh`
falla se restauran ambos y se intenta refrescar de nuevo; esto no es un rollback
completo de todos los efectos externos, sino la conservación recuperable del
XML, wrapper y bundles locales.

WinSW v3 requiere `<serviceaccount><username>...</username></serviceaccount>`;
la forma de cuenta está contrastada con la [documentación oficial de
WinSW](https://github.com/winsw/winsw/blob/v3/docs/xml-config-file.md#service-account).
En una instalación nueva se escribe la cuenta integrada solicitada y también
`TPV_VERIFACTU_SERVICE_ACCOUNT` en el XML. En una actualización WinSW no cambia
la cuenta con `refresh`: el script compara `StartName` y aborta si no coincide.

El XML activa explícitamente el perfil Spring `prod`. La ruta externa se pasa
como una URI `file:///C:/...` obligatoria y como un único argumento citado (sin
`optional:`), por lo que un fichero ausente bloquea el arranque y los espacios
de `C:\ProgramData\TPV ERP\...` no se separan en varios argumentos. El
preflight exige Java 25 y un `ListenAddress` loopback literal (`127.0.0.1` o
`::1`); no se permite exponer este backend directamente en una interfaz de red.

El preflight de `Start` exige que exista el directorio de secretos indicado por
`-SecretDirectory` (por defecto
`C:\ProgramData\TPV ERP\secrets\verifactu`), que él y sus archivos no sean
reparse points. La ACL de secretos exige FullControl para servicio, SYSTEM y
Administrators, sin herencia ni otros titulares, en coherencia con la politica
Java que protege nuevos blobs DPAPI. Exportaciones exige Modify para el
servicio y FullControl para SYSTEM/Administrators. Releases/configuracion no
son escribibles por el servicio. La falta de lectura directa de configuracion
bloquea el inicio, no se reduce a una advertencia.

El aprovisionamiento de ACL del directorio VeriFactu continúa siendo una
operación separada y explícita con
`backend\windows\Provision-VerifactuSecretDirectory.ps1`. No coloque
certificados, contraseñas, tokens ni cadenas de conexión en este repositorio,
en el bundle ni en el XML de WinSW. Ejecute además el mismo aprovisionador con
`-DirectoryKind FiscalExport` para crear/proteger el directorio de exportaciones
fiscales; su ACL solo admite la cuenta efectiva del servicio, SYSTEM y
Administrators, sin Users. El instalador vuelve a verificar esa ACL y rechaza
el despliegue si se reintroduce herencia o una identidad adicional.

## Comprobaciones contractuales

La sintaxis de los scripts y sus invariantes de seguridad se cubren con:

```powershell
Invoke-Pester .\tools\TpvBackendProductionDeployment.Tests.ps1
```

Estas pruebas crean únicamente bundles ZIP temporales para validar el hash
canónico y la identidad de release; no crean servicios, no descargan
dependencias y no contactan AEAT.

## Pendientes de producción

- Incorporar el PDF legal firmado/versionado de `4.2.0` bajo el recurso de
  classpath esperado y reconstruir el fat JAR.
- Ejecutar el build completo en checkout limpio y conservar la evidencia de
  hashes.
- Validar externamente la configuración PostgreSQL, certificados y secretos,
  sin incluir sus valores en Git ni en argumentos.
- Hacer una prueba controlada de parada, arranque, readiness y rollback en el
  equipo objetivo antes de aceptar tráfico real.
