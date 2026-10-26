[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [ValidateSet('request','receive','activate','status','protect-secrets','reconcile-license')] [string] $Action,
    [Parameter(Mandatory = $true)] [string] $BundleDirectory,
    [Parameter(Mandatory = $true)] [string] $ExpectedReleaseId,
    [Parameter(Mandatory = $true)] [string] $MaintenanceDirectory,
    [string] $RecoveryId = '',
    [string] $JavaExecutable = 'java',
    [ValidatePattern('^maintenance-result-[0-9a-f]{32}\.json$')] [string] $ResultFileName,
    [switch] $Execute
)
function Invoke-TpvRecoveryJava([string] $Executable, [string[]] $Arguments) {
    $previousPreference = $ErrorActionPreference
    $nativeExitCode = $null
    $global:LASTEXITCODE = $null
    try {
        # Windows PowerShell 5.1 turns redirected native stderr into ErrorRecords.
        # Preserve those diagnostics, but decide success from this invocation's exit code.
        $ErrorActionPreference = 'Continue'
        & $Executable @Arguments
        $nativeExitCode = $global:LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($null -eq $nativeExitCode) {
        throw 'Java no devolvio un codigo de salida; conserve el journal y revise el diagnostico de arranque.'
    }
    if ($nativeExitCode -ne 0) {
        throw "La recuperacion no se completo (codigo $nativeExitCode); conserve el journal y la cuarentena."
    }
}

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
try {
    $bundle = & (Join-Path $PSScriptRoot 'Test-TpvBackendProductionBundle.ps1') `
        -BundleDirectory $BundleDirectory -ExpectedReleaseId $ExpectedReleaseId -AsObject
} catch {
    if ($_.Exception.Message -notmatch 'marcador TEST_ONLY') { throw }
    $policy = & (Join-Path $PSScriptRoot 'Resolve-TpvBackendInstalledBundleVerifier.ps1') -BundleDirectory $BundleDirectory
    if (-not $policy.TestOnly -or $policy.Bundle.ReleaseId -cne $ExpectedReleaseId) {
        throw 'La release TEST_ONLY instalada no coincide con ExpectedReleaseId.'
    }
    & (Join-Path $PSScriptRoot 'Assert-TpvTestLabContext.ps1') `
        -InstalledServiceXml (Join-Path $env:ProgramData 'TPV ERP\Backend\TPVERPBackend.xml') -ApplyCliEnvironment | Out-Null
    $bundle = $policy.Bundle
}
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
if ($Action -in @('protect-secrets','reconcile-license') -and
    [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('TPV_BACKEND_SERVICE_ACCOUNT', 'Process'))) {
    throw 'Falta TPV_BACKEND_SERVICE_ACCOUNT: indique la cuenta real del servicio instalado.'
}
if (@(Get-ChildItem Env: | Where-Object { $_.Name -like 'LOADER_*' }).Count -gt 0) { throw 'Retire variables LOADER_* del entorno de mantenimiento.' }
if ((Get-FileHash -LiteralPath $bundle.Jar -Algorithm SHA256).Hash -cne $bundle.ArtifactSha256) { throw 'El JAR cambió tras verificar el bundle.' }
$directory = [IO.Path]::GetFullPath($MaintenanceDirectory)
if (-not [IO.Path]::IsPathRooted($directory) -or -not (Test-Path -LiteralPath $directory -PathType Container)) {
    throw 'El directorio de recuperación protegido no está disponible.'
}
$cliArgs = @('--enable-native-access=ALL-UNNAMED',
    '-Dloader.main=com.tpverp.backend.disasterrecovery.OfflineDisasterRecoveryCli',
    '-Dloader.config.location=classpath:META-INF/tpv-erp-release.properties','-Dloader.path=',
    '-cp',$bundle.Jar,'org.springframework.boot.loader.launch.PropertiesLauncher',
    '--action',$Action,'--directory',$directory)
if ($Action -eq 'receive') { $cliArgs += @('--recovery-id',$RecoveryId) }
if ($ResultFileName) { $cliArgs += @('--result-file', $ResultFileName) }
Invoke-TpvRecoveryJava -Executable $JavaExecutable -Arguments $cliArgs
