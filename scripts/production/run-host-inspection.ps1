param([switch]$ValidateOnly, [switch]$WithoutSudo)
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'inspect_host.py'
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw 'INSPECTION_SOURCE_MISSING' }
$digest = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
if ($digest -notmatch '^[a-f0-9]{64}$') { throw 'INSPECTION_HASH_INVALID' }
if ($ValidateOnly) { Write-Output 'INSPECTION_LAUNCHER=VALIDATED_NO_REMOTE_EXECUTION'; exit 0 }
$remote = '.cache/hooshix-inspection-' + [guid]::NewGuid().ToString('N') + '.py'
$report = $remote + '.json'
$localReport = Join-Path $PSScriptRoot ([System.IO.Path]::GetFileName($report))
# Public trusted source only. No password, key, environment or raw config is copied.
try {
    & ssh.exe -o BatchMode=yes -o ConnectTimeout=8 hooshix-server 'umask 077; mkdir -p .cache'
    if ($LASTEXITCODE -ne 0) { throw 'INSPECTION_SSH_FAILED' }
    & scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 $source ('hooshix-server:' + $remote)
    if ($LASTEXITCODE -ne 0) { throw 'INSPECTION_UPLOAD_FAILED' }
    # Read once, check the trusted local digest and execute those exact bytes in
    # memory, avoiding a check-then-reopen race on the user-owned temporary file.
    $bootstrap = 'import hashlib,pathlib; p=pathlib.Path(''' + $remote + '''); b=p.read_bytes(); h=hashlib.sha256(b).hexdigest(); exit(1) if h!=''' + $digest + ''' else None; exec(compile(b,str(p),''exec''),{''__name__'':''__main__''})'
    # Windows PowerShell 5.1 removes embedded double quotes in native argv.
    # Encode public bootstrap source as numeric bytes and use remote single
    # quotes, with no caller-controlled text or secret in the command string.
    $numeric = [string]::Join(',', [System.Text.Encoding]::UTF8.GetBytes($bootstrap))
    $prefix = if ($WithoutSudo) { '' } else { 'sudo ' }
    if (-not $WithoutSudo) { Write-Output 'Read-only host inspection. Enter sudo password only in this window; no password storage.' }
    & ssh.exe -t -o ConnectTimeout=8 hooshix-server ($prefix + '/usr/bin/python3 -c ''exec(bytes([' + $numeric + ']))'' > ' + $report + ' && cat ' + $report)
    if ($LASTEXITCODE -ne 0) { throw 'INSPECTION_ROOT_CHECK_FAILED' }
    & scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 ('hooshix-server:' + $report) $localReport
    if ($LASTEXITCODE -ne 0) { throw 'INSPECTION_RECEIPT_COPY_FAILED' }
    Write-Output ('PUBLIC_RECEIPT=' + $localReport)
} finally {
    & ssh.exe -o BatchMode=yes -o ConnectTimeout=8 hooshix-server ('rm -f -- ' + $remote + ' ' + $report)
}
