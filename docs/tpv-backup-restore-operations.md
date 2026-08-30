# Operación de backup y restauración

## Configuración inicial

El backend no crea una configuración ni una clave de backup automáticamente. Un
administrador debe abrir la configuración de backups y confirmar una contraseña
ADMIN antes de activar las copias. Mientras no exista esa configuración, la
respuesta de configuración y la ejecución programada indican que el backup aún
no está configurado.

El historial HTTP devuelve las 100 ejecuciones más recientes para que una
instalación con años de operación no materialice toda la tabla en cada consulta.

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

La restauración HTTP está bloqueada por defecto (`TPV_BACKUP_RESTORE_ONLINE_ENABLED=false`).
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
  -BackendClasses 'C:\ProgramData\TPV ERP\Backend\current\classes' -Execute
```

La herramienta crea un safety backup de los árboles, escribe el journal sin
secretos y sólo promueve ficheros tras un `pg_restore --single-transaction`
correcto. Si algo falla, no elimina el journal ni el staging. Antes del arranque
normal hay que ejecutar el modo no-web:

```text
java -jar backend.jar --spring.profiles.active=prod --spring.main.web-application-type=none --tpv.restore-finalize="C:\ProgramData\TPV ERP\restore\tpv-restore-journal.properties"
```

Ese finalize comprueba el journal y el modo fiscal restaurado: en NO_VERI*FACTU
crea y firma el evento fiscal 07; en VERI*FACTU y PRE_SIF registra sólo auditoría
operativa. Si el modo no se puede determinar, falla cerrado. Producción aborta
si queda un journal pendiente. Los certificados y secretos de VeriFactu siguen
fuera del ZIP y deben re-provisionarse mediante su procedimiento específico.

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
administrador. La cuenta `NT SERVICE\TPVERPBackend` obtiene sólo lectura y
ejecución en releases/configuración/secretos, y `Modify` únicamente en
logs/exports/operacional. Administrators y SYSTEM conservan `FullControl`.
El script rechaza rutas inexistentes de configuración, reparse points y
herencia ACL; así no se resuelve la identidad antes del registro ni se abre un
bucle de bootstrap inseguro.
