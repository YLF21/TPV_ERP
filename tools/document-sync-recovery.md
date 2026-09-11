# Recuperacion puntual de documentos en desarrollo

`document-sync-recovery.ps1` prepara un unico lote local de hasta 100 documentos y conserva evidencia de su consulta, preparacion y verificacion. Destinado a Windows PowerShell 5.1 o PowerShell 7.5+, backend con perfil `dev`, propiedad `tpv.sync.document-recovery-enabled=true` habilitada manualmente y una sesion local `ADMIN`. La herramienta no configura esa propiedad. No sirve para produccion ni para cambiar datos fiscales historicos.

No activa el worker, no hace flush, no reinicia servicios y no conecta directamente con SaaS. `Prepare` solo encola la primera revision mediante el endpoint idempotente; `Verify` observa el resultado. `ENQUEUED` no significa que el documento este proyectado en SaaS. Las comprobaciones de SaaS necesitan URL central y credenciales vigentes, pero no activar el worker. Si el worker habitual ya esta activo, el lote preparado podra enviarse automaticamente por esa cola; esta herramienta no cambia su configuracion.

## Un lote, tres acciones

Ejecutar desde la raiz del repositorio. Crear antes una carpeta de evidencia restringida al tecnico; los manifiestos contienen identificadores y datos de documentos, aunque nunca credenciales. No incluirlos en Git ni compartirlos publicamente.

```powershell
New-Item -ItemType Directory -Path .\target\document-recovery
$localAdminToken = Read-Host 'Token bearer LOCAL ADMIN' -AsSecureString

$preview = .\tools\document-sync-recovery.ps1 -Action Preview `
  -CompanyId '11111111-1111-1111-1111-111111111111' `
  -StoreId '22222222-2222-2222-2222-222222222222' `
  -DateFrom '2026-09-01' -DateTo '2026-09-10' `
  -OutputPath .\target\document-recovery\lote-001.json `
  -BearerToken $localAdminToken

# Revisar scope, documentos y problem antes de preparar. No editar el manifiesto.
Get-Content -LiteralPath $preview.ManifestPath

$prepared = .\tools\document-sync-recovery.ps1 -Action Prepare `
  -ManifestPath $preview.ManifestPath -Reason 'Recuperacion puntual DEV revisada por el tecnico' `
  -BearerToken $localAdminToken

$verified = .\tools\document-sync-recovery.ps1 -Action Verify `
  -ManifestPath $prepared.ManifestPath -BearerToken $localAdminToken
Get-Content -LiteralPath $verified.ManifestPath
```

Si se omite `BearerToken`, cada accion lo pide mediante entrada oculta. No pasar tokens de texto, no escribirlos en comandos ni ficheros, y no usar el token de instalacion SaaS. El token descifrado solo se usa para la cabecera en memoria; `SecureString` no hace desaparecer las limitaciones de memoria de .NET. No usar transcripciones, depuracion HTTP ni volcado de variables mientras se manejen credenciales.

`BaseUrl` es `http://127.0.0.1:8080` por defecto. Puede ser otro origen HTTPS o HTTP de loopback, sin ruta, query ni credenciales. Para otro puerto, proporcionar el mismo `-BaseUrl` en las tres acciones. No se siguen redirecciones. Cada accion realiza una sola peticion POST, con timeout de conexion/respuesta de 120 segundos y limite de respuesta de 4 MiB. No hay reintentos ni recorridos automaticos.

## Archivos y reintentos

- Preview escribe exclusivamente `OutputPath`. Si ya existe, se rechaza; elegir un nombre nuevo. No hay opcion de sobrescritura ni borrado.
- Prepare acepta exclusivamente el preview y crea un hermano `lote-001.prepared-<UTC>-<id>.json` con recibos `documentId/eventId/sourceRevision/outboxStatus`.
- Verify acepta un checkpoint prepared o verified y crea `lote-001.verified-<UTC>-<id>.json`. Conserva los recibos originales y el resultado tecnico completo; se puede verificar de nuevo expresamente.
- Los checkpoints referencian el nombre y SHA-256 del preview hermano. Mantenerlos juntos y sin editar. El hash detecta cambios accidentales; no es una firma ni autentica datos de terceros.
- Si Prepare pierde la respuesta o no puede guardar el checkpoint, repetir con el **mismo preview y motivo**: la API es idempotente. No inventar recibos ni cambiar los UUID. Si falla la escritura puede quedar un fichero parcial; conservarlo como evidencia y utilizar el nuevo nombre que genera el siguiente intento.
- Un `problem` no nulo impide preparar todo el lote: no se omiten documentos silenciosamente. Un lote vacio no se prepara ni verifica. Revisar la causa con el responsable; la herramienta no modifica ni repara datos base.
- `sourceRevision` se conserva como cadena decimal, sin conversion a `double`. Se valida que cada respuesta pertenezca exactamente al lote y al mismo ambito/corte.
- Los errores solo muestran mensajes locales saneados y, cuando procede, el estado HTTP. Un 401/403 exige revisar sesion/permisos; un 404 puede indicar API no habilitada. No se imprimen cuerpos HTTP ni tokens.

