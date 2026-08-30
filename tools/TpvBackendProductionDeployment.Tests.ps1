$ErrorActionPreference = 'Stop'

function New-ContractBundle {
    param([string] $ReleaseId = 'tpv-erp-4.2.0')
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $directory = Join-Path ([IO.Path]::GetTempPath()) ('tpv-backend-contract-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $directory | Out-Null
    $jar = Join-Path $directory 'tpv-erp-backend-4.2.0.jar'
    $pdf = [Text.Encoding]::ASCII.GetBytes("%PDF-1.7`ncontract`n")
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $declarationHash = ([BitConverter]::ToString($sha.ComputeHash($pdf))).Replace('-', '').ToUpperInvariant() }
    finally { $sha.Dispose() }
    $commit = 'abcdef1'
    $canonical = "release.id=$ReleaseId`n" +
        "system.version=4.2.0`ncapability=VERIFACTU_ONLY`n" +
        "schema.version=V233`nrelease.sequence=1`nbuild.sequence=1`n" +
        "commit.hash=$commit`ndeclaration.hash=$declarationHash`n"
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $manifestHash = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonical)))).Replace('-', '').ToUpperInvariant() }
    finally { $sha.Dispose() }
    $manifest = @"
release.id=$ReleaseId
system.version=4.2.0
capability=VERIFACTU_ONLY
schema.version=V233
release.sequence=1
build.sequence=1
commit.hash=$commit
declaration.hash=$declarationHash
manifest.hash=$manifestHash
"@
    $archive = [IO.Compression.ZipFile]::Open($jar, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entryName in @('BOOT-INF/classes/', 'BOOT-INF/lib/', 'BOOT-INF/lib/runtime.jar',
                'org/springframework/boot/loader/launch/JarLauncher.class')) {
            $entry = $archive.CreateEntry($entryName)
            $stream = $entry.Open()
            try { if ($entryName -notmatch '/$') { $stream.WriteByte(0) } }
            finally { $stream.Dispose() }
        }
        $entry = $archive.CreateEntry('META-INF/tpv-erp-release.properties')
        $stream = $entry.Open()
        try { $bytes = [Text.Encoding]::UTF8.GetBytes($manifest); $stream.Write($bytes, 0, $bytes.Length) }
        finally { $stream.Dispose() }
        $entry = $archive.CreateEntry('BOOT-INF/classes/META-INF/fiscal/declaracion-responsable-4.2.0.pdf')
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

    It 'pasa el releaseId y la version esperados al perfil Maven sin cambiar project.version' {
        $prepareText = Get-Content -LiteralPath $scriptPaths[1] -Raw
        $prepareText | Should Match 'tpv\.release\.id=\$ExpectedReleaseId'
        $prepareText | Should Match 'tpv\.release\.version=\$ExpectedVersion'
        $prepareText | Should Match 'projectVersion'
        $prepareText | Should Match 'ExpectedVersion debe coincidir con project.version'
        $prepareText | Should Not Match 'AllowDirtySource'
    }

    It 'verifica un releaseId alternativo conservando la version publica 4.2.0' {
        $releaseId = 'tpv-erp-hotfix-4.2.0'
        $directory = New-ContractBundle $releaseId
        try {
            $verifier = Join-Path $PSScriptRoot 'Test-TpvBackendProductionBundle.ps1'
            $result = & $verifier -BundleDirectory $directory -ExpectedReleaseId $releaseId `
                -ExpectedVersion '4.2.0' -ExpectedSchemaVersion V233 `
                -ExpectedReleaseSequence 1 -ExpectedBuildSequence 1 -AsObject
            $result.Status | Should Be 'PROMOTABLE'
            $result.ReleaseId | Should Be $releaseId
            $result.Version | Should Be '4.2.0'
        }
        finally { Remove-Item -LiteralPath $directory -Recurse -Force }
    }

    It 'verifica realmente el hash canonico y las secuencias' {
        $directory = New-ContractBundle
        try {
            $verifier = Join-Path $PSScriptRoot 'Test-TpvBackendProductionBundle.ps1'
            $result = & $verifier -BundleDirectory $directory -ExpectedSchemaVersion V233 `
                -ExpectedReleaseSequence 1 -ExpectedBuildSequence 1 -AsObject
            $result.Status | Should Be 'PROMOTABLE'
            $result.ReleaseSequence | Should Be 1
            $threw = $false
            try {
                & $verifier -BundleDirectory $directory -ExpectedSchemaVersion V233 `
                    -ExpectedReleaseSequence 2 -ExpectedBuildSequence 1 -AsObject | Out-Null
            } catch { $threw = $true }
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
                & $verifier -BundleDirectory $directory -ExpectedSchemaVersion V233 `
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
                    -ExpectedSchemaVersion V233 -ExpectedReleaseSequence 1 -ExpectedBuildSequence 1 -AsObject | Out-Null
            } catch { $traversalThrew = $true }
            $traversalThrew | Should Be $true
            $negativeThrew = $false
            try {
                & $verifier -BundleDirectory $directory -ExpectedSchemaVersion V233 `
                    -ExpectedReleaseSequence -1 -ExpectedBuildSequence 1 -AsObject | Out-Null
            } catch { $negativeThrew = $true }
            $negativeThrew | Should Be $true
        }
        finally { Remove-Item -LiteralPath $directory -Recurse -Force }
    }
}
