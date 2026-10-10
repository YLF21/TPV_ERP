Set-StrictMode -Version Latest

function Assert-TpvPort([object] $Port) {
    if ($Port -isnot [int] -or $Port -lt 1 -or $Port -gt 65535) {
        throw 'El puerto debe ser un entero entre 1 y 65535.'
    }
}

function ConvertTo-TpvIpAddress([string] $Address) {
    if ([string]::IsNullOrWhiteSpace($Address) -or $Address -ne $Address.Trim() -or $Address.Contains('%')) {
        throw 'La direccion debe ser una IP literal sin zona ni espacios.'
    }
    $ip = $null
    if (-not [Net.IPAddress]::TryParse($Address, [ref] $ip)) {
        throw 'La direccion debe ser una IP literal.'
    }
    if ($ip.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetwork -and
        $Address -notmatch '^([0-9]{1,3}\.){3}[0-9]{1,3}$') {
        throw 'La direccion IPv4 debe tener cuatro octetos.'
    }
    return $ip
}

function ConvertTo-TpvBackendAddress([string] $Address) {
    $ip = ConvertTo-TpvIpAddress $Address
    if (-not [Net.IPAddress]::IsLoopback($ip) -or $ip.IsIPv4MappedToIPv6) {
        throw 'El backend debe escuchar en una direccion IP de loopback.'
    }
    return $ip.ToString().ToLowerInvariant()
}

function ConvertTo-TpvPublicHost([string] $HostName) {
    if ([string]::IsNullOrWhiteSpace($HostName) -or $HostName -ne $HostName.Trim() -or
        $HostName -match '[\s/@?#\\%]' -or $HostName.Contains('[') -xor $HostName.Contains(']')) {
        throw 'PublicHost debe ser un nombre DNS o una IP sin esquema, ruta ni puerto.'
    }
    $literal = $HostName
    if ($literal.StartsWith('[') -and $literal.EndsWith(']')) {
        $literal = $literal.Substring(1, $literal.Length - 2)
    } elseif ($HostName.Contains('[') -or $HostName.Contains(']')) {
        throw 'PublicHost contiene corchetes invalidos.'
    }
    $ip = $null
    if ([Net.IPAddress]::TryParse($literal, [ref] $ip)) {
        if ($ip.IsIPv4MappedToIPv6 -or
            ($ip.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetwork -and
             $literal -notmatch '^([0-9]{1,3}\.){3}[0-9]{1,3}$')) {
            throw 'PublicHost contiene una IP ambigua.'
        }
        return $ip.ToString().ToLowerInvariant()
    }
    if ($HostName.Contains(':')) { throw 'PublicHost no puede incluir un puerto.' }
    $dns = $HostName.TrimEnd('.').ToLowerInvariant()
    if ($dns.Length -eq 0 -or $dns.Length -gt 253 -or $HostName.EndsWith('..')) {
        throw 'PublicHost contiene un nombre DNS invalido.'
    }
    foreach ($label in $dns.Split('.')) {
        if ($label -notmatch '^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$') {
            throw 'PublicHost contiene un nombre DNS invalido.'
        }
    }
    return $dns
}

function New-TpvPortPlanObject([string] $BackendAddress, [string] $PublicHost,
                               [int] $BackendPort, [int] $HttpsPort) {
    $backendHost = $BackendAddress
    if ($backendHost.Contains(':')) { $backendHost = '[' + $backendHost + ']' }
    $publicUrlHost = $PublicHost
    if ($publicUrlHost.Contains(':')) { $publicUrlHost = '[' + $publicUrlHost + ']' }
    $backendUrl = ([Uri] "http://${backendHost}:$BackendPort").GetLeftPart([UriPartial]::Authority)
    $publicUrl = ([Uri] "https://${publicUrlHost}:$HttpsPort").GetLeftPart([UriPartial]::Authority)
    return [pscustomobject]@{
        SchemaVersion = 1
        BackendAddress = $BackendAddress
        PublicHost = $PublicHost
        BackendPort = $BackendPort
        HttpsPort = $HttpsPort
        BackendUrl = $backendUrl
        PublicUrl = $publicUrl
    }
}

