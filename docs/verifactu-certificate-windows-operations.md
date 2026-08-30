# Custodia del certificado VeriFactu en Windows

Este procedimiento prepara la custodia local de la clave privada utilizada por
el backend para autenticarse ante la AEAT. El secreto se cifra con Windows
DPAPI en ambito de maquina y sus archivos quedan accesibles exclusivamente para
el servicio del backend, `SYSTEM` y el grupo local de administradores.

La ruta de produccion es fija:

```text
C:\ProgramData\TPV ERP\secrets\verifactu
```

No se debe sustituir por una carpeta compartida, sincronizada en la nube, una
unidad de red o una ruta situada dentro de imagenes de producto o backups.

## Orden de instalacion

1. Instale el backend como servicio de Windows con el nombre exacto
   `TPVERPBackend`; el instalador conserva la cuenta integrada efectiva (por
   defecto `NT AUTHORITY\LocalService`).
2. Mantenga el servicio detenido. El script rechaza la operacion si el servicio
   no existe, usa otra identidad o esta iniciado.
3. Abra Windows PowerShell como administrador desde la raiz del repositorio y
   ejecute:

   ```powershell
   .\backend\windows\Provision-VerifactuSecretDirectory.ps1
   ```

   Para inspeccionar la operacion sin modificar el equipo:

   ```powershell
   .\backend\windows\Provision-VerifactuSecretDirectory.ps1 -WhatIf
   ```

4. Configure las variables de produccion en el wrapper del servicio, no en el
   perfil interactivo de un usuario:

   ```text
   SPRING_PROFILES_ACTIVE=prod
   TPV_DB_URL=jdbc:postgresql://localhost:5432/tpv_erp
   TPV_DB_USERNAME=<usuario-limitado-del-backend>
   TPV_DB_PASSWORD=<secreto>
   TPV_VERIFACTU_SECRET_DIRECTORY=C:\ProgramData\TPV ERP\secrets\verifactu
   TPV_VERIFACTU_RUNTIME_CLASS=REAL
   TPV_VERIFACTU_ENDPOINT_MODE=TEST
   TPV_VERIFACTU_ENDPOINT_ENVIRONMENT=TEST
   TPV_VERIFACTU_TRANSPORT_MODE=AEAT
   TPV_VERIFACTU_AEAT_TEST_NETWORK_ENABLED=true
   TPV_VERIFACTU_PRODUCTION_ENABLED=false
   TPV_VERIFACTU_WORKER_ENABLED=false
   TPV_VERIFACTU_PRODUCER_NAME=<nombre legal definitivo del productor>
   TPV_VERIFACTU_PRODUCER_TAX_ID=<NIF legal definitivo del productor>
   TPV_VERIFACTU_SYSTEM_NAME=<nombre definitivo declarado del SIF>
   TPV_VERIFACTU_SYSTEM_ID=<identificador definitivo declarado del SIF>
   TPV_VERIFACTU_SYSTEM_VERSION=<version exacta del build que se prueba y desplegara>
   TPV_VERIFACTU_DECLARATION_HASH=<SHA-256 hexadecimal de la declaracion responsable>
   ```

   El comando Java del servicio debe incluir el acceso nativo requerido por
   JNA para DPAPI y la comprobacion de ACL en Java 25 y versiones posteriores:

   ```text
   java --enable-native-access=ALL-UNNAMED -jar tpv-erp-backend.jar
   ```

5. Inicie el backend, importe el `.p12` o `.pfx` desde APP GESTION y compruebe
   en el diagnostico VeriFactu que el certificado se puede abrir y esta vigente.
