#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateSet('Preview', 'Prepare', 'Verify')][string]$Action,
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [System.Security.SecureString]$BearerToken,
    [string]$CompanyId,
    [string]$StoreId,
    [string]$DateFrom,
    [string]$DateTo,
    [string]$CreatedBefore,
    [string]$AfterId,
    [string]$OutputPath,
    [string]$ManifestPath,
    [string]$Reason
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Stop-Recovery([string]$Message) {
    $failure = [InvalidOperationException]::new($Message)
    $failure.Data['RecoverySafeMessage'] = $true
    throw $failure
}

function Get-RecoveryField($Value, [string]$Name) {
    if ($null -eq $Value -or $null -eq $Value.PSObject.Properties[$Name]) {
        Stop-Recovery 'Manifiesto o respuesta incompatible: falta un campo obligatorio.'
    }
    return $Value.$Name
}

function Assert-RecoveryUuid($Value) {
    $parsed = [Guid]::Empty
    if ($Value -isnot [string] -or -not [Guid]::TryParseExact($Value, 'D', [ref]$parsed)) {
        Stop-Recovery 'Identificador UUID no valido.'
    }
    return $parsed.ToString('D')
}

function Assert-RecoveryDate($Value) {
    $parsed = [DateTime]::MinValue
    if ($Value -isnot [string] -or -not [DateTime]::TryParseExact($Value, 'yyyy-MM-dd',
        [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::None, [ref]$parsed)) {
        Stop-Recovery 'Fecha no valida: utilice YYYY-MM-DD.'
    }
    return $Value
}

function Assert-RecoveryCutoff($Value) {
    # Keep nanoseconds and spelling exactly as returned by the backend; never round a cursor cutoff.
    $parsed = [DateTimeOffset]::MinValue
    if ($Value -isnot [string] -or $Value -notmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$' -or
        -not [DateTimeOffset]::TryParseExact($Value.Substring(0, 19) + 'Z', "yyyy-MM-dd'T'HH:mm:ss'Z'",
        [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AssumeUniversal, [ref]$parsed)) {
        Stop-Recovery 'Corte no valido: utilice el createdBefore UTC exacto de la vista previa.'
    }
    return $Value
}

function Get-RecoveryScope($Value, [switch]$AllowMissingCutoff) {
    $scope = [ordered]@{
        companyId = Assert-RecoveryUuid (Get-RecoveryField $Value 'companyId')
        storeId = Assert-RecoveryUuid (Get-RecoveryField $Value 'storeId')
        dateFrom = Assert-RecoveryDate (Get-RecoveryField $Value 'dateFrom')
        dateTo = Assert-RecoveryDate (Get-RecoveryField $Value 'dateTo')
        createdBefore = $null
    }
    if ($scope.dateFrom -gt $scope.dateTo) { Stop-Recovery 'El intervalo de fechas esta invertido.' }
    $cutoff = Get-RecoveryField $Value 'createdBefore'
    if ($null -ne $cutoff) { $scope.createdBefore = Assert-RecoveryCutoff $cutoff }
    elseif (-not $AllowMissingCutoff) { Stop-Recovery 'La vista previa no contiene el corte asignado por el servidor.' }
    return [pscustomobject]$scope
}

function Assert-RecoveryScope($Actual, $Expected, [switch]$AssignCutoff) {
    $scope = Get-RecoveryScope $Actual
    foreach ($field in @('companyId', 'storeId', 'dateFrom', 'dateTo', 'createdBefore')) {
        if ($field -eq 'createdBefore' -and $AssignCutoff -and $null -eq $Expected.createdBefore) { continue }
        if ($scope.$field -cne $Expected.$field) { Stop-Recovery 'La respuesta no corresponde al ambito y corte solicitados.' }
    }
    return $scope
}

function Get-RecoveryBaseUrl([string]$Value) {
    $uri = $null
    if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or
        $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/' -or
        ($uri.Scheme -ne 'https' -and -not ($uri.Scheme -eq 'http' -and $uri.IsLoopback))) {
        Stop-Recovery 'BaseUrl debe ser un origen HTTPS o HTTP de loopback, sin credenciales, ruta ni parametros.'
    }
    return $uri.GetLeftPart([UriPartial]::Authority)
}

function ConvertFrom-RecoveryJson([string]$Json) {
    $arguments = @{ InputObject = $Json }
    # Windows PowerShell 5.1 keeps ISO strings; recent PowerShell needs an explicit opt-out.
    if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) { $arguments.DateKind = 'String' }
    return ConvertFrom-Json @arguments
}

