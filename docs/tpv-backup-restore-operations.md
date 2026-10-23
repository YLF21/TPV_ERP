# Operación de backup y restauración

## Protección en SaaS y recuperación por avería

Cada copia nueva y verificada se prepara para su envío automático a SaaS. El
resultado local y la entrega remota son independientes: sin conexión o sin
credencial SaaS, el archivo local se conserva y la entrega permanece pendiente.
La cola guarda su propio archivo cifrado y reintenta después de cortes o
reinicios. El detalle muestra el estado de envío y permite solicitar un reintento.
La retención local no elimina el único archivo de una entrega aún no preparada.
El volcado y ZIP temporales sin cifrar se preparan en `backup-work` dentro del
directorio privado de claves del servidor, con sus permisos, aunque la carpeta
de salida de copias sea una unidad compartida. Se retiran al finalizar el trabajo.

SaaS conserva **tres copias verificadas por instalación**, con su archivo de
recuperación v2 envuelto. Verifica tamaño y SHA-256 antes de confirmar una carga;
una carga incompleta no sustituye una copia válida. El almacenamiento utiliza un
volumen persistente separado del sistema de archivos de la imagen. La copia
elegida por una recuperación activa queda fijada durante el procedimiento.

El secreto de recuperación no se sube a SaaS. Debe conservarse fuera del servidor,
en un gestor de contraseñas o por otro medio seguro. La contraseña ADMIN, el
archivo `.tpvb` o una autorización de soporte por sí solos no descifran la copia.
Tras validar ese secreto, la instalación conserva un sello DPAPI de las huellas
de la clave local y del archivo de recuperación. Si alguno cambia o se daña,
se bloquea el envío a SaaS para no reemplazar copias recuperables por otras cuya
clave no corresponda. Las instalaciones v2 anteriores que no tengan ese sello
deben volver a validar su clave en la configuración de copias; sus copias locales
siguen funcionando. La recepción autorizada instala el sello bajo la cuenta del
servicio a partir del material ya comprobado.
Dentro del paquete nuevo se incluye una cápsula adicional cifrada con separación
de claves para transportar identidad y secretos exportables; nunca se escriben
esos secretos en un ZIP temporal sin su propia protección. Los paquetes antiguos
sin esa cápsula siguen siendo legibles como datos, pero no equivalen a una
recuperación completa de un equipo perdido.

La recuperación por avería requiere **autorización de soporte** y restaura
exclusivamente la copia seleccionada. No reconstruye datos posteriores desde
SaaS ni modifica el historial central:

1. Registrar los servicios de la misma release en el equipo nuevo, manteniendo
   el backend detenido y la base de datos vacía.
2. Elegir **Recuperar desde una copia de seguridad** en el primer inicio y generar
   la solicitud pública del destino. La clave privada queda protegida en ese
   equipo y se conserva ante reinicios.
3. Entregar esa solicitud a soporte. Desde el ticket de la instalación, soporte
   consulta las tres copias y autoriza una recuperación ligada a la copia y a la
   clave del destino. Las descargas están restringidas a esa autorización.
4. Recibir la copia mediante el asistente instalado e introducir el secreto de
   recuperación únicamente en la consola protegida. Se contrastan manifiesto,
   datos, archivos, identidad y claves. El servidor queda en cuarentena.
5. Soporte revisa la evidencia contra SaaS. Un documento o evento central ausente
   o diferente, un certificado inválido o una comprobación incompleta impiden la
   apertura. También revisa la situación fiscal externa y los datos compartidos
   que no puedan comprobarse únicamente con la copia. Una confirmación genérica
   no elimina los bloqueos por diferencias conocidas.
6. Tras una revisión satisfactoria y la confirmación de retirada del equipo
   antiguo, soporte aprueba la activación única del destino. Se renuevan las
   credenciales, se invalidan las sesiones copiadas y se vuelve a vincular `001`.
   El primer arranque completa las comprobaciones fiscales antes de operar.

