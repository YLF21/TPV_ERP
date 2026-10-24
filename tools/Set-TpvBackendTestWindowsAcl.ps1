[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)] [string] $BundleDirectory,
    [Parameter(Mandatory)] [string] $ConfigurationFile
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'TEST_ONLY exige Windows VirtualBox.' }
$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Aplique las ACL TEST_ONLY desde PowerShell elevado.'
}
$installRoot = Join-Path $env:ProgramData 'TPV ERP\Backend'
$releasesRoot = Join-Path $installRoot 'releases'
$bundle = (Resolve-Path -LiteralPath $BundleDirectory -ErrorAction Stop).Path
if (-not [string]::Equals([IO.Path]::GetDirectoryName($bundle), $releasesRoot,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'La release TEST_ONLY no está bajo el directorio instalado esperado.'
}
$xmlPath = Join-Path $installRoot 'TPVERPBackend.xml'
$policy = & (Join-Path $PSScriptRoot 'Resolve-TpvBackendInstalledBundleVerifier.ps1') `
    -BundleDirectory $bundle -InstalledServiceXml $xmlPath
if (-not $policy.TestOnly -or $policy.Bundle.Status -cne 'TEST_ONLY_VERIFIED') {
    throw 'El servicio instalado no pertenece al canal TEST_ONLY verificado.'
}
$lab = & (Join-Path $PSScriptRoot 'Assert-TpvTestLabContext.ps1') -InstalledServiceXml $xmlPath
if (-not [string]::Equals([IO.Path]::GetFullPath($ConfigurationFile), $lab.ConfigurationFile,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'ConfigurationFile debe coincidir con la configuracion del servicio TEST_ONLY instalado.'
}

$serviceExe = Join-Path $installRoot 'TPVERPBackend.exe'
$parent = Join-Path $env:ProgramData 'TPV ERP'
$dataRoots = $lab.AclPaths
$parentPrefix = [IO.Path]::GetFullPath($parent).TrimEnd('\') + '\'
$configFull = [IO.Path]::GetFullPath($lab.ConfigurationFile)
foreach ($name in $dataRoots.Keys) {
    $path = [IO.Path]::GetFullPath([string]$dataRoots[$name])
    $backendFull = [IO.Path]::GetFullPath($installRoot)
    $httpsFull = [IO.Path]::GetFullPath((Join-Path $parent 'HTTPS'))
    if (-not $path.StartsWith($parentPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        [string]::Equals($path, $backendFull, [StringComparison]::OrdinalIgnoreCase) -or
        $path.StartsWith(($backendFull.TrimEnd('\') + '\'), [StringComparison]::OrdinalIgnoreCase) -or
        [string]::Equals($path, $httpsFull, [StringComparison]::OrdinalIgnoreCase) -or
        $path.StartsWith(($httpsFull.TrimEnd('\') + '\'), [StringComparison]::OrdinalIgnoreCase) -or
        $configFull.StartsWith(($path.TrimEnd('\') + '\'), [StringComparison]::OrdinalIgnoreCase) -or
        [string]::Equals($path, $configFull, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Ruta de datos TEST_ONLY fuera del espacio privado esperado: $name"
    }
}
$containers = @($parent, $installRoot, $releasesRoot)
$toolsDirectory = Join-Path $bundle 'tools'
$manifestPath = Join-Path $bundle 'maintenance-tools.sha256.json'
$toolManifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$allowed = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($path in @($bundle, $policy.Bundle.Jar, "$($policy.Bundle.Jar).sha256", $manifestPath, $toolsDirectory)) {
    [void]$allowed.Add([IO.Path]::GetFullPath($path))
}
foreach ($entry in $toolManifest.PSObject.Properties) {
    if ($entry.Name -notmatch '^[A-Za-z0-9_.-]+\.ps(?:1|m1)$') { throw 'Nombre de herramienta TEST_ONLY no permitido.' }
    [void]$allowed.Add([IO.Path]::GetFullPath((Join-Path $toolsDirectory $entry.Name)))
}
$code = @($serviceExe, $xmlPath, $bundle) + @(Get-ChildItem -LiteralPath $bundle -Force -Recurse | ForEach-Object FullName)
foreach ($path in $code) {
    if ($path -ne $serviceExe -and $path -ne $xmlPath -and -not $allowed.Contains([IO.Path]::GetFullPath($path))) {
        throw "La release TEST_ONLY contiene un elemento ajeno al codigo verificado: $path"
    }
}
$users = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-545')
foreach ($path in @($containers + $code)) {
    $item = Get-Item -LiteralPath $path -Force -ErrorAction Stop
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "TEST_ONLY no aplica ACL sobre enlaces: $path"
    }
}

function Add-ExplicitUsersRead([string] $Path) {
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "TEST_ONLY no aplica ACL sobre enlaces: $Path"
    }
    $acl = Get-Acl -LiteralPath $Path
    $rule = [Security.AccessControl.FileSystemAccessRule]::new($users,
        [Security.AccessControl.FileSystemRights]::ReadAndExecute,
        [Security.AccessControl.InheritanceFlags]::None,
        [Security.AccessControl.PropagationFlags]::None,
        [Security.AccessControl.AccessControlType]::Allow)
    $acl.SetAccessRule($rule)
    Set-Acl -LiteralPath $Path -AclObject $acl
}

if (-not $PSCmdlet.ShouldProcess($installRoot, 'Aplicar primero la ACL privada del servicio TEST_ONLY')) { return }
& (Join-Path $PSScriptRoot 'Set-TpvBackendWindowsAcl.ps1') -Phase Apply `
    -ConfigurationFile $lab.ConfigurationFile -KeyRoot $dataRoots.KeyRoot `
    -BackupRoot $dataRoots.BackupRoot -ProductImagesRoot $dataRoots.ProductImagesRoot `
    -DocumentTemplatesRoot $dataRoots.DocumentTemplatesRoot `
    -SecretRoot $dataRoots.FiscalSecretRoot -FiscalSecretRoot $dataRoots.FiscalSecretRoot `
    -ExportsRoot $dataRoots.FiscalExportRoot -FiscalExportRoot $dataRoots.FiscalExportRoot `
    -RestoreJournalRoot $dataRoots.RestoreJournalRoot -SkipUnconfiguredOperationalRoot
foreach ($path in $containers) { Add-ExplicitUsersRead $path }
foreach ($path in $code) { Add-ExplicitUsersRead $path }
Write-Host 'ACL TEST_ONLY: Users puede leer sólo contenedores, WinSW/XML y release inmutable; config, logs, secretos y datos siguen privados.' -ForegroundColor Green
