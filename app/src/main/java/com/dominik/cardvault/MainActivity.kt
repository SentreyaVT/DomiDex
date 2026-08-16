package com.dominik.cardvault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dominik.cardvault.ui.CardVaultApp
import com.dominik.cardvault.ui.theme.CardVaultTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CardVaultTheme {
                CardVaultApp()
            }
        }
    }
}
