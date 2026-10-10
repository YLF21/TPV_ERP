Import-Module (Join-Path $PSScriptRoot 'TpvServerPorts.psm1') -Force
$installerPath = Join-Path $PSScriptRoot 'Install-TpvServerWindows.ps1'
$taskTokens = $null; $taskErrors = $null
$installerAst = [Management.Automation.Language.Parser]::ParseFile($installerPath, [ref]$taskTokens, [ref]$taskErrors)
foreach ($definition in $installerAst.FindAll({ param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst]
}, $true)) { . ([scriptblock]::Create($definition.Extent.Text)) }

# Pipeline-compatible stubs prevent CIM parameter binding from reaching Windows
# while unit tests use ordinary objects for firewall policies.
function Get-NetFirewallPortFilter {
    param([Parameter(ValueFromPipeline)] $AssociatedNetFirewallRule)
    process { throw 'Firewall port filter must be mocked.' }
}
function Get-NetFirewallApplicationFilter {
    param([Parameter(ValueFromPipeline)] $AssociatedNetFirewallRule)
    process { throw 'Firewall application filter must be mocked.' }
}
function Get-NetFirewallAddressFilter {
    param([Parameter(ValueFromPipeline)] $AssociatedNetFirewallRule)
    process { throw 'Firewall address filter must be mocked.' }
}

