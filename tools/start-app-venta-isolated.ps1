[CmdletBinding()]
param(
    [switch]$CheckOnly,
    [switch]$FiscalSandbox,
    [ValidateRange(1024, 65535)]
    [int]$BackendPort = 18080,
    [ValidateRange(1024, 65535)]
    [int]$FrontendPort = 4173,
    [ValidatePattern('^[0-9a-fA-F-]{36}$')]
    [string]$TerminalId = "06d2ce45-8ead-349d-b844-4ecdead5e1ec",
    [ValidateNotNullOrEmpty()]
    [string]$TerminalCredential = "DEV-SERVER",
    [ValidateNotNullOrEmpty()]
    [string]$PostgresHost = "127.0.0.1",
    [ValidateRange(1, 65535)]
    [int]$PostgresPort = 5432,
    [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')]
    [string]$PostgresAdminUser = "postgres",
    [string]$PostgresAdminPassword = $env:TPV_POSTGRES_ADMIN_PASSWORD,
    [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')]
    [string]$DatabaseOwner = "tpv_erp_test",
    [string]$DatabasePassword = $env:TPV_TEST_DB_PASSWORD
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$script:DatabasePrefix = "tpv_erp_app_venta_"
$script:RepositoryRoot = Split-Path -Parent $PSScriptRoot
$script:BackendProcess = $null
$script:FrontendProcess = $null
$script:DatabaseCreated = $false
$script:TempRoot = $null
$script:PsqlPath = $null
$script:OriginalEnvironment = @{}

function Assert-RequiredValue {
    param(
        [Parameter(Mandatory)]
        [string]$Name,
        [AllowEmptyString()]
        [string]$Value,
        [Parameter(Mandatory)]
        [string]$EnvironmentHint
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "Falta $Name. Defina $EnvironmentHint antes de ejecutar el script."
    }
}

function Assert-PortAvailable {
    param(
        [Parameter(Mandatory)]
        [int]$Port,
        [Parameter(Mandatory)]
        [string]$ServiceName
    )

    $listeners = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    $inUse = $listeners | Where-Object { $_.Port -eq $Port }
    if ($inUse) {
        throw "El puerto $Port ya esta ocupado. No se puede iniciar $ServiceName de forma aislada."
    }
}

function Set-ScopedEnvironment {
    param(
        [Parameter(Mandatory)]
        [string]$Name,
        [AllowEmptyString()]
        [string]$Value
    )

    if (-not $script:OriginalEnvironment.ContainsKey($Name)) {
        $script:OriginalEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, "Process")
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
}

function Restore-ScopedEnvironment {
    foreach ($entry in $script:OriginalEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
    }
    $script:OriginalEnvironment.Clear()
}

function Invoke-Psql {
    param(
        [Parameter(Mandatory)]
        [string]$Database,
        [Parameter(Mandatory)]
        [string]$User,
        [Parameter(Mandatory)]
        [string]$Password,
        [Parameter(Mandatory)]
        [string]$Sql
    )

    $previousPassword = [Environment]::GetEnvironmentVariable("PGPASSWORD", "Process")
    try {
        [Environment]::SetEnvironmentVariable("PGPASSWORD", $Password, "Process")
        $output = & $script:PsqlPath `
            "--host=$PostgresHost" `
            "--port=$PostgresPort" `
            "--username=$User" `
            "--dbname=$Database" `
            "--no-psqlrc" `
            "--set=ON_ERROR_STOP=1" `
            "--tuples-only" `
            "--no-align" `
            "--command=$Sql" 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "psql finalizo con codigo $LASTEXITCODE`: $($output -join [Environment]::NewLine)"
        }
        return (($output | Out-String).Trim())
    }
    finally {
        [Environment]::SetEnvironmentVariable("PGPASSWORD", $previousPassword, "Process")
    }
}

function Stop-ProcessTree {
    param(
        [Parameter(Mandatory)]
        [int]$RootProcessId
    )

    $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $RootProcessId" -ErrorAction SilentlyContinue)
    foreach ($child in $children) {
        Stop-ProcessTree -RootProcessId ([int]$child.ProcessId)
    }

    if (Get-Process -Id $RootProcessId -ErrorAction SilentlyContinue) {
        Stop-Process -Id $RootProcessId -Force -ErrorAction SilentlyContinue
    }
}

function Get-LogTail {
    param(
        [Parameter(Mandatory)]
        [string[]]$Paths
    )

    $lines = foreach ($path in $Paths) {
        if (Test-Path -LiteralPath $path) {
            Get-Content -LiteralPath $path -Tail 30 -ErrorAction SilentlyContinue
        }
    }
    return ($lines -join [Environment]::NewLine)
}

function Wait-HttpEndpoint {
    param(
        [Parameter(Mandatory)]
        [string]$Url,
        [Parameter(Mandatory)]
        [System.Diagnostics.Process]$Process,
        [Parameter(Mandatory)]
        [string[]]$LogPaths,
        [Parameter(Mandatory)]
        [int]$TimeoutSeconds,
        [Parameter(Mandatory)]
        [string]$ServiceName
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Process.HasExited) {
            $logTail = Get-LogTail -Paths $LogPaths
            throw "$ServiceName se cerro antes de estar disponible.`n$logTail"
        }

        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 4
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
                return
            }
        }
        catch {
            Start-Sleep -Milliseconds 750
        }
    }

    $logTail = Get-LogTail -Paths $LogPaths
    throw "$ServiceName no estuvo disponible en $TimeoutSeconds segundos.`n$logTail"
}