Para otro lote, leer `nextAfterId` y `scope.createdBefore` del preview anterior y ejecutar un nuevo Preview con **las mismas empresa, tienda y fechas**, `-AfterId <nextAfterId>`, `-CreatedBefore '<valor exacto UTC>'` y otro `OutputPath`. `AfterId` requiere un corte explicito; no se genera un corte nuevo al continuar. `hasMore=false` indica que no hay siguiente lote. No hay bucle de preparacion ni envio.

## Contrato HTTP utilizado

Base local: `/api/v1/sync/document-recovery`.

| Accion | POST | Cuerpo |
| --- | --- | --- |
| Preview | `/preview` | `{scope:{companyId,storeId,dateFrom,dateTo,createdBefore},afterId}` |
| Prepare | `/prepare` | `{scope:<exacto del preview>,documentIds:[...],reason}` |
| Verify | `/verify` | `{scope:<exacto del preview>,documents:[<recibos preparados>]}` |

Preview guarda el corte asignado, los documentos y el cursor sin redondear fechas/instantes. Prepare exige `status=ENQUEUED`. Verify conserva `complete`, `projected`, `customerLinked` y `status`; no convierte un estado pendiente, ignorado o ausente en exito. No deriva reglas de vinculacion ni realiza cambios para hacer pasar la verificacion.

## Comprobaciones offline, sin API ni base de datos

Analisis de sintaxis compatible con PowerShell 5.1:

```powershell
$scriptPath = (Resolve-Path .\tools\document-sync-recovery.ps1).Path
$tokens = $null
$parseErrors = $null
$null = [Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Error de sintaxis PowerShell' }
```

Para simular el flujo, dot-source carga solo funciones y no pide token ni hace peticiones. El siguiente mock **sustituye el transporte HTTP** y usa una carpeta temporal nueva. No ejecutar una accion real en la misma sesion mientras este mock este definido; cerrar esa sesion al terminar.

