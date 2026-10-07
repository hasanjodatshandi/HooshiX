param([switch]$ValidateOnly, [switch]$RescueAndSecondSessionReady)
$ErrorActionPreference = 'Stop'
$sources = @('bootstrap_intermediate_csr.py', 'import_intermediate_ca.py', 'verify_storage_guard.py', 'upgrade_kyverno.py', 'commission_platform.py')
$artifacts = @('kyverno-3.9.1.tgz', 'helm-linux-amd64.tar.gz')
$publicNames = @('ca-cert.pem', 'cert-chain.pem', 'root-cert.pem', 'signing-receipt.json')
$expectedNames = @($sources) + @($artifacts) + @('plan.json', 'run-platform-commissioning.ps1', 'USAGE-fa.md') + @($publicNames | ForEach-Object { 'public/' + $_ })
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'bundle.json') -Raw | ConvertFrom-Json
if ($manifest.schema_version -ne 1 -or $manifest.source_revision -cnotmatch '^[a-f0-9]{40}$') { throw 'REVIEWED_BUNDLE_REQUIRED' }
$names = @($manifest.files.PSObject.Properties.Name)
if ($names.Count -ne $expectedNames.Count) { throw 'BUNDLE_FILE_SET_REJECTED' }
foreach ($name in $names) {
    if ($expectedNames -cnotcontains $name) { throw 'BUNDLE_FILE_SET_REJECTED' }
    $entry = Get-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Force
    $bound = if ($name -ceq 'helm-linux-amd64.tar.gz') { 33554432 } elseif ($name -ceq 'plan.json') { 8388608 } else { 2097152 }
    if ($entry.PSIsContainer -or ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        $entry.Length -lt 1 -or $entry.Length -gt $bound) { throw 'BOUNDED_REGULAR_BUNDLE_FILE_REQUIRED' }
    $actual = (Get-FileHash -LiteralPath $entry.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($manifest.files.$name -cnotmatch '^[a-f0-9]{64}$' -or $actual -cne $manifest.files.$name) { throw 'BUNDLE_INTEGRITY_FAILED' }
}
if ($ValidateOnly) { Write-Output 'PLATFORM_BUNDLE=Validated; no remote execution'; exit 0 }
if (-not $RescueAndSecondSessionReady) { throw 'OPEN_WORKING_RESCUE_CONSOLE_AND_SECOND_PRIVATE_SSH_SESSION_FIRST' }
$revision = $manifest.source_revision
$planHash = $manifest.files.'plan.json'
$remote = '/home/hooshixadmin/.cache/hooshix-platform-' + [guid]::NewGuid().ToString('N')
$report = $remote + '.json'
$localReport = Join-Path $PSScriptRoot ([IO.Path]::GetFileName($report))
try {
    & ssh.exe -o BatchMode=yes -o ConnectTimeout=8 hooshix-server ('umask 077; mkdir -p .cache; mkdir ' + $remote + '; mkdir ' + $remote + '/public')
    if ($LASTEXITCODE -ne 0) { throw 'SSH_OR_PUBLIC_STAGING_FAILED' }
    foreach ($name in @($sources) + @($artifacts) + @('plan.json') + @($publicNames | ForEach-Object { 'public/' + $_ })) {
        & scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 (Join-Path $PSScriptRoot $name) ('hooshix-server:' + $remote + '/' + $name)
        if ($LASTEXITCODE -ne 0) { throw 'REVIEWED_PUBLIC_UPLOAD_FAILED' }
    }
    $pairs = @($sources | ForEach-Object { "('$_','$($manifest.files.$_)')" }) -join ','
    $bootstrap = @"
import hashlib,pathlib,sys,types,os,stat
p=pathlib.Path('$remote')
sources={}
for name,digest in [$pairs]:
 with os.fdopen(os.open(p/name,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK),'rb') as stream:
  info=os.fstat(stream.fileno())
  if not stat.S_ISREG(info.st_mode) or info.st_nlink!=1 or not 0<info.st_size<=32768: raise SystemExit(1)
  content=stream.read(32769)
 if len(content)>32768 or hashlib.sha256(content).hexdigest()!=digest: raise SystemExit(1)
 sources[name]=content
for name in ['bootstrap_intermediate_csr','import_intermediate_ca','verify_storage_guard','upgrade_kyverno']:
 m=types.ModuleType(name)
 sys.modules[name]=m
 exec(compile(sources[name+'.py'],name+'.py','exec'),m.__dict__)
sys.argv=['commission_platform.py','--reviewed-commit','$revision','--plan','$remote/plan.json','--plan-sha256','$planHash','--public-directory','$remote/public','--rescue-and-second-session-ready']
exec(compile(sources['commission_platform.py'],'commission_platform.py','exec'),{'__name__':'__main__'})
"@
    $numeric = [string]::Join(',', [Text.Encoding]::UTF8.GetBytes($bootstrap))
    Write-Output 'Upgrade reviewed Kyverno preserving CRDs/policies; install pinned Istio and CLOSED, SEALED OpenBao on existing storage. No Root recreation, SSH/mail changes, reboot or initialization.'
    Write-Output 'Enter sudo, existing GHCR read:packages token, and EXISTING intermediate-key passphrase ONLY here. No recording; no credentials in chat.'
    & ssh.exe -t -o ConnectTimeout=8 hooshix-server ('umask 077; sudo /usr/bin/python3 -I -c ''exec(bytes([' + $numeric + ']))'' > ' + $report + '; status=$?; exit $status')
    $operationExit = $LASTEXITCODE
    & scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 ('hooshix-server:' + $report) $localReport
    if ($LASTEXITCODE -ne 0) { throw 'PUBLIC_RECEIPT_COPY_FAILED_STATE_PRESERVED' }
    $receipt = Get-Content -LiteralPath $localReport -Raw | ConvertFrom-Json
    if ($operationExit -ne 0) {
        Write-Output ('PLATFORM_COMMISSIONING=Failed; reason=' + $receipt.reason)
        throw 'INSTALLATION_FAILED_PARTIAL_STATE_PRESERVED_NO_BLIND_RETRY'
    }
    if ($receipt.source_revision -cne $revision -or $receipt.plan_sha256 -cne $planHash -or
        $receipt.kyverno_upgrade -cne 'Passed' -or $receipt.kyverno_version -cne '1.19.1' -or
        $receipt.openbao_installation -cne 'Passed' -or $receipt.mesh_installation -cne 'Passed' -or
        $receipt.openbao_state -cne 'Installed; sealed; not initialized' -or
        $receipt.retained_local_pvc -cne 'Passed' -or $receipt.tls_and_sealed_probes -cne 'Passed' -or
        $receipt.mail_management_k3s_preserved -cne 'Passed' -or $receipt.initialization -cne 'Not run' -or
        $receipt.production_readiness -cne 'Not verified') { throw 'PUBLIC_RECEIPT_INVALID' }
    Write-Output 'OPENBAO_INSTALLATION=Passed; installed sealed, not initialized; public access remains CLOSED.'
    Write-Output ('PUBLIC_RECEIPT=' + $localReport)
} finally {
    # Only this invocation's UUID-resolved PUBLIC staging files; no persistent
    # workload, storage, TLS Secret, CA or commissioning state is deleted.
    $cleanup = @(@($sources) + @($artifacts) | ForEach-Object { $remote + '/' + $_ }) + @($publicNames | ForEach-Object { $remote + '/public/' + $_ }) + @($remote + '/plan.json', $report)
    & ssh.exe -o BatchMode=yes -o ConnectTimeout=8 hooshix-server ('rm -f -- ' + ($cleanup -join ' ') + '; rmdir -- ' + $remote + '/public ' + $remote)
}
