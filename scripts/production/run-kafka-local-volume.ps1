param([switch]$ValidateOnly, [switch]$RescueAndSecondSessionReady)
$ErrorActionPreference = 'Stop'
$source = 'install_kafka_local_volume.py'
$expectedNames = @($source, 'run-kafka-local-volume.ps1', 'kafka-storage-receipt.json', 'openbao-kubernetes-receipt.json')
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
if ($ValidateOnly) { Write-Output 'KAFKA_LOCAL_VOLUME_BUNDLE=Validated; no remote execution'; exit 0 }
if (-not $RescueAndSecondSessionReady) { throw 'OPEN_RESCUE_VNC_AND_SECOND_PRIVATE_SSH_FIRST' }
if ((Read-Host 'Working rescue VNC and second private SSH: type READY') -cne 'READY') { throw 'RESCUE_REQUIRED' }
$remote = '/home/hooshixadmin/.cache/hooshix-kafka-local-volume-' + [guid]::NewGuid().ToString('N')
$report = $remote + '.json'
$localReport = Join-Path $PSScriptRoot ([IO.Path]::GetFileName($report))
& ssh.exe -T -o BatchMode=yes -o ConnectTimeout=8 hooshix-server ('umask 077; mkdir -p .cache; mkdir ' + $remote)
if ($LASTEXITCODE -ne 0) { throw 'PUBLIC_STAGING_FAILED' }
& scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 (Join-Path $PSScriptRoot $source) ('hooshix-server:' + $remote + '/' + $source)
if ($LASTEXITCODE -ne 0) { throw 'PUBLIC_SOURCE_COPY_FAILED' }
$bootstrap = @"
import hashlib,os,stat,sys
with os.fdopen(os.open('$remote/$source',os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK),'rb') as stream:
 info=os.fstat(stream.fileno()); content=stream.read(32769)
if not stat.S_ISREG(info.st_mode) or info.st_nlink!=1 or len(content)>32768 or hashlib.sha256(content).hexdigest()!='$($manifest.files.$source)': raise SystemExit(1)
sys.argv=['$source','--reserve-approved-volume']
exec(compile(content,'$source','exec'),{'__name__':'__main__','__file__':'$source','__hooshix_source_bytes__':content})
"@
$numeric = [string]::Join(',', [Text.Encoding]::UTF8.GetBytes($bootstrap))
Write-Output 'Reserve existing guarded Kafka storage as a private 14GiB Retain LocalPV. No allocation, format, PVC/Kafka, credentials, restart, admission or existing database changes. Enter sudo only here.'
& ssh.exe -t -o ConnectTimeout=8 hooshix-server ('umask 077; sudo /usr/bin/python3 -I -c ''exec(bytes([' + $numeric + ']))'' > ' + $report + '; status=$?; exit $status')
$operationExit = $LASTEXITCODE
& scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 ('hooshix-server:' + $report) $localReport
if ($LASTEXITCODE -ne 0) { throw 'PUBLIC_RECEIPT_COPY_FAILED_STATE_PRESERVED' }
if ($operationExit -ne 0) { throw 'LOCAL_VOLUME_FAILED_STATE_PRESERVED_NO_BLIND_RETRY' }
$receipt = Get-Content -LiteralPath $localReport -Raw | ConvertFrom-Json
if ($receipt.source_sha256 -cne $manifest.files.$source -or
    $receipt.kafka_local_volume -cne 'Passed' -or $receipt.capacity_bytes -ne 15032385536 -or
    $receipt.volume -cne 'hooshix-kafka-local-14gib' -or $receipt.storage_guard -cne 'Passed' -or
    $receipt.node -cne 'hooshix-production-1' -or $receipt.reserved_claim -cne 'platform-data/data-kafka-0' -or
    $receipt.existing_storage_host_services -cne 'Preserved' -or $receipt.kafka -cne 'Not run' -or $receipt.pvc -cne 'Not run' -or
    $receipt.secret_delivery -cne 'Not run' -or $receipt.public_access -cne 'Unchanged' -or
    $receipt.admission -cne 'Unchanged' -or $receipt.production_readiness -cne 'Not verified') { throw 'LOCAL_VOLUME_RECEIPT_REJECTED' }
Write-Output 'KAFKA_LOCAL_VOLUME=Passed; Kafka runtime Not run; production readiness Not verified'
Write-Output ('PUBLIC_RECEIPT=' + $localReport)
