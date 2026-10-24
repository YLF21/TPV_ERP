[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $BundleDirectory,
    [string] $InstalledServiceXml = (Join-Path $env:ProgramData 'TPV ERP\Backend\TPVERPBackend.xml')
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$bundle = (Resolve-Path -LiteralPath $BundleDirectory -ErrorAction Stop).Path
$xmlPath = (Resolve-Path -LiteralPath $InstalledServiceXml -ErrorAction Stop).Path
foreach ($path in @($xmlPath, $bundle)) {
    $cursor = $path
    while ($true) {
        if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'La ruta del servicio instalado contiene un enlace.'
        }
        $parent = [IO.Path]::GetDirectoryName($cursor)
        if (-not $parent -or $parent -eq $cursor) { break }
        $cursor = $parent
    }
}
$settings = [Xml.XmlReaderSettings]::new()
$settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
$settings.XmlResolver = $null
$settings.MaxCharactersInDocument = 65536
$reader = [Xml.XmlReader]::Create($xmlPath, $settings)
try { $xml = [Xml.XmlDocument]::new(); $xml.XmlResolver = $null; $xml.Load($reader) }
finally { $reader.Dispose() }
if ([string]$xml.service.id -cne 'TPVERPBackend') { throw 'El servicio instalado no es TPVERPBackend.' }
$service = Get-CimInstance -ClassName Win32_Service -Filter "Name = 'TPVERPBackend'" -ErrorAction Stop
if ($null -eq $service) { throw 'TPVERPBackend no esta registrado en el Administrador de servicios.' }
[void](Get-Service -Name 'TPVERPBackend' -ErrorAction Stop)
$serviceExe = Join-Path ([IO.Path]::GetDirectoryName($xmlPath)) 'TPVERPBackend.exe'
if (-not (Test-Path -LiteralPath $serviceExe -PathType Leaf) -or
    -not [string]::Equals(([string]$service.PathName).Trim(' ', '"'), $serviceExe,
        [StringComparison]::OrdinalIgnoreCase) -or
    -not [string]::Equals([string]$service.StartName, [string]$xml.service.serviceaccount.username,
        [StringComparison]::OrdinalIgnoreCase) -or
    [string]$service.StartName -cnotin @('NT SERVICE\TPVERPBackend', 'LocalSystem',
        'NT AUTHORITY\LocalService', 'NT AUTHORITY\NetworkService')) {
    throw 'El XML no pertenece al servicio WinSW TPVERPBackend real con su cuenta virtual.'
}
$arguments = [string]$xml.service.arguments
$jarMatches = [Regex]::Matches($arguments, '(?i)(?:^|\s)-jar\s+"([^"\r\n]+\.jar)"(?=\s|$)')
if ($jarMatches.Count -ne 1) { throw 'El servicio no identifica un unico JAR instalado.' }
$jar = (Resolve-Path -LiteralPath $jarMatches[0].Groups[1].Value -ErrorAction Stop).Path
$cursor = $jar
while ($true) {
    if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'El JAR instalado contiene un enlace.'
    }
    $parent = [IO.Path]::GetDirectoryName($cursor)
    if (-not $parent -or $parent -eq $cursor) { break }
    $cursor = $parent
}
if (-not [string]::Equals([IO.Path]::GetDirectoryName($jar), $bundle,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'El bundle solicitado no es la release activa instalada.'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($jar)
try { $isTest = ($null -ne $zip.GetEntry('META-INF/tpv-test-bundle.properties')) -or
        ($null -ne $zip.GetEntry('BOOT-INF/classes/META-INF/tpv-test-bundle.properties')) }
finally { $zip.Dispose() }
if ($isTest) {
    if ([string]$service.StartName -cne 'NT SERVICE\TPVERPBackend') {
        throw 'El servicio TEST_ONLY exige la cuenta virtual TPVERPBackend.'
    }
    & (Join-Path $PSScriptRoot 'Assert-TpvTestLabContext.ps1') -InstalledServiceXml $xmlPath | Out-Null
    $verifier = Join-Path $PSScriptRoot 'Test-TpvBackendTestBundle.ps1'
} else {
    $verifier = Join-Path $PSScriptRoot 'Test-TpvBackendProductionBundle.ps1'
}
if (-not (Test-Path -LiteralPath $verifier -PathType Leaf)) { throw 'Falta el verificador de la release instalada.' }
$result = & $verifier -BundleDirectory $bundle -AsObject
if (-not [string]::Equals($result.Jar, $jar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'El verificador no coincide con el JAR activo del servicio.'
}
[pscustomobject]@{ Verifier = $verifier; Bundle = $result; TestOnly = $isTest }