function Invoke-RecoveryRequest([string]$Url, [System.Security.SecureString]$Token, $Body) {
    $secretPointer = [IntPtr]::Zero
    $plainToken = $null
    $request = $null
    $response = $null
    $responseStream = $null
    $buffered = $null
    try {
        if ($null -eq $Token -or $Token.Length -eq 0) { Stop-Recovery 'Es necesario el token local ADMIN.' }
        $secretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Token)
        $plainToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($secretPointer)
        if ($plainToken -match '[\r\n]') { Stop-Recovery 'Token local no valido.' }
        $request = [Net.HttpWebRequest]::Create($Url)
        $request.Method = 'POST'
        $request.AllowAutoRedirect = $false
        $request.Timeout = 120000
        $request.ReadWriteTimeout = 120000
        $request.MaximumResponseHeadersLength = 32
        if ($request.RequestUri.IsLoopback) { $request.Proxy = $null }
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Accept = 'application/json'
        $request.Headers['Authorization'] = 'Bearer ' + $plainToken
        $bytes = [Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $Body -Depth 20 -Compress))
        $request.ContentLength = $bytes.Length
        $requestStream = $request.GetRequestStream()
        try { $requestStream.Write($bytes, 0, $bytes.Length) } finally { $requestStream.Dispose() }
        try { $response = $request.GetResponse() }
        catch [Net.WebException] {
            if ($null -ne $_.Exception.Response) {
                $status = [int]$_.Exception.Response.StatusCode
                $_.Exception.Response.Close()
                Stop-Recovery ('API local rechazada (HTTP {0}). Compruebe ADMIN, perfil dev, habilitacion manual y ambito.' -f $status)
            }
            Stop-Recovery 'No se obtuvo respuesta de la API local. No se realizaron reintentos automaticos.'
        }
        $status = [int]$response.StatusCode
        if ($status -lt 200 -or $status -ge 300) { Stop-Recovery 'Respuesta HTTP no admitida; no se siguen redirecciones.' }
        if ($response.ContentLength -gt 4194304) { Stop-Recovery 'La respuesta supera el limite de 4 MiB.' }
        $responseStream = $response.GetResponseStream()
        $buffered = [IO.MemoryStream]::new()
        $chunk = New-Object byte[] 8192
        while (($read = $responseStream.Read($chunk, 0, $chunk.Length)) -gt 0) {
            if ($buffered.Length + $read -gt 4194304) { Stop-Recovery 'La respuesta supera el limite de 4 MiB.' }
            $buffered.Write($chunk, 0, $read)
        }
        try { return ConvertFrom-RecoveryJson ([Text.Encoding]::UTF8.GetString($buffered.ToArray())) }
        catch { Stop-Recovery 'La API no devolvio JSON compatible. No se muestra el cuerpo de la respuesta.' }
    }
    finally {
        if ($null -ne $responseStream) { $responseStream.Dispose() }
        if ($null -ne $buffered) { $buffered.Dispose() }
        if ($null -ne $response) { $response.Close() }
        if ($null -ne $request) { $request.Abort() }
        $plainToken = $null
        if ($secretPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secretPointer) }
    }
}

function Read-RecoveryManifest([string]$Path) {
    $resolved = [IO.Path]::GetFullPath($Path)
    if (-not [IO.File]::Exists($resolved) -or ([IO.FileInfo]$resolved).Length -gt 4194304) {
        Stop-Recovery 'Manifiesto inexistente o mayor de 4 MiB.'
    }
    try { $value = ConvertFrom-RecoveryJson ([IO.File]::ReadAllText($resolved, [Text.Encoding]::UTF8)) }
    catch { Stop-Recovery 'El manifiesto no contiene JSON valido.' }
    if ((Get-RecoveryField $value 'schemaVersion') -ne 1) { Stop-Recovery 'Version de manifiesto no compatible.' }
    return $value
}

