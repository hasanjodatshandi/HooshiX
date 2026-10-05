param([switch]$ValidateOnly, [switch]$ReadOnly)
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'verify_storage_guard.py'
$digest = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
if ($digest -notmatch '^[a-f0-9]{64}$') { throw 'SOURCE_HASH_INVALID' }
if ($ValidateOnly) { Write-Output 'GUARD_MAINTENANCE_LAUNCHER=Validated; no remote execution'; exit 0 }
if (-not $ReadOnly) {
    Write-Output 'Maintenance: K3s control plane temporarily stops. Storage unmount/remount test only while data/PVCs are empty.'
    Write-Output 'No installation, format, reboot, firewall, SSH, mail or secret changes. Existing containers may keep running.'
    Write-Output 'Keep rescue VNC open and tested. Safety recovery is scheduled for 8 minutes; successful recovery cancels it.'
    if ((Read-Host 'Type MAINTENANCE to approve this downtime and confirm rescue VNC is ready') -cne 'MAINTENANCE') {
        Write-Output 'MAINTENANCE=Cancelled; no remote changes'
        exit 0
    }
}
$remote = '.cache/hooshix-guard-test-' + [guid]::NewGuid().ToString('N') + '.py'
$report = $remote + '.json'
$localReport = Join-Path $PSScriptRoot ([System.IO.Path]::GetFileName($report))
try {
    & ssh.exe -o BatchMode=yes -o ConnectTimeout=8 hooshix-server 'umask 077; mkdir -p .cache'
    if ($LASTEXITCODE -ne 0) { throw 'SSH_FAILED' }
    & scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 $source ('hooshix-server:' + $remote)
    if ($LASTEXITCODE -ne 0) { throw 'PUBLIC_SOURCE_UPLOAD_FAILED' }
    $arg = if ($ReadOnly) { '' } else { '''--approved-maintenance-with-rescue''' }
    $bootstrap = 'import hashlib,pathlib,sys; p=pathlib.Path(''' + $remote + '''); b=p.open(''rb'').read(32769); exit(1) if len(b)>32768 or hashlib.sha256(b).hexdigest()!=''' + $digest + ''' else None; sys.argv=[''verify_storage_guard.py'',' + $arg + ']; exec(compile(b,str(p),''exec''),{''__name__'':''__main__''})'
    $numeric = [string]::Join(',', [System.Text.Encoding]::UTF8.GetBytes($bootstrap))
    Write-Output 'Enter sudo password ONLY in this window. No terminal/password recording.'
    & ssh.exe -t -o ConnectTimeout=8 hooshix-server ('umask 077; sudo /usr/bin/python3 -I -c ''exec(bytes([' + $numeric + ']))'' > ' + $report + '; status=$?; /usr/bin/cat ' + $report + '; exit $status')
    $operationExit = $LASTEXITCODE
    & scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 ('hooshix-server:' + $report) $localReport
    if ($LASTEXITCODE -ne 0) { throw 'PUBLIC_RECEIPT_COPY_FAILED' }
    Write-Output ('PUBLIC_RECEIPT=' + $localReport)
    if ($operationExit -ne 0) { throw 'GUARD_TEST_FAILED; inspect receipt and safety timer; do not blindly retry' }
} finally {
    & ssh.exe -o BatchMode=yes -o ConnectTimeout=8 hooshix-server ('rm -f -- ' + $remote + ' ' + $report)
}
