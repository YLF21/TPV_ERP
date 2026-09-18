Describe 'Verifactu isolated sandbox script contracts' {
    # Parse only: never dot-source or invoke the launcher, databases or HTTP APIs.
    $launcherPath = Join-Path $PSScriptRoot 'start-app-venta-isolated.ps1'
    $tokens = $null
    $parseErrors = $null
    $launcherAst = [System.Management.Automation.Language.Parser]::ParseFile(
        $launcherPath, [ref]$tokens, [ref]$parseErrors)
    $assignments = @($launcherAst.FindAll({ param($node)
        $node -is [System.Management.Automation.Language.AssignmentStatementAst]
    }, $true))
    $resolutions = @($assignments | Where-Object {
        $_.Left -is [System.Management.Automation.Language.VariableExpressionAst] -and
        $_.Left.VariablePath.UserPath -eq 'script:ResolvedEvidenceDirectory' -and
        $_.Right.Extent.Text -match 'IsNullOrWhiteSpace\(\$EvidenceDirectory\)'
    })

    It 'preserves the supplied EvidenceDirectory parameter without any assignment to it' {
        $parseErrors | Should BeNullOrEmpty
        @($launcherAst.ParamBlock.Parameters | Where-Object {
            $_.Name.VariablePath.UserPath -eq 'EvidenceDirectory'
        }).Count | Should Be 1
        @($assignments | Where-Object {
            $_.Left -is [System.Management.Automation.Language.VariableExpressionAst] -and
            ($_.Left.VariablePath.UserPath -split ':')[-1] -eq 'EvidenceDirectory'
        }).Count | Should Be 0
    }

    It 'resolves the explicit evidence path with spaces by executing only its assignment AST' {
        $resolutions.Count | Should Be 1
        $EvidenceDirectory = 'C:\fiscal proof fixture\run with spaces'
        $script:RepositoryRoot = 'C:\repository fixture'
        $script:ResolvedEvidenceDirectory = $null
        . ([scriptblock]::Create($resolutions[0].Extent.Text))
        $script:ResolvedEvidenceDirectory | Should Be ([IO.Path]::GetFullPath($EvidenceDirectory))
        $EvidenceDirectory | Should Be 'C:\fiscal proof fixture\run with spaces'
    }

    It 'resolves the default evidence directory without creating it' {
        $resolutions.Count | Should Be 1
        $script:RepositoryRoot = 'C:\repository fixture'
        foreach ($EvidenceDirectory in @($null, '', '   ')) {
            $script:ResolvedEvidenceDirectory = $null
            . ([scriptblock]::Create($resolutions[0].Extent.Text))
            $script:ResolvedEvidenceDirectory | Should Be 'C:\repository fixture\target\verifactu-dev-proof'
        }
    }

    It 'unlocks the fiscal group with the same session before requesting fiscal status' {
        $requests = @($launcherAst.FindAll({ param($node)
            $node -is [System.Management.Automation.Language.CommandAst] -and
            $node.GetCommandName() -eq 'Invoke-RestMethod'
        }, $true))
        $unlock = @($requests | Where-Object {
            $_.Extent.Text -match '/api/v1/auth/gestion-groups/FISCAL/unlock'
        })
        $status = @($requests | Where-Object {
            $_.Extent.Text -match '/api/v1/fiscal/status'
        })
        $unlock.Count | Should Be 1
        $status.Count | Should Be 1
        ($unlock[0].Extent.EndOffset -lt $status[0].Extent.StartOffset) | Should Be $true
        $unlock[0].Extent.Text | Should Match '-Method Post'
        $unlock[0].Extent.Text | Should Match '-Headers \$proofHeaders'
        $status[0].Extent.Text | Should Match '-Headers \$proofHeaders'
        $unlock[0].Extent.Text | Should Match 'password\s*=\s*"0000"'
    }
}
