Describe 'Installed disaster recovery maintenance entry point' {
    It 'parses both scripts and allows only recovery actions' {
        foreach ($name in @('Invoke-TpvDisasterRecovery.ps1', 'Start-TpvDisasterRecoveryMaintenance.ps1')) {
            $tokens = $null
            $errors = $null
            $path = Join-Path $PSScriptRoot $name
            [Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors) | Out-Null
            @($errors).Count | Should Be 0
        }
        $content = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Start-TpvDisasterRecoveryMaintenance.ps1') -Raw
        $content | Should Match "ValidateSet\('request', 'receive', 'activate', 'status'\)"
        $content | Should Not Match "ValidateSet\([^)]*'restore'"
    }
}

Describe 'Recovery readiness HTTP JSON decoding on Windows PowerShell' {
    $tokens = $null
    $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile(
        (Join-Path $PSScriptRoot 'Start-TpvDisasterRecoveryMaintenance.ps1'), [ref]$tokens, [ref]$errors)
    $decoder = $ast.Find({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -ceq 'ConvertFrom-TpvHttpJson'
    }, $true)
    Invoke-Expression $decoder.Extent.Text

    It 'recognizes UP from vendor actuator JSON returned as a byte array' {
        $response = [pscustomobject]@{
            StatusCode = 200
            Headers = @{ 'Content-Type' = 'application/vnd.spring-boot.actuator.v3+json' }
            Content = [Text.Encoding]::UTF8.GetBytes('{"status":"UP"}')
        }
        $health = ConvertFrom-TpvHttpJson -Content $response.Content
        ($response.StatusCode -eq 200 -and $health.status -ceq 'UP') | Should Be $true
    }

    It 'continues to recognize ordinary string JSON' {
        (ConvertFrom-TpvHttpJson -Content '{"status":"UP"}').status | Should Be 'UP'
    }

    It 'preserves DOWN instead of treating successful decoding as readiness' {
        (ConvertFrom-TpvHttpJson -Content ([Text.Encoding]::UTF8.GetBytes('{"status":"DOWN"}'))).status | Should Be 'DOWN'
    }

    It 'rejects malformed JSON text and bytes' {
        { ConvertFrom-TpvHttpJson -Content '{broken' } | Should Throw
        { ConvertFrom-TpvHttpJson -Content ([Text.Encoding]::UTF8.GetBytes('{broken')) } | Should Throw
    }

    It 'rejects malformed UTF-8 rather than silently replacing bytes' {
        { ConvertFrom-TpvHttpJson -Content ([byte[]](123,34,255,34,58,49,125)) } | Should Throw
    }

    It 'rejects other response content types' {
        { ConvertFrom-TpvHttpJson -Content ([object[]](123,34,115)) } | Should Throw
        { ConvertFrom-TpvHttpJson -Content $null } | Should Throw
    }
}