Describe 'TPV server installer orchestration' {
    BeforeEach {
        $script:events = @()
        $script:options = @{
            ServerRoot = (Join-Path $TestDrive 'Server'); BackendInstallRoot = (Join-Path $TestDrive 'Backend')
            HttpsInstallRoot = (Join-Path $TestDrive 'HTTPS'); BackendAddress = '127.0.0.1'
            PublicHost = 'Server.Example.COM.'; PreferredBackendPort = 8080; PreferredHttpsPort = 8443
            BundleDirectory = $TestDrive; ExpectedReleaseId = 'test-release'; WinSwExecutable = 'C:\fixture\winsw.exe'
            WinSwSha256 = ('a' * 64); JavaExecutable = 'C:\fixture\java.exe'; ConfigurationFile = 'C:\fixture\prod.yml'
            CaddyExecutable = 'C:\fixture\caddy.exe'; CaddySha256 = ('b' * 64)
            CertificateFile = 'C:\fixture\cert.pem'; PrivateKeyFile = 'C:\fixture\key.pem'
            SecretRoot = (Join-Path $TestDrive 'secrets'); ExportRoot = (Join-Path $TestDrive 'exports'); Phase = 'Register'
        }
        $script:plan = New-TpvServerPortPlan -PublicHost 'server.example.com' -ExistingBackendPort 18080 -ExistingHttpsPort 18443
        Mock Get-TpvOwnedService { return [pscustomobject]@{State='Stopped';StartMode='Manual'} }
        Mock Resolve-TpvInstalledPortPlan { return $script:plan }
        Mock Assert-TpvServerDesktopOrigin { }
        Mock Invoke-TpvServerStep {
            param($File, $Arguments)
            $script:events += "$File/$($Arguments.Phase)/$($Arguments.Preflight)"
        }
        Mock Initialize-TpvServerRoot { $script:events += 'InitializeRoot' }
        Mock Write-TpvServerPortPlan { $script:events += 'SavePlan' }
        Mock Write-TpvServerDeploymentState { param($Options, $Stage) $script:events += "State/$Stage" }
        Mock Stop-TpvRegisteredService { param($Name) $script:events += "Stop/$Name" }
        Mock Assert-TpvSavedPortsAvailable { $script:events += 'RecheckPorts' }
        Mock Set-TpvServerFirewall { param($Name, $Program, $Protocol, $Port, $Enabled, $Create, $ValidateOnly)
            $script:events += "Firewall/$Name/$Enabled/$([bool]$ValidateOnly)"
        }
        Mock Write-TpvServerDesktopOrigin { $script:events += 'DesktopConfig' }
        Mock Test-TpvTcpPortAvailable { return $true }
        Mock Wait-TpvBackendReady { $script:events += 'BackendReady' }
        Mock Wait-TpvHttpsReady { $script:events += 'HttpsReady' }
        Mock Get-NetFirewallRule { return [pscustomobject]@{Enabled='False'} }
        Mock Set-NetFirewallRule { param($Name, $Enabled) $script:events += "RestoreFirewall/$Name/$Enabled" }
        Mock Stop-Service { param($Name) $script:events += "RollbackStop/$Name" }
        Mock Set-Service { }
    }

    It 'parses and plans without persistence, service stops, desktop writes or firewall changes' {
        @($taskErrors).Count | Should Be 0
        $result = Invoke-TpvServerInstallation $script:options $false
        $result.HttpsPort | Should Be 18443
        $script:options.PublicHost | Should Be 'server.example.com'
        Assert-MockCalled Write-TpvServerPortPlan -Times 0 -Scope It
        Assert-MockCalled Initialize-TpvServerRoot -Times 0 -Scope It
        Assert-MockCalled Stop-TpvRegisteredService -Times 0 -Scope It
        Assert-MockCalled Write-TpvServerDesktopOrigin -Times 0 -Scope It
        Assert-MockCalled Set-TpvServerFirewall -Times 2 -Exactly -Scope It -ParameterFilter { $ValidateOnly }
        Assert-MockCalled Invoke-TpvServerStep -Times 2 -Exactly -Scope It -ParameterFilter { $Arguments.Preflight }
    }

    It 'persists both fallback ports before registration and provisions every consumer consistently' {
        Invoke-TpvServerInstallation $script:options $true | Out-Null
        $events = $script:events -join '|'
        $events.IndexOf('SavePlan') -lt $events.IndexOf('Stop/TPVERPBackend') | Should Be $true
        $events.IndexOf('RecheckPorts') -lt $events.IndexOf('Install-TpvBackendWindowsService.ps1/Register/False') | Should Be $true
        Assert-MockCalled Invoke-TpvServerStep -Times 1 -Exactly -Scope It -ParameterFilter {
            $File -eq 'Install-TpvBackendWindowsService.ps1' -and -not $Arguments.Preflight -and
            $Arguments.Port -eq 18080 -and $Arguments.PublicUrl -eq 'https://server.example.com:18443'
        }
        Assert-MockCalled Invoke-TpvServerStep -Times 1 -Exactly -Scope It -ParameterFilter {
            $File -eq 'Install-TpvHttpsWindowsService.ps1' -and -not $Arguments.Preflight -and
            $Arguments.BackendUrl -eq 'http://127.0.0.1:18080' -and $Arguments.PublicUrl -eq 'https://server.example.com:18443'
        }
        Assert-MockCalled Write-TpvServerDesktopOrigin -Times 1 -Exactly -Scope It -ParameterFilter {
            $BackendUrl -eq 'http://127.0.0.1:18080'
        }
        Assert-MockCalled Write-TpvServerDeploymentState -Times 1 -Scope It -ParameterFilter { $Stage -eq 'Registered' }
    }

    It 'stops before modifying services when saved ports become occupied and records the interruption' {
        Mock Assert-TpvSavedPortsAvailable { throw 'occupied saved port' }
        { Invoke-TpvServerInstallation $script:options $true } | Should Throw
        Assert-MockCalled Invoke-TpvServerStep -Times 0 -Scope It -ParameterFilter { -not $Arguments.Preflight }
        Assert-MockCalled Write-TpvServerDeploymentState -Times 1 -Scope It -ParameterFilter { $Stage -eq 'Failed' }
        Assert-MockCalled Write-TpvServerDesktopOrigin -Times 0 -Scope It
    }

    It 'rejects a different existing desktop destination before persistence or service mutations' {
        Mock Assert-TpvServerDesktopOrigin { throw 'different installation' }
        { Invoke-TpvServerInstallation $script:options $true } | Should Throw
        Assert-MockCalled Write-TpvServerPortPlan -Times 0 -Scope It
        Assert-MockCalled Invoke-TpvServerStep -Times 0 -Scope It
    }

    It 'rejects loopback aliases unsupported by the desktop before touching services' {
        $script:options.BackendAddress = '127.0.0.2'
        { Invoke-TpvServerInstallation $script:options $true } | Should Throw
        Assert-MockCalled Get-TpvOwnedService -Times 0 -Scope It
        Assert-MockCalled Invoke-TpvServerStep -Times 0 -Scope It
    }

    It 'records partial registration failure without changing the saved ports or starting services' {
        Mock Invoke-TpvServerStep {
            param($File, $Arguments)
            if ($File -eq 'Install-TpvHttpsWindowsService.ps1' -and -not $Arguments.Preflight) { throw 'gateway rejected' }
        }
        { Invoke-TpvServerInstallation $script:options $true } | Should Throw
        Assert-MockCalled Write-TpvServerPortPlan -Times 1 -Exactly -Scope It
        Assert-MockCalled Write-TpvServerDeploymentState -Times 1 -Scope It -ParameterFilter { $Stage -eq 'BackendRegistered' }
        Assert-MockCalled Write-TpvServerDeploymentState -Times 1 -Scope It -ParameterFilter { $Stage -eq 'Failed' }
        Assert-MockCalled Invoke-TpvServerStep -Times 0 -Scope It -ParameterFilter { $Arguments.Phase -eq 'Start' }
    }

    It 'starts on stored ports, verifies backend and TLS, then enables only planned LAN rules' {
        $script:options.Phase = 'Start'
        Invoke-TpvServerInstallation $script:options $true | Out-Null
        $events = $script:events -join '|'
        $events.IndexOf('BackendReady') -lt $events.IndexOf('Install-TpvHttpsWindowsService.ps1/Start/False') | Should Be $true
        $events.IndexOf('HttpsReady') -lt $events.IndexOf('Firewall/TPVERPHTTPS-LAN/True/False') | Should Be $true
        Assert-MockCalled Write-TpvServerPortPlan -Times 0 -Scope It
        Assert-MockCalled Initialize-TpvServerRoot -Times 0 -Scope It
        Assert-MockCalled Write-TpvServerDeploymentState -Times 1 -Scope It -ParameterFilter { $Stage -eq 'Active' }
    }

    It 'reverts new service starts and firewall state when TLS readiness fails' {
        $script:options.Phase = 'Start'
        Mock Wait-TpvHttpsReady { throw 'untrusted certificate' }
        { Invoke-TpvServerInstallation $script:options $true } | Should Throw
        Assert-MockCalled Stop-Service -Times 1 -Exactly -Scope It -ParameterFilter { $Name -eq 'TPVERPBackend' }
        Assert-MockCalled Stop-Service -Times 1 -Exactly -Scope It -ParameterFilter { $Name -eq 'TPVERPHTTPS' }
        Assert-MockCalled Set-NetFirewallRule -Times 2 -Exactly -Scope It -ParameterFilter { $Enabled -eq 'False' }
        Assert-MockCalled Write-TpvServerPortPlan -Times 0 -Scope It
        Assert-MockCalled Write-TpvServerDeploymentState -Times 1 -Scope It -ParameterFilter { $Stage -eq 'Failed' }
    }

    It 'enables automatic startup without probing or stopping services already running in manual mode' {
        $script:options.Phase = 'Start'
        Mock Get-TpvOwnedService { return [pscustomobject]@{State='Running';StartMode='Manual'} }
        Invoke-TpvServerInstallation $script:options $true | Out-Null
        Assert-MockCalled Test-TpvTcpPortAvailable -Times 0 -Scope It
        Assert-MockCalled Invoke-TpvServerStep -Times 0 -Scope It -ParameterFilter { -not $Arguments.Preflight }
        Assert-MockCalled Stop-Service -Times 0 -Scope It
        Assert-MockCalled Set-Service -Times 2 -Exactly -Scope It -ParameterFilter { $StartupType -eq 'Automatic' }
    }

    It 'restores startup modes of already running services if activation fails' {
        $script:options.Phase = 'Start'
        Mock Get-TpvOwnedService { return [pscustomobject]@{State='Running';StartMode='Manual'} }
        Mock Set-Service { param($Name, $StartupType)
            if ($Name -eq 'TPVERPHTTPS' -and $StartupType -eq 'Automatic') { throw 'startup update denied' }
        }
        { Invoke-TpvServerInstallation $script:options $true } | Should Throw
        Assert-MockCalled Set-Service -Times 2 -Exactly -Scope It -ParameterFilter { $StartupType -eq 'Manual' }
        Assert-MockCalled Stop-Service -Times 0 -Scope It
        Assert-MockCalled Set-NetFirewallRule -Times 2 -Exactly -Scope It -ParameterFilter { $Enabled -eq 'False' }
    }

    It 'rolls back activation when saving the Active deployment state fails' {
        $script:options.Phase = 'Start'
        Mock Write-TpvServerDeploymentState { param($Options, $Stage)
            if ($Stage -eq 'Active') { throw 'disk full' }
        }
        { Invoke-TpvServerInstallation $script:options $true } | Should Throw
        Assert-MockCalled Stop-Service -Times 2 -Exactly -Scope It
        Assert-MockCalled Set-Service -Times 2 -Exactly -Scope It -ParameterFilter { $StartupType -eq 'Manual' }
        Assert-MockCalled Set-NetFirewallRule -Times 2 -Exactly -Scope It -ParameterFilter { $Enabled -eq 'False' }
        Assert-MockCalled Write-TpvServerDeploymentState -Times 1 -Scope It -ParameterFilter { $Stage -eq 'Failed' }
    }
}

