package com.dominik.cardvault

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dominik.cardvault.data.sharing.PublicSharedBinderClient
import com.dominik.cardvault.data.sharing.sharedBinderTokenFromUri
import com.dominik.cardvault.ui.SharedBinderScreen
import com.dominik.cardvault.ui.theme.CardVaultTheme

/** Public read-only entry point, independent of login, local collection and the owner app screen. */
class SharedBinderActivity : ComponentActivity() {
    private var token by mutableStateOf<String?>(null)
    private var generation by mutableIntStateOf(0)
    private var viewerVisible by mutableStateOf(false)
    private val gateway = PublicSharedBinderClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        accept(intent)
        setContent {
            CardVaultTheme {
                key(generation) { SharedBinderScreen(token, gateway, viewerVisible, onClose = ::finish) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(intent)
    }

    private fun accept(intent: Intent?) {
        // Invalid replacement links also discard the old viewer and cancel its requests.
        token = if (intent?.action == Intent.ACTION_VIEW) sharedBinderTokenFromUri(intent.data) else null
        generation++
    }

    override fun onStart() { super.onStart(); viewerVisible = true }
    override fun onStop() { viewerVisible = false; super.onStop() }
}
