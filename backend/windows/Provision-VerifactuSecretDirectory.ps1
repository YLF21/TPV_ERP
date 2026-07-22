[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ExpectedDirectory = 'C:\ProgramData\TPV ERP\secrets\verifactu'
$ExpectedServiceName = 'TPVERPBackend'
$ExpectedServiceIdentity = 'NT SERVICE\TPVERPBackend'
$AllowedSystemSid = 'S-1-5-18'
$AllowedAdministratorsSid = 'S-1-5-32-544'

function Assert-WindowsAdministrator {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        throw 'Este script solo puede ejecutarse en Windows.'
    }

    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Ejecute PowerShell como administrador.'
    }
}

function Get-NormalizedPath([string] $Path) {
    return [IO.Path]::GetFullPath($Path).TrimEnd([IO.Path]::DirectorySeparatorChar)
}

function Assert-ExactTarget([string] $Path) {
    $normalized = Get-NormalizedPath $Path
    $expected = Get-NormalizedPath $ExpectedDirectory
    if (-not [StringComparer]::OrdinalIgnoreCase.Equals($normalized, $expected)) {
        throw "Directorio no autorizado: $normalized"
    }
    if ([IO.Path]::GetPathRoot($normalized) -eq $normalized) {
        throw 'El directorio de secretos no puede ser la raiz de una unidad.'
    }
    return $normalized
}

function Assert-NoReparsePoint([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "No se permite un enlace, junction o reparse point: $Path"
    }
}

function Assert-SafeExistingAncestors([string] $Path) {
    $expected = Get-NormalizedPath $Path
    $segments = @('C:\ProgramData', 'C:\ProgramData\TPV ERP',
        'C:\ProgramData\TPV ERP\secrets', $expected)
    foreach ($segment in $segments) {
        Assert-NoReparsePoint $segment
    }
}

function Get-ValidatedTree([string] $Root) {
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) {
        return @()
    }

    $prefix = $Root + [IO.Path]::DirectorySeparatorChar
    $items = @(Get-ChildItem -LiteralPath $Root -Force -Recurse)
    foreach ($item in $items) {
        $fullName = Get-NormalizedPath $item.FullName
        if (-not $fullName.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Elemento fuera del directorio autorizado: $fullName"
        }
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "No se permite un enlace, junction o reparse point: $fullName"
        }
    }
    return $items
}

function Resolve-Sid([string] $Identity) {
    try {
        return [Security.Principal.NTAccount]::new($Identity).Translate(
            [Security.Principal.SecurityIdentifier])
    } catch {
        throw "No se pudo resolver la identidad de Windows '$Identity'. Registre primero el servicio $ExpectedServiceName."
    }
}

function New-StrictFileSystemAcl(
    [bool] $IsDirectory,
    [Security.Principal.SecurityIdentifier[]] $AllowedSids
) {
    $acl = if ($IsDirectory) {
        [Security.AccessControl.DirectorySecurity]::new()
    } else {
        [Security.AccessControl.FileSecurity]::new()
    }
    $acl.SetAccessRuleProtection($true, $false)
    $acl.SetOwner([Security.Principal.SecurityIdentifier]::new($AllowedAdministratorsSid))

    $inheritance = if ($IsDirectory) {
        [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
            [Security.AccessControl.InheritanceFlags]::ObjectInherit
    } else {
        [Security.AccessControl.InheritanceFlags]::None
    }

    foreach ($sid in $AllowedSids) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            $sid,
            [Security.AccessControl.FileSystemRights]::FullControl,
            $inheritance,
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow)
        [void] $acl.AddAccessRule($rule)
    }
    return $acl
}

