$ErrorActionPreference = 'Stop'

function Remove-ContractBundle([string] $Directory) {
    $resolved = (Resolve-Path -LiteralPath $Directory).Path
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
    if (-not [string]::Equals([IO.Path]::GetDirectoryName($resolved), $tempRoot,
            [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($resolved) -notmatch '^tpv-backend-contract-[0-9a-f]{32}$') {
        throw 'El directorio de pruebas no pertenece al espacio temporal autorizado.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

function New-ContractBundle {
    param(
        [string] $ReleaseId = 'tpv-erp-4.2.0',
        [switch] $WithTransfer,
        [switch] $WithRecovery,
        [string] $Version = '4.2.0',
        [string] $SchemaVersion = 'V234',
        [long] $ReleaseSequence = 1,
        [long] $BuildSequence = 1
    )
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $directory = Join-Path ([IO.Path]::GetTempPath()) ('tpv-backend-contract-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $directory | Out-Null
    $jar = Join-Path $directory "tpv-erp-backend-$Version.jar"
    $pdf = [Text.Encoding]::ASCII.GetBytes("%PDF-1.7`ncontract`n")
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $declarationHash = ([BitConverter]::ToString($sha.ComputeHash($pdf))).Replace('-', '').ToUpperInvariant() }
    finally { $sha.Dispose() }
    $commit = 'abcdef1'
    $canonical = "release.id=$ReleaseId`n" +
        "system.version=$Version`ncapability=VERIFACTU_ONLY`n" +
        "schema.version=$SchemaVersion`nrelease.sequence=$ReleaseSequence`nbuild.sequence=$BuildSequence`n" +
        "commit.hash=$commit`ndeclaration.hash=$declarationHash`n"
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $manifestHash = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical)))).Replace('-', '').ToUpperInvariant() }
    finally { $sha.Dispose() }
    $manifest = @"
release.id=$ReleaseId
system.version=$Version
capability=VERIFACTU_ONLY
schema.version=$SchemaVersion
release.sequence=$ReleaseSequence
build.sequence=$BuildSequence
commit.hash=$commit
declaration.hash=$declarationHash
manifest.hash=$manifestHash
"@
    $archive = [IO.Compression.ZipFile]::Open($jar, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entryName in @('BOOT-INF/classes/', 'BOOT-INF/lib/', 'BOOT-INF/lib/runtime.jar',
                'org/springframework/boot/loader/launch/JarLauncher.class',
                'org/springframework/boot/loader/launch/PropertiesLauncher.class',
                'BOOT-INF/classes/com/tpverp/backend/backup/application/OfflineRestoreCli.class')) {
            $entry = $archive.CreateEntry($entryName)
            $stream = $entry.Open()
            try { if ($entryName -notmatch '/$') { $stream.WriteByte(0) } }
            finally { $stream.Dispose() }
        }
        if ($WithTransfer) {
            $entry = $archive.CreateEntry('BOOT-INF/classes/com/tpverp/backend/servertransfer/OfflineServerTransferCli.class')
            $stream = $entry.Open()
            try { $stream.WriteByte(0) } finally { $stream.Dispose() }
        }
        if ($WithRecovery) {
            $entry = $archive.CreateEntry('BOOT-INF/classes/com/tpverp/backend/disasterrecovery/OfflineDisasterRecoveryCli.class')
            $stream = $entry.Open()
            try { $stream.WriteByte(0) } finally { $stream.Dispose() }
        }
        $entry = $archive.CreateEntry('META-INF/tpv-erp-release.properties')
        $stream = $entry.Open()
        try { $bytes = [Text.Encoding]::UTF8.GetBytes($manifest); $stream.Write($bytes, 0, $bytes.Length) }
        finally { $stream.Dispose() }
        $entry = $archive.CreateEntry("BOOT-INF/classes/META-INF/fiscal/declaracion-responsable-$Version.pdf")
        $stream = $entry.Open()
        try { $stream.Write($pdf, 0, $pdf.Length) }
        finally { $stream.Dispose() }
    }
    finally { $archive.Dispose() }
    (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant() |
        Set-Content -LiteralPath "$jar.sha256" -NoNewline
    return $directory
}

