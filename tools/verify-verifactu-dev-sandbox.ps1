[CmdletBinding()]
param(
    [string]$EvidenceDirectory = "target\verifactu-dev-proof",
    [int]$BackendPort = 18080,
    [int]$FrontendPort = 4173
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$root = Split-Path -Parent $PSScriptRoot
$resolvedEvidence = [IO.Path]::GetFullPath((Join-Path $root $EvidenceDirectory))

Push-Location $root
try {
    Push-Location (Join-Path $root "backend")
    try {
        & (Join-Path $root "backend\mvnw.cmd") -q `
            "-Dtest=FiscalRuntimePropertiesTest,VerifactuXmlServiceTest,VerifactuOfficialXsdValidatorTest,SimulatedAeatTransportTest" `
            test
        if ($LASTEXITCODE -ne 0) {
            throw "Las pruebas deterministas del laboratorio fiscal han fallado."
        }
    }
    finally {
        Pop-Location
    }
    & (Join-Path $root "tools\start-app-venta-isolated.ps1") `
        -CheckOnly -FiscalSandbox -FiscalProof `
        -EvidenceDirectory $resolvedEvidence `
        -BackendPort $BackendPort -FrontendPort $FrontendPort
    if ($LASTEXITCODE -ne 0) {
        throw "La prueba API del laboratorio fiscal aislado ha fallado."
    }
    Write-Host "Laboratorio fiscal DEV verificado. Evidencia: $resolvedEvidence" -ForegroundColor Green
}
finally {
    Pop-Location
}