La fecha de captura delimita los datos recuperados. Las operaciones posteriores
que solo existieran en el disco averiado pueden haberse perdido. No se garantiza
la detención física de un equipo antiguo desconectado: soporte debe retirarlo o
aislarlo; la rotación de credenciales impide su acceso a SaaS.

## Gestión de copias y traslado planificado

La pantalla **APP GESTIÓN → Seguridad → Copias de seguridad** muestra el catálogo
paginado, los identificadores permanentes, el tamaño del paquete cifrado y su
disponibilidad. Doble clic o **Ver detalle** consulta el manifiesto conservado en
la copia, nunca calcula sus cantidades con los datos actuales. Los paquetes
anteriores sin manifiesto siguen siendo legibles, con detalle no disponible.

Las nuevas copias se denominan `000123.tpvb`. El número se asigna en PostgreSQL
por configuración y no se reutiliza por eliminar filas del historial. Si el
directorio contiene ese número, se rechaza la nueva ejecución sin sobrescribir
ni borrar el archivo existente. La retención reconoce nombres nuevos y antiguos.
Los recuentos y `pg_dump --snapshot` comparten una instantánea exportada; las
mutaciones de imágenes y plantillas del backend esperan hasta terminar el ZIP.
La coordinación de archivos cubre un único proceso escritor: otros procesos
que modifiquen esos árboles deben detenerse durante la captura.

**No existe restauración libre por HTTP**, incluso si una configuración antigua
incluye `TPV_BACKUP_RESTORE_ONLINE_ENABLED=true`. La recuperación excepcional
offline descrita más abajo es una herramienta técnica separada y no aparece
como acción de la pantalla. No sustituye un traslado autorizado.

El traslado ordinario requiere ADMIN protegido, servidor local `001`, origen
operativo, destino sin tablas comerciales y conexión HTTPS con SaaS. Mantiene
la empresa, tienda e instalación lógicas. No revoca ni sustituye su historial
central y no crea otros UUID para documentos ya existentes.

1. En el nuevo equipo, preparar una base PostgreSQL vacía e instalar la **misma
   release y esquema** que el origen. Registrar el servicio con `-Phase Register`
   y mantenerlo detenido: no iniciar el bootstrap sobre la base vacía. En el mantenimiento de Gestión, generar
   la solicitud del destino. La clave privada del destino queda cifrada con
   DPAPI de máquina y debe conservarse durante todo el proceso.
2. Llevar la solicitud pública al origen y preparar el traslado con ADMIN.
   Antes de sellar, cerrar los demás terminales y resolver pagos inciertos.
3. Sellar el traslado. Se drenan trabajos en curso y se bloquea el origen
   persistentemente, tanto en PostgreSQL como fuera de la base de datos. Se
   genera una copia final nueva: una copia diaria ordinaria no es válida.
4. Transportar juntos el `.tpvb`, su `.capsule` cifrada para ese destino y su
   `.transfer.json`. Las claves privadas nunca se entregan al renderer ni se
   pasan por argumentos de procesos. Los certificados no exportables o que
   no correspondan al nombre HTTPS del destino deben reprovisionarse antes
   de activar.
5. Recibir el paquete con el backend detenido. Se comprueban autorización
   central, huellas, instalación, versión, recuentos y secretos. El catálogo
   de la copia final y su directorio se ajustan al equipo nuevo sin modificar
   los estados fiscales ni las confirmaciones de sincronización.
6. Activar una sola vez mediante SaaS. Los reintentos recuperan la misma
   autorización del destino; una activación sustituida o revocada se rechaza.
   El primer arranque bajo la cuenta real del servicio reprotege las claves
   que usan DPAPI de usuario. El servidor `001` requiere una nueva vinculación
   física; las sesiones copiadas quedan revocadas.

Antes de la activación se puede cancelar con prueba de SaaS; un timeout no
desbloquea automáticamente el origen. Después, la vuelta al equipo anterior
requiere otro traslado desde el estado vigente. El ordenador de origen queda
retirado. La operación local habitual conserva su autonomía sin Internet; este
flujo no pretende impedir manipulación externa o clonación deliberada de discos.

