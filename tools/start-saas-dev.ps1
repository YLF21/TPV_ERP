[CmdletBinding()]
param(
    [switch]$NoBuild,
    [ValidateRange(1024, 65535)]
    [int]$WebPort = 8088
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$saasRoot = Join-Path $repositoryRoot "backend-saas"
$environmentFile = Join-Path $saasRoot ".env"
$environmentExample = Join-Path $saasRoot ".env.example"
$composeFile = Join-Path $saasRoot "docker-compose.yml"
$composeDevFile = Join-Path $saasRoot "docker-compose.dev.yml"

if ($null -eq (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "No se encontro Docker. Inicia Docker Desktop y vuelve a intentarlo."
}

if (-not (Test-Path -LiteralPath $environmentFile -PathType Leaf)) {
    Copy-Item -LiteralPath $environmentExample -Destination $environmentFile
    Write-Host "Configuracion DEV creada en $environmentFile"
}

$requiredVariables = @(
    "POSTGRES_PASSWORD",
    "TPV_SAAS_SECRET_ENCRYPTION_KEY"
)
$configured = @{}
foreach ($line in Get-Content -LiteralPath $environmentFile) {
    if ($line -match '^([^#=]+)=(.*)$') {
        $configured[$Matches[1].Trim()] = $Matches[2]
    }
}
foreach ($name in $requiredVariables) {
    if (-not $configured.ContainsKey($name) -or
            [string]::IsNullOrWhiteSpace([string]$configured[$name])) {
        throw "Falta $name en $environmentFile. Vuelve a copiar .env.example o define un valor DEV antes de arrancar."
    }
}

$previousWebPort = [Environment]::GetEnvironmentVariable(
    "TPV_SAAS_WEB_PORT", "Process")
$previousCorsOrigins = [Environment]::GetEnvironmentVariable(
    "TPV_SAAS_CORS_ALLOWED_ORIGINS", "Process")
$environmentCorsOrigins = if ($configured.ContainsKey("TPV_SAAS_CORS_ALLOWED_ORIGINS")) {
    [string]$configured["TPV_SAAS_CORS_ALLOWED_ORIGINS"]
} else {
    $null
}
try {
    [Environment]::SetEnvironmentVariable(
        "TPV_SAAS_WEB_PORT", $WebPort.ToString(), "Process")
    if ([string]::IsNullOrWhiteSpace($previousCorsOrigins) -and
            [string]::IsNullOrWhiteSpace($environmentCorsOrigins)) {
        [Environment]::SetEnvironmentVariable(
            "TPV_SAAS_CORS_ALLOWED_ORIGINS",
            "http://127.0.0.1:$WebPort,http://localhost:$WebPort",
            "Process")
    }
    $arguments = @(
        "compose",
        "--env-file", $environmentFile,
        "-f", $composeFile,
        "-f", $composeDevFile,
        "up", "-d"
    )
    if (-not $NoBuild) {
        $arguments += "--build"
    }
    & docker @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose finalizo con codigo $LASTEXITCODE."
    }
}
finally {
    [Environment]::SetEnvironmentVariable(
        "TPV_SAAS_WEB_PORT", $previousWebPort, "Process")
    [Environment]::SetEnvironmentVariable(
        "TPV_SAAS_CORS_ALLOWED_ORIGINS", $previousCorsOrigins, "Process")
}

$deadline = [DateTimeOffset]::UtcNow.AddMinutes(4)
do {
    $containerState = & docker inspect `
        --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' `
        tpv-erp-saas-backend 2>$null
    if ($LASTEXITCODE -eq 0 -and ($containerState | Select-Object -First 1) -eq "healthy") {
        break
    }
    Start-Sleep -Seconds 2
} while ([DateTimeOffset]::UtcNow -lt $deadline)

if (($containerState | Select-Object -First 1) -ne "healthy") {
    & docker compose --env-file $environmentFile `
        -f $composeFile -f $composeDevFile ps
    throw "El backend SaaS no alcanzo el estado healthy. Revisa los logs con docker compose logs saas-backend."
}

$response = Invoke-WebRequest -UseBasicParsing `
    -Uri "http://127.0.0.1:$WebPort/" -TimeoutSec 10
if ($response.StatusCode -ne 200) {
    throw "El frontend SaaS respondio con HTTP $($response.StatusCode)."
}

Write-Host "SaaS DEV listo en http://127.0.0.1:$WebPort"
Write-Host "Usuario inicial DEV: admin"
Write-Host "Contrasena inicial DEV: admin"
Write-Host "PostgreSQL DEV: 127.0.0.1:5433"
