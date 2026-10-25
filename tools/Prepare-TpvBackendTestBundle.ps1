[CmdletBinding()]
param(
    [string] $OutputDirectory,
    [string] $ExpectedVersion = '0.1.0',
    [string] $ExpectedReleaseId,
    [string] $ExpectedSchemaVersion = 'V279',
    [Nullable[long]] $ExpectedReleaseSequence = 37,
    [Nullable[long]] $ExpectedBuildSequence = 1
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$commit = (& git -C $root rev-parse --verify HEAD).Trim().ToLowerInvariant()
if ($commit -notmatch '^[0-9a-f]{40}$') { throw 'No se pudo resolver el commit limpio de TEST_ONLY.' }
$canonicalReleaseId = "tpv-erp-test-only-0.1.0-v279-$($commit.Substring(0, 12))"
if ([string]::IsNullOrWhiteSpace($ExpectedReleaseId)) { $ExpectedReleaseId = $canonicalReleaseId }
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) { $OutputDirectory = "artifacts\backend-$ExpectedReleaseId" }
if ($ExpectedVersion -cne '0.1.0' -or $ExpectedReleaseId -cne $canonicalReleaseId -or
    $ExpectedSchemaVersion -cne 'V279' -or $null -eq $ExpectedReleaseSequence -or
    $ExpectedReleaseSequence -lt 36 -or $ExpectedBuildSequence -ne 1) {
    throw 'La identidad TEST_ONLY debe coincidir con 0.1.0/V279, releaseSequence >= 36, b1 y el commit HEAD.'
}

function Assert-SafeExpectedSegment([string] $Value, [string] $Name) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' -or
        $Value -in @('.', '..')) {
        throw "$Name debe ser un segmento seguro sin separadores de ruta"
    }
}

Assert-SafeExpectedSegment $ExpectedVersion 'ExpectedVersion'
Assert-SafeExpectedSegment $ExpectedReleaseId 'ExpectedReleaseId'
Assert-SafeExpectedSegment $ExpectedSchemaVersion 'ExpectedSchemaVersion'
if ($ExpectedReleaseSequence -lt 0 -or $ExpectedBuildSequence -lt 0) {
    throw 'ExpectedReleaseSequence y ExpectedBuildSequence deben ser no negativos'
}

$verifier = Join-Path $PSScriptRoot 'Test-TpvBackendTestBundle.ps1'
$backend = Join-Path $root 'backend'
$pom = [xml](Get-Content -LiteralPath (Join-Path $backend 'pom.xml') -Raw)
$testProfile = @($pom.project.profiles.profile | Where-Object { $_.id -ceq 'test-bundle' })
if ($testProfile.Count -ne 1 -or
    [string]$testProfile[0].build.directory -cne '${project.basedir}/target/test-bundle-build') {
    throw 'test-bundle debe compilar y limpiar exclusivamente backend/target/test-bundle-build.'
}
$projectVersion = [string]$pom.project.version
if ([string]::IsNullOrWhiteSpace($projectVersion)) {
    throw 'No se pudo resolver project.version del pom.xml.'
}
# Maven identifies the build artifact; the fiscal release profile identifies the
# public system version. They may differ without changing the JAR contents.
$projectVersion = $projectVersion.Trim()

function Get-Sha256([string] $Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
}

function Get-ManifestHash([string] $ReleaseId, [string] $Version, [string] $Capability,
    [string] $Schema, [long] $ReleaseSequence, [long] $BuildSequence,
    [string] $Commit, [string] $Declaration) {
    $payload = "release.id=$ReleaseId`n" +
        "system.version=$Version`n" +
        "capability=$Capability`n" +
        "schema.version=$Schema`n" +
        "release.sequence=$ReleaseSequence`n" +
        "build.sequence=$BuildSequence`n" +
        "commit.hash=$Commit`n" +
        "declaration.hash=$Declaration`n"
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($payload)))).Replace('-', '').ToUpperInvariant()
    }
    finally { $sha.Dispose() }
}

$gitStatus = (& git -C $root status --porcelain | Out-String).Trim()
if (-not [string]::IsNullOrWhiteSpace($gitStatus)) {
    throw 'El checkout contiene cambios; el flujo de release exige un checkout limpio.'
}

    $manifestHash = Get-ManifestHash $ExpectedReleaseId $ExpectedVersion 'DUAL' $ExpectedSchemaVersion `
        $ExpectedReleaseSequence $ExpectedBuildSequence $commit ''

    Push-Location $backend
    try {
        & (Join-Path $backend 'mvnw.cmd') '-Ptest-bundle' clean package '-DskipTests' `
            "-Dtpv.release.id=$ExpectedReleaseId" `
            "-Dtpv.release.version=$ExpectedVersion" `
            "-Dtpv.release.commit.hash=$commit" `
            '-Dtpv.release.declaration.hash=' `
            "-Dtpv.release.manifest.hash=$manifestHash" `
            "-Dtpv.release.sequence=$ExpectedReleaseSequence" `
            "-Dtpv.release.build.sequence=$ExpectedBuildSequence"
        if ($LASTEXITCODE -ne 0) { throw 'El empaquetado Maven de test-bundle fallo.' }
    }
    finally { Pop-Location }
