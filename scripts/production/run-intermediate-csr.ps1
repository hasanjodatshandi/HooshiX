param([switch]$ValidateOnly, [string]$ReviewedCommit)
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'bootstrap_intermediate_csr.py'
$digest = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
if ($digest -notmatch '^[a-f0-9]{64}$') { throw 'SOURCE_HASH_INVALID' }
if ($ValidateOnly) { Write-Output 'CSR_LAUNCHER=Validated; no remote execution'; exit 0 }
if ($ReviewedCommit -cnotmatch '^[a-f0-9]{40}$') { throw 'REVIEWED_MAIN_COMMIT_REQUIRED' }
$remote = '.cache/hooshix-intermediate-' + [guid]::NewGuid().ToString('N') + '.py'
$report = $remote + '.json'
$localReport = Join-Path $PSScriptRoot ([System.IO.Path]::GetFileName($report))
$localCsr = Join-Path $PSScriptRoot 'cluster-intermediate.csr.pem'
try {
    & ssh.exe -o BatchMode=yes -o ConnectTimeout=8 hooshix-server 'umask 077; mkdir -p .cache'
    if ($LASTEXITCODE -ne 0) { throw 'SSH_FAILED' }
    & scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 $source ('hooshix-server:' + $remote)
    if ($LASTEXITCODE -ne 0) { throw 'PUBLIC_SOURCE_UPLOAD_FAILED' }
    $bootstrap = 'import hashlib,pathlib,sys; p=pathlib.Path(''' + $remote + '''); b=p.open(''rb'').read(32769); exit(1) if len(b)>32768 or hashlib.sha256(b).hexdigest()!=''' + $digest + ''' else None; sys.argv=[''bootstrap_intermediate_csr.py'',''--reviewed-commit'',''' + $ReviewedCommit + ''']; exec(compile(b,str(p),''exec''),{''__name__'':''__main__''})'
    $numeric = [string]::Join(',', [System.Text.Encoding]::UTF8.GetBytes($bootstrap))
    Write-Output 'Generate encrypted intermediate key on VPS; download only PUBLIC CSR. No Root, SSH/mail/storage or cluster writes.'
    Write-Output 'Enter sudo and NEW intermediate passphrase only here. Save the passphrase in your password manager. No recording.'
    & ssh.exe -t -o ConnectTimeout=8 hooshix-server ('umask 077; sudo /usr/bin/python3 -I -c ''exec(bytes([' + $numeric + ']))'' > ' + $report + '; status=$?; exit $status')
    $operationExit = $LASTEXITCODE
    & scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 ('hooshix-server:' + $report) $localReport
    if ($LASTEXITCODE -ne 0) { throw 'PUBLIC_RECEIPT_COPY_FAILED' }
    $receipt = Get-Content -LiteralPath $localReport -Raw | ConvertFrom-Json
    if ($operationExit -ne 0) {
        Write-Output ('CSR_BOOTSTRAP=Failed; reason=' + $receipt.reason)
        throw 'CSR_FAILED_EXISTING_STATE_PRESERVED_DO_NOT_BLINDLY_RETRY'
    }
    if ($receipt.phase -cne 'CSR_CREATED_PENDING_OFFLINE_SIGNATURE' -or
        $receipt.checked_revision -cne $ReviewedCommit -or
        $receipt.root_sha256 -cne 'f6d49249e221fa49138771c3d86037575f55eee5f581af373f4523008e424b94' -or
        $receipt.csr_sha256 -cnotmatch '^[a-f0-9]{64}$' -or
        $receipt.csr_pem.Length -gt 32768 -or
        $receipt.csr_pem -cnotmatch '\A-----BEGIN CERTIFICATE REQUEST-----\r?\n') {
        throw 'PUBLIC_RECEIPT_INVALID'
    }
    $csrBytes = [System.Text.UTF8Encoding]::new($false).GetBytes($receipt.csr_pem)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { $actual = ([BitConverter]::ToString($algorithm.ComputeHash($csrBytes))).Replace('-', '').ToLowerInvariant() }
    finally { $algorithm.Dispose() }
    if ($actual -cne $receipt.csr_sha256) { throw 'CSR_DOWNLOAD_HASH_MISMATCH' }
    if (Test-Path -LiteralPath $localCsr) {
        if ((Get-FileHash -LiteralPath $localCsr -Algorithm SHA256).Hash.ToLowerInvariant() -cne $actual) {
            throw 'EXISTING_PUBLIC_CSR_PRESERVED'
        }
    } else {
        $stream = [IO.File]::Open($localCsr, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $stream.Write($csrBytes, 0, $csrBytes.Length); $stream.Flush($true) }
        finally { $stream.Dispose() }
    }
    Write-Output ('PUBLIC_CSR=' + $localCsr)
    Write-Output ('CSR_SHA256=' + $actual)
    Write-Output ('PUBLIC_RECEIPT=' + $localReport)
    Write-Output 'CSR=Passed; use existing offline run-sign.cmd; bring back PUBLIC signed-intermediate directory only.'
    Write-Output 'OPENBAO_INSTALLATION=Not run; offline signature is the next dependency, not a new Root.'
} finally {
    & ssh.exe -o BatchMode=yes -o ConnectTimeout=8 hooshix-server ('rm -f -- ' + $remote + ' ' + $report)
}