Los bundles nuevos incluyen las herramientas en `tools/` y sus huellas en
`maintenance-tools.sha256.json`. El preparador, verificador e instalador validan
esas huellas. El asistente de escritorio usa exclusivamente el helper de la
release instalada, solicita elevación para el mantenimiento y nunca recibe una
ruta de ejecutable enviada por el renderer. No ejecutar los helpers desde un
checkout de desarrollo contra una instalación productiva.

En el uso técnico directo, `Invoke-TpvServerTransfer.ps1` exige las cuentas
Windows reales `TPV_BACKEND_SERVICE_ACCOUNT` y
`TPV_TRANSFER_HTTPS_SERVICE_ACCOUNT` para preparar, recibir y activar. El
asistente instalado las obtiene de los XML verificados de ambos servicios.
No se sustituye una cuenta ausente por el administrador que ejecuta la consola.

### Comprobación antes de desplegar

Las pruebas automatizadas cubren una base aislada, ventas concurrentes durante
la captura, cifrado, manifiesto, catálogo y recepción real mediante PostgreSQL,
HTTPS y DPAPI de Windows. Incluyen recuperación de un fallo entre la restauración
y el ajuste del catálogo, reintentos y rechazo de paquetes ordinarios. La
recuperación por avería prueba asimismo la pérdida de la respuesta de activación
y de finalización, manteniendo la barrera hasta el reintento correcto. La
autoridad HTTPS del ensayo es simulada; no se han usado empresas ni SaaS reales.

La revisión correctiva local reúne 96 casos: 92 correctos y cuatro omitidos por
falta de permisos de enlaces simbólicos en este Windows. Incluye tres casos de
recepción real de traslado/recuperación con PostgreSQL y las regresiones de clave
de recuperación, reintentos y archivos pendientes de envío. En SaaS pasan diez
casos de recuperación y cinco de almacenamiento: concurrencia de cuatro cargas,
retención de tres copias, rollback, archivos dañados o ausentes y copias fijadas.
Se mantienen las comprobaciones anteriores del contexto Spring completo, ocho
casos de traslado y 27 de licencias, cuyos componentes no se han vuelto a modificar.
Las compilaciones local, SaaS y Gestión son correctas.

Antes de eliminar el archivo local pendiente, el acuse `AVAILABLE` vuelve a
comprobar tamaño y huella del objeto remoto. La recuperación reserva también los
IDs conocidos por SaaS de cargas iniciadas aunque sus bytes no llegaran, para no
reutilizarlos tras una avería. Un informe idéntico reenviado conserva la aprobación
de soporte; evidencia distinta exige otra revisión. No se permite conceder una
aprobación nueva desde un ticket resuelto, pero se puede cancelar para invalidar
la autorización y liberar la copia fijada.

Antes de publicar esta versión debe ensayarse el procedimiento entre dos equipos
Windows con las cuentas reales de los servicios, certificados y terminales. Ese
ensayo físico debe comprobar también reinicios y pérdida de la respuesta de
activación, apertura del nuevo servidor y retirada persistente del origen.

La batería general heredada tiene una incidencia dependiente de la zona horaria:
el `CHECK` inicial de los 30 días de demostración usa días de calendario mientras
Java suma 720 horas. Los 27 casos de arranque afectados pasan con la conexión
Hikari de pruebas en UTC (`spring.datasource.hikari.connection-init-sql=SET TIME
ZONE 'UTC'`). Esta entrega no modifica esa regla ni la configuración productiva.

## Configuración inicial

El backend no crea una configuración ni una clave de backup automáticamente. Un
administrador debe abrir la configuración de backups y confirmar una contraseña
ADMIN antes de activar las copias. Mientras no exista esa configuración, la
respuesta de configuración y la ejecución programada indican que el backup aún
no está configurado.

El historial HTTP permite consultar todas las ejecuciones mediante páginas de
hasta 100 registros, sin materializar toda la tabla. Los registros se conservan
aunque la retención retire sus archivos. La hora diaria y el mes de conservación
se calculan en la zona horaria de la tienda, incluidos los cambios de horario.