function Assert-StrictFileSystemAcl(
    [string] $Path,
    [bool] $IsDirectory,
    [string[]] $AllowedSidValues
) {
    $acl = Get-Acl -LiteralPath $Path
    if (-not $acl.AreAccessRulesProtected) {
        throw "La herencia ACL sigue activa en: $Path"
    }
    $ownerSid = ([Security.Principal.NTAccount]::new($acl.Owner)).Translate(
        [Security.Principal.SecurityIdentifier]).Value
    if ($ownerSid -ne $AllowedAdministratorsSid) {
        throw "El propietario ACL no es Administrators en: $Path"
    }

    $rules = @($acl.Access)
    if ($rules.Count -ne $AllowedSidValues.Count) {
        throw "La ACL contiene reglas no autorizadas en: $Path"
    }

    $expectedInheritance = if ($IsDirectory) {
        [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
            [Security.AccessControl.InheritanceFlags]::ObjectInherit
    } else {
        [Security.AccessControl.InheritanceFlags]::None
    }
    foreach ($rule in $rules) {
        $sid = $rule.IdentityReference.Translate(
            [Security.Principal.SecurityIdentifier]).Value
        if ($sid -notin $AllowedSidValues -or
                $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
                $rule.IsInherited -or
                $rule.InheritanceFlags -ne $expectedInheritance -or
                $rule.PropagationFlags -ne [Security.AccessControl.PropagationFlags]::None -or
                ($rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -ne
                    [Security.AccessControl.FileSystemRights]::FullControl) {
            throw "La ACL contiene una regla no autorizada en: $Path"
        }
    }
}

Assert-WindowsAdministrator
$secretDirectory = Assert-ExactTarget $ExpectedDirectory
Assert-SafeExistingAncestors $secretDirectory
[void] @(Get-ValidatedTree $secretDirectory)

$service = Get-CimInstance -ClassName Win32_Service -Filter "Name = '$ExpectedServiceName'"
if ($null -eq $service) {
    throw "El servicio $ExpectedServiceName no esta registrado. Registrelo antes de aprovisionar los secretos."
}
if (-not [StringComparer]::OrdinalIgnoreCase.Equals($service.StartName, $ExpectedServiceIdentity)) {
    throw "El servicio debe ejecutarse como $ExpectedServiceIdentity y actualmente usa $($service.StartName)."
}
if ($service.State -ne 'Stopped') {
    throw "Detenga el servicio $ExpectedServiceName antes de cambiar las ACL."
}

$serviceSid = Resolve-Sid $ExpectedServiceIdentity
$systemSid = [Security.Principal.SecurityIdentifier]::new($AllowedSystemSid)
$administratorsSid = [Security.Principal.SecurityIdentifier]::new($AllowedAdministratorsSid)
$allowedSids = [Security.Principal.SecurityIdentifier[]] @(
    $serviceSid,
    $systemSid,
    $administratorsSid
)
$allowedSidValues = [string[]] @(
    $serviceSid.Value,
    $systemSid.Value,
    $administratorsSid.Value
)

if (-not $PSCmdlet.ShouldProcess(
        $secretDirectory,
        "Crear el directorio y limitarlo a $ExpectedServiceIdentity, SYSTEM y Administrators")) {
    return
}

New-Item -ItemType Directory -Path $secretDirectory -Force | Out-Null
Assert-SafeExistingAncestors $secretDirectory
$allItems = @(Get-ValidatedTree $secretDirectory) +
    @(Get-Item -LiteralPath $secretDirectory -Force)
$allItems = $allItems | Sort-Object { $_.FullName.Length } -Descending

foreach ($item in $allItems) {
    $isDirectory = $item.PSIsContainer
    $acl = New-StrictFileSystemAcl $isDirectory $allowedSids
    Set-Acl -LiteralPath $item.FullName -AclObject $acl
}

$validatedItems = @(Get-ValidatedTree $secretDirectory) +
    @(Get-Item -LiteralPath $secretDirectory -Force)
foreach ($item in $validatedItems) {
    Assert-StrictFileSystemAcl $item.FullName $item.PSIsContainer $allowedSidValues
}

Write-Host "Directorio VeriFactu aprovisionado correctamente: $secretDirectory"
Write-Host "Identidad autorizada del servicio: $ExpectedServiceIdentity"
