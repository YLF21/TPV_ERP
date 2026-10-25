$ErrorActionPreference = 'Stop'

# Load only the transition guard. The full installer performs Windows service operations.
$installer = Join-Path $PSScriptRoot 'Install-TpvBackendTestWindowsService.ps1'
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($installer, [ref] $tokens, [ref] $parseErrors)
if (@($parseErrors).Count -ne 0) { throw 'TEST_ONLY installer PowerShell parser failed' }
$guard = $ast.Find({ param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -ceq 'Assert-TpvTestOnlyUpdateTransition'
}, $true)
if ($null -eq $guard) { throw 'TEST_ONLY Update transition guard missing' }
. ([scriptblock]::Create($guard.Extent.Text))

function New-TestRelease([string] $Id, [string] $Hash, [long] $Sequence,
    [long] $Build = 1, [string] $Version = '0.1.0', [string] $Schema = 'V279',
    [string] $Status = 'TEST_ONLY_VERIFIED') {
    [pscustomobject]@{
        Status = $Status
        ReleaseId = $Id
        ArtifactSha256 = $Hash
        Version = $Version
        SchemaVersion = $Schema
        ReleaseSequence = $Sequence
        BuildSequence = $Build
    }
}

Describe 'TEST_ONLY Update release sequence transition' {
    It 'rejects the current release even when its bytes are unchanged' {
        $old = New-TestRelease 'tpv-erp-test-only-0.1.0-v279-3c59c0466f21' ('A' * 64) 36
        $candidate = New-TestRelease $old.ReleaseId $old.ArtifactSha256 37
        { Assert-TpvTestOnlyUpdateTransition $old $candidate } | Should Throw 'release nueva'
    }

    It 'rejects the same immutable release ID with different bytes' {
        $old = New-TestRelease 'tpv-erp-test-only-0.1.0-v279-3c59c0466f21' ('A' * 64) 36
        $candidate = New-TestRelease $old.ReleaseId ('B' * 64) 37
        { Assert-TpvTestOnlyUpdateTransition $old $candidate } | Should Throw 'otro hash'
    }

    It 'rejects a new release ID with the same releaseSequence despite a higher buildSequence' {
        $old = New-TestRelease 'tpv-erp-test-only-0.1.0-v279-3c59c0466f21' ('A' * 64) 36
        $candidate = New-TestRelease 'tpv-erp-test-only-0.1.0-v279-957b2b3ab3fb' ('B' * 64) 36 99
        { Assert-TpvTestOnlyUpdateTransition $old $candidate } | Should Throw 'estrictamente mayor'
    }

    It 'rejects a lower releaseSequence' {
        $old = New-TestRelease 'tpv-erp-test-only-0.1.0-v279-3c59c0466f21' ('A' * 64) 36
        $candidate = New-TestRelease 'tpv-erp-test-only-0.1.0-v279-957b2b3ab3fb' ('B' * 64) 35
        { Assert-TpvTestOnlyUpdateTransition $old $candidate } | Should Throw 'downgrade'
    }

    It 'accepts a verified new release with a strictly higher releaseSequence' {
        $old = New-TestRelease 'tpv-erp-test-only-0.1.0-v279-3c59c0466f21' ('A' * 64) 36
        $candidate = New-TestRelease 'tpv-erp-test-only-0.1.0-v279-957b2b3ab3fb' ('B' * 64) 37
        { Assert-TpvTestOnlyUpdateTransition $old $candidate } | Should Not Throw
    }

    It 'still rejects version, schema, and channel downgrades with a higher sequence' {
        $old = New-TestRelease 'tpv-erp-test-only-0.1.0-v279-3c59c0466f21' ('A' * 64) 36
        $candidate = New-TestRelease 'tpv-erp-test-only-0.1.0-v279-957b2b3ab3fb' ('B' * 64) 37 1 '0.0.9'
        { Assert-TpvTestOnlyUpdateTransition $old $candidate } | Should Throw 'downgrade'
        $candidate.Version = '0.1.0'; $candidate.SchemaVersion = 'V278'
        { Assert-TpvTestOnlyUpdateTransition $old $candidate } | Should Throw 'downgrade'
        $candidate.SchemaVersion = 'V279'; $candidate.Status = 'PRODUCTION_VERIFIED'
        { Assert-TpvTestOnlyUpdateTransition $old $candidate } | Should Throw 'TEST_ONLY'
    }
}
