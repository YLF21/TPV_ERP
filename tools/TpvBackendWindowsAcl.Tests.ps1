Describe 'TPV Windows ACL bootstrap' {
    It 'requires registration before resolving the virtual service identity' {
        $text = Get-Content (Join-Path $PSScriptRoot 'Set-TpvBackendWindowsAcl.ps1') -Raw
        $text | Should BeLike "*Phase -eq 'Register'*"
        $text | Should BeLike '*NT SERVICE*'
        $text | Should BeLike '*debe registrarse antes de aplicar ACL*'
    }
    It 'keeps immutable trees read-only and operational trees writable' {
        $text = Get-Content (Join-Path $PSScriptRoot 'Set-TpvBackendWindowsAcl.ps1') -Raw
        $text | Should BeLike '*ReadAndExecute*'
        $text | Should BeLike '*::Modify*'
        $text | Should BeLike '*SetAccessRuleProtection*true*false*'
    }
    It 'defines the exact security principals and root classes' {
        $text = Get-Content (Join-Path $PSScriptRoot 'Set-TpvBackendWindowsAcl.ps1') -Raw
        $text | Should BeLike '*S-1-5-32-544*'
        $text | Should BeLike '*S-1-5-18*'
        $text | Should BeLike '*BackupRoot*'
        $text | Should BeLike '*RestoreJournalRoot*'
        $text | Should BeLike '*FileSecurity*'
        $text | Should BeLike '*DirectorySecurity*'
        $text | Should BeLike '*ReadAndExecute*'
        $text | Should BeLike '*::Modify*'
    }
    It 'requires the registered service to run as its virtual account' {
        $text = Get-Content (Join-Path $PSScriptRoot 'Set-TpvBackendWindowsAcl.ps1') -Raw
        $text | Should BeLike '*StartName*NT SERVICE*'
        $installer = Get-Content (Join-Path $PSScriptRoot 'Install-TpvBackendWindowsService.ps1') -Raw
        $installer | Should BeLike '*ServiceAccount = ''VirtualService''*'
        $installer | Should BeLike '*NT SERVICE*ServiceName*'
    }
}
