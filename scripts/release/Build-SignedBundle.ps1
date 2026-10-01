[CmdletBinding()]
param(
    [string]$SigningDirectory = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.domidex-signing'),
    [string]$JavaDirectory = 'C:/Program Files/Android/Android Studio/jbr',
    [string]$PythonExecutable = 'python',
    [string]$BundletoolPath = '',
    [switch]$OfflineSymbols
)
$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
# Reject historical version reuse BEFORE credentials are read or a build starts.
$buildConfiguration = Get-Content -LiteralPath (Join-Path $repository 'app/build.gradle.kts') -Raw
$versionMatch = [regex]::Match($buildConfiguration, '(?m)^\s*versionCode\s*=\s*(\d+)\s*$')
if (-not $versionMatch.Success -or [int]$versionMatch.Groups[1].Value -lt 27) {
    throw 'This release pipeline is for v27+. Set the intended unused version explicitly; release 26 stays immutable.'
}
$archivePath = Join-Path $repository ('releases/v' + $versionMatch.Groups[1].Value)
if (Test-Path -LiteralPath $archivePath) { throw 'This release archive already exists; refusing to overwrite it.' }
if (-not $BundletoolPath) { $BundletoolPath = Join-Path $repository 'artifacts/release-tools/bundletool-all-1.18.3.jar' }
if (-not (Test-Path -LiteralPath $BundletoolPath -PathType Leaf)) { throw 'bundletool is missing. Supply -BundletoolPath.' }
& $PythonExecutable -B -m unittest discover -s (Join-Path $PSScriptRoot 'tests') -p 'test_*.py'
if ($LASTEXITCODE -ne 0) { throw 'Release pipeline tests failed.' }
$buildStartedAt = [DateTime]::UtcNow.ToString('o')
$buildGitCommit = (& git -C $repository rev-parse HEAD)
if ($LASTEXITCODE -ne 0) { throw 'Cannot identify release Git commit.' }
$keyPath = Join-Path $SigningDirectory 'domidex-upload.p12'
$credentialPath = Join-Path $SigningDirectory 'upload-credential.clixml'
if (-not (Test-Path -LiteralPath $keyPath -PathType Leaf) -or
    -not (Test-Path -LiteralPath $credentialPath -PathType Leaf)) { throw 'Protected upload key/credential missing.' }
$credential = Import-Clixml -LiteralPath $credentialPath
if ($credential -isnot [pscredential]) { throw 'Invalid protected signing credential.' }
$savedJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaDirectory
    $env:DOMIDEX_UPLOAD_STORE_FILE = $keyPath.Replace('\', '/')
    $env:DOMIDEX_UPLOAD_KEY_ALIAS = $credential.UserName
    $env:DOMIDEX_UPLOAD_STORE_PASSWORD = $credential.GetNetworkCredential().Password
    $env:DOMIDEX_UPLOAD_KEY_PASSWORD = $env:DOMIDEX_UPLOAD_STORE_PASSWORD
    Push-Location -LiteralPath $repository
    try {
        # Credentials must not persist in a daemon or the configuration cache.
        & ./gradlew.bat --no-daemon --no-configuration-cache -I scripts/release/release-metadata.init.gradle `
            :app:testDebugUnitTest :app:lintRelease :app:bundleRelease :app:assembleDebug `
            :app:assembleDebugAndroidTest :app:exportReleaseArchiveMetadata --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Signed release bundle build failed.' }
    } finally { Pop-Location }
} finally {
    Remove-Item Env:DOMIDEX_UPLOAD_STORE_FILE, Env:DOMIDEX_UPLOAD_KEY_ALIAS,
        Env:DOMIDEX_UPLOAD_STORE_PASSWORD, Env:DOMIDEX_UPLOAD_KEY_PASSWORD -ErrorAction SilentlyContinue
    $env:JAVA_HOME = $savedJava
    $credential.Password.Dispose()
}
# Run AFTER credentials have been removed from the process environment.
$archiveArguments = @(
    '-B', (Join-Path $PSScriptRoot 'archive_release.py'),
    '--aab', (Join-Path $repository 'app/build/outputs/bundle/release/app-release.aab'),
    '--mapping', (Join-Path $repository 'app/build/outputs/mapping/release/mapping.txt'),
    '--metadata', (Join-Path $repository 'app/build/outputs/release-archive/metadata.json'),
    '--java', (Join-Path $JavaDirectory 'bin/java.exe'), '--bundletool', $BundletoolPath,
    '--output', $archivePath, '--build-started-at', $buildStartedAt, '--build-git-commit', $buildGitCommit
)
if (-not $OfflineSymbols) { $archiveArguments += '--allow-download' }
$agpSymbols = Join-Path $repository 'app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip'
if (Test-Path -LiteralPath $agpSymbols -PathType Leaf) { $archiveArguments += @('--symbol-zip', $agpSymbols) }
$mergedNatives = Join-Path $repository 'app/build/intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib'
if (Test-Path -LiteralPath $mergedNatives -PathType Container) { $archiveArguments += @('--symbol-dir', $mergedNatives) }
& $PythonExecutable @archiveArguments
if ($LASTEXITCODE -ne 0) { throw 'Release was built but archival verification failed. Do not upload it.' }
Write-Host "Verified release archive: $archivePath"
