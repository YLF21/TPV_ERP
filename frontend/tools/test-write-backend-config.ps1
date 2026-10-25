# Run from an elevated Windows PowerShell 5.1 session. All writes are confined
# to a fresh temporary fixture; the production writer has no path override.
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSEdition -ne 'Desktop' -or $PSVersionTable.PSVersion.Major -ne 5) {
  throw 'Run this regression test with Windows PowerShell 5.1 (powershell.exe).'
}
$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
  throw 'An elevated session is required to verify the real Administrators-owned ACL recovery.'
}

function Assert-True([bool]$Condition, [string]$Message) {
  if (!$Condition) { throw $Message }
}

$sourcePath = Join-Path $PSScriptRoot 'write-backend-config.ps1'
$source = [IO.File]::ReadAllText($sourcePath)
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseInput($source, [ref]$tokens, [ref]$errors)
Assert-True ($errors.Count -eq 0) 'Writer must parse without errors.'
$baseAssignments = @($ast.FindAll({ param($node)
  $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -eq '$base'
}, $true))
Assert-True ($baseAssignments.Count -eq 1) 'Expected exactly one fixed production base assignment.'
$baseAssignment = $baseAssignments[0]
Assert-True ($baseAssignment.Right.Extent.Text -eq "'C:\ProgramData\TPV ERP'") 'Unexpected production path; review test isolation before running.'

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('backend-config-acl-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($testRoot) | Out-Null
$admins = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
$accessOwner = [Security.AccessControl.AccessControlSections]::Access -bor [Security.AccessControl.AccessControlSections]::Owner

function Invoke-IsolatedWriter([string]$FixtureName, [string]$Url) {
  Assert-True (@('fresh', 'existing', 'link') -contains $FixtureName) 'Unknown fixture.'
  $fixtureBase = [IO.Path]::GetFullPath((Join-Path $testRoot $FixtureName))
  Assert-True ($fixtureBase.StartsWith($testRoot + '\', [StringComparison]::OrdinalIgnoreCase)) 'Fixture escaped the temporary root.'
  # Substitute only the parsed, verified literal in memory. Never dot-source or
  # launch the on-disk writer, which intentionally retains its fixed destination.
  $replacement = '$base = ' + "'" + $fixtureBase.Replace("'", "''") + "'"
  $isolatedSource = $source.Substring(0, $baseAssignment.Extent.StartOffset) + $replacement + $source.Substring($baseAssignment.Extent.EndOffset)
  & ([ScriptBlock]::Create($isolatedSource)) -BackendUrl $Url
}

function Assert-SecureAcl([string]$Path, [bool]$Directory, [string[]]$ExpectedSids) {
  $acl = if ($Directory) { [IO.Directory]::GetAccessControl($Path) } else { [IO.File]::GetAccessControl($Path) }
  Assert-True ($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -eq $admins.Value) "Wrong owner: $Path"
  Assert-True $acl.AreAccessRulesProtected "Inheritance not protected: $Path"
  $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
  Assert-True ($rules.Count -eq $ExpectedSids.Count) "Unexpected ACL entries: $Path"
  foreach ($sid in $ExpectedSids) {
    $rule = @($rules | Where-Object { $_.IdentityReference.Value -eq $sid })
    Assert-True ($rule.Count -eq 1) "Missing or duplicated SID $sid on $Path"
    Assert-True (!$rule[0].IsInherited -and $rule[0].AccessControlType -eq 'Allow') "Unexpected rule type: $Path"
    $rights = if (@('S-1-5-32-544','S-1-5-18') -contains $sid) { [Security.AccessControl.FileSystemRights]::FullControl } else { [Security.AccessControl.FileSystemRights]::ReadAndExecute -bor [Security.AccessControl.FileSystemRights]::Synchronize }
    Assert-True ($rule[0].FileSystemRights -eq $rights) "Unexpected rights for $sid on $Path"
    $flags = if ($Directory -and ([IO.Path]::GetFileName($Path) -eq 'desktop')) { 'ContainerInherit, ObjectInherit' } else { 'None' }
    Assert-True ($rule[0].InheritanceFlags.ToString() -eq $flags -and $rule[0].PropagationFlags -eq 'None') "Unexpected inherited permissions: $Path"
  }
}

function Assert-Fixture([string]$FixtureName, [string]$Url, [string[]]$Hosts) {
  $fixtureBase = Join-Path $testRoot $FixtureName
  $baseSids = @('S-1-5-32-544','S-1-5-18','S-1-5-11')
  try { $baseSids += ([Security.Principal.NTAccount]::new('NT SERVICE\TPVERPBackend')).Translate([Security.Principal.SecurityIdentifier]).Value } catch { }
  Assert-SecureAcl $fixtureBase $true $baseSids
  Assert-SecureAcl (Join-Path $fixtureBase 'desktop') $true @('S-1-5-32-544','S-1-5-18','S-1-5-32-545')
  $target = Join-Path $fixtureBase 'desktop\backend-config.json'
  Assert-SecureAcl $target $false @('S-1-5-32-544','S-1-5-18','S-1-5-32-545')
  $json = [IO.File]::ReadAllText($target) | ConvertFrom-Json
  Assert-True ($json.backendUrl -eq $Url) 'Backend URL did not persist.'
  Assert-True ((@($json.allowedHosts) -join ',') -eq ($Hosts -join ',')) 'Unexpected allowed hosts.'
  Assert-True (@([IO.Directory]::GetFileSystemEntries((Join-Path $fixtureBase 'desktop'))).Count -eq 1) 'Staging or backup files were left behind.'
}

$passed = $false
try {
  Invoke-IsolatedWriter 'fresh' 'https://saas.example.test'
  Assert-Fixture 'fresh' 'https://saas.example.test' @('saas.example.test')
  # A second write exercises File.Replace on Windows PowerShell 5.1.
  Invoke-IsolatedWriter 'fresh' 'http://127.0.0.1:8080'
  Assert-Fixture 'fresh' 'http://127.0.0.1:8080' @()

  $existing = Join-Path $testRoot 'existing'
  $desktop = Join-Path $existing 'desktop'
  [IO.Directory]::CreateDirectory($desktop) | Out-Null
  $ownerAcl = [Security.AccessControl.DirectorySecurity]::new()
  $ownerAcl.SetOwner($admins)
  [IO.Directory]::SetAccessControl($desktop, $ownerAcl)
  $emptyAcl = [Security.AccessControl.DirectorySecurity]::new()
  $emptyAcl.SetSecurityDescriptorSddlForm('D:P', [Security.AccessControl.AccessControlSections]::Access)
  [IO.Directory]::SetAccessControl($desktop, $emptyAcl)
  $before = [IO.Directory]::GetAccessControl($desktop)
  Assert-True ($before.GetOwner([Security.Principal.SecurityIdentifier]).Value -eq $admins.Value) 'Fixture must have BA owner.'
  Assert-True (@($before.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])).Count -eq 0) 'Fixture must have an empty DACL.'
  Invoke-IsolatedWriter 'existing' 'https://saas.example.test'
  Assert-Fixture 'existing' 'https://saas.example.test' @('saas.example.test')
  # .NET Framework expands IPv6 literals; the writer requires a normalized origin.
  $ipv6Origin = [Uri]::new('http://[::1]:8080').AbsoluteUri.TrimEnd('/')
  Invoke-IsolatedWriter 'existing' $ipv6Origin
  Assert-Fixture 'existing' $ipv6Origin @()

  foreach ($invalidUrl in @('http://remote.example.test', 'https://saas.example.test/path', 'https://saas.example.test?token=x', 'https://user:secret@saas.example.test', 'https://saas.example.test/')) {
    $rejected = $false
    try { Invoke-IsolatedWriter 'fresh' $invalidUrl } catch { $rejected = $true }
    Assert-True $rejected "URL should be rejected: $invalidUrl"
  }
  Assert-Fixture 'fresh' 'http://127.0.0.1:8080' @()

  $linkBase = Join-Path $testRoot 'link'
  $linkDestination = Join-Path $testRoot 'untouched'
  [IO.Directory]::CreateDirectory($linkDestination) | Out-Null
  $beforeLinkAcl = [IO.Directory]::GetAccessControl($linkDestination).GetSecurityDescriptorSddlForm($accessOwner)
  New-Item -ItemType Junction -Path $linkBase -Target $linkDestination | Out-Null
  try {
    $rejected = $false
    try { Invoke-IsolatedWriter 'link' 'https://saas.example.test' } catch { $rejected = $_.Exception.Message -match 'Reparse point' }
    Assert-True $rejected 'Junction must be rejected.'
    Assert-True ([IO.Directory]::GetFileSystemEntries($linkDestination).Length -eq 0) 'Junction destination was modified.'
    Assert-True ([IO.Directory]::GetAccessControl($linkDestination).GetSecurityDescriptorSddlForm($accessOwner) -eq $beforeLinkAcl) 'Junction destination ACL changed.'
  } finally { [IO.Directory]::Delete($linkBase) }
  $passed = $true
  Write-Output 'PASS: fresh directory, empty BA-owned DACL recovery, overwrite, exact ACLs, URL validation and junction rejection.'
} finally {
  if ($passed) {
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    Assert-True ($resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and [IO.Path]::GetFileName($resolved).StartsWith('backend-config-acl-')) 'Unsafe fixture cleanup path.'
    Remove-Item -LiteralPath $resolved -Recurse -Force
  } else { Write-Warning "Failed fixture retained for diagnosis: $testRoot" }
}
