Describe 'TPV offline restore entry point' {
    BeforeAll {
        $script = Join-Path $PSScriptRoot 'Invoke-TpvBackupRestoreOffline.ps1'
        $preflight = Join-Path $PSScriptRoot 'Test-TpvBackupRestoreOfflinePreflight.ps1'
    }
    It 'has an explicit execute gate and never accepts a recovery secret parameter' {
        $text = Get-Content -LiteralPath $script -Raw
        $text | Should BeLike '*$Execute*'
        $text | Should Not BeLike '*$RecoverySecret*'
    }
    It 'routes through the read-only preflight' {
        (Get-Content -LiteralPath $script -Raw) | Should BeLike '*Test-TpvBackupRestoreOfflinePreflight.ps1*'
        (Get-Content -LiteralPath $preflight -Raw) | Should BeLike '*pg_restore*'
    }
    It 'requires a journal and finalize before normal startup' {
        $text = Get-Content -LiteralPath $script -Raw
        $text | Should BeLike '*tpv-restore-journal.properties*'
        $text | Should BeLike '*tpv.restore-finalize*'
    }
}
