[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $BundleDirectory,
    [string] $ExpectedVersion = '4.2.0',
    [string] $ExpectedReleaseId = 'tpv-erp-4.2.0',
    [string] $ExpectedSchemaVersion = 'V229',
    [long] $ExpectedReleaseSequence = 1,
    [long] $ExpectedBuildSequence = 1,
    [switch] $AsObject
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-SafeExpectedSegment([string] $Value, [string] $Name) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' -or
        $Value -in @('.', '..')) {
        Fail "$Name debe ser un segmento seguro sin separadores de ruta"
    }
}

function Assert-NoReparsePath([string] $Path, [string] $Description) {
    $current = [IO.Path]::GetFullPath($Path)
    while ($true) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                Fail "$Description contiene un reparse point: $current"
            }
        }
        $parent = [IO.Path]::GetDirectoryName($current)
        if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $current) { break }
        $current = $parent
    }
}

function Fail([string] $Message) {
    throw "Bundle backend no promocionable: $Message"
}

function Read-ZipText($Entry) {
    $stream = $Entry.Open()
    try {
        $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8, $true)
        try { return $reader.ReadToEnd() }
        finally { $reader.Dispose() }
    }
    finally { $stream.Dispose() }
}

function Read-ZipBytes($Entry) {
    $stream = $Entry.Open()
    try {
        $memory = [IO.MemoryStream]::new()
        try {
            $stream.CopyTo($memory)
            return $memory.ToArray()
        }
        finally { $memory.Dispose() }
    }
    finally { $stream.Dispose() }
}

function Get-Sha256Bytes([byte[]] $Bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToUpperInvariant() }
    finally { $sha.Dispose() }
}

function Get-Property([hashtable] $Properties, [string] $Name) {
    if (-not $Properties.ContainsKey($Name)) { Fail "falta $Name en META-INF/tpv-erp-release.properties" }
    $value = [string]$Properties[$Name]
    if ([string]::IsNullOrWhiteSpace($value) -or $value.Contains('@')) { Fail "$Name esta vacio o sin filtrar" }
    return $value.Trim()
}

Assert-SafeExpectedSegment $ExpectedVersion 'ExpectedVersion'
Assert-SafeExpectedSegment $ExpectedReleaseId 'ExpectedReleaseId'
Assert-SafeExpectedSegment $ExpectedSchemaVersion 'ExpectedSchemaVersion'
if ($ExpectedReleaseSequence -lt 0 -or $ExpectedBuildSequence -lt 0) {
    Fail 'ExpectedReleaseSequence y ExpectedBuildSequence deben ser no negativos'
}

$resolvedBundle = (Resolve-Path -LiteralPath $BundleDirectory -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath $resolvedBundle -PathType Container)) { Fail "BundleDirectory no es un directorio" }
Assert-NoReparsePath $resolvedBundle 'BundleDirectory'

$jars = @(Get-ChildItem -LiteralPath $resolvedBundle -File -Filter '*.jar' |
    Where-Object { $_.Name -notmatch '\.original\.jar$' })
if ($jars.Count -ne 1) { Fail "se esperaba exactamente un JAR (encontrados: $($jars.Count))" }
$jar = $jars[0]
$sidecarPath = "$($jar.FullName).sha256"
if (-not (Test-Path -LiteralPath $sidecarPath -PathType Leaf)) { Fail "falta el sidecar SHA-256 exacto junto al JAR" }
Assert-NoReparsePath $jar.FullName 'JAR'
Assert-NoReparsePath $sidecarPath 'Sidecar SHA-256'