Describe 'Despliegue productivo backend Windows' {
    $scriptPaths = @(
        (Join-Path $PSScriptRoot 'Test-TpvBackendProductionBundle.ps1'),
        (Join-Path $PSScriptRoot 'Prepare-TpvBackendProductionBundle.ps1'),
        (Join-Path $PSScriptRoot 'Install-TpvBackendWindowsService.ps1')
    )

    It 'mantiene sintaxis PowerShell valida' {
        foreach ($path in $scriptPaths) {
            $tokens = $null
            $errors = $null
            [System.Management.Automation.Language.Parser]::ParseFile(
                $path, [ref]$tokens, [ref]$errors) | Out-Null
            $errors | Should BeNullOrEmpty
        }
    }

    It 'no distribuye traslado sin herramientas verificadas ni acepta un helper alterado' {
        $directory = New-ContractBundle -WithTransfer
        try {
            $verifier = Join-Path $PSScriptRoot 'Test-TpvBackendProductionBundle.ps1'
            $rejected = $false
            try { & $verifier -BundleDirectory $directory -AsObject | Out-Null }
            catch { $rejected = $true }
            $rejected | Should Be $true
            $tools = Join-Path $directory 'tools'
            New-Item -ItemType Directory -Path $tools | Out-Null
            $hashes = [ordered]@{}
            foreach ($name in @('Invoke-TpvServerTransfer.ps1','Start-TpvServerTransferMaintenance.ps1',
                    'Test-TpvBackendProductionBundle.ps1','Invoke-TpvBackupRestoreOffline.ps1','Test-TpvBackupRestoreOfflinePreflight.ps1')) {
                $target = Join-Path $tools $name
                Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $target
                $hashes[$name] = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
            }
            [IO.File]::WriteAllText((Join-Path $directory 'maintenance-tools.sha256.json'),
                ($hashes | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
            $result = & $verifier -BundleDirectory $directory -AsObject
            $result.Status | Should Be 'PROMOTABLE'
            Add-Content -LiteralPath (Join-Path $tools 'Start-TpvServerTransferMaintenance.ps1') -Value '# changed'
            $rejected = $false
            try { & $verifier -BundleDirectory $directory -AsObject | Out-Null }
            catch { $rejected = $true }
            $rejected | Should Be $true
        }
        finally { Remove-ContractBundle $directory }
    }

    It 'exige y verifica los helpers de recuperacion solo cuando la release los incorpora' {
        $directory = New-ContractBundle -WithTransfer -WithRecovery
        try {
            $verifier = Join-Path $PSScriptRoot 'Test-TpvBackendProductionBundle.ps1'
            $tools = Join-Path $directory 'tools'
            New-Item -ItemType Directory -Path $tools | Out-Null
            $hashes = [ordered]@{}
            foreach ($name in @('Invoke-TpvServerTransfer.ps1','Start-TpvServerTransferMaintenance.ps1',
                    'Test-TpvBackendProductionBundle.ps1','Invoke-TpvBackupRestoreOffline.ps1','Test-TpvBackupRestoreOfflinePreflight.ps1')) {
                $target = Join-Path $tools $name
                Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $target
                $hashes[$name] = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
            }
            $manifestPath = Join-Path $directory 'maintenance-tools.sha256.json'
            [IO.File]::WriteAllText($manifestPath, ($hashes | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
            $rejected = $false
            try { & $verifier -BundleDirectory $directory -AsObject | Out-Null } catch { $rejected = $true }
            $rejected | Should Be $true
            foreach ($name in @('Invoke-TpvDisasterRecovery.ps1','Start-TpvDisasterRecoveryMaintenance.ps1')) {
                $target = Join-Path $tools $name
                Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $target
                $hashes[$name] = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
            }
            [IO.File]::WriteAllText($manifestPath, ($hashes | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
            (& $verifier -BundleDirectory $directory -AsObject).Status | Should Be 'PROMOTABLE'
            Add-Content -LiteralPath (Join-Path $tools 'Start-TpvDisasterRecoveryMaintenance.ps1') -Value '# changed'
            $rejected = $false
            try { & $verifier -BundleDirectory $directory -AsObject | Out-Null } catch { $rejected = $true }
            $rejected | Should Be $true
        } finally { Remove-ContractBundle $directory }
    }

    It 'exige controles de release, hash, PDF y capacidad fiscal' {
        $text = Get-Content -LiteralPath $scriptPaths[0] -Raw
        $text | Should Match 'BOOT-INF/classes'
        $text | Should Match 'BOOT-INF/lib'
        $text | Should Match 'tpv-erp-release\.properties'
        $text | Should Match 'VERIFACTU_ONLY'
        $text | Should Match 'declaracion-responsable-'
        $text | Should Match 'manifest\.hash'
        $text | Should Match 'Get-FileHash'
        $text | Should Match 'release\.sequence'
        $text | Should Match 'build\.sequence'
    }

    It 'usa la version Maven como origen y la version publica como nombre del bundle' {
        $prepareText = Get-Content -LiteralPath $scriptPaths[1] -Raw
        $prepareText | Should Match 'tpv\.release\.id=\$ExpectedReleaseId'
        $prepareText | Should Match 'tpv\.release\.version=\$ExpectedVersion'
        $prepareText | Should Match 'target\\tpv-erp-backend-\$projectVersion\.jar'
        $prepareText | Should Match 'tpv-erp-backend-\$ExpectedVersion\.jar'
        $prepareText | Should Not Match 'ExpectedVersion debe coincidir con project.version'
        $prepareText | Should Not Match 'AllowDirtySource'
        $pom = [xml](Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\backend\pom.xml') -Raw)
        $defaults = & (Join-Path $PSScriptRoot 'Get-TpvBackendReleaseDefaults.ps1')
        [string]$pom.project.version | Should Be '0.1.0'
        $defaults.Version | Should Be '1.0.0'
        $defaults.ReleaseId | Should Be 'tpv-erp-1.0.0-v278'
        $defaults.SchemaVersion | Should Be V278
        $defaults.ReleaseSequence | Should Be 36
        $defaults.BuildSequence | Should Be 1
    }

    It 'publica un JAR Maven 0.1.0 como release 1.0.0 y bloquea el build sin PDF legal' {
        # The PDF bytes and JAR in this isolated fixture are synthetic contract data.
        $fixtureRoot = New-ContractBundle -ReleaseId 'tpv-erp-1.0.0-v278' `
            -Version '1.0.0' -SchemaVersion V278 -ReleaseSequence 36
        try {
            $backend = Join-Path $fixtureRoot 'backend'
            $target = Join-Path $backend 'target'
            $resource = Join-Path $backend 'src/main/resources/META-INF'
            $fixtureTools = Join-Path $fixtureRoot 'tools'
            New-Item -ItemType Directory -Path $target, $resource, $fixtureTools -Force | Out-Null
            Move-Item -LiteralPath (Join-Path $fixtureRoot 'tpv-erp-backend-1.0.0.jar') `
                -Destination (Join-Path $target 'tpv-erp-backend-0.1.0.jar')
            Move-Item -LiteralPath (Join-Path $fixtureRoot 'tpv-erp-backend-1.0.0.jar.sha256') `
                -Destination (Join-Path $target 'tpv-erp-backend-0.1.0.jar.sha256')
            @'
<project><version>0.1.0</version><profiles><profile><id>production-release</id><properties>
<tpv.release.id>tpv-erp-1.0.0-v278</tpv.release.id><tpv.release.version>1.0.0</tpv.release.version>
<tpv.release.sequence>36</tpv.release.sequence><tpv.release.build.sequence>1</tpv.release.build.sequence>
</properties></profile></profiles></project>
'@ | Set-Content -LiteralPath (Join-Path $backend 'pom.xml')
            'schema.version=V278' | Set-Content -LiteralPath (Join-Path $resource 'tpv-erp-release.properties')
            "backend/target/`nartifacts/" | Set-Content -LiteralPath (Join-Path $fixtureRoot '.gitignore')
            foreach ($name in @('Prepare-TpvBackendProductionBundle.ps1', 'Get-TpvBackendReleaseDefaults.ps1',
                    'Invoke-TpvServerTransfer.ps1', 'Start-TpvServerTransferMaintenance.ps1',
                    'Invoke-TpvDisasterRecovery.ps1', 'Start-TpvDisasterRecoveryMaintenance.ps1',
                    'Test-TpvBackendProductionBundle.ps1', 'Invoke-TpvBackupRestoreOffline.ps1',
                    'Test-TpvBackupRestoreOfflinePreflight.ps1',
                    'Resolve-TpvBackendInstalledBundleVerifier.ps1')) {
                Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $fixtureTools $name)
            }
            & git -C $fixtureRoot init -q
            & git -C $fixtureRoot -c core.autocrlf=false add .
            & git -C $fixtureRoot -c user.name=ContractTest -c user.email=contract@example.invalid commit -qm fixture
            $prepare = Join-Path $fixtureTools 'Prepare-TpvBackendProductionBundle.ps1'
            $pdfGateMessage = ''
            try { & $prepare -OutputDirectory 'artifacts/checked' | Out-Null }
            catch { $pdfGateMessage = $_.Exception.Message }
            $pdfGateMessage | Should Match 'Falta el PDF legal de produccion'
            & $prepare -OutputDirectory 'artifacts/checked' -NoBuild
            $outputJar = Join-Path $fixtureRoot 'artifacts/checked/tpv-erp-backend-1.0.0.jar'
            (Test-Path -LiteralPath $outputJar -PathType Leaf) | Should Be $true
            (& (Join-Path $fixtureTools 'Test-TpvBackendProductionBundle.ps1') `
                -BundleDirectory (Split-Path -Parent $outputJar) -ExpectedVersion '1.0.0' `
                -ExpectedReleaseId 'tpv-erp-1.0.0-v278' -ExpectedSchemaVersion V278 `
                -ExpectedReleaseSequence 36 -ExpectedBuildSequence 1 -AsObject).Status | Should Be 'PROMOTABLE'
        }
        finally { Remove-ContractBundle $fixtureRoot }
    }

    It 'verifica un releaseId alternativo conservando la version publica 4.2.0' {
        $releaseId = 'tpv-erp-hotfix-4.2.0'
        $directory = New-ContractBundle $releaseId
        try {
            $verifier = Join-Path $PSScriptRoot 'Test-TpvBackendProductionBundle.ps1'
            $result = & $verifier -BundleDirectory $directory -ExpectedReleaseId $releaseId `
                -ExpectedVersion '4.2.0' -ExpectedSchemaVersion V234 `
                -ExpectedReleaseSequence 1 -ExpectedBuildSequence 1 -AsObject
            $result.Status | Should Be 'PROMOTABLE'
            $result.ReleaseId | Should Be $releaseId
            $result.Version | Should Be '4.2.0'
        }
        finally { Remove-Item -LiteralPath $directory -Recurse -Force }
    }

    It 'rechaza un marcador TEST_ONLY incluso con PDF legal y manifiesto productivo' {
        $directory = New-ContractBundle
        try {
            $jar = (Get-ChildItem -LiteralPath $directory -Filter '*.jar' -File | Select-Object -First 1).FullName
            $archive = [IO.Compression.ZipFile]::Open($jar, [IO.Compression.ZipArchiveMode]::Update)
            try {
                $entry = $archive.CreateEntry('META-INF/tpv-test-bundle.properties')
                $stream = $entry.Open()
                try {
                    $bytes = [Text.Encoding]::UTF8.GetBytes('purpose=TEST_ONLY')
                    $stream.Write($bytes, 0, $bytes.Length)
                } finally { $stream.Dispose() }
            } finally { $archive.Dispose() }
            (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash |
                Set-Content -LiteralPath "$jar.sha256" -NoNewline
            $message = ''
            try { & (Join-Path $PSScriptRoot 'Test-TpvBackendProductionBundle.ps1') `
                -BundleDirectory $directory -AsObject | Out-Null }
            catch { $message = $_.Exception.Message }
            $message | Should Match 'marcador TEST_ONLY'
        } finally { Remove-ContractBundle $directory }
    }

    It 'verifica realmente el hash canonico y las secuencias' {
        $directory = New-ContractBundle
        try {
            $verifier = Join-Path $PSScriptRoot 'Test-TpvBackendProductionBundle.ps1'
            $result = & $verifier -BundleDirectory $directory -ExpectedSchemaVersion V234 `
                -ExpectedReleaseSequence 1 -ExpectedBuildSequence 1 -AsObject
            $result.Status | Should Be 'PROMOTABLE'
            $result.ReleaseSequence | Should Be 1
            $threw = $false
            try {
                & $verifier -BundleDirectory $directory -ExpectedSchemaVersion V234 `
                    -ExpectedReleaseSequence 2 -ExpectedBuildSequence 1 -AsObject | Out-Null
            } catch { $threw = $true }
            $threw | Should Be $true
        }
        finally { Remove-Item -LiteralPath $directory -Recurse -Force }
    }

    It 'lee la identidad embebida sin defaults historicos y mantiene overrides estrictos' {
        $directory = New-ContractBundle 'tpv-erp-next-release'
        try {
            $verifier = Join-Path $PSScriptRoot 'Test-TpvBackendProductionBundle.ps1'
            $result = & $verifier -BundleDirectory $directory -AsObject
            $result.ReleaseId | Should Be 'tpv-erp-next-release'
            $result.SchemaVersion | Should Be V234
            $threw = $false
            try { & $verifier -BundleDirectory $directory -ExpectedReleaseId 'otra-release' -AsObject | Out-Null }
            catch { $threw = $true }
            $threw | Should Be $true
        }
        finally { Remove-Item -LiteralPath $directory -Recurse -Force }
    }

    It 'obtiene schema y release del manifiesto y perfil Maven actuales' {
        $defaults = & (Join-Path $PSScriptRoot 'Get-TpvBackendReleaseDefaults.ps1')
        $manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\backend\src\main\resources\META-INF\tpv-erp-release.properties') -Raw
        $manifest | Should Match ([Regex]::Escape("schema.version=$($defaults.SchemaVersion)"))
        $defaults.Version | Should Be '1.0.0'
        $defaults.ReleaseId | Should Be 'tpv-erp-1.0.0-v278'
        $defaults.ReleaseSequence | Should Be 36
        $defaults.ReleaseSequence | Should BeGreaterThan 0
    }

    It 'exige launcher y CLI offline realmente incluidos en el bundle' {
        $directory = New-ContractBundle
        try {
            $jar = (Get-ChildItem -LiteralPath $directory -Filter '*.jar' -File)[0].FullName
            $archive = [IO.Compression.ZipFile]::Open($jar, [IO.Compression.ZipArchiveMode]::Update)
            try { $archive.GetEntry('BOOT-INF/classes/com/tpverp/backend/backup/application/OfflineRestoreCli.class').Delete() }
            finally { $archive.Dispose() }
            (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash | Set-Content -LiteralPath "$jar.sha256" -NoNewline
            $threw = $false
            try { & (Join-Path $PSScriptRoot 'Test-TpvBackendProductionBundle.ps1') -BundleDirectory $directory -AsObject | Out-Null }
            catch { $threw = $true }
            $threw | Should Be $true
        }
        finally { Remove-Item -LiteralPath $directory -Recurse -Force }
    }

    It 'no descarga ejecutables ni acepta secretos en el instalador' {
        $text = Get-Content -LiteralPath $scriptPaths[2] -Raw
        $text | Should Not Match '(?i)Invoke-WebRequest|Start-BitsTransfer|DownloadFile|password'
        $text | Should Match 'WinSwSha256'
        $text | Should Match 'ConfigurationFile'
        $text | Should Match 'Preflight'
        $text | Should Match 'ShouldProcess'
        $text | Should Match '<username>'
        $text | Should Match 'TPV_VERIFACTU_SERVICE_ACCOUNT'
        $text | Should Match 'spring\.profiles\.active=prod'
        $text | Should Match 'additional-location=\$escapedConfigurationUri'
        $text | Should Not Match 'additional-location=optional:file:'
        $text | Should Match 'ConvertTo-FileUri'
        $text | Should Match 'Assert-LoopbackAddress'
        $text | Should Match 'Assert-Java25'
        $text | Should Match 'Assert-SecretTreeReadable'
        $text | Should Match 'Assert-RestrictedExportAcl'
        $text | Should Match 'ExportDirectory'
        $text | Should Match 'StartName'
        $text | Should Match '\^\[A-Za-z0-9\]\{1,80\}\$'
        $text | Should Not Match '<domain>|<user>'
        $text | Should Match 'release inmutable'
        $text | Should Match 'releaseDirectoryName'
    }

    It 'conserva bundles previos para rollback' {
        $text = Get-Content -LiteralPath $scriptPaths[2] -Raw
        $text | Should Match 'releases'
        $text | Should Match 'rollback'
        $text | Should Match 'previousXml'
        $text | Should Match 'previousExe'
        $text | Should Match 'previousExePath'
    }

    It 'deriva la ACL de la cuenta efectiva del servicio' {
        $text = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\backend\windows\Provision-VerifactuSecretDirectory.ps1') -Raw
        $text | Should Match 'effectiveServiceIdentity'
        $text | Should Match 'service.StartName'
        $text | Should Match 'FiscalExport'
        $text | Should Match 'exports\\fiscal'
        $text | Should Not Match "NT SERVICE\\TPVERPBackend'"
    }

    It 'rechaza claves duplicadas del manifiesto en vez de sobrescribirlas' {
        $text = Get-Content -LiteralPath $scriptPaths[0] -Raw
        $text | Should Match 'ContainsKey\(\$propertyName\)'
        $text | Should Match 'clave duplicada'
    }

    It 'valida segmentos esperados, secuencias y limites antes de leer el ZIP' {
        $text = Get-Content -LiteralPath $scriptPaths[0] -Raw
        $text | Should Match 'Assert-SafeExpectedSegment'
        $text | Should Match 'ExpectedReleaseSequence -lt 0'
        $text | Should Match '64KB'
        $text | Should Match '25MB'
        $text | Should Match 'ReparsePoint'
        $prepareText = Get-Content -LiteralPath $scriptPaths[1] -Raw
        $prepareText | Should Match 'Assert-SafeExpectedSegment'
        $installText = Get-Content -LiteralPath $scriptPaths[2] -Raw
        $installText | Should Match 'Assert-SafeExpectedSegment'
        $installText | Should Match 'Assert-SafePlannedPath'
    }

    It 'rechaza entradas ZIP duplicadas aunque solo cambie el uso de mayusculas' {
        $directory = New-ContractBundle
        try {
            $jar = Get-ChildItem -LiteralPath $directory -Filter '*.jar' -File | Select-Object -First 1
            $archive = [IO.Compression.ZipFile]::Open($jar.FullName, [IO.Compression.ZipArchiveMode]::Update)
            try {
                $entry = $archive.CreateEntry('boot-inf/classes/')
                $stream = $entry.Open()
                $stream.Dispose()
            }
            finally { $archive.Dispose() }
            $verifier = Join-Path $PSScriptRoot 'Test-TpvBackendProductionBundle.ps1'
            $threw = $false
            try {
                & $verifier -BundleDirectory $directory -ExpectedSchemaVersion V234 `
                    -ExpectedReleaseSequence 1 -ExpectedBuildSequence 1 -AsObject | Out-Null
            } catch { $threw = $true }
            $threw | Should Be $true
        }
        finally { Remove-Item -LiteralPath $directory -Recurse -Force }
    }

    It 'no permite rutas de release con traversal ni secuencias negativas' {
        $directory = New-ContractBundle
        try {
            $verifier = Join-Path $PSScriptRoot 'Test-TpvBackendProductionBundle.ps1'
            $traversalThrew = $false
            try {
                & $verifier -BundleDirectory $directory -ExpectedReleaseId '..\escape' `
                -ExpectedSchemaVersion V234 -ExpectedReleaseSequence 1 -ExpectedBuildSequence 1 -AsObject | Out-Null
            } catch { $traversalThrew = $true }
            $traversalThrew | Should Be $true
            $negativeThrew = $false
            try {
                & $verifier -BundleDirectory $directory -ExpectedSchemaVersion V234 `
                    -ExpectedReleaseSequence -1 -ExpectedBuildSequence 1 -AsObject | Out-Null
            } catch { $negativeThrew = $true }
            $negativeThrew | Should Be $true
        }
        finally { Remove-Item -LiteralPath $directory -Recurse -Force }
    }
}
