package com.dominik.cardvault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.sync.CardmarketSyncScheduler
import com.dominik.cardvault.data.sync.FirebaseCollectionSession
import com.dominik.cardvault.data.knowledge.ScanKnowledgeScheduler
import com.dominik.cardvault.data.content.ContentUpdateCoordinator
import com.dominik.cardvault.data.content.PlayUpdateGateway
import com.dominik.cardvault.ui.CardVaultApp
import com.dominik.cardvault.ui.theme.CardVaultTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var collectionSession: FirebaseCollectionSession? = null
    private var playUpdateGateway: PlayUpdateGateway? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val database = CardVaultDatabase.get(applicationContext)
        val session = FirebaseCollectionSession(applicationContext, database).also { it.start() }
        collectionSession = session
        val repository = RoomCollectionRepository(
            database = database,
            activeOwnerUid = session.ownerUid,
            onSyncNeeded = session::requestSync,
            onKeepCloudConflicts = session::keepCloudVersionsForConflicts,
        )
        val syncScheduler = CardmarketSyncScheduler(applicationContext)
        val contentUpdates = ContentUpdateCoordinator(applicationContext)
        lifecycleScope.launch { repository.seedIfNeeded() }
        syncScheduler.schedule()
        // Separate best-effort outbox: failures here can never block collection persistence/sync.
        ScanKnowledgeScheduler.schedule(applicationContext)
        lifecycleScope.launch { contentUpdates.refreshIfConfigured(syncScheduler::refresh) }
        playUpdateGateway = PlayUpdateGateway(this).also(PlayUpdateGateway::checkForPlayUpdate)
        setContent {
            CardVaultTheme {
                CardVaultApp(repository, syncScheduler::refresh, contentUpdates.state)
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