function Assert-TpvServerPortPlan([object] $Plan) {
    $names = @('SchemaVersion', 'BackendAddress', 'PublicHost', 'BackendPort',
               'HttpsPort', 'BackendUrl', 'PublicUrl')
    if ($Plan -isnot [pscustomobject] -or @($Plan.PSObject.Properties).Count -ne $names.Count) {
        throw 'El plan de puertos tiene un esquema invalido.'
    }
    foreach ($name in $names) {
        if ($null -eq $Plan.PSObject.Properties[$name]) {
            throw "Falta el campo $name en el plan de puertos."
        }
    }
    if ($Plan.SchemaVersion -isnot [int] -or $Plan.SchemaVersion -ne 1 -or
        $Plan.BackendAddress -isnot [string] -or $Plan.PublicHost -isnot [string] -or
        $Plan.BackendUrl -isnot [string] -or $Plan.PublicUrl -isnot [string]) {
        throw 'El plan de puertos tiene tipos o version invalidos.'
    }
    Assert-TpvPort $Plan.BackendPort
    Assert-TpvPort $Plan.HttpsPort
    if ($Plan.BackendPort -eq $Plan.HttpsPort) { throw 'Los puertos deben ser distintos.' }
    $backendAddress = ConvertTo-TpvBackendAddress $Plan.BackendAddress
    $publicHost = ConvertTo-TpvPublicHost $Plan.PublicHost
    if ($Plan.BackendAddress -cne $backendAddress -or $Plan.PublicHost -cne $publicHost) {
        throw 'El plan de puertos contiene direcciones sin normalizar.'
    }
    $expected = New-TpvPortPlanObject $backendAddress $publicHost $Plan.BackendPort $Plan.HttpsPort
    if ($Plan.BackendUrl -cne $expected.BackendUrl -or $Plan.PublicUrl -cne $expected.PublicUrl) {
        throw 'Las URL no coinciden con las direcciones y puertos del plan.'
    }
    return $expected
}

function Assert-TpvRegularPath([string] $Path, [bool] $MustExist) {
    if ([string]::IsNullOrWhiteSpace($Path)) { throw 'Se requiere Path.' }
    $fullPath = [IO.Path]::GetFullPath($Path)
    $parent = [IO.Path]::GetDirectoryName($fullPath)
    if ([string]::IsNullOrEmpty($parent) -or [string]::Equals($fullPath, $parent, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Path debe identificar un archivo.'
    }
    $current = $parent
    while ($true) {
        $item = Get-Item -LiteralPath $current -Force -ErrorAction Stop
        if (-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "La ruta contiene un ancestro inseguro: $current"
        }
        $next = [IO.Path]::GetDirectoryName($current.TrimEnd('\', '/'))
        if ([string]::IsNullOrEmpty($next) -or $next -eq $current) { break }
        $current = $next
    }
    if (Test-Path -LiteralPath $fullPath) {
        $item = Get-Item -LiteralPath $fullPath -Force -ErrorAction Stop
        if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "El plan debe ser un archivo regular: $fullPath"
        }
    } elseif ($MustExist) {
        throw "No existe el plan de puertos: $fullPath"
    }
    return $fullPath
}

function Test-TpvTcpPortAvailable {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string] $Address,
          [Parameter(Mandatory = $true)][int] $Port)
    Assert-TpvPort $Port
    $ip = ConvertTo-TpvIpAddress $Address
    $sockets = New-Object 'System.Collections.Generic.List[System.Net.Sockets.Socket]'
    try {
        $socket = New-Object Net.Sockets.Socket($ip.AddressFamily, [Net.Sockets.SocketType]::Stream, [Net.Sockets.ProtocolType]::Tcp)
        $sockets.Add($socket)
        $socket.ExclusiveAddressUse = $true
        if ($ip.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetworkV6) { $socket.DualMode = $false }
        $socket.Bind([Net.IPEndPoint]::new($ip, $Port))
        if ($ip.Equals([Net.IPAddress]::Any) -and [Net.Sockets.Socket]::OSSupportsIPv6) {
            $ipv6 = New-Object Net.Sockets.Socket([Net.Sockets.AddressFamily]::InterNetworkV6,
                                                  [Net.Sockets.SocketType]::Stream,
                                                  [Net.Sockets.ProtocolType]::Tcp)
            $sockets.Add($ipv6)
            $ipv6.ExclusiveAddressUse = $true
            $ipv6.DualMode = $false
            $ipv6.Bind([Net.IPEndPoint]::new([Net.IPAddress]::IPv6Any, $Port))
        }
        return $true
    } catch [Net.Sockets.SocketException] {
        return $false
    } finally {
        foreach ($socket in $sockets) { $socket.Dispose() }
    }
}

