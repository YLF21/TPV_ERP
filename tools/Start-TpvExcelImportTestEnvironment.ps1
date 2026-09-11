[CmdletBinding()]
param(
    [switch]$StartBackend,
    [int]$DatabasePort = 55439,
    [int]$BackendPort = 18080
)

$ErrorActionPreference = 'Stop'
$container = 'tpv-excel-plan-test-20260907'
$database = 'tpv_excel_plan_test'
$username = 'tpv_excel_test'
# Dedicated local-only test credential. It is not a production secret.
$password = 'TpvExcelPlanOnly-20260907-Db!'
$workspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$root = Join-Path $workspaceRoot 'backend\target\tpv-excel-plan-test-20260907'
$keyDirectory = Join-Path $root 'keys'
$backupDirectory = Join-Path $root 'backups'
$imageDirectory = Join-Path $root 'product-images'
$templateDirectory = Join-Path $root 'document-templates'
$secretDirectory = Join-Path $root 'verifactu-secrets'
$exportDirectory = Join-Path $root 'fiscal-exports'
New-Item -ItemType Directory -Force -Path $keyDirectory,$backupDirectory,$imageDirectory,$templateDirectory,$secretDirectory,$exportDirectory | Out-Null

$existingInspect = docker inspect $container 2>$null
$existing = $false
if ($LASTEXITCODE -eq 0) {
    $existing = $true
    $metadata = docker inspect $container | ConvertFrom-Json
    $config = $metadata[0].Config
    $hostConfig = $metadata[0].HostConfig
    $network = $metadata[0].NetworkSettings
    $envMap = @{}
    foreach ($item in $config.Env) { $key,$value = $item -split '=',2; $envMap[$key] = $value }
    $bindings = @($network.Ports.'5432/tcp')
    $binding = $bindings | Where-Object { $_.HostIp -in @('127.0.0.1','::1') -and $_.HostPort -eq [string]$DatabasePort }
    if ($config.Image -notmatch '^postgres:17\.6(?:@|$)' -or $envMap.POSTGRES_DB -ne $database -or
        $envMap.POSTGRES_USER -ne $username -or $bindings.Count -ne 1 -or -not $binding -or
        $hostConfig.NetworkMode -notin @('bridge','default')) {
        throw "El contenedor existente '$container' no coincide con el entorno PostgreSQL aislado esperado; no se reutiliza."
    }
}
if (-not $existing) {
    $portBinding = "127.0.0.1:$DatabasePort`:5432"
    docker run --detach --rm --name $container `
        --env "POSTGRES_DB=$database" `
        --env "POSTGRES_USER=$username" `
        --env "POSTGRES_PASSWORD=$password" `
        --tmpfs /var/lib/postgresql/data `
        --publish $portBinding `
        postgres:17.6 | Out-Null
} elseif (-not (docker ps --filter "name=^/$container$" --format '{{.Names}}')) {
    docker start $container | Out-Null
}

$ready = $false
for ($attempt = 0; $attempt -lt 45; $attempt++) {
    docker exec $container pg_isready -U $username -d $database 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $ready) { throw "PostgreSQL aislado no alcanzó estado ready: $container" }

$env:TPV_DB_URL = "jdbc:postgresql://127.0.0.1:$DatabasePort/$database"
$env:TPV_DB_USERNAME = $username
$env:TPV_DB_PASSWORD = $password
$env:TPV_ERP_TEST_DB_URL = $env:TPV_DB_URL
$env:TPV_ERP_TEST_DB_USER = $username
$env:TPV_ERP_TEST_DB_PASSWORD = $password
$env:TPV_TEST_DB_URL = $env:TPV_DB_URL
$env:TPV_TEST_DB_USERNAME = $username
$env:TPV_TEST_DB_PASSWORD = $password
$env:TPV_SERVER_ADDRESS = '127.0.0.1'
$env:TPV_SERVER_PORT = [string]$BackendPort
$env:TPV_DEV_UNLICENSED_ACCESS_ENABLED = 'true'
$env:TPV_DEV_SAMPLE_DATA_ENABLED = 'true'
$env:TPV_INSTALLATION_PORTABLE_SECRET_KEY = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='
$env:TPV_KEY_DIRECTORY = $keyDirectory
$env:TPV_BACKUP_DIRECTORY = $backupDirectory
$env:TPV_PRODUCT_IMAGE_DIRECTORY = $imageDirectory
$env:TPV_DOCUMENT_TEMPLATE_DIRECTORY = $templateDirectory
$env:TPV_VERIFACTU_SECRET_DIRECTORY = $secretDirectory
$env:TPV_VERIFACTU_EXPORT_JOB_DIRECTORY = $exportDirectory
$env:TPV_VERIFACTU_DEV_SANDBOX_ENABLED = 'false'
$env:TPV_VERIFACTU_AEAT_TEST_NETWORK_ENABLED = 'false'
$env:TPV_VERIFACTU_PRODUCTION_ENABLED = 'false'
$env:TPV_VERIFACTU_WORKER_ENABLED = 'false'
$env:TPV_VERIFACTU_TRANSPORT_MODE = 'AEAT'
$env:TPV_SCHEDULING_POOL_SIZE = '1'

Write-Output "container=$container"
Write-Output "database=127.0.0.1:$DatabasePort/$database"
Write-Output "username=$username"
Write-Output "password-status=loaded-for-this-PowerShell-process (not displayed)"
Write-Output "backend-profile=dev; server=127.0.0.1:$BackendPort"
Write-Output "task-directories=$root"

if ($StartBackend) {
    if (Get-NetTCPConnection -LocalPort $BackendPort -State Listen -ErrorAction SilentlyContinue) {
        throw "El puerto backend $BackendPort ya está ocupado; no se inicia sobre otro proceso."
    }
    Push-Location (Join-Path $workspaceRoot 'backend')
    try { & .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev" }
    finally { Pop-Location }
}
