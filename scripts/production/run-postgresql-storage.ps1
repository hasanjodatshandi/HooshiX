param([switch]$ValidateOnly, [switch]$RescueAndSecondSessionReady)
$ErrorActionPreference = 'Stop'
$sources = @('provision_openbao_storage.py', 'provision_postgresql_storage.py')
$expectedNames = @($sources) + @('run-postgresql-storage.ps1', 'postgresql-storage-receipt.json')
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'bundle.json') -Raw | ConvertFrom-Json
if ($manifest.schema_version -ne 1 -or $manifest.source_revision -cnotmatch '^[a-f0-9]{40}$' -or
    $manifest.native_run -cnotmatch '^[1-9][0-9]{0,19}$') { throw 'REVIEWED_BUNDLE_REQUIRED' }
$age = ([DateTimeOffset]::UtcNow - [DateTimeOffset]::Parse($manifest.native_observed_at)).TotalSeconds
if ($age -lt 0 -or $age -gt 432000) { throw 'FRESH_NATIVE_EVIDENCE_REQUIRED' }
$names = @($manifest.files.PSObject.Properties.Name)
if ($names.Count -ne $expectedNames.Count) { throw 'BUNDLE_FILE_SET_REJECTED' }
foreach ($name in $names) {
    if ($expectedNames -cnotcontains $name) { throw 'BUNDLE_FILE_SET_REJECTED' }
    $entry = Get-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Force
    if ($entry.PSIsContainer -or ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        $entry.Length -lt 1 -or $entry.Length -gt 32768) { throw 'BOUNDED_REGULAR_FILE_REQUIRED' }
    if ((Get-FileHash -LiteralPath $entry.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -cne $manifest.files.$name) { throw 'BUNDLE_INTEGRITY_FAILED' }
}
if ($ValidateOnly) { Write-Output 'POSTGRESQL_STORAGE_BUNDLE=Validated; no remote execution'; exit 0 }
if (-not $RescueAndSecondSessionReady) { throw 'OPEN_RESCUE_VNC_AND_SECOND_PRIVATE_SSH_FIRST' }
if ((Read-Host 'Working rescue VNC and second private SSH: type READY') -cne 'READY') { throw 'RESCUE_REQUIRED' }
$remote = '/home/hooshixadmin/.cache/hooshix-postgresql-storage-' + [guid]::NewGuid().ToString('N')
$report = $remote + '.json'
$localReport = Join-Path $PSScriptRoot ([IO.Path]::GetFileName($report))
& ssh.exe -T -o BatchMode=yes -o ConnectTimeout=8 hooshix-server ('umask 077; mkdir -p .cache; mkdir ' + $remote)
if ($LASTEXITCODE -ne 0) { throw 'PUBLIC_STAGING_FAILED' }
foreach ($name in $sources) {
    & scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 (Join-Path $PSScriptRoot $name) ('hooshix-server:' + $remote + '/' + $name)
    if ($LASTEXITCODE -ne 0) { throw 'PUBLIC_SOURCE_COPY_FAILED' }
}
$pairs = @($sources | ForEach-Object { "('$_','$($manifest.files.$_)')" }) -join ','
$bootstrap = @"
import hashlib,pathlib,sys,os,stat
p=pathlib.Path('$remote'); sources={}
for name,digest in [$pairs]:
 with os.fdopen(os.open(p/name,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK),'rb') as stream:
  info=os.fstat(stream.fileno()); content=stream.read(32769)
 if not stat.S_ISREG(info.st_mode) or info.st_nlink!=1 or len(content)>32768 or hashlib.sha256(content).hexdigest()!=digest: raise SystemExit(1)
 sources[name]=content
sys.argv=['provision_postgresql_storage.py','--install-approved-32gib']
exec(compile(sources['provision_postgresql_storage.py'],'provision_postgresql_storage.py','exec'),{'__name__':'__main__','__file__':'provision_postgresql_storage.py','__hooshix_core_source_bytes__':sources['provision_openbao_storage.py'],'__hooshix_source_bytes__':sources['provision_postgresql_storage.py']})
"@
$numeric = [string]::Join(',', [Text.Encoding]::UTF8.GetBytes($bootstrap))
Write-Output 'Allocate independent 32GiB PostgreSQL filesystem and healthy guard. No database/PV/passwords, package changes, reboot or K3s restart. OpenBao data/guard, mail, SSH and WireGuard preserved.'
Write-Output 'Later storage failure stops K3s, not all running containers; recovery is explicit. Enter sudo password ONLY here. No recording.'
& ssh.exe -t -o ConnectTimeout=8 hooshix-server ('umask 077; sudo /usr/bin/python3 -I -c ''exec(bytes([' + $numeric + ']))'' > ' + $report + '; status=$?; exit $status')
$operationExit = $LASTEXITCODE
& scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 ('hooshix-server:' + $report) $localReport
if ($LASTEXITCODE -ne 0) { throw 'PUBLIC_RECEIPT_COPY_FAILED_STATE_PRESERVED' }
if ($operationExit -ne 0) { throw 'POSTGRESQL_STORAGE_FAILED_STATE_PRESERVED_NO_BLIND_RETRY' }
$receipt = Get-Content -LiteralPath $localReport -Raw | ConvertFrom-Json
if ($receipt.postgresql_storage -cne 'Passed' -or $receipt.storage_guard -cne 'Passed' -or
    $receipt.backing_bytes -ne 34359738368 -or $receipt.runtime_uid -ne 26 -or
    $receipt.openbao_host_services -cne 'Preserved' -or $receipt.k3s_restart_performed -ne $false -or
    $receipt.database -cne 'Not run' -or $receipt.barman_runtime -cne 'Not run' -or
    $receipt.persistent_volume -cne 'Not run' -or $receipt.public_access -cne 'CLOSED' -or
    $receipt.source_sha256 -cne $manifest.files.'provision_postgresql_storage.py' -or
    $receipt.core_sha256 -cne $manifest.files.'provision_openbao_storage.py') { throw 'STORAGE_RECEIPT_REJECTED' }
Write-Output 'POSTGRESQL_STORAGE=Passed; database, Barman and production readiness Not verified'
Write-Output ('PUBLIC_RECEIPT=' + $localReport)
