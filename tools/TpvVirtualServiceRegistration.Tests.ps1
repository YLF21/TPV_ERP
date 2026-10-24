# Extract functions only; never execute installer entry points or real SCM writes.
foreach ($installer in @('Install-TpvBackendWindowsService.ps1', 'Install-TpvBackendTestWindowsService.ps1',
        'Install-TpvHttpsWindowsService.ps1')) {
    Describe "Virtual service registration: $installer" {
        $path = Join-Path $PSScriptRoot $installer
        $errors = $null
        $ast = [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$null, [ref]$errors)
        foreach ($name in @('Assert-TpvWinSw3', 'Assert-TpvWinSwStartupPolicy', 'Assert-TpvScmIdentity', 'Remove-TpvNewRegistration', 'Register-TpvNewService')) {
            $definition = $ast.FindAll({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst]}, $true) |
                Where-Object Name -eq $name
            . ([scriptblock]::Create($definition.Extent.Text))
        }
        function Assert-RegistrationThrows([scriptblock] $Action) {
            $threw=$false; try { & $Action | Out-Null } catch { $threw=$true }; $threw | Should Be $true
        }
        function Get-CimInstance { param($ClassName,$Filter) return $global:tpvRegistrationTestState.Service }
        function Invoke-CimMethod { param($InputObject,$MethodName,$Arguments) throw 'Unexpected real SCM mutation.' }
        function Set-Service { param($Name,$StartupType) throw 'Unexpected service startup mutation.' }
        function Invoke-TestWinSw {
            param($Operation,$NoElevate)
            $global:tpvRegistrationTestState.Calls += $Operation
            if ($Operation -ne 'install') { throw 'Registration must never start the service.' }
            $xml = [xml][IO.File]::ReadAllText($global:tpvRegistrationTestState.Xml)
            $global:tpvRegistrationTestState.BootstrapAccount = [string]$xml.service.serviceaccount.username
            $global:tpvRegistrationTestState.BootstrapMode = [string]$xml.service.startmode
            $global:tpvRegistrationTestState.Service = [pscustomobject]@{
                Name='TPVERPBackend'; PathName='"Invoke-TestWinSw"'; StartName='LocalSystem'; State='Stopped'; StartMode='Disabled'
            }
            $global:LASTEXITCODE = $global:tpvRegistrationTestState.InstallExit
        }
        BeforeEach {
            $xmlPath = Join-Path $TestDrive 'service.xml'
            $canonical = '<service><id>TPVERPBackend</id><startmode>Manual</startmode><autoRefresh>false</autoRefresh><serviceaccount><username>NT SERVICE\TPVERPBackend</username></serviceaccount></service>'
            [IO.File]::WriteAllText($xmlPath,$canonical)
            $global:tpvRegistrationTestState = @{
                Service=$null; Xml=$xmlPath; Calls=@(); InstallExit=0; ChangeExit=0; KeepWrongAccount=$false; DeleteExit=0
                BootstrapAccount=''; BootstrapMode=''
            }
            Mock Invoke-CimMethod {
                $s=$global:tpvRegistrationTestState
                switch($MethodName) {
                    'Change' {
                        if (-not $Arguments.ContainsKey('StartPassword') -or $null -ne $Arguments.StartPassword -or
                            $Arguments.StartMode -cne 'Manual') { throw 'Virtual accounts require an explicit NULL password.' }
                        if ($s.ChangeExit -eq 0 -and -not $s.KeepWrongAccount) {
                            $s.Service.StartName=$Arguments.StartName
                            $s.Service.StartMode=$Arguments.StartMode
                        }
                        return [pscustomobject]@{ReturnValue=$s.ChangeExit}
                    }
                    'ChangeStartMode' { $s.Service.StartMode=$Arguments.StartMode; return [pscustomobject]@{ReturnValue=0} }
                    'Delete' {
                        if ($s.DeleteExit -eq 0) { $s.Service=$null }
                        return [pscustomobject]@{ReturnValue=$s.DeleteExit}
                    }
                    default { throw "Unexpected mutation $MethodName" }
                }
            }
        }
        AfterEach { Remove-Variable tpvRegistrationTestState -Scope Global -ErrorAction SilentlyContinue }

        It 'parses and rejects v2 PE metadata without executing the wrapper' {
            @($errors).Count | Should Be 0
            Mock Get-Item { [pscustomobject]@{VersionInfo=[pscustomobject]@{FileMajorPart=2}} }
            Assert-RegistrationThrows { Assert-TpvWinSw3 'approved.exe' }
            $global:tpvRegistrationTestState.Calls.Count | Should Be 0
            Mock Get-Item { [pscustomobject]@{VersionInfo=[pscustomobject]@{FileMajorPart=3}} }
            Assert-TpvWinSw3 'approved.exe'
        }
        It 'creates disabled then changes identity and leaves canonical XML and a stopped manual service' {
            Register-TpvNewService 'Invoke-TestWinSw' $xmlPath 'TPVERPBackend' 'NT SERVICE\TPVERPBackend'
            $s=$global:tpvRegistrationTestState
            $s.BootstrapAccount | Should Be 'LocalSystem'
            $s.BootstrapMode | Should Be 'Disabled'
            $s.Service.StartName | Should Be 'NT SERVICE\TPVERPBackend'
            $s.Service.StartMode | Should Be 'Manual'
            $s.Service.State | Should Be 'Stopped'
            ($s.Calls -join ',') | Should Be 'install'
            [IO.File]::ReadAllText($xmlPath) | Should Be $canonical
            Assert-MockCalled Invoke-CimMethod -Times 1 -Exactly -Scope It -ParameterFilter {
                $MethodName -eq 'Change' -and $Arguments.StartName -eq 'NT SERVICE\TPVERPBackend' -and
                    $Arguments.ContainsKey('StartPassword') -and $null -eq $Arguments.StartPassword
            }
        }
        It 'rolls back a failed account change without starting LocalSystem' {
            $global:tpvRegistrationTestState.ChangeExit=22
            Assert-RegistrationThrows { Register-TpvNewService 'Invoke-TestWinSw' $xmlPath 'TPVERPBackend' 'NT SERVICE\TPVERPBackend' }
            $global:tpvRegistrationTestState.Service | Should BeNullOrEmpty
            ($global:tpvRegistrationTestState.Calls -join ',') | Should Be 'install'
            [IO.File]::ReadAllText($xmlPath) | Should Be $canonical
            Assert-MockCalled Invoke-CimMethod -Times 1 -Exactly -Scope It -ParameterFilter { $MethodName -eq 'Delete' }
        }
        It 'rejects an apparently successful Change whose actual SCM identity remains wrong' {
            $global:tpvRegistrationTestState.KeepWrongAccount=$true
            Assert-RegistrationThrows { Register-TpvNewService 'Invoke-TestWinSw' $xmlPath 'TPVERPBackend' 'NT SERVICE\TPVERPBackend' }
            $global:tpvRegistrationTestState.Service | Should BeNullOrEmpty
            ($global:tpvRegistrationTestState.Calls -join ',') | Should Be 'install'
        }
        It 'restores canonical XML and removes a partial WinSW installation' {
            $global:tpvRegistrationTestState.InstallExit=1
            Assert-RegistrationThrows { Register-TpvNewService 'Invoke-TestWinSw' $xmlPath 'TPVERPBackend' 'NT SERVICE\TPVERPBackend' }
            [IO.File]::ReadAllText($xmlPath) | Should Be $canonical
            $global:tpvRegistrationTestState.Service | Should BeNullOrEmpty
            Assert-MockCalled Invoke-CimMethod -Times 0 -Exactly -Scope It -ParameterFilter { $MethodName -eq 'Change' }
        }
        It 'leaves a failed registration disabled when SCM cannot delete it' {
            $global:tpvRegistrationTestState.ChangeExit=22
            $global:tpvRegistrationTestState.DeleteExit=5
            Assert-RegistrationThrows { Register-TpvNewService 'Invoke-TestWinSw' $xmlPath 'TPVERPBackend' 'NT SERVICE\TPVERPBackend' }
            $global:tpvRegistrationTestState.Service.StartMode | Should Be 'Disabled'
            $global:tpvRegistrationTestState.Service.State | Should Be 'Stopped'
        }
        It 'rejects implicit, enabled or duplicate auto refresh before creating a service' {
            foreach ($unsafe in @('', '<autoRefresh>true</autoRefresh>',
                    '<autoRefresh>false</autoRefresh><autoRefresh>false</autoRefresh>')) {
                [IO.File]::WriteAllText($xmlPath, $canonical.Replace('<autoRefresh>false</autoRefresh>', $unsafe))
                Assert-RegistrationThrows { Register-TpvNewService 'Invoke-TestWinSw' $xmlPath 'TPVERPBackend' 'NT SERVICE\TPVERPBackend' }
            }
            $global:tpvRegistrationTestState.Calls.Count | Should Be 0
            Assert-MockCalled Invoke-CimMethod -Times 0 -Exactly -Scope It
        }
        It 'starts an approved virtual service without changing its identity or security descriptor' {
            $global:tpvRegistrationTestState.Service=[pscustomobject]@{
                PathName='Invoke-TestWinSw'; StartName='NT SERVICE\TPVERPBackend'; State='Stopped'; StartMode='Manual'
            }
            Mock Set-Service {}
            Mock Invoke-TestWinSw { $global:LASTEXITCODE=0 }
            $Phase='Start';$ServiceName='TPVERPBackend';$serviceExe='Invoke-TestWinSw';$exe=$serviceExe
            $expectedStartName='NT SERVICE\TPVERPBackend';$service=$global:tpvRegistrationTestState.Service
            $installedXml=[xml]$canonical
            $startBlock=$ast.FindAll({param($n)
                $n -is [System.Management.Automation.Language.IfStatementAst] -and
                $n.Clauses[0].Item1.Extent.Text -eq '$Phase -eq ''Start''' -and
                $n.Extent.Text -match 'Habilitar inicio automatico'
            },$true) | Select-Object -First 1
            $startAction=[scriptblock]::Create('[CmdletBinding(SupportsShouldProcess=$true)]param()'+[Environment]::NewLine+$startBlock.Extent.Text)
            & $startAction -Confirm:$false
            Assert-MockCalled Set-Service -Times 1 -Exactly -Scope It -ParameterFilter {$StartupType -eq 'Automatic'}
            Assert-MockCalled Set-Service -Times 0 -Exactly -Scope It -ParameterFilter {$StartupType -eq 'Manual'}
            Assert-MockCalled Invoke-TestWinSw -Times 1 -Exactly -Scope It -ParameterFilter {$Operation -eq 'start'}
            Assert-MockCalled Invoke-CimMethod -Times 0 -Exactly -Scope It
        }
        It 'refuses automatic startup and wrapper execution when auto refresh is enabled' {
            $global:tpvRegistrationTestState.Service=[pscustomobject]@{
                PathName='Invoke-TestWinSw'; StartName='NT SERVICE\TPVERPBackend'; State='Stopped'; StartMode='Manual'
            }
            Mock Set-Service {}
            $Phase='Start';$ServiceName='TPVERPBackend';$serviceExe='Invoke-TestWinSw';$exe=$serviceExe
            $expectedStartName='NT SERVICE\TPVERPBackend';$service=$global:tpvRegistrationTestState.Service
            $installedXml=[xml]$canonical.Replace('<autoRefresh>false</autoRefresh>', '<autoRefresh>true</autoRefresh>')
            $startBlock=$ast.FindAll({param($n)
                $n -is [System.Management.Automation.Language.IfStatementAst] -and
                $n.Clauses[0].Item1.Extent.Text -eq '$Phase -eq ''Start''' -and
                $n.Extent.Text -match 'Habilitar inicio automatico'
            },$true) | Select-Object -First 1
            $startAction=[scriptblock]::Create('[CmdletBinding(SupportsShouldProcess=$true)]param()'+[Environment]::NewLine+$startBlock.Extent.Text)
            Assert-RegistrationThrows { & $startAction -Confirm:$false }
            Assert-MockCalled Set-Service -Times 0 -Exactly -Scope It -ParameterFilter {$StartupType -eq 'Automatic'}
            $global:tpvRegistrationTestState.Calls.Count | Should Be 0
        }
        It 'does not mutate a preexisting service' {
            $global:tpvRegistrationTestState.Service=[pscustomobject]@{PathName='other.exe'}
            Assert-RegistrationThrows { Register-TpvNewService 'Invoke-TestWinSw' $xmlPath 'TPVERPBackend' 'NT SERVICE\TPVERPBackend' }
            Assert-MockCalled Invoke-CimMethod -Times 0 -Exactly -Scope It
            $global:tpvRegistrationTestState.Calls.Count | Should Be 0
        }
        It 'does not enable or start a service whose SCM account is wrong' {
            $global:tpvRegistrationTestState.Service=[pscustomobject]@{
                PathName='Invoke-TestWinSw'; StartName='LocalSystem'; State='Stopped'; StartMode='Manual'
            }
            Mock Set-Service {}
            $installedXml=[xml]$canonical
            $Phase='Start';$ServiceName='TPVERPBackend';$serviceExe='Invoke-TestWinSw';$exe=$serviceExe
            $expectedStartName='NT SERVICE\TPVERPBackend';$service=$global:tpvRegistrationTestState.Service
            $startBlock=$ast.FindAll({param($n)
                $n -is [System.Management.Automation.Language.IfStatementAst] -and
                $n.Clauses[0].Item1.Extent.Text -eq '$Phase -eq ''Start''' -and
                $n.Extent.Text -match 'Habilitar inicio automatico'
            },$true) | Select-Object -First 1
            $startBlock | Should Not BeNullOrEmpty
            $startAction=[scriptblock]::Create('[CmdletBinding(SupportsShouldProcess=$true)]param()'+[Environment]::NewLine+$startBlock.Extent.Text)
            Assert-RegistrationThrows { & $startAction -Confirm:$false }
            Assert-MockCalled Set-Service -Times 0 -Exactly -Scope It -ParameterFilter {$StartupType -eq 'Automatic'}
            $global:tpvRegistrationTestState.Calls.Count | Should Be 0
        }
    }
}
