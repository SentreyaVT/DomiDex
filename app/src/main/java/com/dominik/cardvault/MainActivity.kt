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
                        onContinue = {
                            if (!savingWelcome.value) {
                                savingWelcome.value = true
                                welcomeSaveFailed.value = false
                                lifecycleScope.launch {
                                    try {
                                        if (withContext(Dispatchers.IO) { welcomePreferences.complete() }) showWelcome.value = false
                                        else welcomeSaveFailed.value = true
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        welcomeSaveFailed.value = true
                                    } finally {
                                        savingWelcome.value = false
                                    }
                                }
                            }
                        },
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
