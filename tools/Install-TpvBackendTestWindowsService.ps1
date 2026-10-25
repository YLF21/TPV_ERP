[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)] [string] $BundleDirectory,
    [Parameter(Mandatory)] [string] $WinSwExecutable,
    [Parameter(Mandatory)] [ValidatePattern('^[0-9a-fA-F]{64}$')] [string] $WinSwSha256,
    [Parameter(Mandatory)] [string] $JavaExecutable,
    [Parameter(Mandatory)] [string] $ConfigurationFile,
    [string] $InstallRoot = 'C:\ProgramData\TPV ERP\Backend',
    [string] $ServiceName = 'TPVERPBackend',
    [ValidateSet('VirtualService', 'LocalSystem', 'LocalService', 'NetworkService')]
    [string] $ServiceAccount = 'VirtualService',
    [string] $ListenAddress = '127.0.0.1',
    [ValidateRange(1, 65535)] [int] $Port = 8080,
    [string] $PublicUrl = '',
    [string] $ExpectedVersion,
    [Parameter(Mandatory)] [string] $ExpectedReleaseId,
    [string] $ExpectedSchemaVersion,
    [Nullable[long]] $ExpectedReleaseSequence,
    [Nullable[long]] $ExpectedBuildSequence,
    [string] $SecretDirectory = 'C:\ProgramData\TPV ERP\secrets\verifactu',
    [string] $ExportDirectory = 'C:\ProgramData\TPV ERP\exports\fiscal',
    [ValidateSet('Register', 'Update', 'Start')] [string] $Phase = 'Register',
    [switch] $Preflight
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-SafeExpectedSegment([string] $Value, [string] $Name) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' -or
        $Value -in @('.', '..')) {
        throw "$Name debe ser un segmento seguro sin separadores de ruta"
    }
}