Describe 'Disaster recovery HTTPS activation and availability' {
    $tokens = $null
    $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile(
        (Join-Path $PSScriptRoot 'Start-TpvDisasterRecoveryMaintenance.ps1'), [ref]$tokens, [ref]$errors)
    foreach ($name in @('ConvertFrom-TpvHttpJson', 'Assert-PlainFile', 'Assert-PlainDirectory',
            'Read-InstalledHttpsService', 'Test-TpvSameHost', 'Test-TpvSameOrigin',
            'Test-TpvLocalReadiness', 'Test-TpvHttpsHealthResponse', 'Test-TpvHttpsReadiness',
            'Read-TpvRecoveryFirewallPortFilter', 'Read-TpvRecoveryFirewallApplicationFilter',
            'Read-TpvRecoveryFirewallAddressFilter', 'Get-TpvRecoveryManagedRule',
            'Resolve-TpvRecoveryPublicAddresses', 'Assert-TpvRecoveryNetworkProfiles',
            'Get-TpvRecoveryExposure', 'Test-TpvRecoveryExposureReady',
            'Enable-TpvRecoveryExposure', 'Test-TpvRecoveryServicesReady', 'Start-TpvRecoveryServices',
            'Test-TpvAlreadyActivated', 'Assert-TpvActivationApproved')) {
        $definition = $ast.Find({ param($node)
            $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name
        }, $true)
        Invoke-Expression $definition.Extent.Text
    }
    $backend = @{ LocalUrl = 'http://127.0.0.1:58442'; Java = 'C:\Java\bin\java.exe' }
    $https = @{ PublicUrl = 'https://tpv.example.test:58443'; Program = 'C:\ProgramData\TPV ERP\HTTPS\caddy.exe' }

    It 'uses only the installed HTTPS origin and rejects unexpected XML settings' {
        $root = Join-Path $TestDrive 'TPV ERP\HTTPS'
        New-Item -ItemType Directory -Path $root -Force | Out-Null
        $xmlPath = Join-Path $root 'TPVERPHTTPS.xml'
        $xml = '<service><id>TPVERPHTTPS</id><workingdirectory>' + $root +
            '</workingdirectory><executable>' + (Join-Path $root 'caddy.exe') + '</executable>' +
            '<serviceaccount><username>NT SERVICE\TPVERPHTTPS</username></serviceaccount>' +
            '<env name="TPV_HTTPS_PUBLIC_URL" value="https://tpv.example.test:58443" />' +
            '<env name="TPV_HTTPS_BACKEND_URL" value="http://127.0.0.1:58442" /></service>'
        [IO.File]::WriteAllText($xmlPath, $xml)
        (Read-InstalledHttpsService $TestDrive).PublicUrl | Should Be 'https://tpv.example.test:58443'
        [IO.File]::WriteAllText($xmlPath, $xml.Replace('https://tpv.example.test:58443',
            'https://tpv.example.test:58443/unexpected'))
        { Read-InstalledHttpsService $TestDrive } | Should Throw
        [IO.File]::WriteAllText($xmlPath, $xml.Replace('https://tpv.example.test:58443',
            'https://[::1]:58443'))
        (Read-InstalledHttpsService $TestDrive).PublicUrl | Should Match '^https://\[0000:'
    }

    It 'matches the official IPv6 loopback origin despite .NET expansion' {
        (Test-TpvSameOrigin 'http://[0000:0000:0000:0000:0000:0000:0000:0001]:58442' 'http://[::1]:58442') | Should Be $true
        (Test-TpvSameHost '::1' '0000:0000:0000:0000:0000:0000:0000:0001') | Should Be $true
        (Test-TpvSameOrigin 'http://[::1]:58443' 'http://[::1]:58442') | Should Be $false
    }

    It 'requires HTTP 200 and UP through HTTPS, including chunked byte-style JSON' {
        $plain = "HTTP/1.1 200 OK`r`nContent-Type: application/vnd.spring-boot.actuator.v3+json`r`nConnection: close`r`n`r`n{""status"":""UP""}"
        (Test-TpvHttpsHealthResponse ([IO.StringReader]::new($plain))) | Should Be $true
        $chunked = "HTTP/1.1 200 OK`r`nTransfer-Encoding: chunked`r`n`r`n9`r`n{""status""`r`n6`r`n:""UP""}`r`n0`r`n`r`n"
        (Test-TpvHttpsHealthResponse ([IO.StringReader]::new($chunked))) | Should Be $true
        (Test-TpvHttpsHealthResponse ([IO.StringReader]::new($plain.Replace('UP', 'DOWN')))) | Should Be $false
        (Test-TpvHttpsHealthResponse ([IO.StringReader]::new($plain.Replace('200 OK', '503 DOWN')))) | Should Be $false
    }

    It 'does not claim availability when HTTPS or its TLS validation fails' {
        Mock Get-Service { [pscustomobject]@{ Status = [ServiceProcess.ServiceControllerStatus]::Running } }
        Mock Test-TpvLocalReadiness { $true }
        Mock Test-TpvHttpsReadiness { $false }
        Mock Test-TpvRecoveryExposureReady { $true }
        (Test-TpvRecoveryServicesReady $backend $https) | Should Be $false
        Mock Test-TpvHttpsReadiness { $true }
        (Test-TpvRecoveryServicesReady $backend $https) | Should Be $true
        Assert-MockCalled Test-TpvHttpsReadiness -Times 2
    }

    It 'starts backend before HTTPS after approved activation and accepts an already running backend' {
        $script:started = @()
        Mock Get-TpvRecoveryExposure { @{} }
        Mock Enable-TpvRecoveryExposure { }
        Mock Get-Service {
            $state = if ($Name -in $script:started) { 'Running' } else { 'Stopped' }
            $service = [pscustomobject]@{ Status = [ServiceProcess.ServiceControllerStatus]::$state }
            $service | Add-Member -MemberType ScriptMethod -Name WaitForStatus -Value { param($status, $timeout) }
            $service
        }
        Mock Start-Service { $script:started += $Name }
        Mock Test-TpvLocalReadiness { $true }
        Mock Test-TpvHttpsReadiness { $true }
        Mock Test-TpvRecoveryExposureReady { $true }
        Start-TpvRecoveryServices $backend $https
        $script:started -join ',' | Should Be 'TPVERPBackend,TPVERPHTTPS'
        Start-TpvRecoveryServices $backend $https
        Assert-MockCalled Start-Service -Times 2
    }

    It 'does not start services when an installed firewall rule is absent or incompatible' {
        Mock Get-TpvRecoveryExposure { throw 'Falta la regla instalada TPVERPHTTPS-LAN' }
        Mock Start-Service { }
        { Start-TpvRecoveryServices $backend $https } | Should Throw
        Assert-MockCalled Start-Service -Times 0 -Scope It
    }

    It 'rejects a missing or redirected firewall rule without changing it' {
        Mock Get-NetFirewallRule { $null }
        Mock Set-NetFirewallRule { }
        { Get-TpvRecoveryManagedRule 'TPVERPHTTPS-LAN' $https.Program 'TCP' 58443 } | Should Throw
        Assert-MockCalled Set-NetFirewallRule -Times 0
        Mock Get-NetFirewallRule { [pscustomobject]@{ Description = 'Other application'; Direction = 'Inbound'; Action = 'Allow'; Profile = 'Domain,Private' } }
        Mock Read-TpvRecoveryFirewallPortFilter { [pscustomobject]@{ Protocol = 'TCP'; LocalPort = '58443' } }
        Mock Read-TpvRecoveryFirewallApplicationFilter { [pscustomobject]@{ Program = 'C:\Other\caddy.exe' } }
        Mock Read-TpvRecoveryFirewallAddressFilter { [pscustomobject]@{ RemoteAddress = 'LocalSubnet' } }
        { Get-TpvRecoveryManagedRule 'TPVERPHTTPS-LAN' $https.Program 'TCP' 58443 } | Should Throw
        Assert-MockCalled Set-NetFirewallRule -Times 0
    }

}