$targetJar = Join-Path $backend "target\test-bundle-build\tpv-erp-backend-$projectVersion.jar"
if (-not (Test-Path -LiteralPath $targetJar -PathType Leaf)) {
    throw "No existe el fat JAR esperado: $targetJar"
}
$targetSidecar = "$targetJar.sha256"
if (-not (Test-Path -LiteralPath $targetSidecar -PathType Leaf)) {
    throw "No existe el sidecar SHA-256 esperado: $targetSidecar"
}

$resolvedOutput = [IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
if ([IO.Path]::GetPathRoot($resolvedOutput) -eq $resolvedOutput) { throw 'OutputDirectory no puede ser la raiz de una unidad.' }
New-Item -ItemType Directory -Path $resolvedOutput -Force | Out-Null
$outputJar = Join-Path $resolvedOutput "tpv-erp-backend-$ExpectedVersion.jar"
$sourceHash = (Get-FileHash -LiteralPath $targetJar -Algorithm SHA256).Hash.ToUpperInvariant()
if (Test-Path -LiteralPath $outputJar -PathType Leaf) {
    $existingHash = (Get-FileHash -LiteralPath $outputJar -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($existingHash -cne $sourceHash) {
        throw "La release inmutable ya existe con otro hash: $outputJar"
    }
} else {
    Copy-Item -LiteralPath $targetJar -Destination $outputJar
}
if (Test-Path -LiteralPath "$outputJar.sha256" -PathType Leaf) {
    $existingSidecar = (Get-Content -LiteralPath "$outputJar.sha256" -Raw).Trim().Split([char[]]@(' ', "`t"), [StringSplitOptions]::RemoveEmptyEntries)[0]
    if ($existingSidecar -cne $sourceHash) { throw "El sidecar de la release inmutable no coincide: $outputJar.sha256" }
} else {
    Copy-Item -LiteralPath $targetSidecar -Destination "$outputJar.sha256"
}

$maintenanceNames = @('Invoke-TpvServerTransfer.ps1', 'Start-TpvServerTransferMaintenance.ps1',
    'Invoke-TpvDisasterRecovery.ps1', 'Start-TpvDisasterRecoveryMaintenance.ps1',
    'Test-TpvBackendTestBundle.ps1', 'Test-TpvBackendProductionBundle.ps1',
    'Invoke-TpvBackupRestoreOffline.ps1', 'Test-TpvBackupRestoreOfflinePreflight.ps1',
    'Resolve-TpvBackendInstalledBundleVerifier.ps1', 'Assert-TpvTestLabContext.ps1',
    'Install-TpvBackendTestWindowsService.ps1', 'Install-TpvHttpsWindowsService.ps1',
    'Set-TpvBackendWindowsAcl.ps1', 'Set-TpvBackendTestWindowsAcl.ps1', 'TpvServerPorts.psm1')
$maintenanceDirectory = Join-Path $resolvedOutput 'tools'
New-Item -ItemType Directory -Path $maintenanceDirectory -Force | Out-Null
$maintenanceHashes = [ordered]@{}
foreach ($name in $maintenanceNames) {
    $source = Join-Path $PSScriptRoot $name
    $destination = Join-Path $maintenanceDirectory $name
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Falta una herramienta de mantenimiento: $name" }
    $hash = Get-Sha256 $source
    if (Test-Path -LiteralPath $destination) {
        if ((Get-Sha256 $destination) -cne $hash) { throw "La herramienta inmutable ya existe con otro hash: $name" }
    } else { Copy-Item -LiteralPath $source -Destination $destination }
    $maintenanceHashes[$name] = $hash
}
$toolsManifest = Join-Path $resolvedOutput 'maintenance-tools.sha256.json'
$toolsJson = $maintenanceHashes | ConvertTo-Json
if (Test-Path -LiteralPath $toolsManifest) {
    if ((Get-Content -LiteralPath $toolsManifest -Raw).Trim() -cne $toolsJson.Trim()) { throw 'El manifiesto de herramientas inmutable no coincide.' }
} else { [IO.File]::WriteAllText($toolsManifest, $toolsJson, [Text.UTF8Encoding]::new($false)) }

& $verifier -BundleDirectory $resolvedOutput `
    -ExpectedVersion $ExpectedVersion -ExpectedReleaseId $ExpectedReleaseId `
    -ExpectedSchemaVersion $ExpectedSchemaVersion `
    -ExpectedReleaseSequence $ExpectedReleaseSequence -ExpectedBuildSequence $ExpectedBuildSequence
if ($LASTEXITCODE -ne 0) { throw 'La verificacion final del bundle fallo.' }
Write-Host "TEST_ONLY_VERIFIED / NO PROMOTABLE en: $resolvedOutput" -ForegroundColor Yellow
