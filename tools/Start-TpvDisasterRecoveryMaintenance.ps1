[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('request', 'receive', 'activate', 'status')]
    [string] $Action
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function ConvertFrom-TpvHttpJson([object] $Content) {
    # Windows PowerShell may return vendor JSON media types as bytes instead of text.
    if ($Content -is [byte[]]) {
        $json = [Text.UTF8Encoding]::new($false, $true).GetString($Content)
    } elseif ($Content -is [string]) {
        $json = $Content
    } else {
        throw 'La respuesta HTTP no contiene JSON de texto o UTF-8.'
    }
    return ConvertFrom-Json -InputObject $json -ErrorAction Stop
}

function Assert-PlainFile([string] $Value, [string] $Description) {
    $full = [IO.Path]::GetFullPath($Value)
    if (-not [IO.Path]::IsPathRooted($full)) { throw "$Description debe tener ruta absoluta." }
    $item = Get-Item -LiteralPath $full -Force -ErrorAction Stop
    if ($item.PSIsContainer) { throw "$Description no es un archivo." }
    $current = $full
    while ($true) {
        $part = Get-Item -LiteralPath $current -Force -ErrorAction Stop
        if (($part.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Description contiene un enlace o reparse point."
        }
        $parent = [IO.Path]::GetDirectoryName($current)
        if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $current) { break }
        $current = $parent
    }
    return $full
}

function Assert-PlainDirectory([string] $Value, [string] $Description) {
    $full = [IO.Path]::GetFullPath($Value)
    if (-not [IO.Path]::IsPathRooted($full)) { throw "$Description debe tener ruta absoluta." }
    $item = Get-Item -LiteralPath $full -Force -ErrorAction Stop
    if (-not $item.PSIsContainer) { throw "$Description no es un directorio." }
    $current = $full
    while ($true) {
        $part = Get-Item -LiteralPath $current -Force -ErrorAction Stop
        if (($part.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Description contiene un enlace o reparse point."
        }
        $parent = [IO.Path]::GetDirectoryName($current)
        if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $current) { break }
        $current = $parent
    }
    return $full
}

