#requires -Version 5.1
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$PackageDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$script:PackageRoot=[IO.Path]::GetFullPath($PackageDirectory)
$script:Tool=Join-Path $script:PackageRoot 'tools\openssl.exe'
$script:Config=Join-Path $script:PackageRoot 'tools\openssl.cnf'
$script:OfflineRequired=$false
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $script:PackageRoot 'offline-ca.ps1'),[ref]$tokens,[ref]$errors)
if ($errors.Count -ne 0) { throw 'SCRIPT_PARSE_FAILED' }
# Load functions only. Never run the production entry point or touch operator state.
foreach ($function in $ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] },$true)) {
    . ([scriptblock]::Create($function.Extent.Text))
}
function Expect-Failure([scriptblock]$Run,[string]$Pattern) {
    $failed=$false
    try { & $Run } catch { if ($_.Exception.Message -notlike $Pattern) { throw }; $failed=$true }
    if (-not $failed) { throw ('NEGATIVE_ACCEPTED: '+$Pattern) }
    $script:Checks++
}
function Check([bool]$Condition,[string]$Name) {
    if (-not $Condition) { throw $Name }
    $script:Checks++
}
$script:Checks=0
$temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$fixture=Join-Path $temp ('HooshiX-CA-NONPRODUCTION-'+[Guid]::NewGuid().ToString('N'))
$password=$null; $wrong=$null
try {
    Assert-Package
    $null=New-Item -ItemType Directory -Path $fixture
    Protect-StateDirectory $fixture
    $acl=Get-Acl -LiteralPath $fixture
    Check $acl.AreAccessRulesProtected 'PRIVATE_ACL_NOT_PROTECTED'
    $rng=New-Object Security.Cryptography.RNGCryptoServiceProvider
    $random=New-Object byte[] 32
    try {
        $rng.GetBytes($random)
        $text=([string][char]0x0631+[char]0x0645+[char]0x0632+' ')+([BitConverter]::ToString($random)).Replace('-','')
        $password=ConvertTo-SecureString $text -AsPlainText -Force
        $text=$null
        $rng.GetBytes($random)
        $wrong=ConvertTo-SecureString ([BitConverter]::ToString($random)).Replace('-','') -AsPlainText -Force
    } finally { [Array]::Clear($random,0,$random.Length); $rng.Dispose() }
    Assert-PasswordText (([string][char]0x0631)*20+' space')
    Expect-Failure { Assert-PasswordText 'short' } 'PASSWORD_LENGTH*'
    Expect-Failure { Assert-PasswordText ('a'*20+[char]10) } 'PASSWORD_CHARACTERS*'
    Expect-Failure { Assert-SamePassword $password $wrong } 'PASSWORD_CONFIRMATION_MISMATCH'
    Assert-SamePassword $password $password
    $boot=Join-Path $fixture 'bootstrap.enc.pem'
    $key=Join-Path $fixture 'root-key.enc.pem'
    $cert=Join-Path $fixture 'root-cert.pem'
    $null=Invoke-OpenSSL -ArgumentVector @('genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:4096','-aes-256-cbc','-pass','stdin','-out',$boot) -Password $password
    $null=Invoke-OpenSSL -ArgumentVector @('pkcs8','-topk8','-in',$boot,'-passin','stdin','-passout','stdin','-v2','aes-256-cbc','-v2prf','hmacWithSHA256','-iter','1000000','-out',$key) -Password $password
    Assert-EncryptedKey $key
    $culture=[Threading.Thread]::CurrentThread.CurrentCulture
    try {
        [Threading.Thread]::CurrentThread.CurrentCulture=[Globalization.CultureInfo]::GetCultureInfo('fa-IR')
        $expires=[DateTime]::UtcNow.AddYears(10).ToString('yyyyMMddHHmmssZ',[Globalization.CultureInfo]::InvariantCulture)
        $null=Invoke-OpenSSL -ArgumentVector @('req','-new','-x509','-sha256','-key',$key,'-passin','stdin','-config',(Join-Path $script:PackageRoot 'root.cnf'),'-extensions','root_ca','-not_after',$expires,'-out',$cert) -Password $password
    } finally { [Threading.Thread]::CurrentThread.CurrentCulture=$culture }
    Assert-KeyMatches $key $cert $password
    $script:Checks++
    Expect-Failure { Assert-KeyMatches $key $cert $wrong } 'OPENSSL_FAILED*'
    Expect-Failure { Assert-EncryptedKey $boot } 'ENCRYPTED_KEY_PARAMETERS_REJECTED'
    $backup=Join-Path $fixture 'backup'
    $null=New-Item -ItemType Directory -Path $backup
    Copy-Item -LiteralPath $key -Destination $backup
    Copy-Item -LiteralPath $cert -Destination $backup
    $marker=Join-Path $backup 'backup-receipt.json'
    @{ schema_version=1; installation_id=$script:InstallationId; root_certificate_sha256=(Get-FileHash -LiteralPath $cert).Hash; encrypted_key_sha256=(Get-FileHash -LiteralPath $key).Hash } | ConvertTo-Json | Set-Content -LiteralPath $marker -Encoding UTF8
    Assert-Backup $backup
    Assert-KeyMatches (Join-Path $backup 'root-key.enc.pem') (Join-Path $backup 'root-cert.pem') $password
    $script:Checks++
    $original=[IO.File]::ReadAllBytes($marker)
    $receipt=Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json
    $receipt.installation_id='another-customer'
    $receipt | ConvertTo-Json | Set-Content -LiteralPath $marker -Encoding UTF8
    Expect-Failure { Assert-Backup $backup } 'BACKUP_INSTALLATION_MISMATCH'
    [IO.File]::WriteAllBytes($marker,$original)
    $receipt.installation_id=$script:InstallationId; $receipt.encrypted_key_sha256='0'*64
    $receipt | ConvertTo-Json | Set-Content -LiteralPath $marker -Encoding UTF8
    Expect-Failure { Assert-Backup $backup } 'BACKUP_KEY_HASH_REJECTED'
    [IO.File]::WriteAllBytes($marker,$original)
    $extra=Join-Path $backup 'unowned.txt'
    [IO.File]::WriteAllText($extra,'preserve me')
    Expect-Failure { Assert-Backup $backup } 'BACKUP_FILE_SET_REJECTED'
    Check ((Get-Content -LiteralPath $extra -Raw) -eq 'preserve me') 'UNOWNED_FILE_CHANGED'
    Remove-Item -LiteralPath $extra
    $copy=Join-Path $fixture 'independent-copy'
    Copy-Item -LiteralPath $backup -Destination $copy -Recurse
    Assert-Backup $copy
    Assert-KeyMatches (Join-Path $copy 'root-key.enc.pem') (Join-Path $copy 'root-cert.pem') $password
    $script:Checks++
    # Synthetic legacy receipt/root only. Never load or decrypt the operator Root.
    # Inject a fixture digest in memory, not a production bypass flag or policy file.
    $savedId=$script:InstallationId
    $savedAuthority=$script:RootAuthority | ConvertTo-Json -Depth 5
    $exception=$script:RootAuthority.existing_root_exception
    $script:InstallationId='hooshix-production'
    Expect-Failure { Assert-NewRootAllowed } 'EXISTING_ROOT_APPROVED_USE_SIGN*'
    Expect-Failure { Assert-KeyMatches $key $cert $password } 'ROOT_INSTALLATION_IDENTITY_REJECTED'
    $sameName=Join-Path $fixture 'unapproved-same-installation.pem'
    $null=Invoke-OpenSSL -ArgumentVector @('req','-new','-x509','-sha256','-key',$key,'-passin','stdin','-config',(Join-Path $script:PackageRoot 'root.cnf'),'-extensions','root_ca','-subj','/O=HooshiX/CN=hooshix-production Offline Root CA','-days','365','-out',$sameName) -Password $password
    Expect-Failure { Assert-KeyMatches $key $sameName $password } 'ROOT_INSTALLATION_IDENTITY_REJECTED'
    $exception.certificate_file_sha256=(Get-FileHash -LiteralPath $cert).Hash.ToLowerInvariant()
    $exception.subject_rfc2253=Invoke-OpenSSL -ArgumentVector @('x509','-in',$cert,'-subject','-nameopt','RFC2253','-noout')
    $legacy=@{ schema_version=1; certificate_sha256=(Get-FileHash -LiteralPath $cert).Hash; encrypted_key_sha256=(Get-FileHash -LiteralPath $key).Hash; production_approved=$false; offline_custody='Not verified' }
    $legacy | ConvertTo-Json | Set-Content -LiteralPath $marker -Encoding UTF8
    $legacyBytes=[IO.File]::ReadAllBytes($marker)
    Assert-Backup $backup
    Assert-KeyMatches $key $cert $password
    Check ([Convert]::ToBase64String([IO.File]::ReadAllBytes($marker)) -ceq [Convert]::ToBase64String($legacyBytes)) 'LEGACY_RECEIPT_REWRITTEN'
    Expect-Failure { Assert-KeyMatches $boot $cert $wrong } 'OPENSSL_FAILED*'
    $exception.owner_approved=$false
    Expect-Failure { Assert-Backup $backup } 'BACKUP_INSTALLATION_MISMATCH'
    $exception.owner_approved='true'
    Expect-Failure { Assert-Backup $backup } 'BACKUP_INSTALLATION_MISMATCH'
    $exception.owner_approved=$true
    $exception.profile='production-ha'
    Expect-Failure { Assert-Backup $backup } 'BACKUP_INSTALLATION_MISMATCH'
    $exception.profile='production-single-server'
    $exception.subject_rfc2253='subject=CN=wrong'
    Expect-Failure { Assert-KeyMatches $key $cert $password } 'ROOT_INSTALLATION_IDENTITY_REJECTED'
    $exception.subject_rfc2253=Invoke-OpenSSL -ArgumentVector @('x509','-in',$cert,'-subject','-nameopt','RFC2253','-noout')
    $script:InstallationId='another-customer'
    Expect-Failure { Assert-Backup $backup } 'BACKUP_INSTALLATION_MISMATCH'
    $script:InstallationId='hooshix-production'
    $legacy.certificate_sha256='0'*64
    $legacy | ConvertTo-Json | Set-Content -LiteralPath $marker -Encoding UTF8
    Expect-Failure { Assert-Backup $backup } 'BACKUP_CERTIFICATE_HASH_REJECTED'
    $legacy.certificate_sha256=(Get-FileHash -LiteralPath $cert).Hash
    $legacy.encrypted_key_sha256='0'*64
    $legacy | ConvertTo-Json | Set-Content -LiteralPath $marker -Encoding UTF8
    Expect-Failure { Assert-Backup $backup } 'BACKUP_KEY_HASH_REJECTED'
    $script:InstallationId=$savedId
    Assert-NewRootAllowed
    $script:RootAuthority=$savedAuthority | ConvertFrom-Json
    [IO.File]::WriteAllBytes($marker,$original)
    $csrKey=Join-Path $fixture 'intermediate.enc.pem'; $csr=Join-Path $fixture 'cluster.csr.pem'
    $null=Invoke-OpenSSL -ArgumentVector @('genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:4096','-aes-256-cbc','-pass','stdin','-out',$csrKey) -Password $password
    Expect-Failure { Assert-KeyMatches $csrKey $cert $password } 'KEY_CERTIFICATE_MISMATCH'
    $null=Invoke-OpenSSL -ArgumentVector @('req','-new','-sha256','-key',$csrKey,'-passin','stdin','-subj',('/CN='+$script:InstallationId+' Cluster Intermediate CA'),'-addext','basicConstraints=critical,CA:true,pathlen:5','-out',$csr) -Password $password
    $csrHash=(Get-FileHash -LiteralPath $csr).Hash
    $signed=Join-Path $fixture 'signed'
    Expect-Failure { Sign-Intermediate $csr ('0'*64) $key $cert $password $signed } 'TRUSTED_CSR_HASH_MISMATCH'
    $oversize=Join-Path $fixture 'oversize.csr.pem'
    [IO.File]::WriteAllBytes($oversize,(New-Object byte[] 32769))
    Expect-Failure { Sign-Intermediate $oversize (Get-FileHash -LiteralPath $oversize).Hash $key $cert $password (Join-Path $fixture 'oversize-output') } 'CSR_SIZE_REJECTED'
    Sign-Intermediate $csr $csrHash $key $cert $password $signed
    $description=Invoke-OpenSSL -ArgumentVector @('x509','-in',(Join-Path $signed 'ca-cert.pem'),'-text','-noout')
    Check ($description -match 'CA:TRUE, pathlen:0' -and $description -notmatch 'pathlen:5') 'CSR_EXTENSIONS_COPIED'
    Check (@(Get-ChildItem -LiteralPath $signed).Count -eq 4) 'SIGNED_PUBLIC_FILE_SET_WRONG'
    $script:InstallationId='hooshix-production'
    $exception=$script:RootAuthority.existing_root_exception
    $exception.certificate_file_sha256=(Get-FileHash -LiteralPath $cert).Hash.ToLowerInvariant()
    $exception.subject_rfc2253=Invoke-OpenSSL -ArgumentVector @('x509','-in',$cert,'-subject','-nameopt','RFC2253','-noout')
    $legacyCsr=Join-Path $fixture 'legacy-cluster.csr.pem'
    $null=Invoke-OpenSSL -ArgumentVector @('req','-new','-sha256','-key',$csrKey,'-passin','stdin','-subj','/CN=hooshix-production Cluster Intermediate CA','-out',$legacyCsr) -Password $password
    $legacySigned=Join-Path $fixture 'legacy-signed'
    Sign-Intermediate $legacyCsr (Get-FileHash -LiteralPath $legacyCsr).Hash $key $cert $password $legacySigned
    Check (Test-Path -LiteralPath (Join-Path $legacySigned 'ca-cert.pem')) 'EXISTING_ROOT_SIGNING_FAILED'
    $script:InstallationId=$savedId
    $script:RootAuthority=$savedAuthority | ConvertFrom-Json
    Expect-Failure { Sign-Intermediate $csr $csrHash $key $cert $password $signed } 'EXISTING_SIGNED_OUTPUT_PRESERVED'
    $script:InstallationId='another-customer'
    Expect-Failure { Sign-Intermediate $csr $csrHash $key $cert $password (Join-Path $fixture 'wrong-install') } 'ROOT_INSTALLATION_IDENTITY_REJECTED'
    Assert-Package
    $policyFile=Join-Path $script:PackageRoot 'root-authority.json'
    $originalPolicy=[IO.File]::ReadAllBytes($policyFile)
    try {
        [IO.File]::AppendAllText($policyFile,"`n ",[Text.UTF8Encoding]::new($false))
        Expect-Failure { Assert-Package } 'PACKAGE_HASH_MISMATCH: root-authority.json'
    } finally { [IO.File]::WriteAllBytes($policyFile,$originalPolicy) }
    Assert-Package
    # The manifest catches tampering and tolerates text transfer's CRLF conversion.
    $scriptFile=Join-Path $script:PackageRoot 'offline-ca.ps1'
    $originalScript=[IO.File]::ReadAllBytes($scriptFile)
    try {
        $text=[IO.File]::ReadAllText($scriptFile).Replace("`r`n","`n").Replace("`n","`r`n")
        [IO.File]::WriteAllText($scriptFile,$text,[Text.UTF8Encoding]::new($false))
        Assert-Package
        $script:Checks++
        [IO.File]::AppendAllText($scriptFile,"`r`n# tampered",[Text.UTF8Encoding]::new($false))
        Expect-Failure { Assert-Package } 'PACKAGE_HASH_MISMATCH*'
    } finally { [IO.File]::WriteAllBytes($scriptFile,$originalScript) }
    $manifestFile=Join-Path $script:PackageRoot 'package-manifest.json'
    $originalManifest=[IO.File]::ReadAllBytes($manifestFile)
    try {
        $manifest=Get-Content -LiteralPath $manifestFile -Raw | ConvertFrom-Json
        $manifest.files=@($manifest.files | Where-Object path -ne 'offline-ca.ps1')
        $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestFile -Encoding UTF8
        Expect-Failure { Assert-Package } 'PACKAGE_FILE_SET_REJECTED'
    } finally { [IO.File]::WriteAllBytes($manifestFile,$originalManifest) }
    # Fixture-only mock: production has no flag that bypasses its offline check.
    function Get-NetAdapter { param([switch]$IncludeHidden) return @{ Status='Up' } }
    Expect-Failure { Assert-Disconnected } 'NETWORK_ADAPTER_UP*'
    function Get-NetAdapter { param([switch]$IncludeHidden) return @() }
    Assert-Disconnected
    $script:Checks++
    Assert-Package
    Write-Host ('WINDOWS_CA_FIXTURE_CHECKS=PASSED count='+$script:Checks)
    Write-Host 'NO_OPERATOR_KEYS_OR_STATE_READ'
} finally {
    if ($null -ne $password) { $password.Dispose() }
    if ($null -ne $wrong) { $wrong.Dispose() }
    $resolved=[IO.Path]::GetFullPath($fixture)
    if ($resolved.StartsWith($temp+'\HooshiX-CA-NONPRODUCTION-',[StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolved)) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
