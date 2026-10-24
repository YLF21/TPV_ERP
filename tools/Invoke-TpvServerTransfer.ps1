[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [ValidateSet('request','receive','activate','status','cancel')] [string] $Action,
    [Parameter(Mandatory = $true)] [string] $BundleDirectory,
    [Parameter(Mandatory = $true)] [string] $ExpectedReleaseId,
    [Parameter(Mandatory = $true)] [string] $DestinationDirectory,
    [string] $BackupFile = '',
    [string] $JavaExecutable = 'java',
    [ValidatePattern('^maintenance-result-[0-9a-f]{32}\.json$')] [string] $ResultFileName,
    [switch] $Execute
)
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
if ($Action -ne 'status' -and -not $Execute) { throw 'La operación requiere -Execute en mantenimiento autorizado.' }
if ($Action -eq 'receive' -and [string]::IsNullOrWhiteSpace($BackupFile)) { throw 'Seleccione el paquete final autorizado.' }
if ($Action -in @('request','receive','activate')) {
    foreach ($accountVariable in @('TPV_BACKEND_SERVICE_ACCOUNT','TPV_TRANSFER_HTTPS_SERVICE_ACCOUNT')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($accountVariable, 'Process'))) {
            throw "Falta ${accountVariable}: indique la cuenta real del servicio instalado antes de preparar secretos."
        }
    }
}
if (@(Get-ChildItem Env: | Where-Object { $_.Name -like 'LOADER_*' }).Count -gt 0) { throw 'Retire variables LOADER_* del entorno de mantenimiento.' }
if ((Get-FileHash -LiteralPath $bundle.Jar -Algorithm SHA256).Hash -cne $bundle.ArtifactSha256) { throw 'El JAR cambió tras verificar el bundle.' }
$destination = [IO.Path]::GetFullPath($DestinationDirectory)
if (-not [IO.Path]::IsPathRooted($destination)) { throw 'El directorio de mantenimiento debe ser absoluto.' }
if (Test-Path -LiteralPath $destination) {
    if (((Get-Item -LiteralPath $destination -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'No se permiten enlaces en el directorio de mantenimiento.' }
} else { New-Item -ItemType Directory -Path $destination -Force | Out-Null }
# Capsule and destination identity remain accessible only to the administrator and service identities.
$acl = New-Object System.Security.AccessControl.DirectorySecurity
$acl.SetAccessRuleProtection($true, $false)
$rights = [System.Security.AccessControl.FileSystemRights]::FullControl
$inherit = [System.Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'
foreach ($sidText in @('S-1-5-18','S-1-5-32-544',[Security.Principal.WindowsIdentity]::GetCurrent().User.Value)) {
    $sid = New-Object Security.Principal.SecurityIdentifier($sidText)
    $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($sid,$rights,$inherit,[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow)))
}
Set-Acl -LiteralPath $destination -AclObject $acl
$cliArgs = @('-Dloader.main=com.tpverp.backend.servertransfer.OfflineServerTransferCli',
    '-Dloader.config.location=classpath:META-INF/tpv-erp-release.properties','-Dloader.path=',
    '-cp',$bundle.Jar,'org.springframework.boot.loader.launch.PropertiesLauncher',
    '--action',$Action,'--directory',$destination)
if ($Action -eq 'receive') { $cliArgs += @('--backup',(Resolve-Path -LiteralPath $BackupFile).Path) }
if ($ResultFileName) { $cliArgs += @('--result-file', $ResultFileName) }
& $JavaExecutable @cliArgs
if ($LASTEXITCODE -ne 0) { throw "El traslado no se completó (código $LASTEXITCODE); conserve journal y bloqueo para reintentar." }
