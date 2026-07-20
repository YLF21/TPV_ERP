[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory)] [string] $AdapterJar,
    [Parameter(Mandatory)] [ValidatePattern('^[0-9a-fA-F]{64}$')] [string] $ExpectedSha256,
    [string] $InstallRoot = "$env:ProgramData\TPV ERP\PaymentTerminalBridge",
    [string] $ServiceId = 'TpvPaymentTerminalBridge',
    [string] $JavaExecutable = 'java.exe'
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($InstallRoot)
$source = (Resolve-Path -LiteralPath $AdapterJar -ErrorAction Stop).Path
$actual = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
if ($actual -ne $ExpectedSha256.ToUpperInvariant()) { throw 'El SHA-256 del adaptador no coincide.' }
$plugins = [IO.Path]::GetFullPath((Join-Path $root 'plugins'))
$target = [IO.Path]::GetFullPath((Join-Path $plugins ([IO.Path]::GetFileName($source))))
if (-not $target.StartsWith($plugins + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Nombre de adaptador no permitido.'
}
$config = Join-Path $root 'bridge-config.json'
$bridge = Join-Path $root 'payment-terminal-bridge.jar'
$service = Join-Path $root "$ServiceId.exe"
foreach ($required in @($config, $bridge, $service)) { if (-not (Test-Path -LiteralPath $required)) { throw "Falta $required" } }

if ($PSCmdlet.ShouldProcess($target, 'Instalar o actualizar adaptador verificado')) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backup = Join-Path (Join-Path $root 'backups') "adapter-$stamp"
    New-Item -ItemType Directory -Path $backup -Force | Out-Null
    Copy-Item -LiteralPath $config -Destination (Join-Path $backup 'bridge-config.json')
    if (Test-Path -LiteralPath $target) { Copy-Item -LiteralPath $target -Destination (Join-Path $backup ([IO.Path]::GetFileName($target))) }
    & $service stop | Out-Null
    try {
        Copy-Item -LiteralPath $source -Destination $target -Force
        $json = Get-Content -LiteralPath $config -Raw | ConvertFrom-Json
        if ($null -eq $json.pluginDigests) { $json | Add-Member -NotePropertyName pluginDigests -NotePropertyValue ([ordered]@{}) }
        $json.pluginDigests | Add-Member -NotePropertyName ([IO.Path]::GetFileName($target)) -NotePropertyValue $actual -Force
        $json | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $config -Encoding UTF8
        & $JavaExecutable --enable-native-access=ALL-UNNAMED -jar $bridge --config $config --validate
        if ($LASTEXITCODE -ne 0) { throw 'El catálogo o la configuración del adaptador no es válido.' }
        & $service start | Out-Null
    } catch {
        Copy-Item -LiteralPath (Join-Path $backup 'bridge-config.json') -Destination $config -Force
        $oldAdapter = Join-Path $backup ([IO.Path]::GetFileName($target))
        if (Test-Path -LiteralPath $oldAdapter) { Copy-Item -LiteralPath $oldAdapter -Destination $target -Force }
        & $service start | Out-Null
        throw
    }
    Write-Host "Adaptador instalado y registrado. Backup: $backup"
}
