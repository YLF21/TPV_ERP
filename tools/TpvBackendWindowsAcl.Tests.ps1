Describe 'TPV Windows ACL bootstrap' {
    $installerPath = Join-Path $PSScriptRoot 'Install-TpvBackendWindowsService.ps1'
    $tokens = $null; $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($installerPath, [ref]$tokens, [ref]$parseErrors)
    foreach ($name in @('Assert-RestrictedTreeAcl', 'Assert-RestrictedExportAcl')) {
        $definition = $ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true) |
            Where-Object { $_.Name -eq $name }
        . ([scriptblock]::Create($definition.Extent.Text))
    }
    $provisionPath = Join-Path $PSScriptRoot '..\backend\windows\Provision-VerifactuSecretDirectory.ps1'
    $provisionAst = [System.Management.Automation.Language.Parser]::ParseFile($provisionPath, [ref]$tokens, [ref]$parseErrors)
    foreach ($name in @('Get-NormalizedPath', 'Assert-NoReparsePoint', 'Assert-SafeExistingAncestors')) {
        $definition = $provisionAst.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true) |
            Where-Object { $_.Name -eq $name }
        . ([scriptblock]::Create($definition.Extent.Text))
    }
    It 'walks provision ancestors in Windows PowerShell 5.1 without changing filesystem ACL' {
        $script:visitedPaths = @()
        Mock Assert-NoReparsePoint { param($Path) $script:visitedPaths += $Path }
        Assert-SafeExistingAncestors 'C:\fixture\verifactu'
        ($script:visitedPaths -join '|') | Should Be 'C:\|C:\fixture|C:\fixture\verifactu'
        Assert-MockCalled Assert-NoReparsePoint -Times 3 -Exactly
    }
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
    It 'does not require a virtual SID before registration and never auto-starts registration' {
        $installer = Get-Content (Join-Path $PSScriptRoot 'Install-TpvBackendWindowsService.ps1') -Raw
        $installer | Should Match "ValidateSet\('Register', 'Start'\)"
        $installer | Should Match '<startmode>Manual</startmode>'
        $installer | Should Match "if \(\`$Phase -eq 'Start'\)"
        $installer | Should Match 'Set-Service -Name \$ServiceName -StartupType Automatic'
        $installer | Should Match 'Registre primero el servicio detenido'
        $installer | Should Match 'ExpectedReleaseId'
    }
    It 'matches Java secret FullControl and export Modify without invalid New-Item parameters' {
        $text = Get-Content (Join-Path $PSScriptRoot 'Set-TpvBackendWindowsAcl.ps1') -Raw
        $text | Should Match 'Set-TreeAcl \$SecretRoot \$serviceSid .*::FullControl'
        $text | Should Match 'Set-TreeAcl \$ExportsRoot \$serviceSid .*::Modify'
        $text | Should Not Match 'New-Item[^\r\n]*-LiteralPath'
        $text | Should Match "service.State -ne 'Stopped'"
    }
    It 'validates ACL roots even in WhatIf and orders root protection before overrides' {
        $text = Get-Content (Join-Path $PSScriptRoot 'Set-TpvBackendWindowsAcl.ps1') -Raw
        $text | Should Match 'GetDirectoryName'
        $text | Should Match 'ShouldProcess\(\$Path'
        ($text.IndexOf('Set-TreeAcl $InstallRoot $serviceSid') -lt $text.IndexOf('Set-TreeAcl $SecretRoot $serviceSid')) | Should Be $true
    }
    It 'rejects absent virtual SID with a deliberate error before reading ACL' {
        Mock Get-Acl { throw 'No debe consultar ACL sin SID' }
        $message = ''
        try { Assert-RestrictedExportAcl 'C:\fixture\fiscal' $null }
        catch { $message = $_.Exception.Message }
        $message | Should Match 'Registre primero'
        Assert-MockCalled Get-Acl -Times 0
    }
    It 'accepts export Modify and rejects broader service FullControl using fake ACL only' {
        $sid = [Security.Principal.SecurityIdentifier]::new('S-1-5-80-1-2-3-4-5')
        $rights = [Security.AccessControl.FileSystemRights]::Modify -bor [Security.AccessControl.FileSystemRights]::Synchronize
        $script:fakeRules = @(
            [pscustomobject]@{ IdentityReference = $sid; AccessControlType = [Security.AccessControl.AccessControlType]::Allow; FileSystemRights = $rights },
            [pscustomobject]@{ IdentityReference = [Security.Principal.SecurityIdentifier]::new('S-1-5-18'); AccessControlType = [Security.AccessControl.AccessControlType]::Allow; FileSystemRights = [Security.AccessControl.FileSystemRights]::FullControl },
            [pscustomobject]@{ IdentityReference = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'); AccessControlType = [Security.AccessControl.AccessControlType]::Allow; FileSystemRights = [Security.AccessControl.FileSystemRights]::FullControl }
        )
        Mock Get-Item { [pscustomobject]@{ FullName = 'C:\fixture\fiscal' } }
        Mock Get-ChildItem { @() }
        Mock Get-Acl { [pscustomobject]@{ AreAccessRulesProtected = $true; Access = $script:fakeRules } }
        Assert-RestrictedExportAcl 'C:\fixture\fiscal' $sid
        $script:fakeRules[0].FileSystemRights = [Security.AccessControl.FileSystemRights]::FullControl
        $threw = $false
        try { Assert-RestrictedExportAcl 'C:\fixture\fiscal' $sid } catch { $threw = $true }
        $threw | Should Be $true
    }
}
