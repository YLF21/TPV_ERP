# Recuperación de eventos históricos

La operación de mantenimiento `POST /api/v1/admin/sync/events/{eventId}/recover`
está ausente por defecto. Solo se registra al activar temporalmente
`TPV_SAAS_SYNC_RECOVERY_ENABLED=true` y requiere una sesión administrativa con
`MANAGE_OPERATIONAL_INCIDENTS`.

El cuerpo contiene `companyId`, `expectedPayloadHash` (SHA-256 hexadecimal),
`expectedStatus` (`RECEIVED` o `ERROR`) y `reason`. No admite contenido de
sustitución. Bloquea empresa, instalación y evento; verifica procedencia,
instalación activa, estado esperado y hash del contenido original antes de
ejecutar el proyector existente. Conserva contenido, recepción, secuencia y
origen. La proyección y su auditoría se confirman en una sola transacción;
cualquier error revierte todos sus cambios. Repetir un evento ya terminado
devuelve `replayed=true` sin reaplicar ni duplicar la auditoría.

## Procedimiento

1. Detener brevemente las escrituras y obtener una copia completa de PostgreSQL,
   con hash verificado y versiones de aplicación registradas.
2. Restaurarla en una red y volumen aislados. Guardar un manifiesto cerrado de
   eventos, empresas, hashes y estados esperados, ordenado cronológicamente.
3. Comparar huellas de las tablas y de los eventos originales. Ejecutar el
   manifiesto mediante la API y detenerse ante cualquier discrepancia.
4. Repetir el manifiesto y comprobar idempotencia. Validar también saldos,
   lotes, reservas, documentos, estado fiscal, categorías y asignaciones.
5. Tras superar el ensayo, tomar una copia actualizada del entorno real y
   aplicar exactamente el manifiesto validado. No cambiar hashes para salvar
   conflictos ni borrar eventos pendientes.
6. Verificar las mismas invariantes, conservar resultados y auditoría, y retirar
   la variable de mantenimiento para dejar el endpoint deshabilitado.

`IGNORED` conserva el evento original: los tipos históricos sin proyector siguen
disponibles para las consultas que agregan esos eventos. `PROJECTED` tampoco
significa necesariamente que un saldo esté activo: las operaciones de puntos
con autoridad `NOT_INITIALIZED` deben quedar en `PENDING_BOOTSTRAP`, sin activar
la autoridad ni modificar cuentas oficiales. Su inicialización es un proceso
independiente.

Una inicialización residual de categorías vacía se cancela exclusivamente con
la API de incidencias operativas y un `commandId` estable. Debe existir una
inicialización completada, no haber bloques ni snapshots y superarse las
comprobaciones de inactividad. No completar una inicialización vacía ni
reemplazar las categorías vigentes para eliminar el aviso.