Describe 'Recovery exposure completion and rollback' {
    $tokens = $null
    $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile(
        (Join-Path $PSScriptRoot 'Start-TpvDisasterRecoveryMaintenance.ps1'), [ref]$tokens, [ref]$errors)
    foreach ($name in @('Resolve-TpvRecoveryPublicAddresses', 'Assert-TpvRecoveryNetworkProfiles', 'Get-TpvRecoveryExposure', 'Test-TpvRecoveryExposureReady',
            'Enable-TpvRecoveryExposure', 'Test-TpvAlreadyActivated', 'Assert-TpvActivationApproved')) {
        $definition = $ast.Find({ param($node)
            $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name
        }, $true)
        Invoke-Expression $definition.Extent.Text
    }
    $backend = @{ LocalUrl = 'http://127.0.0.1:58442'; Java = 'C:\Java\bin\java.exe' }
    $https = @{ PublicUrl = 'https://tpv.example.test:58443'; Program = 'C:\ProgramData\TPV ERP\HTTPS\caddy.exe' }

    It 'requires enabled managed rules and Automatic services for COMPLETE readiness' {
        $script:exposure = @{ Rules = @{ 'TPVERPHTTPS-LAN' = [pscustomobject]@{ Enabled = 'False' };
                'TPVERPDiscovery-LAN' = [pscustomobject]@{ Enabled = 'True' } };
            Services = @{ 'TPVERPBackend' = [pscustomobject]@{ StartType = 'Automatic' };
                'TPVERPHTTPS' = [pscustomobject]@{ StartType = 'Manual' } } }
        Mock Get-TpvRecoveryExposure { $script:exposure }
        (Test-TpvRecoveryExposureReady $backend $https) | Should Be $false
        $script:exposure.Rules['TPVERPHTTPS-LAN'].Enabled = 'True'
        (Test-TpvRecoveryExposureReady $backend $https) | Should Be $false
        $script:exposure.Services['TPVERPHTTPS'].StartType = 'Automatic'
        (Test-TpvRecoveryExposureReady $backend $https) | Should Be $true
    }

    It 'enables only installed rules and services after readiness, rolling back its own partial change' {
        $script:exposure = @{ Rules = @{ 'TPVERPHTTPS-LAN' = [pscustomobject]@{ Enabled = 'False' };
                'TPVERPDiscovery-LAN' = [pscustomobject]@{ Enabled = 'False' } };
            Services = @{ 'TPVERPBackend' = [pscustomobject]@{ StartType = 'Manual' };
                'TPVERPHTTPS' = [pscustomobject]@{ StartType = 'Manual' } } }
        Mock Get-TpvRecoveryExposure { $script:exposure }
        $script:ruleChanges = @()
        Mock Set-NetFirewallRule {
            param($Name, $Enabled)
            $script:ruleChanges += "$Name=$Enabled"
            if ($Name -eq 'TPVERPDiscovery-LAN' -and $Enabled -eq 'True') { throw 'denied' }
        }
        Mock Set-Service { }
        { Enable-TpvRecoveryExposure $backend $https } | Should Throw
        $script:ruleChanges -join ',' | Should Be 'TPVERPHTTPS-LAN=True,TPVERPDiscovery-LAN=True,TPVERPHTTPS-LAN=False'
        Assert-MockCalled Set-Service -Times 0 -Scope It
    }

    It 'restores only service and rule settings changed by a failed Automatic update' {
        $script:exposure = @{ Rules = @{ 'TPVERPHTTPS-LAN' = [pscustomobject]@{ Enabled = 'False' };
                'TPVERPDiscovery-LAN' = [pscustomobject]@{ Enabled = 'True' } };
            Services = @{ 'TPVERPBackend' = [pscustomobject]@{ StartType = 'Manual' };
                'TPVERPHTTPS' = [pscustomobject]@{ StartType = 'Manual' } } }
        Mock Get-TpvRecoveryExposure { $script:exposure }
        $script:serviceChanges = @()
        $script:ruleChanges = @()
        Mock Set-Service {
            param($Name, $StartupType)
            $script:serviceChanges += "$Name=$StartupType"
            if ($Name -eq 'TPVERPHTTPS' -and $StartupType -eq 'Automatic') { throw 'denied' }
        }
        Mock Set-NetFirewallRule {
            param($Name, $Enabled)
            $script:ruleChanges += "$Name=$Enabled"
        }
        { Enable-TpvRecoveryExposure $backend $https } | Should Throw
        $script:serviceChanges -join ',' | Should Be 'TPVERPBackend=Automatic,TPVERPHTTPS=Automatic,TPVERPBackend=Manual'
        $script:ruleChanges -join ',' | Should Be 'TPVERPHTTPS-LAN=True,TPVERPHTTPS-LAN=False'
    }

    It 'allows availability retry for ACTIVATED and COMPLETE without a new activation' {
        (Test-TpvAlreadyActivated 'ACTIVATED') | Should Be $true
        (Test-TpvAlreadyActivated 'COMPLETE') | Should Be $true
        (Test-TpvAlreadyActivated 'ACTIVATION_APPROVED') | Should Be $false
        { Assert-TpvActivationApproved 'SUPPORT_REVIEW' } | Should Throw
        { Assert-TpvActivationApproved 'ACTIVATED' } | Should Not Throw
        { Assert-TpvActivationApproved 'COMPLETE' } | Should Not Throw
    }
}

