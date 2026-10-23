[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [ValidateSet('request','receive','activate','status')] [string] $Action,
    [Parameter(Mandatory = $true)] [string] $BundleDirectory,
    [Parameter(Mandatory = $true)] [string] $ExpectedReleaseId,
    [Parameter(Mandatory = $true)] [string] $MaintenanceDirectory,
    [string] $RecoveryId = '',
    [string] $JavaExecutable = 'java',
    [switch] $Execute
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$bundle = & (Join-Path $PSScriptRoot 'Test-TpvBackendProductionBundle.ps1') -BundleDirectory $BundleDirectory -ExpectedReleaseId $ExpectedReleaseId -AsObject
if ($Action -ne 'status' -and -not $Execute) { throw 'La operación requiere mantenimiento autorizado.' }
if ($Action -eq 'receive' -and $RecoveryId -notmatch '^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$') {
    throw 'El ID de recuperación autorizado no es válido.'
}
if ($Action -in @('request','receive','activate')) {
    foreach ($accountVariable in @('TPV_BACKEND_SERVICE_ACCOUNT','TPV_TRANSFER_HTTPS_SERVICE_ACCOUNT')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($accountVariable, 'Process'))) {
            throw "Falta ${accountVariable}: indique la cuenta real del servicio instalado."
        }
    }
}
if (@(Get-ChildItem Env: | Where-Object { $_.Name -like 'LOADER_*' }).Count -gt 0) { throw 'Retire variables LOADER_* del entorno de mantenimiento.' }
if ((Get-FileHash -LiteralPath $bundle.Jar -Algorithm SHA256).Hash -cne $bundle.ArtifactSha256) { throw 'El JAR cambió tras verificar el bundle.' }
$directory = [IO.Path]::GetFullPath($MaintenanceDirectory)
if (-not [IO.Path]::IsPathRooted($directory) -or -not (Test-Path -LiteralPath $directory -PathType Container)) {
    throw 'El directorio de recuperación protegido no está disponible.'
}
$cliArgs = @('-Dloader.main=com.tpverp.backend.disasterrecovery.OfflineDisasterRecoveryCli',
    '-Dloader.config.location=classpath:META-INF/tpv-erp-release.properties','-Dloader.path=',
    '-cp',$bundle.Jar,'org.springframework.boot.loader.launch.PropertiesLauncher',
    '--action',$Action,'--directory',$directory)
if ($Action -eq 'receive') { $cliArgs += @('--recovery-id',$RecoveryId) }
& $JavaExecutable @cliArgs
if ($LASTEXITCODE -ne 0) { throw "La recuperación no se completó (código $LASTEXITCODE); conserve el journal y la cuarentena." }
