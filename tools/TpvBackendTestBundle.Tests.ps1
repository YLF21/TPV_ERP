$ErrorActionPreference = 'Stop'

function New-TestOnlyContractBundle {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $directory = Join-Path ([IO.Path]::GetTempPath()) ('tpv-test-bundle-contract-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $directory | Out-Null
    $jar = Join-Path $directory 'tpv-erp-backend-0.1.0.jar'
    $commit = 'abcdef012345abcdef012345abcdef012345abcd'
    $releaseId = 'tpv-erp-test-only-0.1.0-v279-' + $commit.Substring(0, 12)
    $canonical = "release.id=$releaseId`nsystem.version=0.1.0`ncapability=DUAL`n" +
        "schema.version=V279`nrelease.sequence=36`nbuild.sequence=1`ncommit.hash=$commit`ndeclaration.hash=`n"
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $manifestHash = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical)))).Replace('-', '') }
    finally { $sha.Dispose() }
    $manifest = $canonical + "manifest.hash=$manifestHash`n"
    $entries = [ordered]@{
        'BOOT-INF/classes/' = ''
        'BOOT-INF/lib/' = ''
        'BOOT-INF/lib/runtime.jar' = 'test'
        'org/springframework/boot/loader/launch/JarLauncher.class' = 'test'
        'org/springframework/boot/loader/launch/PropertiesLauncher.class' = 'test'
        'BOOT-INF/classes/com/tpverp/backend/backup/application/OfflineRestoreCli.class' = 'test'
        'META-INF/tpv-erp-release.properties' = $manifest
        'META-INF/tpv-test-bundle.properties' = "purpose=TEST_ONLY`nruntime=SANDBOX`nversion=0.1.0`nproduction.allowed=false`n"
    }
    $zip = [IO.Compression.ZipFile]::Open($jar, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($pair in $entries.GetEnumerator()) {
            $entry = $zip.CreateEntry($pair.Key)
            $stream = $entry.Open()
            try {
                $bytes = [Text.Encoding]::UTF8.GetBytes([string]$pair.Value)
                if ($bytes.Length) { $stream.Write($bytes, 0, $bytes.Length) }
            } finally { $stream.Dispose() }
        }
    } finally { $zip.Dispose() }
    (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash | Set-Content -LiteralPath "$jar.sha256" -NoNewline
    return $directory
}