Describe 'Recovery availability requires active Windows network profiles matching installed LAN rules' {
    $tokens = $null
    $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile(
        (Join-Path $PSScriptRoot 'Start-TpvDisasterRecoveryMaintenance.ps1'), [ref]$tokens, [ref]$errors)
    foreach ($name in @('Get-TpvRecoveryManagedRule', 'Test-TpvLocalReadiness', 'Test-TpvHttpsReadiness',
            'Resolve-TpvRecoveryPublicAddresses', 'Assert-TpvRecoveryNetworkProfiles', 'Get-TpvRecoveryExposure',
            'Test-TpvRecoveryExposureReady', 'Enable-TpvRecoveryExposure',
            'Test-TpvRecoveryServicesReady', 'Start-TpvRecoveryServices')) {
        $definition = $ast.Find({ param($node)
            $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name
        }, $true)
        Invoke-Expression $definition.Extent.Text
    }
    $backend = @{ LocalUrl = 'http://127.0.0.1:58442'; Java = 'C:\Java\bin\java.exe' }
    $https = @{ PublicUrl = 'https://tpv.example.test:58443'; Program = 'C:\ProgramData\TPV ERP\HTTPS\caddy.exe' }

    BeforeEach {
        Mock Resolve-TpvRecoveryPublicAddresses { @() }
        Mock Get-NetAdapter { [pscustomobject]@{ ifIndex = 3; Status = 'Up' } }
        Mock Get-NetIPAddress { [pscustomobject]@{ InterfaceIndex = 3; IPAddress = '10.77.23.50'; AddressState = 'Preferred' } }
        Mock Get-NetConnectionProfile { [pscustomobject]@{ InterfaceIndex = 3; NetworkCategory = 'Private' } }
        Mock Get-TpvRecoveryManagedRule { [pscustomobject]@{ Enabled = 'True' } }
        Mock Get-Service { [pscustomobject]@{ Status = [ServiceProcess.ServiceControllerStatus]::Running; StartType = 'Automatic' } }
        Mock Test-TpvLocalReadiness { $true }
        Mock Test-TpvHttpsReadiness { $true }
        Mock Start-Service { }
        Mock Set-Service { }
        Mock Set-NetFirewallRule { }
    }

    It 'accepts an active Private LAN with real service and exposure checks' {
        { Assert-TpvRecoveryNetworkProfiles $https.PublicUrl } | Should Not Throw
        (Test-TpvRecoveryServicesReady $backend $https) | Should Be $true
    }

    It 'accepts the DomainAuthenticated category returned by Windows' {
        Mock Get-NetConnectionProfile { [pscustomobject]@{ InterfaceIndex = 3; NetworkCategory = 'DomainAuthenticated' } }
        (Test-TpvRecoveryServicesReady $backend $https) | Should Be $true
    }

    It 'does not declare COMPLETE after an active LAN changes from Private to Public' {
        (Test-TpvRecoveryServicesReady $backend $https) | Should Be $true
        Mock Get-NetConnectionProfile { [pscustomobject]@{ InterfaceIndex = 3; NetworkCategory = 'Public' } }
        (Test-TpvRecoveryServicesReady $backend $https) | Should Be $false
        { Assert-TpvRecoveryNetworkProfiles $https.PublicUrl } | Should Throw 'Privada'
    }

    It 'rejects Public before starting services or changing exposure' {
        Mock Get-NetConnectionProfile { [pscustomobject]@{ InterfaceIndex = 3; NetworkCategory = 'Public' } }
        { Start-TpvRecoveryServices $backend $https } | Should Throw
        { Enable-TpvRecoveryExposure $backend $https } | Should Throw
        Assert-MockCalled Start-Service -Times 0 -Scope It
        Assert-MockCalled Set-Service -Times 0 -Scope It
        Assert-MockCalled Set-NetFirewallRule -Times 0 -Scope It
    }

    It 'fails closed without an active adapter even if an old Private profile remains' {
        Mock Get-NetAdapter { [pscustomobject]@{ ifIndex = 3; Status = 'Disconnected' } }
        (Test-TpvRecoveryServicesReady $backend $https) | Should Be $false
        { Assert-TpvRecoveryNetworkProfiles $https.PublicUrl } | Should Throw 'interfaz'
    }

    It 'does not count loopback or tentative addresses as an active LAN' {
        Mock Get-NetIPAddress {
            [pscustomobject]@{ InterfaceIndex = 3; IPAddress = '127.0.0.1'; AddressState = 'Preferred' }
            [pscustomobject]@{ InterfaceIndex = 3; IPAddress = '::1'; AddressState = 'Preferred' }
            [pscustomobject]@{ InterfaceIndex = 3; IPAddress = '10.77.23.50'; AddressState = 'Tentative' }
        }
        (Test-TpvRecoveryServicesReady $backend $https) | Should Be $false
    }

    It 'fails closed when Windows has no profile for the active interface' {
        Mock Get-NetConnectionProfile { [pscustomobject]@{ InterfaceIndex = 8; NetworkCategory = 'Private' } }
        (Test-TpvRecoveryServicesReady $backend $https) | Should Be $false
    }

    It 'uses the HTTPS interface without rejecting unrelated public Wi-Fi or VPN interfaces' {
        Mock Get-NetAdapter {
            [pscustomobject]@{ ifIndex = 3; Status = 'Up' }
            [pscustomobject]@{ ifIndex = 8; Status = 'Up' }
        }
        Mock Get-NetIPAddress {
            [pscustomobject]@{ InterfaceIndex = 3; IPAddress = '10.77.23.50'; AddressState = 'Preferred' }
            [pscustomobject]@{ InterfaceIndex = 8; IPAddress = '192.168.1.50'; AddressState = 'Preferred' }
        }
        Mock Get-NetConnectionProfile {
            [pscustomobject]@{ InterfaceIndex = 3; NetworkCategory = 'Private' }
            [pscustomobject]@{ InterfaceIndex = 8; NetworkCategory = 'Public' }
        }
        # Unresolved hostname: a valid Private LAN is sufficient despite public Wi-Fi.
        (Test-TpvRecoveryServicesReady $backend $https) | Should Be $true
        $privateHttps = @{ PublicUrl = 'https://10.77.23.50:58443'; Program = $https.Program }
        (Test-TpvRecoveryServicesReady $backend $privateHttps) | Should Be $true
        # A configured address on public Wi-Fi cannot borrow the other LAN's profile.
        $publicHttps = @{ PublicUrl = 'https://192.168.1.50:58443'; Program = $https.Program }
        (Test-TpvRecoveryServicesReady $backend $publicHttps) | Should Be $false
        Mock Resolve-TpvRecoveryPublicAddresses { [Net.IPAddress]::Parse('192.168.1.50') }
        (Test-TpvRecoveryServicesReady $backend $https) | Should Be $false
    }

    It 'requires a matching profile for the selected HTTPS interface' {
        Mock Get-NetConnectionProfile { [pscustomobject]@{ InterfaceIndex = 8; NetworkCategory = 'Private' } }
        { Assert-TpvRecoveryNetworkProfiles 'https://10.77.23.50:58443' } | Should Throw 'perfil'
    }

    It 'matches equivalent IPv6 addresses to their active interface' {
        Mock Get-NetIPAddress { [pscustomobject]@{ InterfaceIndex = 3; IPAddress = '2001:db8::50'; AddressState = 'Preferred' } }
        { Assert-TpvRecoveryNetworkProfiles 'https://[2001:0db8:0000:0000:0000:0000:0000:0050]:58443' } | Should Not Throw
    }

    It 'rejects a literal HTTPS address that is not on an active local interface' {
        { Assert-TpvRecoveryNetworkProfiles 'https://10.77.23.99:58443' } | Should Throw 'interfaz local activa'
    }

    It 'fails closed on an unknown network category' {
        Mock Get-NetConnectionProfile { [pscustomobject]@{ InterfaceIndex = 3; NetworkCategory = 'Unknown' } }
        (Test-TpvRecoveryServicesReady $backend $https) | Should Be $false
    }

    It 'fails closed when Windows network inspection raises an error' {
        Mock Get-NetConnectionProfile { throw 'CIM unavailable' }
        (Test-TpvRecoveryServicesReady $backend $https) | Should Be $false
        { Enable-TpvRecoveryExposure $backend $https } | Should Throw 'comprobar'
        Assert-MockCalled Set-NetFirewallRule -Times 0 -Scope It
        Assert-MockCalled Set-Service -Times 0 -Scope It
    }
}
