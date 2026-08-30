[CmdletBinding(SupportsShouldProcess = $true)]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ProductionRoot = 'C:\ProgramData\TPV ERP'
$DesktopRoot = Join-Path $ProductionRoot 'desktop'
$ConfigurationFile = Join-Path $DesktopRoot 'backend-config.json'

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    if (-not ([Security.Principal.WindowsPrincipal]::new($identity)).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Ejecute PowerShell como administrador.'
    }
}

function Assert-NoReparsePoint([string] $Path) {
    $resolved = [IO.Path]::GetFullPath($Path)
    $current = $resolved
    while ($true) {
        $item = Get-Item -LiteralPath $current -Force -ErrorAction Stop
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Ruta insegura: $current contiene un reparse point."
        }
        $parent = Split-Path -Parent $current
        if ($parent -eq $current) { break }
        $current = $parent
    }
}

function Assert-ProductionPath([string] $Path, [string] $ExpectedPath) {
    if (-not [string]::Equals(
        [IO.Path]::GetFullPath($Path),
        [IO.Path]::GetFullPath($ExpectedPath),
        [StringComparison]::OrdinalIgnoreCase)) {
        throw "La ruta de configuración debe ser exactamente $ExpectedPath."
    }
}

function New-ProductionRootAcl {
    $acl = New-Object System.Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    $full = [Security.AccessControl.FileSystemRights]::FullControl
    $read = [Security.AccessControl.FileSystemRights]::ReadAndExecute
    foreach ($entry in @(
        @([Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'), $full),
        @([Security.Principal.SecurityIdentifier]::new('S-1-5-18'), $full),
        @([Security.Principal.SecurityIdentifier]::new('S-1-5-11'), $read)
    )) {
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            $entry[0], $entry[1], [Security.AccessControl.InheritanceFlags]::None,
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow))
    }
    try {
        $backendServiceSid = ([Security.Principal.NTAccount]::new('NT SERVICE\TPVERPBackend')).Translate([Security.Principal.SecurityIdentifier])
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            $backendServiceSid, $read, [Security.AccessControl.InheritanceFlags]::None,
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow))
    } catch {
        Write-Warning 'No se pudo resolver NT SERVICE\TPVERPBackend; compruebe su ACL de servicio antes de aplicar.'
    }
    return $acl
}

function New-DesktopDirectoryAcl {
    $acl = New-Object System.Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    $inherit = [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit
    $full = [Security.AccessControl.FileSystemRights]::FullControl
    $read = [Security.AccessControl.FileSystemRights]::ReadAndExecute
    foreach ($entry in @(
        @([Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'), $full),
        @([Security.Principal.SecurityIdentifier]::new('S-1-5-18'), $full),
        @([Security.Principal.SecurityIdentifier]::new('S-1-5-32-545'), $read)
    )) {
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            $entry[0], $entry[1], $inherit,
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow))
    }
    return $acl
}

function New-FileAcl {
    $acl = New-Object System.Security.AccessControl.FileSecurity
    $acl.SetAccessRuleProtection($true, $false)
    $full = [Security.AccessControl.FileSystemRights]::FullControl
    $read = [Security.AccessControl.FileSystemRights]::ReadAndExecute
    foreach ($entry in @(
        @([Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'), $full),
        @([Security.Principal.SecurityIdentifier]::new('S-1-5-18'), $full),
        @([Security.Principal.SecurityIdentifier]::new('S-1-5-32-545'), $read)
    )) {
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            $entry[0], $entry[1], [Security.AccessControl.InheritanceFlags]::None,
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow))
    }
    return $acl
}

Assert-Administrator
Assert-ProductionPath $ProductionRoot 'C:\ProgramData\TPV ERP'
Assert-ProductionPath $DesktopRoot 'C:\ProgramData\TPV ERP\desktop'
Assert-ProductionPath $ConfigurationFile 'C:\ProgramData\TPV ERP\desktop\backend-config.json'
Assert-NoReparsePoint $ConfigurationFile

$root = Get-Item -LiteralPath $ProductionRoot -Force -ErrorAction Stop
$desktop = Get-Item -LiteralPath $DesktopRoot -Force -ErrorAction Stop
$config = Get-Item -LiteralPath $ConfigurationFile -Force -ErrorAction Stop
if (-not $root.PSIsContainer -or -not $desktop.PSIsContainer) {
    throw 'La cadena productiva TPV ERP\desktop debe ser directorio regular.'
}
if ($config.PSIsContainer -or (($config.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
    throw "El fichero de configuración debe ser regular y no ser un reparse point: $ConfigurationFile"
}

if ($PSCmdlet.ShouldProcess($root.FullName, 'Aplicar ACL de directorio productivo TPV ERP')) {
    Set-Acl -LiteralPath $root.FullName -AclObject (New-ProductionRootAcl)
}
if ($PSCmdlet.ShouldProcess($desktop.FullName, 'Aplicar ACL de directorio productivo desktop')) {
    Set-Acl -LiteralPath $desktop.FullName -AclObject (New-DesktopDirectoryAcl)
}
if ($PSCmdlet.ShouldProcess($config.FullName, 'Aplicar ACL de fichero de configuración Electron')) {
    Set-Acl -LiteralPath $config.FullName -AclObject (New-FileAcl)
}
Write-Output "ACL productiva aplicada a TPV ERP\desktop y backend-config.json; Users solo conserva lectura."