```powershell
. .\tools\document-sync-recovery.ps1
$testDirectory = Join-Path ([IO.Path]::GetTempPath()) ('tpv-recovery-mock-' + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $testDirectory
$script:mockRequests = 0
$script:mockDocumentId = '33333333-3333-3333-3333-333333333333'
$script:mockEventId = '44444444-4444-4444-4444-444444444444'
function Invoke-RecoveryRequest($Url, $Token, $Body) {
    $script:mockRequests++
    $scope = $Body.scope
    if ($Url.EndsWith('/preview')) {
        $scope.createdBefore = '2026-09-10T12:34:56.123456789Z'
        return [pscustomobject]@{ scope = $scope; documents = @([pscustomobject]@{
            documentId = $script:mockDocumentId; number = 'T-001'; type = 'TICKET'; status = 'PAGADO';
            date = '2026-09-01'; total = '9007199254740993.01'; currency = 'EUR'; problem = $null
        }); nextAfterId = $null; hasMore = $false }
    }
    $receipt = [pscustomobject]@{ documentId = $script:mockDocumentId; eventId = $script:mockEventId;
        sourceRevision = '9007199254740993'; outboxStatus = 'PENDIENTE' }
    if ($Url.EndsWith('/prepare')) { return [pscustomobject]@{ scope = $scope; documents = @($receipt); status = 'ENQUEUED' } }
    return [pscustomobject]@{ scope = $scope; complete = $true; documents = @([pscustomobject]@{
        documentId = $receipt.documentId; eventId = $receipt.eventId; sourceRevision = $receipt.sourceRevision;
        projected = $true; customerLinked = $null; status = 'PROJECTED'
    }) }
}
$fakeToken = ConvertTo-SecureString 'OFFLINE-MOCK-NOT-A-CREDENTIAL' -AsPlainText -Force
$first = Invoke-DocumentSyncRecovery @{ Action = 'Preview'; CompanyId = '11111111-1111-1111-1111-111111111111';
    StoreId = '22222222-2222-2222-2222-222222222222'; DateFrom = '2026-09-01'; DateTo = '2026-09-10';
    OutputPath = (Join-Path $testDirectory 'lote.json'); BearerToken = $fakeToken }
$hashBefore = Get-RecoveryHash $first.ManifestPath
$second = Invoke-DocumentSyncRecovery @{ Action = 'Prepare'; ManifestPath = $first.ManifestPath; Reason = 'Mock'; BearerToken = $fakeToken }
$third = Invoke-DocumentSyncRecovery @{ Action = 'Verify'; ManifestPath = $second.ManifestPath; BearerToken = $fakeToken }
if ($script:mockRequests -ne 3 -or -not $third.Complete -or (Get-RecoveryHash $first.ManifestPath) -ne $hashBefore) { throw 'Mock incompleto' }
$saved = Read-RecoveryManifest $third.ManifestPath
if ($saved.receipts[0].sourceRevision -cne '9007199254740993' -or $saved.scope.createdBefore -cne '2026-09-10T12:34:56.123456789Z') { throw 'Perdida de precision' }
foreach ($instant in @('2026-09-10T12:34:56.123456Z', '2026-09-10T12:34:56.123456789Z')) {
    $decoded = ConvertFrom-RecoveryJson ('{"instant":"' + $instant + '"}')
    if ($decoded.instant -isnot [string] -or $decoded.instant -cne $instant) { throw 'El JSON ha convertido el corte a DateTime' }
}
$blocked = $false
try { Get-RecoveryBaseUrl 'http://example.invalid' } catch { $blocked = $true }
if (-not $blocked) { throw 'HTTP externo no fue rechazado' }
$script:rejectedChecks = 0
function Assert-RecoveryRejected([scriptblock]$Check) {
    try { $null = & $Check }
    catch {
        if ($_.Exception.Data['RecoverySafeMessage'] -eq $true) { $script:rejectedChecks++; return }
        throw
    }
    throw 'Se esperaba un rechazo seguro'
}
foreach ($url in @('http://example.invalid', 'https://user:secret@example.invalid', 'https://example.invalid/path', 'http://localhost/?token=fake')) {
    Assert-RecoveryRejected { Get-RecoveryBaseUrl $url }
}
Assert-RecoveryRejected { Invoke-DocumentSyncRecovery @{ Action = 'Preview'; OutputPath = $first.ManifestPath; BearerToken = $fakeToken } }
Assert-RecoveryRejected { Invoke-DocumentSyncRecovery @{ Action = 'Prepare'; ManifestPath = $first.ManifestPath;
    BaseUrl = 'https://other.example.invalid'; Reason = 'Mock'; BearerToken = $fakeToken } }
Assert-RecoveryRejected { Invoke-DocumentSyncRecovery @{ Action = 'Preview';
    CompanyId = '11111111-1111-1111-1111-111111111111'; StoreId = '22222222-2222-2222-2222-222222222222';
    DateFrom = '2026-09-01'; DateTo = '2026-09-10'; AfterId = $script:mockDocumentId;
    OutputPath = (Join-Path $testDirectory 'missing-cutoff.json'); BearerToken = $fakeToken } }
foreach ($case in @('empty', 'duplicate', 'oversize', 'problem')) {
    $invalid = Read-RecoveryManifest $first.ManifestPath
    switch ($case) {
        'empty' { $invalid.documents = @() }
        'duplicate' { $invalid.documents = @($invalid.documents[0], $invalid.documents[0]) }
        'oversize' { $invalid.documents = @($invalid.documents[0]) * 101 }
        'problem' { $invalid.documents[0].problem = 'SNAPSHOT_INVALID' }
    }
    $invalidPath = Join-Path $testDirectory ($case + '.json')
    Write-RecoveryManifest $invalidPath $invalid
    Assert-RecoveryRejected { Invoke-DocumentSyncRecovery @{ Action = 'Prepare'; ManifestPath = $invalidPath; Reason = 'Mock'; BearerToken = $fakeToken } }
}
$invalid = Read-RecoveryManifest $second.ManifestPath
$invalid.previewSha256 = 'CHANGED'
$invalidPath = Join-Path $testDirectory 'wrong-hash.json'
Write-RecoveryManifest $invalidPath $invalid
Assert-RecoveryRejected { Invoke-DocumentSyncRecovery @{ Action = 'Verify'; ManifestPath = $invalidPath; BearerToken = $fakeToken } }
$original = Read-RecoveryManifest $first.ManifestPath
$invalid = Read-RecoveryManifest $second.ManifestPath
$invalid.receipts[0].sourceRevision = 1
Assert-RecoveryRejected { Assert-RecoveryReceipts $invalid.receipts $original.documents }
$invalid = Read-RecoveryManifest $third.ManifestPath
$invalid.result.documents[0].eventId = '55555555-5555-5555-5555-555555555555'
Assert-RecoveryRejected { Assert-RecoveryReceipts $invalid.result.documents $saved.receipts -Verified }
if ($script:rejectedChecks -ne 14 -or $script:mockRequests -ne 3) { throw 'Un rechazo realizo una peticion o no se cubrieron los casos' }
$retryPrepared = Invoke-DocumentSyncRecovery @{ Action = 'Prepare'; ManifestPath = $first.ManifestPath; Reason = 'Mock'; BearerToken = $fakeToken }
$retryVerified = Invoke-DocumentSyncRecovery @{ Action = 'Verify'; ManifestPath = $third.ManifestPath; BearerToken = $fakeToken }
if ($script:mockRequests -ne 5 -or $retryPrepared.ManifestPath -eq $second.ManifestPath -or -not $retryVerified.Complete -or
    (Get-RecoveryHash $first.ManifestPath) -ne $hashBefore) { throw 'Reintento o checkpoint no valido' }
'Mock correcto. Evidencia conservada en: ' + $testDirectory
```