Describe 'Actualizacion oficial TEST_ONLY de un servicio existente' {
    BeforeAll {
        $installerPath = Join-Path $PSScriptRoot 'Install-TpvBackendTestWindowsService.ps1'
        $tokens = $null
        $errors = $null
        $ast = [Management.Automation.Language.Parser]::ParseFile($installerPath, [ref]$tokens, [ref]$errors)
        if (@($errors).Count -ne 0) { throw 'El instalador TEST_ONLY no compila' }
        $functionAst = $ast.Find({ param($node)
            $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -ceq 'Assert-TpvTestOnlyUpdateTransition' }, $true)
        if ($null -eq $functionAst) { throw 'Falta la guarda de transicion TEST_ONLY' }
        . ([scriptblock]::Create($functionAst.Extent.Text))
    }

    It 'acepta solo una release nueva verificada sin descenso de contrato' {
        $old = [pscustomobject]@{Status='TEST_ONLY_VERIFIED';ReleaseId='tpv-erp-test-only-0.1.0-v279-73e0d66b47dd';
            ArtifactSha256=('A'*64);Version='0.1.0';SchemaVersion='V279';ReleaseSequence=36;BuildSequence=1}
        $new = [pscustomobject]@{Status='TEST_ONLY_VERIFIED';ReleaseId='tpv-erp-test-only-0.1.0-v279-abcdef012345';
            ArtifactSha256=('B'*64);Version='0.1.0';SchemaVersion='V279';ReleaseSequence=36;BuildSequence=1}
        { Assert-TpvTestOnlyUpdateTransition $old $new } | Should Not Throw
        $new.Status = 'PRODUCTION_VERIFIED'
        { Assert-TpvTestOnlyUpdateTransition $old $new } | Should Throw 'TEST_ONLY'
    }

    It 'rechaza reetiquetar una release inmutable o repetir su instalacion' {
        $old = [pscustomobject]@{Status='TEST_ONLY_VERIFIED';ReleaseId='tpv-erp-test-only-0.1.0-v279-73e0d66b47dd';
            ArtifactSha256=('A'*64);Version='0.1.0';SchemaVersion='V279';ReleaseSequence=36;BuildSequence=1}
        $new = $old.PSObject.Copy()
        $new.ArtifactSha256 = 'B'*64
        { Assert-TpvTestOnlyUpdateTransition $old $new } | Should Throw 'otro hash'
        $new.ArtifactSha256 = $old.ArtifactSha256
        { Assert-TpvTestOnlyUpdateTransition $old $new } | Should Throw 'release nueva'
    }

    It 'rechaza downgrade de version, esquema o secuencia' {
        $old = [pscustomobject]@{Status='TEST_ONLY_VERIFIED';ReleaseId='tpv-erp-test-only-0.1.0-v279-73e0d66b47dd';
            ArtifactSha256=('A'*64);Version='0.1.0';SchemaVersion='V279';ReleaseSequence=36;BuildSequence=1}
        $new = [pscustomobject]@{Status='TEST_ONLY_VERIFIED';ReleaseId='tpv-erp-test-only-0.1.0-v279-abcdef012345';
            ArtifactSha256=('B'*64);Version='0.0.9';SchemaVersion='V279';ReleaseSequence=36;BuildSequence=1}
        { Assert-TpvTestOnlyUpdateTransition $old $new } | Should Throw 'downgrade'
        $new.Version='0.1.0';$new.SchemaVersion='V278'
        { Assert-TpvTestOnlyUpdateTransition $old $new } | Should Throw 'downgrade'
        $new.SchemaVersion='V279';$new.ReleaseSequence=35
        { Assert-TpvTestOnlyUpdateTransition $old $new } | Should Throw 'downgrade'
    }

    It 'mantiene Register cerrado y Update detenido antes de mutar' {
        $source = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Install-TpvBackendTestWindowsService.ps1') -Raw
        $source | Should Match "ValidateSet\('Register', 'Update', 'Start'\)"
        $source | Should Match 'if \(\$Phase -eq ''Register'' -and \$null -ne \$service\)'
        $source | Should Match 'Assert-TpvScmIdentity \$ServiceName \$serviceExe \$expectedStartName -Stopped'
        $source | Should Match 'Resolve-TpvBackendInstalledBundleVerifier.ps1'
        $source | Should Not Match '& \$serviceExe stop'
    }
}

