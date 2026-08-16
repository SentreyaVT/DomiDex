package com.dominik.cardvault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.ui.CardVaultApp
import com.dominik.cardvault.ui.theme.CardVaultTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = RoomCollectionRepository(CardVaultDatabase.get(applicationContext))
        lifecycleScope.launch { repository.seedIfNeeded() }
        setContent {
            CardVaultTheme {
                CardVaultApp(repository)
            }
        }
    }
}