Estas comprobaciones no prueban permisos efectivos, migraciones, idempotencia transaccional ni proyeccion real. La validacion de backend/API y la habilitacion puntual corresponden al procedimiento autorizado por separado.

Validacion inicial: sintaxis del script y de los ejemplos comprobada con el parser de Windows PowerShell 5.1; flujo, reintentos explicitos y 14 rechazos comprobados con transporte simulado en PowerShell 7.6. El primer intento HTTP quedo bloqueado antes de ejecutar el fixture. El ensayo posterior se describe a continuacion; no se cambio ninguna politica de ejecucion.

## Ensayo HTTP real aislado (2026-09-11)

Prueba reproducible sin dependencias nuevas, usando el servidor HTTP de Node y el transporte original `HttpWebRequest` de PowerShell:

```powershell
node .\tools\document-sync-recovery.http.test.cjs (Get-Command pwsh).Source
# Opcional, solo cuando la politica del equipo ya permita ejecutar estos archivos:
node .\tools\document-sync-recovery.http.test.cjs (Get-Command powershell).Source
# Si se ha autorizado expresamente RemoteSigned solo para el proceso de prueba:
node .\tools\document-sync-recovery.http.test.cjs --process-remote-signed (Get-Command powershell).Source
```

El runner abre exclusivamente `127.0.0.1` en un puerto efimero, utiliza identidades y credenciales ficticias, y cierra el servidor al terminar o fallar. No acepta una URL de backend de la aplicacion, no consulta BD, no conecta a SaaS ni modifica politicas persistentes. Sin la opcion expresa conserva la politica existente; `--process-remote-signed` pasa `-ExecutionPolicy RemoteSigned` solo al proceso hijo y requiere autorizacion previa. No utiliza `Bypass` ni `Set-ExecutionPolicy`. Cada hijo descubre sus propios modulos estandar sin heredar `PSModulePath` de otra version; el entorno padre no se modifica. El cliente de prueba rechaza los puertos de aplicacion 8080/8088/8090; sus manifiestos sinteticos se crean en una carpeta temporal exclusiva y se eliminan al finalizar.

- **PowerShell 7.6.5: aprobado.** Diecinueve peticiones HTTP reales contra el fixture: cinco del flujo correcto/reintentos expresos y catorce variantes controladas. Se comprueban metodo, ruta, cabeceras, cuerpo, ambito, recibos, estados pendientes y cliente sin vinculo; importes y revisiones superiores a 2^53 y corte UTC con nanosegundos permanecen exactos. Se verifica texto UTF-8 y ausencia de credenciales/cuerpos de error en mensajes y manifiestos.
- Rechazos probados: HTTP 401/403/404/500, redireccion 302 sin seguirla, JSON invalido, mas de 4 MiB con longitud declarada y con transferencia chunked, desconexion, ambito incorrecto, revision numerica y recibo con evento ajeno. El servidor confirma el numero exacto de peticiones; no hubo reintentos automaticos ni redirecciones.
- **Windows PowerShell 5.1.26100.9444: aprobado tras autorizacion expresa.** El primer intento sin opcion rechazo el archivo con `UnauthorizedAccess`. El usuario autorizo despues `RemoteSigned` solo para el proceso de prueba. Se corrigio en el runner la herencia de rutas de modulos de PowerShell 7 que impedia cargar `Microsoft.PowerShell.Security` en 5.1; el script operativo no cambio. El ensayo completo paso las mismas 19 peticiones. Se repitio 7.6.5 sin opcion de politica para comprobar ese ajuste compartido, tambien con resultado correcto.
- Politicas comprobadas antes y despues: Windows PowerShell 5.1 mantiene los cinco ambitos `Undefined`; PowerShell 7.6 mantiene `LocalMachine=RemoteSigned` y los demas `Undefined`. La opcion autorizada termino con el proceso hijo. No se hicieron cambios permanentes ni se instalaron modulos.

Este ensayo valida el transporte del script contra respuestas sinteticas; no demuestra por si solo permisos, transacciones o recepcion en SaaS. La carga real de los 206 documentos realizada desde APP VENTA se documenta por separado en [document-sync-oneoff-recovery.md](../docs/document-sync-oneoff-recovery.md).
