[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('request', 'receive', 'activate', 'status', 'cancel')]
    [string] $Action
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-PlainFile([string] $Value, [string] $Description) {
    $full = [IO.Path]::GetFullPath($Value)
    if (-not [IO.Path]::IsPathRooted($full)) { throw "$Description debe tener ruta absoluta." }
    $item = Get-Item -LiteralPath $full -Force -ErrorAction Stop
    if ($item.PSIsContainer) { throw "$Description no es un archivo." }
    $current = $full
    while ($true) {
        $part = Get-Item -LiteralPath $current -Force -ErrorAction Stop
        if (($part.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Description contiene un enlace o reparse point."
        }
        $parent = [IO.Path]::GetDirectoryName($current)
        if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $current) { break }
        $current = $parent
    }
    return $full
}

function Assert-PlainDirectory([string] $Value, [string] $Description) {
    $full = [IO.Path]::GetFullPath($Value)
    if (-not [IO.Path]::IsPathRooted($full)) { throw "$Description debe tener ruta absoluta." }
    $item = Get-Item -LiteralPath $full -Force -ErrorAction Stop
    if (-not $item.PSIsContainer) { throw "$Description no es un directorio." }
    $current = $full
    while ($true) {
        $part = Get-Item -LiteralPath $current -Force -ErrorAction Stop
        if (($part.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Description contiene un enlace o reparse point."
        }
        $parent = [IO.Path]::GetDirectoryName($current)
        if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $current) { break }
        $current = $parent
    }
    return $full
}

