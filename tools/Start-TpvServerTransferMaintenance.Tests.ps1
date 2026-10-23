Describe 'Installed server transfer maintenance entry point' {
    BeforeAll {
        $scriptPath = Join-Path $PSScriptRoot 'Start-TpvServerTransferMaintenance.ps1'
        $tokens = $null
        $parseErrors = $null
        $ast = [Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$parseErrors)
    }

    It 'parses without errors and accepts only an allowlisted action' {
        @($parseErrors).Count | Should Be 0
        $ast.ParamBlock.Parameters.Count | Should Be 1
        $ast.ParamBlock.Parameters[0].Name.VariablePath.UserPath | Should Be 'Action'
        $validateSet = $ast.ParamBlock.Parameters[0].Attributes |
            Where-Object { $_.TypeName.FullName -eq 'ValidateSet' }
        ($validateSet.PositionalArguments.Value -join ',') | Should Be 'request,receive,activate,status,cancel'
    }
}