Assert-SafeExpectedSegment $ExpectedReleaseId 'ExpectedReleaseId'
if ($ServiceName -cne 'TPVERPBackend' -or $ServiceAccount -cne 'VirtualService' -or
    -not [string]::Equals([IO.Path]::GetFullPath($InstallRoot), 'C:\ProgramData\TPV ERP\Backend',
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'TEST_ONLY exige TPVERPBackend en C:\ProgramData\TPV ERP\Backend dentro de la VM.'
}
foreach ($key in @('ExpectedVersion', 'ExpectedSchemaVersion')) {
    if ($PSBoundParameters.ContainsKey($key)) { Assert-SafeExpectedSegment $PSBoundParameters[$key] $key }
}
if (($null -ne $ExpectedReleaseSequence -and $ExpectedReleaseSequence -lt 0) -or
    ($null -ne $ExpectedBuildSequence -and $ExpectedBuildSequence -lt 0)) {
    throw 'ExpectedReleaseSequence y ExpectedBuildSequence deben ser no negativos'
}

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$verifier = Join-Path $PSScriptRoot 'Test-TpvBackendTestBundle.ps1'

if ($ServiceName -notmatch '^[A-Za-z0-9]{1,80}$') {
    throw 'ServiceName debe ser un ID WinSW alfanumerico (1-80 caracteres).'
}

function Assert-TpvWinSw3([string] $Path) {
    # Read PE metadata only: preflight must not register or execute the wrapper.
    if ((Get-Item -LiteralPath $Path).VersionInfo.FileMajorPart -ne 3) {
        throw 'Se requiere WinSW 3; WinSW 2 ignora la identidad username del XML.'
    }
}

function Assert-TpvWinSwStartupPolicy([xml] $Definition) {
    # WinSW 3 alpha.11 AutoRefresh requests SERVICE_ALL_ACCESS as the service
    # account before ServiceBase.Run. Only the elevated installer may refresh SCM.
    $nodes = $Definition.SelectNodes('/service/autoRefresh')
    if ($nodes.Count -ne 1 -or $nodes[0].InnerText -cne 'false') {
        throw 'WinSW exige autoRefresh=false; vuelva a registrar con el instalador aprobado antes de arrancar.'
    }
}

function Assert-TpvTestOnlyUpdateTransition($Previous, $Candidate) {
    if ($Previous.Status -cne 'TEST_ONLY_VERIFIED' -or $Candidate.Status -cne 'TEST_ONLY_VERIFIED') {
        throw 'Update exige releases TEST_ONLY verificadas antes y despues.'
    }
    if ($Previous.ReleaseId -ceq $Candidate.ReleaseId) {
        if ($Previous.ArtifactSha256 -cne $Candidate.ArtifactSha256) {
            throw 'La misma release inmutable tiene otro hash.'
        }
        throw 'Update exige una release nueva; la release actual ya esta instalada.'
    }
    $oldSchema = [int]([Regex]::Match([string]$Previous.SchemaVersion, '^V([0-9]+)$').Groups[1].Value)
    $newSchema = [int]([Regex]::Match([string]$Candidate.SchemaVersion, '^V([0-9]+)$').Groups[1].Value)
    if ([version]$Candidate.Version -lt [version]$Previous.Version -or
        $newSchema -lt $oldSchema -or
        [long]$Candidate.ReleaseSequence -lt [long]$Previous.ReleaseSequence -or
        ([long]$Candidate.ReleaseSequence -eq [long]$Previous.ReleaseSequence -and
         [long]$Candidate.BuildSequence -lt [long]$Previous.BuildSequence)) {
        throw 'Update no permite downgrade de version, esquema o secuencia.'
    }
}

function Assert-TpvScmIdentity([string] $Id, [string] $Executable, [string] $Account,
    [switch] $Stopped, [string] $StartMode = '') {
    $registered = Get-CimInstance -ClassName Win32_Service -Filter "Name = '$Id'"
    if ($null -eq $registered -or
        -not [StringComparer]::OrdinalIgnoreCase.Equals(([string]$registered.PathName).Trim('"'), $Executable) -or
        -not [StringComparer]::OrdinalIgnoreCase.Equals([string]$registered.StartName, $Account) -or
        ($Stopped -and $registered.State -ne 'Stopped') -or
        ($StartMode -and $registered.StartMode -ne $StartMode)) {
        throw "La identidad, ruta o estado SCM de $Id no coincide con el registro aprobado."
    }
}

function Remove-TpvNewRegistration([string] $Id, [string] $Executable) {
    $registered = Get-CimInstance -ClassName Win32_Service -Filter "Name = '$Id'"
    if ($null -eq $registered) { return }
    if (-not [StringComparer]::OrdinalIgnoreCase.Equals(([string]$registered.PathName).Trim('"'), $Executable)) {
        throw "No se elimina ${Id}: la ruta SCM no pertenece a este registro."
    }
    # Leave it disabled even if deletion fails; never silently retain LocalSystem.
    $disabled = Invoke-CimMethod -InputObject $registered -MethodName ChangeStartMode -Arguments @{StartMode='Disabled'}
    if ($null -eq $disabled -or $disabled.ReturnValue -ne 0) { throw "No se pudo deshabilitar el registro fallido $Id." }
    if ($registered.State -ne 'Stopped') {
        $stopped = Invoke-CimMethod -InputObject $registered -MethodName StopService
        if ($null -eq $stopped -or $stopped.ReturnValue -notin @(0, 6)) { throw "No se pudo detener el registro fallido $Id." }
    }
    $deleted = Invoke-CimMethod -InputObject $registered -MethodName Delete
    if ($null -eq $deleted -or $deleted.ReturnValue -ne 0) { throw "No se pudo eliminar $Id; permanece deshabilitado." }
}

function Register-TpvNewService([string] $Executable, [string] $XmlPath, [string] $Id, [string] $Account) {
    if ($null -ne (Get-CimInstance -ClassName Win32_Service -Filter "Name = '$Id'")) {
        throw "El servicio $Id ya existe; no se adopta durante un registro nuevo."
    }
    $canonical = [IO.File]::ReadAllText($XmlPath)
    $definition = [xml]::new()
    $definition.XmlResolver = $null
    $definition.LoadXml($canonical)
    Assert-TpvWinSwStartupPolicy $definition
    if ([string]$definition.service.id -cne $Id -or [string]$definition.service.startmode -cne 'Manual' -or
        [string]$definition.service.serviceaccount.username -cne $Account) {
        throw 'El XML no declara el registro manual y la identidad aprobados.'
    }
    $virtual = $Account -ceq "NT SERVICE\$Id"
    try {
        if ($virtual) {
            # WinSW 3 resolves the virtual SID before CreateService, and XML wins
            # over --user. Bootstrap DISABLED, then assign the virtual identity via SCM.
            $definition.service.serviceaccount.username = 'LocalSystem'
            $definition.service.startmode = 'Disabled'
            try {
                [IO.File]::WriteAllText($XmlPath, $definition.OuterXml, [Text.UTF8Encoding]::new($false))
                & $Executable install --no-elevate
                if ($LASTEXITCODE -ne 0) { throw 'WinSW no pudo registrar el servicio deshabilitado.' }
            } finally {
                [IO.File]::WriteAllText($XmlPath, $canonical, [Text.UTF8Encoding]::new($false))
            }
            Assert-TpvScmIdentity $Id $Executable 'LocalSystem' -Stopped -StartMode Disabled
            $registered = Get-CimInstance -ClassName Win32_Service -Filter "Name = '$Id'"
            $changed = Invoke-CimMethod -InputObject $registered -MethodName Change -Arguments @{
                StartName=$Account; StartPassword=$null; StartMode='Manual'
            }
            if ($null -eq $changed) { throw "SCM no devolvio resultado al asignar la cuenta virtual de $Id." }
            if ($changed.ReturnValue -ne 0) { throw "SCM no pudo asignar la cuenta virtual de ${Id}: ReturnValue=$($changed.ReturnValue)." }
        } else {
            & $Executable install --no-elevate
            if ($LASTEXITCODE -ne 0) { throw 'WinSW no pudo registrar el servicio.' }
        }
        Assert-TpvScmIdentity $Id $Executable $Account -Stopped -StartMode Manual
    } catch {
        $failure = $_
        Remove-TpvNewRegistration $Id $Executable
        throw $failure
    }
}

function Assert-Administrator {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        throw 'Este instalador solo puede ejecutarse en Windows.'
    }
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Ejecute PowerShell como administrador.'
    }
}

function Resolve-ExistingFile([string] $Path, [string] $Description) {
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) { throw "$Description no es un archivo: $resolved" }
    return $resolved
}

function ConvertTo-XmlText([string] $Value) {
    return [Security.SecurityElement]::Escape($Value)
}

function ConvertTo-FileUri([string] $Path) {
    $uri = [Uri]::new([IO.Path]::GetFullPath($Path))
    if (-not $uri.IsFile) { throw "La configuracion no produce una URI file valida: $Path" }
    return $uri.AbsoluteUri
}

function Assert-LoopbackAddress([string] $Address) {
    $parsed = $null
    if (-not [IPAddress]::TryParse($Address, [ref]$parsed) -or -not [IPAddress]::IsLoopback($parsed)) {
        throw "ListenAddress debe ser una direccion loopback literal (127.0.0.1 o ::1), no '$Address'."
    }
}