Los paquetes v2 son independientes de ADMIN y un cambio de contraseña no hace
ninguna lectura ni escritura criptográfica. Si una instalación aún tiene v1,
el cambio automático de ADMIN se bloquea para evitar split-brain; un técnico debe
migrar manualmente el paquete a v2. El rewrap v1 queda reservado a esa operación
manual controlada.

Las configuraciones nuevas requieren una clave de recuperación v2 independiente
del PIN/contraseña ADMIN (mínimo 16 caracteres) o un código generado aleatorio
de 256 bits. El código sólo se muestra al operador que lo provisiona y no se
guarda en respuestas, logs ni auditoría. Los paquetes v1 existentes se pueden
leer durante la transición, pero el sistema no crea nuevos paquetes legacy.

Cada ejecución usa un token de fencing y un lease renovado periódicamente. La
restricción única en PostgreSQL impide dos workers multinodo para la misma
configuración; un lease caducado se marca como fallo antes de reclamar otro.
Las transacciones sólo cubren reclamar, heartbeat y finalizar, nunca pg_dump,
ZIP o cifrado.

## Restauración

La restauración HTTP se ha retirado. El siguiente procedimiento es únicamente
para recuperación excepcional técnica, fuera del flujo ordinario del producto.
No se puede prometer atomicidad entre PostgreSQL y dos árboles de ficheros con el
backend atendiendo tráfico: `pg_restore` y el reemplazo de ficheros son sistemas
distintos. No habilite esa opción como sustituto de una ventana de mantenimiento.

Antes de cualquier operación, ejecute el preflight (solo lectura):

```powershell
.\tools\Test-TpvBackupRestoreOfflinePreflight.ps1 `
  -BackupFile 'D:\backups\tpv-erp-2026-08-27.tpvb' `
  -RecoveryFile 'D:\backups\tpv-backup-recovery.key' `
  -ProductImagesDirectory 'C:\Users\Public\.tpv-erp\product-images' `
  -DocumentTemplatesDirectory 'C:\Users\Public\.tpv-erp\document-templates'
```

La fase de archivo siempre descifra y extrae a staging, comprueba que exista
exactamente una entrada `database.backup`, rechaza rutas inseguras, enlaces
simbólicos, duplicados y archivos ZIP que superen los límites configurados.
Solo tras completar esa validación se puede restaurar la base de datos. El
reemplazo de árboles conserva una copia temporal y revierte los árboles si el
reemplazo falla; si PostgreSQL ya terminó, el error lo declara explícitamente
porque la base de datos no puede deshacerse con ese rollback.

La secuencia de mantenimiento es:

1. Parar el servicio/backend y confirmar que no quedan procesos atendiendo.
2. Ejecutar el preflight anterior y conservar una copia de los árboles activos y
   el `pg_dump` de la base de datos actual que crea la herramienta.
3. Ejecutar el procedimiento interno de mantenimiento que descifra y valida el
   backup, ejecuta `pg_restore --single-transaction` y promueve los árboles desde
   staging.
4. Arrancar el backend y comprobar salud, migraciones y datos restaurados.

La ejecución efectiva (con el backend detenido) es:

```powershell
.\tools\Invoke-TpvBackupRestoreOffline.ps1 `
  -BackupFile 'D:\backups\tpv-erp-2026-08-27.tpvb' `
  -RecoveryFile 'D:\backups\tpv-backup-recovery.key' `
  -ProductImagesDirectory 'C:\ProgramData\TPV ERP\product-images' `
  -DocumentTemplatesDirectory 'C:\ProgramData\TPV ERP\document-templates' `
  -JournalFile 'C:\ProgramData\TPV ERP\restore\tpv-restore-journal.properties' `
  -BundleDirectory 'C:\ProgramData\TPV ERP\Backend\releases\<release-id>-r<secuencia>-b<build>' `
  -ExpectedReleaseId '<release-id-aprobado>' -Execute
