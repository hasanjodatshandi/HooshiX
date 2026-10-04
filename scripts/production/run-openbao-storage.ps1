param([switch]$ValidateOnly, [switch]$InstallApproved8GiB, [switch]$InstallApprovedGuard)
$ErrorActionPreference = 'Stop'
if ($InstallApproved8GiB -and $InstallApprovedGuard) { throw 'CHOOSE_ONE_STORAGE_OPERATION' }
$source = Join-Path $PSScriptRoot 'provision_openbao_storage.py'
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw 'STORAGE_SOURCE_MISSING' }
$digest = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
if ($digest -notmatch '^[a-f0-9]{64}$') { throw 'STORAGE_SOURCE_HASH_INVALID' }
if ($ValidateOnly) { Write-Output 'STORAGE_LAUNCHER=VALIDATED_NO_REMOTE_EXECUTION'; exit 0 }
$remote = '.cache/hooshix-storage-' + [guid]::NewGuid().ToString('N') + '.py'
$report = $remote + '.json'
$localReport = Join-Path $PSScriptRoot ([System.IO.Path]::GetFileName($report))
try {
    & ssh.exe -o BatchMode=yes -o ConnectTimeout=8 hooshix-server 'umask 077; mkdir -p .cache'
    if ($LASTEXITCODE -ne 0) { throw 'STORAGE_SSH_FAILED' }
    & scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 $source ('hooshix-server:' + $remote)
    if ($LASTEXITCODE -ne 0) { throw 'STORAGE_UPLOAD_FAILED' }
    $arg = if ($InstallApproved8GiB) { '''--install-approved-8gib''' } elseif ($InstallApprovedGuard) { '''--install-approved-guard''' } else { '' }
    # Hash and execute the same bytes once: no reopening user-writable source as root.
    $bootstrap = 'import hashlib,pathlib,sys; p=pathlib.Path(''' + $remote + '''); b=p.open(''rb'').read(32769); exit(1) if len(b)>32768 or hashlib.sha256(b).hexdigest()!=''' + $digest + ''' else None; sys.argv=[''provision_openbao_storage.py'',' + $arg + ']; exec(compile(b,str(p),''exec''),{''__name__'':''__main__'',''__hooshix_source_bytes__'':b})'
    $numeric = [string]::Join(',', [System.Text.Encoding]::UTF8.GetBytes($bootstrap))
    if ($InstallApproved8GiB) { Write-Output 'Create only approved 8GiB VPS storage. No partitions/SSH/MCP/cluster changes. Enter sudo password only here.' }
    elseif ($InstallApprovedGuard) { Write-Output 'Install storage guard; failure stops K3s, not all existing containers. No reboot/restart/SSH/MCP/mail changes. Enter sudo password only here.' }
    else { Write-Output 'Read-only storage plan/verification. Enter sudo password only here.' }
    & ssh.exe -t -o ConnectTimeout=8 hooshix-server ('umask 077; sudo /usr/bin/python3 -I -c ''exec(bytes([' + $numeric + ']))'' > ' + $report + '; status=$?; /usr/bin/cat ' + $report + '; exit $status')
    $operationExit = $LASTEXITCODE
    & scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 ('hooshix-server:' + $report) $localReport
    if ($LASTEXITCODE -ne 0) { throw 'STORAGE_RECEIPT_COPY_FAILED' }
    Write-Output ('PUBLIC_RECEIPT=' + $localReport)
    # Preserve the public failure reason too; partial host state is never deleted.
    if ($operationExit -ne 0) { throw 'STORAGE_FAILED_OR_PARTIALLY_APPLIED_DO_NOT_BLINDLY_RETRY' }
} finally {
    & ssh.exe -o BatchMode=yes -o ConnectTimeout=8 hooshix-server ('rm -f -- ' + $remote + ' ' + $report)
}
