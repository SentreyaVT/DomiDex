package com.dominik.cardvault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.sync.CardmarketSyncScheduler
import com.dominik.cardvault.data.content.ContentUpdateCoordinator
import com.dominik.cardvault.data.content.PlayUpdateGateway
import com.dominik.cardvault.ui.CardVaultApp
import com.dominik.cardvault.ui.theme.CardVaultTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = RoomCollectionRepository(CardVaultDatabase.get(applicationContext))
        val syncScheduler = CardmarketSyncScheduler(applicationContext)
        val contentUpdates = ContentUpdateCoordinator(applicationContext)
        lifecycleScope.launch { repository.seedIfNeeded() }
        syncScheduler.schedule()
        lifecycleScope.launch { contentUpdates.refreshIfConfigured(syncScheduler::refresh) }
        PlayUpdateGateway(this).checkForPlayUpdate()
        setContent {
            CardVaultTheme {
                CardVaultApp(repository, syncScheduler::refresh, contentUpdates.state)
            }
        }
    }
}
