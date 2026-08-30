package com.dominik.cardvault.updates

/**
 * Facts returned by an authenticated DomiDex update service.
 *
 * The download URL must be short-lived and must never be a GitHub token or a
 * permanent credential embedded in the APK.
 */
data class AndroidRelease(
    val versionName: String,
    val versionCode: Long,
    val releaseNotes: String,
    val downloadUrl: String,
    val sha256: String,
)

sealed interface AndroidUpdateCheckResult {
    data object UpToDate : AndroidUpdateCheckResult
    data class Available(val release: AndroidRelease) : AndroidUpdateCheckResult
    data class Unavailable(val reason: String) : AndroidUpdateCheckResult
    data class Failed(val safeMessage: String) : AndroidUpdateCheckResult
}

sealed interface AndroidUpdateInstallResult {
    data object InstallationRequested : AndroidUpdateInstallResult
    data class Unavailable(val reason: String) : AndroidUpdateInstallResult
    data class Failed(val safeMessage: String) : AndroidUpdateInstallResult
}

/**
 * Boundary for the future authenticated backend. GitHub credentials and
 * release access stay server-side; this app only receives a verified release.
 */
interface AndroidUpdateGateway {
    suspend fun checkForUpdate(installedVersionCode: Long): AndroidUpdateCheckResult
    suspend fun downloadAndRequestInstall(release: AndroidRelease): AndroidUpdateInstallResult
}

/** There is intentionally no direct private-GitHub fallback inside the APK. */
object UnconfiguredAndroidUpdateGateway : AndroidUpdateGateway {
    override suspend fun checkForUpdate(installedVersionCode: Long): AndroidUpdateCheckResult =
        AndroidUpdateCheckResult.Unavailable(
            "Der sichere DomiDex-Update-Dienst ist noch nicht verbunden."
        )

    override suspend fun downloadAndRequestInstall(release: AndroidRelease): AndroidUpdateInstallResult =
        AndroidUpdateInstallResult.Unavailable(
            "Der sichere DomiDex-Update-Dienst ist noch nicht verbunden."
        )
}
