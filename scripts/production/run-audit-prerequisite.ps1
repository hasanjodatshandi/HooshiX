param([switch]$ValidateOnly, [switch]$Install, [switch]$VerifyOnly)
$ErrorActionPreference = 'Stop'
if ($Install -and $VerifyOnly) { throw 'INSTALL_AND_VERIFY_ONLY_ARE_MUTUALLY_EXCLUSIVE' }
$source = Join-Path $PSScriptRoot 'install_audit_prerequisite.py'
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw 'AUDIT_SOURCE_MISSING' }
$digest = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
if ($digest -notmatch '^[a-f0-9]{64}$') { throw 'AUDIT_HASH_INVALID' }
if ($ValidateOnly) { Write-Output 'AUDIT_LAUNCHER=VALIDATED_NO_REMOTE_EXECUTION'; exit 0 }
$remote = '.cache/hooshix-audit-prerequisite-' + [guid]::NewGuid().ToString('N') + '.py'
$report = $remote + '.json'
try {
    & ssh.exe -o BatchMode=yes -o ConnectTimeout=8 hooshix-server 'umask 077; mkdir -p .cache'
    if ($LASTEXITCODE -ne 0) { throw 'AUDIT_SSH_FAILED' }
    & scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 $source ('hooshix-server:' + $remote)
    if ($LASTEXITCODE -ne 0) { throw 'AUDIT_UPLOAD_FAILED' }
    $mode = if ($Install) { ',''--install''' } elseif ($VerifyOnly) { ',''--verify-only''' } else { '' }
    $bootstrap = 'import hashlib,pathlib,sys; p=pathlib.Path(''' + $remote + '''); b=p.read_bytes(); exit(1) if hashlib.sha256(b).hexdigest()!=''' + $digest + ''' else None; sys.argv=[''audit-prerequisite''' + $mode + ']; exec(compile(b,str(p),''exec''),{''__name__'':''__main__''})'
    $numeric = [string]::Join(',', [System.Text.Encoding]::UTF8.GetBytes($bootstrap))
    $prefix = if ($Install -or $VerifyOnly) { 'sudo ' } else { '' }
    if ($Install) { Write-Output 'Install only three reviewed OS-audit packages. Enter sudo password only here. Do not interrupt dpkg. No SSH/MCP/JIT/sudo policy changes.' }
    if ($VerifyOnly) { Write-Output 'Read-only audit prerequisite verification. Enter sudo password only here. No package installation or configuration changes.' }
    $command = $prefix + '/usr/bin/python3 -I -c ''exec(bytes([' + $numeric + ']))'''
    if ($VerifyOnly) { $command = 'umask 077; ' + $command + ' > ' + $report + ' && cat ' + $report }
    & ssh.exe -t -o ConnectTimeout=8 hooshix-server $command
    if ($LASTEXITCODE -ne 0) { throw 'AUDIT_PREREQUISITE_FAILED_OR_PARTIALLY_APPLIED' }
    if ($VerifyOnly) {
        $localReport = Join-Path $PSScriptRoot ([System.IO.Path]::GetFileName($report))
        & scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 ('hooshix-server:' + $report) $localReport
        if ($LASTEXITCODE -ne 0) { throw 'AUDIT_RECEIPT_COPY_FAILED' }
        Write-Output ('PUBLIC_RECEIPT=' + $localReport)
    }
} finally {
    # Delete only our unique public source, never packages, audit logs or user data.
    & ssh.exe -o BatchMode=yes -o ConnectTimeout=8 hooshix-server ('rm -f -- ' + $remote + ' ' + $report)
}
