Describe 'TPV HTTPS Windows service installer' {
    $installerPath = Join-Path $PSScriptRoot 'Install-TpvHttpsWindowsService.ps1'
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($installerPath, [ref]$tokens, [ref]$errors)
    foreach ($name in @('ConvertTo-ValidatedOrigin', 'ConvertTo-CaddyPath', 'New-TpvCaddyfile',
        'New-TpvWinSwXml', 'Assert-ServiceId', 'Restore-TpvGatewayFiles', 'Assert-BackendDependency',
        'Assert-CaddyConfiguration')) {
        $definition = $ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true) |
            Where-Object { $_.Name -eq $name }
        . ([scriptblock]::Create($definition.Extent.Text))
    }
    function Assert-Throws([scriptblock] $Action) {
        $threw = $false
        try { & $Action | Out-Null } catch { $threw = $true }
        $threw | Should Be $true
    }

    It 'parses with Windows PowerShell and exposes every required generator' {
        @($errors).Count | Should Be 0
        (Get-Command New-TpvCaddyfile).Name | Should Be 'New-TpvCaddyfile'
        (Get-Command New-TpvWinSwXml).Name | Should Be 'New-TpvWinSwXml'
    }

    It 'accepts normalized HTTPS and literal loopback HTTP origins only' {
        (ConvertTo-ValidatedOrigin 'https://tpv.tienda.local:8443' 'https') | Should Be 'https://tpv.tienda.local:8443'
        (ConvertTo-ValidatedOrigin 'http://127.0.0.1:8080' 'http' -Loopback) | Should Be 'http://127.0.0.1:8080'
        $expectedPublicIpv6 = ([Uri]'https://[::1]:8443').GetLeftPart([UriPartial]::Authority)
        $expectedBackendIpv6 = ([Uri]'http://[::1]:8080').GetLeftPart([UriPartial]::Authority)
        (ConvertTo-ValidatedOrigin 'https://[::1]:8443' 'https') | Should Be $expectedPublicIpv6
        (ConvertTo-ValidatedOrigin 'http://[::1]:8080' 'http' -Loopback) | Should Be $expectedBackendIpv6
        (ConvertTo-ValidatedOrigin 'https://[0:0:0:0:0:0:0:1]:8443' 'https') | Should Be $expectedPublicIpv6
        Assert-Throws { ConvertTo-ValidatedOrigin 'https://tpv.tienda.local:8443/path' 'https' }
        Assert-Throws { ConvertTo-ValidatedOrigin 'https://admin@tpv.tienda.local:8443' 'https' }
        Assert-Throws { ConvertTo-ValidatedOrigin 'https://tpv.tienda.local:8443?x=1' 'https' }
        Assert-Throws { ConvertTo-ValidatedOrigin 'https://TPV.TIENDA.LOCAL:8443' 'https' }
        Assert-Throws { ConvertTo-ValidatedOrigin 'https://user@[::1]:8443' 'https' }
        Assert-Throws { ConvertTo-ValidatedOrigin 'https://[::1]:8443/path' 'https' }
        Assert-Throws { ConvertTo-ValidatedOrigin 'https://[::1]:8443#part' 'https' }
        Assert-Throws { ConvertTo-ValidatedOrigin 'http://[::1]:8080?x=1' 'http' -Loopback }
        Assert-Throws { ConvertTo-ValidatedOrigin 'http://[2001:db8::1]:8080' 'http' -Loopback }
        Assert-Throws { ConvertTo-ValidatedOrigin 'http://192.168.1.10:8080' 'http' -Loopback }
        Assert-Throws { ConvertTo-ValidatedOrigin 'http://localhost:8080' 'http' -Loopback }
    }

    It 'accepts the IPv6 port-plan URLs including default ports 80 and 443' {
        Import-Module (Join-Path $PSScriptRoot 'TpvServerPorts.psm1') -Force
        $plan = New-TpvServerPortPlan -BackendAddress '::1' -PublicHost '[::1]' -ExistingBackendPort 80 -ExistingHttpsPort 443
        (ConvertTo-ValidatedOrigin $plan.PublicUrl 'https') | Should Be $plan.PublicUrl
        (ConvertTo-ValidatedOrigin $plan.BackendUrl 'http' -Loopback) | Should Be $plan.BackendUrl
        (ConvertTo-ValidatedOrigin 'https://[::1]:443' 'https') | Should Be $plan.PublicUrl
        (ConvertTo-ValidatedOrigin 'http://[::1]:80' 'http' -Loopback) | Should Be $plan.BackendUrl
    }

    It 'allows Register planning before backend registration but requires it for changes and Start' {
        Assert-BackendDependency $null 'TPVERPBackend' 'Register' $true $false
        Assert-BackendDependency $null 'TPVERPBackend' 'Register' $false $true
        Assert-Throws { Assert-BackendDependency $null 'TPVERPBackend' 'Register' $false $false }
        Assert-Throws { Assert-BackendDependency $null 'TPVERPBackend' 'Start' $true $false }
        Assert-BackendDependency ([pscustomobject]@{Name='TPVERPBackend'}) 'TPVERPBackend' 'Start' $false $false
    }

    It 'generates a manual TLS HTTP/1.1 and HTTP/2 gateway to loopback' {
        $file = New-TpvCaddyfile 'https://tpv.tienda.local:8443' 'http://127.0.0.1:8080' 'C:\ProgramData\TPV ERP\HTTPS\tls\certificate.pem' 'C:\ProgramData\TPV ERP\HTTPS\tls\private-key.pem'
        $file | Should Match 'admin off'
        $file | Should Match 'persist_config off'
        $file | Should Match 'auto_https off'
        $file | Should Match 'ocsp_stapling off'
        $file | Should Match 'protocols h1 h2'
        $file | Should Match 'https://tpv\.tienda\.local:8443'
        $file | Should Match 'tls "C:/ProgramData/TPV ERP/HTTPS/tls/certificate.pem" "C:/ProgramData/TPV ERP/HTTPS/tls/private-key.pem"'
        $file | Should Match 'reverse_proxy http://127\.0\.0\.1:8080'
        $file | Should Not Match 'h3|quic|acme|access_log'
        $file | Should Not Match 'default_sni'
        Assert-Throws { ConvertTo-CaddyPath 'C:\bad"path' }
    }

    It 'selects the configured IP certificate when a client omits SNI' {
        $ipv4 = New-TpvCaddyfile 'https://192.168.1.10:8443' 'http://127.0.0.1:8080' 'C:\cert.pem' 'C:\key.pem'
        $ipv4 | Should Match '(?m)^\s+default_sni 192\.168\.1\.10\s*$'
        $ipv4 | Should Match '(?m)^https://192\.168\.1\.10:8443 \{'
        $ipv4 | Should Not Match '(?m)^https?://\s*\{'

        $ipv6Origin = ([Uri]'https://[::1]:8443').GetLeftPart([UriPartial]::Authority)
        $ipv6 = New-TpvCaddyfile $ipv6Origin 'http://[::1]:8080' 'C:\cert.pem' 'C:\key.pem'
        $ipv6 | Should Match '(?m)^\s+default_sni ::1\s*$'
        $ipv6 | Should Match ([regex]::Escape($ipv6Origin) + ' \{')
        $ipv6 | Should Not Match 'default_sni \['
        $ipv6 | Should Not Match '(?m)^https?://\s*\{'
    }

    It 'generates a manual virtual-account WinSW service with backend dependency and adoption metadata' {
        $xmlText = New-TpvWinSwXml 'TPVERPHTTPS' 'TPVERPBackend' 'C:\ProgramData\TPV ERP\HTTPS\caddy.exe' 'C:\ProgramData\TPV ERP\HTTPS\Caddyfile' 'C:\ProgramData\TPV ERP\HTTPS' 'https://tpv.tienda.local:8443' 'http://127.0.0.1:8080'
        $xml = [xml]$xmlText
        $xml.service.id | Should Be 'TPVERPHTTPS'
        $xml.service.startmode | Should Be 'Manual'
        $xml.service.depend | Should Be 'TPVERPBackend'
        $xml.service.serviceaccount.username | Should Be 'NT SERVICE\TPVERPHTTPS'
        $xml.service.executable | Should Be 'C:\ProgramData\TPV ERP\HTTPS\caddy.exe'
        $xml.service.arguments | Should Be 'run --config "C:\ProgramData\TPV ERP\HTTPS\Caddyfile" --adapter caddyfile'
        (@($xml.service.env) | Where-Object { $_.name -eq 'TPV_HTTPS_PUBLIC_URL' }).value | Should Be 'https://tpv.tienda.local:8443'
        (@($xml.service.env) | Where-Object { $_.name -eq 'TPV_HTTPS_BACKEND_URL' }).value | Should Be 'http://127.0.0.1:8080'
        (@($xml.service.env) | Where-Object { $_.name -eq 'XDG_DATA_HOME' }).value | Should Be 'C:\ProgramData\TPV ERP\HTTPS\storage'
    }

    It 'holds registration behind validation and ShouldProcess with local rollback' {
        $source = [IO.File]::ReadAllText($installerPath)
        $source.IndexOf('Assert-Hash $caddySource') -lt $source.IndexOf('ShouldProcess($install') | Should Be $true
        $source.IndexOf('Assert-ServiceOwnership $service') -lt $source.IndexOf('ShouldProcess($install') | Should Be $true
        $source.IndexOf('Assert-CaddyConfiguration $caddySource') -lt $source.IndexOf('if ($Preflight)') | Should Be $true
        $source.IndexOf('Assert-CaddyConfiguration $caddySource') -lt $source.IndexOf('ShouldProcess($install') | Should Be $true
        $source | Should Match "if \(\`$Preflight\)"
        $source | Should Match 'if \(-not \$PSCmdlet.ShouldProcess\(\$install'
        $source | Should Match '(?s)if \(\$createdService\).*uninstall'
        $source | Should Match '\$previousFiles\[\$target\]'
        $source | Should Match 'Assert-InstalledGateway \$install'
        $source | Should Match '\$CaddyfileText \| & \$Executable validate --config - --adapter caddyfile'
        $source | Should Match '\$mutatedFiles\[\$target\]'
        $source | Should Match 'Restore-TpvGatewayFiles \$previousFiles \$mutatedFiles'
        $source | Should Match 'Set-Service -Name \$ServiceName -StartupType Manual'
        $source | Should Not Match 'Invoke-WebRequest|Start-BitsTransfer|certutil.*-addstore'
    }

    It 'holds the shared installation mutex through preflight and early returns' {
        $source = [IO.File]::ReadAllText($installerPath)
        $source | Should Match "Import-Module \(Join-Path \`$PSScriptRoot 'TpvServerPorts.psm1'\)"
        $source | Should Match '\$installationMutex = Enter-TpvServerInstallationLock'
        $source | Should Match 'finally \{\s+Exit-TpvServerInstallationLock -Mutex \$installationMutex'
        $source.LastIndexOf('Assert-Administrator') -lt $source.IndexOf('$installationMutex = Enter-TpvServerInstallationLock') | Should Be $true
        $source.IndexOf('$installationMutex = Enter-TpvServerInstallationLock') -lt $source.IndexOf('Assert-ServiceId $ServiceName') | Should Be $true
        $source.IndexOf('$installationMutex = Enter-TpvServerInstallationLock') -lt $source.IndexOf('if ($Preflight)') | Should Be $true
    }

    It 'validates the proposed Caddyfile through stdin and hides native output' {
        $fake = Join-Path $TestDrive 'fake-caddy.ps1'
        [IO.File]::WriteAllText($fake, @'
param([string] $Command, [string] $ConfigFlag, [string] $Dash, [string] $AdapterFlag, [string] $Adapter)
$body = @($input) -join "`n"
if ($Command -ne 'validate' -or $ConfigFlag -ne '--config' -or $Dash -ne '-' -or
    $AdapterFlag -ne '--adapter' -or $Adapter -ne 'caddyfile' -or
    $body -notmatch 'ocsp_stapling off') {
    $global:LASTEXITCODE = 7
    Write-Output 'internal validation detail'
} else {
    $global:LASTEXITCODE = 0
    Write-Output 'internal success detail'
}
'@)
        $file = New-TpvCaddyfile 'https://tpv.tienda.local:8443' 'http://127.0.0.1:8080' 'C:\cert.pem' 'C:\key.pem'
        @(Assert-CaddyConfiguration $fake $file).Count | Should Be 0
        Assert-Throws { Assert-CaddyConfiguration $fake 'bad config' }
    }

    It 'restores only changed files and removes only newly created files without service operations' {
        $backup = Join-Path $TestDrive 'old.bak'
        $existing = Join-Path $TestDrive 'existing.xml'
        $created = Join-Path $TestDrive 'new.Caddyfile'
        $untouched = Join-Path $TestDrive 'untouched.pem'
        [IO.File]::WriteAllText($backup, 'prior config')
        [IO.File]::WriteAllText($existing, 'new config')
        [IO.File]::WriteAllText($created, 'new file')
        [IO.File]::WriteAllText($untouched, 'original key')
        Restore-TpvGatewayFiles @{$existing = $backup} @{$existing = $true; $created = $true}
        [IO.File]::ReadAllText($existing) | Should Be 'prior config'
        (Test-Path -LiteralPath $created) | Should Be $false
        [IO.File]::ReadAllText($untouched) | Should Be 'original key'
    }
}