Describe 'TPV installed port adoption' {
    BeforeEach {
        $script:identityOptions = @{
            ServerRoot = (Join-Path $TestDrive 'NotCreated'); BackendInstallRoot = (Join-Path $TestDrive 'Backend')
            HttpsInstallRoot = (Join-Path $TestDrive 'HTTPS'); BackendAddress = '127.0.0.1'
            PublicHost = 'server.example.com'; PreferredBackendPort = 8080; PreferredHttpsPort = 8443; Phase = 'Register'
        }
    }
    It 'adopts fixed ports from owned legacy services without treating active listeners as conflicts' {
        Mock Read-TpvInstalledServiceXml {
            param($Path)
            if ($Path.EndsWith('TPVERPBackend.xml')) {
                return [xml]'<service><id>TPVERPBackend</id><arguments>--server.address=127.0.0.1 --server.port=9090</arguments></service>'
            }
            return [xml]'<service><env name="TPV_HTTPS_PUBLIC_URL" value="https://server.example.com:9443" /></service>'
        }
        $plan = Resolve-TpvInstalledPortPlan $script:identityOptions ([pscustomobject]@{}) ([pscustomobject]@{})
        $plan.BackendPort | Should Be 9090
        $plan.HttpsPort | Should Be 9443
    }
    It 'never selects new ports when Start has no persisted plan' {
        $script:identityOptions.Phase = 'Start'
        { Resolve-TpvInstalledPortPlan $script:identityOptions $null $null } | Should Throw
    }

    It 'reuses the saved plan and rejects corrupt configuration without selecting new ports' {
        $script:identityOptions.ServerRoot = $TestDrive
        $saved = New-TpvServerPortPlan -PublicHost 'server.example.com' -ExistingBackendPort 18080 -ExistingHttpsPort 18443
        $path = Join-Path $TestDrive 'server-network.json'
        [IO.File]::WriteAllText($path, ($saved | ConvertTo-Json))
        (Resolve-TpvInstalledPortPlan $script:identityOptions $null $null).HttpsPort | Should Be 18443
        [IO.File]::WriteAllText($path, '{"SchemaVersion":2}')
        { Resolve-TpvInstalledPortPlan $script:identityOptions $null $null } | Should Throw
    }

    It 'adopts equivalent IPv6 legacy authorities in Windows PowerShell' {
        $script:identityOptions.BackendAddress = '::1'
        $script:identityOptions.PublicHost = '::1'
        Mock Read-TpvInstalledServiceXml {
            param($Path)
            if ($Path.EndsWith('TPVERPBackend.xml')) {
                return [xml]'<service><id>TPVERPBackend</id><arguments>--server.address=0:0:0:0:0:0:0:1 --server.port=9090</arguments></service>'
            }
            return [xml]'<service><env name="TPV_HTTPS_PUBLIC_URL" value="https://[::1]:9443" /></service>'
        }
        $plan = Resolve-TpvInstalledPortPlan $script:identityOptions ([pscustomobject]@{}) ([pscustomobject]@{})
        $plan.BackendPort | Should Be 9090
        $plan.HttpsPort | Should Be 9443
        $plan.PublicHost | Should Be '::1'
    }
}