```

El wrapper verifica el fat JAR, sidecar, manifiesto y declaracion; ejecuta
`OfflineRestoreCli` desde ese mismo JAR mediante `PropertiesLauncher`, sin
iniciar Spring/web y sin depender de `target/classes` ni `current/classes`.
Rechaza variables `LOADER_*` externas y fija la configuracion del launcher al
manifiesto verificado; la clave de recuperacion sigue solicitandose en consola.
No ejecutar desde el repositorio de desarrollo ni con un bundle distinto al
aprobado. [Launcher oficial Spring Boot](https://docs.spring.io/spring-boot/specification/executable-jar/property-launcher.html).

La herramienta crea un safety backup de los árboles, escribe el journal sin
secretos y sólo promueve ficheros tras un `pg_restore --single-transaction`
correcto. Si algo falla, no elimina el journal ni el staging. Antes del arranque
normal hay que ejecutar el modo no-web:

```text
java --enable-native-access=ALL-UNNAMED -jar "<JAR verificado del bundle>" --spring.profiles.active=prod --spring.main.web-application-type=none "--spring.config.additional-location=file:///C:/ProgramData/TPV%20ERP/config/application-prod.yml" --tpv.restore-finalize="C:\ProgramData\TPV ERP\restore\tpv-restore-journal.properties"
```

Ese finalize comprueba el journal y el modo fiscal restaurado: en NO_VERI*FACTU
crea y firma el evento fiscal 07; en VERI*FACTU y PRE_SIF registra sólo auditoría
operativa. Si el modo no se puede determinar, falla cerrado. Producción aborta
si queda un journal pendiente. Este flujo técnico no instala la cápsula portátil
de las copias nuevas: sus certificados y secretos de VeriFactu deben
reprovisionarse mediante su procedimiento específico. La recuperación con
soporte utiliza el asistente descrito al principio de este documento.

En producción la ruta del journal es fija y conocida por el backend, CLI y wrapper:
`C:\ProgramData\TPV ERP\restore\tpv-restore-journal.properties` (sólo se puede
cambiar mediante `TPV_BACKUP_RESTORE_JOURNAL_PATH`, que debe ser una ruta absoluta
normalizada y exacta). Un journal pendiente en cualquier fase bloquea el arranque
normal; el argumento `--tpv.restore-finalize` sólo admite esa misma ruta y las
fases `FILES_PROMOTED` o `FINALIZED`.

Tras un finalize correcto, `FINALIZED` se valida con el identificador, la huella
registrada y el marker transaccional en la base de datos; no se necesita conservar
el `.tpvb` original. Archive el journal y su staging/safety backup junto con la
evidencia de la operación y retire el journal de la ruta fija antes de iniciar una
nueva restauración. Nunca sobrescriba un journal pendiente.

En desarrollo el límite por entrada es 256 MiB y el total 2 GiB. Producción usa
límites explícitos independientes (`TPV_BACKUP_PROD_MAX_ENTRY_BYTES`,
`TPV_BACKUP_PROD_MAX_TOTAL_BYTES` y `TPV_BACKUP_PROD_MAX_ENTRIES`).

## ACL de Windows

La instalación mantiene el bootstrap en dos fases: primero se registra el
servicio con `Install-TpvBackendWindowsService.ps1`; después, ya existente la
identidad virtual, se ejecuta `Set-TpvBackendWindowsAcl.ps1 -Phase Apply` como
administrador. La cuenta `NT SERVICE\TPVERPBackend` obtiene lectura y
ejecución en releases, lectura en configuracion, FullControl en secretos
(politica exacta de la custodia Java) y `Modify` en
logs/exports/operacional/backup/imagenes/plantillas/restore. Administrators y SYSTEM conservan `FullControl`.
El script rechaza rutas inexistentes de configuración, reparse points y
herencia ACL; así no se resuelve la identidad antes del registro ni se abre un
bucle de bootstrap inseguro. El registro deja inicio Manual; solo `-Phase Start`
tras el preflight de ACL habilita el arranque.
