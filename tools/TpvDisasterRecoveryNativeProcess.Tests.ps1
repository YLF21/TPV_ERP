# Extract only the invocation helper: never run recovery, the JVM application, or service operations.
Describe 'Disaster recovery native invocation on Windows PowerShell' {
    $scriptPath = Join-Path $PSScriptRoot 'Invoke-TpvDisasterRecovery.ps1'
    $parseErrors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$null, [ref]$parseErrors)
    $definition = $ast.Find({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Invoke-TpvRecoveryJava'
    }, $true)
    . ([scriptblock]::Create($definition.Extent.Text))
    $nativePowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'

    It 'parses and explicitly enables the native access required by JNA' {
        @($parseErrors).Count | Should Be 0
        [IO.File]::ReadAllText($scriptPath) | Should Match "'--enable-native-access=ALL-UNNAMED'"
    }

    It 'preserves redirected stderr warnings and accepts only the actual zero exit' {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Stop'
        $log = Join-Path $TestDrive 'warning-zero.log'
        $global:LASTEXITCODE = 42
        try {
            Invoke-TpvRecoveryJava -Executable $nativePowerShell -Arguments @('-NoProfile', '-NonInteractive',
                '-Command', "[Console]::Error.WriteLine('synthetic native warning'); [Console]::WriteLine('native-completed'); exit 0") *>> $log
            $ErrorActionPreference | Should Be 'Stop'
            [IO.File]::ReadAllText($log) | Should Match 'synthetic native warning'
            [IO.File]::ReadAllText($log) | Should Match 'native-completed'
        } finally { $ErrorActionPreference = $previous }
    }

    It 'rejects exit 42 even with a stale successful exit and preserves its diagnostic' {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Stop'
        $log = Join-Path $TestDrive 'failure-42.log'
        $global:LASTEXITCODE = 0
        $failure = $null
        try {
            try {
                Invoke-TpvRecoveryJava -Executable $nativePowerShell -Arguments @('-NoProfile', '-NonInteractive',
                    '-Command', "[Console]::Error.WriteLine('synthetic native failure'); exit 42") *>> $log
            } catch { $failure = $_ }
            $failure | Should Not BeNullOrEmpty
            $failure.Exception.Message | Should Match 'codigo 42'
            [IO.File]::ReadAllText($log) | Should Match 'synthetic native failure'
            $ErrorActionPreference | Should Be 'Stop'
        } finally { $ErrorActionPreference = $previous }
    }

    It 'does not adopt stale zero when the executable cannot start' {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Stop'
        $missing = Join-Path $TestDrive 'absent-java.exe'
        $global:LASTEXITCODE = 0
        $failure = $null
        try {
            try { Invoke-TpvRecoveryJava -Executable $missing -Arguments @('-version') *>> (Join-Path $TestDrive 'missing.log') }
            catch { $failure = $_ }
            $failure | Should Not BeNullOrEmpty
            $ErrorActionPreference | Should Be 'Stop'
        } finally { $ErrorActionPreference = $previous }
    }
}
