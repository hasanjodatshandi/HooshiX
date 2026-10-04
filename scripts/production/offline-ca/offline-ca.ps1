#requires -Version 5.1
[CmdletBinding()]
param([ValidateSet('CreateRoot','Backup','Verify','SignIntermediate')][string]$Action='CreateRoot',[switch]$ValidateOnly,[string]$BackupDirectory,[string]$ExpectedCertificateSha256)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$script:PackageRoot=$PSScriptRoot
$script:OfflineRequired=$false
$script:Tool=Join-Path $script:PackageRoot 'tools\openssl.exe'
$script:Config=Join-Path $script:PackageRoot 'tools\openssl.cnf'

function Assert-PasswordText([string]$Value) {
    if ($Value.Length -lt 20 -or $Value.Length -gt 128) { throw 'PASSWORD_LENGTH: use 20-128 characters.' }
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -match '[\p{Cc}\p{Cs}\p{Zl}\p{Zp}]') { throw 'PASSWORD_CHARACTERS: letters, digits, symbols and spaces are allowed; control characters and emoji are not.' }
}

function Assert-NoReparse([string]$Path) {
    $item=Get-Item -LiteralPath $Path -Force
    while ($null -ne $item) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'REPARSE_PATH_REJECTED' }
        if ($item -is [IO.DirectoryInfo]) { $item=$item.Parent } else { $item=$item.Directory }
    }
}
function Assert-Disconnected {
    if (@(Get-NetAdapter -IncludeHidden | Where-Object Status -eq 'Up').Count -ne 0) {
        throw 'NETWORK_ADAPTER_UP: unplug Ethernet and disable Wi-Fi, Bluetooth networking and virtual adapters before retrying.'
    }
}
function Invoke-OpenSSL([string[]]$ArgumentVector,[Security.SecureString]$Password=$null,[Security.SecureString]$OutputPassword=$null) {
    if ($script:OfflineRequired) { Assert-Disconnected }
    $info=New-Object Diagnostics.ProcessStartInfo
    $info.FileName=$script:Tool
    $quoted=@($ArgumentVector | ForEach-Object {
        if ($_ -match '["\r\n]' -or $_.EndsWith('\')) { throw 'INVALID_TOOL_ARGUMENT' }
        '"'+$_+'"'
    })
    $info.Arguments=$quoted -join ' '
    $info.WorkingDirectory=Join-Path $script:PackageRoot 'tools'
    $info.UseShellExecute=$false
    $info.CreateNoWindow=$true
    $info.RedirectStandardInput=$true
    $info.RedirectStandardOutput=$true
    $info.RedirectStandardError=$true
    foreach ($name in @($info.EnvironmentVariables.Keys)) {
        if ($name -match '(?i)(secret|token|password|credential|api.?key|^OPENSSL_|^SSL_CERT_|^LD_)') { $info.EnvironmentVariables.Remove($name) }
    }
    $info.EnvironmentVariables['OPENSSL_CONF']=$script:Config
    $info.EnvironmentVariables['OPENSSL_MODULES']=Join-Path $script:PackageRoot 'tools'
    $info.EnvironmentVariables['PATH']=(Join-Path $script:PackageRoot 'tools')+';'+$env:SystemRoot+'\System32'
    $process=New-Object Diagnostics.Process
    $process.StartInfo=$info
    $plain=$null
    $ptr=[IntPtr]::Zero
    $outputPtr=[IntPtr]::Zero
    $outputPlain=$null
    try {
        # .NET Framework creates the redirected stdin writer during Start and may
        # emit Console.InputEncoding's UTF-8 BOM before caller-supplied bytes.
        # Use a BOM-free encoding only for Start, then restore the operator console.
        $previousInputEncoding=[Console]::InputEncoding
        try {
            [Console]::InputEncoding=New-Object Text.UTF8Encoding($false)
            if (-not $process.Start()) { throw 'OPENSSL_START_FAILED' }
        } finally { [Console]::InputEncoding=$previousInputEncoding }

        $stdout=$process.StandardOutput.ReadToEndAsync()
        $stderr=$process.StandardError.ReadToEndAsync()
        if ($null -ne $Password) {
            $ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password)
            $plain=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
            Assert-PasswordText $plain
            # Two lines permit distinct stdin reads for PKCS8 input/output passwords.
            # Strict UTF-8/LF preserves password bytes across Windows hosts without a BOM.
            if ($null -ne $OutputPassword) {
                $outputPtr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($OutputPassword)
                $outputPlain=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($outputPtr)
                Assert-PasswordText $outputPlain
            } else { $outputPlain=$plain }
            $passwordBytes=(New-Object Text.UTF8Encoding($false,$true)).GetBytes($plain+[char]10+$outputPlain+[char]10)
            try {
                $process.StandardInput.BaseStream.Write($passwordBytes,0,$passwordBytes.Length)
                $process.StandardInput.BaseStream.Flush()
            } finally { [Array]::Clear($passwordBytes,0,$passwordBytes.Length) }

        }
        $process.StandardInput.BaseStream.Close()
        if (-not $process.WaitForExit(180000)) { $process.Kill(); throw 'OPENSSL_DEADLINE_EXCEEDED' }
        $out=$stdout.GetAwaiter().GetResult()
        $err=$stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            $category='operation_failed'
            if ($err -match '(?i)bad decrypt|bad password') { $category='password_or_encrypted_key_invalid' }
            elseif ($err -match '(?i)no such file|cannot find|system library.*file') { $category='file_unavailable' }
            elseif ($err -match '(?i)unsupported|unknown option') { $category='unsupported_tool_option' }
            elseif ($err -match '(?i)reading password|passphrase') { $category='password_pipe_failed' }
            throw ('OPENSSL_FAILED: '+$ArgumentVector[0]+' exit='+$process.ExitCode+' category='+$category)
        }

        if ($out.Length -gt 262144 -or $err.Length -gt 262144) { throw 'OPENSSL_OUTPUT_BOUND_EXCEEDED' }
        if ($script:OfflineRequired) { Assert-Disconnected }
        return $out.Trim()
    } finally {
        $plain=$null
        $outputPlain=$null
        if ($outputPtr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($outputPtr) }
        if ($ptr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
        try { if (-not $process.HasExited) { $process.Kill() } } catch {}
        $process.Dispose()
    }
}


function Get-ManifestHash([string]$Path,[string]$Mode) {
    if ($Mode -eq 'text-lf-v1') {
        $bytes=[IO.File]::ReadAllBytes($Path)
        $decoder=New-Object Text.UTF8Encoding($false,$true)
        try { $text=$decoder.GetString($bytes) } catch { throw 'PACKAGE_TEXT_ENCODING_INVALID' }
        $crlf=[string]([char]13)+[char]10
        $text=$text.Replace($crlf,[string][char]10).Replace([string][char]13,[string][char]10)
        $normalized=[Text.UTF8Encoding]::new($false).GetBytes($text)
        try { return (([Security.Cryptography.SHA256]::Create().ComputeHash($normalized) | ForEach-Object ToString x2) -join '') } finally { [Array]::Clear($normalized,0,$normalized.Length); [Array]::Clear($bytes,0,$bytes.Length) }
    }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Assert-Package {
    if (-not [Environment]::Is64BitOperatingSystem) { throw 'WINDOWS_X64_REQUIRED' }
    Assert-NoReparse $script:PackageRoot
    $manifest=Get-Content -LiteralPath (Join-Path $script:PackageRoot 'package-manifest.json') -Raw | ConvertFrom-Json
    if ($manifest.schema_version -ne 3 -or $manifest.installation_id -notmatch '\A[a-z][a-z0-9-]{2,39}\z' -or $manifest.source_revision -notmatch '\A[a-f0-9]{40}\z') { throw 'INVALID_PACKAGE_MANIFEST' }
    $expected=@('offline-ca.ps1','root-authority.json','root.cnf','intermediate.cnf','run-root.cmd','run-backup.cmd','run-verify.cmd','run-sign.cmd','README-fa.txt','tools/openssl.exe','tools/libcrypto-3-x64.dll','tools/libssl-3-x64.dll','tools/openssl.cnf','licenses/OpenSSL-LICENSE.txt')
    $actual=@($manifest.files | ForEach-Object path)
    if ($actual.Count -ne $expected.Count -or @($actual | Select-Object -Unique).Count -ne $expected.Count -or @(Compare-Object $expected $actual).Count -ne 0) { throw 'PACKAGE_FILE_SET_REJECTED' }
    $script:InstallationId=$manifest.installation_id
    $script:SourceRevision=$manifest.source_revision
    foreach ($entry in $manifest.files) {
        if ($entry.path -notmatch '^[a-zA-Z0-9._/-]+$' -or $entry.path.Contains('..')) { throw 'INVALID_MANIFEST_PATH' }
        $mode=$null
        if ($entry.PSObject.Properties.Name -contains 'hash_mode') { $mode=[string]$entry.hash_mode; if ($mode -notin @('text-lf-v1')) { throw 'INVALID_MANIFEST_HASH_MODE' } }
        $file=Join-Path $script:PackageRoot $entry.path
        Assert-NoReparse $file
        if ((Get-ManifestHash $file $mode) -ne $entry.sha256.ToLowerInvariant()) { throw ('PACKAGE_HASH_MISMATCH: '+$entry.path) }
    }
    $script:RootAuthority=Get-Content -LiteralPath (Join-Path $script:PackageRoot 'root-authority.json') -Raw | ConvertFrom-Json
    $exception=$script:RootAuthority.existing_root_exception
    if ($script:RootAuthority.schema_version -ne 1 -or $script:RootAuthority.default_root_origin -cne 'offline_generated' -or
        $exception.installation_id -cne 'hooshix-production' -or $exception.profile -cne 'production-single-server' -or
        $exception.owner_approved -isnot [bool] -or -not $exception.owner_approved -or
        $exception.certificate_file_sha256 -cnotmatch '\A[a-f0-9]{64}\z' -or [string]::IsNullOrWhiteSpace($exception.subject_rfc2253) -or
        $exception.origin -cne 'connected_windows_generated' -or $exception.authority -cne 'ADR-0002' -or
        $exception.offline_custody_and_two_backups -cne 'owner_attested' -or
        $exception.independent_recovery_evidence -cne 'Not verified' -or $exception.production_readiness -cne 'Not verified') { throw 'ROOT_AUTHORITY_REJECTED' }
    $allowed=@('openssl.exe','libcrypto-3-x64.dll','libssl-3-x64.dll','openssl.cnf')
    foreach ($item in Get-ChildItem -LiteralPath (Join-Path $script:PackageRoot 'tools') -Force) {
        if ($item.PSIsContainer -or $item.Name -notin $allowed) { throw 'UNEXPECTED_TOOL_DIRECTORY_CONTENT' }
    }
    $version=Invoke-OpenSSL -ArgumentVector @('version')
    if ($version -ne 'OpenSSL 3.5.7 9 Jun 2026 (Library: OpenSSL 3.5.7 9 Jun 2026)') { throw 'OPENSSL_VERSION_MISMATCH' }
    Write-Host 'PACKAGE_INTEGRITY=PASSED'
}
function Assert-EncryptedKey([string]$Path) {
    if ((Get-Content -LiteralPath $Path -TotalCount 1) -ne '-----BEGIN ENCRYPTED PRIVATE KEY-----') { throw 'ENCRYPTED_KEY_HEADER_REQUIRED' }
    $structure=Invoke-OpenSSL -ArgumentVector @('asn1parse','-in',$Path)
    foreach ($pattern in @('PBES2','PBKDF2','hmacWithSHA256','aes-256-cbc','INTEGER\s*:0F4240')) {
        if ($structure -notmatch $pattern) { throw 'ENCRYPTED_KEY_PARAMETERS_REJECTED' }
    }
    $structure=$null
}
function Protect-StateDirectory([string]$Directory) {
    Assert-NoReparse $Directory
    $acl=New-Object Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true,$false)
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.SetOwner($sid)
    foreach ($allowedSid in @($sid,(New-Object Security.Principal.SecurityIdentifier 'S-1-5-18'))) {
        $rule=New-Object Security.AccessControl.FileSystemAccessRule($allowedSid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Directory -AclObject $acl
}
function Read-KeyPassword {
    while ($true) {
        $password=Read-Host 'Password (20-128 characters; Persian/English and spaces allowed; never share it)' -AsSecureString
        $ptr=[IntPtr]::Zero
        $plain=$null
        try {
            $ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($password)
            $plain=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
            Assert-PasswordText $plain
            return $password
        } catch {
            $password.Dispose()
            if ($_.Exception.Message -notlike 'PASSWORD_*') { throw }
            Write-Host $_.Exception.Message
            Write-Host 'Try again in this window. Ctrl+C cancels.'
        } finally {
            $plain=$null
            if ($ptr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
        }
    }
}


function Test-ApprovedExistingRoot([string]$Certificate) {
    $exception=$script:RootAuthority.existing_root_exception
    if ($exception.owner_approved -isnot [bool] -or -not $exception.owner_approved -or
        $exception.installation_id -cne $script:InstallationId -or $exception.profile -cne 'production-single-server') { return $false }
    if ((Get-FileHash -LiteralPath $Certificate -Algorithm SHA256).Hash.ToLowerInvariant() -cne $exception.certificate_file_sha256) { return $false }
    return (Invoke-OpenSSL -ArgumentVector @('x509','-in',$Certificate,'-subject','-nameopt','RFC2253','-noout')) -ceq $exception.subject_rfc2253
}
function Assert-NewRootAllowed {
    if ($script:InstallationId -ceq $script:RootAuthority.existing_root_exception.installation_id) {
        throw 'EXISTING_ROOT_APPROVED_USE_SIGN: do not regenerate; use the existing encrypted backup with run-sign.cmd.'
    }
}
function Assert-KeyMatches([string]$Key,[string]$Certificate,[Security.SecureString]$Password) {
    $subject=Invoke-OpenSSL -ArgumentVector @('x509','-in',$Certificate,'-subject','-nameopt','RFC2253','-noout')
    if ($subject -cne ('subject=CN='+$script:InstallationId+' Offline Root CA,O=HooshiX') -and -not (Test-ApprovedExistingRoot $Certificate)) { throw 'ROOT_INSTALLATION_IDENTITY_REJECTED' }
    $keyPublic=Invoke-OpenSSL -ArgumentVector @('pkey','-in',$Key,'-passin','stdin','-pubout') -Password $Password
    $certPublic=Invoke-OpenSSL -ArgumentVector @('x509','-in',$Certificate,'-pubkey','-noout')
    if ($keyPublic -ne $certPublic) { throw 'KEY_CERTIFICATE_MISMATCH' }
    $null=Invoke-OpenSSL -ArgumentVector @('verify','-x509_strict','-check_ss_sig','-no-CApath','-no-CAstore','-CAfile',$Certificate,$Certificate)
}
function Write-PublicReceipt([string]$State,[string]$Cert,[string]$Copies,[string]$Locations) {
    $public=Join-Path $State 'ToOnline'
    if (-not (Test-Path -LiteralPath $public)) { $null=New-Item -ItemType Directory -Path $public }
    Assert-NoReparse $public
    foreach ($item in Get-ChildItem -LiteralPath $public -Force) {
        if ($item.Name -notin @('root-cert.pem','root-public-receipt.json') -or $item.PSIsContainer) { throw 'UNEXPECTED_PUBLIC_EXPORT_CONTENT' }
    }
    $exportCert=Join-Path $public 'root-cert.pem'
    $exportReceipt=Join-Path $public 'root-public-receipt.json'
    $certHash=(Get-FileHash -LiteralPath $Cert -Algorithm SHA256).Hash.ToLowerInvariant()
    if (Test-Path -LiteralPath $exportCert) {
        Assert-NoReparse $exportCert
        if ((Get-FileHash -LiteralPath $exportCert -Algorithm SHA256).Hash.ToLowerInvariant() -ne $certHash) { throw 'EXISTING_PUBLIC_CERTIFICATE_MISMATCH_PRESERVED' }
    } else { Copy-Item -LiteralPath $Cert -Destination $exportCert }
    if (Test-Path -LiteralPath $exportReceipt) {
        Assert-NoReparse $exportReceipt
        $existingReceipt=Get-Content -LiteralPath $exportReceipt -Raw | ConvertFrom-Json
        if ($existingReceipt.schema_version -ne 1 -or $existingReceipt.phase -ne 'OFFLINE_ROOT_CREATED_MESH_NOT_INSTALLED' -or $existingReceipt.root_certificate_sha256 -ne $certHash) { throw 'UNOWNED_PUBLIC_RECEIPT_PRESERVED' }
    }

    $receipt=[ordered]@{
        schema_version=1; phase='OFFLINE_ROOT_CREATED_MESH_NOT_INSTALLED'; source_revision=$script:SourceRevision; installation_id=$script:InstallationId;
        root_certificate_sha256=(Get-FileHash -LiteralPath $Cert -Algorithm SHA256).Hash.ToLowerInvariant();
        fingerprint=(Invoke-OpenSSL -ArgumentVector @('x509','-in',$Cert,'-sha256','-fingerprint','-noout'));
        validity=(Invoke-OpenSSL -ArgumentVector @('x509','-in',$Cert,'-dates','-noout'));
        encrypted_pkcs8_aes256_pbkdf2_sha256_1000000='Passed'; key_matches_certificate='Passed';
        network_adapters_observed_disconnected='Passed'; two_distinct_usb_backup_copies=$Copies;
        separate_physical_locations=$Locations; production_readiness='Not verified'
    }
    $receipt | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $public 'root-public-receipt.json') -Encoding UTF8
}
function Get-UsbDestination([string]$Prompt) {
    $drive=(Read-Host $Prompt).Trim().TrimEnd(':').ToUpperInvariant()
    if ($drive -notmatch '^[D-Z]$') { throw 'USB_DRIVE_LETTER_REQUIRED' }
    $volume=Get-Volume -DriveLetter $drive
    $disk=Get-Partition -DriveLetter $drive | Get-Disk
    if (@($disk).Count -ne 1 -or $disk.BusType -ne 'USB' -or ($volume.DriveType -ne 'Removable' -and $volume.DriveType -ne 'Fixed')) { throw 'USB_PHYSICAL_DISK_REQUIRED' }
    return @{ drive=$drive; number=$disk.Number }
}
function Assert-SamePassword([Security.SecureString]$First,[Security.SecureString]$Second) {
    $a=[IntPtr]::Zero; $b=[IntPtr]::Zero
    try {
        $a=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($First)
        $b=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($Second)
        if ([Runtime.InteropServices.Marshal]::PtrToStringBSTR($a) -cne [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b)) { throw 'PASSWORD_CONFIRMATION_MISMATCH' }
    } finally {
        if ($a -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($a) }
        if ($b -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
    }
}
function Assert-Backup([string]$Folder) {
    Assert-NoReparse $Folder
    $expected=@('root-key.enc.pem','root-cert.pem','backup-receipt.json')
    $items=@(Get-ChildItem -LiteralPath $Folder -Force)
    if ($items.Count -ne 3) { throw 'BACKUP_FILE_SET_REJECTED' }
    foreach ($item in $items) {
        if ($item.PSIsContainer -or $item.Name -notin $expected) { throw 'BACKUP_FILE_SET_REJECTED' }
        Assert-NoReparse $item.FullName
    }
    $marker=Get-Content -LiteralPath (Join-Path $Folder 'backup-receipt.json') -Raw | ConvertFrom-Json
    if ($marker.schema_version -ne 1) { throw 'BACKUP_INSTALLATION_MISMATCH' }
    $certificate=Join-Path $Folder 'root-cert.pem'
    if ($marker.PSObject.Properties.Name -contains 'installation_id') {
        if ($marker.installation_id -cne $script:InstallationId) { throw 'BACKUP_INSTALLATION_MISMATCH' }
        $certificateHash=$marker.root_certificate_sha256
    } else {
        # The old portable export has no installation ID. Only the exact approved
        # public certificate can bind it to this installation; never edit its receipt.
        if (-not (Test-ApprovedExistingRoot $certificate)) { throw 'BACKUP_INSTALLATION_MISMATCH' }
        $certificateHash=$marker.certificate_sha256
    }
    if ($certificateHash -notmatch '\A[a-fA-F0-9]{64}\z' -or $certificateHash -ne (Get-FileHash -LiteralPath $certificate -Algorithm SHA256).Hash) { throw 'BACKUP_CERTIFICATE_HASH_REJECTED' }
    if ($marker.encrypted_key_sha256 -ne (Get-FileHash -LiteralPath (Join-Path $Folder 'root-key.enc.pem') -Algorithm SHA256).Hash) { throw 'BACKUP_KEY_HASH_REJECTED' }
    Assert-EncryptedKey (Join-Path $Folder 'root-key.enc.pem')
}
function Sign-Intermediate([string]$Csr,[string]$ExpectedCsrHash,[string]$RootKey,[string]$RootCert,[Security.SecureString]$Password,[string]$Destination) {
    Assert-NoReparse $Csr
    Assert-NoReparse $RootKey
    Assert-NoReparse $RootCert
    if ((Get-Item -LiteralPath $Csr).Length -gt 32768) { throw 'CSR_SIZE_REJECTED' }
    if ($ExpectedCsrHash -notmatch '^[a-fA-F0-9]{64}$' -or $ExpectedCsrHash -ne (Get-FileHash -LiteralPath $Csr -Algorithm SHA256).Hash) { throw 'TRUSTED_CSR_HASH_MISMATCH' }
    if (Test-Path -LiteralPath $Destination) { throw 'EXISTING_SIGNED_OUTPUT_PRESERVED' }
    Assert-NoReparse (Split-Path $Destination)
    $null=New-Item -ItemType Directory -Path $Destination
    Protect-StateDirectory $Destination
    # Snapshot a bounded CSR before parsing/signing, so removing/replacing the
    # transfer USB cannot change the material after the operator hash check.
    $snapshot=Join-Path $Destination 'approved-csr.pem'
    $input=[IO.File]::Open($Csr,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    try {
        $bytes=New-Object byte[] 32769
        $count=0
        while ($count -lt $bytes.Length) {
            $read=$input.Read($bytes,$count,$bytes.Length-$count)
            if ($read -eq 0) { break }
            $count+=$read
        }
        if ($count -gt 32768) { throw 'CSR_SIZE_REJECTED' }
        $output=[IO.File]::Open($snapshot,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        try { $output.Write($bytes,0,$count) } finally { $output.Dispose() }
    } finally { $input.Dispose() }
    if ($ExpectedCsrHash -ne (Get-FileHash -LiteralPath $snapshot -Algorithm SHA256).Hash) { throw 'CSR_CHANGED_DURING_SNAPSHOT' }
    $Csr=$snapshot
    Assert-EncryptedKey $RootKey
    Assert-KeyMatches $RootKey $RootCert $Password
    $null=Invoke-OpenSSL -ArgumentVector @('x509','-in',$RootCert,'-checkend','34128000','-noout')
    $null=Invoke-OpenSSL -ArgumentVector @('req','-in',$Csr,'-verify','-noout')
    $subject=Invoke-OpenSSL -ArgumentVector @('req','-in',$Csr,'-subject','-nameopt','RFC2253','-noout')
    if ($subject -cne ('subject=CN='+$script:InstallationId+' Cluster Intermediate CA')) { throw 'CSR_SUBJECT_REJECTED' }
    $description=Invoke-OpenSSL -ArgumentVector @('req','-in',$Csr,'-text','-noout')
    if ($description -notmatch 'Public Key Algorithm: rsaEncryption' -or $description -notmatch 'Public-Key: \(4096 bit\)') { throw 'CSR_KEY_ALGORITHM_REJECTED' }
    $serial=Invoke-OpenSSL -ArgumentVector @('rand','-hex','16')
    if ($serial -notmatch '^[a-f0-9]{32}$' -or $serial -eq ('0'*32)) { throw 'SERIAL_GENERATION_FAILED' }
    $signed=Join-Path $Destination 'ca-cert.pem'
    # Never copy CSR extensions: the issuer supplies fixed CA/path-length/key-usage controls.
    $null=Invoke-OpenSSL -ArgumentVector @('x509','-req','-in',$Csr,'-CA',$RootCert,'-CAkey',$RootKey,'-passin','stdin','-set_serial',('0x'+$serial),'-days','365','-sha256','-copy_extensions','none','-extfile',(Join-Path $script:PackageRoot 'intermediate.cnf'),'-extensions','intermediate_ca','-out',$signed) -Password $Password
    $null=Invoke-OpenSSL -ArgumentVector @('verify','-x509_strict','-CAfile',$RootCert,$signed)
    $requested=Invoke-OpenSSL -ArgumentVector @('req','-in',$Csr,'-pubkey','-noout')
    $issued=Invoke-OpenSSL -ArgumentVector @('x509','-in',$signed,'-pubkey','-noout')
    if ($requested -ne $issued) { throw 'ISSUED_PUBLIC_KEY_MISMATCH' }
    Remove-Item -LiteralPath $snapshot
    Copy-Item -LiteralPath $RootCert -Destination (Join-Path $Destination 'root-cert.pem')
    [IO.File]::WriteAllText((Join-Path $Destination 'cert-chain.pem'),([IO.File]::ReadAllText($signed)+[IO.File]::ReadAllText($RootCert)),[Text.UTF8Encoding]::new($false))
    @{ schema_version=1; installation_id=$script:InstallationId; source_revision=$script:SourceRevision; csr_sha256=$ExpectedCsrHash; root_certificate_sha256=(Get-FileHash -LiteralPath $RootCert).Hash; intermediate_certificate_sha256=(Get-FileHash -LiteralPath $signed).Hash; chain_verification='Passed'; production_readiness='Not verified' } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Destination 'signing-receipt.json') -Encoding UTF8
}

try {
    Assert-Package
    if ($ValidateOnly) { Write-Host 'VALIDATE_ONLY=PASSED_NO_KEY_GENERATION'; exit 0 }
    if ($script:PackageRoot -notmatch '^[A-Za-z]:\\') { throw 'COPY_PACKAGE_TO_LOCAL_WINDOWS_DISK_FIRST' }
    Assert-Disconnected
    $operationMutex=New-Object Threading.Mutex($false,('Local\HooshiXOfflineRootCA-'+[Security.Principal.WindowsIdentity]::GetCurrent().User.Value))
    $operationHeld=$false
    try { $operationHeld=$operationMutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $operationHeld=$true }
    if (-not $operationHeld) { throw 'OFFLINE_CA_OPERATION_ALREADY_RUNNING' }
    $script:OfflineRequired=$true

    Write-Host ('Offline computer UTC time: '+[DateTime]::UtcNow.ToString('yyyy-MM-dd HH:mm:ss',[Globalization.CultureInfo]::InvariantCulture))
    if ((Read-Host 'Confirm physical disconnection and correct clock: type OFFLINE') -cne 'OFFLINE') { throw 'OFFLINE_OPERATOR_CONFIRMATION_REQUIRED' }
    $state=Join-Path $env:LOCALAPPDATA ('HooshiX\OfflineRootCA\'+$script:InstallationId)
    $key=Join-Path $state 'root-key.enc.pem'
    $cert=Join-Path $state 'root-cert.pem'
    $password=$null
    try {
        if ($Action -eq 'CreateRoot') {
            Assert-NewRootAllowed
            if (Test-Path -LiteralPath $state) { throw 'EXISTING_ROOT_STATE_PRESERVED: do not regenerate; use run-backup.cmd if root creation previously succeeded, otherwise report this error.' }
            $parent=Split-Path $state
            if (-not (Test-Path -LiteralPath $parent)) { $null=New-Item -ItemType Directory -Path $parent }
            Assert-NoReparse $parent
            $volume=Get-Volume -DriveLetter ([IO.Path]::GetPathRoot($state).Substring(0,1))
            if ($volume.FileSystem -ne 'NTFS' -or $volume.DriveType -ne 'Fixed') { throw 'PRIVATE_STATE_REQUIRES_FIXED_NTFS_DISK' }
            $password=Read-KeyPassword
            while ($true) {
                $confirmation=Read-KeyPassword
                try { Assert-SamePassword $password $confirmation; break }
                catch { if ($_.Exception.Message -ne 'PASSWORD_CONFIRMATION_MISMATCH') { throw }; Write-Host 'PASSWORD_CONFIRMATION_MISMATCH: repeat the FIRST password; Ctrl+C cancels.' }
                finally { $confirmation.Dispose() }
            }
            $null=New-Item -ItemType Directory -Path $state
            Protect-StateDirectory $state
            Assert-NoReparse $state
            $bootstrap=Join-Path $state 'root-bootstrap.enc.pem'
            Write-Host 'ROOT_PHASE=GENERATING_ENCRYPTED_RSA4096'
            $null=Invoke-OpenSSL -ArgumentVector @('genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:4096','-aes-256-cbc','-pass','stdin','-out',$bootstrap) -Password $password
            $null=Invoke-OpenSSL -ArgumentVector @('pkcs8','-topk8','-in',$bootstrap,'-passin','stdin','-passout','stdin','-v2','aes-256-cbc','-v2prf','hmacWithSHA256','-iter','1000000','-out',$key) -Password $password
            Assert-EncryptedKey $key
            $initial=Invoke-OpenSSL -ArgumentVector @('pkey','-in',$bootstrap,'-passin','stdin','-pubout') -Password $password
            $strong=Invoke-OpenSSL -ArgumentVector @('pkey','-in',$key,'-passin','stdin','-pubout') -Password $password
            if ($initial -ne $strong) { throw 'REENCRYPTED_KEY_MISMATCH' }
            # Only this just-created encrypted temporary file is removed, after key equality verification.
            Remove-Item -LiteralPath $bootstrap
            $expires=[DateTime]::UtcNow.AddYears(10).ToString('yyyyMMddHHmmssZ',[Globalization.CultureInfo]::InvariantCulture)
            $null=Invoke-OpenSSL -ArgumentVector @('req','-new','-x509','-sha256','-key',$key,'-passin','stdin','-config',(Join-Path $script:PackageRoot 'root.cnf'),'-extensions','root_ca','-not_after',$expires,'-out',$cert) -Password $password
            Assert-KeyMatches $key $cert $password
            Write-PublicReceipt $state $cert 'Not verified' 'Not verified'
            Write-Host 'ROOT_CERTIFICATE=PASSED'
            Write-Host 'TWO_BACKUP_COPIES=NOT_VERIFIED_RUN_BACKUP'
        } elseif ($Action -eq 'Verify') {
            if ([string]::IsNullOrWhiteSpace($BackupDirectory)) { $BackupDirectory=Read-Host 'Backup folder copied from ONE USB, for example D:\HooshiX-OfflineRootCA-...' }
            Assert-Backup $BackupDirectory
            if ([string]::IsNullOrWhiteSpace($ExpectedCertificateSha256)) { $ExpectedCertificateSha256=Read-Host 'Root certificate FILE SHA256 from your separately trusted public certificate/receipt (64 hexadecimal characters)' }
            if ($ExpectedCertificateSha256 -notmatch '^[a-fA-F0-9]{64}$' -or $ExpectedCertificateSha256 -ne (Get-FileHash -LiteralPath (Join-Path $BackupDirectory 'root-cert.pem') -Algorithm SHA256).Hash) { throw 'TRUSTED_ROOT_CERTIFICATE_HASH_MISMATCH' }
            $password=Read-KeyPassword
            Assert-KeyMatches (Join-Path $BackupDirectory 'root-key.enc.pem') (Join-Path $BackupDirectory 'root-cert.pem') $password
            $receipt=[ordered]@{ schema_version=1; installation_id=$script:InstallationId; source_revision=$script:SourceRevision; root_certificate_sha256=(Get-FileHash -LiteralPath (Join-Path $BackupDirectory 'root-cert.pem')).Hash; offline_recovery_test='Passed'; production_readiness='Not verified' }
            $receiptPath=Join-Path $script:PackageRoot ('offline-recovery-receipt-'+[Guid]::NewGuid().ToString('N')+'.json')
            $receipt | ConvertTo-Json | Set-Content -LiteralPath $receiptPath -Encoding UTF8
            Write-Host 'OFFLINE_RECOVERY_TEST=PASSED'
            Write-Host ('PUBLIC_RECEIPT='+$receiptPath)
        } elseif ($Action -eq 'SignIntermediate') {
            if ([string]::IsNullOrWhiteSpace($BackupDirectory) -and (-not (Test-Path -LiteralPath $key) -or -not (Test-Path -LiteralPath $cert))) {
                $BackupDirectory=Read-Host 'Existing encrypted Root backup folder on this offline computer (do not create a new Root)'
                if ([string]::IsNullOrWhiteSpace($BackupDirectory)) { throw 'EXISTING_ROOT_BACKUP_REQUIRED' }
            }
            if (-not [string]::IsNullOrWhiteSpace($BackupDirectory)) {
                Assert-Backup $BackupDirectory
                $key=Join-Path $BackupDirectory 'root-key.enc.pem'
                $cert=Join-Path $BackupDirectory 'root-cert.pem'
                if ([string]::IsNullOrWhiteSpace($ExpectedCertificateSha256)) {
                    if (Test-ApprovedExistingRoot $cert) { $ExpectedCertificateSha256=$script:RootAuthority.existing_root_exception.certificate_file_sha256 }
                    else { $ExpectedCertificateSha256=Read-Host 'Root certificate FILE SHA256 from your separately trusted public certificate/receipt' }
                }
                if ($ExpectedCertificateSha256 -notmatch '\A[a-fA-F0-9]{64}\z' -or $ExpectedCertificateSha256 -ne (Get-FileHash -LiteralPath $cert -Algorithm SHA256).Hash) { throw 'TRUSTED_ROOT_CERTIFICATE_HASH_MISMATCH' }
            }
            $csr=Read-Host 'Public CSR file from the approved cluster trust boundary'
            $csrHash=Read-Host 'CSR FILE SHA256 obtained independently from the server'
            $password=Read-KeyPassword
            $signedOutput=Join-Path $script:PackageRoot ('signed-intermediate-'+[Guid]::NewGuid().ToString('N'))
            Sign-Intermediate $csr $csrHash $key $cert $password $signedOutput
            Write-Host ('PUBLIC_SIGNED_OUTPUT='+$signedOutput)
            Write-Host 'INTERMEDIATE_CERTIFICATE=PASSED_NOT_INSTALLED'
        } else {
            Assert-NoReparse $state
            Assert-NoReparse $key
            Assert-NoReparse $cert
            Assert-EncryptedKey $key
            $password=Read-KeyPassword
            Assert-KeyMatches $key $cert $password
            $first=Get-UsbDestination 'First backup USB drive letter, for example E'
            $second=Get-UsbDestination 'Second backup USB drive letter, for example F'
            if ($first.number -eq $second.number) { throw 'TWO_DISTINCT_PHYSICAL_USB_DISKS_REQUIRED' }
            $suffix=(Get-FileHash -LiteralPath $cert -Algorithm SHA256).Hash.Substring(0,16)
            $certHash=(Get-FileHash -LiteralPath $cert -Algorithm SHA256).Hash
            # Both devices must remain attached throughout the copy/verification phase.
            foreach ($destination in @($first,$second)) {
                $folder=$destination.drive+':\HooshiX-OfflineRootCA-'+$suffix
                if ((Get-Partition -DriveLetter $destination.drive | Get-Disk).Number -ne $destination.number) { throw 'USB_DEVICE_CHANGED' }
                $marker=Join-Path $folder 'backup-receipt.json'
                if (Test-Path -LiteralPath $folder) {
                    Assert-NoReparse $folder
                    if (-not (Test-Path -LiteralPath $marker)) { throw 'UNOWNED_BACKUP_DESTINATION_PRESERVED' }
                    Assert-NoReparse $marker
                    $owned=Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json
                    if ($owned.installation_id -ne $script:InstallationId -or $owned.root_certificate_sha256 -ne $certHash) { throw 'BACKUP_OWNERSHIP_MISMATCH' }
                    foreach ($item in Get-ChildItem -LiteralPath $folder -Force) {
                        if ($item.Name -notin @('root-key.enc.pem','root-cert.pem','backup-receipt.json') -or $item.PSIsContainer) { throw 'UNEXPECTED_BACKUP_CONTENT' }
                    }
                }
            }
            foreach ($destination in @($first,$second)) {
                $folder=$destination.drive+':\HooshiX-OfflineRootCA-'+$suffix
                if ((Get-Partition -DriveLetter $destination.drive | Get-Disk).Number -ne $destination.number) { throw 'USB_DEVICE_CHANGED' }
                if (-not (Test-Path -LiteralPath $folder)) {
                    $null=New-Item -ItemType Directory -Path $folder
                    @{ schema_version=1; installation_id=$script:InstallationId; root_certificate_sha256=$certHash; encrypted_key_sha256=(Get-FileHash -LiteralPath $key -Algorithm SHA256).Hash } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $folder 'backup-receipt.json') -Encoding UTF8
                }
                Assert-NoReparse $folder
                foreach ($source in @($key,$cert)) {
                    $copy=Join-Path $folder ([IO.Path]::GetFileName($source))
                    if (-not (Test-Path -LiteralPath $copy)) { Copy-Item -LiteralPath $source -Destination $copy }
                    Assert-NoReparse $copy
                    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash) { throw 'BACKUP_HASH_MISMATCH_EXISTING_DATA_PRESERVED' }
                }
                Assert-Backup $folder
                Assert-KeyMatches (Join-Path $folder 'root-key.enc.pem') (Join-Path $folder 'root-cert.pem') $password
                if ((Get-Partition -DriveLetter $destination.drive | Get-Disk).Number -ne $destination.number) { throw 'USB_DEVICE_CHANGED' }
            }
            Write-PublicReceipt $state $cert 'Passed' 'Not verified: operator must store the two devices in separate physical locations'
            Write-Host 'TWO_DISTINCT_USB_COPIES=PASSED'
            Write-Host 'STORE_BACKUP_DEVICES_IN_SEPARATE_LOCATIONS_AND_KEEP_THEM_OFFLINE'
        }
        if ($Action -ne 'Verify') {
            Write-Host ('PUBLIC_RETURN_FOLDER='+ (Join-Path $state 'ToOnline'))
            Write-Host 'COPY_ONLY_THE_TWO_FILES_IN_TOONLINE_BACK_TO_THE_CONNECTED_COMPUTER'
        }
        Write-Host 'PRODUCTION_READINESS=NOT_VERIFIED'
    } finally { if ($null -ne $password) { $password.Dispose() } }
    exit 0
} catch {
    Write-Host ('OFFLINE_CA_STOPPED: '+$_.Exception.Message)
    Write-Host 'Existing private state was retained. Send only this error text; never key files or passwords.'
    exit 1
} finally {
    if (Get-Variable operationMutex -ErrorAction SilentlyContinue) {
        if ($operationHeld) { $operationMutex.ReleaseMutex() }
        $operationMutex.Dispose()
    }
}