function Assert-BackendPublicUrl([string] $Value) {
    if ([string]::IsNullOrEmpty($Value)) { return }
    $uri = $null
    if ($Value.Length -gt 512 -or -not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne 'https' -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or
        $uri.AbsolutePath -ne '/' -or $uri.AbsoluteUri.TrimEnd('/') -cne $Value) {
        throw 'PublicUrl debe ser un origen HTTPS normalizado, sin credenciales, ruta, consulta ni fragmento.'
    }
}

function Get-ServiceAccountSid([string] $Account) {
    if ($Account -eq 'VirtualService') {
        $existing = Get-CimInstance -ClassName Win32_Service -Filter "Name = '$ServiceName'"
        if ($null -eq $existing) { return $null }
        try { return ([Security.Principal.NTAccount]::new("NT SERVICE\$ServiceName")).Translate([Security.Principal.SecurityIdentifier]) }
        catch { throw "No se pudo resolver la cuenta virtual NT SERVICE\$ServiceName." }
    }
    $sidValue = switch ($Account) {
        'LocalSystem' { 'S-1-5-18' }
        'LocalService' { 'S-1-5-19' }
        'NetworkService' { 'S-1-5-20' }
    }
    return [Security.Principal.SecurityIdentifier]::new($sidValue)
}

function Assert-SafeRegularFile([string] $Path, [string] $Description) {
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    $item = Get-Item -LiteralPath $resolved -Force
    if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Description debe ser un archivo regular sin reparse point: $resolved"
    }
    $current = $resolved
    while ($true) {
        $ancestor = Get-Item -LiteralPath $current -Force
        if (($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "La ruta de $Description contiene un reparse point: $current"
        }
        $parent = [IO.Path]::GetDirectoryName($current)
        if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $current) { break }
        $current = $parent
    }
    return $resolved
}

function Assert-SafeDirectoryTree([string] $Path, [string] $Description) {
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    $rootItem = Get-Item -LiteralPath $resolved -Force
    if (-not $rootItem.PSIsContainer -or ($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Description debe ser un directorio regular sin reparse point: $resolved"
    }
    $current = $resolved
    while ($true) {
        $ancestor = Get-Item -LiteralPath $current -Force
        if (($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "La ruta de $Description contiene un reparse point: $current"
        }
        $parent = [IO.Path]::GetDirectoryName($current)
        if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $current) { break }
        $current = $parent
    }
    foreach ($item in @(Get-ChildItem -LiteralPath $resolved -Force -Recurse)) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Description contiene un reparse point: $($item.FullName)"
        }
    }
    return $resolved
}

function Assert-SafePlannedPath([string] $Path, [string] $Description,
    [ValidateSet('Any', 'Directory', 'File')] [string] $ExpectedType = 'Any') {
    $full = [IO.Path]::GetFullPath($Path)
    $current = $full
    while ($true) {
        $item = Get-Item -LiteralPath $current -Force -ErrorAction SilentlyContinue
        if ($null -ne $item) {
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "$Description contiene un reparse point: $current"
            }
            if ($current -eq $full) {
                if ($ExpectedType -eq 'Directory' -and -not $item.PSIsContainer) {
                    throw "$Description debe ser un directorio: $full"
                }
                if ($ExpectedType -eq 'File' -and $item.PSIsContainer) {
                    throw "$Description debe ser un archivo regular: $full"
                }
            }
        }
        $parent = [IO.Path]::GetDirectoryName($current)
        if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $current) { break }
        $current = $parent
    }
}

function Assert-ServiceCanRead([string] $Path, [Security.Principal.SecurityIdentifier] $ServiceSid, [string] $Description) {
    $acl = Get-Acl -LiteralPath $Path
    $sidValue = $ServiceSid.Value
    $matching = @($acl.Access | Where-Object {
        try { $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -eq $sidValue } catch { $false }
    })
    $readMask = [Security.AccessControl.FileSystemRights]::Read
    if (@($matching | Where-Object {
        $_.AccessControlType -eq [Security.AccessControl.AccessControlType]::Deny -and
        (($_.FileSystemRights -band $readMask) -ne 0)
    }).Count -gt 0) {
        throw "$Description tiene un denegado ACL de lectura para la cuenta del servicio: $Path"
    }
    if (@($matching | Where-Object {
        $_.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
        (($_.FileSystemRights -band $readMask) -ne 0)
    }).Count -eq 0) {
        throw "Falta una regla ACL directa de lectura para la cuenta del servicio en ${Description}: $Path"
    }
}

function Assert-SecretTreeReadable([string] $Path, [Security.Principal.SecurityIdentifier] $ServiceSid) {
    [void] (Assert-SafeDirectoryTree $Path 'El directorio de secretos')
    Assert-ServiceCanRead $Path $ServiceSid 'El directorio de secretos'
    foreach ($item in @(Get-ChildItem -LiteralPath $Path -Force -Recurse)) {
        Assert-ServiceCanRead $item.FullName $ServiceSid 'Un secreto'
    }
}

function Assert-RestrictedExportAcl([string] $Path, [Security.Principal.SecurityIdentifier] $ServiceSid) {
    Assert-RestrictedTreeAcl $Path $ServiceSid ([Security.AccessControl.FileSystemRights]::Modify)
}

