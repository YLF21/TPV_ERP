[CmdletBinding()]
param([string] $RepositoryRoot = (Join-Path $PSScriptRoot '..'))

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$backend = Join-Path (Resolve-Path -LiteralPath $RepositoryRoot).Path 'backend'
$pom = [xml](Get-Content -LiteralPath (Join-Path $backend 'pom.xml') -Raw)
$profile = @($pom.project.profiles.profile | Where-Object { $_.id -eq 'production-release' })
if ($profile.Count -ne 1) { throw 'Se requiere un unico perfil production-release.' }
$schemaLines = @(Get-Content -LiteralPath (Join-Path $backend 'src\main\resources\META-INF\tpv-erp-release.properties') |
    Where-Object { $_ -match '^schema\.version=' })
if ($schemaLines.Count -ne 1 -or $schemaLines[0] -notmatch '^schema\.version=(V[0-9]+(?:[._][0-9]+)*)$') {
    throw 'El manifiesto fuente debe declarar un unico schema.version concreto.'
}
$schema = $matches[1]
$properties = $profile[0].properties
[pscustomobject]@{
    Version = [string]$properties.'tpv.release.version'
    ReleaseId = [string]$properties.'tpv.release.id'
    SchemaVersion = $schema
    ReleaseSequence = [long]$properties.'tpv.release.sequence'
    BuildSequence = [long]$properties.'tpv.release.build.sequence'
}