Describe 'TPV server managed firewall rules' {
    $firewallDefinition = $installerAst.FindAll({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst]
    }, $true) | Where-Object { $_.Name -eq 'Set-TpvServerFirewall' }
    . ([scriptblock]::Create($firewallDefinition.Extent.Text))
    BeforeEach {
        $script:rule = [pscustomobject]@{Description='TPV ERP server installer managed';
            Direction='Inbound';Action='Allow';Profile='Private, Domain'}
        Mock Get-NetFirewallRule { return $script:rule }
        Mock Get-NetFirewallPortFilter { return [pscustomobject]@{Protocol='TCP';LocalPort='18443'} }
        Mock Get-NetFirewallApplicationFilter { return [pscustomobject]@{Program='C:\fixture\caddy.exe'} }
        Mock Get-NetFirewallAddressFilter { return [pscustomobject]@{RemoteAddress='LocalSubnet'} }
        Mock New-NetFirewallRule { }
        Mock Set-NetFirewallRule { }
    }

    It 'creates only a disabled private and domain local-subnet rule during Register' {
        Mock Get-NetFirewallRule { return $null }
        Set-TpvServerFirewall 'TPVERPHTTPS-LAN' 'C:\fixture\caddy.exe' 'TCP' 18443 $false -Create
        Assert-MockCalled New-NetFirewallRule -Times 1 -Exactly -Scope It -ParameterFilter {
            $Enabled -eq 'False' -and $LocalPort -eq 18443 -and $Protocol -eq 'TCP' -and
            $RemoteAddress -eq 'LocalSubnet' -and (([string]$Profile -replace '\s', '') -eq 'Domain,Private') -and
            $Program -eq 'C:\fixture\caddy.exe'
        }
    }

    It 'validates without mutation and enables an owned matching rule only when requested' {
        Set-TpvServerFirewall 'TPVERPHTTPS-LAN' 'C:\fixture\caddy.exe' 'TCP' 18443 $false -ValidateOnly
        Assert-MockCalled Set-NetFirewallRule -Times 0 -Scope It
        Assert-MockCalled New-NetFirewallRule -Times 0 -Scope It
        Set-TpvServerFirewall 'TPVERPHTTPS-LAN' 'C:\fixture\caddy.exe' 'TCP' 18443 $true
        Assert-MockCalled Set-NetFirewallRule -Times 1 -Exactly -Scope It -ParameterFilter { $Enabled -eq 'True' }
    }

    It 'rejects foreign programs, public network profiles and different ports without replacing rules' {
        Mock Get-NetFirewallApplicationFilter { return [pscustomobject]@{Program='C:\other\app.exe'} }
        { Set-TpvServerFirewall 'TPVERPHTTPS-LAN' 'C:\fixture\caddy.exe' 'TCP' 18443 $true } | Should Throw
        Mock Get-NetFirewallApplicationFilter { return [pscustomobject]@{Program='C:\fixture\caddy.exe'} }
        $script:rule.Profile = 'Domain, Private, Public'
        { Set-TpvServerFirewall 'TPVERPHTTPS-LAN' 'C:\fixture\caddy.exe' 'TCP' 18443 $true } | Should Throw
        $script:rule.Profile = 'Domain, Private'
        { Set-TpvServerFirewall 'TPVERPHTTPS-LAN' 'C:\fixture\caddy.exe' 'TCP' 8443 $true } | Should Throw
        Assert-MockCalled Set-NetFirewallRule -Times 0 -Scope It
        Assert-MockCalled New-NetFirewallRule -Times 0 -Scope It
    }
}

