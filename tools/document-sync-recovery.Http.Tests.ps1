#requires -Version 5.1
param([Parameter(Mandatory = $true)][string]$FixtureOrigin)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$fixtureUri = [Uri]$FixtureOrigin
if ($fixtureUri.Scheme -ne 'http' -or $fixtureUri.Host -ne '127.0.0.1' -or $fixtureUri.Port -in @(8080, 8088, 8090)) {
    throw 'This test only accepts an isolated loopback fixture, never the application backend.'
}
$recoveryScript = Join-Path $PSScriptRoot 'document-sync-recovery.ps1'
. $recoveryScript
$testDirectory = Join-Path ([IO.Path]::GetTempPath()) ('tpv-recovery-http-' + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $testDirectory
function Fixture-Token([string]$Mode) { ConvertTo-SecureString ('FIXTURE-' + $Mode) -AsPlainText -Force }
function Assert-Test([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Assert-SafeRejection([scriptblock]$Operation, [string]$MessagePattern) {
    try { $null = & $Operation }
    catch {
        Assert-Test ($_.Exception.Data['RecoverySafeMessage'] -eq $true) 'Unsanitized exception'
        Assert-Test ($_.Exception.Message -match $MessagePattern) 'Unexpected safe error message'
        Assert-Test ($_.Exception.Message -notmatch 'RESPONSE_BODY_MUST_NOT_ESCAPE|FIXTURE-') 'Response or credential leaked'
        return
    }
    throw 'Expected rejection did not occur'
}

try {
    $previewOptions = @{
        Action = 'Preview'; BaseUrl = $FixtureOrigin; BearerToken = (Fixture-Token 'success')
        CompanyId = '11111111-1111-1111-1111-111111111111'; StoreId = '22222222-2222-2222-2222-222222222222'
        DateFrom = '2026-08-01'; DateTo = '2026-08-31'; CreatedBefore = '2026-09-10T12:34:56.123456789Z'
        OutputPath = (Join-Path $testDirectory 'preview.json')
    }
    # Exercise the public script entrypoint as well as its unmodified HTTP transport.
    $preview = & $recoveryScript @previewOptions
    Assert-Test ($preview.DocumentCount -eq 1) 'Preview count'
    $beforeHash = Get-RecoveryHash $preview.ManifestPath
    $savedPreview = Read-RecoveryManifest $preview.ManifestPath
    Assert-Test ($savedPreview.documents[0].total -ceq '9007199254740993.01') 'Amount precision'
    Assert-Test ($savedPreview.documents[0].number -ceq ('T-001-' + [char]0x5BA2)) 'UTF-8 response'
    $prepareOptions = @{ Action = 'Prepare'; BaseUrl = $FixtureOrigin; BearerToken = (Fixture-Token 'success')
        ManifestPath = $preview.ManifestPath; Reason = 'Synthetic HTTP test' }
    $prepared = & $recoveryScript @prepareOptions
    Assert-Test ($null -eq $prepared.Complete) 'ENQUEUED must not imply verified'
    $verifyOptions = @{ Action = 'Verify'; BaseUrl = $FixtureOrigin; BearerToken = (Fixture-Token 'pending')
        ManifestPath = $prepared.ManifestPath }
    $pending = & $recoveryScript @verifyOptions
    Assert-Test (-not $pending.Complete) 'Pending must remain incomplete'
    $verifyOptions.ManifestPath = $pending.ManifestPath
    $verifyOptions.BearerToken = Fixture-Token 'missing-customer'
    $missingCustomer = & $recoveryScript @verifyOptions
    Assert-Test (-not $missingCustomer.Complete) 'Missing customer must remain incomplete'
    $verifyOptions.ManifestPath = $missingCustomer.ManifestPath
    $verifyOptions.BearerToken = Fixture-Token 'success'
    $verified = & $recoveryScript @verifyOptions
    Assert-Test $verified.Complete 'Verified result'
    $saved = Read-RecoveryManifest $verified.ManifestPath
    Assert-Test ($saved.receipts[0].sourceRevision -ceq '9007199254740993') 'Revision precision'
    Assert-Test ($saved.scope.createdBefore -ceq '2026-09-10T12:34:56.123456789Z') 'Cutoff precision'
    $retry = & $recoveryScript @prepareOptions
    Assert-Test ($retry.ManifestPath -ne $prepared.ManifestPath) 'Retry must create a different checkpoint'
    $verifyOptions.ManifestPath = $verified.ManifestPath
    $retryVerified = & $recoveryScript @verifyOptions
    Assert-Test $retryVerified.Complete 'Explicit verify retry'
    Assert-Test ((Get-RecoveryHash $preview.ManifestPath) -ceq $beforeHash) 'Preview changed'

    $verifyOptions.BearerToken = Fixture-Token 'wrong-event'
    Assert-SafeRejection { Invoke-DocumentSyncRecovery $verifyOptions } 'recibo preparado'
    $prepareOptions.BearerToken = Fixture-Token 'numeric-revision'
    Assert-SafeRejection { Invoke-DocumentSyncRecovery $prepareOptions } 'revision debe ser una cadena'
    $previewOptions.OutputPath = Join-Path $testDirectory 'wrong-scope.json'
    $previewOptions.BearerToken = Fixture-Token 'wrong-scope'
    Assert-SafeRejection { Invoke-DocumentSyncRecovery $previewOptions } 'ambito y corte'
    Assert-Test (-not (Test-Path -LiteralPath $previewOptions.OutputPath)) 'Invalid response persisted'
    foreach ($status in @(401,403,404,500)) {
        Assert-SafeRejection { Invoke-RecoveryRequest ($FixtureOrigin + '/api/v1/sync/document-recovery/preview') (Fixture-Token ('http-' + $status)) @{} } ('HTTP ' + $status)
    }
    foreach ($case in @(
        @('redirect', 'redirecciones'), @('invalid-json', 'JSON compatible'),
        @('oversize-known', '4 MiB'), @('oversize-chunked', '4 MiB'),
        @('disconnect', 'No se obtuvo respuesta')
    )) {
        Assert-SafeRejection { Invoke-RecoveryRequest ($FixtureOrigin + '/api/v1/sync/document-recovery/preview') (Fixture-Token $case[0]) @{} } $case[1]
    }
    foreach ($file in Get-ChildItem -LiteralPath $testDirectory -File) {
        Assert-Test (([IO.File]::ReadAllText($file.FullName)) -notmatch 'FIXTURE-|RESPONSE_BODY_MUST_NOT_ESCAPE') 'Secret persisted'
    }
    Write-Output ('PowerShell ' + $PSVersionTable.PSVersion + ': real HTTP flow, precision, retries and safe rejections passed')
}
finally {
    # Only this run's synthetic manifests; never remove the temp root or business data.
    $resolvedTestDirectory = [IO.Path]::GetFullPath($testDirectory)
    $resolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedTestDirectory.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolvedTestDirectory) -notmatch '^tpv-recovery-http-[a-f0-9]{32}$') {
        throw 'Refusing cleanup outside the exact synthetic test directory'
    }
    Remove-Item -LiteralPath $resolvedTestDirectory -Recurse -Force
}
