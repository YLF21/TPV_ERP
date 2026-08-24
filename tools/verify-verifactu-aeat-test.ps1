[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$CertificatePath,
    [Parameter(Mandatory)]
    [string]$CertificatePassword,
    [Parameter(Mandatory)]
    [string]$BackendBaseUrl,
    [Parameter(Mandatory)]
    [string]$AccessToken,
    [switch]$AllowAeatTest,
    [string]$EvidenceDirectory = "target\verifactu-dev-proof\aeat-test"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$root = Split-Path -Parent $PSScriptRoot
$resolvedEvidence = [IO.Path]::GetFullPath((Join-Path $root $EvidenceDirectory))

if (-not $AllowAeatTest) {
    throw "La prueba AEAT TEST exige -AllowAeatTest explicito; nunca se ejecuta por defecto."
}
if ([string]::IsNullOrWhiteSpace($CertificatePassword)) {
    throw "CertificatePassword no puede estar vacio."
}
if (-not (Test-Path -LiteralPath $CertificatePath -PathType Leaf)) {
    throw "No existe el certificado PKCS#12 indicado."
}
$certificateExtension = [IO.Path]::GetExtension($CertificatePath).ToLowerInvariant()
if ($certificateExtension -notin @(".p12", ".pfx")) {
    throw "El certificado AEAT TEST debe ser PKCS#12 (.p12 o .pfx)."
}
$certificate = $null
try {
    $certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
        $CertificatePath,
        $CertificatePassword,
        [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet)
    if (-not $certificate.HasPrivateKey) {
        throw "El PKCS#12 no contiene clave privada."
    }
    $now = [DateTime]::UtcNow
    $notYetValid = $certificate.NotBefore.ToUniversalTime() -gt $now
    $expired = $certificate.NotAfter.ToUniversalTime() -lt $now
    if ($notYetValid -or $expired) {
        throw "El certificado PKCS#12 esta caducado o aun no es valido."
    }
}
catch {
    throw "No se pudo validar el certificado PKCS#12 AEAT TEST: $($_.Exception.Message)"
}
finally {
    if ($null -ne $certificate) {
        $certificate.Dispose()
    }
}
$baseUri = [Uri]$BackendBaseUrl
if ($baseUri.Scheme -ne "https" -and $baseUri.Host -notin @("127.0.0.1", "localhost")) {
    throw "El backend AEAT TEST debe ser HTTPS salvo un backend local de DEV."
}

# This script is deliberately a gate, not an implicit network sender. A live
# submission still requires a configured certificate in APP GESTION and an
# operator-created fiscal record/queue item in the isolated environment.
[void](New-Item -ItemType Directory -Path $resolvedEvidence -Force)
$headers = @{ Authorization = "Bearer $AccessToken" }
$status = Invoke-RestMethod -Uri "$($baseUri.AbsoluteUri.TrimEnd('/'))/api/v1/verifactu/admin/status" `
    -Headers $headers -Method Get -TimeoutSec 20
$endpointMode = if ($status.PSObject.Properties.Name -contains "endpointMode") {
    [string]$status.endpointMode
} else {
    ""
}
if ($endpointMode -and $endpointMode -notlike "TEST*") {
    throw "El backend no esta en entorno TEST; se aborta antes de cualquier transporte."
}
$proof = [ordered]@{
    generatedAt = [DateTime]::UtcNow.ToString("o")
    label = "aeat-test"
    endpointEnvironment = "TEST"
    certificateFileName = [IO.Path]::GetFileName($CertificatePath)
    certificatePasswordProvided = $true
    status = $status
    networkSubmission = "NOT_EXECUTED"
    note = "Preflight seguro: requiere certificado y registro/cola preparados por un operador. No se contacta AEAT desde este gate."
}
$proof | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath `
    (Join-Path $resolvedEvidence "aeat-test-preflight.json") -Encoding UTF8
@(
    "# AEAT TEST"
    ""
    "- Etiqueta: aeat-test"
    "- Entorno permitido: TEST"
    "- Produccion: bloqueada"
    "- Transporte: no ejecutado por este preflight"
    "- La contrasena no se escribe en la evidencia."
    "- Para ejecutar un envio real, importar primero el certificado desde APP GESTION y crear un registro fiscal DEV pendiente."
) | Set-Content -LiteralPath (Join-Path $resolvedEvidence "README.md") -Encoding UTF8
Write-Host "Preflight AEAT TEST completado. Evidencia: $resolvedEvidence" -ForegroundColor Yellow
