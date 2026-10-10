[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)] [string] $CaddyExecutable,
    [Parameter(Mandatory)] [ValidatePattern('^[0-9a-fA-F]{64}$')] [string] $CaddySha256,
    [Parameter(Mandatory)] [string] $WinSwExecutable,
    [Parameter(Mandatory)] [ValidatePattern('^[0-9a-fA-F]{64}$')] [string] $WinSwSha256,
    [Parameter(Mandatory)] [string] $CertificateFile,
    [Parameter(Mandatory)] [string] $PrivateKeyFile,
    [Parameter(Mandatory)] [string] $PublicUrl,
    [Parameter(Mandatory)] [string] $BackendUrl,
    [string] $InstallRoot = 'C:\ProgramData\TPV ERP\HTTPS',
    [string] $ServiceName = 'TPVERPHTTPS',
    [string] $BackendServiceName = 'TPVERPBackend',
    [ValidateSet('Register', 'Start')] [string] $Phase = 'Register',
    [switch] $Preflight
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-ServiceId([string] $Value, [string] $Label) {
    if ($Value -notmatch '^[A-Za-z0-9]{1,80}$') { throw "$Label debe ser alfanumerico (1-80 caracteres)." }
}

function Assert-Administrator {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'Este instalador requiere Windows.' }
    $principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Ejecute PowerShell como administrador.'
    }
}

function Assert-SafePlannedPath([string] $Path, [string] $Label, [string] $Kind = 'Any') {
    $full = [IO.Path]::GetFullPath($Path)
    $current = $full
    while (-not [string]::IsNullOrWhiteSpace($current)) {
        $item = Get-Item -LiteralPath $current -Force -ErrorAction SilentlyContinue
        if ($null -ne $item) {
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "$Label contiene un reparse point: $current"
            }
            if ($current -eq $full) {
                if ($Kind -eq 'File' -and $item.PSIsContainer) { throw "$Label debe ser un archivo: $full" }
                if ($Kind -eq 'Directory' -and -not $item.PSIsContainer) { throw "$Label debe ser un directorio: $full" }
            }
        }
        $parent = [IO.Path]::GetDirectoryName($current)
        if ($parent -eq $current) { break }
        $current = $parent
    }
    return $full
}

function Resolve-SafeFile([string] $Path, [string] $Label) {
    $full = Assert-SafePlannedPath $Path $Label 'File'
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { throw "$Label no existe: $full" }
    if ((Get-Item -LiteralPath $full -Force).Length -eq 0) { throw "$Label esta vacio: $full" }
    return $full
}

function Assert-Hash([string] $Path, [string] $Expected, [string] $Label) {
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($actual -cne $Expected.ToUpperInvariant()) { throw "El SHA-256 de $Label no coincide." }
    return $actual
}

