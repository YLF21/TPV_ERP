param(
    [switch]$IncludeFrontend,
    [switch]$IncludeE2E
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

Push-Location (Join-Path $root "backend")
try {
    & mvn.cmd "-Dtest=BusinessOperationClassifierTest,BusinessObservabilityInterceptorTest,BusinessBacklogMonitorTest,PaymentTerminalGatewayContractTest,SalePaymentSessionServiceTest,CustomerPendingSaleServiceTest,CustomerCreditAccountServiceTest,CustomerCreditAccountControllerContractTest,PromotionEngineTest,PromotionalCouponServiceTest,DocumentPromotionIntegrationTest,PosCashServiceTest,PosCardServiceTest,TicketReturnServiceTest" test
    if ($LASTEXITCODE -ne 0) {
        throw "Las pruebas de preparación backend han fallado"
    }
}
finally {
    Pop-Location
}

if ($IncludeFrontend) {
    Push-Location (Join-Path $root "frontend")
    try {
        & npm.cmd test -- apps/app-venta/src packages/app-common/src
        if ($LASTEXITCODE -ne 0) {
            throw "Las pruebas de APP VENTA han fallado"
        }
    }
    finally {
        Pop-Location
    }
}

if ($IncludeE2E) {
    Push-Location (Join-Path $root "frontend")
    try {
        & npm.cmd run test:e2e -- app-venta-readiness.spec.ts
        if ($LASTEXITCODE -ne 0) {
            throw "Las pruebas E2E de APP VENTA han fallado"
        }
    }
    finally {
        Pop-Location
    }
}

Write-Host "Preparación de APP VENTA verificada correctamente." -ForegroundColor Green