6. Complete las pruebas contra AEAT TEST y conserve su evidencia. El opt-in
   `TPV_VERIFACTU_AEAT_TEST_NETWORK_ENABLED=true` es obligatorio para que el
   transporte pueda abrir conexiones de preproduccion. La identidad del productor,
   el SIF, la version y el hash de declaracion utilizados en AEAT TEST deben ser
   ya los definitivos y no pueden cambiar al promover ese mismo build. Para pasar
   a produccion cambie de forma atomica solo los controles de entorno siguientes,
   solo despues de cerrar la
   declaracion responsable y autorizar expresamente la instalacion para remitir
   registros reales:

   ```text
   TPV_VERIFACTU_RUNTIME_CLASS=REAL
   TPV_VERIFACTU_ENDPOINT_MODE=PRODUCTION
   TPV_VERIFACTU_ENDPOINT_ENVIRONMENT=PRODUCTION
   TPV_VERIFACTU_TRANSPORT_MODE=AEAT
   TPV_VERIFACTU_AEAT_TEST_NETWORK_ENABLED=false
   TPV_VERIFACTU_PRODUCTION_ENABLED=true
   TPV_VERIFACTU_WORKER_ENABLED=true
   ```

   Para ejecutar el verificador manual use siempre un prompt seguro o una
   variable de entorno con nombre explicito. El script no acepta
   `AccessToken` ni `CertificatePassword` como argumentos de texto, y nunca
   escribe esos secretos en la evidencia ni en los mensajes de error:

   ```powershell
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\verify-verifactu-aeat-test.ps1 `
      -Preflight -CertificatePath .\aeat-test.p12 -PromptForCertificatePassword

    # Para la ejecucion, omitir el token de la linea de comandos:
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\verify-verifactu-aeat-test.ps1 `
      -AllowAeatTest -PromptForAccessToken -BackendBaseUrl https://127.0.0.1:8080 `
      -CompanyId <empresa> -InstallationId <instalacion> -ExpectedReleaseId <release>
    ```

   En automatizaciones no interactivas, el secreto puede inyectarse mediante
   `TPV_VERIFACTU_AEAT_TEST_ACCESS_TOKEN` y, solo para `-Preflight`,
   `TPV_VERIFACTU_AEAT_TEST_CERTIFICATE_PASSWORD`. No se deben guardar esas
   variables en el repositorio ni incluirlas en logs.

   Mantenga sin cambios las seis variables de identidad configuradas para TEST.
   Si el build cambia, debe usar su nueva version real y repetir AEAT TEST antes
   de promoverla. Reinicie el servicio despues de modificar sus variables. El backend aborta
   el arranque si mezcla TEST y PRODUCTION, conserva identidades
   DEV/provisionales o no recibe el hash de la declaracion; en TEST, el
   transporte rechaza cualquier envio si falta su opt-in de red.

El script es idempotente: puede repetirse con el servicio detenido para reparar
ACL. Antes de cualquier cambio valida la ruta fija, los ancestros existentes,
la ausencia de enlaces o junctions y la identidad del servicio. Deshabilita la
herencia y reemplaza las reglas de acceso de todo el arbol por tres identidades:

- La cuenta que figure en `Win32_Service.StartName` para `TPVERPBackend`:
  el script de aprovisionamiento la lee y valida; no se debe asumir una cuenta
  virtual distinta a la configurada en WinSW.
- `SYSTEM`: control total.
- `BUILTIN\Administrators`: control total.

Una ejecucion parcial se puede corregir repitiendo el script. No copie archivos
manualmente mientras el backend este iniciado.

## Backup

El backup ordinario del ERP contiene la base de datos y las imagenes de
producto, pero no incluye las claves privadas VeriFactu. Esta exclusion es
intencionada: un blob protegido con DPAPI en ambito de maquina no constituye
una copia recuperable para otro equipo.

- Conserve el PKCS#12 original y su contrasena mediante el procedimiento seguro
  externo definido por la empresa.
- No añada `verifactu` al archivo del backup ni copie `private-key.dpapi` a una
  carpeta de backup.
- No configure `TPV_PRODUCT_IMAGE_DIRECTORY` ni `TPV_BACKUP_DIRECTORY` dentro
  del directorio de secretos, ni el directorio de secretos dentro de ellos.
- Una copia de `private-key.dpapi` no sustituye al PKCS#12 original.

## Restauracion en el mismo equipo

1. Detenga `TPVERPBackend` y deje `TPV_VERIFACTU_WORKER_ENABLED=false`.
2. Restaure el backup de la aplicacion.
3. Compruebe que el directorio de secretos sigue presente y vuelva a ejecutar
   el script de aprovisionamiento para verificar y reparar sus ACL.
4. Inicie el backend y compruebe el certificado y la cola desde APP GESTION.
5. Si el certificado no se puede abrir, reimporte el PKCS#12 antes de habilitar
   los envios.

## Restauracion en otro equipo o despues de reinstalar Windows

DPAPI en ambito de maquina vincula el secreto al sistema Windows que lo
protegió. No se debe esperar que un archivo `.dpapi` copiado funcione en otro
equipo ni despues de reinstalar el sistema.

1. Registre el nuevo servicio con su cuenta virtual y ejecute el script de
   aprovisionamiento.
2. Restaure la base de datos con el worker deshabilitado.
3. Reimporte el `.p12` o `.pfx` original desde APP GESTION. Si ya existe un
   certificado activo en los metadatos restaurados, utilice el flujo de
   sustitucion y confirme la huella del nuevo certificado.
4. Verifique certificado, reloj, conectividad y cola antes de reactivar envios.

Los metadatos fiscales, la cadena y el historial no deben borrarse para resolver
un secreto ausente. Si no se conserva el PKCS#12 original, debe obtenerse un
certificado valido y sustituirse mediante el flujo administrativo previsto.

## Comprobacion manual de ACL

Con el servicio detenido, un administrador puede consultar la DACL sin mostrar
ningun secreto:

```powershell
(Get-Acl -LiteralPath 'C:\ProgramData\TPV ERP\secrets\verifactu').Access |
    Select-Object IdentityReference, FileSystemRights, AccessControlType,
        IsInherited
```

Solo deben aparecer la cuenta virtual del servicio, `SYSTEM` y
`BUILTIN\Administrators`; ninguna regla debe ser heredada.
