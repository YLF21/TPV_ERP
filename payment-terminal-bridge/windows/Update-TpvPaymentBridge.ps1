[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory)] [string] $BridgeJar,
    [Parameter(Mandatory)] [ValidatePattern('^[0-9a-fA-F]{64}$')] [string] $ExpectedSha256,
    [string] $InstallRoot = "$env:ProgramData\TPV ERP\PaymentTerminalBridge",
    [string] $ServiceId = 'TpvPaymentTerminalBridge',
    [string] $JavaExecutable = 'java.exe'
)

$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $BridgeJar -ErrorAction Stop).Path
$actual = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
if ($actual -ne $ExpectedSha256.ToUpperInvariant()) { throw 'El SHA-256 del puente no coincide.' }
$root = [IO.Path]::GetFullPath($InstallRoot)
$target = Join-Path $root 'payment-terminal-bridge.jar'
$config = Join-Path $root 'bridge-config.json'
$service = Join-Path $root "$ServiceId.exe"
foreach ($required in @($target, $config, $service)) { if (-not (Test-Path -LiteralPath $required)) { throw "Falta $required" } }

if ($PSCmdlet.ShouldProcess($target, 'Actualizar puente con rollback automático')) {
    $backup = Join-Path (Join-Path $root 'backups') ("bridge-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Path $backup -Force | Out-Null
    Copy-Item -LiteralPath $target -Destination (Join-Path $backup 'payment-terminal-bridge.jar')
    Copy-Item -LiteralPath $config -Destination (Join-Path $backup 'bridge-config.json')
    & $service stop | Out-Null
    try {
        Copy-Item -LiteralPath $source -Destination $target -Force
        & $JavaExecutable --enable-native-access=ALL-UNNAMED -jar $target --config $config --validate
        if ($LASTEXITCODE -ne 0) { throw 'La nueva versión no valida la configuración instalada.' }
        & $service start | Out-Null
        Start-Sleep -Seconds 3
        if ((Get-Service -Name $ServiceId -ErrorAction Stop).Status -ne 'Running') { throw 'El servicio no permanece iniciado.' }
    } catch {
        Copy-Item -LiteralPath (Join-Path $backup 'payment-terminal-bridge.jar') -Destination $target -Force
        Copy-Item -LiteralPath (Join-Path $backup 'bridge-config.json') -Destination $config -Force
        & $service start | Out-Null
        throw
    }
    Write-Host "Puente actualizado. Backup recuperable: $backup"
}