Describe 'TPV backend public origin override' {
    $backendPath = Join-Path $PSScriptRoot 'Install-TpvBackendWindowsService.ps1'
    $tokens = $null; $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($backendPath, [ref]$tokens, [ref]$errors)
    $function = $ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] }, $true) |
        Where-Object { $_.Name -eq 'Assert-BackendPublicUrl' }
    . ([scriptblock]::Create($function.Extent.Text))
    It 'rejects malformed public origins before placing them in service arguments' {
        Assert-BackendPublicUrl 'https://server.example.com:18443'
        Assert-BackendPublicUrl 'https://server.example.com'
        foreach ($origin in @('http://server.example.com:18443', 'https://admin@server.example.com', 'https://server.example.com/path', 'https://server.example.com?secret=value')) {
            { Assert-BackendPublicUrl $origin } | Should Throw
        }
    }
    It 'forces the chosen discovery origin over an older external Spring property' {
        $text = Get-Content -LiteralPath $backendPath -Raw
        $text | Should Match '--tpv\.terminal-discovery\.public-url=\$PublicUrl'
        $text | Should Match 'server.port=\$Port\$publicUrlArgument'
        $text | Should Match 'TPV_BACKEND_PUBLIC_URL'
    }
    It 'shares the server installation lock and releases it for every backend execution path' {
        @($errors).Count | Should Be 0
        $text = Get-Content -LiteralPath $backendPath -Raw
        $text | Should Match '\$tpvInstallationMutex = Enter-TpvServerInstallationLock'
        $text | Should Match '(?s)finally \{\s*Exit-TpvServerInstallationLock -Mutex \$tpvInstallationMutex'
        $root = Get-Content -LiteralPath $installerPath -Raw
        $root | Should Match '\$mutex = Enter-TpvServerInstallationLock'
        $root | Should Match '(?s)finally \{\s*Exit-TpvServerInstallationLock -Mutex \$mutex'
    }
}
