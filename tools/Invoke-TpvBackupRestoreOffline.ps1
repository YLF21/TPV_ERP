[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)] [string] $BackupFile,
    [Parameter(Mandatory = $true)] [string] $RecoveryFile,
    [Parameter(Mandatory = $true)] [string] $ProductImagesDirectory,
    [Parameter(Mandatory = $true)] [string] $DocumentTemplatesDirectory,
    [string] $StagingDirectory = '',
    [string] $JournalFile = '',
    [string] $JavaExecutable = 'java',
    [Parameter(Mandatory = $true)] [string] $BundleDirectory,
    [Parameter(Mandatory = $true)] [string] $ExpectedReleaseId,
    [string] $PgRestoreCommand = 'pg_restore',
    [switch] $Execute
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$bundleRoot = (Resolve-Path -LiteralPath $BundleDirectory -ErrorAction Stop).Path
$jars = @(Get-ChildItem -LiteralPath $bundleRoot -Filter '*.jar' -File |
    Where-Object { $_.Name -notmatch '\.original\.jar$' })
if ($jars.Count -ne 1) { throw 'Se requiere un unico JAR de bundle.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($jars[0].FullName)
try { $testOnly = ($null -ne $zip.GetEntry('META-INF/tpv-test-bundle.properties')) -or
        ($null -ne $zip.GetEntry('BOOT-INF/classes/META-INF/tpv-test-bundle.properties')) }
finally { $zip.Dispose() }
if ($testOnly) {
    $policy = & (Join-Path $PSScriptRoot 'Resolve-TpvBackendInstalledBundleVerifier.ps1') -BundleDirectory $bundleRoot
    if (-not $policy.TestOnly -or $policy.Bundle.ReleaseId -cne $ExpectedReleaseId) {
        throw 'La release TEST_ONLY instalada no coincide con ExpectedReleaseId.'
    }
    & (Join-Path $PSScriptRoot 'Assert-TpvTestLabContext.ps1') `
        -InstalledServiceXml (Join-Path $env:ProgramData 'TPV ERP\Backend\TPVERPBackend.xml') -ApplyCliEnvironment | Out-Null
    $bundle = $policy.Bundle
} else {
    $bundle = & (Join-Path $PSScriptRoot 'Test-TpvBackendProductionBundle.ps1') `
        -BundleDirectory $bundleRoot -ExpectedReleaseId $ExpectedReleaseId -AsObject
}
$preflight = Join-Path $PSScriptRoot 'Test-TpvBackupRestoreOfflinePreflight.ps1'
$preflightArgs = @{
    BackupFile = $BackupFile; RecoveryFile = $RecoveryFile
    ProductImagesDirectory = $ProductImagesDirectory
    DocumentTemplatesDirectory = $DocumentTemplatesDirectory
    PgRestoreCommand = $PgRestoreCommand
}
if (-not [string]::IsNullOrWhiteSpace($StagingDirectory)) { $preflightArgs.StagingDirectory = $StagingDirectory }
& $preflight @preflightArgs | Write-Output
if (-not $Execute) {
    Write-Output 'Preflight solamente. Use -Execute en una ventana de mantenimiento aprobada para restaurar.'
    return
}
if (-not (Get-Command $JavaExecutable -ErrorAction SilentlyContinue)) { throw "No se encuentra Java: $JavaExecutable" }

$resolvedBackup = (Resolve-Path -LiteralPath $BackupFile).Path
$resolvedRecovery = (Resolve-Path -LiteralPath $RecoveryFile).Path
$resolvedImages = [IO.Path]::GetFullPath($ProductImagesDirectory)
$resolvedTemplates = [IO.Path]::GetFullPath($DocumentTemplatesDirectory)
if ([string]::IsNullOrWhiteSpace($JournalFile)) {
    $JournalFile = if ([string]::IsNullOrWhiteSpace($env:TPV_BACKUP_RESTORE_JOURNAL_PATH)) {
        'C:\ProgramData\TPV ERP\restore\tpv-restore-journal.properties'
    } else { $env:TPV_BACKUP_RESTORE_JOURNAL_PATH }
}
if ($testOnly -and -not [string]::Equals([IO.Path]::GetFullPath($JournalFile),
        [IO.Path]::GetFullPath($env:TPV_BACKUP_RESTORE_JOURNAL_PATH),
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'El journal TEST_ONLY debe coincidir con la configuracion instalada.'
}
$resolvedJournal = [IO.Path]::GetFullPath($JournalFile)
$cliArgs = @(
    '-Dloader.main=com.tpverp.backend.backup.application.OfflineRestoreCli',
    '-Dloader.config.location=classpath:META-INF/tpv-erp-release.properties',
    '-Dloader.path=',
    '-cp', $bundle.Jar,
    'org.springframework.boot.loader.launch.PropertiesLauncher',
    '--backup', $resolvedBackup, '--recovery', $resolvedRecovery,
    '--images', $resolvedImages, '--templates', $resolvedTemplates,
    '--journal', $resolvedJournal, '--pg-restore', $PgRestoreCommand
)
if (-not [string]::IsNullOrWhiteSpace($StagingDirectory)) { $cliArgs += @('--staging', [IO.Path]::GetFullPath($StagingDirectory)) }
if ($PSCmdlet.ShouldProcess($resolvedBackup, 'Ejecutar restauración offline PostgreSQL + ficheros')) {
    if (@(Get-ChildItem Env: | Where-Object { $_.Name -like 'LOADER_*' }).Count -gt 0) {
        throw 'Retire variables LOADER_* del entorno de mantenimiento; el CLI solo carga el bundle verificado.'
    }
    if ((Get-FileHash -LiteralPath $bundle.Jar -Algorithm SHA256).Hash -cne $bundle.ArtifactSha256) {
        throw 'El JAR cambio tras el preflight; no se ejecuta la restauracion.'
    }
    Write-Output 'Se solicitará la clave de recuperación en consola segura; no se acepta como parámetro ni se registra.'
    & $JavaExecutable @cliArgs
    if ($LASTEXITCODE -ne 0) { throw "La restauración offline terminó con código $LASTEXITCODE; conserve el journal para recuperación." }
    $profile = if ($testOnly) { 'lab-bundle' } else { 'prod' }
    Write-Output "Antes del arranque normal finalice con el mismo JAR verificado '$($bundle.Jar)' y la configuracion externa: --spring.profiles.active=$profile --spring.main.web-application-type=none --tpv.restore-finalize=$resolvedJournal"
}