function New-TpvServerPortPlan {
    [CmdletBinding()]
    param([string] $BackendAddress = '127.0.0.1',
          [Parameter(Mandatory = $true)][string] $PublicHost,
          [int] $PreferredBackendPort = 8080,
          [int] $PreferredHttpsPort = 8443,
          [int] $ExistingBackendPort = 0,
          [int] $ExistingHttpsPort = 0)
    $backendAddress = ConvertTo-TpvBackendAddress $BackendAddress
    $publicHost = ConvertTo-TpvPublicHost $PublicHost
    Assert-TpvPort $PreferredBackendPort
    Assert-TpvPort $PreferredHttpsPort
    if ($ExistingBackendPort -ne 0) { Assert-TpvPort $ExistingBackendPort }
    if ($ExistingHttpsPort -ne 0) { Assert-TpvPort $ExistingHttpsPort }
    if ($ExistingBackendPort -ne 0 -and $ExistingBackendPort -eq $ExistingHttpsPort) {
        throw 'Los puertos existentes de backend y HTTPS deben ser distintos.'
    }
    $backendCandidates = @($PreferredBackendPort, 18080, 28080, 38080) + @(18081..18180)
    $httpsCandidates = @($PreferredHttpsPort, 18443, 28443, 38443) + @(18444..18543)
    $backendPort = $null
    if ($ExistingBackendPort -ne 0) {
        $backendPort = $ExistingBackendPort
    } else {
        foreach ($candidate in ($backendCandidates | Select-Object -Unique)) {
            if ($candidate -ne $ExistingHttpsPort -and
                (Test-TpvTcpPortAvailable -Address $backendAddress -Port $candidate)) {
                $backendPort = [int] $candidate
                break
            }
        }
    }
    if ($null -eq $backendPort) { throw 'No hay un puerto backend disponible.' }
    $httpsPort = $null
    if ($ExistingHttpsPort -ne 0) {
        $httpsPort = $ExistingHttpsPort
    } else {
        foreach ($candidate in ($httpsCandidates | Select-Object -Unique)) {
            if ($candidate -ne $backendPort -and
                (Test-TpvTcpPortAvailable -Address '0.0.0.0' -Port $candidate)) {
                $httpsPort = [int] $candidate
                break
            }
        }
    }
    if ($null -eq $httpsPort) { throw 'No hay un puerto HTTPS disponible.' }
    return New-TpvPortPlanObject $backendAddress $publicHost $backendPort $httpsPort
}

function Read-TpvServerPortPlan {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string] $Path)
    $fullPath = Assert-TpvRegularPath $Path $true
    $file = Get-Item -LiteralPath $fullPath -Force -ErrorAction Stop
    if ($file.Length -gt 16384 -or $file.Length -eq 0) { throw 'El archivo de puertos tiene un tamano invalido.' }
    $json = [IO.File]::ReadAllText($fullPath, [Text.UTF8Encoding]::new($false, $true))
    $names = @('SchemaVersion', 'BackendAddress', 'PublicHost', 'BackendPort',
               'HttpsPort', 'BackendUrl', 'PublicUrl')
    $keys = [regex]::Matches($json, '"(?<key>(?:\\.|[^"\\])*)"\s*:')
    if ($keys.Count -ne $names.Count) { throw 'El JSON contiene campos duplicados o inesperados.' }
    foreach ($name in $names) {
        if (@($keys | Where-Object { $_.Groups['key'].Value -ceq $name }).Count -ne 1) {
            throw "El JSON no contiene exactamente un campo $name."
        }
    }
    try { $plan = ConvertFrom-Json -InputObject $json -ErrorAction Stop }
    catch { throw 'El archivo de puertos contiene JSON invalido.' }
    return Assert-TpvServerPortPlan $plan
}

