param([switch]$ValidateOnly, [switch]$RescueAndSecondSessionReady)
$ErrorActionPreference = 'Stop'
$sources = @('bootstrap_intermediate_csr.py', 'import_intermediate_ca.py', 'verify_storage_guard.py', 'upgrade_kyverno.py', 'platform_image_egress.py', 'commission_platform.py', 'upgrade_mesh_security.py', 'install_eso.py', 'install_cert_manager.py')
$expectedNames = @($sources) + @('plan.json', 'run-cert-manager-commissioning.ps1')
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'bundle.json') -Raw | ConvertFrom-Json
if ($manifest.schema_version -ne 1 -or $manifest.source_revision -cnotmatch '^[a-f0-9]{40}$') { throw 'REVIEWED_BUNDLE_REQUIRED' }
$names = @($manifest.files.PSObject.Properties.Name)
if ($names.Count -ne $expectedNames.Count) { throw 'BUNDLE_FILE_SET_REJECTED' }
foreach ($name in $names) {
    if ($expectedNames -cnotcontains $name) { throw 'BUNDLE_FILE_SET_REJECTED' }
    $entry = Get-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Force
    $bound = if ($name -ceq 'plan.json') { 8388608 } else { 32768 }
    if ($entry.PSIsContainer -or ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        $entry.Length -lt 1 -or $entry.Length -gt $bound) { throw 'BOUNDED_REGULAR_BUNDLE_FILE_REQUIRED' }
    if ((Get-FileHash -LiteralPath $entry.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -cne $manifest.files.$name) { throw 'BUNDLE_INTEGRITY_FAILED' }
}
if ($ValidateOnly) { Write-Output 'CERT_MANAGER_BUNDLE=Validated; no remote execution'; exit 0 }
if (-not $RescueAndSecondSessionReady) { throw 'OPEN_WORKING_RESCUE_VNC_AND_SECOND_PRIVATE_SSH_FIRST' }
if ((Read-Host 'Working rescue VNC and second private SSH: type READY') -cne 'READY') { throw 'RESCUE_REQUIRED' }
$remote = '/home/hooshixadmin/.cache/hooshix-cert-manager-' + [guid]::NewGuid().ToString('N')
$planHash = $manifest.files.'plan.json'
$report = $remote + '.json'
$localReport = Join-Path $PSScriptRoot ([IO.Path]::GetFileName($report))
& ssh.exe -T -o BatchMode=yes -o ConnectTimeout=8 hooshix-server ('umask 077; mkdir -p .cache; mkdir ' + $remote)
if ($LASTEXITCODE -ne 0) { throw 'PUBLIC_STAGING_FAILED' }
foreach ($name in @($sources) + @('plan.json')) {
    & scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 (Join-Path $PSScriptRoot $name) ('hooshix-server:' + $remote + '/' + $name)
    if ($LASTEXITCODE -ne 0) { throw 'PUBLIC_SOURCE_COPY_FAILED' }
}
$pairs = @($sources | ForEach-Object { "('$_','$($manifest.files.$_)')" }) -join ','
$bootstrap = @"
import hashlib,pathlib,sys,types,os,stat
p=pathlib.Path('$remote')
for name,digest in [$pairs]:
 with os.fdopen(os.open(p/name,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK),'rb') as stream:
  info=os.fstat(stream.fileno()); content=stream.read(32769)
 if not stat.S_ISREG(info.st_mode) or info.st_nlink!=1 or len(content)>32768 or hashlib.sha256(content).hexdigest()!=digest: raise SystemExit(1)
 m=types.ModuleType(name[:-3]); m.__file__=str(p/name); sys.modules[name[:-3]]=m
 exec(compile(content,name,'exec'),m.__dict__)
sys.argv=['install_cert_manager.py','--plan','$remote/plan.json','--sha256','$planHash']
raise SystemExit(sys.modules['install_cert_manager'].main())
"@
$numeric = [string]::Join(',', [Text.Encoding]::UTF8.GetBytes($bootstrap))
Write-Output 'Install only three internal cert-manager components. Root/CA/mesh/OpenBao/ESO/storage/mail/SSH preserved. No public TLS, ACME, initialization or reboot.'
Write-Output 'Enter sudo password ONLY here. No GHCR token, Root or custody passphrase required. No recording.'
& ssh.exe -t -o ConnectTimeout=8 hooshix-server ('umask 077; sudo /usr/bin/python3 -I -c ''exec(bytes([' + $numeric + ']))'' > ' + $report + '; status=$?; exit $status')
$operationExit = $LASTEXITCODE
& scp.exe -q -o BatchMode=yes -o ConnectTimeout=8 ('hooshix-server:' + $report) $localReport
if ($LASTEXITCODE -ne 0) { throw 'PUBLIC_RECEIPT_COPY_FAILED_STATE_PRESERVED' }
if ($operationExit -ne 0) { throw 'CERT_MANAGER_FAILED_PARTIAL_STATE_PRESERVED; no automatic rollback; retain public bundle and receipt' }
$receipt = Get-Content -LiteralPath $localReport -Raw | ConvertFrom-Json
if ($receipt.cert_manager_installation -cne 'Passed' -or $receipt.plan_sha256 -cne $planHash -or
    $receipt.api_callback_tls -cne 'Passed' -or $receipt.external_issuer_denied -cne 'Passed' -or
    $receipt.root_mesh_openbao_eso_storage -cne 'Preserved' -or $receipt.public_access -cne 'CLOSED' -or
    $receipt.production_readiness -cne 'Not verified') { throw 'CERT_MANAGER_RECEIPT_REJECTED' }
Write-Output 'CERT_MANAGER_INSTALLATION=Passed; internal control-plane TLS only; Barman/data/applications and production readiness Not verified'
Write-Output ('PUBLIC_RECEIPT=' + $localReport)
