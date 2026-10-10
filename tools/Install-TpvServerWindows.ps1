[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)] [string] $BundleDirectory,
    [Parameter(Mandatory)] [string] $ExpectedReleaseId,
    [Parameter(Mandatory)] [string] $WinSwExecutable,
    [Parameter(Mandatory)] [ValidatePattern('^[0-9a-fA-F]{64}$')] [string] $WinSwSha256,
    [Parameter(Mandatory)] [string] $JavaExecutable,
    [Parameter(Mandatory)] [string] $ConfigurationFile,
    [Parameter(Mandatory)] [string] $CaddyExecutable,
    [Parameter(Mandatory)] [ValidatePattern('^[0-9a-fA-F]{64}$')] [string] $CaddySha256,
    [Parameter(Mandatory)] [string] $CertificateFile,
    [Parameter(Mandatory)] [string] $PrivateKeyFile,
    [Parameter(Mandatory)] [string] $PublicHost,
    [string] $ServerRoot = 'C:\ProgramData\TPV ERP\Server',
    [string] $BackendInstallRoot = 'C:\ProgramData\TPV ERP\Backend',
    [string] $HttpsInstallRoot = 'C:\ProgramData\TPV ERP\HTTPS',
    [string] $BackendAddress = '127.0.0.1',
    [ValidateRange(1, 65535)] [int] $PreferredBackendPort = 8080,
    [ValidateRange(1, 65535)] [int] $PreferredHttpsPort = 8443,
    [string] $SecretRoot = 'C:\ProgramData\TPV ERP\secrets',
    [string] $ExportRoot = 'C:\ProgramData\TPV ERP\exports',
    [ValidateSet('Register', 'Start')] [string] $Phase = 'Register',
    [switch] $Preflight
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'TpvServerPorts.psm1') -Force

function Assert-TpvServerPath([string] $Path) {
    $full = [IO.Path]::GetFullPath($Path)
    if ([IO.Path]::GetPathRoot($full) -eq $full) { throw 'No se admite una raiz de unidad.' }
    $cursor = $full
    while ($cursor) {
        $item = Get-Item -LiteralPath $cursor -Force -ErrorAction SilentlyContinue
        if ($null -ne $item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Ruta con reparse point no permitida: $cursor"
        }
        $parent = [IO.Path]::GetDirectoryName($cursor)
        if ($parent -eq $cursor) { break }
        $cursor = $parent
    }
    return $full
}

