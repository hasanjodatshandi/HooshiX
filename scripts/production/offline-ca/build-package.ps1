#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidatePattern('\A[a-z][a-z0-9-]{2,39}\z')][string]$InstallationId,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string]$ToolBundleDirectory,
    [string]$ArchivePath
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'

function Assert-PlainPath([string]$Path) {
    $item=Get-Item -LiteralPath $Path -Force
    while ($null -ne $item) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'REPARSE_PATH_REJECTED' }
        if ($item -is [IO.DirectoryInfo]) { $item=$item.Parent } else { $item=$item.Directory }
    }
}
function Get-Sha([string]$File) { return (Get-FileHash -LiteralPath $File -Algorithm SHA256).Hash.ToLowerInvariant() }

$work=$null
try {
    Assert-PlainPath $PSScriptRoot
    $lock=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'tools.lock.json') -Raw | ConvertFrom-Json
    $output=[IO.Path]::GetFullPath($OutputDirectory).TrimEnd('\')
    if ($output -notmatch '^[A-Za-z]:\\') { throw 'OUTPUT_REQUIRES_LOCAL_WINDOWS_DISK' }
    $repository=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..')).TrimEnd('\')
    if ($output.Equals($repository,[StringComparison]::OrdinalIgnoreCase) -or $output.StartsWith($repository+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'PACKAGE_OUTPUT_MUST_BE_OUTSIDE_REPOSITORY' }
    if ((Test-Path -LiteralPath $output) -or (Test-Path -LiteralPath ($output+'.zip'))) { throw 'EXISTING_OUTPUT_PRESERVED' }
    $parent=Split-Path $output
    Assert-PlainPath $parent
    $revision=(& git -C $repository rev-parse HEAD)
    if ($LASTEXITCODE -ne 0 -or $revision -notmatch '^[a-f0-9]{40}$') { throw 'SOURCE_REVISION_UNAVAILABLE' }
    $dirty=@(& git -C $repository status --porcelain -- scripts/production/offline-ca)
    if ($LASTEXITCODE -ne 0 -or $dirty.Count -ne 0) { throw 'COMMIT_REVIEWED_CA_SOURCE_BEFORE_PACKAGING' }
    $work=Join-Path $parent ('HooshiX-public-package-'+[Guid]::NewGuid().ToString('N'))
    $null=New-Item -ItemType Directory -Path $work
    if ([string]::IsNullOrWhiteSpace($ToolBundleDirectory)) {
        if ([string]::IsNullOrWhiteSpace($ArchivePath)) {
            $ArchivePath=Join-Path $work $lock.source_archive
            $previous=[Net.ServicePointManager]::SecurityProtocol
            try {
                [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12
                $url='https://github.com/git-for-windows/git/releases/download/v2.55.0.windows.3/'+$lock.source_archive
                Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $ArchivePath -TimeoutSec 180
            } finally { [Net.ServicePointManager]::SecurityProtocol=$previous }
        }
        Assert-PlainPath $ArchivePath
        if ((Get-Sha $ArchivePath) -ne $lock.source_archive_sha256) { throw 'VENDOR_ARCHIVE_HASH_MISMATCH' }
        $vendor=Join-Path $work 'vendor'
        $process=Start-Process -FilePath $ArchivePath -ArgumentList @('-y',('-o"'+$vendor+'"')) -WindowStyle Hidden -PassThru
        try {
            if (-not $process.WaitForExit(180000)) { $process.Kill(); throw 'VENDOR_EXTRACTION_DEADLINE' }
            if ($process.ExitCode -ne 0) { throw 'VENDOR_EXTRACTION_FAILED' }
        } finally { $process.Dispose() }
        $ToolBundleDirectory=Join-Path $work 'tool-bundle'
        $null=New-Item -ItemType Directory -Path (Join-Path $ToolBundleDirectory 'tools') -Force
        $null=New-Item -ItemType Directory -Path (Join-Path $ToolBundleDirectory 'licenses')
        foreach ($name in @('openssl.exe','libcrypto-3-x64.dll','libssl-3-x64.dll')) {
            Copy-Item -LiteralPath (Join-Path $vendor ('mingw64\bin\'+$name)) -Destination (Join-Path $ToolBundleDirectory ('tools\'+$name))
        }
        Copy-Item -LiteralPath (Join-Path $vendor 'mingw64\etc\ssl\openssl.cnf') -Destination (Join-Path $ToolBundleDirectory 'tools\openssl.cnf')
        Copy-Item -LiteralPath (Join-Path $vendor 'mingw64\share\licenses\openssl\LICENSE') -Destination (Join-Path $ToolBundleDirectory 'licenses\OpenSSL-LICENSE.txt')
    }
    foreach ($entry in $lock.files) {
        $source=Join-Path $ToolBundleDirectory $entry.path
        Assert-PlainPath $source
        if ((Get-Sha $source) -ne $entry.sha256) { throw ('TOOL_HASH_MISMATCH: '+$entry.path) }
    }
    $bundle=Join-Path $work 'package'
    $null=New-Item -ItemType Directory -Path $bundle
    foreach ($directory in @('tools','licenses')) { $null=New-Item -ItemType Directory -Path (Join-Path $bundle $directory) }
    foreach ($entry in $lock.files) { Copy-Item -LiteralPath (Join-Path $ToolBundleDirectory $entry.path) -Destination (Join-Path $bundle $entry.path) }
    foreach ($name in @('offline-ca.ps1','intermediate.cnf','run-root.cmd','run-backup.cmd','run-verify.cmd','run-sign.cmd','README-fa.txt')) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $bundle $name) }
    $config=@"
[req]
prompt = no
distinguished_name = root_dn
x509_extensions = root_ca
[root_dn]
O = HooshiX
CN = $InstallationId Offline Root CA
[root_ca]
basicConstraints = critical,CA:true,pathlen:1
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always
"@
    [IO.File]::WriteAllText((Join-Path $bundle 'root.cnf'),$config.Replace("`r`n","`n"),[Text.UTF8Encoding]::new($false))
    $entries=@()
    foreach ($file in Get-ChildItem -LiteralPath $bundle -File -Recurse) {
        $relative=$file.FullName.Substring($bundle.Length+1).Replace('\','/')
        if ($relative -match '\.(ps1|cmd|txt|cnf)$' -and $relative -notmatch '^(tools|licenses)/') {
            $normalized=[IO.File]::ReadAllText($file.FullName,[Text.UTF8Encoding]::new($false,$true)).Replace("`r`n","`n").Replace("`r","`n")
            [IO.File]::WriteAllText($file.FullName,$normalized,[Text.UTF8Encoding]::new($false))
            $entries+=@{ path=$relative; sha256=(Get-Sha $file.FullName); hash_mode='text-lf-v1' }
        } else { $entries+=@{ path=$relative; sha256=(Get-Sha $file.FullName) } }
    }
    @{ schema_version=2; source_revision=$revision; installation_id=$InstallationId; openssl=$lock.openssl; files=$entries } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $bundle 'package-manifest.json') -Encoding UTF8
    & powershell.exe -NoProfile -File (Join-Path $bundle 'offline-ca.ps1') -ValidateOnly
    if ($LASTEXITCODE -ne 0) { throw 'PACKAGE_VALIDATION_FAILED' }
    # An exclusive directory move publishes only a fully validated public package.
    [IO.Directory]::Move($bundle,$output)
    Compress-Archive -LiteralPath $output -DestinationPath ($output+'.zip')
    Write-Host ('PACKAGE='+$output)
    Write-Host ('ZIP_SHA256='+(Get-Sha ($output+'.zip')))
    Write-Host 'NO_PRIVATE_KEYS_GENERATED'
} finally {
    if ($null -ne $work -and (Test-Path -LiteralPath $work)) {
        $resolved=[IO.Path]::GetFullPath($work)
        if ($resolved.StartsWith($parent+'\HooshiX-public-package-',[StringComparison]::OrdinalIgnoreCase)) { Remove-Item -LiteralPath $resolved -Recurse -Force }
    }
}