function Set-TpvPlanFileAcl([string] $Path) {
    $acl = New-Object Security.AccessControl.FileSecurity
    $acl.SetAccessRuleProtection($true, $false)
    $full = [Security.AccessControl.FileSystemRights]::FullControl
    foreach ($sid in @('S-1-5-32-544', 'S-1-5-18')) {
        $identity = New-Object Security.Principal.SecurityIdentifier($sid)
        $rule = New-Object Security.AccessControl.FileSystemAccessRule(
            $identity, $full, [Security.AccessControl.InheritanceFlags]::None,
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow)
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl -ErrorAction Stop
}

function Assert-TpvAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    if (-not ([Security.Principal.WindowsPrincipal]::new($identity)).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Es necesario ejecutar como administrador para guardar el plan de puertos.'
    }
}

function Enter-TpvServerInstallationLock {
    [CmdletBinding()]
    param([string] $Name = 'Global\TPVERPServerInstallation')
    if ($Name -notmatch '^Global\\TPVERPServerInstallation(?:-[A-Za-z0-9-]{1,64})?$') {
        throw 'El nombre del bloqueo de instalacion no es valido.'
    }
    $mutex = $null
    $acquired = $false
    try {
        $mutex = [Threading.Mutex]::new($false, $Name)
        try {
            $acquired = $mutex.WaitOne(0)
        } catch [Threading.AbandonedMutexException] {
            $acquired = $true
        }
        if (-not $acquired) {
            throw 'Otra instalacion del servidor TPV ERP esta en curso.'
        }
        return $mutex
    } catch {
        if ($null -ne $mutex) { $mutex.Dispose() }
        if ($_.Exception.Message -eq 'Otra instalacion del servidor TPV ERP esta en curso.') { throw }
        throw "No se pudo adquirir el bloqueo de instalacion del servidor TPV ERP: $($_.Exception.Message)"
    }
}

function Exit-TpvServerInstallationLock {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][Threading.Mutex] $Mutex)
    try {
        $Mutex.ReleaseMutex()
    } finally {
        $Mutex.Dispose()
    }
}

function Write-TpvServerPortPlan {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string] $Path,
          [Parameter(Mandatory = $true)][object] $Plan)
    Assert-TpvAdministrator
    $validPlan = Assert-TpvServerPortPlan $Plan
    $fullPath = Assert-TpvRegularPath $Path $false
    if (Test-Path -LiteralPath $fullPath) {
        $existing = Read-TpvServerPortPlan -Path $fullPath
        if (($existing | ConvertTo-Json -Compress) -cne ($validPlan | ConvertTo-Json -Compress)) {
            throw 'Ya existe un plan de puertos diferente.'
        }
        Set-TpvPlanFileAcl $fullPath
        return $existing
    }
    $parent = [IO.Path]::GetDirectoryName($fullPath)
    $staging = [IO.Path]::Combine($parent, '.' + [IO.Path]::GetFileName($fullPath) + '.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $json = $validPlan | ConvertTo-Json -Depth 2
        [IO.File]::WriteAllText($staging, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
        Set-TpvPlanFileAcl $staging
        Assert-TpvRegularPath $Path $false | Out-Null
        [IO.File]::Move($staging, $fullPath)
    } catch {
        if (Test-Path -LiteralPath $fullPath) {
            $existing = Read-TpvServerPortPlan -Path $fullPath
            if (($existing | ConvertTo-Json -Compress) -ceq ($validPlan | ConvertTo-Json -Compress)) {
                Set-TpvPlanFileAcl $fullPath
                return $existing
            }
        }
        throw
    } finally {
        if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Force }
    }
    return $validPlan
}

Export-ModuleMember -Function Test-TpvTcpPortAvailable, New-TpvServerPortPlan,
                              Read-TpvServerPortPlan, Write-TpvServerPortPlan,
                              ConvertTo-TpvPublicHost, ConvertTo-TpvBackendAddress,
                              Enter-TpvServerInstallationLock, Exit-TpvServerInstallationLock
