[CmdletBinding()]
param(
    [string]$CertificatePath,
    [string]$BackendBaseUrl,
    [switch]$AllowAeatTest,
    [switch]$Preflight,
    [switch]$PromptForCertificatePassword,
    [switch]$PromptForAccessToken,
    [Guid]$CompanyId,
    [Guid]$InstallationId,
    [Guid]$RecordId,
    [string]$ExpectedReleaseId,
    [string]$Confirmation = "CONFIRMAR_AEAT_TEST",
    [string]$EvidenceDirectory = "target\verifactu-aeat-test-proof"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$root = Split-Path -Parent $PSScriptRoot
$accessTokenEnvironmentName = "TPV_VERIFACTU_AEAT_TEST_ACCESS_TOKEN"
$certificatePasswordEnvironmentName = "TPV_VERIFACTU_AEAT_TEST_CERTIFICATE_PASSWORD"

function Get-OptionalProperty {
    param($Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Get-BoundedText {
    param($Value, [int]$MaximumLength = 128)
    if ($null -eq $Value) { return $null }
    $text = [string]$Value
    if ($text.Length -gt $MaximumLength) { return "INVALID_OR_REDACTED" }
    return $text
}

function Get-SecureSecret {
    param(
        [string]$EnvironmentName,
        [switch]$Prompt,
        [string]$PromptText
    )
    $environmentValue = [Environment]::GetEnvironmentVariable($EnvironmentName)
    try {
        if (-not [string]::IsNullOrWhiteSpace($environmentValue)) {
            $secure = [System.Security.SecureString]::new()
            foreach ($character in $environmentValue.ToCharArray()) {
                $secure.AppendChar($character)
            }
            $secure.MakeReadOnly()
            return $secure
        }
        if ($Prompt) {
            return Read-Host -Prompt $PromptText -AsSecureString
        }
        return $null
    }
    finally {
        $environmentValue = $null
    }
}

function Convert-SecureSecretToPlainText {
    param([System.Security.SecureString]$Secret)
    $pointer = [IntPtr]::Zero
    try {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secret)
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        if ($pointer -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        }
    }
}

function Assert-SafeEvidenceSegment {
    param([string]$Value, [string]$Name)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z0-9._-]{1,128}$') {
        throw "$Name no es un identificador seguro para evidencia."
    }
}

function Test-ProvidedGuid {
    param($Value)
    if ($null -eq $Value) { return $false }
    return ([Guid]$Value) -ne [Guid]::Empty
}

function Test-Pkcs12Certificate {
    param([System.Security.SecureString]$Password)
    if ([string]::IsNullOrWhiteSpace($CertificatePath)) {
        if ($null -ne $Password) {
            throw "La contrasena del certificado requiere CertificatePath."
        }
        return
    }
    if ($null -eq $Password) {
        throw "CertificatePath requiere la contrasena segura del entorno o -PromptForCertificatePassword."
    }
    if (-not (Test-Path -LiteralPath $CertificatePath -PathType Leaf)) {
        throw "No existe el certificado PKCS#12 indicado."
    }
    if ([IO.Path]::GetExtension($CertificatePath).ToLowerInvariant() -notin @(".p12", ".pfx")) {
        throw "El certificado AEAT TEST debe ser PKCS#12 (.p12 o .pfx)."
    }
    $certificate = $null
    try {
        $certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
            $CertificatePath, $Password,
            [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet)
        if (-not $certificate.HasPrivateKey) { throw "El PKCS#12 no contiene clave privada." }
        $now = [DateTime]::UtcNow
        if ($certificate.NotBefore.ToUniversalTime() -gt $now -or
            $certificate.NotAfter.ToUniversalTime() -lt $now) {
            throw "El certificado PKCS#12 esta caducado o aun no es valido."
        }
    }
    catch {
        throw "No se pudo validar el certificado PKCS#12 AEAT TEST."
    }
    finally {
        if ($null -ne $certificate) { $certificate.Dispose() }
    }
}