function ConvertTo-ValidatedOrigin([string] $Value, [string] $Scheme, [switch] $Loopback) {
    $uri = $null
    if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri)) {
        throw "Se requiere un origen $Scheme normalizado sin ruta, credenciales ni parametros."
    }
    $normalizedOrigin = $uri.GetLeftPart([UriPartial]::Authority)
    $allowedAuthorityText = $Value -ceq $normalizedOrigin
    if ($uri.HostNameType -eq [UriHostNameType]::IPv6) {
        # .NET Framework expands IPv6; the port-plan module uses GetLeftPart too.
        # Accept an IPv6 literal authority without URL decoration, then return
        # that same framework-specific normalized authority to the caller.
        $ipv6AuthorityPattern = '^' + [Regex]::Escape($Scheme) + '://\[[0-9A-Fa-f:.]+\](?::[0-9]{1,5})?$'
        $allowedAuthorityText = $Value -cmatch $ipv6AuthorityPattern
    }
    if (
        $uri.Scheme -cne $Scheme -or -not [string]::IsNullOrEmpty($uri.UserInfo) -or
        $uri.AbsolutePath -cne '/' -or -not [string]::IsNullOrEmpty($uri.Query) -or
        -not [string]::IsNullOrEmpty($uri.Fragment) -or
        -not $allowedAuthorityText) {
        throw "Se requiere un origen $Scheme normalizado sin ruta, credenciales ni parametros."
    }
    if ($uri.Port -lt 1 -or $uri.Port -gt 65535) { throw 'Puerto fuera de rango.' }
    $hostLiteral = $uri.Host.Trim([char[]]@('[', ']'))
    if ($Loopback) {
        $ip = $null
        if (-not [Net.IPAddress]::TryParse($hostLiteral, [ref]$ip) -or -not [Net.IPAddress]::IsLoopback($ip)) {
            throw 'BackendUrl debe utilizar una direccion IP loopback literal.'
        }
    } else {
        $publicIp = $null
        if (-not [Net.IPAddress]::TryParse($hostLiteral, [ref]$publicIp) -and
            $hostLiteral -notmatch '^[A-Za-z0-9.-]+$') {
            throw 'PublicUrl contiene un hostname no apto para Caddyfile.'
        }
    }
    return $normalizedOrigin
}

function Assert-PemFile([string] $Path, [string] $Kind) {
    $body = [IO.File]::ReadAllText($Path, [Text.Encoding]::ASCII)
    if ($Kind -eq 'certificate' -and $body -notmatch '-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----') {
        throw 'CertificateFile debe contener un certificado publico PEM.'
    }
    if ($Kind -eq 'key' -and $body -notmatch '-----BEGIN (?:RSA |EC |ENCRYPTED )?PRIVATE KEY-----[\s\S]+?-----END (?:RSA |EC |ENCRYPTED )?PRIVATE KEY-----') {
        throw 'PrivateKeyFile debe contener una clave privada PEM.'
    }
}

