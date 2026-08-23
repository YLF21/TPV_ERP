[CmdletBinding()]
param(
    [string]$HardwareConfigPath = (Join-Path $env:APPDATA "Electron\hardware-config.json"),
    [string]$PrinterName,
    [switch]$CheckOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Resolve-TicketPrinterName {
    param(
        [string]$ExplicitPrinterName,
        [string]$ConfigPath
    )

    if (-not [string]::IsNullOrWhiteSpace($ExplicitPrinterName)) {
        return $ExplicitPrinterName.Trim()
    }
    if (-not (Test-Path -LiteralPath $ConfigPath)) {
        throw "No existe la configuracion de hardware: $ConfigPath. Indique -PrinterName o configure APP VENTA primero."
    }

    $config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
    $ticketRoute = @($config.documentPrintRoutes) |
        Where-Object { $_.documentType -eq "TICKET" } |
        Select-Object -First 1
    $configuredName = if ($null -ne $ticketRoute -and
        -not [string]::IsNullOrWhiteSpace([string]$ticketRoute.printerName)) {
        [string]$ticketRoute.printerName
    }
    else {
        [string]$config.ticketPrinterName
    }
    if ([string]::IsNullOrWhiteSpace($configuredName)) {
        throw "APP VENTA no tiene una impresora de tickets configurada."
    }
    return $configuredName.Trim()
}

if (-not $CheckOnly -and -not (Test-IsAdministrator)) {
    throw "Ejecute este script como administrador para configurar el servicio Spooler y el registro de diagnostico."
}

$resolvedPrinterName = Resolve-TicketPrinterName -ExplicitPrinterName $PrinterName -ConfigPath $HardwareConfigPath

if (-not $CheckOnly) {
    Set-Service -Name "Spooler" -StartupType Automatic
    $spooler = Get-Service -Name "Spooler"
    if ($spooler.Status -ne "Running") {
        Start-Service -Name "Spooler"
    }

    & wevtutil.exe sl "Microsoft-Windows-PrintService/Operational" /e:true
    if ($LASTEXITCODE -ne 0) {
        throw "No se pudo habilitar Microsoft-Windows-PrintService/Operational (codigo $LASTEXITCODE)."
    }
}

$spooler = Get-CimInstance Win32_Service -Filter "Name = 'Spooler'"
if ($null -eq $spooler) {
    throw "Windows no ha devuelto el servicio Spooler."
}
if ($spooler.StartMode -ne "Auto") {
    throw "Spooler no tiene inicio automatico (estado actual: $($spooler.StartMode))."
}
if ($spooler.State -ne "Running") {
    throw "Spooler no esta iniciado (estado actual: $($spooler.State))."
}

$printer = Get-Printer -Name $resolvedPrinterName -ErrorAction Stop
$printLog = Get-WinEvent -ListLog "Microsoft-Windows-PrintService/Operational"
if (-not $printLog.IsEnabled) {
    throw "El registro Microsoft-Windows-PrintService/Operational no esta habilitado."
}

Write-Host "Preparacion de impresion de APP VENTA correcta." -ForegroundColor Green
Write-Host "Spooler: Automatico y en ejecucion"
Write-Host "Impresora: $($printer.Name)"
Write-Host "Puerto: $($printer.PortName)"
Write-Host "Registro operativo de impresion: habilitado"
Write-Host "No se ha enviado ninguna impresion de prueba."
