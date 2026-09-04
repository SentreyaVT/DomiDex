[CmdletBinding()]
param(
    [string]$SigningDirectory = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.domidex-signing'),
    [string]$JavaDirectory = 'C:/Program Files/Android/Android Studio/jbr'
)
$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
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
        & ./gradlew.bat --no-daemon --no-configuration-cache :app:testDebugUnitTest :app:lintRelease `
            :app:bundleRelease :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Signed release bundle build failed.' }
    } finally { Pop-Location }
} finally {
    Remove-Item Env:DOMIDEX_UPLOAD_STORE_FILE, Env:DOMIDEX_UPLOAD_KEY_ALIAS,
        Env:DOMIDEX_UPLOAD_STORE_PASSWORD, Env:DOMIDEX_UPLOAD_KEY_PASSWORD -ErrorAction SilentlyContinue
    $env:JAVA_HOME = $savedJava
    $credential.Password.Dispose()
}