$certificatePassword = $null
$accessToken = $null
$accessTokenPlain = $null
$manifest = $null
$resolvedEvidence = $null
try {
    $certificatePasswordEnvironmentValue = [Environment]::GetEnvironmentVariable($certificatePasswordEnvironmentName)
    try {
        $certificateSecretRequested = -not [string]::IsNullOrWhiteSpace($CertificatePath) -or
            $PromptForCertificatePassword -or
            -not [string]::IsNullOrWhiteSpace($certificatePasswordEnvironmentValue)
    }
    finally {
        $certificatePasswordEnvironmentValue = $null
    }
    if ($certificateSecretRequested) {
        $certificatePassword = Get-SecureSecret $certificatePasswordEnvironmentName `
            -Prompt:$PromptForCertificatePassword -PromptText "Contrasena del certificado PKCS#12 AEAT TEST"
    }

    if ($Preflight) {
        Test-Pkcs12Certificate $certificatePassword
        $accessTokenEnvironmentValue = [Environment]::GetEnvironmentVariable($accessTokenEnvironmentName)
        try {
            $accessTokenProvided = -not [string]::IsNullOrWhiteSpace($accessTokenEnvironmentValue)
        }
        finally {
            $accessTokenEnvironmentValue = $null
        }
        if ($AllowAeatTest -or $PromptForAccessToken -or $accessTokenProvided -or
            -not [string]::IsNullOrWhiteSpace($BackendBaseUrl) -or
            (Test-ProvidedGuid $CompanyId) -or (Test-ProvidedGuid $InstallationId) -or
            (Test-ProvidedGuid $RecordId)) {
            throw "El preflight es explicito y no acepta parametros de ejecucion de red."
        }
        $releaseId = if ([string]::IsNullOrWhiteSpace($ExpectedReleaseId)) { "preflight" } else { $ExpectedReleaseId.Trim() }
        Assert-SafeEvidenceSegment $releaseId "ExpectedReleaseId"
        $resolvedEvidence = [IO.Path]::GetFullPath((Join-Path $root (Join-Path $EvidenceDirectory $releaseId)))
        [void](New-Item -ItemType Directory -Path $resolvedEvidence -Force)
        $manifest = [ordered]@{
            generatedAt = [DateTime]::UtcNow.ToString("o")
            label = "aeat-test"
            mode = "PREFLIGHT"
            releaseId = $releaseId
            endpointEnvironment = "TEST"
            transport = "AEAT"
            networkSubmission = "NOT_EXECUTED"
            certificateProvided = (-not [string]::IsNullOrWhiteSpace($CertificatePath))
            certificateMaterial = "REDACTED"
            gateAccepted = $false
            note = "Preflight explicito: no ejecuta el endpoint ni contacta AEAT."
        }
        $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $resolvedEvidence "manifest.json") -Encoding UTF8
        @("# AEAT TEST", "", "- Modo: PREFLIGHT explicito", "- Entorno: TEST", "- Produccion: bloqueada", "- Transporte: no ejecutado", "- El certificado, la contrasena y la clave no se escriben en la evidencia.") |
            Set-Content -LiteralPath (Join-Path $resolvedEvidence "README.md") -Encoding UTF8
        Write-Host "Preflight AEAT TEST generado (red no ejecutada). Evidencia: $resolvedEvidence" -ForegroundColor Yellow
        return
    }

    if (-not $AllowAeatTest) { throw "La ejecucion AEAT TEST exige -AllowAeatTest explicito." }
    if ($CertificatePath -or $certificatePassword -or $PromptForCertificatePassword) {
        throw "CertificatePath y su contrasena solo se admiten en PREFLIGHT; EXECUTED usa el certificado configurado en backend."
    }
    if ([string]::IsNullOrWhiteSpace($BackendBaseUrl) -or
        -not (Test-ProvidedGuid $CompanyId) -or -not (Test-ProvidedGuid $InstallationId) -or
        [string]::IsNullOrWhiteSpace($ExpectedReleaseId)) {
        throw "La ejecucion AEAT TEST requiere BackendBaseUrl, el secreto $accessTokenEnvironmentName o -PromptForAccessToken, CompanyId, InstallationId y ExpectedReleaseId."
    }
    Assert-SafeEvidenceSegment $ExpectedReleaseId.Trim() "ExpectedReleaseId"
    if ($Confirmation -cne "CONFIRMAR_AEAT_TEST") { throw "Confirmation debe ser exactamente CONFIRMAR_AEAT_TEST." }
    $baseUri = [Uri]$BackendBaseUrl
    if ($baseUri.Scheme -ne "https" -and $baseUri.Host -notin @("127.0.0.1", "localhost")) {
        throw "El backend AEAT TEST debe ser HTTPS salvo un backend local de DEV."
    }

    $payload = [ordered]@{
        companyId = $CompanyId.ToString()
        installationId = $InstallationId.ToString()
        expectedReleaseId = $ExpectedReleaseId.Trim()
        confirmation = $Confirmation
    }
    if (Test-ProvidedGuid $RecordId) { $payload.recordId = $RecordId.ToString() }
    $accessToken = Get-SecureSecret $accessTokenEnvironmentName -Prompt:$PromptForAccessToken `
        -PromptText "Access token AEAT TEST"
    if ($null -eq $accessToken) {
        throw "Falta el secreto $accessTokenEnvironmentName o -PromptForAccessToken."
    }
    $accessTokenPlain = Convert-SecureSecretToPlainText $accessToken
    $headers = @{ Authorization = "Bearer $accessTokenPlain" }
    $uri = "$($baseUri.AbsoluteUri.TrimEnd('/'))/api/v1/dev/fiscal-aeat-test/dispatch-next"
    try {
        $response = Invoke-RestMethod -Uri $uri -Headers $headers -Method Post -ContentType "application/json" `
            -Body ($payload | ConvertTo-Json -Compress) -TimeoutSec 60

        # Persist bounded, redacted metadata before evaluating the gate. A
        # rejected or malformed AEAT response is evidence, never success.
        $responseEvidence = Get-OptionalProperty $response "evidence"
        $responseReleaseIdRaw = [string](Get-OptionalProperty $responseEvidence "releaseId")
        $responseReleaseId = Get-BoundedText $responseReleaseIdRaw
        $responseStatusRaw = [string](Get-OptionalProperty $response "status")
        $responseStatus = Get-BoundedText $responseStatusRaw
        $resolvedEvidence = [IO.Path]::GetFullPath((Join-Path $root (Join-Path $EvidenceDirectory $ExpectedReleaseId.Trim())))
        [void](New-Item -ItemType Directory -Path $resolvedEvidence -Force)
        $manifest = [ordered]@{
            generatedAt = [DateTime]::UtcNow.ToString("o")
            label = "aeat-test"
            mode = "EXECUTED"
            releaseId = $ExpectedReleaseId.Trim()
            responseReleaseId = $responseReleaseId
            processed = [bool](Get-OptionalProperty $response "processed")
            status = $responseStatus
            errorCode = Get-BoundedText (Get-OptionalProperty $response "errorCode")
            endpointEnvironment = Get-BoundedText (Get-OptionalProperty $responseEvidence "endpointEnvironment")
            transport = Get-BoundedText (Get-OptionalProperty $responseEvidence "transport")
            companyId = Get-BoundedText (Get-OptionalProperty $responseEvidence "companyId")
            installationId = Get-BoundedText (Get-OptionalProperty $responseEvidence "installationId")
            recordId = Get-BoundedText (Get-OptionalProperty $responseEvidence "recordId")
            networkRequestIssued = [bool](Get-OptionalProperty $responseEvidence "networkRequestIssued")
            certificateMaterial = "REDACTED"
            gateAccepted = $false
        }
        $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $resolvedEvidence "manifest.json") -Encoding UTF8

        $failure = $null
        if ($null -eq $response -or $response.PSObject.Properties.Name -notcontains "processed") {
            $failure = "La respuesta del endpoint AEAT TEST no es valida."
        } elseif ($responseStatusRaw -cne "ACEPTADO") {
            $failure = "El gate AEAT TEST solo acepta status ACEPTADO."
        } elseif (-not [bool](Get-OptionalProperty $response "processed")) {
            $failure = "El endpoint AEAT TEST se ejecuto pero no proceso ningun registro pendiente."
        } elseif ($responseReleaseId -cne $ExpectedReleaseId.Trim()) {
            $failure = "La evidencia del endpoint no coincide con ExpectedReleaseId."
        } elseif ((Get-OptionalProperty $responseEvidence "endpointEnvironment") -cne "TEST" -or
            (Get-OptionalProperty $responseEvidence "transport") -cne "AEAT" -or
            (Get-OptionalProperty $responseEvidence "companyId") -cne $CompanyId.ToString() -or
            (Get-OptionalProperty $responseEvidence "installationId") -cne $InstallationId.ToString() -or
            -not [bool](Get-OptionalProperty $responseEvidence "certificateMaterialRedacted")) {
            $failure = "La evidencia del endpoint no confirma el alcance AEAT TEST esperado."
        } elseif (-not [bool](Get-OptionalProperty $responseEvidence "networkRequestIssued")) {
            $failure = "El endpoint AEAT TEST no abrio transporte de red; no se presenta como prueba real."
        }
        if ($null -ne $failure) {
            $manifest.gateFailure = $failure
            $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $resolvedEvidence "manifest.json") -Encoding UTF8
            throw $failure
        }
        $manifest.gateAccepted = $true
        $manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $resolvedEvidence "manifest.json") -Encoding UTF8
        Write-Host "AEAT TEST endpoint ejecutado. Evidencia: $resolvedEvidence" -ForegroundColor Green
    }
    catch {
        if ($null -ne $manifest -and $null -ne $resolvedEvidence -and
            ($_.Exception.Message -like "El gate AEAT TEST*" -or
             $_.Exception.Message -like "El endpoint AEAT TEST*" -or
             $_.Exception.Message -like "La evidencia del endpoint*")) { throw }
        throw "No se ejecuto el endpoint AEAT TEST."
    }
}
finally {
    $accessTokenPlain = $null
    if ($null -ne $accessToken) { $accessToken.Dispose() }
    if ($null -ne $certificatePassword) { $certificatePassword.Dispose() }
}