function Assert-RestrictedTreeAcl([string] $Path, [Security.Principal.SecurityIdentifier] $ServiceSid,
    [Security.AccessControl.FileSystemRights] $ServiceRights, [switch] $AllowInteractiveRead) {
    if ($null -eq $ServiceSid) { throw 'Registre primero el servicio detenido antes de verificar sus ACL.' }
    $allowed = @($ServiceSid.Value, 'S-1-5-18', 'S-1-5-32-544') | Select-Object -Unique
    if ($AllowInteractiveRead) { $allowed += 'S-1-5-32-545' }
    $required = @($ServiceSid.Value, 'S-1-5-18', 'S-1-5-32-544') | Select-Object -Unique
    $targets = @((Get-Item -LiteralPath $Path -Force)) +
        @(Get-ChildItem -LiteralPath $Path -Force -Recurse)
    foreach ($target in $targets) {
        $acl = Get-Acl -LiteralPath $target.FullName
        # New operational files may inherit only the verified root's closed ACL.
        # Secrets retain the stricter Java contract: every object is protected.
        if (-not $acl.AreAccessRulesProtected -and
            ($target.FullName -eq (Get-Item -LiteralPath $Path -Force).FullName -or
             $ServiceRights -eq [Security.AccessControl.FileSystemRights]::FullControl)) {
            throw "La ACL raiz (o secreto) no puede heredar reglas: $($target.FullName)"
        }
        foreach ($rule in @($acl.Access)) {
            $ruleSid = try {
                $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
            } catch { throw "No se pudo resolver una identidad ACL en exportaciones: $($target.FullName)" }
            $requiredRights = if ($AllowInteractiveRead -and $ruleSid -eq 'S-1-5-32-545') {
                [Security.AccessControl.FileSystemRights]::ReadAndExecute -bor [Security.AccessControl.FileSystemRights]::Synchronize
            } elseif ($ruleSid -eq $ServiceSid.Value -and $ruleSid -notin @('S-1-5-18', 'S-1-5-32-544')) {
                $ServiceRights -bor [Security.AccessControl.FileSystemRights]::Synchronize
            } else { [Security.AccessControl.FileSystemRights]::FullControl }
            if ($ruleSid -notin $allowed -or
                $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
                $rule.FileSystemRights -ne $requiredRights) {
                throw "La ACL contiene una regla no autorizada o derechos divergentes: $($target.FullName)"
            }
            if ($AllowInteractiveRead -and $ruleSid -eq 'S-1-5-32-545' -and
                ($rule.IsInherited -or
                 $rule.InheritanceFlags -ne [Security.AccessControl.InheritanceFlags]::None -or
                 $rule.PropagationFlags -ne [Security.AccessControl.PropagationFlags]::None)) {
                throw "UsersRX debe ser explicito y sin herencia: $($target.FullName)"
            }
        }
        $present = @($acl.Access | ForEach-Object {
            try { $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value } catch { $null }
        })
        foreach ($requiredSid in $required) {
            if ($requiredSid -notin $present) {
                throw "Falta la identidad requerida en la ACL de exportaciones ($requiredSid): $($target.FullName)"
            }
        }
    }
}