function Initialize-TpvServerRoot([string] $Path) {
    $full = Assert-TpvServerPath $Path
    [void][IO.Directory]::CreateDirectory($full)
    [void](Assert-TpvServerPath $full)
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    $admin = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    $acl.SetOwner($admin)
    foreach ($sid in @($admin, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            $sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
    }
    Set-Acl -LiteralPath $full -AclObject $acl
}

function Get-TpvOwnedService([string] $Name, [string] $Root) {
    $service = Get-CimInstance Win32_Service -Filter "Name = '$Name'"
    if ($null -eq $service) { return $null }
    $expected = Join-Path ([IO.Path]::GetFullPath($Root)) "$Name.exe"
    if ([string]$service.PathName -notmatch ('^\s*"?' + [Regex]::Escape($expected) + '"?\s*$') -or
        -not [StringComparer]::OrdinalIgnoreCase.Equals([string]$service.StartName, "NT SERVICE\$Name")) {
        throw "El servicio $Name no pertenece a esta instalacion o usa otra cuenta; no se modifica."
    }
    return $service
}

function Read-TpvInstalledServiceXml([string] $Path) {
    [void](Assert-TpvServerPath $Path)
    $settings = [Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $reader = [Xml.XmlReader]::Create($Path, $settings)
    try { $xml = [xml]::new(); $xml.XmlResolver = $null; $xml.Load($reader); return $xml }
    finally { $reader.Dispose() }
}

function Resolve-TpvInstalledPortPlan([hashtable] $Options, $BackendService, $HttpsService) {
    $path = Join-Path $Options.ServerRoot 'server-network.json'
    [void](Assert-TpvServerPath $path)
    if (Test-Path -LiteralPath $path) {
        $plan = Read-TpvServerPortPlan -Path $path
        if ($plan.PublicHost -cne $Options.PublicHost -or $plan.BackendAddress -cne $Options.BackendAddress) {
            throw 'La autoridad HTTPS o la direccion interna difiere de la configuracion guardada; no se cambia automaticamente.'
        }
        return $plan
    }
    if ($Options.Phase -eq 'Start') { throw 'Falta server-network.json; complete primero la fase Register.' }
    $existingBackendPort = 0
    $existingHttpsPort = 0
    if ($null -ne $BackendService) {
        $xml = Read-TpvInstalledServiceXml (Join-Path $Options.BackendInstallRoot 'TPVERPBackend.xml')
        $matches = [Regex]::Matches([string]$xml.service.arguments, '(?:^|\s)--server.port=(\d+)(?=\s|$)')
        if ($matches.Count -ne 1 -or [string]$xml.service.id -cne 'TPVERPBackend') {
            throw 'No se puede adoptar con seguridad el puerto del servicio backend existente.'
        }
        $existingBackendPort = [int]$matches[0].Groups[1].Value
        $addresses = [Regex]::Matches([string]$xml.service.arguments, '(?:^|\s)--server.address=([^\s]+)(?=\s|$)')
        if ($addresses.Count -ne 1 -or
            (ConvertTo-TpvBackendAddress $addresses[0].Groups[1].Value) -cne $Options.BackendAddress) {
            throw 'La escucha del backend existente no coincide con la direccion solicitada.'
        }
    }
    if ($null -ne $HttpsService) {
        $xml = Read-TpvInstalledServiceXml (Join-Path $Options.HttpsInstallRoot 'TPVERPHTTPS.xml')
        $origins = @($xml.service.env | Where-Object { $_.name -ceq 'TPV_HTTPS_PUBLIC_URL' })
        $uri = $null
        if ($origins.Count -ne 1 -or
            -not [Uri]::TryCreate([string]$origins[0].value, [UriKind]::Absolute, [ref]$uri) -or
            $uri.Scheme -ne 'https' -or
            (ConvertTo-TpvPublicHost $uri.DnsSafeHost.Trim('[', ']')) -cne $Options.PublicHost) {
            throw 'No se puede adoptar con seguridad el puerto del servicio HTTPS existente.'
        }
        $existingHttpsPort = $uri.Port
    }
    return New-TpvServerPortPlan -BackendAddress $Options.BackendAddress -PublicHost $Options.PublicHost `
        -PreferredBackendPort $Options.PreferredBackendPort -PreferredHttpsPort $Options.PreferredHttpsPort `
        -ExistingBackendPort $existingBackendPort -ExistingHttpsPort $existingHttpsPort
}

function Invoke-TpvServerStep([string] $File, [hashtable] $Arguments) {
    & (Join-Path $PSScriptRoot $File) @Arguments
}

function Assert-TpvServerDesktopOrigin([string] $BackendUrl) {
    $path = 'C:\ProgramData\TPV ERP\desktop\backend-config.json'
    [void](Assert-TpvServerPath $path)
    if (Test-Path -LiteralPath $path) {
        $existing = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
        if ([string]$existing.backendUrl -cne $BackendUrl) {
            throw 'Las aplicaciones de este ordenador estan configuradas para otro destino; no se sobrescribe su conexion.'
        }
    }
}

function Write-TpvServerDesktopOrigin([string] $BackendUrl) {
    & (Join-Path $PSScriptRoot '..\frontend\tools\write-backend-config.ps1') -BackendUrl $BackendUrl
}

function Write-TpvServerDeploymentState([hashtable] $Options, [string] $Stage) {
    $path = Join-Path $Options.ServerRoot 'deployment-state.json'
    [void](Assert-TpvServerPath $path)
    $staging = Join-Path $Options.ServerRoot ('.deployment-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    $state = [ordered]@{
        SchemaVersion = 1; Phase = $Options.Phase; Stage = $Stage
        ReleaseId = $Options.ExpectedReleaseId; UpdatedAtUtc = [DateTime]::UtcNow.ToString('o')
    }
    try {
        [IO.File]::WriteAllText($staging, ($state | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
        $acl = [Security.AccessControl.FileSecurity]::new()
        $acl.SetAccessRuleProtection($true, $false)
        $admin = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
        $acl.SetOwner($admin)
        foreach ($sid in @($admin, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
            $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'Allow'))
        }
        Set-Acl -LiteralPath $staging -AclObject $acl
        if (Test-Path -LiteralPath $path) {
            Set-Acl -LiteralPath $path -AclObject $acl
            [IO.File]::Replace($staging, $path, $null)
        } else { [IO.File]::Move($staging, $path) }
    } finally { if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Force } }
}

function Set-TpvServerFirewall([string] $Name, [string] $Program, [string] $Protocol, [int] $Port,
    [bool] $Enabled, [switch] $Create, [switch] $ValidateOnly) {
    $rule = Get-NetFirewallRule -Name $Name -ErrorAction SilentlyContinue
    if ($null -eq $rule) {
        if (-not $Create) { throw "Falta la regla de firewall $Name; complete Register." }
        if ($ValidateOnly) { return }
        New-NetFirewallRule -Name $Name -DisplayName $Name -Description 'TPV ERP server installer managed' `
            -Direction Inbound -Action Allow -Protocol $Protocol -LocalPort $Port -Program $Program `
            -RemoteAddress LocalSubnet -Profile Domain,Private -Enabled False | Out-Null
        return
    }
    $filter = $rule | Get-NetFirewallPortFilter
    $application = $rule | Get-NetFirewallApplicationFilter
    $address = $rule | Get-NetFirewallAddressFilter
    if ([string]$rule.Description -cne 'TPV ERP server installer managed' -or
        [string]$rule.Direction -ne 'Inbound' -or [string]$rule.Action -ne 'Allow' -or
        [string]$filter.Protocol -ne $Protocol -or [string]$filter.LocalPort -ne [string]$Port -or
        -not [StringComparer]::OrdinalIgnoreCase.Equals([string]$application.Program, $Program) -or
        [string]$address.RemoteAddress -ne 'LocalSubnet' -or
        ((@(([string]$rule.Profile).Split(',') | ForEach-Object { $_.Trim() } | Sort-Object) -join ',') -ne 'Domain,Private')) {
        throw "La regla $Name existe con una configuracion diferente; no se reemplaza."
    }
    if ($ValidateOnly) { return }
    Set-NetFirewallRule -Name $Name -Enabled $(if ($Enabled) { 'True' } else { 'False' }) | Out-Null
}

function Stop-TpvRegisteredService([string] $Name, $Service) {
    if ($null -ne $Service -and [string]$Service.State -ne 'Stopped') {
        Stop-Service -Name $Name -ErrorAction Stop
        (Get-Service -Name $Name).WaitForStatus([ServiceProcess.ServiceControllerStatus]::Stopped,
            [TimeSpan]::FromSeconds(30))
    }
}

function Assert-TpvSavedPortsAvailable($Plan) {
    foreach ($entry in @(@($Plan.BackendAddress, $Plan.BackendPort), @('0.0.0.0', $Plan.HttpsPort))) {
        if (-not (Test-TpvTcpPortAvailable -Address $entry[0] -Port $entry[1])) {
            throw "El puerto guardado $($entry[1]) no esta disponible; no se reasigna ni se cierra el programa que lo ocupa."
        }
    }
}

function Wait-TpvBackendReady([string] $BackendUrl) {
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        try {
            $response = Invoke-WebRequest -Uri "$BackendUrl/actuator/health" -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -eq 200 -and ($response.Content | ConvertFrom-Json).status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'El backend no alcanzo el estado UP; se conserva el puerto guardado.'
}

function Wait-TpvHttpsReady([string] $PublicUrl) {
    $uri = [Uri]$PublicUrl
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $client = [Net.Sockets.TcpClient]::new()
        $ssl = $null
        try {
            if (-not $client.ConnectAsync('127.0.0.1', $uri.Port).Wait(2000)) { throw 'HTTPS no escucha.' }
            $ssl = [Net.Security.SslStream]::new($client.GetStream(), $false)
            $ssl.ReadTimeout = 2000; $ssl.WriteTimeout = 2000
            if (-not $ssl.AuthenticateAsClientAsync($uri.DnsSafeHost.Trim('[', ']')).Wait(5000)) {
                throw 'No se pudo completar TLS.'
            }
            $request = [Text.Encoding]::ASCII.GetBytes("GET /actuator/health HTTP/1.1`r`nHost: $($uri.Authority)`r`nConnection: close`r`n`r`n")
            $ssl.Write($request, 0, $request.Length)
            $reader = [IO.StreamReader]::new($ssl)
            try { if ($reader.ReadLine() -match '^HTTP/1\.[01] 200(?: |$)') { return } }
            finally { $reader.Dispose() }
        } catch { }
        finally { if ($null -ne $ssl) { $ssl.Dispose() }; $client.Dispose() }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'HTTPS no responde con un certificado de confianza para la autoridad configurada; se conserva la configuracion.'
}

function Invoke-TpvServerInstallation([hashtable] $Options, [bool] $Apply) {
    foreach ($key in @('ServerRoot', 'BackendInstallRoot', 'HttpsInstallRoot')) {
        $Options[$key] = Assert-TpvServerPath $Options[$key]
    }
    $Options.PublicHost = ConvertTo-TpvPublicHost $Options.PublicHost
    $Options.BackendAddress = ConvertTo-TpvBackendAddress $Options.BackendAddress
    if ($Options.BackendAddress -notin @('127.0.0.1', '::1')) {
        throw 'El escritorio local requiere el backend en 127.0.0.1 o ::1; no se admite otra direccion loopback.'
    }
    foreach ($pair in @(@('ServerRoot', 'BackendInstallRoot'), @('ServerRoot', 'HttpsInstallRoot'), @('BackendInstallRoot', 'HttpsInstallRoot'))) {
        $first = $Options[$pair[0]].TrimEnd('\') + '\'
        $second = $Options[$pair[1]].TrimEnd('\') + '\'
        if ($first.StartsWith($second, [StringComparison]::OrdinalIgnoreCase) -or
            $second.StartsWith($first, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Los directorios de configuracion, backend y HTTPS deben estar separados.'
        }
    }
    $backend = Get-TpvOwnedService 'TPVERPBackend' $Options.BackendInstallRoot
    $https = Get-TpvOwnedService 'TPVERPHTTPS' $Options.HttpsInstallRoot
    $plan = Resolve-TpvInstalledPortPlan $Options $backend $https
    Assert-TpvServerDesktopOrigin $plan.BackendUrl
    $backendArgs = @{
        BundleDirectory = $Options.BundleDirectory; ExpectedReleaseId = $Options.ExpectedReleaseId
        WinSwExecutable = $Options.WinSwExecutable; WinSwSha256 = $Options.WinSwSha256
        JavaExecutable = $Options.JavaExecutable; ConfigurationFile = $Options.ConfigurationFile
        InstallRoot = $Options.BackendInstallRoot; ListenAddress = $plan.BackendAddress
        Port = $plan.BackendPort; PublicUrl = $plan.PublicUrl; Phase = $Options.Phase
        SecretDirectory = (Join-Path $Options.SecretRoot 'verifactu')
        ExportDirectory = (Join-Path $Options.ExportRoot 'fiscal'); Confirm = $false; Preflight = $true
    }
    $httpsArgs = @{
        CaddyExecutable = $Options.CaddyExecutable; CaddySha256 = $Options.CaddySha256
        WinSwExecutable = $Options.WinSwExecutable; WinSwSha256 = $Options.WinSwSha256
        CertificateFile = $Options.CertificateFile; PrivateKeyFile = $Options.PrivateKeyFile
        PublicUrl = $plan.PublicUrl; BackendUrl = $plan.BackendUrl; InstallRoot = $Options.HttpsInstallRoot
        Phase = $Options.Phase; Confirm = $false; Preflight = $true
    }
    Invoke-TpvServerStep 'Install-TpvBackendWindowsService.ps1' $backendArgs.Clone()
    Invoke-TpvServerStep 'Install-TpvHttpsWindowsService.ps1' $httpsArgs.Clone()
    $plannedRule = $Options.Phase -eq 'Register'
    Set-TpvServerFirewall 'TPVERPHTTPS-LAN' (Join-Path $Options.HttpsInstallRoot 'caddy.exe') 'TCP' $plan.HttpsPort $false -Create:$plannedRule -ValidateOnly
    Set-TpvServerFirewall 'TPVERPDiscovery-LAN' ([IO.Path]::GetFullPath($Options.JavaExecutable)) 'UDP' 5353 $false -Create:$plannedRule -ValidateOnly
    Write-Host "Puertos $(if (Test-Path -LiteralPath (Join-Path $Options.ServerRoot 'server-network.json')) { 'guardados' } else { 'provisionales' }): backend $($plan.BackendPort), HTTPS $($plan.HttpsPort)."
    if (-not $Apply) { return $plan }
    $backendArgs.Preflight = $false; $httpsArgs.Preflight = $false
    if ($Options.Phase -eq 'Register') {
        Initialize-TpvServerRoot $Options.ServerRoot
        Write-TpvServerPortPlan -Path (Join-Path $Options.ServerRoot 'server-network.json') -Plan $plan | Out-Null
    }
    Write-TpvServerDeploymentState $Options 'Beginning'
    try {
    if ($Options.Phase -eq 'Register') {
        Stop-TpvRegisteredService 'TPVERPHTTPS' $https
        Stop-TpvRegisteredService 'TPVERPBackend' $backend
        Assert-TpvSavedPortsAvailable $plan
        Invoke-TpvServerStep 'Install-TpvBackendWindowsService.ps1' $backendArgs
        Write-TpvServerDeploymentState $Options 'BackendRegistered'
        Invoke-TpvServerStep 'Install-TpvHttpsWindowsService.ps1' $httpsArgs
        Write-TpvServerDeploymentState $Options 'HttpsRegistered'
        Invoke-TpvServerStep 'Set-TpvBackendWindowsAcl.ps1' @{
            Phase = 'Apply'; InstallRoot = $Options.BackendInstallRoot; ConfigurationFile = $Options.ConfigurationFile
            SecretRoot = $Options.SecretRoot; ExportsRoot = $Options.ExportRoot; Confirm = $false
        }
        Set-TpvServerFirewall 'TPVERPHTTPS-LAN' (Join-Path $Options.HttpsInstallRoot 'caddy.exe') 'TCP' $plan.HttpsPort $false -Create
        Set-TpvServerFirewall 'TPVERPDiscovery-LAN' ([IO.Path]::GetFullPath($Options.JavaExecutable)) 'UDP' 5353 $false -Create
        Write-TpvServerDesktopOrigin $plan.BackendUrl
        Write-TpvServerDeploymentState $Options 'Registered'
        Write-Host 'Servidor registrado y detenido. Los puertos quedan guardados; complete Start -Preflight antes de arrancar.'
    } else {
        $started = @()
        $startupBefore = @{}
        foreach ($entry in @(@('TPVERPBackend', $backend), @('TPVERPHTTPS', $https))) {
            $startupBefore[$entry[0]] = switch ([string]$entry[1].StartMode) {
                'Auto' { 'Automatic' }
                'Manual' { 'Manual' }
                'Disabled' { 'Disabled' }
                default { throw "Tipo de inicio no reconocido para $($entry[0]); no se cambia." }
            }
        }
        $firewallBefore = @{}
        foreach ($name in @('TPVERPHTTPS-LAN', 'TPVERPDiscovery-LAN')) {
            $firewallBefore[$name] = [string](Get-NetFirewallRule -Name $name).Enabled
        }
        try {
            if ($backend.State -ne 'Running') {
                if (-not (Test-TpvTcpPortAvailable -Address $plan.BackendAddress -Port $plan.BackendPort)) {
                    throw "El puerto interno guardado $($plan.BackendPort) esta ocupado; no se reasigna."
                }
                $started += 'TPVERPBackend'
                Invoke-TpvServerStep 'Install-TpvBackendWindowsService.ps1' $backendArgs
            }
            Wait-TpvBackendReady $plan.BackendUrl
            Write-TpvServerDeploymentState $Options 'BackendReady'
            if ($https.State -ne 'Running') {
                if (-not (Test-TpvTcpPortAvailable -Address '0.0.0.0' -Port $plan.HttpsPort)) {
                    throw "El puerto HTTPS guardado $($plan.HttpsPort) esta ocupado; no se reasigna."
                }
                $started += 'TPVERPHTTPS'
                Invoke-TpvServerStep 'Install-TpvHttpsWindowsService.ps1' $httpsArgs
            }
            Wait-TpvHttpsReady $plan.PublicUrl
            Write-TpvServerDeploymentState $Options 'HttpsReady'
            Set-TpvServerFirewall 'TPVERPHTTPS-LAN' (Join-Path $Options.HttpsInstallRoot 'caddy.exe') 'TCP' $plan.HttpsPort $true
            Set-TpvServerFirewall 'TPVERPDiscovery-LAN' ([IO.Path]::GetFullPath($Options.JavaExecutable)) 'UDP' 5353 $true
            foreach ($name in @('TPVERPBackend', 'TPVERPHTTPS')) {
                Set-Service -Name $name -StartupType Automatic -ErrorAction Stop
            }
            Write-TpvServerDeploymentState $Options 'Active'
        } catch {
            for ($index = $started.Count - 1; $index -ge 0; $index--) {
                $name = $started[$index]
                try { Stop-Service -Name $name -ErrorAction Stop } catch { }
            }
            foreach ($name in $startupBefore.Keys) {
                try { Set-Service -Name $name -StartupType $startupBefore[$name] -ErrorAction Stop } catch { }
            }
            foreach ($name in $firewallBefore.Keys) {
                try { Set-NetFirewallRule -Name $name -Enabled $firewallBefore[$name] | Out-Null } catch { }
            }
            throw
        }
        Write-Host "Servidor disponible. Terminales: $($plan.PublicUrl). Backend interno: $($plan.BackendUrl)."
    }
    } catch {
        $failure = $_
        try { Write-TpvServerDeploymentState $Options 'Failed' } catch { Write-Warning 'No se pudo guardar el estado de instalacion incompleta.' }
        Write-Warning 'Instalacion incompleta. Los puertos se conservan; corrija la causa y repita la fase indicada con la misma configuracion.'
        throw $failure
    }
    return $plan
}

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT -or
    -not ([Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Ejecute el instalador en Windows como administrador.' }
$options = @{}
foreach ($name in $MyInvocation.MyCommand.Parameters.Keys) {
    if ($name -notin @('Verbose', 'Debug', 'ErrorAction', 'WarningAction', 'InformationAction', 'ProgressAction',
        'ErrorVariable', 'WarningVariable', 'InformationVariable', 'OutVariable', 'OutBuffer', 'PipelineVariable', 'WhatIf', 'Confirm')) {
        $options[$name] = Get-Variable -Name $name -ValueOnly
    }
}
$mutex = Enter-TpvServerInstallationLock
try {
    $apply = -not $Preflight -and $PSCmdlet.ShouldProcess($ServerRoot, "Instalar servidor: fase $Phase, backend y HTTPS")
    Invoke-TpvServerInstallation $options $apply
} finally {
    Exit-TpvServerInstallationLock -Mutex $mutex
}
