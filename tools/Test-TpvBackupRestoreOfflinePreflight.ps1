[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $BackupFile,
    [Parameter(Mandatory = $true)] [string] $RecoveryFile,
    [Parameter(Mandatory = $true)] [string] $ProductImagesDirectory,
    [Parameter(Mandatory = $true)] [string] $DocumentTemplatesDirectory,
    [string] $StagingDirectory = '',
    [string] $ServiceName = 'TPVERPBackend',
    [ValidateRange(1, 65535)] [int] $BackendPort = 8080,
    [string] $PgRestoreCommand = 'pg_restore',
    [ValidateRange(1, [long]::MaxValue)] [long] $MaximumExpandedArchiveBytes = 2147483648
)

$ErrorActionPreference = 'Stop'

function Get-SafeResolvedPath([string] $Path, [bool] $MustExist) {
    if ($MustExist -and -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "No existe el archivo requerido: $path"
    }
    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "La ruta no puede ser un enlace ni punto de reanalisis: $Path"
    }
    return $item.FullName
}

$resolvedBackup = Get-SafeResolvedPath $BackupFile $true
$resolvedRecovery = Get-SafeResolvedPath $RecoveryFile $true

function Assert-Magic([string] $Path, [byte[]] $Expected, [string] $Label) {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        $actual = New-Object byte[] $Expected.Length
        if ($stream.Read($actual, 0, $actual.Length) -ne $actual.Length) {
            throw "$Label esta truncado"
        }
        if ([Convert]::ToHexString($actual) -ne [Convert]::ToHexString($Expected)) {
            throw "$Label no tiene la cabecera esperada"
        }
    } finally {
        $stream.Dispose()
    }
}

Assert-Magic $resolvedBackup ([byte[]](0x54, 0x50, 0x56, 0x42)) 'El backup TPVB'
Assert-Magic $resolvedRecovery ([byte[]](0x54, 0x50, 0x56, 0x52)) 'El archivo de recuperacion'

$activeRoots = @()
foreach ($path in @($ProductImagesDirectory, $DocumentTemplatesDirectory)) {
    if (Test-Path -LiteralPath $path) {
        $item = Get-Item -LiteralPath $path -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "La ruta activa no puede ser un enlace ni punto de reanalisis: $path"
        }
        if (-not $item.PSIsContainer) {
            throw "La ruta activa debe ser un directorio: $path"
        }
        $activeRoots += $item.FullName.TrimEnd('\')
    } else {
        $activeRoots += [IO.Path]::GetFullPath($path).TrimEnd('\')
    }
}
if ($activeRoots[0] -eq $activeRoots[1] -or
        $activeRoots[0].StartsWith($activeRoots[1] + '\', [StringComparison]::OrdinalIgnoreCase) -or
        $activeRoots[1].StartsWith($activeRoots[0] + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Las rutas activas de imagenes y plantillas deben ser independientes.'
}

$service = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($service -and $service.Status -ne [System.ServiceProcess.ServiceControllerStatus]::Stopped) {
    throw "El servicio $ServiceName esta $($service.Status). Detengalo antes de la restauracion offline."
}

$listeners = Get-NetTCPConnection -State Listen -LocalPort $BackendPort -ErrorAction SilentlyContinue
if ($listeners) {
    throw "El puerto backend $BackendPort sigue escuchando. Detenga el proceso antes de restaurar."
}

try {
    $javaBackends = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction Stop |
        Where-Object { $_.CommandLine -match '(?i)(tpv[- ]?erp|backend[^ ]*\.jar|spring-boot:run)' }
    if ($javaBackends) {
        throw 'Existe un proceso Java del backend TPV ERP. Detengalo antes de restaurar.'
    }
} catch {
    if ($_.Exception.Message -like '*proceso Java*') { throw }
    throw 'No se pudo comprobar de forma fiable si existe un backend Java activo.'
}

if (-not (Get-Command $PgRestoreCommand -ErrorAction SilentlyContinue)) {
    throw "No se encuentra pg_restore: $PgRestoreCommand"
}

if ([string]::IsNullOrWhiteSpace($StagingDirectory)) {
    $StagingDirectory = Split-Path -Parent $resolvedBackup
}
$resolvedStaging = [IO.Path]::GetFullPath($StagingDirectory)
$stagingRoot = [IO.Path]::GetPathRoot($resolvedStaging)
$drive = [IO.DriveInfo]::new($stagingRoot)
$requiredFree = (Get-Item -LiteralPath $resolvedBackup).Length + $MaximumExpandedArchiveBytes
if ($drive.AvailableFreeSpace -lt $requiredFree) {
    throw "Espacio libre insuficiente en $stagingRoot. Requerido: $requiredFree; disponible: $($drive.AvailableFreeSpace)."
}

Write-Output ('Backup SHA-256: ' + (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedBackup).Hash)
Write-Output ('Recovery SHA-256: ' + (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedRecovery).Hash)
Write-Output 'Preflight correcto: formato base, rutas, servicio/procesos, puerto, pg_restore y espacio verificados.'
Write-Output 'La restauración efectiva debe ejecutarse siguiendo docs/tpv-backup-restore-operations.md.'
