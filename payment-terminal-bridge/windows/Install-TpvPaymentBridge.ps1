[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory)] [string] $BridgeJar,
    [Parameter(Mandatory)] [ValidatePattern('^[0-9a-fA-F]{64}$')] [string] $BridgeJarSha256,
    [Parameter(Mandatory)] [string] $WinSwExecutable,
    [Parameter(Mandatory)] [ValidatePattern('^[0-9a-fA-F]{64}$')] [string] $WinSwSha256,
    [Parameter(Mandatory)] [string] $JavaExecutable,
    [Parameter(Mandatory)] [string] $Configuration,
    [string] $InstallRoot = "$env:ProgramData\TPV ERP\PaymentTerminalBridge",
    [string] $ServiceId = 'TpvPaymentTerminalBridge'
)

$ErrorActionPreference = 'Stop'
function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Ejecute el instalador desde PowerShell como administrador.'
    }
}
function Resolve-VerifiedFile([string] $Path, [string] $ExpectedHash) {
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) { throw "No es un archivo: $resolved" }
    $actual = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash
    if ($actual -ne $ExpectedHash.ToUpperInvariant()) { throw "SHA-256 incorrecto para $resolved" }
    return $resolved
}

Assert-Administrator
$sourceJar = Resolve-VerifiedFile $BridgeJar $BridgeJarSha256
$sourceWinSw = Resolve-VerifiedFile $WinSwExecutable $WinSwSha256
$sourceConfig = (Resolve-Path -LiteralPath $Configuration -ErrorAction Stop).Path
$java = (Resolve-Path -LiteralPath $JavaExecutable -ErrorAction Stop).Path
$root = [IO.Path]::GetFullPath($InstallRoot)
if ([IO.Path]::GetPathRoot($root) -eq $root) { throw 'InstallRoot no puede ser la raiz de una unidad.' }

if ($PSCmdlet.ShouldProcess($root, 'Instalar el servicio local de datáfono')) {
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $root 'plugins') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $root 'data') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $root 'logs') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $root 'backups') -Force | Out-Null
    $jarTarget = Join-Path $root 'payment-terminal-bridge.jar'
    $serviceTarget = Join-Path $root "$ServiceId.exe"
    $configTarget = Join-Path $root 'bridge-config.json'
    Copy-Item -LiteralPath $sourceJar -Destination $jarTarget -Force
    Copy-Item -LiteralPath $sourceWinSw -Destination $serviceTarget -Force
    if (-not (Test-Path -LiteralPath $configTarget)) { Copy-Item -LiteralPath $sourceConfig -Destination $configTarget }
    $xmlTarget = Join-Path $root "$ServiceId.xml"
    $xml = @"
<service>
  <id>$ServiceId</id>
  <name>TPV ERP - Payment Terminal Bridge</name>
  <description>Puente local aislado para datáfonos físicos de TPV ERP.</description>
  <executable>$([Security.SecurityElement]::Escape($java))</executable>
  <arguments>--enable-native-access=ALL-UNNAMED -jar &quot;$([Security.SecurityElement]::Escape($jarTarget))&quot; --config &quot;$([Security.SecurityElement]::Escape($configTarget))&quot;</arguments>
  <workingdirectory>$([Security.SecurityElement]::Escape($root))</workingdirectory>
  <startmode>Automatic</startmode>
  <delayedAutoStart>true</delayedAutoStart>
  <serviceaccount><domain>NT AUTHORITY</domain><user>LocalService</user></serviceaccount>
  <onfailure action="restart" delay="10 sec" />
  <onfailure action="restart" delay="30 sec" />
  <onfailure action="none" />
  <resetfailure>1 hour</resetfailure>
  <logpath>$([Security.SecurityElement]::Escape((Join-Path $root 'logs')))</logpath>
  <log mode="roll-by-size"><sizeThreshold>10240</sizeThreshold><keepFiles>8</keepFiles></log>
</service>
"@
    Set-Content -LiteralPath $xmlTarget -Value $xml -Encoding UTF8
    & $java --enable-native-access=ALL-UNNAMED -jar $jarTarget --config $configTarget --validate
    if ($LASTEXITCODE -ne 0) { throw 'La configuración del puente no es válida.' }
    & $serviceTarget install
    if ($LASTEXITCODE -ne 0) { throw 'WinSW no pudo registrar el servicio.' }
    Write-Host "Servicio instalado. Guarde el token con: java -jar `"$jarTarget`" --config `"$configTarget`" --store-secret windows:bridge-http-token"
}
