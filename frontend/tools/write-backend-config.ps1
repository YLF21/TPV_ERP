param([Parameter(Mandatory=$true)][string]$BackendUrl)
$ErrorActionPreference = 'Stop'

function Assert-PlainPath([string]$Target, [bool]$ExpectFile) {
  $cursor = [System.IO.Path]::GetFullPath($Target)
  $first = $true
  while ($cursor) {
    if ([System.IO.File]::Exists($cursor) -or [System.IO.Directory]::Exists($cursor)) {
      $item = Get-Item -LiteralPath $cursor -Force
      if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Reparse point no permitido' }
      if ($first -and $ExpectFile -and $item.PSIsContainer) { throw 'Se esperaba un archivo' }
      if ((!$first -or !$ExpectFile) -and !$item.PSIsContainer) { throw 'Se esperaba un directorio' }
    }
    $parent = [System.IO.Path]::GetDirectoryName($cursor)
    if (!$parent -or $parent -eq $cursor) { break }
    $cursor = $parent
    $first = $false
  }
}

if ($BackendUrl.Length -gt 512) { throw 'URL demasiado larga' }
$uri = [System.Uri]::new($BackendUrl)
if (!$uri.IsAbsoluteUri -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/') { throw 'URL inválida' }
$localHost = @('localhost','127.0.0.1','::1') -contains $uri.DnsSafeHost.ToLowerInvariant()
if ($uri.Scheme -ne 'https' -and !($uri.Scheme -eq 'http' -and $localHost)) { throw 'HTTPS obligatorio para backend remoto' }
if ($uri.AbsoluteUri.TrimEnd('/') -ne $BackendUrl) { throw 'URL debe ser origen normalizado' }
$config = @{ backendUrl = $BackendUrl; allowedHosts = @() }
if (!$localHost) { $config.allowedHosts = @($uri.DnsSafeHost.ToLowerInvariant()) }
$payload = [System.Text.Encoding]::UTF8.GetBytes(($config | ConvertTo-Json -Compress))

$base = 'C:\ProgramData\TPV ERP'
$directory = Join-Path $base 'desktop'
$target = Join-Path $directory 'backend-config.json'
Assert-PlainPath $base $false
Assert-PlainPath $directory $false
Assert-PlainPath $target $true
[System.IO.Directory]::CreateDirectory($base) | Out-Null

$admins = [System.Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
$system = [System.Security.Principal.SecurityIdentifier]::new('S-1-5-18')
$users = [System.Security.Principal.SecurityIdentifier]::new('S-1-5-32-545')
$baseAcl = [System.Security.AccessControl.DirectorySecurity]::new()
$baseAcl.SetAccessRuleProtection($true, $false)
$baseAcl.SetOwner($admins)
$baseAcl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($admins,'FullControl','Allow'))
$baseAcl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($system,'FullControl','Allow'))
$authenticatedUsers = [System.Security.Principal.SecurityIdentifier]::new('S-1-5-11')
$baseAcl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($authenticatedUsers,'ReadAndExecute','Allow'))
try {
  $backendSid = ([System.Security.Principal.NTAccount]::new('NT SERVICE\TPVERPBackend')).Translate([System.Security.Principal.SecurityIdentifier])
  $baseAcl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($backendSid,'ReadAndExecute','Allow'))
} catch { }
[System.IO.Directory]::SetAccessControl($base, $baseAcl)
[System.IO.Directory]::CreateDirectory($directory) | Out-Null
$acl = [System.Security.AccessControl.DirectorySecurity]::new()
$acl.SetAccessRuleProtection($true, $false)
$acl.SetOwner($admins)
foreach ($sid in @($admins,$system)) {
  $acl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
}
$acl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($users,'ReadAndExecute','ContainerInherit,ObjectInherit','None','Allow'))
[System.IO.Directory]::SetAccessControl($directory, $acl)
$staging = Join-Path $directory ([System.IO.Path]::GetRandomFileName())
try {
  [System.IO.File]::WriteAllBytes($staging, $payload)
  $acl = [System.Security.AccessControl.FileSecurity]::new()
  $acl.SetAccessRuleProtection($true, $false)
  $acl.SetOwner($admins)
  foreach ($sid in @($admins,$system)) {
    $acl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','Allow'))
  }
  $acl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($users,'ReadAndExecute','Allow'))
  [System.IO.File]::SetAccessControl($staging, $acl)
  Assert-PlainPath $target $true
  if ([System.IO.File]::Exists($target)) {
    [System.IO.File]::SetAccessControl($target, $acl)
    [System.IO.File]::Replace($staging, $target, $null)
  } else {
    [System.IO.File]::Move($staging, $target)
  }
  [System.IO.File]::SetAccessControl($target, $acl)
} finally {
  if ([System.IO.File]::Exists($staging)) { [System.IO.File]::Delete($staging) }
}
