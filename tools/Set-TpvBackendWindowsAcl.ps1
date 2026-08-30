[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)] [ValidateSet('Register', 'Apply')] [string] $Phase,
    [string] $ServiceName = 'TPVERPBackend',
    [string] $InstallRoot = 'C:\ProgramData\TPV ERP\Backend',
    [string] $ImmutableRoot = '',
    [string] $ConfigurationFile = 'C:\ProgramData\TPV ERP\backend.properties',
    [string] $SecretRoot = 'C:\ProgramData\TPV ERP\secrets',
    [string] $LogsRoot = '',
    [string] $ExportsRoot = 'C:\ProgramData\TPV ERP\exports',
    [string] $OperationalRoot = 'C:\ProgramData\TPV ERP\operational',
    [string] $BackupRoot = 'C:\ProgramData\TPV ERP\Backups',
    [string] $ProductImagesRoot = 'C:\ProgramData\TPV ERP\product-images',
    [string] $DocumentTemplatesRoot = 'C:\ProgramData\TPV ERP\document-templates',
    [string] $RestoreJournalRoot = 'C:\ProgramData\TPV ERP\restore'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    if (-not ([Security.Principal.WindowsPrincipal]::new($identity)).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Ejecute PowerShell como administrador.' }
}
function Resolve-ServiceSid {
    $service = Get-CimInstance Win32_Service -Filter "Name = '$ServiceName'"
    if ($null -eq $service) { throw "El servicio $ServiceName debe registrarse antes de aplicar ACL." }
    if ([string]$service.StartName -ne "NT SERVICE\$ServiceName") {
        throw "El servicio $ServiceName no usa la cuenta virtual NT SERVICE\$ServiceName; no se aplican ACL parciales."
    }
    $account = [Security.Principal.NTAccount]::new('NT SERVICE\' + $ServiceName)
    try { return $account.Translate([Security.Principal.SecurityIdentifier]) }
    catch { throw "No se pudo resolver la cuenta virtual NT SERVICE\$ServiceName despues del registro." }
}
function Ensure-Directory([string] $Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { throw 'Ruta ACL vacia.' }
    New-Item -ItemType Directory -LiteralPath $Path -Force | Out-Null
    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Ruta ACL insegura: $Path" }
    return $item.FullName
}
function Set-TreeAcl([string] $Path, [Security.Principal.SecurityIdentifier] $ServiceSid,
    [Security.AccessControl.FileSystemRights] $ServiceRights, [switch] $RequireFile) {
    $existing = Get-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
    if ($null -eq $existing) {
        if ($RequireFile) { throw "El fichero de configuracion no existe; aprovisionelo antes de aplicar ACL: $Path" }
        $resolved = Ensure-Directory $Path
    } else {
        if (($existing.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Ruta ACL insegura: $Path" }
        $resolved = $existing.FullName
    }
    $admin = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    $system = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    $rootItem = Get-Item -LiteralPath $resolved -Force
    $items = if ($rootItem.PSIsContainer) {
        @($rootItem) + @(Get-ChildItem -LiteralPath $resolved -Force -Recurse)
    } else { @($rootItem) }
    foreach ($item in $items) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Ruta ACL insegura: $($item.FullName)" }
        $acl = if ($item.PSIsContainer) {
            New-Object Security.AccessControl.DirectorySecurity
        } else {
            New-Object Security.AccessControl.FileSecurity
        }
        $acl.SetAccessRuleProtection($true, $false)
        $inherit = if ($item.PSIsContainer) {
            [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit
        } else { [Security.AccessControl.InheritanceFlags]::None }
        $prop = [Security.AccessControl.PropagationFlags]::None
        foreach ($entry in @(
            @($admin, [Security.AccessControl.FileSystemRights]::FullControl),
            @($system, [Security.AccessControl.FileSystemRights]::FullControl),
            @($ServiceSid, $ServiceRights))) {
            $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
                $entry[0], $entry[1], $inherit, $prop, [Security.AccessControl.AccessControlType]::Allow))
        }
        if ($PSCmdlet.ShouldProcess($item.FullName, "Aplicar ACL $ServiceRights")) { Set-Acl -LiteralPath $item.FullName -AclObject $acl }
    }
}

Assert-Administrator
if ($Phase -eq 'Register') {
    if ($null -eq (Get-CimInstance Win32_Service -Filter "Name = '$ServiceName'")) {
        throw "Fase Register: registre $ServiceName con Install-TpvBackendWindowsService.ps1 y vuelva a ejecutar -Phase Apply."
    }
    Write-Output "Servicio $ServiceName detectado. La cuenta virtual ya puede resolverse; ejecute -Phase Apply."
    return
}
$serviceSid = Resolve-ServiceSid
$immutable = if ([string]::IsNullOrWhiteSpace($ImmutableRoot)) { Join-Path $InstallRoot 'releases' } else { $ImmutableRoot }
$logs = if ([string]::IsNullOrWhiteSpace($LogsRoot)) { Join-Path $InstallRoot 'logs' } else { $LogsRoot }
Set-TreeAcl $immutable $serviceSid ([Security.AccessControl.FileSystemRights]::ReadAndExecute)
Set-TreeAcl $ConfigurationFile $serviceSid ([Security.AccessControl.FileSystemRights]::Read) -RequireFile
Set-TreeAcl $SecretRoot $serviceSid ([Security.AccessControl.FileSystemRights]::ReadAndExecute)
Set-TreeAcl $InstallRoot $serviceSid ([Security.AccessControl.FileSystemRights]::ReadAndExecute)
Set-TreeAcl $logs $serviceSid ([Security.AccessControl.FileSystemRights]::Modify)
Set-TreeAcl $ExportsRoot $serviceSid ([Security.AccessControl.FileSystemRights]::Modify)
Set-TreeAcl $OperationalRoot $serviceSid ([Security.AccessControl.FileSystemRights]::Modify)
Set-TreeAcl $BackupRoot $serviceSid ([Security.AccessControl.FileSystemRights]::Modify)
Set-TreeAcl $ProductImagesRoot $serviceSid ([Security.AccessControl.FileSystemRights]::Modify)
Set-TreeAcl $DocumentTemplatesRoot $serviceSid ([Security.AccessControl.FileSystemRights]::Modify)
Set-TreeAcl $RestoreJournalRoot $serviceSid ([Security.AccessControl.FileSystemRights]::Modify)
Write-Output "ACL de dos fases aplicada para NT SERVICE\$ServiceName (RX en instalación/inmutables/config/secrets; Modify en logs/exports/operacional/backup/imágenes/plantillas/restore)."
