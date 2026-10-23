Describe 'Maintenance Java console regression' {
    It 'retains the console through both real wrappers and executes each action once' {
        $fixture = Join-Path $TestDrive 'console'
        New-Item -ItemType Directory -Path $fixture | Out-Null
        @'
package org.springframework.boot.loader.launch;
import java.nio.file.*;
import java.util.*;
public final class PropertiesLauncher {
    public static void main(String[] args)throws Exception {
        var values=new HashMap<String,String>();
        for(int i=0;i<args.length;i+=2)values.put(args[i],args[i+1]);
        Path directory=Path.of(values.get("--directory"));
        String result="{\"state\":\""+(System.console()==null?"NO_CONSOLE":"CONSOLE_PRESENT")+"\"}";
        String mode=System.getenv("TPV_CONSOLE_PROBE_MODE");
        if("malformed".equals(mode))result="invalid-json";
        Files.writeString(directory.resolve("calls.txt"),values.get("--action")+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        if(values.containsKey("--result-file"))Files.writeString(directory.resolve(values.get("--result-file")),result,StandardOpenOption.CREATE_NEW);
        else System.out.println(result);
        if("failed".equals(mode))System.exit(23);
    }
}
'@ | Set-Content -LiteralPath (Join-Path $fixture 'PropertiesLauncher.java') -Encoding ASCII
        & javac -d $fixture (Join-Path $fixture 'PropertiesLauncher.java')
        $LASTEXITCODE | Should Be 0
        & jar --create --file (Join-Path $fixture 'probe.jar') -C $fixture org
        $LASTEXITCODE | Should Be 0
        @'
param($BundleDirectory,$ExpectedReleaseId,[switch]$AsObject)
$jar = Join-Path $PSScriptRoot 'probe.jar'
[pscustomobject]@{Jar=$jar;ArtifactSha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash}
'@ | Set-Content (Join-Path $fixture 'Test-TpvBackendProductionBundle.ps1')
        foreach ($kind in @('DisasterRecovery','ServerTransfer')) {
            Copy-Item (Join-Path $PSScriptRoot "Invoke-Tpv$kind.ps1") $fixture
            $source = Get-Content (Join-Path $PSScriptRoot ("Start-Tpv"+$kind+"Maintenance.ps1")) -Raw
            $start = $source.IndexOf('    # Do not capture/pipeline this invocation:')
            $end = $source.IndexOf("    if (`$Action -eq 'status'", $start)
            ($start -ge 0 -and $end -gt $start) | Should Be $true
            $assertFunction = [Management.Automation.Language.Parser]::ParseInput($source,[ref]$null,[ref]$null).Find({
                param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Assert-PlainFile'
            }, $false).Extent.Text
            $runner = @'
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$env:PSModulePath=Join-Path $PSHOME 'Modules'
# Apply the wrapper's real DACL without requesting SACL privileges in this non-elevated test process.
function Set-Acl($LiteralPath,$AclObject) {
    $existing=Get-Acl -LiteralPath $LiteralPath
    $access=[Security.AccessControl.AccessControlSections]::Access
    $existing.SetSecurityDescriptorSddlForm($AclObject.GetSecurityDescriptorSddlForm($access),$access)
    [IO.Directory]::SetAccessControl($LiteralPath,$existing)
}
$kind='__KIND__'
$env:TPV_BACKEND_SERVICE_ACCOUNT=[Security.Principal.WindowsIdentity]::GetCurrent().Name
$env:TPV_TRANSFER_HTTPS_SERVICE_ACCOUNT=$env:TPV_BACKEND_SERVICE_ACCOUNT
$wrapper=Join-Path $PSScriptRoot "Invoke-Tpv$kind.ps1"
$privateDirectory=Join-Path $PSScriptRoot "$kind-private"
$destination=$privateDirectory
New-Item -ItemType Directory -Path $privateDirectory | Out-Null
$Action=if($kind -eq 'DisasterRecovery'){'receive'}else{'cancel'}
$arguments=@{Action=$Action;BundleDirectory=$PSScriptRoot;ExpectedReleaseId='fixture';JavaExecutable='__JAVA__';Execute=$true}
if($kind -eq 'DisasterRecovery'){$arguments.MaintenanceDirectory=$privateDirectory;$arguments.RecoveryId='11111111-1111-1111-1111-111111111111'}
else{$arguments.DestinationDirectory=$privateDirectory}
__ASSERT__
try {
    $baseline=@(& $wrapper @arguments)
    if(($baseline[-1] | ConvertFrom-Json).state -ne 'NO_CONSOLE'){throw 'Old capture did not reproduce console loss'}
    Remove-Item -LiteralPath (Join-Path $privateDirectory 'calls.txt')
__BLOCK__
    if($result.state -ne 'CONSOLE_PRESENT'){throw 'Java lost its secure console'}
    if(@(Get-Content (Join-Path $privateDirectory 'calls.txt')).Count -ne 1){throw 'Action executed more than once'}
    if(@(Get-ChildItem $privateDirectory -Filter 'maintenance-result-*.json').Count -ne 0){throw 'Result was not removed'}
    foreach($mode in @('failed','malformed')) {
        Remove-Item -LiteralPath (Join-Path $privateDirectory 'calls.txt')
        $env:TPV_CONSOLE_PROBE_MODE=$mode
        $rejected=$false
        try {
__BLOCK__
        } catch { $rejected=$true }
        if(-not $rejected){throw 'Failed action or invalid result was accepted'}
        if(@(Get-Content (Join-Path $privateDirectory 'calls.txt')).Count -ne 1){throw 'Failure retried the action'}
        if(@(Get-ChildItem $privateDirectory -Filter 'maintenance-result-*.json').Count -ne 0){throw 'Failed result was not removed'}
    }
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot "$kind-ok.txt"),'PASS')
} catch {
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot "$kind-error.txt"),($_ | Out-String))
    exit 1
}
'@
            $runner = $runner.Replace('__KIND__',$kind).Replace('__JAVA__',(Get-Command java).Source.Replace("'","''")).
                Replace('__ASSERT__',$assertFunction).Replace('__BLOCK__',$source.Substring($start,$end-$start))
            $runnerPath = Join-Path $fixture "$kind-runner.ps1"
            $runner | Set-Content -LiteralPath $runnerPath -Encoding UTF8
            # A fresh hidden console makes this independent of redirected CI/Pester output.
            $process = Start-Process (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -WindowStyle Hidden -PassThru -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',"`"$runnerPath`"")
            if (-not $process.WaitForExit(30000)) { $process.Kill(); throw 'Console probe timed out' }
            $failure = Join-Path $fixture "$kind-error.txt"
            if (Test-Path $failure) { throw (Get-Content $failure -Raw) }
            $process.ExitCode | Should Be 0
            (Get-Content (Join-Path $fixture "$kind-ok.txt") -Raw) | Should Be 'PASS'
        }
    }
}