function Remove-IsolatedDatabase {
    param(
        [Parameter(Mandatory)]
        [string]$DatabaseName
    )

    if (-not $DatabaseName.StartsWith($script:DatabasePrefix, [StringComparison]::Ordinal)) {
        throw "Limpieza rechazada: la base '$DatabaseName' no tiene el prefijo aislado esperado."
    }

    [void](Invoke-Psql -Database "postgres" -User $PostgresAdminUser -Password $PostgresAdminPassword -Sql `
        "select pg_terminate_backend(pid) from pg_stat_activity where datname = '$DatabaseName' and pid <> pg_backend_pid();")
    [void](Invoke-Psql -Database "postgres" -User $PostgresAdminUser -Password $PostgresAdminPassword -Sql `
        "drop database if exists `"$DatabaseName`";")
}

try {
    Assert-RequiredValue -Name "la contrasena administrativa de PostgreSQL" `
        -Value $PostgresAdminPassword -EnvironmentHint 'la variable $env:TPV_POSTGRES_ADMIN_PASSWORD'
    Assert-RequiredValue -Name "la contrasena del usuario de la base temporal" `
        -Value $DatabasePassword -EnvironmentHint 'la variable $env:TPV_TEST_DB_PASSWORD'

    $parsedTerminalId = [Guid]::Empty
    if (-not [Guid]::TryParseExact($TerminalId, "D", [ref]$parsedTerminalId)) {
        throw "TerminalId debe ser un UUID en formato canonico, por ejemplo 06d2ce45-8ead-349d-b844-4ecdead5e1ec."
    }
    $TerminalId = $parsedTerminalId.ToString("D")

    if ($BackendPort -eq $FrontendPort) {
        throw "BackendPort y FrontendPort deben ser distintos."
    }
    Assert-PortAvailable -Port $BackendPort -ServiceName "el backend"
    Assert-PortAvailable -Port $FrontendPort -ServiceName "APP VENTA"

    $script:PsqlPath = (Get-Command psql.exe -ErrorAction Stop).Source
    $npmPath = (Get-Command npm.cmd -ErrorAction Stop).Source
    $mavenWrapper = Join-Path $script:RepositoryRoot "backend\mvnw.cmd"
    if (-not (Test-Path -LiteralPath $mavenWrapper)) {
        throw "No se encontro el wrapper Maven en $mavenWrapper."
    }

    $roleExists = Invoke-Psql -Database "postgres" -User $PostgresAdminUser `
        -Password $PostgresAdminPassword `
        -Sql "select exists (select 1 from pg_roles where rolname = '$DatabaseOwner');"
    if ($roleExists -ne "t") {
        throw "El rol PostgreSQL '$DatabaseOwner' no existe. Ejecute backend/scripts/create-databases.sql una sola vez."
    }

    $uniqueSuffix = "{0}_{1}" -f [DateTime]::UtcNow.ToString("yyyyMMddHHmmss"), (Get-Random -Minimum 1000 -Maximum 9999)
    $databaseName = "$($script:DatabasePrefix)$uniqueSuffix"
    $script:TempRoot = Join-Path ([IO.Path]::GetTempPath()) "tpv-erp-app-venta-$uniqueSuffix"
    [void](New-Item -ItemType Directory -Path $script:TempRoot -Force)

    $devSigningPath = Join-Path $script:TempRoot "verifactu-dev-signing.p12"
    $devSigningPassword = "DEV-SANDBOX-$uniqueSuffix"
    if ($FiscalSandbox) {
        $keytoolPath = (Get-Command keytool.exe -ErrorAction SilentlyContinue).Source
        if ([string]::IsNullOrWhiteSpace($keytoolPath)) {
            $javaHome = [Environment]::GetEnvironmentVariable("JAVA_HOME", "Process")
            if ([string]::IsNullOrWhiteSpace($javaHome)) {
                throw "No se encontro keytool.exe ni JAVA_HOME para generar el certificado temporal del laboratorio."
            }
            $keytoolPath = Join-Path $javaHome "bin\keytool.exe"
        }
        if (-not (Test-Path -LiteralPath $keytoolPath)) {
            throw "No se encontro keytool.exe en '$keytoolPath' para generar el certificado temporal del laboratorio."
        }
        & $keytoolPath -genkeypair -alias "fiscal-dev" -storetype PKCS12 `
            -keystore $devSigningPath -storepass $devSigningPassword -keypass $devSigningPassword `
            -keyalg RSA -keysize 2048 -dname "CN=TPV ERP DEV Fiscal,SERIALNUMBER=DEV-00000000" `
            -validity 365 -noprompt | Out-Null
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $devSigningPath)) {
            throw "No se pudo generar el certificado RSA temporal del laboratorio fiscal."
        }
    }

    Write-Host "Creando base temporal $databaseName..."
    [void](Invoke-Psql -Database "postgres" -User $PostgresAdminUser -Password $PostgresAdminPassword `
        -Sql "create database `"$databaseName`" owner `"$DatabaseOwner`";")
    $script:DatabaseCreated = $true

    $backendOutLog = Join-Path $script:TempRoot "backend.out.log"
    $backendErrorLog = Join-Path $script:TempRoot "backend.error.log"
    $frontendOutLog = Join-Path $script:TempRoot "frontend.out.log"
    $frontendErrorLog = Join-Path $script:TempRoot "frontend.error.log"

    $activeProfiles = if ($FiscalSandbox) { "dev,fiscal-dev" } else { "dev" }
    $backendEnvironment = @{
        SPRING_PROFILES_ACTIVE = $activeProfiles
        TPV_SERVER_ADDRESS = "127.0.0.1"
        TPV_SERVER_PORT = "$BackendPort"
        TPV_DB_URL = "jdbc:postgresql://${PostgresHost}:$PostgresPort/$databaseName"
        TPV_DB_USERNAME = $DatabaseOwner
        TPV_DB_PASSWORD = $DatabasePassword
        TPV_DEV_SAMPLE_DATA_ENABLED = "true"
        TPV_DEV_UNLICENSED_ACCESS_ENABLED = "true"
        TPV_INSTALLATION_PORTABLE_SECRET_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        TPV_KEY_DIRECTORY = (Join-Path $script:TempRoot "keys")
        TPV_VERIFACTU_SECRET_DIRECTORY = (Join-Path $script:TempRoot "verifactu")
        TPV_VERIFACTU_SECRET_ACL_MODE = "PORTABLE"
        TPV_VERIFACTU_RUNTIME_CLASS = $(if ($FiscalSandbox) { "SANDBOX" } else { "REAL" })
        TPV_VERIFACTU_ENDPOINT_ENVIRONMENT = "TEST"
        TPV_VERIFACTU_TRANSPORT_MODE = $(if ($FiscalSandbox) { "SIMULATED" } else { "AEAT" })
        TPV_VERIFACTU_DEV_SANDBOX_ENABLED = $(if ($FiscalSandbox) { "true" } else { "false" })
        TPV_VERIFACTU_DEV_SIGNING_PKCS12 = $(if ($FiscalSandbox) { $devSigningPath } else { "" })
        TPV_VERIFACTU_DEV_SIGNING_PASSWORD = $(if ($FiscalSandbox) { $devSigningPassword } else { "" })
        TPV_VERIFACTU_WORKER_ENABLED = "false"
        TPV_DOCUMENT_TEMPLATE_DIRECTORY = (Join-Path $script:TempRoot "document-templates")
        TPV_PRODUCT_IMAGE_DIRECTORY = (Join-Path $script:TempRoot "product-images")
    }
    foreach ($entry in $backendEnvironment.GetEnumerator()) {
        Set-ScopedEnvironment -Name $entry.Key -Value $entry.Value
    }

    Write-Host "Iniciando backend en http://127.0.0.1:$BackendPort..."
    $script:BackendProcess = Start-Process -FilePath $mavenWrapper `
        -ArgumentList @("--batch-mode", "spring-boot:run") `
        -WorkingDirectory (Join-Path $script:RepositoryRoot "backend") `
        -WindowStyle Hidden `
        -RedirectStandardOutput $backendOutLog `
        -RedirectStandardError $backendErrorLog `
        -PassThru
    Restore-ScopedEnvironment

    Set-ScopedEnvironment -Name "VITE_TPV_BACKEND_URL" -Value "http://127.0.0.1:$BackendPort"
    Set-ScopedEnvironment -Name "VITE_TPV_TERMINAL_ID" -Value $TerminalId
    Set-ScopedEnvironment -Name "VITE_TPV_TERMINAL_CREDENTIAL" -Value $TerminalCredential

    Write-Host "Iniciando APP VENTA en http://127.0.0.1:$FrontendPort..."
    $script:FrontendProcess = Start-Process -FilePath $npmPath `
        -ArgumentList @("run", "dev", "--workspace", "@tpverp/app-venta", "--", "--host", "127.0.0.1", "--port", "$FrontendPort", "--strictPort") `
        -WorkingDirectory (Join-Path $script:RepositoryRoot "frontend") `
        -WindowStyle Hidden `
        -RedirectStandardOutput $frontendOutLog `
        -RedirectStandardError $frontendErrorLog `
        -PassThru
    Restore-ScopedEnvironment

    Wait-HttpEndpoint -Url "http://127.0.0.1:$BackendPort/actuator/health" `
        -Process $script:BackendProcess -LogPaths @($backendOutLog, $backendErrorLog) `
        -TimeoutSeconds 180 -ServiceName "El backend"
    Wait-HttpEndpoint -Url "http://127.0.0.1:$FrontendPort" `
        -Process $script:FrontendProcess -LogPaths @($frontendOutLog, $frontendErrorLog) `
        -TimeoutSeconds 60 -ServiceName "APP VENTA"

    $migrationApplied = Invoke-Psql -Database $databaseName -User $DatabaseOwner -Password $DatabasePassword `
        -Sql "select exists (select 1 from flyway_schema_history where version = '191' and success);"
    if ($migrationApplied -ne "t") {
        throw "La migracion V191 no consta como aplicada correctamente en la base temporal."
    }

    $loginBody = @{
        terminalId = $TerminalId
        terminalCredential = $TerminalCredential
        userName = "ADMIN"
        password = "0000"
    } | ConvertTo-Json
    $login = Invoke-RestMethod -Uri "http://127.0.0.1:$BackendPort/api/v1/auth/login" `
        -Method Post -ContentType "application/json" -Body $loginBody -TimeoutSec 15
    if ([string]::IsNullOrWhiteSpace([string]$login.accessToken)) {
        throw "El backend respondio al login, pero no devolvio un token de acceso."
    }

    Write-Host ""
    Write-Host "APP VENTA lista: http://127.0.0.1:$FrontendPort" -ForegroundColor Green
    Write-Host "Usuario demo: ADMIN"
    Write-Host "Contrasena demo: 0000"
    Write-Host "Base temporal: $databaseName"

    if ($CheckOnly) {
        Write-Host "Comprobacion automatica correcta: salud, migracion V191, terminal y login verificados." -ForegroundColor Green
    }
    else {
        Write-Host ""
        [void](Read-Host "Pulse Entrar para detener los servicios y eliminar la base temporal")
    }
}
finally {
    Restore-ScopedEnvironment

    if ($null -ne $script:FrontendProcess) {
        Stop-ProcessTree -RootProcessId $script:FrontendProcess.Id
    }
    if ($null -ne $script:BackendProcess) {
        Stop-ProcessTree -RootProcessId $script:BackendProcess.Id
    }

    if ($script:DatabaseCreated) {
        try {
            Remove-IsolatedDatabase -DatabaseName $databaseName
            Write-Host "Base temporal eliminada: $databaseName"
        }
        catch {
            Write-Warning "No se pudo eliminar la base temporal '$databaseName': $($_.Exception.Message)"
        }
    }

    if ($null -ne $script:TempRoot -and (Test-Path -LiteralPath $script:TempRoot)) {
        $resolvedTemp = [IO.Path]::GetFullPath($script:TempRoot)
        $systemTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if ($resolvedTemp.StartsWith($systemTemp, [StringComparison]::OrdinalIgnoreCase) -and
            (Split-Path -Leaf $resolvedTemp).StartsWith("tpv-erp-app-venta-", [StringComparison]::Ordinal)) {
            Remove-Item -LiteralPath $resolvedTemp -Recurse -Force -ErrorAction SilentlyContinue
            if (Test-Path -LiteralPath $resolvedTemp) {
                Write-Warning "No se pudo eliminar por completo el directorio temporal: $resolvedTemp"
            }
        }
    }
}