function ConvertTo-CaddyPath([string] $Path) {
    if ($Path.Contains('"') -or $Path.Contains("`n") -or $Path.Contains("`r")) { throw 'Ruta no apta para Caddyfile.' }
    return $Path.Replace('\', '/')
}

function New-TpvCaddyfile([string] $PublicOrigin, [string] $BackendOrigin,
    [string] $CertificatePath, [string] $KeyPath) {
    $cert = ConvertTo-CaddyPath $CertificatePath
    $key = ConvertTo-CaddyPath $KeyPath
    $publicUri = [Uri]$PublicOrigin
    $publicIp = $null
    $defaultSni = ''
    if ([Net.IPAddress]::TryParse($publicUri.Host.Trim([char[]]@('[', ']')), [ref]$publicIp)) {
        $defaultSni = "    default_sni $($publicIp.ToString().ToLowerInvariant())`n"
    }
    return @"
{
    admin off
    persist_config off
    auto_https off
$defaultSni    ocsp_stapling off
    servers {
        protocols h1 h2
    }
}

$PublicOrigin {
    tls `"$cert`" `"$key`"
    reverse_proxy $BackendOrigin
}
"@
}

function Assert-CaddyConfiguration([string] $Executable, [string] $CaddyfileText) {
    $previousEncoding = $OutputEncoding
    $previousErrorAction = $ErrorActionPreference
    try {
        # Windows PowerShell 5.1 otherwise sends non-ASCII paths to native stdin as ASCII.
        $OutputEncoding = [Text.UTF8Encoding]::new($false)
        $ErrorActionPreference = 'Continue'
        $CaddyfileText | & $Executable validate --config - --adapter caddyfile 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Caddy rechazo el Caddyfile o el par certificado/clave.' }
    } finally {
        $OutputEncoding = $previousEncoding
        $ErrorActionPreference = $previousErrorAction
    }
}

function New-TpvWinSwXml([string] $Id, [string] $BackendId, [string] $CaddyPath,
    [string] $ConfigPath, [string] $Root, [string] $PublicOrigin, [string] $BackendOrigin) {
    $escape = { param($v) [Security.SecurityElement]::Escape($v) }
    $account = "NT SERVICE\$Id"
    $args = 'run --config "' + $ConfigPath + '" --adapter caddyfile'
    return @"
<service>
  <id>$(& $escape $Id)</id>
  <name>TPV ERP HTTPS</name>
  <description>Entrada HTTPS del backend TPV ERP.</description>
  <executable>$(& $escape $CaddyPath)</executable>
  <arguments>$(& $escape $args)</arguments>
  <workingdirectory>$(& $escape $Root)</workingdirectory>
  <startmode>Manual</startmode>
  <depend>$(& $escape $BackendId)</depend>
  <env name="TPV_HTTPS_PUBLIC_URL" value="$(& $escape $PublicOrigin)" />
  <env name="TPV_HTTPS_BACKEND_URL" value="$(& $escape $BackendOrigin)" />
  <env name="XDG_DATA_HOME" value="$(& $escape (Join-Path $Root 'storage'))" />
  <env name="XDG_CONFIG_HOME" value="$(& $escape (Join-Path $Root 'storage'))" />
  <serviceaccount><username>$(& $escape $account)</username></serviceaccount>
  <onfailure action="restart" delay="10 sec" />
  <onfailure action="restart" delay="30 sec" />
  <onfailure action="none" />
  <resetfailure>1 hour</resetfailure>
  <logpath>$(& $escape (Join-Path $Root 'logs'))</logpath>
  <log mode="roll-by-size"><sizeThreshold>20480</sizeThreshold><keepFiles>10</keepFiles></log>
</service>
"@
}

function Get-TpvService([string] $Id) {
    return Get-CimInstance -ClassName Win32_Service -Filter "Name = '$Id'"
}

function Assert-BackendDependency($BackendService, [string] $Id, [string] $CurrentPhase,
    [bool] $IsPreflight, [bool] $IsWhatIf) {
    if ($null -eq $BackendService -and
        ($CurrentPhase -eq 'Start' -or (-not $IsPreflight -and -not $IsWhatIf))) {
        throw "El servicio backend $Id debe estar registrado."
    }
}

function Assert-ServiceOwnership($Service, [string] $Id, [string] $Executable) {
    if ($null -eq $Service) { return }
    $path = [string]$Service.PathName
    $quoted = '"' + $Executable + '"'
    if (-not ([StringComparer]::OrdinalIgnoreCase.Equals($path, $quoted) -or
        [StringComparer]::OrdinalIgnoreCase.Equals($path, $Executable))) {
        throw "El servicio $Id ya existe con otra ruta."
    }
    if (-not [StringComparer]::OrdinalIgnoreCase.Equals([string]$Service.StartName, "NT SERVICE\$Id")) {
        throw "El servicio $Id usa otra cuenta."
    }
}

function Get-TpvServiceSid([string] $Id) {
    try { return ([Security.Principal.NTAccount]::new("NT SERVICE\$Id")).Translate([Security.Principal.SecurityIdentifier]) }
    catch { throw "No se pudo resolver la cuenta virtual NT SERVICE\$Id despues de registrar el servicio." }
}

function Set-TpvClosedAcl([string] $Path, [Security.Principal.SecurityIdentifier] $ServiceSid,
    [Security.AccessControl.FileSystemRights] $ServiceRights) {
    $item = Get-Item -LiteralPath $Path -Force
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Ruta ACL insegura: $Path" }
    $acl = if ($item.PSIsContainer) { New-Object Security.AccessControl.DirectorySecurity }
        else { New-Object Security.AccessControl.FileSecurity }
    $acl.SetAccessRuleProtection($true, $false)
    $admin = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    $system = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    $acl.SetOwner($admin)
    $inherit = if ($item.PSIsContainer) {
        [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit
    } else { [Security.AccessControl.InheritanceFlags]::None }
    foreach ($entry in @(
        @($admin, [Security.AccessControl.FileSystemRights]::FullControl),
        @($system, [Security.AccessControl.FileSystemRights]::FullControl))) {
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($entry[0], $entry[1], $inherit,
            [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow))
    }
    if ($null -ne $ServiceSid) {
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($ServiceSid, $ServiceRights, $inherit,
            [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow))
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Assert-TpvClosedAcl([string] $Path, [Security.Principal.SecurityIdentifier] $ServiceSid,
    [Security.AccessControl.FileSystemRights] $ServiceRights) {
    $acl = Get-Acl -LiteralPath $Path
    if (-not $acl.AreAccessRulesProtected) { throw "ACL heredada: $Path" }
    $expected = @{'S-1-5-32-544' = [Security.AccessControl.FileSystemRights]::FullControl;
                  'S-1-5-18' = [Security.AccessControl.FileSystemRights]::FullControl}
    if ($null -ne $ServiceSid) { $expected[$ServiceSid.Value] = $ServiceRights }
    $seen = @{}
    foreach ($rule in @($acl.Access)) {
        $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        $rights = $expected[$sid]
        if ($null -eq $rights -or $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
            $rule.FileSystemRights -ne ($rights -bor [Security.AccessControl.FileSystemRights]::Synchronize)) {
            throw "ACL no autorizada: $Path"
        }
        $seen[$sid] = $true
    }
    foreach ($sid in $expected.Keys) { if (-not $seen.ContainsKey($sid)) { throw "ACL incompleta: $Path" } }
}

function Assert-InstalledGateway([string] $Root, [string] $Id, [string] $BackendId,
    [string] $CaddyHash, [string] $WrapperHash, [string] $CertHash, [string] $KeyHash,
    [string] $PublicOrigin, [string] $BackendOrigin, [Security.Principal.SecurityIdentifier] $ServiceSid) {
    $exe = Join-Path $Root "$Id.exe"
    $xmlPath = Join-Path $Root "$Id.xml"
    $caddy = Join-Path $Root 'caddy.exe'
    $config = Join-Path $Root 'Caddyfile'
    $cert = Join-Path $Root 'tls/certificate.pem'
    $key = Join-Path $Root 'tls/private-key.pem'
    $log = Join-Path $Root 'logs'
    $storage = Join-Path $Root 'storage'
    foreach ($file in @($exe, $xmlPath, $caddy, $config, $cert, $key)) { [void](Resolve-SafeFile $file 'Archivo instalado') }
    foreach ($dir in @($Root, (Join-Path $Root 'tls'), $log, $storage)) {
        [void](Assert-SafePlannedPath $dir 'Directorio instalado' 'Directory')
        if (-not (Test-Path -LiteralPath $dir -PathType Container)) { throw "Directorio instalado ausente: $dir" }
    }
    [void](Assert-Hash $exe $WrapperHash 'WinSW instalado')
    [void](Assert-Hash $caddy $CaddyHash 'Caddy instalado')
    [void](Assert-Hash $cert $CertHash 'certificado instalado')
    [void](Assert-Hash $key $KeyHash 'clave instalada')
    $wantedConfig = New-TpvCaddyfile $PublicOrigin $BackendOrigin $cert $key
    if ([IO.File]::ReadAllText($config).Trim() -cne $wantedConfig.Trim()) { throw 'Caddyfile instalado no coincide.' }
    $wantedXml = New-TpvWinSwXml $Id $BackendId $caddy $config $Root $PublicOrigin $BackendOrigin
    if ([IO.File]::ReadAllText($xmlPath).Trim() -cne $wantedXml.Trim()) { throw 'XML WinSW instalado no coincide.' }
    foreach ($path in @($Root, (Join-Path $Root 'tls'), $exe, $xmlPath, $caddy, $config, $cert, $key)) {
        Assert-TpvClosedAcl $path $ServiceSid ([Security.AccessControl.FileSystemRights]::ReadAndExecute)
    }
    foreach ($path in @($log, $storage)) {
        Assert-TpvClosedAcl $path $ServiceSid ([Security.AccessControl.FileSystemRights]::Modify)
    }
}

function Restore-TpvGatewayFiles([hashtable] $PreviousFiles, [hashtable] $MutatedFiles) {
    foreach ($target in $MutatedFiles.Keys) {
        try {
            if ($PreviousFiles.ContainsKey($target)) {
                [IO.File]::Copy($PreviousFiles[$target], $target, $true)
            } elseif (Test-Path -LiteralPath $target -PathType Leaf) {
                Remove-Item -LiteralPath $target -Force
            }
        } catch { Write-Warning "No se pudo restaurar $target" }
    }
}

Assert-Administrator
Import-Module (Join-Path $PSScriptRoot 'TpvServerPorts.psm1') -Force -ErrorAction Stop
$installationMutex = Enter-TpvServerInstallationLock
try {
Assert-ServiceId $ServiceName 'ServiceName'
Assert-ServiceId $BackendServiceName 'BackendServiceName'
if ($ServiceName -ceq $BackendServiceName) { throw 'Los dos servicios deben ser distintos.' }
$publicOrigin = ConvertTo-ValidatedOrigin $PublicUrl 'https'
$backendOrigin = ConvertTo-ValidatedOrigin $BackendUrl 'http' -Loopback
$caddySource = Resolve-SafeFile $CaddyExecutable 'CaddyExecutable'
$wrapperSource = Resolve-SafeFile $WinSwExecutable 'WinSwExecutable'
$certSource = Resolve-SafeFile $CertificateFile 'CertificateFile'
$keySource = Resolve-SafeFile $PrivateKeyFile 'PrivateKeyFile'
Assert-PemFile $certSource 'certificate'
Assert-PemFile $keySource 'key'
$caddyHash = Assert-Hash $caddySource $CaddySha256 'Caddy'
$wrapperHash = Assert-Hash $wrapperSource $WinSwSha256 'WinSW'
$certHash = (Get-FileHash -LiteralPath $certSource -Algorithm SHA256).Hash
$keyHash = (Get-FileHash -LiteralPath $keySource -Algorithm SHA256).Hash
$install = [IO.Path]::GetFullPath($InstallRoot)
if ([IO.Path]::GetPathRoot($install) -eq $install) { throw 'InstallRoot no puede ser la raiz de una unidad.' }
if ($install -notmatch '^[A-Za-z]:\\') { throw 'InstallRoot debe ser una ruta local absoluta de Windows.' }
foreach ($dir in @($install, (Join-Path $install 'tls'), (Join-Path $install 'logs'),
    (Join-Path $install 'storage'), (Join-Path $install 'rollback'))) {
    [void](Assert-SafePlannedPath $dir 'InstallRoot' 'Directory')
}
$exe = Join-Path $install "$ServiceName.exe"
$xml = Join-Path $install "$ServiceName.xml"
$caddy = Join-Path $install 'caddy.exe'
$config = Join-Path $install 'Caddyfile'
$cert = Join-Path $install 'tls/certificate.pem'
$key = Join-Path $install 'tls/private-key.pem'
foreach ($file in @($exe, $xml, $caddy, $config, $cert, $key)) {
    [void](Assert-SafePlannedPath $file 'Archivo instalado' 'File')
}
$allSources = @($caddySource, $wrapperSource, $certSource, $keySource)
if (@($allSources | Where-Object { $_.StartsWith($install.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) }).Count -gt 0) {
    throw 'Los ficheros de origen deben estar fuera de InstallRoot.'
}
if ($Phase -eq 'Register') {
    Assert-CaddyConfiguration $caddySource (New-TpvCaddyfile $publicOrigin $backendOrigin $certSource $keySource)
}
$service = Get-TpvService $ServiceName
Assert-ServiceOwnership $service $ServiceName $exe
$backendService = Get-TpvService $BackendServiceName
Assert-BackendDependency $backendService $BackendServiceName $Phase ([bool]$Preflight) ([bool]$WhatIfPreference)

if ($Phase -eq 'Start') {
    if ($null -eq $service) { throw 'Registre primero el servicio HTTPS.' }
    $sid = Get-TpvServiceSid $ServiceName
    Assert-InstalledGateway $install $ServiceName $BackendServiceName $caddyHash $wrapperHash $certHash $keyHash $publicOrigin $backendOrigin $sid
}
if ($Preflight) {
    Write-Host "Preflight $Phase para $ServiceName ($publicOrigin -> $backendOrigin). Sin cambios." -ForegroundColor Yellow
    return
}
if ($Phase -eq 'Start') {
    if ($PSCmdlet.ShouldProcess($ServiceName, 'Habilitar inicio automatico y arrancar gateway HTTPS')) {
        try {
            Set-Service -Name $ServiceName -StartupType Automatic
            if ($service.State -ne 'Running') {
                & $exe start
                if ($LASTEXITCODE -ne 0) { throw 'WinSW no pudo arrancar el servicio HTTPS.' }
            }
        } catch {
            Set-Service -Name $ServiceName -StartupType Manual
            throw
        }
    }
    return
}

if (-not $PSCmdlet.ShouldProcess($install, "Registrar $ServiceName detenido")) { return }
$wasRunning = $null -ne $service -and $service.State -eq 'Running'
if ($null -ne $service -and $service.State -ne 'Stopped') {
    & $exe stop
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo detener el servicio HTTPS existente.' }
}
$files = @{
    $exe = $wrapperSource; $xml = $null; $caddy = $caddySource; $config = $null;
    $cert = $certSource; $key = $keySource
}
$backup = Join-Path $install ('rollback/' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ'))
$createdService = $null -eq $service
$previousFiles = @{}
$mutatedFiles = @{}
try {
    [void][IO.Directory]::CreateDirectory($install)
    Set-TpvClosedAcl $install $null ([Security.AccessControl.FileSystemRights]::ReadAndExecute)
    foreach ($dir in @('tls', 'logs', 'storage', 'rollback')) {
        $path = Join-Path $install $dir
        [void][IO.Directory]::CreateDirectory($path)
        Set-TpvClosedAcl $path $null ([Security.AccessControl.FileSystemRights]::ReadAndExecute)
    }
    [void][IO.Directory]::CreateDirectory($backup)
    Set-TpvClosedAcl $backup $null ([Security.AccessControl.FileSystemRights]::ReadAndExecute)
    $index = 0
    foreach ($target in $files.Keys) {
        [void](Assert-SafePlannedPath $target 'Destino HTTPS' 'File')
        if (Test-Path -LiteralPath $target -PathType Leaf) {
            $saved = Join-Path $backup ("$index.bak")
            [IO.File]::Copy($target, $saved, $false)
            Set-TpvClosedAcl $saved $null ([Security.AccessControl.FileSystemRights]::ReadAndExecute)
            $previousFiles[$target] = $saved
        }
        $index++
    }
    foreach ($target in $files.Keys) {
        $source = $files[$target]
        if ($null -ne $source) {
            $mutatedFiles[$target] = $true
            [IO.File]::Copy($source, $target, $true)
        }
    }
    $mutatedFiles[$config] = $true
    [IO.File]::WriteAllText($config, (New-TpvCaddyfile $publicOrigin $backendOrigin $cert $key), [Text.UTF8Encoding]::new($false))
    $mutatedFiles[$xml] = $true
    [IO.File]::WriteAllText($xml, (New-TpvWinSwXml $ServiceName $BackendServiceName $caddy $config $install $publicOrigin $backendOrigin), [Text.UTF8Encoding]::new($false))
    [void](Assert-Hash $exe $wrapperHash 'WinSW copiado')
    [void](Assert-Hash $caddy $caddyHash 'Caddy copiado')
    [void](Assert-Hash $cert $certHash 'certificado copiado')
    [void](Assert-Hash $key $keyHash 'clave copiada')
    foreach ($target in $files.Keys) {
        Set-TpvClosedAcl $target $null ([Security.AccessControl.FileSystemRights]::ReadAndExecute)
    }
    Assert-CaddyConfiguration $caddy (New-TpvCaddyfile $publicOrigin $backendOrigin $cert $key)
    if ($createdService) { & $exe install } else { & $exe refresh }
    if ($LASTEXITCODE -ne 0) { throw 'WinSW no pudo registrar o refrescar el servicio HTTPS.' }
    $registered = Get-TpvService $ServiceName
    Assert-ServiceOwnership $registered $ServiceName $exe
    if ($null -eq $registered -or $registered.State -ne 'Stopped') { throw 'El servicio HTTPS debe quedar registrado y detenido.' }
    $sid = Get-TpvServiceSid $ServiceName
    foreach ($path in @($install, (Join-Path $install 'tls'), $exe, $xml, $caddy, $config, $cert, $key)) {
        Set-TpvClosedAcl $path $sid ([Security.AccessControl.FileSystemRights]::ReadAndExecute)
    }
    foreach ($path in @((Join-Path $install 'logs'), (Join-Path $install 'storage'))) {
        Set-TpvClosedAcl $path $sid ([Security.AccessControl.FileSystemRights]::Modify)
    }
    Assert-InstalledGateway $install $ServiceName $BackendServiceName $caddyHash $wrapperHash $certHash $keyHash $publicOrigin $backendOrigin $sid
} catch {
    $failure = $_
    if ($createdService -and (Test-Path -LiteralPath $exe -PathType Leaf)) {
        try { & $exe uninstall | Out-Null } catch { Write-Warning 'No se pudo revertir el registro WinSW.' }
    }
    Restore-TpvGatewayFiles $previousFiles $mutatedFiles
    try {
        if (-not $createdService -and (Test-Path -LiteralPath $exe -PathType Leaf)) { & $exe refresh | Out-Null }
    } catch { Write-Warning 'No se pudo revertir el registro WinSW.' }
    if (-not $createdService) {
        try {
            $oldSid = Get-TpvServiceSid $ServiceName
            foreach ($path in @($install, (Join-Path $install 'tls'), $exe, $xml, $caddy, $config, $cert, $key)) {
                if (Test-Path -LiteralPath $path) {
                    Set-TpvClosedAcl $path $oldSid ([Security.AccessControl.FileSystemRights]::ReadAndExecute)
                }
            }
            foreach ($path in @((Join-Path $install 'logs'), (Join-Path $install 'storage'))) {
                if (Test-Path -LiteralPath $path) {
                    Set-TpvClosedAcl $path $oldSid ([Security.AccessControl.FileSystemRights]::Modify)
                }
            }
        } catch { Write-Warning 'No se pudieron restaurar todas las ACL del servicio previo.' }
        if ($wasRunning -and (Test-Path -LiteralPath $exe -PathType Leaf)) {
            try {
                & $exe start | Out-Null
                if ($LASTEXITCODE -ne 0) { Write-Warning 'No se pudo volver a arrancar el servicio HTTPS previo.' }
            } catch { Write-Warning 'No se pudo volver a arrancar el servicio HTTPS previo.' }
        }
    }
    throw $failure
}
Write-Host "Servicio $ServiceName registrado y detenido para $publicOrigin. Ejecute -Phase Start tras el preflight." -ForegroundColor Green
} finally {
    Exit-TpvServerInstallationLock -Mutex $installationMutex
}
