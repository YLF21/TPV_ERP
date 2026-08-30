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
    [string] $ExpectedVersion = '4.2.0',
    [string] $ExpectedReleaseId = 'tpv-erp-4.2.0',
    [string] $ExpectedSchemaVersion = 'V229',
    [long] $ExpectedReleaseSequence = 1,
    [long] $ExpectedBuildSequence = 1,
    [string] $SecretDirectory = 'C:\ProgramData\TPV ERP\secrets\verifactu',
    [string] $ExportDirectory = 'C:\ProgramData\TPV ERP\exports\fiscal',
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

Assert-SafeExpectedSegment $ExpectedVersion 'ExpectedVersion'
Assert-SafeExpectedSegment $ExpectedReleaseId 'ExpectedReleaseId'
Assert-SafeExpectedSegment $ExpectedSchemaVersion 'ExpectedSchemaVersion'
if ($ExpectedReleaseSequence -lt 0 -or $ExpectedBuildSequence -lt 0) {
    throw 'ExpectedReleaseSequence y ExpectedBuildSequence deben ser no negativos'
}

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$verifier = Join-Path $PSScriptRoot 'Test-TpvBackendProductionBundle.ps1'

if ($ServiceName -notmatch '^[A-Za-z0-9]{1,80}$') {
    throw 'ServiceName debe ser un ID WinSW alfanumerico (1-80 caracteres).'
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
        Write-Warning "No se puede demostrar con una regla ACL directa que la cuenta del servicio lea $Description; se conserva la validacion de seguridad de la ruta: $Path"
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
    $allowed = @($ServiceSid.Value, 'S-1-5-18', 'S-1-5-32-544')
    $targets = @((Get-Item -LiteralPath $Path -Force)) +
        @(Get-ChildItem -LiteralPath $Path -Force -Recurse)
    foreach ($target in $targets) {
        $acl = Get-Acl -LiteralPath $target.FullName
        if (-not $acl.AreAccessRulesProtected) {
            throw "La ACL del directorio de exportaciones no puede heredar reglas: $($target.FullName)"
        }
        foreach ($rule in @($acl.Access)) {
            $ruleSid = try {
                $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
            } catch { throw "No se pudo resolver una identidad ACL en exportaciones: $($target.FullName)" }
            if ($ruleSid -notin $allowed -or
                $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
                ($rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -ne
                    [Security.AccessControl.FileSystemRights]::FullControl) {
                throw "La ACL de exportaciones contiene una regla no autorizada (Users/herencia/permiso parcial): $($target.FullName)"
            }
        }
        $present = @($acl.Access | ForEach-Object {
            try { $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value } catch { $null }
        })
        foreach ($requiredSid in $allowed) {
            if ($requiredSid -notin $present) {
                throw "Falta la identidad requerida en la ACL de exportaciones ($requiredSid): $($target.FullName)"
            }
        }
    }
}

function Assert-Java25([string] $Path) {
    $versionOutput = (& $Path -version 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -and [string]::IsNullOrWhiteSpace($versionOutput)) {
        throw "No se pudo ejecutar Java para comprobar la version: $Path"
    }
    $match = [Regex]::Match($versionOutput, '(?m)(?:version\s+"|openjdk\s+|java\s+)(\d+)')
    if (-not $match.Success -or [int]$match.Groups[1].Value -ne 25) {
        throw "El backend productivo exige Java 25; se obtuvo: $versionOutput"
    }
}

Assert-Administrator
$bundle = (Resolve-Path -LiteralPath $BundleDirectory -ErrorAction Stop).Path
$bundleInfo = & $verifier -BundleDirectory $bundle `
    -ExpectedVersion $ExpectedVersion -ExpectedReleaseId $ExpectedReleaseId `
    -ExpectedSchemaVersion $ExpectedSchemaVersion `
    -ExpectedReleaseSequence $ExpectedReleaseSequence -ExpectedBuildSequence $ExpectedBuildSequence -AsObject
$jar = $bundleInfo.Jar
$wrapperSource = Resolve-ExistingFile $WinSwExecutable 'WinSW'
$java = Resolve-ExistingFile $JavaExecutable 'Java'
$configuration = Resolve-ExistingFile $ConfigurationFile 'El fichero de configuracion externo'
$secretDirectory = (Resolve-Path -LiteralPath $SecretDirectory -ErrorAction Stop).Path
Assert-Java25 $java
$configuration = Assert-SafeRegularFile $configuration 'El fichero de configuracion externo'
$secretDirectory = Assert-SafeDirectoryTree $secretDirectory 'El directorio de secretos'
$exportDirectory = Assert-SafeDirectoryTree $ExportDirectory 'El directorio de exportaciones fiscales'
$wrapperActualHash = (Get-FileHash -LiteralPath $wrapperSource -Algorithm SHA256).Hash
if ($wrapperActualHash -cne $WinSwSha256.ToUpperInvariant()) { throw 'El SHA-256 de WinSW no coincide.' }
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
$configurationUri = ConvertTo-FileUri $configurationFull
$serviceSid = Get-ServiceAccountSid $ServiceAccount
if ($null -ne $serviceSid) {
    Assert-ServiceCanRead $configuration $serviceSid 'El fichero de configuracion externo'
    Assert-SecretTreeReadable $secretDirectory $serviceSid
}
Assert-RestrictedExportAcl $exportDirectory $serviceSid

$service = Get-CimInstance -ClassName Win32_Service -Filter "Name = '$ServiceName'"
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

if ($Preflight) {
    Write-Host "Preflight correcto: $($bundleInfo.ReleaseId), $($bundleInfo.SchemaVersion), cuenta $ServiceAccount, bind $ListenAddress`:$Port" -ForegroundColor Yellow
    if ($null -ne $service) { Write-Host "Servicio existente: $($service.State)" }
    Write-Host 'No se han creado directorios, copiado binarios, registrado ni detenido ningun servicio.'
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
    $destinationInfo = & $verifier -BundleDirectory $releaseDirectory `
        -ExpectedVersion $ExpectedVersion -ExpectedReleaseId $ExpectedReleaseId `
        -ExpectedSchemaVersion $ExpectedSchemaVersion `
        -ExpectedReleaseSequence $ExpectedReleaseSequence -ExpectedBuildSequence $ExpectedBuildSequence -AsObject
    if ($destinationInfo.ArtifactSha256 -cne $bundleInfo.ArtifactSha256) {
        throw 'La verificacion del bundle destino no coincide con el bundle origen.'
    }
    if ($null -ne $service -and $service.State -ne 'Stopped') {
        & $serviceExe stop
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo detener el servicio anterior.' }
    }

    $previousXml = if (Test-Path -LiteralPath $serviceXml -PathType Leaf) {
        [IO.File]::ReadAllText($serviceXml, [Text.Encoding]::UTF8)
    } else { $null }
    $backupStamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
    $previousExePath = Join-Path $rollbackRoot "$backupStamp.exe"
    $previousXmlPath = Join-Path $rollbackRoot "$backupStamp.xml"
    $previousExe = if (Test-Path -LiteralPath $serviceExe -PathType Leaf) {
        Copy-Item -LiteralPath $serviceExe -Destination $previousExePath -Force -PassThru
    } else { $null }
    if ($null -ne $previousXml) {
        Set-Content -LiteralPath $previousXmlPath -Value $previousXml -Encoding UTF8
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
        $xml = @"
<service>
  <id>$(ConvertTo-XmlText $ServiceName)</id>
  <name>TPV ERP Backend</name>
  <description>Backend productivo TPV ERP VeriFactu.</description>
  <executable>$escapedJava</executable>
  <arguments>-jar &quot;$escapedJar&quot; --spring.profiles.active=prod --server.address=$escapedListen --server.port=$Port &quot;--spring.config.additional-location=$escapedConfigurationUri&quot;</arguments>
  <workingdirectory>$escapedInstall</workingdirectory>
  <startmode>Automatic</startmode>
  <delayedAutoStart>true</delayedAutoStart>
  <env name="TPV_VERIFACTU_SERVICE_ACCOUNT" value="$([Security.SecurityElement]::Escape($expectedStartName))" />
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
            & $serviceExe install
            if ($LASTEXITCODE -ne 0) { throw 'WinSW no pudo registrar el servicio.' }
        } else {
            & $serviceExe refresh
            if ($LASTEXITCODE -ne 0) { throw 'WinSW no pudo refrescar la configuracion del servicio.' }
        }
    }
    catch {
        if ($null -ne $previousXml) { Set-Content -LiteralPath $serviceXml -Value $previousXml -Encoding UTF8 }
        if ($null -ne $previousExe -and (Test-Path -LiteralPath $previousExePath -PathType Leaf)) {
            Copy-Item -LiteralPath $previousExePath -Destination $serviceExe -Force
        }
        if ($null -ne $service) { try { & $serviceExe refresh | Out-Null } catch { } }
        else { try { & $serviceExe uninstall | Out-Null } catch { } }
        throw
    }
    Write-Host "Servicio $ServiceName instalado/actualizado sin arrancarlo. Release: $($bundleInfo.ReleaseId)" -ForegroundColor Green
    Write-Host "Rollback conservador: se conserva $releaseDirectory y la configuracion anterior en rollback."
}