function Assert-Java25([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "No se pudo ejecutar Java para comprobar la version: $Path"
    }
    $previousErrorAction = $ErrorActionPreference
    try {
        # Windows PowerShell 5.1 reports native stderr as NativeCommandError under Stop.
        $ErrorActionPreference = 'Continue'
        $versionOutput = ((& $Path -version 2>&1 | ForEach-Object { $_.ToString() }) -join "`n").Trim()
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($null -eq $exitCode -or $exitCode -ne 0) {
        throw "No se pudo ejecutar Java para comprobar la version: $Path (codigo $exitCode): $versionOutput"
    }
    $match = [Regex]::Match($versionOutput, '(?m)(?:version\s+"|openjdk\s+|java\s+)(\d+)')
    if (-not $match.Success -or [int]$match.Groups[1].Value -ne 25) {
        throw "El backend productivo exige Java 25; se obtuvo: $versionOutput"
    }
}

Assert-Administrator
$lab = & (Join-Path $PSScriptRoot 'Assert-TpvTestLabContext.ps1') -ConfigurationFile $ConfigurationFile
$aclPaths = $lab.AclPaths
if (-not [string]::Equals([IO.Path]::GetFullPath($SecretDirectory), $aclPaths.FiscalSecretRoot,
        [StringComparison]::OrdinalIgnoreCase) -or
    -not [string]::Equals([IO.Path]::GetFullPath($ExportDirectory), $aclPaths.FiscalExportRoot,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'SecretDirectory y ExportDirectory deben coincidir con las rutas efectivas del laboratorio.'
}
Import-Module (Join-Path $PSScriptRoot 'TpvServerPorts.psm1') -Force
$tpvInstallationMutex = Enter-TpvServerInstallationLock
try {
$bundle = (Resolve-Path -LiteralPath $BundleDirectory -ErrorAction Stop).Path
$verificationArgs = @{ ExpectedReleaseId = $ExpectedReleaseId; AsObject = $true }
foreach ($key in @('ExpectedVersion', 'ExpectedSchemaVersion', 'ExpectedReleaseSequence', 'ExpectedBuildSequence')) {
    if ($PSBoundParameters.ContainsKey($key)) { $verificationArgs[$key] = $PSBoundParameters[$key] }
}
$bundleInfo = & $verifier -BundleDirectory $bundle @verificationArgs
if ($bundleInfo.Status -cne 'TEST_ONLY_VERIFIED') { throw 'Solo se admite un bundle TEST_ONLY verificado.' }
$jar = $bundleInfo.Jar
$wrapperSource = Resolve-ExistingFile $WinSwExecutable 'WinSW'
$java = Resolve-ExistingFile $JavaExecutable 'Java'
$configuration = Resolve-ExistingFile $ConfigurationFile 'El fichero de configuracion externo'
Assert-Java25 $java
$configuration = Assert-SafeRegularFile $configuration 'El fichero de configuracion externo'
Assert-SafePlannedPath $SecretDirectory 'El directorio de secretos' 'Directory'
Assert-SafePlannedPath $ExportDirectory 'El directorio de exportaciones' 'Directory'
$wrapperActualHash = (Get-FileHash -LiteralPath $wrapperSource -Algorithm SHA256).Hash
if ($wrapperActualHash -cne $WinSwSha256.ToUpperInvariant()) { throw 'El SHA-256 de WinSW no coincide.' }
Assert-TpvWinSw3 $wrapperSource
$expectedStartName = switch ($ServiceAccount) {
    'VirtualService' { "NT SERVICE\$ServiceName" }
    'LocalSystem' { 'LocalSystem' }
    'LocalService' { 'NT AUTHORITY\LocalService' }
    'NetworkService' { 'NT AUTHORITY\NetworkService' }
}

$install = [IO.Path]::GetFullPath($InstallRoot)
if ([IO.Path]::GetPathRoot($install) -eq $install) { throw 'InstallRoot no puede ser la raiz de una unidad.' }
$releasesRoot = Join-Path $install 'releases'
$rollbackRoot = Join-Path $install 'rollback'
Assert-SafePlannedPath $install 'InstallRoot' 'Directory'
Assert-SafePlannedPath $releasesRoot 'El directorio de releases' 'Directory'
Assert-SafePlannedPath $rollbackRoot 'El directorio de rollback' 'Directory'
$configurationFull = [IO.Path]::GetFullPath($configuration)
if ($configurationFull.StartsWith(([IO.Path]::GetFullPath($bundle)).TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'La configuracion debe estar fuera del bundle inmutable.'
}
Assert-LoopbackAddress $ListenAddress
Assert-BackendPublicUrl $PublicUrl
$publicUrlArgument = if ($PublicUrl) { " --tpv.terminal-discovery.public-url=$PublicUrl" } else { '' }
$configurationUri = ConvertTo-FileUri $configurationFull
$serviceSid = Get-ServiceAccountSid $ServiceAccount
if ($Phase -eq 'Start') {
    if ($null -eq $serviceSid) { throw 'Fase Start requiere el servicio registrado; ejecute primero -Phase Register y aplique las ACL.' }
    $secretDirectory = Assert-SafeDirectoryTree $SecretDirectory 'El directorio de secretos'
    $exportDirectory = Assert-SafeDirectoryTree $ExportDirectory 'El directorio de exportaciones fiscales'
    Assert-ServiceCanRead $configuration $serviceSid 'El fichero de configuracion externo'
    Assert-SecretTreeReadable $secretDirectory $serviceSid
    Assert-RestrictedTreeAcl $secretDirectory $serviceSid ([Security.AccessControl.FileSystemRights]::FullControl)
    Assert-RestrictedExportAcl $exportDirectory $serviceSid
    Assert-RestrictedTreeAcl $aclPaths.KeyRoot $serviceSid ([Security.AccessControl.FileSystemRights]::FullControl)
    foreach ($name in @('BackupRoot', 'ProductImagesRoot', 'DocumentTemplatesRoot', 'RestoreJournalRoot')) {
        Assert-RestrictedTreeAcl $aclPaths[$name] $serviceSid ([Security.AccessControl.FileSystemRights]::Modify)
    }
}

$service = Get-CimInstance -ClassName Win32_Service -Filter "Name = '$ServiceName'"
if ($Phase -eq 'Register' -and $null -ne $service) {
    throw 'Ya existe TPVERPBackend; TEST_ONLY no modifica ni sustituye un servicio existente.'
}
if ($Phase -eq 'Update' -and $null -eq $service) {
    throw 'Update exige un TPVERPBackend TEST_ONLY existente y detenido.'
}
$serviceExe = Join-Path $install "$ServiceName.exe"
$serviceXml = Join-Path $install "$ServiceName.xml"
$releaseDirectoryName = "$($bundleInfo.ReleaseId)-r$($bundleInfo.ReleaseSequence)-b$($bundleInfo.BuildSequence)"
$releaseDirectory = Join-Path $releasesRoot $releaseDirectoryName
$releaseJar = Join-Path $releaseDirectory (Split-Path -Leaf $jar)
$releaseSidecar = "$releaseJar.sha256"
Assert-SafePlannedPath $serviceExe 'El ejecutable del servicio' 'File'
Assert-SafePlannedPath $serviceXml 'La configuracion WinSW del servicio' 'File'
Assert-SafePlannedPath $releaseDirectory 'El directorio de la release' 'Directory'
Assert-SafePlannedPath $releaseJar 'El JAR de la release' 'File'
Assert-SafePlannedPath $releaseSidecar 'El sidecar de la release' 'File'

if ($null -ne $service) {
    $servicePath = [string]$service.PathName
    if ($servicePath -notmatch [Regex]::Escape($serviceExe)) {
        throw "El servicio $ServiceName ya existe con otra ruta; no se reemplaza automaticamente."
    }
    if (-not [StringComparer]::OrdinalIgnoreCase.Equals([string]$service.StartName, $expectedStartName)) {
        throw "El servicio $ServiceName usa '$($service.StartName)' y no la cuenta solicitada '$expectedStartName'; no se modifica una cuenta existente."
    }
}

if ($Phase -eq 'Update') {
    Assert-TpvScmIdentity $ServiceName $serviceExe $expectedStartName -Stopped
    $httpsService = Get-CimInstance -ClassName Win32_Service -Filter "Name = 'TPVERPHTTPS'"
    if ($null -ne $httpsService -and $httpsService.State -cne 'Stopped') {
        throw 'Update exige detener primero el gateway HTTPS existente.'
    }
    if ($service.StartMode -cnotin @('Manual', 'Auto')) {
        throw 'Update exige un servicio detenido con inicio Manual o Automatico.'
    }
    [void](Assert-SafeRegularFile $serviceExe 'WinSW instalado')
    [void](Assert-SafeRegularFile $serviceXml 'XML WinSW instalado')
    if ((Get-FileHash -LiteralPath $serviceExe -Algorithm SHA256).Hash -cne $wrapperActualHash) {
        throw 'Update no sustituye un WinSW instalado diferente del binario aprobado.'
    }
    $xmlSettings = [Xml.XmlReaderSettings]::new()
    $xmlSettings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $xmlSettings.XmlResolver = $null
    $xmlSettings.MaxCharactersInDocument = 65536
    $xmlReader = [Xml.XmlReader]::Create($serviceXml, $xmlSettings)
    try {
        $previousXml = [Xml.XmlDocument]::new()
        $previousXml.XmlResolver = $null
        $previousXml.Load($xmlReader)
    } finally { $xmlReader.Dispose() }
    Assert-TpvWinSwStartupPolicy $previousXml
    $oldJarMatches = [Regex]::Matches([string]$previousXml.service.arguments,
        '(?i)(?:^|\s)-jar\s+"([^"\r\n]+\.jar)"(?=\s|$)')
    if ($oldJarMatches.Count -ne 1) { throw 'Update exige un unico JAR anterior instalado.' }
    $oldJar = Resolve-ExistingFile $oldJarMatches[0].Groups[1].Value 'JAR TEST_ONLY anterior'
    $oldReleaseDirectory = [IO.Path]::GetDirectoryName($oldJar)
    if (-not [string]::Equals([IO.Path]::GetDirectoryName($oldReleaseDirectory), $releasesRoot,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw 'La release anterior no pertenece al Backend instalado.'
    }
    $expectedOldArguments = "--enable-native-access=ALL-UNNAMED -jar `"$oldJar`" --spring.profiles.active=lab-bundle --server.address=$ListenAddress --server.port=$Port$publicUrlArgument `"--spring.config.additional-location=$configurationUri`""
    $oldPublicEnvironment = @($previousXml.service.env | Where-Object { $_.name -ceq 'TPV_BACKEND_PUBLIC_URL' })
    if ([string]$previousXml.service.id -cne $ServiceName -or
        [string]$previousXml.service.executable -cne $java -or
        [string]$previousXml.service.arguments -cne $expectedOldArguments -or
        [string]$previousXml.service.workingdirectory -cne $install -or
        [string]$previousXml.service.startmode -cne 'Manual' -or
        [string]$previousXml.service.serviceaccount.username -cne $expectedStartName -or
        ($PublicUrl -and ($oldPublicEnvironment.Count -ne 1 -or
          [string]$oldPublicEnvironment[0].value -cne $PublicUrl))) {
        throw 'La identidad, configuracion o XML anterior difiere del servicio TEST_ONLY aprobado.'
    }
    $previousPolicy = & (Join-Path $PSScriptRoot 'Resolve-TpvBackendInstalledBundleVerifier.ps1') `
        -BundleDirectory $oldReleaseDirectory -InstalledServiceXml $serviceXml
    if (-not $previousPolicy.TestOnly) { throw 'Update no admite un servicio de produccion.' }
    Assert-TpvTestOnlyUpdateTransition $previousPolicy.Bundle $bundleInfo
}

if ($Phase -eq 'Start') {
    if ($null -eq $service) { throw 'No existe el servicio que se pretende arrancar.' }
    [void](Assert-SafeRegularFile $serviceExe 'WinSW instalado')
    [void](Assert-SafeRegularFile $serviceXml 'XML WinSW instalado')
    if ((Get-FileHash -LiteralPath $serviceExe -Algorithm SHA256).Hash -cne $wrapperActualHash) { throw 'WinSW instalado no coincide con el binario aprobado.' }
    $installed = & $verifier -BundleDirectory $releaseDirectory @verificationArgs
    if ($installed.ArtifactSha256 -cne $bundleInfo.ArtifactSha256) { throw 'La release instalada no coincide con el bundle aprobado.' }
    Assert-RestrictedTreeAcl $releaseDirectory $serviceSid ([Security.AccessControl.FileSystemRights]::ReadAndExecute) -AllowInteractiveRead
    Assert-RestrictedTreeAcl $serviceExe $serviceSid ([Security.AccessControl.FileSystemRights]::ReadAndExecute) -AllowInteractiveRead
    Assert-RestrictedTreeAcl $serviceXml $serviceSid ([Security.AccessControl.FileSystemRights]::ReadAndExecute) -AllowInteractiveRead
    Assert-RestrictedTreeAcl $configuration $serviceSid ([Security.AccessControl.FileSystemRights]::Read)
    $installedXml = [xml]::new()
    $installedXml.XmlResolver = $null
    $installedXml.Load($serviceXml)
    Assert-TpvWinSwStartupPolicy $installedXml
    $expectedArguments = "--enable-native-access=ALL-UNNAMED -jar `"$releaseJar`" --spring.profiles.active=lab-bundle --server.address=$ListenAddress --server.port=$Port$publicUrlArgument `"--spring.config.additional-location=$configurationUri`""
    if ([string]$installedXml.service.executable -cne $java -or
        [string]$installedXml.service.arguments -cne $expectedArguments -or
        [string]$installedXml.service.id -cne $ServiceName -or
        [string]$installedXml.service.serviceaccount.username -cne $expectedStartName) {
        throw 'El XML instalado no coincide con la release, configuracion, Java o identidad aprobados.'
    }
    $publicEnvironments = @($installedXml.service.env | Where-Object { $_.name -ceq 'TPV_BACKEND_PUBLIC_URL' })
    if ($PublicUrl -and ($publicEnvironments.Count -ne 1 -or [string]$publicEnvironments[0].value -cne $PublicUrl)) {
        throw 'La URL HTTPS anunciada no coincide con la configuracion aprobada.'
    }
}

if ($Preflight) {
    Write-Host "Preflight fase ${Phase}: $($bundleInfo.ReleaseId), $($bundleInfo.SchemaVersion), cuenta $ServiceAccount, bind $ListenAddress`:$Port" -ForegroundColor Yellow
    if ($Phase -eq 'Register') { Write-Host 'El preflight de registro NO acredita ACL ni aptitud para arrancar; falta aplicar ACL y pasar -Phase Start -Preflight.' }
    if ($Phase -eq 'Update') { Write-Host 'Update queda detenido: despues aplique ACL oficiales y ejecute -Phase Start -Preflight.' }
    if ($null -ne $service) { Write-Host "Servicio existente: $($service.State)" }
    Write-Host 'No se han creado directorios, copiado binarios, registrado ni detenido ningun servicio.'
    return
}

if ($Phase -eq 'Start') {
    if ($PSCmdlet.ShouldProcess($ServiceName, 'Habilitar inicio automatico y arrancar tras verificar ACL y release')) {
        try {
            Assert-TpvWinSwStartupPolicy $installedXml
            Assert-TpvScmIdentity $ServiceName $serviceExe $expectedStartName
            Set-Service -Name $ServiceName -StartupType Automatic
            & $serviceExe start
            if ($LASTEXITCODE -ne 0) { throw 'WinSW no pudo arrancar; revise logs y conserve la evidencia.' }
        } catch {
            Set-Service -Name $ServiceName -StartupType Manual
            throw
        }
    }
    return
}

if ($PSCmdlet.ShouldProcess($install, "Instalar/actualizar $ServiceName con release $($bundleInfo.ReleaseId)")) {
    New-Item -ItemType Directory -Path $install -Force | Out-Null
    New-Item -ItemType Directory -Path $releasesRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $rollbackRoot -Force | Out-Null
    Assert-SafePlannedPath $install 'InstallRoot' 'Directory'
    Assert-SafePlannedPath $releasesRoot 'El directorio de releases' 'Directory'
    Assert-SafePlannedPath $rollbackRoot 'El directorio de rollback' 'Directory'
    $releaseAlreadyExists = Test-Path -LiteralPath $releaseDirectory -PathType Container
    if ($releaseAlreadyExists) {
        if (-not (Test-Path -LiteralPath $releaseJar -PathType Leaf) -or
            -not (Test-Path -LiteralPath $releaseSidecar -PathType Leaf)) {
            throw "La release inmutable existe incompleta y no se sobrescribe: $releaseDirectory"
        }
        $existingHash = (Get-FileHash -LiteralPath $releaseJar -Algorithm SHA256).Hash.ToUpperInvariant()
        if ($existingHash -cne $bundleInfo.ArtifactSha256.ToUpperInvariant()) {
            throw "La release inmutable ya existe con otro hash: $releaseDirectory"
        }
        $existingSidecarHash = (Get-Content -LiteralPath $releaseSidecar -Raw).Trim().Split(
            [char[]]@(' ', "`t"), [StringSplitOptions]::RemoveEmptyEntries)[0].ToUpperInvariant()
        if ($existingSidecarHash -cne $bundleInfo.ArtifactSha256.ToUpperInvariant()) {
            throw "El sidecar de la release inmutable no coincide: $releaseSidecar"
        }
    } else {
        New-Item -ItemType Directory -Path $releaseDirectory -Force | Out-Null
        Copy-Item -LiteralPath $jar -Destination $releaseJar
        Copy-Item -LiteralPath "$jar.sha256" -Destination $releaseSidecar
        $copiedHash = (Get-FileHash -LiteralPath $releaseJar -Algorithm SHA256).Hash.ToUpperInvariant()
        if ($copiedHash -cne $bundleInfo.ArtifactSha256.ToUpperInvariant()) {
            throw 'La verificacion posterior a la copia del JAR fallo.'
        }
    }
    $maintenanceManifest = Join-Path $bundle 'maintenance-tools.sha256.json'
    if (Test-Path -LiteralPath $maintenanceManifest -PathType Leaf) {
        $releaseTools = Join-Path $releaseDirectory 'tools'
        if (-not $releaseAlreadyExists) {
            Copy-Item -LiteralPath (Join-Path $bundle 'tools') -Destination $releaseTools -Recurse
            Copy-Item -LiteralPath $maintenanceManifest -Destination (Join-Path $releaseDirectory 'maintenance-tools.sha256.json')
        }
    }
    $destinationInfo = & $verifier -BundleDirectory $releaseDirectory @verificationArgs
    if ($destinationInfo.ArtifactSha256 -cne $bundleInfo.ArtifactSha256) {
        throw 'La verificacion del bundle destino no coincide con el bundle origen.'
    }
    if ($Phase -eq 'Update') {
        Assert-TpvScmIdentity $ServiceName $serviceExe $expectedStartName -Stopped
    }

    $previousXmlBytes = if (Test-Path -LiteralPath $serviceXml -PathType Leaf) {
        [IO.File]::ReadAllBytes($serviceXml)
    } else { $null }
    $backupStamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
    $previousExePath = Join-Path $rollbackRoot "$backupStamp.exe"
    $previousXmlPath = Join-Path $rollbackRoot "$backupStamp.xml"
    $previousExe = if (Test-Path -LiteralPath $serviceExe -PathType Leaf) {
        Copy-Item -LiteralPath $serviceExe -Destination $previousExePath -Force -PassThru
    } else { $null }
    if ($null -ne $previousXmlBytes) {
        [IO.File]::WriteAllBytes($previousXmlPath, $previousXmlBytes)
    }

    try {
        Copy-Item -LiteralPath $wrapperSource -Destination $serviceExe -Force
        $copiedWrapperHash = (Get-FileHash -LiteralPath $serviceExe -Algorithm SHA256).Hash
        if ($copiedWrapperHash -cne $wrapperActualHash) {
            throw 'La verificacion posterior a la copia de WinSW fallo.'
        }
        $accountXml = switch ($ServiceAccount) {
        'VirtualService' { "<serviceaccount><username>NT SERVICE\$ServiceName</username></serviceaccount>" }
        'LocalSystem' { '<serviceaccount><username>LocalSystem</username></serviceaccount>' }
        'LocalService' { '<serviceaccount><username>NT AUTHORITY\LocalService</username></serviceaccount>' }
        'NetworkService' { '<serviceaccount><username>NT AUTHORITY\NetworkService</username></serviceaccount>' }
        }
        $escapedJava = ConvertTo-XmlText $java
        $escapedJar = ConvertTo-XmlText $releaseJar
        $escapedConfiguration = ConvertTo-XmlText $configurationFull
        $escapedInstall = ConvertTo-XmlText $install
        $escapedListen = ConvertTo-XmlText $ListenAddress
        $escapedConfigurationUri = ConvertTo-XmlText $configurationUri
        $publicUrlEnvironment = if ($PublicUrl) {
            '<env name="TPV_BACKEND_PUBLIC_URL" value="' + (ConvertTo-XmlText $PublicUrl) + '" />'
        } else { '' }
        $xml = @"
<service>
  <id>$(ConvertTo-XmlText $ServiceName)</id>
  <name>TPV ERP Backend</name>
  <description>Backend TEST_ONLY TPV ERP dentro de VirtualBox.</description>
  <executable>$escapedJava</executable>
  <arguments>--enable-native-access=ALL-UNNAMED -jar &quot;$escapedJar&quot; --spring.profiles.active=lab-bundle --server.address=$escapedListen --server.port=$Port$(ConvertTo-XmlText $publicUrlArgument) &quot;--spring.config.additional-location=$escapedConfigurationUri&quot;</arguments>
  <workingdirectory>$escapedInstall</workingdirectory>
  <startmode>Manual</startmode>
  <autoRefresh>false</autoRefresh>
  <env name="TPV_VERIFACTU_SERVICE_ACCOUNT" value="$([Security.SecurityElement]::Escape($expectedStartName))" />
  $publicUrlEnvironment
  $accountXml
  <onfailure action="restart" delay="10 sec" />
  <onfailure action="restart" delay="30 sec" />
  <onfailure action="none" />
  <resetfailure>1 hour</resetfailure>
  <logpath>$escapedInstall\logs</logpath>
  <log mode="roll-by-size"><sizeThreshold>20480</sizeThreshold><keepFiles>10</keepFiles></log>
</service>
"@
        New-Item -ItemType Directory -Path (Join-Path $install 'logs') -Force | Out-Null
        Set-Content -LiteralPath $serviceXml -Value $xml -Encoding UTF8

        if ($null -eq $service) {
            Register-TpvNewService $serviceExe $serviceXml $ServiceName $expectedStartName
        } else {
            Assert-TpvScmIdentity $ServiceName $serviceExe $expectedStartName -Stopped
            & $serviceExe refresh
            if ($LASTEXITCODE -ne 0) { throw 'WinSW no pudo refrescar la configuracion del servicio.' }
            Assert-TpvScmIdentity $ServiceName $serviceExe $expectedStartName -Stopped -StartMode Manual
        }
    }
    catch {
        if ($null -ne $previousXmlBytes) { [IO.File]::WriteAllBytes($serviceXml, $previousXmlBytes) }
        if ($null -ne $previousExe -and (Test-Path -LiteralPath $previousExePath -PathType Leaf)) {
            Copy-Item -LiteralPath $previousExePath -Destination $serviceExe -Force
        }
        if ($null -ne $service) { try { & $serviceExe refresh | Out-Null } catch { } }
        throw
    }
    $completedAction = if ($Phase -eq 'Update') { 'actualizado' } else { 'registrado' }
    Write-Host "TEST_ONLY_VERIFIED / NO PROMOTABLE: servicio $ServiceName $completedAction sin arrancarlo. Release: $($bundleInfo.ReleaseId)" -ForegroundColor Yellow
    Write-Host "Rollback conservador: se conserva $releaseDirectory y la configuracion anterior en rollback."
}
} finally {
    Exit-TpvServerInstallationLock -Mutex $tpvInstallationMutex
}