function Read-InstalledService([string] $XmlPath) {
    $settings = [Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $settings.MaxCharactersInDocument = 65536
    $reader = [Xml.XmlReader]::Create($XmlPath, $settings)
    try {
        $document = [Xml.XmlDocument]::new()
        $document.XmlResolver = $null
        $document.Load($reader)
    } finally { $reader.Dispose() }
    if ([string]$document.service.id -cne 'TPVERPBackend') { throw 'El servicio instalado no es TPVERPBackend.' }
    $arguments = [string]$document.service.arguments
    $match = [regex]::Match($arguments, '(?i)(?:^|\s)-jar\s+"([^"\r\n]+\.jar)"(?:\s|$)')
    if (-not $match.Success) { throw 'El servicio no declara un JAR de release seguro.' }
    $addressMatch = [regex]::Match($arguments, '(?i)(?:^|\s)--server\.address=(127\.0\.0\.1|::1)(?:\s|$)')
    $portMatch = [regex]::Match($arguments, '(?i)(?:^|\s)--server\.port=([0-9]{1,5})(?:\s|$)')
    $port = 0
    if (-not $addressMatch.Success -or -not $portMatch.Success -or
            -not [int]::TryParse($portMatch.Groups[1].Value, [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
        throw 'El servicio no declara una dirección loopback y puerto válidos.'
    }
    $hostText = if ($addressMatch.Groups[1].Value -eq '::1') { '[::1]' } else { '127.0.0.1' }
    $serviceAccount = [string]$document.service.serviceaccount.username
    if ($serviceAccount -cnotin @('NT SERVICE\TPVERPBackend', 'LocalSystem', 'NT AUTHORITY\LocalService', 'NT AUTHORITY\NetworkService')) {
        throw 'La cuenta del servicio instalado no está permitida para mantenimiento.'
    }
    return @{
        Java = [string]$document.service.executable
        Jar = $match.Groups[1].Value
        Port = $port
        LocalUrl = "http://${hostText}:$port"
        ServiceAccount = $serviceAccount
    }
}

function Read-InstalledHttpsService([string] $ProgramDataRoot) {
    $httpsRoot = Join-Path $ProgramDataRoot 'TPV ERP\HTTPS'
    $xml = Assert-PlainFile (Join-Path $httpsRoot 'TPVERPHTTPS.xml') 'El XML del servicio HTTPS'
    $settings = [Xml.XmlReaderSettings]::new()
    $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
    $settings.XmlResolver = $null
    $settings.MaxCharactersInDocument = 65536
    $reader = [Xml.XmlReader]::Create($xml, $settings)
    try {
        $document = [Xml.XmlDocument]::new()
        $document.XmlResolver = $null
        $document.Load($reader)
    } finally { $reader.Dispose() }
    if ([string]$document.service.id -cne 'TPVERPHTTPS' -or
            [string]$document.service.serviceaccount.username -cne 'NT SERVICE\TPVERPHTTPS' -or
            -not [string]::Equals([string]$document.service.workingdirectory, $httpsRoot,
                [StringComparison]::OrdinalIgnoreCase)) {
        throw 'El servicio HTTPS instalado no tiene identidad o directorio esperado.'
    }
    Assert-PlainDirectory $httpsRoot 'La instalación HTTPS' | Out-Null
    return @{ Account = 'NT SERVICE\TPVERPHTTPS'; TlsDirectory = (Join-Path $httpsRoot 'tls') }
}

function Resolve-ServiceSid([string] $ServiceAccount) {
    switch ($ServiceAccount) {
        'LocalSystem' { 'S-1-5-18' }
        'NT AUTHORITY\LocalService' { 'S-1-5-19' }
        'NT AUTHORITY\NetworkService' { 'S-1-5-20' }
        default { ([Security.Principal.NTAccount]::new($ServiceAccount)).Translate([Security.Principal.SecurityIdentifier]).Value }
    }
}

function Set-TransferDirectoryAcl([string] $Directory, [string] $ServiceAccount, [switch] $Public, [switch] $ServiceReadOnly) {
    $acl = New-Object System.Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    $full = [System.Security.AccessControl.FileSystemRights]::FullControl
    $inherit = [System.Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'
    $sids = @('S-1-5-18', 'S-1-5-32-544')
    if (-not $Public) {
        $serviceSid = Resolve-ServiceSid $ServiceAccount
        $sids += $serviceSid
    }
    foreach ($sidText in ($sids | Select-Object -Unique)) {
        $sid = [Security.Principal.SecurityIdentifier]::new($sidText)
        $rights = if ($ServiceReadOnly -and $sidText -eq $serviceSid) {
            [Security.AccessControl.FileSystemRights]::ReadAndExecute
        } else { $full }
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, $rights, $inherit,
            [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow))
    }
    if ($Public) {
        $users = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-545')
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($users,
            [Security.AccessControl.FileSystemRights]::ReadAndExecute, $inherit,
            [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow))
    }
    Set-Acl -LiteralPath $Directory -AclObject $acl
}

function Assert-ExactSecretAcl([string] $Path, [string] $ServiceAccount, [switch] $File, [switch] $ServiceReadOnly) {
    if ($File) { Assert-PlainFile $Path 'El secreto protegido' | Out-Null }
    else { Assert-PlainDirectory $Path 'El directorio de secretos' | Out-Null }
    $acl = Get-Acl -LiteralPath $Path
    if (-not $acl.AreAccessRulesProtected) { throw 'El secreto hereda permisos NTFS no autorizados.' }
    $serviceSid = Resolve-ServiceSid $ServiceAccount
    $expectedSids = @('S-1-5-18', 'S-1-5-32-544', $serviceSid) | Select-Object -Unique
    $expectedFlags = if ($File) { [Security.AccessControl.InheritanceFlags]::None }
        else { [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit' }
    $rules = @($acl.Access)
    if ($rules.Count -ne @($expectedSids).Count) { throw 'El secreto contiene permisos NTFS adicionales.' }
    foreach ($rule in $rules) {
        $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        $expectedRights = if ($ServiceReadOnly -and $sid -eq $serviceSid) {
            [Security.AccessControl.FileSystemRights]::ReadAndExecute
        } else { [Security.AccessControl.FileSystemRights]::FullControl }
        $expectedRights = $expectedRights -bor [Security.AccessControl.FileSystemRights]::Synchronize
        if ($sid -notin $expectedSids -or $rule.IsInherited -or
                $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
                $rule.FileSystemRights -ne $expectedRights -or
                $rule.InheritanceFlags -ne $expectedFlags -or
                $rule.PropagationFlags -ne [Security.AccessControl.PropagationFlags]::None) {
            throw 'El secreto contiene permisos NTFS distintos de los autorizados.'
        }
    }
}

function Prepare-SecretRoot([string] $Directory, [string] $ServiceAccount, [switch] $ServiceReadOnly) {
    if (-not (Test-Path -LiteralPath $Directory)) { return }
    Assert-PlainDirectory $Directory 'El directorio de secretos' | Out-Null
    $empty = $null -eq (Get-ChildItem -LiteralPath $Directory -Force | Select-Object -First 1)
    if ($empty) { Set-TransferDirectoryAcl $Directory $ServiceAccount -ServiceReadOnly:$ServiceReadOnly }
    Assert-ExactSecretAcl $Directory $ServiceAccount -ServiceReadOnly:$ServiceReadOnly
}

function Ensure-KeyDirectory([string] $Directory, [string] $ServiceAccount, [string] $RequestedAction) {
    $full = [IO.Path]::GetFullPath($Directory)
    $created = -not (Test-Path -LiteralPath $full)
    $ancestor = $full
    while (-not (Test-Path -LiteralPath $ancestor)) {
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
        if ([string]::IsNullOrWhiteSpace($ancestor)) { throw 'El directorio de claves no tiene raíz existente.' }
    }
    Assert-PlainDirectory $ancestor 'La raíz del directorio de claves' | Out-Null
    if ($created) { New-Item -ItemType Directory -Path $full -Force | Out-Null }
    Assert-PlainDirectory $full 'El directorio de claves' | Out-Null
    if ($RequestedAction -eq 'request' -and (Test-Path -LiteralPath (Join-Path $full 'installation-private.dpapi'))) {
        throw 'Este servidor ya tiene identidad de instalación; no se puede generar una solicitud de destino.'
    }
    $empty = $null -eq (Get-ChildItem -LiteralPath $full -Force | Select-Object -First 1)
    if ($created -or $empty) {
        Set-TransferDirectoryAcl $full $ServiceAccount
    } else {
        $allowed = @('S-1-5-18', 'S-1-5-32-544', (Resolve-ServiceSid $ServiceAccount),
            [Security.Principal.WindowsIdentity]::GetCurrent().User.Value)
        foreach ($rule in (Get-Acl -LiteralPath $full).Access) {
            if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
                    ($rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::ReadData) -eq 0) { continue }
            $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
            if ($sid -notin $allowed) { throw 'El directorio de claves permite lectura a otra identidad.' }
        }
    }
    return $full
}

function Ensure-PublicDirectory([string] $Directory) {
    $ancestor = [IO.Path]::GetFullPath($Directory)
    while (-not (Test-Path -LiteralPath $ancestor)) {
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
        if ([string]::IsNullOrWhiteSpace($ancestor)) { throw 'El directorio público no tiene raíz existente.' }
    }
    Assert-PlainDirectory $ancestor 'La raíz del directorio público' | Out-Null
    if (-not (Test-Path -LiteralPath $Directory)) { New-Item -ItemType Directory -Path $Directory -Force | Out-Null }
    Assert-PlainDirectory $Directory 'El directorio público de traslado' | Out-Null
    Set-TransferDirectoryAcl $Directory '' -Public
}

function Ensure-Environment([string] $Name, [string] $Label, [switch] $Secret) {
    if (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($Name, 'Process'))) { return }
    $installedValue = [Environment]::GetEnvironmentVariable($Name, 'Machine')
    if (-not [string]::IsNullOrWhiteSpace($installedValue)) {
        [Environment]::SetEnvironmentVariable($Name, $installedValue, 'Process')
        return
    }
    if ($Secret) {
        $secure = Read-Host $Label -AsSecureString
        $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try {
            $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
            if ([string]::IsNullOrWhiteSpace($plain)) { throw "$Label es obligatorio." }
            [Environment]::SetEnvironmentVariable($Name, $plain, 'Process')
        } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    } else {
        $value = Read-Host $Label
        if ([string]::IsNullOrWhiteSpace($value)) { throw "$Label es obligatorio." }
        [Environment]::SetEnvironmentVariable($Name, $value.Trim(), 'Process')
    }
}

function Read-SelectedBackup {
    Add-Type -AssemblyName System.Windows.Forms
    $dialog = [Windows.Forms.OpenFileDialog]::new()
    $dialog.Title = 'Seleccionar paquete final de traslado'
    $dialog.Filter = 'Paquete de copia (*.tpvb)|*.tpvb'
    $dialog.CheckFileExists = $true
    $dialog.Multiselect = $false
    try {
        if ($dialog.ShowDialog() -ne [Windows.Forms.DialogResult]::OK) { throw 'Selección de paquete cancelada.' }
        $backup = Assert-PlainFile $dialog.FileName 'El paquete de traslado'
        if ([IO.Path]::GetExtension($backup) -cne '.tpvb') { throw 'El paquete debe ser .tpvb.' }
        Assert-PlainFile "$backup.capsule" 'La cápsula de traslado' | Out-Null
        Assert-PlainFile "$backup.transfer.json" 'El manifiesto de traslado' | Out-Null
        return $backup
    } finally { $dialog.Dispose() }
}

function Write-PublicStatus([string] $Directory, [object] $Result, [string] $RequestedAction) {
    $public = [ordered]@{ action = $RequestedAction; checkedAt = [DateTimeOffset]::UtcNow.ToString('o') }
    foreach ($key in @('state', 'transferId', 'destinationKeyFingerprint', 'destinationPublicKey', 'destinationProof', 'releaseId')) {
        if ($null -ne $Result.PSObject.Properties[$key]) { $public[$key] = $Result.$key }
    }
    $statusFile = Join-Path $Directory 'status.json'
    $tempFile = Join-Path $Directory ([guid]::NewGuid().ToString('N') + '.status.tmp')
    [IO.File]::WriteAllText($tempFile, ($public | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $tempFile -Destination $statusFile -Force
}

function Publish-DestinationRequest([string] $PrivateDirectory, [string] $PublicDirectory) {
    $source = Assert-PlainFile (Join-Path $PrivateDirectory 'destination-request.json') 'La solicitud generada'
    if ((Get-Item -LiteralPath $source).Length -gt 32768) { throw 'La solicitud generada supera el tamaño permitido.' }
    $request = Get-Content -LiteralPath $source -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop
    if ($request.state -cne 'DESTINATION_READY' -or
            [string]::IsNullOrWhiteSpace([string]$request.destinationPublicKey) -or
            [string]::IsNullOrWhiteSpace([string]$request.destinationProof) -or
            ([string]$request.destinationPublicKey).Length -gt 2048 -or
            ([string]$request.destinationProof).Length -gt 2048) {
        throw 'La solicitud generada no contiene un preflight público válido.'
    }
    $public = [ordered]@{
        state = 'DESTINATION_READY'
        destinationPublicKey = [string]$request.destinationPublicKey
        destinationProof = [string]$request.destinationProof
    }
    foreach ($key in @('destinationKeyFingerprint', 'releaseId')) {
        if ($null -ne $request.PSObject.Properties[$key] -and ([string]$request.$key).Length -le 256) {
            $public[$key] = [string]$request.$key
        }
    }
    $temp = Join-Path $PublicDirectory ([guid]::NewGuid().ToString('N') + '.request.tmp')
    $target = Join-Path $PublicDirectory 'destination-request.json'
    [IO.File]::WriteAllText($temp, ($public | ConvertTo-Json -Depth 3), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temp -Destination $target -Force
}

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'El mantenimiento de traslado solo funciona en Windows.' }
if ($env:ProgramData -notmatch '^[A-Za-z]:\\ProgramData\\?$') { throw 'ProgramData no tiene una ruta instalada válida.' }
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    # No secret or arbitrary path is passed to the elevated process. A visible console is needed for maintenance input.
    $self = Assert-PlainFile $PSCommandPath 'El asistente de mantenimiento'
    Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -Verb RunAs -WindowStyle Normal -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$self`" -Action $Action"
    return
}

$failed = $false
$activationResult = $null
try {
    $installRoot = Assert-PlainDirectory (Join-Path $env:ProgramData 'TPV ERP\Backend') 'La instalación backend'
    $serviceXml = Assert-PlainFile (Join-Path $installRoot 'TPVERPBackend.xml') 'El XML del servicio'
    $service = Read-InstalledService $serviceXml
    $java = Assert-PlainFile $service.Java 'Java instalado'
    $jar = Assert-PlainFile $service.Jar 'El JAR instalado'
    $releasesRoot = Assert-PlainDirectory (Join-Path $installRoot 'releases') 'Las releases instaladas'
    $releaseDirectory = Assert-PlainDirectory ([IO.Path]::GetDirectoryName($jar)) 'La release activa'
    if (-not [string]::Equals([IO.Path]::GetDirectoryName($releaseDirectory), $releasesRoot,
            [StringComparison]::OrdinalIgnoreCase)) { throw 'El JAR no pertenece a una release instalada.' }
    if (-not [string]::Equals($PSScriptRoot, (Join-Path $releaseDirectory 'tools'),
            [StringComparison]::OrdinalIgnoreCase)) { throw 'El asistente no pertenece a la release activa.' }
    $verifier = Assert-PlainFile (Join-Path $PSScriptRoot 'Test-TpvBackendProductionBundle.ps1') 'El verificador de release'
    $wrapper = Assert-PlainFile (Join-Path $PSScriptRoot 'Invoke-TpvServerTransfer.ps1') 'El ejecutor de traslado'
    $unboundBundle = & $verifier -BundleDirectory $releaseDirectory -AsObject
    $releaseId = [string]$unboundBundle.ReleaseId
    if ($releaseId -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$') { throw 'release.id no es válido.' }
    $bundle = & $verifier -BundleDirectory $releaseDirectory -ExpectedReleaseId $releaseId -AsObject
    if (-not [string]::Equals($bundle.Jar, $jar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'El JAR verificado no coincide con el servicio.'
    }

    $destination = Join-Path $env:ProgramData 'TPV ERP\transfer\destination'
    $publicDirectory = Join-Path $env:ProgramData 'TPV ERP\transfer\public'
    [Environment]::SetEnvironmentVariable('TPV_BACKEND_PORT', [string]$service.Port, 'Process')
    [Environment]::SetEnvironmentVariable('TPV_BACKEND_SERVICE_ACCOUNT', $service.ServiceAccount, 'Process')
    if ($Action -in @('request', 'receive', 'activate')) {
        $https = Read-InstalledHttpsService $env:ProgramData
        [Environment]::SetEnvironmentVariable('TPV_TRANSFER_HTTPS_SERVICE_ACCOUNT', $https.Account, 'Process')
    }
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('TPV_TRANSFER_TLS_REPROVISIONED', 'Process'))) {
        $tlsReprovisioned = [Environment]::GetEnvironmentVariable('TPV_TRANSFER_TLS_REPROVISIONED', 'Machine')
        if (-not [string]::IsNullOrWhiteSpace($tlsReprovisioned)) {
            [Environment]::SetEnvironmentVariable('TPV_TRANSFER_TLS_REPROVISIONED', $tlsReprovisioned, 'Process')
        }
    }
    Ensure-Environment 'TPV_KEY_DIRECTORY' 'Directorio de claves del servidor destino'
    if ($Action -in @('request', 'receive', 'activate', 'cancel')) {
        Ensure-Environment 'TPV_DB_URL' 'URL JDBC de PostgreSQL destino'
        Ensure-Environment 'TPV_DB_USERNAME' 'Usuario PostgreSQL destino'
        Ensure-Environment 'TPV_DB_PASSWORD' 'Contraseña PostgreSQL destino' -Secret
    }
    if ($Action -in @('receive', 'activate', 'cancel')) {
        Ensure-Environment 'TPV_LICENSE_SAAS_URL' 'URL SaaS de licencias'
    }
    if ($Action -in @('receive', 'activate')) {
        Ensure-Environment 'TPV_BACKUP_DIRECTORY' 'Directorio de copias del servidor destino'
        Ensure-Environment 'TPV_PRODUCT_IMAGE_DIRECTORY' 'Directorio de imágenes de producto destino'
        Ensure-Environment 'TPV_DOCUMENT_TEMPLATE_DIRECTORY' 'Directorio de plantillas destino'
        Ensure-Environment 'TPV_VERIFACTU_SECRET_DIRECTORY' 'Directorio de secretos VeriFactu destino'
        Prepare-SecretRoot ([Environment]::GetEnvironmentVariable('TPV_VERIFACTU_SECRET_DIRECTORY', 'Process')) $service.ServiceAccount
    }
    if ($Action -in @('receive', 'activate') -or
            ($Action -eq 'request' -and [Environment]::GetEnvironmentVariable('TPV_TRANSFER_TLS_REPROVISIONED', 'Process') -eq 'true')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('TPV_TRANSFER_TLS_DIRECTORY', 'Process')) -and
                [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('TPV_TRANSFER_TLS_DIRECTORY', 'Machine'))) {
            [Environment]::SetEnvironmentVariable('TPV_TRANSFER_TLS_DIRECTORY',
                (Join-Path $env:ProgramData 'TPV ERP\HTTPS\tls'), 'Process')
        }
        Ensure-Environment 'TPV_TRANSFER_TLS_DIRECTORY' 'Directorio TLS del destino'
        $tlsDirectory = [IO.Path]::GetFullPath([Environment]::GetEnvironmentVariable('TPV_TRANSFER_TLS_DIRECTORY', 'Process'))
        if (-not [string]::Equals($tlsDirectory, [IO.Path]::GetFullPath($https.TlsDirectory),
                [StringComparison]::OrdinalIgnoreCase)) {
            throw 'El directorio TLS no coincide con el servicio HTTPS instalado.'
        }
        Prepare-SecretRoot $tlsDirectory $https.Account -ServiceReadOnly
        $tlsPrivateKey = Join-Path $tlsDirectory 'private-key.pem'
        if (Test-Path -LiteralPath $tlsPrivateKey) {
            Assert-ExactSecretAcl $tlsPrivateKey $https.Account -File -ServiceReadOnly
        } elseif ($Action -eq 'request') {
            throw 'La reprovisión TLS requiere una clave HTTPS local protegida antes de generar la solicitud.'
        }
    }
    if ($Action -in @('request', 'receive', 'activate')) { Ensure-Environment 'TPV_TRANSFER_PUBLIC_HOST' 'Nombre público HTTPS del destino' }
    if ($Action -eq 'request') {
        Ensure-KeyDirectory ([Environment]::GetEnvironmentVariable('TPV_KEY_DIRECTORY', 'Process')) $service.ServiceAccount $Action | Out-Null
    }
    Ensure-PublicDirectory $publicDirectory

    if ($Action -ne 'status') {
        $backend = Get-Service -Name 'TPVERPBackend' -ErrorAction Stop
        if ($backend.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
            Stop-Service -Name 'TPVERPBackend' -ErrorAction Stop
            (Get-Service -Name 'TPVERPBackend').WaitForStatus([ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromMinutes(2))
        }
    }
    $arguments = @{
        Action = $Action; BundleDirectory = $releaseDirectory; ExpectedReleaseId = $releaseId
        JavaExecutable = $java; DestinationDirectory = $destination
    }
    if ($Action -eq 'receive') { $arguments.BackupFile = Read-SelectedBackup }
    if ($Action -ne 'status') { $arguments.Execute = $true }
    # Do not capture/pipeline this invocation: Java readPassword requires inherited console handles.
    $resultName = 'maintenance-result-' + [guid]::NewGuid().ToString('N') + '.json'
    $resultPath = Join-Path $destination $resultName
    $arguments.ResultFileName = $resultName
    try {
        & $wrapper @arguments
        Assert-PlainFile $resultPath 'El resultado privado de mantenimiento' | Out-Null
        if ((Get-Item -LiteralPath $resultPath).Length -gt 32768) { throw 'El resultado de mantenimiento excede el límite.' }
        $result = Get-Content -LiteralPath $resultPath -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop
    } finally {
        if (Test-Path -LiteralPath $resultPath) {
            Assert-PlainFile $resultPath 'El resultado privado de mantenimiento' | Out-Null
            Remove-Item -LiteralPath $resultPath -Force
        }
    }
    if ([string]::IsNullOrWhiteSpace([string]$result.state)) { throw 'La herramienta devolvió un estado inválido.' }
    if ($Action -eq 'status' -and $result.state -ceq 'NONE') {
        $requestFile = Join-Path $publicDirectory 'destination-request.json'
        if (Test-Path -LiteralPath $requestFile -PathType Leaf) {
            Assert-PlainFile $requestFile 'La solicitud pública del destino' | Out-Null
            if ((Get-Item -LiteralPath $requestFile).Length -le 32768) {
                $savedRequest = Get-Content -LiteralPath $requestFile -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop
                if ($savedRequest.state -ceq 'DESTINATION_READY') { $result = $savedRequest }
            }
        }
    }
    if ($Action -eq 'status' -and $result.state -ceq 'ACTIVE') {
        $readyNow = $false
        try {
            $response = Invoke-WebRequest -Uri "$($service.LocalUrl)/actuator/health/readiness" -UseBasicParsing -TimeoutSec 3
            $health = $response.Content | ConvertFrom-Json -ErrorAction Stop
            $readyNow = $response.StatusCode -eq 200 -and $health.status -ceq 'UP'
        } catch { }
        if (-not $readyNow) { $result.state = 'ACTIVE_SERVICE_UNREADY' }
    }
    Write-Host "Estado del destino: $($result.state)" -ForegroundColor Green
    if ($Action -eq 'request' -and $result.state -ceq 'DESTINATION_READY') {
        Publish-DestinationRequest $destination $publicDirectory
        Write-Host "Solicitud pública: $(Join-Path $publicDirectory 'destination-request.json')" -ForegroundColor Cyan
        Invoke-Item -LiteralPath $publicDirectory
    }
    if ($Action -eq 'activate') {
        if ($result.state -cne 'ACTIVE') { throw 'La activación no quedó confirmada; el servicio permanece detenido.' }
        $activationResult = $result
        Start-Service -Name 'TPVERPBackend' -ErrorAction Stop
        (Get-Service -Name 'TPVERPBackend').WaitForStatus([ServiceProcess.ServiceControllerStatus]::Running, [TimeSpan]::FromMinutes(2))
        $ready = $false
        for ($attempt = 0; $attempt -lt 45; $attempt++) {
            try {
                $response = Invoke-WebRequest -Uri "$($service.LocalUrl)/actuator/health/readiness" -UseBasicParsing -TimeoutSec 3
                $health = $response.Content | ConvertFrom-Json -ErrorAction Stop
                if ($response.StatusCode -eq 200 -and $health.status -ceq 'UP') { $ready = $true; break }
            } catch { Start-Sleep -Seconds 2 }
        }
        if (-not $ready) { throw 'El servidor se activó, pero la comprobación local de disponibilidad no confirmó UP.' }
        Write-Host 'Servidor destino activado; servicio iniciado.' -ForegroundColor Green
    }
    Write-PublicStatus $publicDirectory $result $Action
} catch {
    $failed = $true
    if ($null -ne $activationResult) {
        try {
            Write-PublicStatus $publicDirectory ([pscustomobject]@{
                state = 'ACTIVE_SERVICE_UNREADY'; transferId = $activationResult.transferId
            }) $Action
        } catch { }
    }
    Write-Host "Mantenimiento detenido: $($_.Exception.Message)" -ForegroundColor Red
} finally {
    [Environment]::SetEnvironmentVariable('TPV_DB_PASSWORD', $null, 'Process')
    Read-Host 'Pulse Intro para cerrar esta consola' | Out-Null
}
if ($failed) { exit 1 }
