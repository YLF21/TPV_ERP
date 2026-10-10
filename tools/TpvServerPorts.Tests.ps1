$modulePath = Join-Path $PSScriptRoot 'TpvServerPorts.psm1'
Import-Module $modulePath -Force

Describe 'TpvServerPorts selection' {
    It 'normalizes the public host and uses the first free preferred ports' {
        Mock Test-TpvTcpPortAvailable -ModuleName TpvServerPorts -MockWith { return $true }
        $plan = New-TpvServerPortPlan -PublicHost 'Server.Example.COM.'
        $plan.SchemaVersion | Should Be 1
        $plan.PublicHost | Should Be 'server.example.com'
        $plan.BackendPort | Should Be 8080
        $plan.HttpsPort | Should Be 8443
        $plan.BackendUrl | Should Be 'http://127.0.0.1:8080'
        $plan.PublicUrl | Should Be 'https://server.example.com:8443'
        Assert-MockCalled Test-TpvTcpPortAvailable -ModuleName TpvServerPorts -Scope It -Exactly 1 -ParameterFilter {
            $Address -eq '127.0.0.1' -and $Port -eq 8080
        }
        Assert-MockCalled Test-TpvTcpPortAvailable -ModuleName TpvServerPorts -Scope It -Exactly 1 -ParameterFilter {
            $Address -eq '0.0.0.0' -and $Port -eq 8443
        }
    }

    It 'falls back in the documented order and never chooses one port twice' {
        Mock Test-TpvTcpPortAvailable -ModuleName TpvServerPorts -MockWith {
            if ($Address -eq '127.0.0.1') { return $Port -eq 18080 }
            return $Port -eq 18443
        }
        $plan = New-TpvServerPortPlan -PublicHost '192.168.1.15' -PreferredHttpsPort 18080
        $plan.BackendPort | Should Be 18080
        $plan.HttpsPort | Should Be 18443
        $plan.PublicUrl | Should Be 'https://192.168.1.15:18443'
        Assert-MockCalled Test-TpvTcpPortAvailable -ModuleName TpvServerPorts -Scope It -Exactly 0 -ParameterFilter {
            $Address -eq '0.0.0.0' -and $Port -eq 18080
        }
    }

    It 'continues through the sequential fallback ranges' {
        Mock Test-TpvTcpPortAvailable -ModuleName TpvServerPorts -MockWith {
            if ($Address -eq '127.0.0.1') { return $Port -eq 18081 }
            return $Port -eq 18444
        }
        $plan = New-TpvServerPortPlan -PublicHost 'server.example.com'
        $plan.BackendPort | Should Be 18081
        $plan.HttpsPort | Should Be 18444
    }

    It 'fails when there is no free backend port' {
        Mock Test-TpvTcpPortAvailable -ModuleName TpvServerPorts -MockWith { return $false }
        { New-TpvServerPortPlan -PublicHost 'server.example.com' } | Should Throw
    }

    It 'rejects URL and authority injection in PublicHost' {
        Mock Test-TpvTcpPortAvailable -ModuleName TpvServerPorts -MockWith { return $true }
        foreach ($hostName in @('https://server.example.com', 'user@server.example.com',
                                  'server.example.com:8443', 'server.example.com/path',
                                  'server.example.com?x=1', 'server example.com',
                                  'server.example.com\evil')) {
            { New-TpvServerPortPlan -PublicHost $hostName } | Should Throw
        }
    }

    It 'requires a loopback backend and formats an IPv6 public host' {
        Mock Test-TpvTcpPortAvailable -ModuleName TpvServerPorts -MockWith { return $true }
        { New-TpvServerPortPlan -BackendAddress '0.0.0.0' -PublicHost 'server.example.com' } | Should Throw
        $plan = New-TpvServerPortPlan -PublicHost '[2001:db8::1]'
        $plan.PublicHost | Should Be '2001:db8::1'
        $uri = [Uri] $plan.PublicUrl
        $plan.PublicUrl | Should Be $uri.GetLeftPart([UriPartial]::Authority)
        $uri.Scheme | Should Be 'https'
        $uri.Port | Should Be 8443
        ([Net.IPAddress]::Parse($uri.Host.Trim('[', ']')).Equals([Net.IPAddress]::Parse('2001:db8::1'))) | Should Be $true
    }

    It 'uses Uri authority form for default HTTP and HTTPS ports' {
        Mock Test-TpvTcpPortAvailable -ModuleName TpvServerPorts -MockWith { return $true }
        $plan = New-TpvServerPortPlan -PublicHost 'server.example.com' -PreferredBackendPort 80 -PreferredHttpsPort 443
        $plan.BackendUrl | Should Be 'http://127.0.0.1'
        $plan.PublicUrl | Should Be 'https://server.example.com'
    }

    It 'exports normalized public host and loopback address helpers' {
        (ConvertTo-TpvPublicHost 'Server.Example.COM.') | Should Be 'server.example.com'
        (ConvertTo-TpvBackendAddress '127.0.0.1') | Should Be '127.0.0.1'
    }

    It 'keeps a validated existing backend port without probing it' {
        Mock Test-TpvTcpPortAvailable -ModuleName TpvServerPorts -MockWith {
            if ($Address -eq '127.0.0.1') { throw 'The active service must not be probed.' }
            return $Port -eq 18443
        }
        $plan = New-TpvServerPortPlan -PublicHost 'server.example.com' -ExistingBackendPort 9090
        $plan.BackendPort | Should Be 9090
        $plan.HttpsPort | Should Be 18443
        Assert-MockCalled Test-TpvTcpPortAvailable -ModuleName TpvServerPorts -Scope It -Exactly 0 -ParameterFilter {
            $Address -eq '127.0.0.1'
        }
    }

    It 'keeps a validated existing HTTPS port without probing it' {
        Mock Test-TpvTcpPortAvailable -ModuleName TpvServerPorts -MockWith {
            if ($Address -eq '0.0.0.0') { throw 'The active gateway must not be probed.' }
            return $Port -eq 18080
        }
        $plan = New-TpvServerPortPlan -PublicHost 'server.example.com' -ExistingHttpsPort 8080
        $plan.BackendPort | Should Be 18080
        $plan.HttpsPort | Should Be 8080
        Assert-MockCalled Test-TpvTcpPortAvailable -ModuleName TpvServerPorts -Scope It -Exactly 0 -ParameterFilter {
            $Address -eq '0.0.0.0'
        }
    }

    It 'rejects identical fixed backend and HTTPS ports' {
        Mock Test-TpvTcpPortAvailable -ModuleName TpvServerPorts -MockWith { throw 'No probe is expected.' }
        { New-TpvServerPortPlan -PublicHost 'server.example.com' -ExistingBackendPort 8443 -ExistingHttpsPort 8443 } | Should Throw
        Assert-MockCalled Test-TpvTcpPortAvailable -ModuleName TpvServerPorts -Scope It -Exactly 0
    }
}

