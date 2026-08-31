[CmdletBinding()]
param(
    [string] $OutputDirectory = 'artifacts\backend-4.2.0',
    [string] $ExpectedVersion = '4.2.0',
    [string] $ExpectedReleaseId = 'tpv-erp-4.2.0',
    [string] $ExpectedSchemaVersion = 'V234',
    [long] $ExpectedReleaseSequence = 1,
    [long] $ExpectedBuildSequence = 1,
    [string] $DeclarationPdf,
    [switch] $NoBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

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

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$verifier = Join-Path $PSScriptRoot 'Test-TpvBackendProductionBundle.ps1'
$backend = Join-Path $root 'backend'
$pom = [xml](Get-Content -LiteralPath (Join-Path $backend 'pom.xml') -Raw)
$projectVersion = [string]$pom.project.version
if ([string]::IsNullOrWhiteSpace($projectVersion)) {
    throw 'No se pudo resolver project.version del pom.xml.'
}
if ($ExpectedVersion -cne $projectVersion.Trim()) {
    throw "ExpectedVersion debe coincidir con project.version ($($projectVersion.Trim())); el script no cambia el nombre del JAR Maven."
}
$expectedDeclaration = Join-Path $backend "src\main\resources\META-INF\fiscal\declaracion-responsable-$ExpectedVersion.pdf"

if (-not [string]::IsNullOrWhiteSpace($DeclarationPdf) -and
    [IO.Path]::GetFullPath($DeclarationPdf) -ne [IO.Path]::GetFullPath($expectedDeclaration)) {
    throw 'DeclarationPdf externo se rechaza: el PDF debe estar incorporado en el recurso versionado del backend.'
}

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

if (-not $NoBuild) {
    $commit = (& git -C $root rev-parse --verify HEAD).Trim().ToLowerInvariant()
    if ($commit -notmatch '^[0-9a-f]{7,64}$') { throw 'No se pudo resolver el commit de release.' }

    $DeclarationPdf = $expectedDeclaration
    if (-not (Test-Path -LiteralPath $DeclarationPdf -PathType Leaf)) {
        throw "Falta el PDF legal de produccion: $DeclarationPdf. La release queda bloqueada."
    }
    $declarationHash = Get-Sha256 $DeclarationPdf
    $manifestHash = Get-ManifestHash $ExpectedReleaseId $ExpectedVersion 'VERIFACTU_ONLY' $ExpectedSchemaVersion `
        $ExpectedReleaseSequence $ExpectedBuildSequence $commit $declarationHash

    Push-Location $backend
    try {
        & (Join-Path $backend 'mvnw.cmd') '-Pproduction-release' clean package `
            "-Dtpv.release.id=$ExpectedReleaseId" `
            "-Dtpv.release.version=$ExpectedVersion" `
            "-Dtpv.release.commit.hash=$commit" `
            "-Dtpv.release.declaration.hash=$declarationHash" `
            "-Dtpv.release.manifest.hash=$manifestHash" `
            "-Dtpv.release.sequence=$ExpectedReleaseSequence" `
            "-Dtpv.release.build.sequence=$ExpectedBuildSequence"
        if ($LASTEXITCODE -ne 0) { throw 'El empaquetado Maven de production-release fallo.' }
    }
    finally { Pop-Location }
}

$targetJar = Join-Path $backend "target\tpv-erp-backend-$ExpectedVersion.jar"
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
$outputJar = Join-Path $resolvedOutput (Split-Path -Leaf $targetJar)
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

& $verifier -BundleDirectory $resolvedOutput `
    -ExpectedVersion $ExpectedVersion -ExpectedReleaseId $ExpectedReleaseId `
    -ExpectedSchemaVersion $ExpectedSchemaVersion `
    -ExpectedReleaseSequence $ExpectedReleaseSequence -ExpectedBuildSequence $ExpectedBuildSequence
if ($LASTEXITCODE -ne 0) { throw 'La verificacion final del bundle fallo.' }
Write-Host "Bundle backend preparado y verificado en: $resolvedOutput" -ForegroundColor Green
