package com.dominik.cardvault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.sync.CardmarketSyncScheduler
import com.dominik.cardvault.data.sync.FirebaseCollectionSession
import com.dominik.cardvault.data.knowledge.ScanKnowledgeScheduler
import com.dominik.cardvault.data.content.ContentUpdateCoordinator
import com.dominik.cardvault.data.content.PlayUpdateGateway
import com.dominik.cardvault.ui.CardVaultApp
import com.dominik.cardvault.ui.LocalDataStartupContent
import com.dominik.cardvault.ui.DomiDexWelcome
import com.dominik.cardvault.ui.WelcomePreferences
import com.dominik.cardvault.ui.WelcomeAccountOutcome
import com.dominik.cardvault.ui.completeWelcomeAccountChoice
import com.dominik.cardvault.auth.FirebaseGoogleAuthGateway
import com.google.firebase.auth.FirebaseAuth
import com.dominik.cardvault.ui.launchSettingsIntent
import com.dominik.cardvault.ui.privacyPolicyIntent
import com.dominik.cardvault.data.backup.LocalDataStartupState
import com.dominik.cardvault.data.backup.prepareLocalDataStartup
import com.dominik.cardvault.ui.theme.CardVaultTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var collectionSession: FirebaseCollectionSession? = null
    private var playUpdateGateway: PlayUpdateGateway? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val database = CardVaultDatabase.get(applicationContext)
        val session = FirebaseCollectionSession(applicationContext, database)
        collectionSession = session
        val repository = RoomCollectionRepository(
            database = database,
            activeOwnerUid = session.ownerUid,
            onSyncNeeded = session::requestSync,
            onKeepCloudConflicts = session::keepCloudVersionsForConflicts,
            cloudState = session.cloudState,
        )
        val syncScheduler = CardmarketSyncScheduler(applicationContext)
        val contentUpdates = ContentUpdateCoordinator(applicationContext)
        val startup = mutableStateOf<LocalDataStartupState>(LocalDataStartupState.Loading)
        val welcomePreferences = WelcomePreferences(applicationContext)
        val showWelcome = mutableStateOf(false)
        val savingWelcome = mutableStateOf(false)
        val welcomeSaveFailed = mutableStateOf(false)
        val welcomeLoginMessage = mutableStateOf<String?>(null)
        val signingIn = mutableStateOf(false)
        val auth = FirebaseAuth.getInstance()
        val googleAuth = FirebaseGoogleAuthGateway(this, auth)
        val alreadySignedIn = mutableStateOf(auth.currentUser?.isAnonymous == false)
        fun finishWelcome(useGoogle: Boolean) {
            if (savingWelcome.value) return
            savingWelcome.value = true
            signingIn.value = useGoogle && auth.currentUser?.isAnonymous != false
            welcomeSaveFailed.value = false
            welcomeLoginMessage.value = null
            lifecycleScope.launch {
                try {
                    when (completeWelcomeAccountChoice(
                        useGoogle = useGoogle,
                        hasSignedInAccount = { auth.currentUser?.isAnonymous == false },
                        signIn = googleAuth::signIn,
                        completePresentation = { withContext(Dispatchers.IO) { welcomePreferences.complete() } },
                    )) {
                        WelcomeAccountOutcome.COMPLETE -> showWelcome.value = false
                        WelcomeAccountOutcome.CANCELLED -> welcomeLoginMessage.value =
                            "Anmeldung abgebrochen. Du kannst es erneut versuchen oder vorerst lokal starten."
                        WelcomeAccountOutcome.LOGIN_FAILED -> welcomeLoginMessage.value =
                            "Die Google-Anmeldung hat nicht funktioniert. Bitte prüfe deine Verbindung und versuche es erneut."
                        WelcomeAccountOutcome.SAVE_FAILED -> welcomeSaveFailed.value = true
                    }
                } finally {
                    alreadySignedIn.value = auth.currentUser?.isAnonymous == false
                    signingIn.value = false
                    savingWelcome.value = false
                }
            }
        }
        var startupJob: Job? = null
        fun prepareLocalData() {
            if (startupJob?.isActive == true) return
            startupJob = lifecycleScope.launch {
                startup.value = LocalDataStartupState.Loading
                val result = prepareLocalDataStartup(
                    recover = { withContext(Dispatchers.IO) {
                        com.dominik.cardvault.data.backup.BackupImportJournal(applicationContext, database).recover()
                        com.dominik.cardvault.data.backup.BackupSettings.applyPending(applicationContext, database)
                    } },
                    seed = {
                        repository.seedIfNeeded()
                        // Read after backup presentation settings have been safely recovered.
                        showWelcome.value = withContext(Dispatchers.IO) { welcomePreferences.shouldShow() }
                    },
                    startCollectionSync = { withContext(Dispatchers.IO) { session.start() } },
                    onPhaseChanged = { startup.value = LocalDataStartupState.Preparing(it) },
                )
                startup.value = result
                if (result is LocalDataStartupState.Failed) {
                    android.util.Log.e("DomiDexStartup", "Local preparation failed (${result.failureType}).")
                } else {
                    // A restored, authenticated session needs no repeated account
                    // chooser or second confirmation; only presentation is completed.
                    if (showWelcome.value && auth.currentUser?.isAnonymous == false) finishWelcome(useGoogle = false)
                    syncScheduler.schedule()
                    ScanKnowledgeScheduler.schedule(applicationContext)
                    lifecycleScope.launch { contentUpdates.refreshIfConfigured(syncScheduler::refresh) }
                    playUpdateGateway = PlayUpdateGateway(this@MainActivity).also(PlayUpdateGateway::checkForPlayUpdate)
                }
            }
        }
        prepareLocalData()
        // Only the compact source-set index is warmed. Network work never gates the UI,
        // and all picker/scanner/binder catalogue instances reuse this metadata cache.
        lifecycleScope.launch(Dispatchers.IO) {
            try { PokemonVisualCatalog().warmUp() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Existing local collection remains usable offline. */ }
        }
        setContent {
            CardVaultTheme {
                if (startup.value == LocalDataStartupState.Ready) {
                    if (showWelcome.value) DomiDexWelcome(
                        saving = savingWelcome.value,
                        saveFailed = welcomeSaveFailed.value,
                        onContinue = { finishWelcome(useGoogle = false) },
                        onGoogleSignIn = { finishWelcome(useGoogle = true) },
                        signingIn = signingIn.value,
                        loginMessage = welcomeLoginMessage.value,
                        alreadySignedIn = alreadySignedIn.value,
                        onPrivacy = {
                            if (!launchSettingsIntent(this@MainActivity, privacyPolicyIntent())) {
                                android.widget.Toast.makeText(this@MainActivity,
                                    "Datenschutz öffnen nicht möglich: https://domidex.web.app/datenschutz",
                                    android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                    ) else CardVaultApp(repository, syncScheduler::refresh, contentUpdates.state, true, startupPresentationHandled = true)
                } else {
                    LocalDataStartupContent(startup.value, retry = ::prepareLocalData)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        playUpdateGateway?.onResume()
    }

    override fun onDestroy() {
        playUpdateGateway?.close()
        playUpdateGateway = null
        collectionSession?.close()
        collectionSession = null
        super.onDestroy()
    }
}
