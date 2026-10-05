param([switch]$ValidateOnly, [string]$ReviewedCommit, [string]$PublicDirectory)
$ErrorActionPreference = 'Stop'
$sources = @('bootstrap_intermediate_csr.py', 'import_intermediate_ca.py')
$hashes = @{}
foreach ($name in $sources) {
    $hashes[$name] = (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $name) -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($hashes[$name] -cnotmatch '^[a-f0-9]{64}$') { throw 'SOURCE_HASH_INVALID' }
}
if ($ValidateOnly) { Write-Output 'CA_IMPORT_LAUNCHER=Validated; no remote execution'; exit 0 }
if ($ReviewedCommit -cnotmatch '^[a-f0-9]{40}$') { throw 'REVIEWED_MAIN_COMMIT_REQUIRED' }
$publicNames = @('ca-cert.pem', 'cert-chain.pem', 'root-cert.pem', 'signing-receipt.json')
$folder = Get-Item -LiteralPath $PublicDirectory
if (-not $folder.PSIsContainer -or ($folder.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'PUBLIC_DIRECTORY_REQUIRED' }
$entries = @(Get-ChildItem -LiteralPath $folder.FullName -Force)
if ($entries.Count -ne 4) { throw 'ONLY_FOUR_PUBLIC_FILES_ALLOWED' }
foreach ($entry in $entries) {
    if ($entry.PSIsContainer -or $publicNames -cnotcontains $entry.Name -or
        ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        $entry.Length -lt 1 -or $entry.Length -gt 32768) { throw 'PUBLIC_FILE_REJECTED' }
}
$remote = '/home/hooshixadmin/.cache/hooshix-ca-import-' + [guid]::NewGuid().ToString('N')
$report = $remote + '.json'
$localReport = Join-Path $PSScriptRoot ([IO.Path]::GetFileName($report))
try {
    & ssh.exe -o BatchMode=yes -o ConnectTimeout=8 hooshix-server ('umask 077; mkdir -p .cache; mkdir ' + $remote)
    if ($LASTEXITCODE -ne 0) { throw 'SSH_OR_PUBLIC_STAGING_FAILED' }
    foreach ($name in $sources) {
        & scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 (Join-Path $PSScriptRoot $name) ('hooshix-server:' + $remote + '/' + $name)
        if ($LASTEXITCODE -ne 0) { throw 'REVIEWED_SOURCE_UPLOAD_FAILED' }
    }
    # Public input is in a separate directory so its exact four-file set is checked.
    & ssh.exe -o BatchMode=yes -o ConnectTimeout=8 hooshix-server ('mkdir ' + $remote + '/public')
    if ($LASTEXITCODE -ne 0) { throw 'PUBLIC_STAGING_FAILED' }
    foreach ($name in $publicNames) {
        & scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 (Join-Path $folder.FullName $name) ('hooshix-server:' + $remote + '/public/' + $name)
        if ($LASTEXITCODE -ne 0) { throw 'PUBLIC_CERTIFICATE_UPLOAD_FAILED' }
    }
    $bootstrap = @"
import hashlib,pathlib,sys,types
p=pathlib.Path('$remote')
sources={}
for name,digest in [('bootstrap_intermediate_csr.py','$($hashes['bootstrap_intermediate_csr.py'])'),('import_intermediate_ca.py','$($hashes['import_intermediate_ca.py'])')]:
 b=(p/name).open('rb').read(32769)
 if len(b)>32768 or hashlib.sha256(b).hexdigest()!=digest: raise SystemExit(1)
 sources[name]=b
m=types.ModuleType('bootstrap_intermediate_csr')
sys.modules[m.__name__]=m
exec(compile(sources['bootstrap_intermediate_csr.py'],'bootstrap_intermediate_csr.py','exec'),m.__dict__)
sys.argv=['import_intermediate_ca.py','--reviewed-commit','$ReviewedCommit','--public-directory','$remote/public']
exec(compile(sources['import_intermediate_ca.py'],'import_intermediate_ca.py','exec'),{'__name__':'__main__'})
"@
    $numeric = [string]::Join(',', [Text.Encoding]::UTF8.GetBytes($bootstrap))
    Write-Output 'Import existing signed intermediate into istio-system/cacerts only. No new Root/key, mesh/OpenBao install or SSH/mail/storage changes.'
    Write-Output 'Enter sudo and the EXISTING intermediate passphrase only here; NOT the Root passphrase. No recording.'
    & ssh.exe -t -o ConnectTimeout=8 hooshix-server ('umask 077; sudo /usr/bin/python3 -I -c ''exec(bytes([' + $numeric + ']))'' > ' + $report + '; status=$?; exit $status')
    $operationExit = $LASTEXITCODE
    & scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 ('hooshix-server:' + $report) $localReport
    if ($LASTEXITCODE -ne 0) { throw 'PUBLIC_RECEIPT_COPY_FAILED' }
    $receipt = Get-Content -LiteralPath $localReport -Raw | ConvertFrom-Json
    if ($operationExit -ne 0) {
        Write-Output ('CA_IMPORT=Failed; reason=' + $receipt.reason)
        throw 'IMPORT_FAILED_STATE_PRESERVED_NO_BLIND_RETRY'
    }
    if ($receipt.phase -cne 'INTERMEDIATE_IMPORTED' -or $receipt.checked_revision -cne $ReviewedCommit -or
        $receipt.ca_secret -cne 'Passed' -or $receipt.cluster_encryption -cne 'Passed' -or
        $receipt.root_private_key_online -ne $false -or $receipt.encrypted_host_key_preserved -ne $true -or
        $receipt.root_sha256 -cne 'f6d49249e221fa49138771c3d86037575f55eee5f581af373f4523008e424b94' -or
        $receipt.csr_sha256 -cnotmatch '^[a-f0-9]{64}$' -or
        $receipt.certificate_sha256 -cne (Get-FileHash -LiteralPath (Join-Path $folder.FullName 'ca-cert.pem') -Algorithm SHA256).Hash.ToLowerInvariant()) { throw 'PUBLIC_RECEIPT_INVALID' }
    Write-Output 'CA_IMPORT=Passed; existing encrypted host key preserved; Root stayed offline.'
    Write-Output ('PUBLIC_RECEIPT=' + $localReport)
    Write-Output 'NEXT=reviewed mesh and OpenBao bootstrap; production readiness remains Not verified.'
} finally {
    $cleanup = @($sources | ForEach-Object { $remote + '/' + $_ }) + @($publicNames | ForEach-Object { $remote + '/public/' + $_ }) + @($report)
    & ssh.exe -o BatchMode=yes -o ConnectTimeout=8 hooshix-server ('rm -f -- ' + ($cleanup -join ' ') + '; rmdir -- ' + $remote + '/public ' + $remote)
}
