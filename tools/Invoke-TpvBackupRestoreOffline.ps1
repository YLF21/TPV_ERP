[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)] [string] $BackupFile,
    [Parameter(Mandatory = $true)] [string] $RecoveryFile,
    [Parameter(Mandatory = $true)] [string] $ProductImagesDirectory,
    [Parameter(Mandatory = $true)] [string] $DocumentTemplatesDirectory,
    [string] $StagingDirectory = '',
    [string] $JournalFile = '',
    [string] $JavaExecutable = 'java',
    [string] $BackendClasses = '',
    [string] $PgRestoreCommand = 'pg_restore',
    [switch] $Execute
)

$ErrorActionPreference = 'Stop'
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
if ([string]::IsNullOrWhiteSpace($BackendClasses)) { throw 'BackendClasses es obligatorio para la ejecución efectiva.' }
if (-not (Test-Path -LiteralPath $BackendClasses -PathType Container)) { throw "No existe BackendClasses: $BackendClasses" }
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
$resolvedJournal = [IO.Path]::GetFullPath($JournalFile)
$cliArgs = @(
    '-cp', [IO.Path]::GetFullPath($BackendClasses),
    'com.tpverp.backend.backup.application.OfflineRestoreCli',
    '--backup', $resolvedBackup, '--recovery', $resolvedRecovery,
    '--images', $resolvedImages, '--templates', $resolvedTemplates,
    '--journal', $resolvedJournal, '--pg-restore', $PgRestoreCommand
)
if (-not [string]::IsNullOrWhiteSpace($StagingDirectory)) { $cliArgs += @('--staging', [IO.Path]::GetFullPath($StagingDirectory)) }
if ($PSCmdlet.ShouldProcess($resolvedBackup, 'Ejecutar restauración offline PostgreSQL + ficheros')) {
    Write-Output 'Se solicitará la clave de recuperación en consola segura; no se acepta como parámetro ni se registra.'
    & $JavaExecutable @cliArgs
    if ($LASTEXITCODE -ne 0) { throw "La restauración offline terminó con código $LASTEXITCODE; conserve el journal para recuperación." }
    Write-Output "Antes del arranque normal ejecute sin web: java -jar <backend.jar> --spring.profiles.active=prod --spring.main.web-application-type=none --tpv.restore-finalize=$resolvedJournal"
}
