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
