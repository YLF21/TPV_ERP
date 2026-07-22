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
   `TPVERPBackend` y la cuenta virtual `NT SERVICE\TPVERPBackend`.
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
   TPV_VERIFACTU_ENDPOINT_MODE=TEST
   TPV_VERIFACTU_WORKER_ENABLED=false
   ```

   El comando Java del servicio debe incluir el acceso nativo requerido por
   JNA para DPAPI y la comprobacion de ACL en Java 25 y versiones posteriores:

   ```text
   java --enable-native-access=ALL-UNNAMED -jar tpv-erp-backend.jar
   ```

5. Inicie el backend, importe el `.p12` o `.pfx` desde APP GESTION y compruebe
   en el diagnostico VeriFactu que el certificado se puede abrir y esta vigente.
6. Complete las pruebas contra el entorno de AEAT correspondiente. Cambie a
   `PRODUCTION` y habilite el worker solo cuando la instalacion este autorizada
   para remitir registros reales:

   ```text
   TPV_VERIFACTU_ENDPOINT_MODE=PRODUCTION
   TPV_VERIFACTU_WORKER_ENABLED=true
   ```

   Reinicie el servicio despues de modificar sus variables.

El script es idempotente: puede repetirse con el servicio detenido para reparar
ACL. Antes de cualquier cambio valida la ruta fija, los ancestros existentes,
la ausencia de enlaces o junctions y la identidad del servicio. Deshabilita la
herencia y reemplaza las reglas de acceso de todo el arbol por tres identidades:

- `NT SERVICE\TPVERPBackend`: control total.
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