function Get-RecoveryHash([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

function Assert-RecoveryDocuments($Documents, [switch]$Preview) {
    if ($null -eq $Documents -or $Documents -isnot [Array] -or $Documents.Count -gt 100) {
        Stop-Recovery 'El lote debe ser una lista de hasta 100 documentos.'
    }
    $seen = @{}
    foreach ($document in $Documents) {
        $id = Assert-RecoveryUuid (Get-RecoveryField $document 'documentId')
        if ($seen.ContainsKey($id)) { Stop-Recovery 'El lote contiene documentos duplicados.' }
        $seen[$id] = $true
        if ($Preview) {
            $problem = Get-RecoveryField $document 'problem'
            if ($null -ne $problem -and $problem -isnot [string]) { Stop-Recovery 'Problema de documento incompatible.' }
            $total = Get-RecoveryField $document 'total'
            if ($total -isnot [string] -or $total -cnotmatch '^-?[0-9]+(?:\.[0-9]+)?$') {
                Stop-Recovery 'El importe debe ser una cadena decimal exacta, no un numero JSON.'
            }
            $currency = Get-RecoveryField $document 'currency'
            if ($currency -isnot [string] -or $currency -cnotmatch '^[A-Z]{3}$') { Stop-Recovery 'Moneda de documento incompatible.' }
            $null = Assert-RecoveryDate (Get-RecoveryField $document 'date')
        }
    }
}

function Assert-RecoveryReceipts($Documents, $Expected, [switch]$Verified) {
    Assert-RecoveryDocuments $Documents
    if ($Documents.Count -ne $Expected.Count) { Stop-Recovery 'La respuesta no contiene exactamente el lote solicitado.' }
    $expectedById = @{}
    foreach ($document in $Expected) { $expectedById[$document.documentId] = $document }
    foreach ($document in $Documents) {
        if (-not $expectedById.ContainsKey($document.documentId)) { Stop-Recovery 'La respuesta contiene un documento ajeno al lote.' }
        $null = Assert-RecoveryUuid (Get-RecoveryField $document 'eventId')
        $revision = Get-RecoveryField $document 'sourceRevision'
        $parsedRevision = [long]0
        if ($revision -isnot [string] -or $revision -cnotmatch '^[1-9][0-9]{0,18}$' -or
            -not [long]::TryParse($revision, [ref]$parsedRevision)) {
            Stop-Recovery 'La revision debe ser una cadena decimal exacta, no un numero JSON.'
        }
        if ($Verified) {
            $prior = $expectedById[$document.documentId]
            if ($document.eventId -cne $prior.eventId -or $revision -cne $prior.sourceRevision -or
                (Get-RecoveryField $document 'projected') -isnot [bool]) { Stop-Recovery 'El resultado no corresponde al recibo preparado.' }
            $linked = Get-RecoveryField $document 'customerLinked'
            if ($null -ne $linked -and $linked -isnot [bool]) { Stop-Recovery 'Estado de vinculacion incompatible.' }
            $state = Get-RecoveryField $document 'status'
        }
        else { $state = Get-RecoveryField $document 'outboxStatus' }
        if ($state -isnot [string] -or $state -cnotmatch '^[A-Z][A-Z_0-9]{0,39}$') { Stop-Recovery 'Estado tecnico incompatible.' }
    }
}

function Write-RecoveryManifest([string]$Path, $Value) {
    # CreateNew is also the race-safe guard: this tool never overwrites any existing file.
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes((ConvertTo-Json -InputObject $Value -Depth 30))
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $stream.Write($bytes, 0, $bytes.Length); $stream.Flush() } finally { $stream.Dispose() }
}

function Invoke-DocumentSyncRecovery([hashtable]$Options) {
    $Options = $Options.Clone()
    foreach ($name in @('Action', 'BaseUrl', 'BearerToken', 'CompanyId', 'StoreId', 'DateFrom', 'DateTo',
        'CreatedBefore', 'AfterId', 'OutputPath', 'ManifestPath', 'Reason')) {
        if (-not $Options.ContainsKey($name)) { $Options[$name] = $null }
    }
    $action = [string]$Options.Action
    if ($action -notin @('Preview', 'Prepare', 'Verify')) { Stop-Recovery 'Indique -Action Preview, Prepare o Verify.' }
    $base = if ($Options.BaseUrl) { Get-RecoveryBaseUrl $Options.BaseUrl } else { 'http://127.0.0.1:8080' }
    $token = $Options.BearerToken
    $createdAt = [DateTime]::UtcNow.ToString('o')
    if ($action -eq 'Preview') {
        if (-not $Options.OutputPath -or $Options.ManifestPath -or $Options.Reason) { Stop-Recovery 'Preview requiere OutputPath nuevo y no admite ManifestPath ni Reason.' }
        $output = [IO.Path]::GetFullPath($Options.OutputPath)
        if ([IO.File]::Exists($output) -or [IO.Directory]::Exists($output) -or -not [IO.Directory]::Exists([IO.Path]::GetDirectoryName($output))) {
            Stop-Recovery 'La ruta de salida ya existe o su carpeta no existe. Elija una ruta nueva.'
        }
        $scope = Get-RecoveryScope ([pscustomobject]@{ companyId = $Options.CompanyId; storeId = $Options.StoreId;
            dateFrom = $Options.DateFrom; dateTo = $Options.DateTo; createdBefore = $(if ($Options.CreatedBefore) { $Options.CreatedBefore } else { $null }) }) -AllowMissingCutoff
        $after = if ($Options.AfterId) { Assert-RecoveryUuid $Options.AfterId } else { $null }
        if ($after -and -not $scope.createdBefore) { Stop-Recovery 'AfterId requiere el CreatedBefore exacto del lote anterior.' }
        if ($null -eq $token) { $token = Read-Host 'Token bearer LOCAL de una sesion ADMIN (no token SaaS)' -AsSecureString }
        $result = Invoke-RecoveryRequest ($base + '/api/v1/sync/document-recovery/preview') $token @{ scope = $scope; afterId = $after }
        $scope = Assert-RecoveryScope (Get-RecoveryField $result 'scope') $scope -AssignCutoff
        $null = Get-RecoveryField $result 'documents'
        # Get-RecoveryField enumerates arrays at the pipeline boundary: recover the actual JSON array.
        $documents = $result.documents
        Assert-RecoveryDocuments $documents -Preview
        $more = Get-RecoveryField $result 'hasMore'
        $next = Get-RecoveryField $result 'nextAfterId'
        if ($more -isnot [bool] -or ($more -and $null -eq $next)) { Stop-Recovery 'Paginacion incompatible.' }
        if ($null -ne $next) { $null = Assert-RecoveryUuid $next }
        $manifest = [ordered]@{ schemaVersion = 1; kind = 'preview'; createdAt = $createdAt; baseUrl = $base;
            scope = $scope; afterId = $after; documents = $documents; nextAfterId = $next; hasMore = $more }
        Write-RecoveryManifest $output $manifest
        return [pscustomobject]@{ Action = $action; ManifestPath = $output; DocumentCount = $documents.Count; HasMore = $more }
    }

    if (-not $Options.ManifestPath -or $Options.OutputPath -or $Options.CompanyId -or $Options.StoreId -or
        $Options.DateFrom -or $Options.DateTo -or $Options.CreatedBefore -or $Options.AfterId) {
        Stop-Recovery 'Prepare/Verify requieren solo ManifestPath; no se puede sustituir el ambito del manifiesto.'
    }
    $inputPath = [IO.Path]::GetFullPath($Options.ManifestPath)
    $source = Read-RecoveryManifest $inputPath
    $kind = Get-RecoveryField $source 'kind'
    $directory = [IO.Path]::GetDirectoryName($inputPath)
    $previewPath = $inputPath
    if ($action -eq 'Prepare') {
        if ($kind -ne 'preview') { Stop-Recovery 'Prepare requiere el manifiesto preview original.' }
        $reason = [string]$Options.Reason
        if ([string]::IsNullOrWhiteSpace($reason) -or $reason.Trim().Length -gt 500) { Stop-Recovery 'Prepare requiere un motivo de 1 a 500 caracteres.' }
        $preview = $source
    }
    else {
        if ($Options.Reason -or $kind -notin @('prepared', 'verified')) { Stop-Recovery 'Verify requiere un checkpoint prepared/verified y no admite Reason.' }
        $previewName = Get-RecoveryField $source 'previewManifestFile'
        if ($previewName -isnot [string] -or [IO.Path]::GetFileName($previewName) -cne $previewName -or $previewName -in @('', '.', '..')) {
            Stop-Recovery 'La referencia al preview debe ser un archivo hermano.'
        }
        $previewPath = Join-Path $directory $previewName
        if ((Get-RecoveryHash $previewPath) -cne (Get-RecoveryField $source 'previewSha256')) { Stop-Recovery 'El preview original ha cambiado; no se continua.' }
        $preview = Read-RecoveryManifest $previewPath
        if ((Get-RecoveryField $preview 'kind') -ne 'preview') { Stop-Recovery 'Referencia preview incompatible.' }
    }
    if ((Get-RecoveryBaseUrl (Get-RecoveryField $preview 'baseUrl')) -cne $base -or
        (Get-RecoveryBaseUrl (Get-RecoveryField $source 'baseUrl')) -cne $base) { Stop-Recovery 'BaseUrl no coincide con el manifiesto; especifique el mismo origen.' }
    $scope = Get-RecoveryScope (Get-RecoveryField $preview 'scope')
    $null = Assert-RecoveryScope (Get-RecoveryField $source 'scope') $scope
    $null = Get-RecoveryField $preview 'documents'
    $documents = $preview.documents
    Assert-RecoveryDocuments $documents -Preview
    if ($documents.Count -eq 0) { Stop-Recovery 'El lote esta vacio; no hay nada que preparar o verificar.' }
    if (@($documents | Where-Object { $null -ne $_.problem }).Count -gt 0) { Stop-Recovery 'El preview contiene problemas. Reviselos; no se prepara parcialmente el lote.' }
    $digest = Get-RecoveryHash $previewPath
    if ($action -eq 'Prepare') { $body = @{ scope = $scope; documentIds = @($documents | ForEach-Object { $_.documentId }); reason = $reason.Trim() } }
    else {
        $null = Get-RecoveryField $source 'receipts'
        $receipts = $source.receipts
        Assert-RecoveryReceipts $receipts $documents
        $body = @{ scope = $scope; documents = $receipts }
    }
    $checkpointKind = if ($action -eq 'Prepare') { 'prepared' } else { 'verified' }
    $output = Join-Path $directory ('{0}.{1}-{2}-{3}.json' -f [IO.Path]::GetFileNameWithoutExtension($previewPath),
        $checkpointKind, [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ'), [Guid]::NewGuid().ToString('N'))
    if ($null -eq $token) { $token = Read-Host 'Token bearer LOCAL de una sesion ADMIN (no token SaaS)' -AsSecureString }
    $result = Invoke-RecoveryRequest ($base + '/api/v1/sync/document-recovery/' + $action.ToLowerInvariant()) $token $body
    $null = Assert-RecoveryScope (Get-RecoveryField $result 'scope') $scope
    $null = Get-RecoveryField $result 'documents'
    if ($action -eq 'Prepare') {
        if ((Get-RecoveryField $result 'status') -cne 'ENQUEUED') { Stop-Recovery 'La preparacion no confirma ENQUEUED.' }
        Assert-RecoveryReceipts $result.documents $documents
        $receipts = $result.documents
    }
    else {
        Assert-RecoveryReceipts $result.documents $receipts -Verified
        if ((Get-RecoveryField $result 'complete') -isnot [bool]) { Stop-Recovery 'Verificacion incompatible.' }
    }
    $manifest = [ordered]@{ schemaVersion = 1; kind = $checkpointKind; createdAt = $createdAt; baseUrl = $base;
        previewManifestFile = [IO.Path]::GetFileName($previewPath); previewSha256 = $digest;
        scope = $scope; receipts = $receipts; result = $result }
    if ($action -eq 'Prepare') { $manifest.reason = $reason.Trim() }
    Write-RecoveryManifest $output $manifest
    return [pscustomobject]@{ Action = $action; ManifestPath = $output; DocumentCount = $documents.Count;
        Complete = $(if ($action -eq 'Verify') { $result.complete } else { $null }) }
}

# Dot-sourcing exposes the pure validators/orchestrator for offline mocks; it performs no action.
if ($MyInvocation.InvocationName -eq '.') { return }
try { Invoke-DocumentSyncRecovery $PSBoundParameters }
catch {
    # Never print ErrorRecord/InnerException: HTTP libraries can include URLs, headers or response bodies.
    $message = 'No se pudo completar o guardar el resultado. No se reintento ni envio automaticamente; Prepare permite reintentar con el mismo preview.'
    if ($_.Exception.Data['RecoverySafeMessage'] -eq $true) { $message = $_.Exception.Message }
    Write-Error -Message $message -ErrorAction Continue
    exit 1
}