Describe 'TpvServerPorts socket probes' {
    It 'detects a bound IPv4 loopback port and releases its own probes' {
        $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
        $listener.Start()
        try {
            $port = $listener.LocalEndpoint.Port
            (Test-TpvTcpPortAvailable -Address '127.0.0.1' -Port $port) | Should Be $false
        } finally {
            $listener.Stop()
        }
        (Test-TpvTcpPortAvailable -Address '127.0.0.1' -Port $port) | Should Be $true
    }

    It 'checks the IPv6 wildcard when HTTPS binds all IPv4 interfaces' {
        if (-not [Net.Sockets.Socket]::OSSupportsIPv6) { return }
        $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::IPv6Any, 0)
        $listener.Server.DualMode = $false
        $listener.Start()
        try {
            $port = $listener.LocalEndpoint.Port
            (Test-TpvTcpPortAvailable -Address '0.0.0.0' -Port $port) | Should Be $false
        } finally {
            $listener.Stop()
        }
    }
}

Describe 'TpvServerPorts persistence' {
    BeforeEach {
        $testDirectory = Join-Path ([IO.Path]::GetTempPath()) ('tpv-server-ports-' + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $testDirectory | Out-Null
        $planPath = Join-Path $testDirectory 'server-ports.json'
        Mock Assert-TpvAdministrator -ModuleName TpvServerPorts -MockWith { }
        Mock Set-TpvPlanFileAcl -ModuleName TpvServerPorts -MockWith { }
    }

    AfterEach {
        Remove-Item -LiteralPath $testDirectory -Recurse -Force
    }

    It 'writes atomically, reads back, and accepts an identical repeat' {
        Mock Test-TpvTcpPortAvailable -ModuleName TpvServerPorts -MockWith { return $true }
        $plan = New-TpvServerPortPlan -PublicHost 'server.example.com'
        $written = Write-TpvServerPortPlan -Path $planPath -Plan $plan
        $written.PublicUrl | Should Be $plan.PublicUrl
        (Read-TpvServerPortPlan -Path $planPath).BackendPort | Should Be 8080
        (Write-TpvServerPortPlan -Path $planPath -Plan $plan).PublicUrl | Should Be $plan.PublicUrl
        @(Get-ChildItem -LiteralPath $testDirectory -Force).Count | Should Be 1
        Assert-MockCalled Set-TpvPlanFileAcl -ModuleName TpvServerPorts -Scope It -Exactly 2
    }

    It 'does not overwrite a different existing plan' {
        Mock Test-TpvTcpPortAvailable -ModuleName TpvServerPorts -MockWith { return $true }
        $first = New-TpvServerPortPlan -PublicHost 'one.example.com'
        $second = New-TpvServerPortPlan -PublicHost 'two.example.com'
        Write-TpvServerPortPlan -Path $planPath -Plan $first | Out-Null
        { Write-TpvServerPortPlan -Path $planPath -Plan $second } | Should Throw
        (Read-TpvServerPortPlan -Path $planPath).PublicHost | Should Be 'one.example.com'
    }

    It 'reads persisted plans using standard HTTP and HTTPS ports' {
        Mock Test-TpvTcpPortAvailable -ModuleName TpvServerPorts -MockWith { return $true }
        $plan = New-TpvServerPortPlan -PublicHost 'server.example.com' -PreferredBackendPort 80 -PreferredHttpsPort 443
        Write-TpvServerPortPlan -Path $planPath -Plan $plan | Out-Null
        $read = Read-TpvServerPortPlan -Path $planPath
        $read.BackendPort | Should Be 80
        $read.HttpsPort | Should Be 443
        $read.BackendUrl | Should Be 'http://127.0.0.1'
        $read.PublicUrl | Should Be 'https://server.example.com'
    }

    It 'rejects corrupted schema, duplicate keys, and inconsistent URLs' {
        @(
            '{}',
            '{"SchemaVersion":1,"SchemaVersion":1}',
            '{"SchemaVersion":2,"BackendAddress":"127.0.0.1","PublicHost":"server.example.com","BackendPort":8080,"HttpsPort":8443,"BackendUrl":"http://127.0.0.1:8080","PublicUrl":"https://server.example.com:8443"}',
            '{"SchemaVersion":1,"BackendAddress":"127.0.0.1","PublicHost":"server.example.com","BackendPort":8080,"HttpsPort":8443,"BackendUrl":"http://127.0.0.1:8080","PublicUrl":"http://server.example.com:8443"}'
        ) | ForEach-Object {
            [IO.File]::WriteAllText($planPath, $_)
            { Read-TpvServerPortPlan -Path $planPath } | Should Throw
        }
    }

    It 'rejects a reparse point in an ancestor' {
        $target = Join-Path $testDirectory 'target'
        $link = Join-Path $testDirectory 'link'
        New-Item -ItemType Directory -Path $target | Out-Null
        try {
            New-Item -ItemType Junction -Path $link -Target $target -ErrorAction Stop | Out-Null
        } catch {
            return
        }
        { Read-TpvServerPortPlan -Path (Join-Path $link 'server-ports.json') } | Should Throw
        { Write-TpvServerPortPlan -Path (Join-Path $link 'server-ports.json') -Plan (New-TpvServerPortPlan -PublicHost 'server.example.com') } | Should Throw
    }
}

Describe 'TpvServerPorts file ACL' {
    It 'assigns explicit full control only to Administrators and SYSTEM' {
        InModuleScope TpvServerPorts {
            $script:capturedAcl = $null
            Mock Set-Acl -MockWith { $script:capturedAcl = $AclObject }
            Set-TpvPlanFileAcl 'C:\fake\server-ports.json'
            $rules = @($script:capturedAcl.GetAccessRules($true, $false, [Security.Principal.SecurityIdentifier]))
            $rules.Count | Should Be 2
            @($rules | ForEach-Object { $_.IdentityReference.Value } | Sort-Object) | Should Be @('S-1-5-18', 'S-1-5-32-544')
            @($rules | Where-Object { $_.FileSystemRights -ne [Security.AccessControl.FileSystemRights]::FullControl }).Count | Should Be 0
            $script:capturedAcl.AreAccessRulesProtected | Should Be $true
        }
    }
}

Describe 'TpvServerPorts installation lock' {
    It 'allows nested acquisition on the same thread and releases both levels' {
        $name = 'Global\TPVERPServerInstallation-Test-' + [Guid]::NewGuid().ToString('N')
        $outer = Enter-TpvServerInstallationLock -Name $name
        try {
            $inner = Enter-TpvServerInstallationLock -Name $name
            try {
                $outer | Should Not BeNullOrEmpty
                $inner | Should Not BeNullOrEmpty
            } finally {
                Exit-TpvServerInstallationLock -Mutex $inner
            }
        } finally {
            Exit-TpvServerInstallationLock -Mutex $outer
        }
        $again = Enter-TpvServerInstallationLock -Name $name
        Exit-TpvServerInstallationLock -Mutex $again
    }

    It 'rejects another process immediately and permits a retry after release' {
        $name = 'Global\TPVERPServerInstallation-Test-' + [Guid]::NewGuid().ToString('N')
        $testDirectory = Join-Path ([IO.Path]::GetTempPath()) ('tpv-install-lock-' + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $testDirectory | Out-Null
        $ready = Join-Path $testDirectory 'ready'
        $release = Join-Path $testDirectory 'release'
        $child = $null
        try {
            $childScript = @'
$ErrorActionPreference = 'Stop'
Import-Module '__MODULE__' -ErrorAction Stop
$mutex = Enter-TpvServerInstallationLock -Name '__NAME__'
try {
    [IO.File]::WriteAllText('__READY__', 'ready')
    for ($i = 0; $i -lt 200 -and -not (Test-Path -LiteralPath '__RELEASE__'); $i++) {
        Start-Sleep -Milliseconds 50
    }
} finally {
    Exit-TpvServerInstallationLock -Mutex $mutex
}
'@
            $childScript = $childScript.Replace('__MODULE__', $modulePath.Replace("'", "''"))
            $childScript = $childScript.Replace('__NAME__', $name)
            $childScript = $childScript.Replace('__READY__', $ready.Replace("'", "''"))
            $childScript = $childScript.Replace('__RELEASE__', $release.Replace("'", "''"))
            $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($childScript))
            $child = Start-Process -FilePath (Get-Command powershell.exe).Source -ArgumentList @(
                '-NoProfile', '-ExecutionPolicy', 'RemoteSigned', '-EncodedCommand', $encoded
            ) -PassThru -WindowStyle Hidden
            $deadline = [DateTime]::UtcNow.AddSeconds(5)
            while (-not (Test-Path -LiteralPath $ready) -and [DateTime]::UtcNow -lt $deadline -and -not $child.HasExited) {
                Start-Sleep -Milliseconds 50
            }
            (Test-Path -LiteralPath $ready) | Should Be $true
            { Enter-TpvServerInstallationLock -Name $name } | Should Throw 'Otra instalacion'
            [IO.File]::WriteAllText($release, 'release')
            $child.WaitForExit(5000) | Should Be $true
            $child.ExitCode | Should Be 0
            $again = Enter-TpvServerInstallationLock -Name $name
            Exit-TpvServerInstallationLock -Mutex $again
        } finally {
            if ($null -ne $child -and -not $child.HasExited) {
                Stop-Process -Id $child.Id -Force
                $child.WaitForExit(5000) | Out-Null
            }
            if ($null -ne $child) { $child.Dispose() }
            Remove-Item -LiteralPath $testDirectory -Recurse -Force
        }
    }
}
