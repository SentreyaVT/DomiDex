# Run only after the operator authorizes creation of a new upload key.
[CmdletBinding()]
param(
    [string]$SigningDirectory = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.domidex-signing'),
    [string]$JavaDirectory = 'C:/Program Files/Android/Android Studio/jbr'
)
$ErrorActionPreference = 'Stop'
$keytool = Join-Path $JavaDirectory 'bin/keytool.exe'
if (-not (Test-Path -LiteralPath $keytool -PathType Leaf)) { throw 'keytool is not available.' }
if (Test-Path -LiteralPath $SigningDirectory) { throw 'Signing directory already exists. Never overwrite an upload key.' }
$directory = New-Item -ItemType Directory -Path $SigningDirectory
$owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = [Security.AccessControl.DirectorySecurity]::new()
$acl.SetOwner($owner)
$acl.SetAccessRuleProtection($true, $false)
$inherit = [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'
foreach ($identity in @($owner, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
        $identity, 'FullControl', $inherit, 'None', 'Allow'))
}
Set-Acl -LiteralPath $directory.FullName -AclObject $acl
$keyPath = Join-Path $directory.FullName 'domidex-upload.p12'
$credentialPath = Join-Path $directory.FullName 'upload-credential.clixml'
$certificatePath = Join-Path $directory.FullName 'domidex-upload-cert.pem'
$random = [byte[]]::new(32)
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $rng.GetBytes($random)
    $password = [Convert]::ToBase64String($random)
    $securePassword = ConvertTo-SecureString $password -AsPlainText -Force
    # Export-Clixml encrypts SecureString with Windows DPAPI for this user/machine.
    [pscredential]::new('domidex-upload', $securePassword) | Export-Clixml -LiteralPath $credentialPath
    $env:DOMIDEX_UPLOAD_STORE_PASSWORD = $password
    & $keytool -genkeypair -keystore $keyPath -storetype PKCS12 -alias domidex-upload `
        -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=DomiDex Upload,C=AT' `
        -storepass:env DOMIDEX_UPLOAD_STORE_PASSWORD -keypass:env DOMIDEX_UPLOAD_STORE_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Upload key generation failed. Inspect the protected directory; nothing was overwritten.' }
    & $keytool -exportcert -rfc -keystore $keyPath -alias domidex-upload `
        -storepass:env DOMIDEX_UPLOAD_STORE_PASSWORD -file $certificatePath
    if ($LASTEXITCODE -ne 0) { throw 'Public upload certificate export failed.' }
    & $keytool -list -v -keystore $keyPath -alias domidex-upload -storepass:env DOMIDEX_UPLOAD_STORE_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Upload certificate verification failed.' }
    Write-Output 'Upload key created. Password not displayed. Back up the key AND password securely before relying on another computer.'
} finally {
    Remove-Item Env:DOMIDEX_UPLOAD_STORE_PASSWORD -ErrorAction SilentlyContinue
    [Array]::Clear($random, 0, $random.Length)
    $rng.Dispose()
    $password = $null
    if ($securePassword) { $securePassword.Dispose() }
}
