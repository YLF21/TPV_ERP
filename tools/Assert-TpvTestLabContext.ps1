[CmdletBinding()]
param(
    [string] $ConfigurationFile,
    [string] $InstalledServiceXml,
    [switch] $ApplyCliEnvironment
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'TEST_ONLY requiere Windows dentro de VirtualBox.'
}
$computer = Get-CimInstance -ClassName Win32_ComputerSystem -ErrorAction Stop
if ([string]$computer.Model -notmatch '(?i)^VirtualBox(?:\s|$)' -or
    [string]$computer.Manufacturer -notmatch '(?i)^(innotek GmbH|Oracle Corporation)$') {
    throw 'TEST_ONLY solo puede instalarse o mantenerse dentro de una VM VirtualBox.'
}
if ($InstalledServiceXml) {
    $xmlPath = (Resolve-Path -LiteralPath $InstalledServiceXml -ErrorAction Stop).Path
    if ((Get-Item -LiteralPath $xmlPath -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'El XML del servicio no puede ser un enlace.'
    }
    $settings = [Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $settings.MaxCharactersInDocument = 65536
    $reader = [Xml.XmlReader]::Create($xmlPath, $settings)
    try { $document = [Xml.XmlDocument]::new(); $document.XmlResolver = $null; $document.Load($reader) }
    finally { $reader.Dispose() }
    if ([string]$document.service.id -cne 'TPVERPBackend') { throw 'El servicio instalado no es TPVERPBackend.' }
    $arguments = [string]$document.service.arguments
    if ([Regex]::Matches($arguments, '(?i)(?:^|\s)--spring\.profiles\.active=lab-bundle(?=\s|$)').Count -ne 1 -or
        $arguments -match '(?i)(?:^|\s)--spring\.profiles\.active=prod(?=\s|$)') {
        throw 'El servicio TEST_ONLY debe usar exclusivamente el perfil lab-bundle.'
    }
    $location = [Regex]::Matches($arguments,
        '(?i)(?:^|\s)"--spring\.config\.additional-location=(file:/[^"\s]+)"(?=\s|$)')
    if ($location.Count -ne 1) { throw 'Falta la configuracion externa file: del servicio TEST_ONLY.' }
    $uri = [Uri]::new($location[0].Groups[1].Value)
    if (-not $uri.IsFile) { throw 'La configuracion TEST_ONLY no usa una ruta local.' }
    $ConfigurationFile = $uri.LocalPath
}
if ([string]::IsNullOrWhiteSpace($ConfigurationFile)) { throw 'Falta ConfigurationFile del laboratorio.' }
$config = (Resolve-Path -LiteralPath $ConfigurationFile -ErrorAction Stop).Path
if ([IO.Path]::GetExtension($config) -cne '.properties') {
    throw 'La configuracion TEST_ONLY debe ser un fichero .properties explicito.'
}
$cursor = $config
while ($true) {
    $item = Get-Item -LiteralPath $cursor -Force
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'La configuracion TEST_ONLY contiene un enlace.' }
    $parent = [IO.Path]::GetDirectoryName($cursor)
    if (-not $parent -or $parent -eq $cursor) { break }
    $cursor = $parent
}
if ((Get-Item -LiteralPath $config).Length -gt 64KB) { throw 'La configuracion TEST_ONLY supera 64 KiB.' }
$values = @{}
foreach ($line in Get-Content -LiteralPath $config -Encoding UTF8) {
    if ($line -match '^\s*(?:#|!|$)') { continue }
    if ($line -notmatch '^\s*([A-Za-z0-9._-]+)\s*=\s*(.*?)\s*$') {
        throw 'La configuracion TEST_ONLY contiene una linea no verificable.'
    }
    $name = $matches[1]
    if ($values.ContainsKey($name)) { throw "Clave duplicada en configuracion TEST_ONLY: $name" }
    if ($matches[2].Contains('\')) {
        throw 'TEST_ONLY exige valores .properties literales sin escapes; use barras / en rutas.'
    }
    $values[$name] = $matches[2]
}
foreach ($name in $values.Keys) {
    $normalized = [Regex]::Replace($name, '[^A-Za-z0-9]', '').ToLowerInvariant()
    if (($normalized.StartsWith('springdatasource') -and $normalized -cnotin @(
            'springdatasourceurl','springdatasourceusername','springdatasourcepassword',
            'springdatasourcedriverclassname')) -or
        ($normalized.StartsWith('springflyway') -and $normalized -cne 'springflywayenabled') -or
        $normalized.StartsWith('springjpaproperties')) {
        throw "TEST_ONLY rechaza una configuracion alternativa de datos: $name"
    }
}
if ($values.ContainsKey('spring.datasource.driver-class-name') -and
    $values['spring.datasource.driver-class-name'] -cne 'org.postgresql.Driver') {
    throw 'TEST_ONLY exige el driver PostgreSQL.'
}
if ($values.ContainsKey('spring.sql.init.mode') -and $values['spring.sql.init.mode'] -cne 'never') {
    throw 'TEST_ONLY exige spring.sql.init.mode=never.'
}
foreach ($name in @('tpv.verifactu.dev-signing-pkcs12', 'tpv.verifactu.dev-signing-password')) {
    if ($values.ContainsKey($name) -and -not [string]::IsNullOrWhiteSpace([string]$values[$name])) {
        throw 'TEST_ONLY no acepta material de firma fiscal en el laboratorio.'
    }
}
$required = @{
    'tpv.test-bundle.lab-context' = 'VIRTUALBOX_ONLY'
    'tpv.verifactu.runtime-class' = 'SANDBOX'
    'tpv.verifactu.endpoint-environment' = 'TEST'
    'tpv.verifactu.endpoint-mode' = 'TEST'
    'tpv.verifactu.transport-mode' = 'SIMULATED'
    'tpv.verifactu.dev-sandbox.enabled' = 'true'
    'tpv.verifactu.production-enabled' = 'false'
    'tpv.verifactu.worker-enabled' = 'false'
    'tpv.verifactu.aeat-test-network-enabled' = 'false'
    'tpv.sync.worker-enabled' = 'false'
}
foreach ($name in $required.Keys) {
    if (-not $values.ContainsKey($name) -or $values[$name] -cne $required[$name]) {
        throw "Configuracion TEST_ONLY incorrecta: $name"
    }
}
$dbUrl = [string]$values['spring.datasource.url']
if ($dbUrl -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1|\[::1\]):[0-9]{1,5}/lab_[A-Za-z0-9_]+$') {
    throw 'La base de datos TEST_ONLY debe ser PostgreSQL local de la VM.'
}
$dbPort = [Regex]::Match($dbUrl, '^jdbc:postgresql://(?:localhost|127\.0\.0\.1|\[::1\]):([0-9]{1,5})/')
if (-not $dbPort.Success -or [int]$dbPort.Groups[1].Value -lt 1 -or
    [int]$dbPort.Groups[1].Value -gt 65535) {
    throw 'El puerto PostgreSQL TEST_ONLY esta fuera de rango.'
}
function Test-PrivateLabHost([string] $HostName) {
    if ($HostName -ceq 'localhost') { return $true }
    $ip = $null
    if (-not [Net.IPAddress]::TryParse($HostName, [ref]$ip)) { return $false }
    if ([Net.IPAddress]::IsLoopback($ip)) { return $true }
    if ($HostName -notmatch '^[0-9]{1,3}(?:\.[0-9]{1,3}){3}$' -or
        @($HostName.Split('.') | Where-Object { $_.Length -gt 1 -and $_.StartsWith('0') }).Count -gt 0) {
        return $false
    }
    if ($ip.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { return $false }
    $octets = $ip.GetAddressBytes()
    return ($octets[0] -eq 10) -or ($octets[0] -eq 172 -and $octets[1] -ge 16 -and $octets[1] -le 31) -or
        ($octets[0] -eq 192 -and $octets[1] -eq 168)
}
foreach ($name in @('tpv.license.saas-url', 'tpv.sync.central-url')) {
    $url = [string]$values[$name]
    $uri = $null
    if (-not [Uri]::TryCreate($url, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -cne 'https' -or -not (Test-PrivateLabHost $uri.Host) -or
        $uri.UserInfo -or $uri.Query -or $uri.Fragment) {
        throw "$name debe apuntar al SaaS HTTPS de una VM en la red privada del laboratorio."
    }
}
$aclProperties = [ordered]@{
    KeyRoot = 'tpv.installation.key-directory'
    BackupRoot = 'tpv.backup.default-directory'
    ProductImagesRoot = 'tpv.product-images.directory'
    DocumentTemplatesRoot = 'tpv.document-templates.directory'
    FiscalSecretRoot = 'tpv.verifactu.secret-directory'
    FiscalExportRoot = 'tpv.verifactu.export-job-directory'
}
$aclPaths = @{}
foreach ($entry in $aclProperties.GetEnumerator()) {
    $value = [string]$values[$entry.Value]
    if ([string]::IsNullOrWhiteSpace($value) -or -not [IO.Path]::IsPathRooted($value)) {
        throw "Falta una ruta absoluta de laboratorio para $($entry.Value)."
    }
    $full = [IO.Path]::GetFullPath($value)
    if ($full -eq [IO.Path]::GetPathRoot($full)) { throw "Ruta ACL de unidad no permitida: $($entry.Value)" }
    $aclPaths[$entry.Key] = $full
}
$journal = [string]$values['tpv.backup.restore-journal-path']
if ([string]::IsNullOrWhiteSpace($journal) -or -not [IO.Path]::IsPathRooted($journal)) {
    throw 'Falta tpv.backup.restore-journal-path absoluto.'
}
$aclPaths.RestoreJournalRoot = [IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($journal))
if ($ApplyCliEnvironment) {
    if (-not $InstalledServiceXml) { throw 'La configuracion CLI TEST_ONLY debe proceder del servicio instalado.' }
    $map = [ordered]@{
        'TPV_DB_URL' = 'spring.datasource.url'
        'TPV_DB_USERNAME' = 'spring.datasource.username'
        'TPV_DB_PASSWORD' = 'spring.datasource.password'
        'PGPASSWORD' = 'spring.datasource.password'
        'TPV_PG_DUMP_COMMAND' = 'tpv.backup.pg-dump-command'
        'TPV_PG_RESTORE_COMMAND' = 'tpv.backup.pg-restore-command'
        'TPV_LICENSE_SAAS_URL' = 'tpv.license.saas-url'
        'TPV_SYNC_CENTRAL_URL' = 'tpv.sync.central-url'
        'TPV_KEY_DIRECTORY' = 'tpv.installation.key-directory'
        'TPV_BACKUP_DIRECTORY' = 'tpv.backup.default-directory'
        'TPV_PRODUCT_IMAGE_DIRECTORY' = 'tpv.product-images.directory'
        'TPV_DOCUMENT_TEMPLATE_DIRECTORY' = 'tpv.document-templates.directory'
        'TPV_VERIFACTU_SECRET_DIRECTORY' = 'tpv.verifactu.secret-directory'
        'TPV_VERIFACTU_EXPORT_JOB_DIRECTORY' = 'tpv.verifactu.export-job-directory'
        'TPV_BACKUP_RESTORE_JOURNAL_PATH' = 'tpv.backup.restore-journal-path'
        'TPV_VERIFACTU_RUNTIME_CLASS' = 'tpv.verifactu.runtime-class'
        'TPV_VERIFACTU_ENDPOINT_ENVIRONMENT' = 'tpv.verifactu.endpoint-environment'
        'TPV_VERIFACTU_ENDPOINT_MODE' = 'tpv.verifactu.endpoint-mode'
        'TPV_VERIFACTU_TRANSPORT_MODE' = 'tpv.verifactu.transport-mode'
        'TPV_VERIFACTU_DEV_SANDBOX_ENABLED' = 'tpv.verifactu.dev-sandbox.enabled'
        'TPV_VERIFACTU_PRODUCTION_ENABLED' = 'tpv.verifactu.production-enabled'
        'TPV_VERIFACTU_WORKER_ENABLED' = 'tpv.verifactu.worker-enabled'
        'TPV_VERIFACTU_AEAT_TEST_NETWORK_ENABLED' = 'tpv.verifactu.aeat-test-network-enabled'
        'TPV_SYNC_WORKER_ENABLED' = 'tpv.sync.worker-enabled'
        'TPV_TRANSFER_TLS_REPROVISIONED' = 'tpv.test-bundle.tls-reprovisioned'
    }
    if (-not $values.ContainsKey('tpv.test-bundle.tls-reprovisioned') -or
        $values['tpv.test-bundle.tls-reprovisioned'] -cnotin @('true', 'false')) {
        throw 'Falta tpv.test-bundle.tls-reprovisioned=true/false en el laboratorio.'
    }
    foreach ($propertyName in $map.Values) {
        $value = [string]$values[$propertyName]
        if ([string]::IsNullOrWhiteSpace($value) -or $value -match '\$\{|@[^@]+@|^<.*>$') {
            throw "Falta un valor concreto de $propertyName en la configuracion instalada."
        }
    }
    foreach ($propertyName in @('tpv.installation.key-directory','tpv.backup.default-directory',
            'tpv.product-images.directory','tpv.document-templates.directory','tpv.verifactu.secret-directory',
            'tpv.backup.restore-journal-path','tpv.backup.pg-dump-command',
            'tpv.backup.pg-restore-command')) {
        $path = [string]$values[$propertyName]
        if (-not [IO.Path]::IsPathRooted($path) -or [IO.Path]::GetPathRoot($path) -eq [IO.Path]::GetFullPath($path)) {
            throw "$propertyName debe ser una ruta absoluta de la VM."
        }
    }
    $httpsRoot = Join-Path $env:ProgramData 'TPV ERP\HTTPS'
    $httpsXmlPath = Join-Path $httpsRoot 'TPVERPHTTPS.xml'
    if (-not (Test-Path -LiteralPath $httpsXmlPath -PathType Leaf)) {
        throw 'Falta el servicio HTTPS instalado del laboratorio.'
    }
    $httpsReader = [Xml.XmlReader]::Create($httpsXmlPath, $settings)
    try { $https = [Xml.XmlDocument]::new(); $https.XmlResolver = $null; $https.Load($httpsReader) }
    finally { $httpsReader.Dispose() }
    $httpsService = Get-CimInstance -ClassName Win32_Service -Filter "Name = 'TPVERPHTTPS'" -ErrorAction Stop
    $httpsExe = Join-Path $httpsRoot 'TPVERPHTTPS.exe'
    if ($null -eq $httpsService -or [string]$https.service.id -cne 'TPVERPHTTPS' -or
        [string]$https.service.serviceaccount.username -cne 'NT SERVICE\TPVERPHTTPS' -or
        [string]$httpsService.StartName -cne 'NT SERVICE\TPVERPHTTPS' -or
        -not [string]::Equals(([string]$httpsService.PathName).Trim(' ', '"'), $httpsExe,
            [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $httpsExe -PathType Leaf)) {
        throw 'El gateway HTTPS TEST_ONLY no es un servicio WinSW registrado y esperado.'
    }
    $httpsUrls = @($https.service.env | Where-Object { $_.name -ceq 'TPV_HTTPS_PUBLIC_URL' })
    $publicUri = $null
    if ($httpsUrls.Count -ne 1 -or
        -not [Uri]::TryCreate([string]$httpsUrls[0].value, [UriKind]::Absolute, [ref]$publicUri) -or
        $publicUri.Scheme -cne 'https' -or -not (Test-PrivateLabHost $publicUri.Host) -or
        $publicUri.UserInfo -or $publicUri.Query -or $publicUri.Fragment) {
        throw 'La URL pública HTTPS instalada no pertenece al laboratorio privado.'
    }
    $portMatch = [Regex]::Matches($arguments, '(?i)(?:^|\s)--server\.port=([0-9]{1,5})(?=\s|$)')
    if ($portMatch.Count -ne 1) { throw 'El puerto del backend TEST_ONLY instalado es ambiguo.' }
    foreach ($variable in @(Get-ChildItem Env: | Where-Object {
            $_.Name -match '^PG[A-Z0-9_]*$' -and $_.Name -cne 'PGPASSWORD' })) {
        Remove-Item -LiteralPath ('Env:' + $variable.Name) -ErrorAction Stop
    }
    foreach ($name in @('TPV_VERIFACTU_DEV_SIGNING_PKCS12', 'TPV_VERIFACTU_DEV_SIGNING_PASSWORD',
            'TPV_VERIFACTU_DEV_SIGNING_PKCS12_PASSWORD')) {
        Remove-Item -LiteralPath ('Env:' + $name) -ErrorAction SilentlyContinue
    }
    foreach ($entry in $map.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, [string]$values[$entry.Value], 'Process')
    }
    [Environment]::SetEnvironmentVariable('TPV_TRANSFER_PUBLIC_HOST', $publicUri.Host, 'Process')
    [Environment]::SetEnvironmentVariable('TPV_TRANSFER_TLS_DIRECTORY', (Join-Path $httpsRoot 'tls'), 'Process')
    [Environment]::SetEnvironmentVariable('TPV_TRANSFER_HTTPS_SERVICE_ACCOUNT', 'NT SERVICE\TPVERPHTTPS', 'Process')
    [Environment]::SetEnvironmentVariable('TPV_BACKEND_SERVICE_ACCOUNT', 'NT SERVICE\TPVERPBackend', 'Process')
    [Environment]::SetEnvironmentVariable('TPV_BACKEND_PORT', $portMatch[0].Groups[1].Value, 'Process')
    [Environment]::SetEnvironmentVariable('SPRING_PROFILES_ACTIVE', 'lab-bundle', 'Process')
}
[pscustomobject]@{ ConfigurationFile = $config; VirtualMachine = [string]$computer.Model; AclPaths = $aclPaths }
