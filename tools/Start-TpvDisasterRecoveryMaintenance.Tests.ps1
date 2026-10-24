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