$sidecar = (Get-Content -LiteralPath $sidecarPath -Raw).Trim()
if ($sidecar -notmatch '^[0-9A-Fa-f]{64}(?:\s+\*?[^\r\n]+)?$') {
    Fail "el sidecar no contiene un SHA-256 valido"
}
$declaredArtifactHash = $sidecar.Split([char[]]@(' ', "`t"), [StringSplitOptions]::RemoveEmptyEntries)[0].ToUpperInvariant()
$actualArtifactHash = (Get-FileHash -LiteralPath $jar.FullName -Algorithm SHA256).Hash.ToUpperInvariant()
if ($declaredArtifactHash -cne $actualArtifactHash) { Fail "el sidecar no coincide con el JAR" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
try {
    $entryNames = @($zip.Entries | ForEach-Object FullName)
    $seenEntryNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entryName in $entryNames) {
        if (-not $seenEntryNames.Add($entryName)) {
            Fail "el JAR contiene entradas ZIP duplicadas (ignorando mayusculas): $entryName"
        }
    }
    $hasLib = ('BOOT-INF/lib/' -in $entryNames) -or
        (@($entryNames | Where-Object { $_ -like 'BOOT-INF/lib/*.jar' }).Count -gt 0)
    if ('BOOT-INF/classes/' -notin $entryNames -or -not $hasLib) {
        Fail 'el JAR no parece un fat JAR Spring Boot (faltan BOOT-INF/classes o BOOT-INF/lib)'
    }
    $hasLauncher = ('org/springframework/boot/loader/JarLauncher.class' -in $entryNames) -or
        ('org/springframework/boot/loader/launch/JarLauncher.class' -in $entryNames)
    if (-not $hasLauncher) {
        Fail 'el JAR no contiene el launcher Spring Boot'
    }

    $manifestEntry = $zip.GetEntry('META-INF/tpv-erp-release.properties')
    if ($null -eq $manifestEntry) { Fail 'falta META-INF/tpv-erp-release.properties' }
    if ($manifestEntry.Length -gt 64KB) { Fail 'el manifiesto supera el limite de 64 KiB' }
    $properties = @{}
    foreach ($line in (Read-ZipText $manifestEntry) -split "`r?`n") {
        if ($line -match '^\s*(#|$)') { continue }
        if ($line -notmatch '^\s*([^=]+?)\s*=\s*(.*)$') { Fail 'el manifiesto contiene una linea no valida' }
        $propertyName = $matches[1].Trim()
        if ([string]::IsNullOrWhiteSpace($propertyName)) { Fail 'el manifiesto contiene una clave vacia' }
        if ($properties.ContainsKey($propertyName)) { Fail "el manifiesto contiene la clave duplicada $propertyName" }
        $properties[$propertyName] = $matches[2].Trim()
    }

    $releaseId = Get-Property $properties 'release.id'
    $version = Get-Property $properties 'system.version'
    $capability = Get-Property $properties 'capability'
    $schema = Get-Property $properties 'schema.version'
    $releaseSequenceValue = Get-Property $properties 'release.sequence'
    $buildSequenceValue = Get-Property $properties 'build.sequence'
    $commitHash = Get-Property $properties 'commit.hash'
    $declarationHash = Get-Property $properties 'declaration.hash'
    $manifestHash = Get-Property $properties 'manifest.hash'

    if ($releaseId -cne $ExpectedReleaseId) { Fail "release.id esperado $ExpectedReleaseId y recibido $releaseId" }
    if ($version -cne $ExpectedVersion -or $version -match '(?i)dev|snapshot') { Fail 'la version no es una release productiva esperada' }
    if ($capability -cne 'VERIFACTU_ONLY') { Fail 'capability debe ser VERIFACTU_ONLY' }
    if ($schema -cne $ExpectedSchemaVersion) { Fail "schema.version esperado $ExpectedSchemaVersion y recibido $schema" }
    if ($releaseSequenceValue -notmatch '^[0-9]+$' -or $buildSequenceValue -notmatch '^[0-9]+$') {
        Fail 'release.sequence y build.sequence deben ser enteros no negativos'
    }
    try {
        $releaseSequence = [long]::Parse($releaseSequenceValue,
            [Globalization.CultureInfo]::InvariantCulture)
        $buildSequence = [long]::Parse($buildSequenceValue,
            [Globalization.CultureInfo]::InvariantCulture)
    } catch { Fail 'release.sequence o build.sequence exceden el rango permitido' }
    if ($releaseSequence -ne $ExpectedReleaseSequence) {
        Fail "release.sequence esperado $ExpectedReleaseSequence y recibido $releaseSequence"
    }
    if ($buildSequence -ne $ExpectedBuildSequence) {
        Fail "build.sequence esperado $ExpectedBuildSequence y recibido $buildSequence"
    }
    if ($commitHash -notmatch '^[0-9a-f]{7,64}$') { Fail 'commit.hash no es un commit hexadecimal' }
    if ($declarationHash -notmatch '^[0-9A-Fa-f]{64}$') { Fail 'declaration.hash no es SHA-256' }
    if ($manifestHash -notmatch '^[0-9A-Fa-f]{64}$') { Fail 'manifest.hash no es SHA-256' }

    $canonical = "release.id=$releaseId`n" +
        "system.version=$version`n" +
        "capability=$($capability.ToUpperInvariant())`n" +
        "schema.version=$schema`n" +
        "release.sequence=$releaseSequence`n" +
        "build.sequence=$buildSequence`n" +
        "commit.hash=$($commitHash.ToLowerInvariant())`n" +
        "declaration.hash=$($declarationHash.ToUpperInvariant())`n"
    $computedManifestHash = Get-Sha256Bytes ([Text.Encoding]::UTF8.GetBytes($canonical))
    if ($manifestHash.ToUpperInvariant() -cne $computedManifestHash) { Fail 'manifest.hash no coincide con el contenido canonico' }

    $safeVersion = [Regex]::Replace($version, '[^A-Za-z0-9._-]', '_')
    $declarationName = "BOOT-INF/classes/META-INF/fiscal/declaracion-responsable-$safeVersion.pdf"
    $declarationEntry = $zip.GetEntry($declarationName)
    if ($null -eq $declarationEntry) { Fail "falta el PDF legal embebido $declarationName" }
    if ($declarationEntry.Length -gt 25MB) { Fail 'la declaracion embebida supera el limite de 25 MiB' }
    $pdf = Read-ZipBytes $declarationEntry
    if ($pdf.Length -lt 5 -or [Text.Encoding]::ASCII.GetString($pdf, 0, 5) -cne '%PDF-') {
        Fail 'la declaracion embebida no tiene firma PDF'
    }
    $actualDeclarationHash = Get-Sha256Bytes $pdf
    if ($actualDeclarationHash -cne $declarationHash.ToUpperInvariant()) { Fail 'declaration.hash no coincide con el PDF embebido' }
}
finally { $zip.Dispose() }

$result = [pscustomobject]@{
    Status = 'PROMOTABLE'
    Jar = $jar.FullName
    ArtifactSha256 = $actualArtifactHash
    ReleaseId = $releaseId
    Version = $version
    Capability = $capability
    SchemaVersion = $schema
    ReleaseSequence = $releaseSequence
    BuildSequence = $buildSequence
    DeclarationSha256 = $declarationHash.ToUpperInvariant()
    ManifestSha256 = $manifestHash.ToUpperInvariant()
}
if ($AsObject) { $result } else {
    Write-Host "Bundle backend verificado: $($result.ReleaseId) / $($result.Version) / $($result.SchemaVersion)" -ForegroundColor Green
    Write-Host "JAR: $($result.Jar)"
    Write-Host "SHA-256: $($result.ArtifactSha256)"
}