function Remove-TestOnlyContractBundle([string] $Directory) {
    $full = (Resolve-Path -LiteralPath $Directory).Path
    if ([IO.Path]::GetDirectoryName($full) -cne [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/') -or
        [IO.Path]::GetFileName($full) -notmatch '^tpv-test-bundle-contract-[0-9a-f]{32}$') {
        throw 'Directorio temporal de prueba no reconocido.'
    }
    Remove-Item -LiteralPath $full -Recurse -Force
}

Describe 'Frontera del paquete TEST_ONLY' {
    It 'verifica identidad TEST_ONLY y nunca la promociona' {
        $directory = New-TestOnlyContractBundle
        try {
            $result = & (Join-Path $PSScriptRoot 'Test-TpvBackendTestBundle.ps1') -BundleDirectory $directory -AsObject
            $result.Status | Should Be 'TEST_ONLY_VERIFIED'
            $result.Capability | Should Be 'DUAL'
            { & (Join-Path $PSScriptRoot 'Test-TpvBackendProductionBundle.ps1') -BundleDirectory $directory -AsObject } |
                Should Throw 'TEST_ONLY'
        } finally { Remove-TestOnlyContractBundle $directory }
    }

    It 'rechaza el sidecar alterado y la ausencia del marcador' {
        $directory = New-TestOnlyContractBundle
        try {
            $jar = Join-Path $directory 'tpv-erp-backend-0.1.0.jar'
            ('0' * 64) | Set-Content -LiteralPath "$jar.sha256" -NoNewline
            $failed = $false
            try { & (Join-Path $PSScriptRoot 'Test-TpvBackendTestBundle.ps1') -BundleDirectory $directory -AsObject | Out-Null }
            catch { $failed = $true }
            $failed | Should Be $true
        } finally { Remove-TestOnlyContractBundle $directory }
        $directory = New-TestOnlyContractBundle
        try {
            $jar = Join-Path $directory 'tpv-erp-backend-0.1.0.jar'
            $zip = [IO.Compression.ZipFile]::Open($jar, [IO.Compression.ZipArchiveMode]::Update)
            try { $zip.GetEntry('META-INF/tpv-test-bundle.properties').Delete() }
            finally { $zip.Dispose() }
            (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash | Set-Content -LiteralPath "$jar.sha256" -NoNewline
            $failed = $false
            try { & (Join-Path $PSScriptRoot 'Test-TpvBackendTestBundle.ps1') -BundleDirectory $directory -AsObject | Out-Null }
            catch { $failed = $true }
            $failed | Should Be $true
        } finally { Remove-TestOnlyContractBundle $directory }
    }

    It 'rechaza el instalador en el host y los CLI sin servicio instalado' {
        $directory = New-TestOnlyContractBundle
        try {
            $failed = $false
            try { & (Join-Path $PSScriptRoot 'Resolve-TpvBackendInstalledBundleVerifier.ps1') `
                -BundleDirectory $directory -InstalledServiceXml (Join-Path $directory 'missing.xml') | Out-Null }
            catch { $failed = $true }
            $failed | Should Be $true
            $config = Join-Path $directory 'application-lab.properties'
            'tpv.verifactu.runtime-class=REAL' | Set-Content -LiteralPath $config
            $failed = $false
            try { & (Join-Path $PSScriptRoot 'Assert-TpvTestLabContext.ps1') -ConfigurationFile $config | Out-Null }
            catch { $failed = $true }
            $failed | Should Be $true
        } finally { Remove-TestOnlyContractBundle $directory }
    }

    It 'rechaza una configuracion REAL aun con hardware VirtualBox' {
        Mock Get-CimInstance { [pscustomobject]@{ Model = 'VirtualBox'; Manufacturer = 'innotek GmbH' } }
        $directory = New-TestOnlyContractBundle
        try {
            $config = Join-Path $directory 'application-lab.properties'
            $operational = ($directory -replace '\\', '/')
            @"
tpv.test-bundle.lab-context=VIRTUALBOX_ONLY
spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/lab_tpv_a
tpv.license.saas-url=https://10.77.23.20:58443
tpv.sync.central-url=https://10.77.23.20:58443
tpv.sync.worker-enabled=false
tpv.installation.key-directory=$operational/keys
tpv.backup.default-directory=$operational/Backups
tpv.product-images.directory=$operational/images
tpv.document-templates.directory=$operational/templates
tpv.verifactu.secret-directory=$operational/secrets
tpv.verifactu.export-job-directory=$operational/exports-fiscal
tpv.backup.restore-journal-path=$operational/restore/journal.properties
tpv.verifactu.runtime-class=SANDBOX
tpv.verifactu.endpoint-environment=TEST
tpv.verifactu.endpoint-mode=TEST
tpv.verifactu.transport-mode=SIMULATED
tpv.verifactu.dev-sandbox.enabled=true
tpv.verifactu.production-enabled=false
tpv.verifactu.worker-enabled=false
tpv.verifactu.aeat-test-network-enabled=false
"@ | Set-Content -LiteralPath $config
            $accepted = & (Join-Path $PSScriptRoot 'Assert-TpvTestLabContext.ps1') -ConfigurationFile $config
            $accepted.VirtualMachine | Should Be 'VirtualBox'
            (Get-Content -LiteralPath $config -Raw).Replace('runtime-class=SANDBOX', 'runtime-class=REAL') |
                Set-Content -LiteralPath $config
            $failed = $false
            try { & (Join-Path $PSScriptRoot 'Assert-TpvTestLabContext.ps1') -ConfigurationFile $config | Out-Null }
            catch { $failed = $true }
            $failed | Should Be $true
        } finally { Remove-TestOnlyContractBundle $directory }
    }

    It 'rechaza un equipo Windows que no sea VirtualBox' {
        Mock Get-CimInstance { [pscustomobject]@{ Model = 'Physical PC'; Manufacturer = 'Example' } }
        $directory = New-TestOnlyContractBundle
        try {
            $config = Join-Path $directory 'application-lab.properties'
            'tpv.test-bundle.lab-context=VIRTUALBOX_ONLY' | Set-Content -LiteralPath $config
            $message = ''
            try { & (Join-Path $PSScriptRoot 'Assert-TpvTestLabContext.ps1') -ConfigurationFile $config | Out-Null }
            catch { $message = $_.Exception.Message }
            $message | Should Match 'VirtualBox'
        } finally { Remove-TestOnlyContractBundle $directory }
    }

    It 'acepta la URI entre comillas que genera WinSW y rechaza un XML sin SCM' {
        Mock Get-CimInstance { if ($ClassName -eq 'Win32_ComputerSystem') {
                [pscustomobject]@{ Model = 'VirtualBox'; Manufacturer = 'innotek GmbH' }
            } else { $null } }
        $directory = New-TestOnlyContractBundle
        try {
            $config = Join-Path $directory 'application-lab.properties'
            $operational = ($directory -replace '\\', '/')
            @"
tpv.test-bundle.lab-context=VIRTUALBOX_ONLY
spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/lab_tpv_a
tpv.license.saas-url=https://10.77.23.20:58443
tpv.sync.central-url=https://10.77.23.20:58443
tpv.sync.worker-enabled=false
tpv.installation.key-directory=$operational/keys
tpv.backup.default-directory=$operational/Backups
tpv.product-images.directory=$operational/images
tpv.document-templates.directory=$operational/templates
tpv.verifactu.secret-directory=$operational/secrets
tpv.verifactu.export-job-directory=$operational/exports-fiscal
tpv.backup.restore-journal-path=$operational/restore/journal.properties
tpv.verifactu.runtime-class=SANDBOX
tpv.verifactu.endpoint-environment=TEST
tpv.verifactu.endpoint-mode=TEST
tpv.verifactu.transport-mode=SIMULATED
tpv.verifactu.dev-sandbox.enabled=true
tpv.verifactu.production-enabled=false
tpv.verifactu.worker-enabled=false
tpv.verifactu.aeat-test-network-enabled=false
"@ | Set-Content -LiteralPath $config
            $uri = [Uri]::new($config).AbsoluteUri
            $jar = Join-Path $directory 'tpv-erp-backend-0.1.0.jar'
            $arguments = "--enable-native-access=ALL-UNNAMED -jar `"$jar`" --spring.profiles.active=lab-bundle --server.address=127.0.0.1 --server.port=8080 `"--spring.config.additional-location=$uri`""
            $xmlPath = Join-Path $directory 'TPVERPBackend.xml'
            @"
<service><id>TPVERPBackend</id><arguments>$([Security.SecurityElement]::Escape($arguments))</arguments><serviceaccount><username>NT SERVICE\TPVERPBackend</username></serviceaccount></service>
"@ | Set-Content -LiteralPath $xmlPath
            $result = & (Join-Path $PSScriptRoot 'Assert-TpvTestLabContext.ps1') -InstalledServiceXml $xmlPath
            $result.ConfigurationFile | Should Be $config
            $message = ''
            try { & (Join-Path $PSScriptRoot 'Resolve-TpvBackendInstalledBundleVerifier.ps1') `
                -BundleDirectory $directory -InstalledServiceXml $xmlPath | Out-Null }
            catch { $message = $_.Exception.Message }
            $message | Should Match 'Administrador de servicios'
        } finally { Remove-TestOnlyContractBundle $directory }
    }

    It 'toma el entorno CLI del fichero instalado y descarta valores heredados' {
        $directory = New-TestOnlyContractBundle
        $priorEnvironment = @{}
        foreach ($entry in Get-ChildItem Env:) { $priorEnvironment[$entry.Name] = $entry.Value }
        try {
            $env:ProgramData = $directory
            $httpsRoot = Join-Path $directory 'TPV ERP\HTTPS'
            New-Item -ItemType Directory -Path $httpsRoot -Force | Out-Null
            $httpsExe = Join-Path $httpsRoot 'TPVERPHTTPS.exe'
            [IO.File]::WriteAllBytes($httpsExe, [byte[]]@(1))
            '<service><id>TPVERPHTTPS</id><serviceaccount><username>NT SERVICE\TPVERPHTTPS</username></serviceaccount><env name="TPV_HTTPS_PUBLIC_URL" value="https://10.77.23.10:58443" /></service>' |
                Set-Content -LiteralPath (Join-Path $httpsRoot 'TPVERPHTTPS.xml')
            Mock Get-CimInstance { if ($ClassName -eq 'Win32_ComputerSystem') {
                    [pscustomobject]@{ Model = 'VirtualBox'; Manufacturer = 'innotek GmbH' }
                } else { [pscustomobject]@{ StartName = 'NT SERVICE\TPVERPHTTPS'; PathName = $httpsExe } } }
            $config = Join-Path $directory 'application-lab.properties'
            $operational = ($directory -replace '\\', '/')
            @"
tpv.test-bundle.lab-context=VIRTUALBOX_ONLY
spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/lab_tpv_a
spring.datasource.username=lab_user
spring.datasource.password=lab_secret
tpv.license.saas-url=https://10.77.23.20:58443
tpv.sync.central-url=https://10.77.23.20:58443
tpv.sync.worker-enabled=false
tpv.installation.key-directory=$operational/keys
tpv.backup.default-directory=$operational/Backups
tpv.product-images.directory=$operational/images
tpv.document-templates.directory=$operational/templates
tpv.verifactu.secret-directory=$operational/secrets
tpv.verifactu.export-job-directory=$operational/exports-fiscal
tpv.backup.restore-journal-path=$operational/restore/journal.properties
tpv.backup.pg-dump-command=$operational/pg_dump.exe
tpv.backup.pg-restore-command=$operational/pg_restore.exe
tpv.test-bundle.tls-reprovisioned=false
tpv.verifactu.runtime-class=SANDBOX
tpv.verifactu.endpoint-environment=TEST
tpv.verifactu.endpoint-mode=TEST
tpv.verifactu.transport-mode=SIMULATED
tpv.verifactu.dev-sandbox.enabled=true
tpv.verifactu.production-enabled=false
tpv.verifactu.worker-enabled=false
tpv.verifactu.aeat-test-network-enabled=false
"@ | Set-Content -LiteralPath $config
            $uri = [Uri]::new($config).AbsoluteUri
            $arguments = "-jar `"$(Join-Path $directory 'tpv-erp-backend-0.1.0.jar')`" --spring.profiles.active=lab-bundle --server.port=8080 `"--spring.config.additional-location=$uri`""
            $xmlPath = Join-Path $directory 'TPVERPBackend.xml'
            "<service><id>TPVERPBackend</id><arguments>$([Security.SecurityElement]::Escape($arguments))</arguments></service>" |
                Set-Content -LiteralPath $xmlPath
            $env:TPV_DB_URL = 'jdbc:postgresql://production.example/real'
            $env:PGHOSTADDR = '203.0.113.5'
            $lab = & (Join-Path $PSScriptRoot 'Assert-TpvTestLabContext.ps1') -InstalledServiceXml $xmlPath -ApplyCliEnvironment
            $lab.AclPaths.KeyRoot | Should Be (Join-Path $directory 'keys')
            $lab.AclPaths.FiscalSecretRoot | Should Be (Join-Path $directory 'secrets')
            $lab.AclPaths.FiscalExportRoot | Should Be (Join-Path $directory 'exports-fiscal')
            $lab.AclPaths.RestoreJournalRoot | Should Be (Join-Path $directory 'restore')
            $env:TPV_DB_URL | Should Be 'jdbc:postgresql://127.0.0.1:5432/lab_tpv_a'
            $env:PGPASSWORD | Should Be 'lab_secret'
            $env:TPV_PG_DUMP_COMMAND | Should Be "$operational/pg_dump.exe"
            $env:TPV_PG_RESTORE_COMMAND | Should Be "$operational/pg_restore.exe"
            (Test-Path Env:PGHOSTADDR) | Should Be $false
            $env:TPV_LICENSE_SAAS_URL | Should Be 'https://10.77.23.20:58443'
            $env:TPV_TRANSFER_PUBLIC_HOST | Should Be '10.77.23.10'
            $env:SPRING_PROFILES_ACTIVE | Should Be 'lab-bundle'
            $validConfig = Get-Content -LiteralPath $config -Raw
            $validConfig.Replace("tpv.backup.pg-restore-command=$operational/pg_restore.exe", '') |
                Set-Content -LiteralPath $config
            $failure = ''
            try { & (Join-Path $PSScriptRoot 'Assert-TpvTestLabContext.ps1') `
                -InstalledServiceXml $xmlPath -ApplyCliEnvironment | Out-Null }
            catch { $failure = $_.Exception.Message }
            $failure | Should Match 'tpv.backup.pg-restore-command'
            $validConfig.Replace("tpv.backup.pg-dump-command=$operational/pg_dump.exe",
                'tpv.backup.pg-dump-command=pg_dump.exe') | Set-Content -LiteralPath $config
            $failure = ''
            try { & (Join-Path $PSScriptRoot 'Assert-TpvTestLabContext.ps1') `
                -InstalledServiceXml $xmlPath -ApplyCliEnvironment | Out-Null }
            catch { $failure = $_.Exception.Message }
            $failure | Should Match 'tpv.backup.pg-dump-command'
        } finally {
            foreach ($entry in @(Get-ChildItem Env:)) {
                if (-not $priorEnvironment.ContainsKey($entry.Name)) {
                    [Environment]::SetEnvironmentVariable($entry.Name, $null, 'Process')
                }
            }
            foreach ($name in $priorEnvironment.Keys) {
                [Environment]::SetEnvironmentVariable($name, [string]$priorEnvironment[$name], 'Process')
            }
            Remove-TestOnlyContractBundle $directory
        }
    }

    It 'rechaza un SCM que señala otro ejecutable aunque exista XML de release' {
        $directory = New-TestOnlyContractBundle
        try {
            [IO.File]::WriteAllBytes((Join-Path $directory 'TPVERPBackend.exe'), [byte[]]@(1))
            $xmlPath = Join-Path $directory 'TPVERPBackend.xml'
            '<service><id>TPVERPBackend</id><serviceaccount><username>NT SERVICE\TPVERPBackend</username></serviceaccount></service>' |
                Set-Content -LiteralPath $xmlPath
            Mock Get-CimInstance { [pscustomobject]@{
                    PathName = 'C:\otro\TPVERPBackend.exe'; StartName = 'NT SERVICE\TPVERPBackend' } }
            Mock Get-Service { [pscustomobject]@{ Name = 'TPVERPBackend' } }
            $message = ''
            try { & (Join-Path $PSScriptRoot 'Resolve-TpvBackendInstalledBundleVerifier.ps1') `
                -BundleDirectory $directory -InstalledServiceXml $xmlPath | Out-Null }
            catch { $message = $_.Exception.Message }
            $message | Should Match 'no pertenece al servicio WinSW'
        } finally { Remove-TestOnlyContractBundle $directory }
    }

    It 'limita UsersRX a código y contenedores tras la ACL privada' {
        $aclPath = Join-Path $PSScriptRoot 'Set-TpvBackendTestWindowsAcl.ps1'
        $tokens = $null
        $errors = $null
        [Management.Automation.Language.Parser]::ParseFile($aclPath, [ref]$tokens, [ref]$errors) | Out-Null
        @($errors).Count | Should Be 0
        $source = Get-Content -LiteralPath $aclPath -Raw
        $source | Should Match 'Set-TpvBackendWindowsAcl\.ps1.*-Phase Apply'
        $source | Should Match "'S-1-5-32-545'"
        $source | Should Match 'InheritanceFlags\]::None'
        $source | Should Match '-KeyRoot \$dataRoots.KeyRoot'
        $source | Should Match '-BackupRoot \$dataRoots.BackupRoot'
        $source | Should Match '-RestoreJournalRoot \$dataRoots.RestoreJournalRoot'
        $source | Should Match '-FiscalSecretRoot \$dataRoots.FiscalSecretRoot'
        $source | Should Match '-FiscalExportRoot \$dataRoots.FiscalExportRoot'
        $source | Should Match 'Get-ChildItem -LiteralPath \$bundle -Force -Recurse'
        $source | Should Not Match 'Get-ChildItem -LiteralPath \$installRoot -Force -Recurse'
    }

    It 'exige que UsersRX sea explícito y nunca heredable al arrancar' {
        $installer = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Install-TpvBackendTestWindowsService.ps1') -Raw
        $installer | Should Match '\$rule.IsInherited'
        $installer | Should Match '\$rule.InheritanceFlags -ne \[Security.AccessControl.InheritanceFlags\]::None'
        $installer | Should Match '\$rule.PropagationFlags -ne \[Security.AccessControl.PropagationFlags\]::None'
        $installer | Should Match 'Assert-RestrictedTreeAcl \$aclPaths.KeyRoot'
    }
}