function Read-InstalledService([string] $XmlPath) {
    $settings = [Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $settings.MaxCharactersInDocument = 65536
    $reader = [Xml.XmlReader]::Create($XmlPath, $settings)
    try {
        $document = [Xml.XmlDocument]::new()
        $document.XmlResolver = $null
        $document.Load($reader)
    } finally { $reader.Dispose() }
    if ([string]$document.service.id -cne 'TPVERPBackend') { throw 'El servicio instalado no es TPVERPBackend.' }
    $arguments = [string]$document.service.arguments
    $match = [regex]::Match($arguments, '(?i)(?:^|\s)-jar\s+"([^"\r\n]+\.jar)"(?:\s|$)')
    if (-not $match.Success) { throw 'El servicio no declara un JAR de release seguro.' }
    $addressMatch = [regex]::Match($arguments, '(?i)(?:^|\s)--server\.address=(127\.0\.0\.1|::1)(?:\s|$)')
    $portMatch = [regex]::Match($arguments, '(?i)(?:^|\s)--server\.port=([0-9]{1,5})(?:\s|$)')
    $port = 0
    if (-not $addressMatch.Success -or -not $portMatch.Success -or
            -not [int]::TryParse($portMatch.Groups[1].Value, [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
        throw 'El servicio no declara una dirección loopback y puerto válidos.'
    }
    $hostText = if ($addressMatch.Groups[1].Value -eq '::1') { '[::1]' } else { '127.0.0.1' }
    $serviceAccount = [string]$document.service.serviceaccount.username
    if ($serviceAccount -cnotin @('NT SERVICE\TPVERPBackend', 'LocalSystem', 'NT AUTHORITY\LocalService', 'NT AUTHORITY\NetworkService')) {
        throw 'La cuenta del servicio instalado no está permitida para mantenimiento.'
    }
    return @{
        Java = [string]$document.service.executable
        Jar = $match.Groups[1].Value
        Port = $port
        LocalUrl = "http://${hostText}:$port"
        ServiceAccount = $serviceAccount
    }
}

function Read-InstalledHttpsService([string] $ProgramDataRoot) {
    $httpsRoot = Join-Path $ProgramDataRoot 'TPV ERP\HTTPS'
    $xml = Assert-PlainFile (Join-Path $httpsRoot 'TPVERPHTTPS.xml') 'El XML del servicio HTTPS'
    $settings = [Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $settings.MaxCharactersInDocument = 65536
    $reader = [Xml.XmlReader]::Create($xml, $settings)
    try {
        $document = [Xml.XmlDocument]::new()
        $document.XmlResolver = $null
        $document.Load($reader)
    } finally { $reader.Dispose() }
    $caddy = Join-Path $httpsRoot 'caddy.exe'
    if ([string]$document.service.id -cne 'TPVERPHTTPS' -or
            [string]$document.service.serviceaccount.username -cne 'NT SERVICE\TPVERPHTTPS' -or
            -not [string]::Equals([string]$document.service.executable, $caddy,
                [StringComparison]::OrdinalIgnoreCase) -or
            -not [string]::Equals([string]$document.service.workingdirectory, $httpsRoot,
                [StringComparison]::OrdinalIgnoreCase)) {
        throw 'El servicio HTTPS instalado no tiene identidad o directorio esperado.'
    }
    $publicSettings = @($document.service.env | Where-Object { $_.name -ceq 'TPV_HTTPS_PUBLIC_URL' })
    $backendSettings = @($document.service.env | Where-Object { $_.name -ceq 'TPV_HTTPS_BACKEND_URL' })
    $publicUri = $null
    $publicText = if ($publicSettings.Count -eq 1) { [string]$publicSettings[0].value } else { '' }
    $ipv6Authority = $publicText -cmatch '^https://\[[0-9A-Fa-f:.]+\](?::[0-9]{1,5})?$'
    if ($publicSettings.Count -ne 1 -or $backendSettings.Count -ne 1 -or
            -not [Uri]::TryCreate($publicText, [UriKind]::Absolute, [ref]$publicUri) -or
            $publicUri.Scheme -cne 'https' -or -not [string]::IsNullOrEmpty($publicUri.UserInfo) -or
            $publicUri.AbsolutePath -cne '/' -or -not [string]::IsNullOrEmpty($publicUri.Query) -or
            -not [string]::IsNullOrEmpty($publicUri.Fragment) -or
            ($publicText -cne $publicUri.GetLeftPart([UriPartial]::Authority) -and
                -not ($publicUri.HostNameType -eq [UriHostNameType]::IPv6 -and $ipv6Authority))) {
        throw 'El servicio HTTPS no declara una URL pública instalada válida.'
    }
    Assert-PlainDirectory $httpsRoot 'La instalación HTTPS' | Out-Null
    return @{ Account = 'NT SERVICE\TPVERPHTTPS'; TlsDirectory = (Join-Path $httpsRoot 'tls');
        Program = $caddy; PublicUrl = $publicUri.GetLeftPart([UriPartial]::Authority);
        BackendUrl = [string]$backendSettings[0].value }
}

function Test-TpvSameHost([string] $Left, [string] $Right) {
    $leftIp = $null
    $rightIp = $null
    if ([Net.IPAddress]::TryParse($Left.Trim('[', ']'), [ref]$leftIp) -and
            [Net.IPAddress]::TryParse($Right.Trim('[', ']'), [ref]$rightIp)) {
        return $leftIp.Equals($rightIp)
    }
    return [string]::Equals($Left, $Right, [StringComparison]::OrdinalIgnoreCase)
}

function Test-TpvSameOrigin([string] $Left, [string] $Right) {
    $leftUri = $null
    $rightUri = $null
    if (-not [Uri]::TryCreate($Left, [UriKind]::Absolute, [ref]$leftUri) -or
            -not [Uri]::TryCreate($Right, [UriKind]::Absolute, [ref]$rightUri)) { return $false }
    return $leftUri.Scheme -ceq $rightUri.Scheme -and $leftUri.Port -eq $rightUri.Port -and
        (Test-TpvSameHost $leftUri.DnsSafeHost $rightUri.DnsSafeHost) -and
        [string]::IsNullOrEmpty($leftUri.UserInfo) -and [string]::IsNullOrEmpty($rightUri.UserInfo) -and
        $leftUri.AbsolutePath -ceq '/' -and $rightUri.AbsolutePath -ceq '/' -and
        [string]::IsNullOrEmpty($leftUri.Query) -and [string]::IsNullOrEmpty($rightUri.Query) -and
        [string]::IsNullOrEmpty($leftUri.Fragment) -and [string]::IsNullOrEmpty($rightUri.Fragment)
}

function Test-TpvLocalReadiness([string] $LocalUrl) {
    try {
        $response = Invoke-WebRequest -Uri "$LocalUrl/actuator/health/readiness" -UseBasicParsing -TimeoutSec 3
        $health = ConvertFrom-TpvHttpJson -Content $response.Content
        return $response.StatusCode -eq 200 -and $health.status -ceq 'UP'
    } catch { return $false }
}

function Test-TpvHttpsHealthResponse([IO.TextReader] $Reader) {
    $buffer = New-Object char[] 1024
    $wire = [Text.StringBuilder]::new()
    while ($wire.Length -le 16384) {
        $read = $Reader.Read($buffer, 0, $buffer.Length)
        if ($read -eq 0) { break }
        [void]$wire.Append($buffer, 0, $read)
    }
    if ($wire.Length -gt 16384) { return $false }
    $message = $wire.ToString()
    $separator = $message.IndexOf("`r`n`r`n", [StringComparison]::Ordinal)
    if ($separator -lt 0) { return $false }
    $headers = $message.Substring(0, $separator)
    if ($headers -notmatch '^HTTP/1\.[01] 200(?: |\r\n)') { return $false }
    $body = $message.Substring($separator + 4)
    if ($headers -match '(?im)^Transfer-Encoding:\s*chunked\s*$') {
        $decoded = [Text.StringBuilder]::new()
        $offset = 0
        while ($true) {
            $lineEnd = $body.IndexOf("`r`n", $offset, [StringComparison]::Ordinal)
            if ($lineEnd -lt 0) { return $false }
            $sizeText = $body.Substring($offset, $lineEnd - $offset)
            if ($sizeText -notmatch '^[0-9a-fA-F]{1,5}$') { return $false }
            $size = [Convert]::ToInt32($sizeText, 16)
            if ($size -eq 0) { break }
            $offset = $lineEnd + 2
            if ($size -gt 8192 -or $offset + $size + 2 -gt $body.Length -or
                    $body.Substring($offset + $size, 2) -cne "`r`n") { return $false }
            [void]$decoded.Append($body, $offset, $size)
            $offset += $size + 2
        }
        $body = $decoded.ToString()
    }
    if ($body.Length -gt 8192) { return $false }
    try { return (ConvertFrom-TpvHttpJson -Content $body).status -ceq 'UP' }
    catch { return $false }
}

function Test-TpvHttpsReadiness([string] $PublicUrl) {
    $uri = [Uri]$PublicUrl
    $client = [Net.Sockets.TcpClient]::new()
    $ssl = $null
    try {
        if (-not $client.ConnectAsync('127.0.0.1', $uri.Port).Wait(2000)) { return $false }
        # SNI uses the installed public host. SslStream's default policy validates chain and name.
        $ssl = [Net.Security.SslStream]::new($client.GetStream(), $false)
        $ssl.ReadTimeout = 3000
        $ssl.WriteTimeout = 3000
        if (-not $ssl.AuthenticateAsClientAsync($uri.DnsSafeHost.Trim('[', ']')).Wait(5000)) { return $false }
        $request = [Text.Encoding]::ASCII.GetBytes("GET /actuator/health/readiness HTTP/1.1`r`nHost: $($uri.Authority)`r`nConnection: close`r`n`r`n")
        $ssl.Write($request, 0, $request.Length)
        $reader = [IO.StreamReader]::new($ssl)
        try { return Test-TpvHttpsHealthResponse $reader }
        finally { $reader.Dispose() }
    } catch { return $false }
    finally { if ($null -ne $ssl) { $ssl.Dispose() }; $client.Dispose() }
}

function Read-TpvRecoveryFirewallPortFilter($Rule) { return $Rule | Get-NetFirewallPortFilter -ErrorAction Stop }
function Read-TpvRecoveryFirewallApplicationFilter($Rule) { return $Rule | Get-NetFirewallApplicationFilter -ErrorAction Stop }
function Read-TpvRecoveryFirewallAddressFilter($Rule) { return $Rule | Get-NetFirewallAddressFilter -ErrorAction Stop }

function Get-TpvRecoveryManagedRule([string] $Name, [string] $Program,
        [string] $Protocol, [int] $Port) {
    $rules = @(Get-NetFirewallRule -Name $Name -ErrorAction SilentlyContinue | Where-Object { $null -ne $_ })
    if ($rules.Count -ne 1) { throw "Falta la regla instalada $Name o está duplicada." }
    $rule = $rules[0]
    $ports = @(Read-TpvRecoveryFirewallPortFilter $rule)
    $applications = @(Read-TpvRecoveryFirewallApplicationFilter $rule)
    $addresses = @(Read-TpvRecoveryFirewallAddressFilter $rule)
    if ($ports.Count -ne 1 -or $applications.Count -ne 1 -or $addresses.Count -ne 1 -or
            [string]$rule.Description -cne 'TPV ERP server installer managed' -or
            [string]$rule.Direction -ne 'Inbound' -or [string]$rule.Action -ne 'Allow' -or
            [string]$ports[0].Protocol -ne $Protocol -or
            [string]$ports[0].LocalPort -ne [string]$Port -or
            -not [StringComparer]::OrdinalIgnoreCase.Equals([string]$applications[0].Program, $Program) -or
            [string]$addresses[0].RemoteAddress -ne 'LocalSubnet' -or
            ((@(([string]$rule.Profile).Split(',') | ForEach-Object { $_.Trim() } | Sort-Object) -join ',') -ne 'Domain,Private')) {
        throw "La regla instalada $Name tiene otra configuración; no se modifica."
    }
    return $rule
}

function Resolve-TpvRecoveryPublicAddresses([string] $HostName) {
    try {
        $lookup = [Net.Dns]::GetHostAddressesAsync($HostName)
        if ($lookup.Wait(2000)) { return $lookup.Result }
    } catch { }
    return @()
}

function Assert-TpvRecoveryNetworkProfiles([string] $PublicUrl) {
    try {
        $adapters = @(Get-NetAdapter -IncludeHidden -ErrorAction Stop | Where-Object { $_.Status -eq 'Up' })
        $addresses = @(Get-NetIPAddress -ErrorAction Stop)
        $profiles = @(Get-NetConnectionProfile -ErrorAction Stop)
    } catch {
        throw 'No se pudo comprobar el perfil de la red activa de Windows. No se confirma la disponibilidad HTTPS en la red local.'
    }
    $localAddresses = @(foreach ($adapter in $adapters) {
        foreach ($address in $addresses) {
            $ip = $null
            if ($address.InterfaceIndex -eq $adapter.ifIndex -and $address.AddressState -eq 'Preferred' -and
                [Net.IPAddress]::TryParse([string]$address.IPAddress, [ref]$ip) -and
                -not [Net.IPAddress]::IsLoopback($ip) -and
                -not $ip.Equals([Net.IPAddress]::Any) -and -not $ip.Equals([Net.IPAddress]::IPv6Any)) {
                [pscustomobject]@{ InterfaceIndex = $adapter.ifIndex; Address = $ip }
            }
        }
    })
    $activeInterfaces = @($localAddresses | Select-Object -ExpandProperty InterfaceIndex -Unique)
    if ($activeInterfaces.Count -eq 0) {
        throw 'No hay una interfaz de red activa con dirección válida fuera de loopback. No se confirma la disponibilidad HTTPS en la red local.'
    }
    $hostName = ([Uri]$PublicUrl).DnsSafeHost.Trim('[', ']')
    $literal = $null
    $isLiteral = [Net.IPAddress]::TryParse($hostName, [ref]$literal)
    $publicAddresses = if ($isLiteral) { @($literal) } else { @(Resolve-TpvRecoveryPublicAddresses $hostName) }
    $selectedInterfaces = @($localAddresses | Where-Object {
        $address = $_.Address
        @($publicAddresses | Where-Object { $_.Equals($address) }).Count -gt 0
    } | Select-Object -ExpandProperty InterfaceIndex -Unique)
    if ($isLiteral -and $selectedInterfaces.Count -eq 0) {
        throw 'La dirección IP HTTPS instalada no pertenece a una interfaz local activa. Revise la dirección y la conexión de red antes de reintentar.'
    }
    if ($selectedInterfaces.Count -eq 0) {
        # A hostname may be unresolved or map to a NAT address. Require a usable
        # LAN profile, without rejecting unrelated public Wi-Fi/VPN adapters.
        foreach ($index in $activeInterfaces) {
            $matching = @($profiles | Where-Object { $_.InterfaceIndex -eq $index })
            if ($matching.Count -eq 1 -and [string]$matching[0].NetworkCategory -cin @('Private', 'DomainAuthenticated')) { return }
        }
        throw 'No hay una red activa Privada o de dominio autenticado compatible con las reglas HTTPS instaladas. Revise la categoría de red en Windows y reintente; el asistente no la cambia ni abre acceso en redes públicas.'
    }
    foreach ($index in $selectedInterfaces) {
        $matching = @($profiles | Where-Object { $_.InterfaceIndex -eq $index })
        if ($matching.Count -ne 1) {
            throw 'No se pudo identificar un único perfil de Windows para la interfaz HTTPS. Revise la conexión de red antes de reintentar.'
        }
        if ([string]$matching[0].NetworkCategory -cnotin @('Private', 'DomainAuthenticated')) {
            throw 'La red activa de Windows debe ser Privada o de dominio autenticado para las reglas HTTPS instaladas. Revise la categoría de red en Windows y reintente; el asistente no la cambia ni abre acceso en redes públicas.'
        }
    }
}

function Get-TpvRecoveryExposure([hashtable] $Backend, [hashtable] $Https) {
    # Enabled Domain/Private rules do not apply while NLA classifies the active
    # interface as Public (for example after rebooting an unidentified network).
    Assert-TpvRecoveryNetworkProfiles $Https.PublicUrl
    $httpsPort = ([Uri]$Https.PublicUrl).Port
    $rules = @{
        'TPVERPHTTPS-LAN' = Get-TpvRecoveryManagedRule 'TPVERPHTTPS-LAN' $Https.Program 'TCP' $httpsPort
        'TPVERPDiscovery-LAN' = Get-TpvRecoveryManagedRule 'TPVERPDiscovery-LAN' $Backend.Java 'UDP' 5353
    }
    $services = @{
        'TPVERPBackend' = Get-Service -Name 'TPVERPBackend' -ErrorAction Stop
        'TPVERPHTTPS' = Get-Service -Name 'TPVERPHTTPS' -ErrorAction Stop
    }
    foreach ($name in $services.Keys) {
        if ([string]$services[$name].StartType -notin @('Manual', 'Automatic')) {
            throw "El inicio del servicio $name no corresponde a la instalación recuperable."
        }
    }
    return @{ Rules = $rules; Services = $services }
}

function Test-TpvRecoveryExposureReady([hashtable] $Backend, [hashtable] $Https) {
    try {
        $exposure = Get-TpvRecoveryExposure $Backend $Https
        foreach ($name in $exposure.Rules.Keys) {
            if ([string]$exposure.Rules[$name].Enabled -ne 'True') { return $false }
        }
        foreach ($name in $exposure.Services.Keys) {
            if ([string]$exposure.Services[$name].StartType -ne 'Automatic') { return $false }
        }
        return $true
    } catch { return $false }
}

function Enable-TpvRecoveryExposure([hashtable] $Backend, [hashtable] $Https) {
    # Validate both installed rules and both start modes before the first change.
    $exposure = Get-TpvRecoveryExposure $Backend $Https
    $changedRules = @()
    $changedServices = @()
    try {
        foreach ($name in @('TPVERPHTTPS-LAN', 'TPVERPDiscovery-LAN')) {
            if ([string]$exposure.Rules[$name].Enabled -ne 'True') {
                Set-NetFirewallRule -Name $name -Enabled True -ErrorAction Stop | Out-Null
                $changedRules += $name
            }
        }
        foreach ($name in @('TPVERPBackend', 'TPVERPHTTPS')) {
            if ([string]$exposure.Services[$name].StartType -ne 'Automatic') {
                Set-Service -Name $name -StartupType Automatic -ErrorAction Stop
                $changedServices += $name
            }
        }
    } catch {
        foreach ($name in $changedServices) {
            try { Set-Service -Name $name -StartupType Manual -ErrorAction Stop } catch { }
        }
        foreach ($name in $changedRules) {
            try { Set-NetFirewallRule -Name $name -Enabled False -ErrorAction Stop | Out-Null } catch { }
        }
        throw
    }
}

function Test-TpvRecoveryLicenseReady([string] $LocalUrl, [string] $ExpectedInstallationId) {
    try {
        if ($ExpectedInstallationId -notmatch '^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$') { return $false }
        $response = Invoke-WebRequest -UseBasicParsing -Uri ($LocalUrl.TrimEnd('/') + '/api/v1/installation/status') -TimeoutSec 5 -ErrorAction Stop
        $status = ConvertFrom-TpvHttpJson -Content $response.Content
        return $response.StatusCode -eq 200 -and $status.mode -ceq 'LICENSED' -and
            ([string]$status.id) -ieq $ExpectedInstallationId
    } catch { return $false }
}

function Prepare-TpvCompletedRecoveryLicense([string] $Wrapper, [hashtable] $Arguments,
        [string] $PrivateDirectory, $Preflight) {
    if ($Preflight.state -cne 'COMPLETE') { throw 'La reconciliación de licencia requiere una recuperación completada.' }
    Ensure-Environment 'TPV_DB_URL' 'URL JDBC de PostgreSQL destino'
    Ensure-Environment 'TPV_DB_USERNAME' 'Usuario PostgreSQL destino'
    Ensure-Environment 'TPV_DB_PASSWORD' 'Contraseña PostgreSQL destino' -Secret
    foreach ($name in @('TPVERPHTTPS', 'TPVERPBackend')) {
        if ((Get-Service -Name $name -ErrorAction Stop).Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
            Stop-Service -Name $name -ErrorAction Stop
            (Get-Service -Name $name -ErrorAction Stop).WaitForStatus([ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromMinutes(2))
        }
    }
    $licenseArguments = $Arguments.Clone()
    $licenseArguments.Action = 'reconcile-license'
    $licenseArguments.Execute = $true
    $licenseResult = $null
    Invoke-TpvRecoveryResult $Wrapper $licenseArguments $PrivateDirectory ([ref]$licenseResult)
    if ($licenseResult.state -cne 'COMPLETE' -or $licenseResult.recoveryId -cne $Preflight.recoveryId -or
            $licenseResult.installationId -cne $Preflight.installationId) {
        throw 'La preparación de licencia no coincide con la recuperación completada.'
    }
}

function Test-TpvRecoveryServicesReady([hashtable] $Backend, [hashtable] $Https) {
    try {
        if ((Get-Service -Name 'TPVERPBackend' -ErrorAction Stop).Status -ne [ServiceProcess.ServiceControllerStatus]::Running -or
                (Get-Service -Name 'TPVERPHTTPS' -ErrorAction Stop).Status -ne [ServiceProcess.ServiceControllerStatus]::Running) {
            return $false
        }
        return (Test-TpvLocalReadiness $Backend.LocalUrl) -and
            (Test-TpvRecoveryLicenseReady $Backend.LocalUrl $Backend.RecoveryInstallationId) -and
            (Test-TpvHttpsReadiness $Https.PublicUrl) -and
            (Test-TpvRecoveryExposureReady $Backend $Https)
    } catch { return $false }
}

function Get-TpvRecoveryElapsedMilliseconds([Diagnostics.Stopwatch] $Clock) {
    return $Clock.ElapsedMilliseconds
}

function Start-TpvRecoveryBackend([hashtable] $Backend) {
    if ((Get-Service -Name 'TPVERPBackend' -ErrorAction Stop).Status -ne [ServiceProcess.ServiceControllerStatus]::Running) {
        Start-Service -Name 'TPVERPBackend' -ErrorAction Stop
        (Get-Service -Name 'TPVERPBackend' -ErrorAction Stop).WaitForStatus(
            [ServiceProcess.ServiceControllerStatus]::Running, [TimeSpan]::FromMinutes(2))
    }
    $backendReady = $false
    $readinessClock = [Diagnostics.Stopwatch]::StartNew()
    while ((Get-TpvRecoveryElapsedMilliseconds $readinessClock) -lt 300000) {
        if (Test-TpvLocalReadiness $Backend.LocalUrl) {
            $backendReady = (Get-TpvRecoveryElapsedMilliseconds $readinessClock) -le 300000
            break
        }
        $remainingMilliseconds = 300000 - (Get-TpvRecoveryElapsedMilliseconds $readinessClock)
        if ($remainingMilliseconds -le 0) { break }
        Start-Sleep -Milliseconds ([Math]::Min(2000, $remainingMilliseconds))
    }
    $readinessClock.Stop()
    if (-not $backendReady) {
        throw 'El servidor se activó, pero la disponibilidad local no confirmó HTTP 200 y JSON UP en 300 segundos. Reintente activate sin volver a activar la licencia.'
    }
}

function Start-TpvRecoveryServices([hashtable] $Backend, [hashtable] $Https) {
    # Fail before starting anything if a firewall rule belongs to another program or scope.
    Get-TpvRecoveryExposure $Backend $Https | Out-Null
    Start-TpvRecoveryBackend $Backend
    if (-not (Test-TpvRecoveryLicenseReady $Backend.LocalUrl $Backend.RecoveryInstallationId)) {
        throw 'La recuperación sigue pendiente: SaaS no ha confirmado una licencia auténtica LICENSED. Reintente activate con conexión al SaaS; no vuelva a restaurar ni autorizar.'
    }
    if ((Get-Service -Name 'TPVERPHTTPS' -ErrorAction Stop).Status -ne [ServiceProcess.ServiceControllerStatus]::Running) {
        Start-Service -Name 'TPVERPHTTPS' -ErrorAction Stop
        (Get-Service -Name 'TPVERPHTTPS' -ErrorAction Stop).WaitForStatus(
            [ServiceProcess.ServiceControllerStatus]::Running, [TimeSpan]::FromMinutes(2))
    }
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ((Get-Service -Name 'TPVERPHTTPS' -ErrorAction Stop).Status -eq [ServiceProcess.ServiceControllerStatus]::Running -and
                (Test-TpvHttpsReadiness $Https.PublicUrl)) {
            Enable-TpvRecoveryExposure $Backend $Https
            if (Test-TpvRecoveryServicesReady $Backend $Https) { return }
        }
        Start-Sleep -Seconds 2
    }
    throw 'El servidor se activó, pero HTTPS no confirmó disponibilidad TLS para la URL pública instalada.'
}

function Test-TpvAlreadyActivated([string] $State) {
    return $State -cin @('ACTIVATED', 'COMPLETE')
}

function Assert-TpvActivationApproved([string] $State) {
    if (-not (Test-TpvAlreadyActivated $State)) {
        throw 'Soporte aún no ha aprobado la activación; la cuarentena continúa.'
    }
}

function Resolve-ServiceSid([string] $ServiceAccount) {
    switch ($ServiceAccount) {
        'LocalSystem' { 'S-1-5-18' }
        'NT AUTHORITY\LocalService' { 'S-1-5-19' }
        'NT AUTHORITY\NetworkService' { 'S-1-5-20' }
        default { ([Security.Principal.NTAccount]::new($ServiceAccount)).Translate([Security.Principal.SecurityIdentifier]).Value }
    }
}

function Set-TransferDirectoryAcl([string] $Directory, [string] $ServiceAccount, [switch] $Public, [switch] $ServiceReadOnly) {
    $acl = New-Object System.Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    $full = [System.Security.AccessControl.FileSystemRights]::FullControl
    $inherit = [System.Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'
    $sids = @('S-1-5-18', 'S-1-5-32-544')
    $serviceSid = $null
    if (-not $Public) {
        $serviceSid = Resolve-ServiceSid $ServiceAccount
        $sids += $serviceSid
    } elseif (-not [string]::IsNullOrWhiteSpace($ServiceAccount)) {
        $serviceSid = Resolve-ServiceSid $ServiceAccount
        $sids += $serviceSid
    }
    foreach ($sidText in ($sids | Select-Object -Unique)) {
        $sid = [Security.Principal.SecurityIdentifier]::new($sidText)
        $rights = if (($ServiceReadOnly -or $Public) -and $sidText -eq $serviceSid) {
            [Security.AccessControl.FileSystemRights]::ReadAndExecute
        } else { $full }
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, $rights, $inherit,
            [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow))
    }
    if ($Public) {
        $users = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-545')
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($users,
            [Security.AccessControl.FileSystemRights]::ReadAndExecute, $inherit,
            [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow))
    }
    Set-Acl -LiteralPath $Directory -AclObject $acl
}

function Assert-ExactSecretAcl([string] $Path, [string] $ServiceAccount, [switch] $File, [switch] $ServiceReadOnly) {
    if ($File) { Assert-PlainFile $Path 'El secreto protegido' | Out-Null }
    else { Assert-PlainDirectory $Path 'El directorio de secretos' | Out-Null }
    $acl = Get-Acl -LiteralPath $Path
    if (-not $acl.AreAccessRulesProtected) { throw 'El secreto hereda permisos NTFS no autorizados.' }
    $serviceSid = Resolve-ServiceSid $ServiceAccount
    $expectedSids = @('S-1-5-18', 'S-1-5-32-544', $serviceSid) | Select-Object -Unique
    $expectedFlags = if ($File) { [Security.AccessControl.InheritanceFlags]::None }
        else { [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit' }
    $rules = @($acl.Access)
    if ($rules.Count -ne @($expectedSids).Count) { throw 'El secreto contiene permisos NTFS adicionales.' }
    foreach ($rule in $rules) {
        $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        $expectedRights = if ($ServiceReadOnly -and $sid -eq $serviceSid) {
            [Security.AccessControl.FileSystemRights]::ReadAndExecute
        } else { [Security.AccessControl.FileSystemRights]::FullControl }
        $expectedRights = $expectedRights -bor [Security.AccessControl.FileSystemRights]::Synchronize
        if ($sid -notin $expectedSids -or $rule.IsInherited -or
                $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
                $rule.FileSystemRights -ne $expectedRights -or
                $rule.InheritanceFlags -ne $expectedFlags -or
                $rule.PropagationFlags -ne [Security.AccessControl.PropagationFlags]::None) {
            throw 'El secreto contiene permisos NTFS distintos de los autorizados.'
        }
    }
}

function Prepare-SecretRoot([string] $Directory, [string] $ServiceAccount, [switch] $ServiceReadOnly) {
    if (-not (Test-Path -LiteralPath $Directory)) { return }
    Assert-PlainDirectory $Directory 'El directorio de secretos' | Out-Null
    $empty = $null -eq (Get-ChildItem -LiteralPath $Directory -Force | Select-Object -First 1)
    if ($empty) { Set-TransferDirectoryAcl $Directory $ServiceAccount -ServiceReadOnly:$ServiceReadOnly }
    Assert-ExactSecretAcl $Directory $ServiceAccount -ServiceReadOnly:$ServiceReadOnly
}

function Ensure-KeyDirectory([string] $Directory, [string] $ServiceAccount, [string] $RequestedAction) {
    $full = [IO.Path]::GetFullPath($Directory)
    $created = -not (Test-Path -LiteralPath $full)
    $ancestor = $full
    while (-not (Test-Path -LiteralPath $ancestor)) {
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
        if ([string]::IsNullOrWhiteSpace($ancestor)) { throw 'El directorio de claves no tiene raíz existente.' }
    }
    Assert-PlainDirectory $ancestor 'La raíz del directorio de claves' | Out-Null
    if ($created) { New-Item -ItemType Directory -Path $full -Force | Out-Null }
    Assert-PlainDirectory $full 'El directorio de claves' | Out-Null
    if ($RequestedAction -eq 'request' -and (Test-Path -LiteralPath (Join-Path $full 'installation-private.dpapi'))) {
        throw 'Este servidor ya tiene identidad de instalación; no se puede generar una solicitud de destino.'
    }
    $empty = $null -eq (Get-ChildItem -LiteralPath $full -Force | Select-Object -First 1)
    if ($created -or $empty) {
        Set-TransferDirectoryAcl $full $ServiceAccount
    } else {
        $allowed = @('S-1-5-18', 'S-1-5-32-544', (Resolve-ServiceSid $ServiceAccount),
            [Security.Principal.WindowsIdentity]::GetCurrent().User.Value)
        foreach ($rule in (Get-Acl -LiteralPath $full).Access) {
            if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
                    ($rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::ReadData) -eq 0) { continue }
            $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
            if ($sid -notin $allowed) { throw 'El directorio de claves permite lectura a otra identidad.' }
        }
    }
    return $full
}

function Ensure-PublicDirectory([string] $Directory) {
    $ancestor = [IO.Path]::GetFullPath($Directory)
    while (-not (Test-Path -LiteralPath $ancestor)) {
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
        if ([string]::IsNullOrWhiteSpace($ancestor)) { throw 'El directorio público no tiene raíz existente.' }
    }
    Assert-PlainDirectory $ancestor 'La raíz del directorio público' | Out-Null
    if (-not (Test-Path -LiteralPath $Directory)) { New-Item -ItemType Directory -Path $Directory -Force | Out-Null }
    Assert-PlainDirectory $Directory 'El directorio público de traslado' | Out-Null
    Set-TransferDirectoryAcl $Directory '' -Public
}

function Ensure-Environment([string] $Name, [string] $Label, [switch] $Secret) {
    if (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($Name, 'Process'))) { return }
    $installedValue = [Environment]::GetEnvironmentVariable($Name, 'Machine')
    if (-not [string]::IsNullOrWhiteSpace($installedValue)) {
        [Environment]::SetEnvironmentVariable($Name, $installedValue, 'Process')
        return
    }
    if ($Secret) {
        $secure = Read-Host $Label -AsSecureString
        $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try {
            $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
            if ([string]::IsNullOrWhiteSpace($plain)) { throw "$Label es obligatorio." }
            [Environment]::SetEnvironmentVariable($Name, $plain, 'Process')
        } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    } else {
        $value = Read-Host $Label
        if ([string]::IsNullOrWhiteSpace($value)) { throw "$Label es obligatorio." }
        [Environment]::SetEnvironmentVariable($Name, $value.Trim(), 'Process')
    }
}

function Read-SelectedBackup {
    Add-Type -AssemblyName System.Windows.Forms
    $dialog = [Windows.Forms.OpenFileDialog]::new()
    $dialog.Title = 'Seleccionar paquete final de traslado'
    $dialog.Filter = 'Paquete de copia (*.tpvb)|*.tpvb'
    $dialog.CheckFileExists = $true
    $dialog.Multiselect = $false
    try {
        if ($dialog.ShowDialog() -ne [Windows.Forms.DialogResult]::OK) { throw 'Selección de paquete cancelada.' }
        $backup = Assert-PlainFile $dialog.FileName 'El paquete de traslado'
        if ([IO.Path]::GetExtension($backup) -cne '.tpvb') { throw 'El paquete debe ser .tpvb.' }
        Assert-PlainFile "$backup.capsule" 'La cápsula de traslado' | Out-Null
        Assert-PlainFile "$backup.transfer.json" 'El manifiesto de traslado' | Out-Null
        return $backup
    } finally { $dialog.Dispose() }
}

function Write-PublicStatus([string] $Directory, [object] $Result, [string] $RequestedAction) {
    $public = [ordered]@{ action = $RequestedAction; checkedAt = [DateTimeOffset]::UtcNow.ToString('o') }
    foreach ($key in @('state', 'transferId', 'destinationKeyFingerprint', 'destinationPublicKey', 'destinationProof', 'releaseId')) {
        if ($null -ne $Result.PSObject.Properties[$key]) { $public[$key] = $Result.$key }
    }
    $statusFile = Join-Path $Directory 'status.json'
    $tempFile = Join-Path $Directory ([guid]::NewGuid().ToString('N') + '.status.tmp')
    [IO.File]::WriteAllText($tempFile, ($public | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $tempFile -Destination $statusFile -Force
}

function Publish-DestinationRequest([string] $PrivateDirectory, [string] $PublicDirectory) {
    $source = Assert-PlainFile (Join-Path $PrivateDirectory 'destination-request.json') 'La solicitud generada'
    if ((Get-Item -LiteralPath $source).Length -gt 32768) { throw 'La solicitud generada supera el tamaño permitido.' }
    $request = Get-Content -LiteralPath $source -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop
    if ($request.state -cne 'DESTINATION_READY' -or
            [string]::IsNullOrWhiteSpace([string]$request.destinationPublicKey) -or
            [string]::IsNullOrWhiteSpace([string]$request.destinationProof) -or
            ([string]$request.destinationPublicKey).Length -gt 2048 -or
            ([string]$request.destinationProof).Length -gt 2048) {
        throw 'La solicitud generada no contiene un preflight público válido.'
    }
    $public = [ordered]@{
        state = 'DESTINATION_READY'
        destinationPublicKey = [string]$request.destinationPublicKey
        destinationProof = [string]$request.destinationProof
    }
    foreach ($key in @('destinationKeyFingerprint', 'releaseId')) {
        if ($null -ne $request.PSObject.Properties[$key] -and ([string]$request.$key).Length -le 256) {
            $public[$key] = [string]$request.$key
        }
    }
    $temp = Join-Path $PublicDirectory ([guid]::NewGuid().ToString('N') + '.request.tmp')
    $target = Join-Path $PublicDirectory 'destination-request.json'
    [IO.File]::WriteAllText($temp, ($public | ConvertTo-Json -Depth 3), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temp -Destination $target -Force
}

function Publish-RecoveryStatus([string] $Directory, [object] $Result, [string] $RequestedAction) {
    $public = [ordered]@{ action = $RequestedAction; checkedAt = [DateTimeOffset]::UtcNow.ToString('o') }
    foreach ($key in @('state', 'recoveryId', 'destinationKeyFingerprint', 'destinationPublicKey')) {
        if ($null -ne $Result.PSObject.Properties[$key] -and $null -ne $Result.$key) {
            $public[$key] = [string]$Result.$key
        }
    }
    $target = Join-Path $Directory 'status.json'
    $temp = Join-Path $Directory ([guid]::NewGuid().ToString('N') + '.status.tmp')
    [IO.File]::WriteAllText($temp, ($public | ConvertTo-Json -Depth 3), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temp -Destination $target -Force
}

function Invoke-TpvRecoveryResult([string] $Wrapper, [hashtable] $Arguments,
        [string] $PrivateDirectory, [ref] $Result) {
    # Java reads the password from the inherited console; never pipeline this invocation.
    $resultName = 'maintenance-result-' + [guid]::NewGuid().ToString('N') + '.json'
    $resultPath = Join-Path $PrivateDirectory $resultName
    $Arguments.ResultFileName = $resultName
    try {
        & $Wrapper @Arguments
        Assert-PlainFile $resultPath 'El resultado privado de mantenimiento' | Out-Null
        if ((Get-Item -LiteralPath $resultPath).Length -gt 32768) { throw 'El resultado de mantenimiento excede el límite.' }
        $Result.Value = Get-Content -LiteralPath $resultPath -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop
    } finally {
        if (Test-Path -LiteralPath $resultPath) {
            Assert-PlainFile $resultPath 'El resultado privado de mantenimiento' | Out-Null
            Remove-Item -LiteralPath $resultPath -Force
        }
    }
}

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'La recuperación solo funciona en Windows.' }
if ($env:ProgramData -notmatch '^[A-Za-z]:\\ProgramData\\?$') { throw 'ProgramData no tiene una ruta instalada válida.' }
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    $self = Assert-PlainFile $PSCommandPath 'El asistente de recuperación'
    Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -Verb RunAs -WindowStyle Normal -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$self`" -Action $Action"
    return
}

$failed = $false
$activationResult = $null
try {
    $installRoot = Assert-PlainDirectory (Join-Path $env:ProgramData 'TPV ERP\Backend') 'La instalación backend'
    $serviceXml = Assert-PlainFile (Join-Path $installRoot 'TPVERPBackend.xml') 'El XML del servicio'
    $service = Read-InstalledService $serviceXml
    $https = Read-InstalledHttpsService $env:ProgramData
    if (-not (Test-TpvSameOrigin $https.BackendUrl $service.LocalUrl)) {
        throw 'El servicio HTTPS instalado apunta a otro backend interno.'
    }
    $java = Assert-PlainFile $service.Java 'Java instalado'
    $jar = Assert-PlainFile $service.Jar 'El JAR instalado'
    $releasesRoot = Assert-PlainDirectory (Join-Path $installRoot 'releases') 'Las releases instaladas'
    $releaseDirectory = Assert-PlainDirectory ([IO.Path]::GetDirectoryName($jar)) 'La release activa'
    if (-not [string]::Equals([IO.Path]::GetDirectoryName($releaseDirectory), $releasesRoot,
            [StringComparison]::OrdinalIgnoreCase)) { throw 'El JAR no pertenece a una release instalada.' }
    if (-not [string]::Equals($PSScriptRoot, (Join-Path $releaseDirectory 'tools'),
            [StringComparison]::OrdinalIgnoreCase)) { throw 'El asistente no pertenece a la release activa.' }
    $policyScript = Assert-PlainFile (Join-Path $PSScriptRoot 'Resolve-TpvBackendInstalledBundleVerifier.ps1') 'La politica de release instalada'
    $wrapper = Assert-PlainFile (Join-Path $PSScriptRoot 'Invoke-TpvDisasterRecovery.ps1') 'El ejecutor de recuperación'
    $policy = & $policyScript -BundleDirectory $releaseDirectory -InstalledServiceXml $serviceXml
    if ($policy.TestOnly) {
        & (Join-Path $PSScriptRoot 'Assert-TpvTestLabContext.ps1') -InstalledServiceXml $serviceXml -ApplyCliEnvironment | Out-Null
    }
    $verifier = Assert-PlainFile $policy.Verifier 'El verificador de release'
    $unboundBundle = $policy.Bundle
    $releaseId = [string]$unboundBundle.ReleaseId
    if ($releaseId -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$') { throw 'release.id no es válido.' }
    $bundle = & $verifier -BundleDirectory $releaseDirectory -ExpectedReleaseId $releaseId -AsObject
    if (-not [string]::Equals($bundle.Jar, $jar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'El JAR verificado no coincide con el servicio.'
    }

    $sharedRoot = Assert-PlainDirectory (Join-Path $env:ProgramData 'TPV ERP') 'La instalación local'
    $recoveryRoot = Join-Path $sharedRoot 'disaster-recovery'
    if (-not (Test-Path -LiteralPath $recoveryRoot)) { New-Item -ItemType Directory -Path $recoveryRoot | Out-Null }
    Assert-PlainDirectory $recoveryRoot 'La raíz de recuperación' | Out-Null
    Set-TransferDirectoryAcl $recoveryRoot $service.ServiceAccount -Public
    $privateDirectory = Join-Path $recoveryRoot 'destination'
    $publicDirectory = Join-Path $recoveryRoot 'public'
    [Environment]::SetEnvironmentVariable('TPV_BACKEND_PORT', [string]$service.Port, 'Process')
    [Environment]::SetEnvironmentVariable('TPV_BACKEND_SERVICE_ACCOUNT', $service.ServiceAccount, 'Process')
    [Environment]::SetEnvironmentVariable('TPV_TRANSFER_HTTPS_SERVICE_ACCOUNT', $https.Account, 'Process')
    Ensure-Environment 'TPV_KEY_DIRECTORY' 'Directorio de claves del servidor destino'
    Ensure-Environment 'TPV_LICENSE_SAAS_URL' 'URL SaaS de licencias'
    $alreadyActivated = $false
    $preflightResult = $null
    if ($Action -eq 'activate' -and (Test-Path -LiteralPath $privateDirectory -PathType Container)) {
        Assert-PlainDirectory $privateDirectory 'El destino de recuperación' | Out-Null
        $preflightArguments = @{ Action = 'status'; BundleDirectory = $releaseDirectory;
            ExpectedReleaseId = $releaseId; JavaExecutable = $java;
            MaintenanceDirectory = $privateDirectory }
        Invoke-TpvRecoveryResult $wrapper $preflightArguments $privateDirectory ([ref]$preflightResult)
        $alreadyActivated = Test-TpvAlreadyActivated ([string]$preflightResult.state)
    }
    if ($policy.TestOnly -and $Action -eq 'status') {
        Ensure-Environment 'TPV_DB_URL' 'URL JDBC PostgreSQL local del laboratorio'
        Ensure-Environment 'TPV_DB_USERNAME' 'Usuario PostgreSQL del laboratorio'
        Ensure-Environment 'TPV_DB_PASSWORD' 'Contraseña PostgreSQL del laboratorio' -Secret
    }
    if ($Action -ne 'status' -and -not $alreadyActivated) {
        Ensure-Environment 'TPV_DB_URL' 'URL JDBC de PostgreSQL destino'
        Ensure-Environment 'TPV_DB_USERNAME' 'Usuario PostgreSQL destino'
        Ensure-Environment 'TPV_DB_PASSWORD' 'Contraseña PostgreSQL destino' -Secret
        Ensure-Environment 'TPV_BACKUP_DIRECTORY' 'Directorio de copias del servidor destino'
        Ensure-Environment 'TPV_PRODUCT_IMAGE_DIRECTORY' 'Directorio de imágenes de producto destino'
        Ensure-Environment 'TPV_DOCUMENT_TEMPLATE_DIRECTORY' 'Directorio de plantillas destino'
        Ensure-Environment 'TPV_VERIFACTU_SECRET_DIRECTORY' 'Directorio de secretos VeriFactu destino'
        Ensure-Environment 'TPV_TRANSFER_PUBLIC_HOST' 'Nombre público HTTPS del destino'
        $recoveryHost = [Environment]::GetEnvironmentVariable('TPV_TRANSFER_PUBLIC_HOST', 'Process')
        $installedHost = ([Uri]$https.PublicUrl).DnsSafeHost
        if (-not (Test-TpvSameHost $recoveryHost $installedHost)) {
            throw 'El nombre público de recuperación difiere del servicio HTTPS instalado.'
        }
        Prepare-SecretRoot ([Environment]::GetEnvironmentVariable('TPV_VERIFACTU_SECRET_DIRECTORY', 'Process')) $service.ServiceAccount
        Ensure-KeyDirectory ([Environment]::GetEnvironmentVariable('TPV_KEY_DIRECTORY', 'Process')) $service.ServiceAccount $Action | Out-Null
    }
    $tlsDirectory = Join-Path $env:ProgramData 'TPV ERP\HTTPS\tls'
    [Environment]::SetEnvironmentVariable('TPV_TRANSFER_TLS_DIRECTORY', $tlsDirectory, 'Process')
    if ($Action -ne 'status' -and -not $alreadyActivated) {
        Prepare-SecretRoot $tlsDirectory $https.Account -ServiceReadOnly
        $tlsPrivateKey = Join-Path $tlsDirectory 'private-key.pem'
        if (Test-Path -LiteralPath $tlsPrivateKey) {
            Assert-ExactSecretAcl $tlsPrivateKey $https.Account -File -ServiceReadOnly
        }
    }
    if (-not (Test-Path -LiteralPath $privateDirectory)) {
        if ($Action -eq 'status') {
            Write-Host 'No hay recuperación iniciada.'
            return
        }
        New-Item -ItemType Directory -Path $privateDirectory -Force | Out-Null
    }
    Prepare-SecretRoot $privateDirectory $service.ServiceAccount
    Ensure-PublicDirectory $publicDirectory

    if ($Action -in @('request', 'receive') -or ($Action -eq 'activate' -and -not $alreadyActivated)) {
        $backend = Get-Service -Name 'TPVERPBackend' -ErrorAction Stop
        if ($backend.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
            Stop-Service -Name 'TPVERPBackend' -ErrorAction Stop
            (Get-Service -Name 'TPVERPBackend').WaitForStatus([ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromMinutes(2))
        }
    }
    $arguments = @{ Action = $Action; BundleDirectory = $releaseDirectory; ExpectedReleaseId = $releaseId
        JavaExecutable = $java; MaintenanceDirectory = $privateDirectory }
    if ($alreadyActivated -and $preflightResult.state -ceq 'COMPLETE') {
        $activationResult = $preflightResult
        # An unavailable HTTP process is not evidence that its already authenticated license needs reconciliation.
        Get-TpvRecoveryExposure $service $https | Out-Null
        Start-TpvRecoveryBackend $service
    }
    if ($alreadyActivated -and $preflightResult.state -ceq 'COMPLETE' -and
            -not (Test-TpvRecoveryLicenseReady $service.LocalUrl $preflightResult.installationId)) {
        $activationResult = $preflightResult
        # Upgrade of a previously completed recovery: prepare only its credential-bound license handoff.
        Prepare-TpvCompletedRecoveryLicense $wrapper $arguments $privateDirectory $preflightResult
    }
    if ($Action -eq 'receive') {
        $recoveryId = Read-Host 'ID de recuperación autorizado por soporte'
        if ($recoveryId -notmatch '^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$') {
            throw 'El ID de recuperación no es válido.'
        }
        $arguments.RecoveryId = $recoveryId
    }
    if ($Action -ne 'status') { $arguments.Execute = $true }
    $result = $null
    if ($alreadyActivated) { $result = $preflightResult }
    else { Invoke-TpvRecoveryResult $wrapper $arguments $privateDirectory ([ref]$result) }
    if ([string]::IsNullOrWhiteSpace([string]$result.state)) { throw 'La herramienta devolvió un estado inválido.' }
    if ($result.state -cin @('ACTIVATED', 'COMPLETE')) {
        $service.RecoveryInstallationId = [string]$result.installationId
    }
    if ($Action -eq 'status' -and $result.state -ceq 'DESTINATION_READY' -and
            $null -eq $result.PSObject.Properties['destinationPublicKey']) {
        $requestPath = Join-Path $privateDirectory 'destination-request.json'
        if (Test-Path -LiteralPath $requestPath -PathType Leaf) {
            Assert-PlainFile $requestPath 'La solicitud pública del destino' | Out-Null
            if ((Get-Item -LiteralPath $requestPath).Length -le 32768) {
                $savedRequest = Get-Content -LiteralPath $requestPath -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop
                if ($savedRequest.state -ceq 'DESTINATION_READY' -and
                        ([string]$savedRequest.destinationPublicKey) -match '^[A-Za-z0-9+/=]{128,2048}$') {
                    $result | Add-Member -NotePropertyName destinationPublicKey -NotePropertyValue ([string]$savedRequest.destinationPublicKey)
                }
            }
        }
    }
    if ($Action -eq 'status' -and $result.state -cin @('ACTIVATED', 'COMPLETE')) {
        if (-not (Test-TpvRecoveryServicesReady $service $https)) { $result.state = 'ACTIVATED_SERVICE_UNREADY' }
    }
    if ($Action -eq 'request' -and $result.state -ceq 'DESTINATION_READY') {
        $request = [ordered]@{ state = 'DESTINATION_READY'; destinationPublicKey = [string]$result.destinationPublicKey
            destinationKeyFingerprint = [string]$result.destinationKeyFingerprint }
        if ($request.destinationPublicKey.Length -lt 128 -or $request.destinationPublicKey.Length -gt 2048) {
            throw 'La solicitud generada no contiene una clave pública válida.'
        }
        $target = Join-Path $publicDirectory 'destination-request.json'
        $temp = Join-Path $publicDirectory ([guid]::NewGuid().ToString('N') + '.request.tmp')
        [IO.File]::WriteAllText($temp, ($request | ConvertTo-Json -Depth 3), [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $temp -Destination $target -Force
        Write-Host "Solicitud pública: $target" -ForegroundColor Cyan
        Invoke-Item -LiteralPath $publicDirectory
    }
    Write-Host "Estado de recuperación: $($result.state)" -ForegroundColor Green
    if ($Action -eq 'activate') {
        Assert-TpvActivationApproved ([string]$result.state)
        $activationResult = $result
        Start-TpvRecoveryServices $service $https
    }
    Publish-RecoveryStatus $publicDirectory $result $Action
} catch {
    $failed = $true
    if ($null -ne $activationResult) {
        try { Publish-RecoveryStatus $publicDirectory ([pscustomobject]@{
            state = 'ACTIVATED_SERVICE_UNREADY'; recoveryId = $activationResult.recoveryId
        }) $Action } catch { }
    }
    Write-Host "Mantenimiento detenido: $($_.Exception.Message)" -ForegroundColor Red
} finally {
    [Environment]::SetEnvironmentVariable('TPV_DB_PASSWORD', $null, 'Process')
    Read-Host 'Pulse Intro para cerrar esta consola' | Out-Null
}
if ($failed) { exit 1 }
