Describe 'TPV Electron backend config ACL' {
    It 'uses only the fixed ProgramData location and rejects reparse points' {
        $text = Get-Content (Join-Path $PSScriptRoot 'Set-TpvDesktopBackendConfigAcl.ps1') -Raw
        $text | Should BeLike '*C:\ProgramData\TPV ERP\desktop\backend-config.json*'
        $text | Should BeLike '*ReparsePoint*'
        $text | Should BeLike '*Assert-ProductionPath*'
        $text | Should Not Match 'param\(\s*\[string\]'
    }

    It 'protects the controlled directory chain and file separately' {
        $text = Get-Content (Join-Path $PSScriptRoot 'Set-TpvDesktopBackendConfigAcl.ps1') -Raw
        $root = $text.Substring($text.IndexOf('function New-ProductionRootAcl'), $text.IndexOf('function New-DesktopDirectoryAcl') - $text.IndexOf('function New-ProductionRootAcl'))
        $desktop = $text.Substring($text.IndexOf('function New-DesktopDirectoryAcl'), $text.IndexOf('function New-FileAcl') - $text.IndexOf('function New-DesktopDirectoryAcl'))
        $text | Should BeLike '*DirectorySecurity*'
        $text | Should BeLike '*FileSecurity*'
        $text | Should BeLike '*SetAccessRuleProtection*true*false*'
        $root | Should BeLike '*InheritanceFlags]::None*'
        $root | Should Not BeLike '*ContainerInherit*'
        $root | Should Not BeLike '*ObjectInherit*'
        $root | Should BeLike '*S-1-5-11*'
        $root | Should BeLike '*NT SERVICE\TPVERPBackend*'
        $desktop | Should BeLike '*ContainerInherit*'
        $desktop | Should BeLike '*ObjectInherit*'
        $text | Should BeLike '*S-1-5-32-545*'
        $text | Should BeLike '*S-1-5-32-544*'
        $text | Should BeLike '*S-1-5-18*'
        $text | Should BeLike '*ReadAndExecute*'
        $text | Should BeLike '*FullControl*'
    }
}

Describe 'TPV desktop backend configuration origin validation' {
    $writerPath = Join-Path $PSScriptRoot '..\frontend\tools\write-backend-config.ps1'
    $source = Get-Content -LiteralPath $writerPath -Raw
    $start = $source.IndexOf('if ($BackendUrl.Length')
    $end = $source.IndexOf('$payload =', $start)
    $validation = [scriptblock]::Create('param([string] $BackendUrl)' + "`n" +
        $source.Substring($start, $end - $start) + "`n" + 'return $config')

    It 'recognizes IPv6 loopback in the Windows PowerShell authority form without writing files' {
        $origin = ([Uri]'http://[::1]:18080').GetLeftPart([UriPartial]::Authority)
        $config = & $validation $origin
        $config.backendUrl | Should Be $origin
        @($config.allowedHosts).Count | Should Be 0
    }

    It 'keeps remote HTTP, non-approved loopback aliases and credential origins prohibited' {
        foreach ($origin in @('http://192.168.1.1:18080', 'http://[2001:db8::1]:18080',
            'http://127.0.0.2:18080', 'http://admin@127.0.0.1:18080')) {
            { & $validation $origin } | Should Throw
        }
    }
}
