package com.dominik.cardvault.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.dominik.cardvault.R
import com.dominik.cardvault.auth.FirebaseGoogleAuthGateway
import com.dominik.cardvault.auth.FirebaseGitHubAuthGateway
import com.dominik.cardvault.auth.GoogleAccountState
import com.dominik.cardvault.auth.GoogleLoginResult
import com.dominik.cardvault.auth.GitHubLoginResult
import com.dominik.cardvault.model.*
import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import com.dominik.cardvault.data.catalog.PokemonSetRegistry
import com.dominik.cardvault.data.WachsendesChaosMasterSet
import com.dominik.cardvault.data.content.ContentUpdateState
import com.dominik.cardvault.discord.DiscordDevUpdatePayload
import com.dominik.cardvault.discord.DiscordDevUpdatePublishResult
import com.dominik.cardvault.discord.UnconfiguredDiscordDevUpdatePublisher
import com.dominik.cardvault.updates.AndroidUpdateCheckResult
import com.dominik.cardvault.updates.UnconfiguredAndroidUpdateGateway
import com.dominik.cardvault.ui.theme.*
import coil3.compose.AsyncImage
import java.text.NumberFormat
import java.text.Normalizer
import java.util.Locale
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import com.google.firebase.auth.FirebaseAuth

private enum class Destination(@param:StringRes val label: Int, val glyph: String) {
    DASHBOARD(R.string.nav_portfolio, "⌂"), SCANNER(R.string.nav_scanner, "▣"),
    BINDER(R.string.nav_binder, "▥"), GRADING(R.string.nav_grading, "◇"), SETTINGS(R.string.nav_settings, "⚙")
}

private data class TutorialStep(val title: String, val body: String, val destination: Destination)

private val relaxedTutorialSteps = listOf(
    TutorialStep("Dein Binder", "Hier siehst du deine Sets wie in einem echten Album. Dunkle Karten sind noch nicht in deiner Sammlung – antippen zeigt trotzdem ihre Details.", Destination.BINDER),
    TutorialStep("Karte scannen", "Karte ruhig in den Rahmen legen und Aufnahme drücken. DomiDex liest unten Set-Kürzel und Nummer; bei Unsicherheit wählst du sie einfach aus.", Destination.SCANNER),
    TutorialStep("Centering prüfen", "Für eine ruhige Vorab-Prüfung nimmst du Vorder- und Rückseite getrennt auf. Außen- und Innenrahmen lassen sich danach präzise verschieben.", Destination.GRADING),
    TutorialStep("Alles bei dir", "Preise, Binder und Konto findest du in Settings. Deine Sammlung bleibt lokal nutzbar, auch ohne Anmeldung.", Destination.SETTINGS),
)

@Composable fun CardVaultApp(repository: CollectionRepository, refreshCatalog: () -> Unit = {}, contentUpdateState: StateFlow<ContentUpdateState>? = null) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("cardvault_ui", Context.MODE_PRIVATE) }
    var focusChoice by rememberSaveable { mutableStateOf(preferences.getString("dashboard_focus", "auto") ?: "auto") }
    var destination by rememberSaveable { mutableStateOf(Destination.DASHBOARD) }
    // A short launch moment is intentionally shown on every cold app start while
    // local data, scheduled sync and authentication state warm up in parallel.
    var showArrival by rememberSaveable { mutableStateOf(true) }
    var showTutorialPrompt by rememberSaveable { mutableStateOf(false) }
    var tutorialStep by rememberSaveable { mutableIntStateOf(-1) }
    val portfolio by repository.portfolio.collectAsState(initial = PortfolioSummary.Empty)
    val syncStatus by repository.catalogSyncStatus.collectAsState(initial = CatalogSyncStatus("IDLE", null, null, 0, 0, null))
    val contentUpdates by (contentUpdateState ?: remember { kotlinx.coroutines.flow.MutableStateFlow(ContentUpdateState()) }).collectAsState()
    Scaffold(containerColor = Color.Transparent, bottomBar = {
        // The launch moment is deliberately the only visible surface. Navigation
        // appears only after local warm-up has completed.
        if (!showArrival && destination != Destination.SCANNER) DomiDexBottomNavigation(destination) { destination = it }
    }) { padding -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(VaultNight, Color(0xFF121024), VaultNight))).padding(padding)) {
        AnimatedContent(
            targetState = destination,
            transitionSpec = { (fadeIn(tween(DomiMotion.Fast)) + slideInHorizontally(tween(DomiMotion.Fast)) { it / 12 }) togetherWith (fadeOut(tween(DomiMotion.Fast)) + slideOutHorizontally(tween(DomiMotion.Fast)) { -it / 16 }) },
            label = "domidex-destination",
        ) { shownDestination ->
            when (shownDestination) {
                Destination.DASHBOARD -> DashboardScreen(portfolio, contentUpdates, focusChoice, { choice -> focusChoice = choice; preferences.edit().putString("dashboard_focus", choice).apply() }, openBinder = { destination = Destination.BINDER }, openScanner = { destination = Destination.SCANNER })
                Destination.SCANNER -> ScannerScreen(repository) { destination = Destination.DASHBOARD }
                Destination.BINDER -> BinderScreen(portfolio.cards, repository)
                Destination.GRADING -> GradingScreen()
                Destination.SETTINGS -> SettingsScreen(repository, portfolio, refreshCatalog)
            }
        }
        AnimatedVisibility(
            visible = showArrival,
            enter = fadeIn(animationSpec = tween(durationMillis = 180)),
            exit = fadeOut(animationSpec = tween(durationMillis = 320)),
        ) {
            ArrivalMoment(onContinue = {
                preferences.edit().putBoolean("arrival_seen", true).apply()
                showArrival = false
                if (!preferences.getBoolean("relaxed_tutorial_answered_v1", false)) showTutorialPrompt = true
            })
        }
        if (showTutorialPrompt) TutorialOfferDialog(
            onStart = {
                preferences.edit().putBoolean("relaxed_tutorial_answered_v1", true).apply()
                showTutorialPrompt = false
                tutorialStep = 0
                destination = relaxedTutorialSteps.first().destination
            },
            onDecline = {
                preferences.edit().putBoolean("relaxed_tutorial_answered_v1", true).apply()
                showTutorialPrompt = false
            },
        )
        if (tutorialStep in relaxedTutorialSteps.indices) RelaxedTutorialDialog(
            stepIndex = tutorialStep,
            onNext = {
                if (tutorialStep == relaxedTutorialSteps.lastIndex) tutorialStep = -1
                else {
                    tutorialStep += 1
                    destination = relaxedTutorialSteps[tutorialStep].destination
                }
            },
            onFinish = { tutorialStep = -1 },
        )
    } }
}

/** GPU-friendly, screen-local navigation; the Binder remains the visual center action. */
@Composable private fun DomiDexBottomNavigation(selected: Destination, onSelected: (Destination) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        color = VaultNightRaised.copy(alpha = .96f),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, MewPlum.copy(alpha = .42f)),
        shadowElevation = 12.dp,
    ) {
        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Destination.entries.forEach { item ->
                val active = selected == item
                val iconLift by animateFloatAsState(if (active) -3f else 0f, tween(DomiMotion.Fast), label = "nav-lift-${item.name}")
                val iconScale by animateFloatAsState(if (active) if (item == Destination.BINDER) 1.16f else 1.06f else 1f, tween(DomiMotion.Fast), label = "nav-scale-${item.name}")
                val tint by animateFloatAsState(if (active) 1f else .55f, tween(DomiMotion.Fast), label = "nav-tint-${item.name}")
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable(interactionSource = interaction, indication = null) { onSelected(item) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (item == Destination.BINDER) {
                        val borderAlpha = if (active) {
                            rememberInfiniteTransition(label = "binder-nav-pulse").animateFloat(.40f, .78f, infiniteRepeatable(tween(2_600), RepeatMode.Reverse), label = "binder-nav-glow").value
                        } else .25f
                        Surface(
                            modifier = Modifier.size(56.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale; translationY = iconLift },
                            color = if (active) MewPlumDeep else VaultPlumSurface,
                            shape = RoundedCornerShape(22.dp),
                            border = BorderStroke(1.dp, MewRose.copy(alpha = borderAlpha)),
                            shadowElevation = if (active) 10.dp else 0.dp,
                        ) { Box(contentAlignment = Alignment.Center) { Text(item.glyph, color = if (active) MewRose else VaultTextMuted, fontSize = 23.sp, fontWeight = FontWeight.Black) } }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.graphicsLayer { translationY = iconLift }) {
                            Text(item.glyph, modifier = Modifier.clearAndSetSemantics { }, color = if (active) MewRose.copy(alpha = tint) else VaultTextMuted.copy(alpha = tint), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(stringResource(item.label), color = if (active) VaultText else VaultTextMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Box(Modifier.padding(top = 3.dp).width(if (active) 18.dp else 4.dp).height(2.dp).background(if (active) MewIce else Color.Transparent, RoundedCornerShape(2.dp)))
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun TutorialOfferDialog(onStart: () -> Unit, onDecline: () -> Unit) = AlertDialog(
    onDismissRequest = onDecline,
    title = { Text("Kleine DomiDex-Tour?") },
    text = { Text("Möchtest du eine kurze, entspannte Einführung? Sie zeigt Binder, Scanner, Centering und Settings – ohne etwas zu verändern.", color = VaultTextMuted) },
    confirmButton = { TextButton(onClick = onStart) { Text("Ja, kurz zeigen") } },
    dismissButton = { TextButton(onClick = onDecline) { Text("Nein, nicht mehr fragen") } },
)

@Composable private fun RelaxedTutorialDialog(stepIndex: Int, onNext: () -> Unit, onFinish: () -> Unit) {
    val step = relaxedTutorialSteps[stepIndex]
    AlertDialog(
        onDismissRequest = onFinish,
        title = { Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(step.title); Text("Kurze Tour · ${stepIndex + 1}/${relaxedTutorialSteps.size}", color = MewRoseSoft, fontSize = 12.sp) } },
        text = { Text(step.body, color = VaultTextMuted) },
        confirmButton = { TextButton(onClick = onNext) { Text(if (stepIndex == relaxedTutorialSteps.lastIndex) "Fertig" else "Weiter") } },
        dismissButton = { TextButton(onClick = onFinish) { Text("Tour beenden") } },
    )
}

@Composable private fun SettingsScreen(repository: CollectionRepository, portfolio: PortfolioSummary, refreshCatalog: () -> Unit) {
    val context = LocalContext.current
    val sync by repository.catalogSyncStatus.collectAsState(initial = CatalogSyncStatus("IDLE", null, null, 0, 0, null))
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var passwordDialog by rememberSaveable { mutableStateOf(false) }
    var betaPassword by rememberSaveable { mutableStateOf("") }
    var adminUnlocked by rememberSaveable { mutableStateOf(false) }
    val accountActive = remember { FirebaseAuth.getInstance().currentUser != null }
    val categories = listOf(
        "Konto" to if (accountActive) "Google-Anmeldung auf diesem Gerät aktiv" else "Optional · Sammlung bleibt lokal nutzbar",
        "App" to "Sprache, lokale Inhalte und Updates",
        "Update" to "Aktuellste DomiDex-Version sicher prüfen",
        "Scanner" to "Kamera, Fotos und Erkennung verständlich erklärt",
        "Binder & Sync" to "Lokale Sammlung und Katalogstatus",
        "Preise" to "Cardmarket-Quelle und Aktualität",
        "Sammlung & Backup" to "Lokale Sicherung als DomiDex-Datei",
        "Datenschutz" to "Deine Daten, Einwilligungen und DSGVO-Hinweise",
        "Hilfe" to "Schnellstart, Korrekturen und Support",
        "Über DomiDex" to "Wofür DomiDex steht – und wofür nicht",
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("Einstellungen", "Dein DomiDex", "Verstehen, steuern, bei dir behalten.") }
        items(categories, key = { it.first }) { (title, subtitle) -> OutlinedCard(onClick = { detail = title }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = VaultNightRaised)) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = VaultTextMuted, fontSize = 11.sp) }; Text("›", color = MewRose, fontSize = 24.sp) } } }
        item { OutlinedButton(onClick = { passwordDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Admin / Entwickler") } }
    }
    detail?.let { section ->
        if (section == "Konto") AccountSettingsDialog(onDismiss = { detail = null })
        else if (section == "Sammlung & Backup") CollectionBackupDialog(repository, portfolio.cards, onDismiss = { detail = null })
        else SettingsDetailDialog(section, sync, context, onRefresh = refreshCatalog, onDismiss = { detail = null })
    }
    if (passwordDialog) AlertDialog(onDismissRequest = { passwordDialog = false }, title = { Text("Beta-Administration") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Nur für die Beta. Der Zugriff bleibt lokal auf diesem Gerät.", color = VaultTextMuted, fontSize = 13.sp); OutlinedTextField(betaPassword, { betaPassword = it }, label = { Text("Beta-Passwort") }, visualTransformation = PasswordVisualTransformation(), singleLine = true) } }, confirmButton = { TextButton(onClick = { if (betaPassword == "DomiDexAdmin95.!?") { adminUnlocked = true; passwordDialog = false; betaPassword = "" } }) { Text("Öffnen") } }, dismissButton = { TextButton(onClick = { passwordDialog = false; betaPassword = "" }) { Text(stringResource(R.string.action_cancel)) } })
    if (adminUnlocked) AdminSupportDialog(context, portfolio, sync, showDiscordDevUpdate = isDebuggableBuild(context), onDismiss = { adminUnlocked = false })
}

@Composable private fun AccountSettingsDialog(onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Konto") },
    text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Dein Konto verbindet nur die Anmeldung mit diesem Gerät – nicht deine Karten automatisch mit einer Cloud.", color = VaultTextMuted, fontSize = 12.sp)
        GoogleAccountCard()
        SettingsInfoBlock("Was dein Konto kann", "Du kannst dich mit Google anmelden und später wieder abmelden. Die Anmeldung bestätigt deine Identität für künftige, ausdrücklich freigeschaltete Dienste.")
        SettingsInfoBlock("Was privat bleibt", "Binder, Karten, Fotos, Preisnotizen und Scanner-Aufnahmen bleiben lokal. DomiDex veröffentlicht oder synchronisiert davon nichts automatisch.")
        SettingsInfoBlock("Kein versteckter Sonderzugriff", "Eine Google-Anmeldung verleiht keine Admin-, Support- oder Pro-Rechte. Solche Rollen müssten serverseitig separat bestätigt werden.")
    } },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
)

@Composable private fun SettingsDetailDialog(section: String, sync: CatalogSyncStatus, context: Context, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val online = remember { isOnline(context) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(section) }, text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(max = 510.dp)) {
        item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { when (section) {
            "App" -> {
                SettingsInfoBlock("Ruhig, auch ohne Netz", "DomiDex zeigt deine lokale Sammlung zuerst. Katalog-Inhalte können später aktualisiert werden und bleiben zwischengespeichert, wenn du offline bist.")
                SettingsInfoBlock("Beta bedeutet ehrlich testen", "Neue Funktionen dürfen sich weiterentwickeln. Vor allem Preise, Katalogabdeckung, Kamera und Grading sind keine Garantien.")
            }
            "Update" -> AndroidUpdateSettings(context)
            "Scanner" -> {
                SettingsInfoBlock("Du bestimmst den Moment", "Kamera und Galerie werden erst geöffnet, wenn du Aufnahme oder Foto auswählen antippst. Eine Karte wird lokal auf Rahmen, Text, Set und Nummer geprüft.")
                SettingsInfoBlock("Hilfe statt Raten", "Wenn Set oder Nummer nicht sicher lesbar sind, bietet DomiDex die manuelle Zuordnung an. Es wird keine beliebige Karte als Treffer ausgegeben.")
                SettingsInfoBlock("Hardware bleibt Hardware", "Blitz, Fokus und Kamerawahl erscheinen nur, wenn dein Gerät sie wirklich unterstützt. Normales Licht und ein ruhiger, gerader Kartenrahmen helfen am meisten.")
            }
            "Binder & Sync" -> SettingsInfoBlock("Dein Stand", "${if (online) "Online" else "Offline"}. Binder und lokale Karten bleiben immer nutzbar. Letzter Katalogstatus: ${sync.state}.")
            "Preise" -> SettingsInfoBlock("Eine Quelle, klar benannt", "Der Marktwert nutzt ausschließlich den zuletzt bekannten Cardmarket-Trendpreis eines ausdrücklich zugeordneten Produkts. Ohne exakte Zuordnung zeigt DomiDex keinen Preis; tatsächliche Verkaufspreise können abweichen.")
            "Datenschutz" -> {
                SettingsInfoBlock("Datensparsam by design", "Karten, Binder, Notizen und Fotos liegen lokal auf deinem Gerät. Es gibt keine automatische Bild-, Diagnose- oder Sammlungsübertragung.")
                SettingsInfoBlock("Kamera und Galerie", "Diese Berechtigungen nutzt DomiDex nur nach deiner Aktion zum Scannen oder Prüfen. Eine Community-Fotoübermittlung ist in dieser Beta nicht aktiv und würde immer eine eigene Einwilligung verlangen.")
                SettingsInfoBlock("Google-Anmeldung", "Wenn du Google aktiv nutzt, verarbeitet Firebase Authentication die für die Anmeldung notwendigen Kontodaten. DomiDex bekommt daraus keine Zahlungsdaten und keine geheimen Google-Zugangsdaten.")
                SettingsInfoBlock("Deine DSGVO-Rechte", "Du kannst Auskunft, Berichtigung, Löschung, Einschränkung und Datenübertragbarkeit verlangen sowie Einwilligungen widerrufen. Weil Daten derzeit lokal bleiben, kannst du Karten und Fotos direkt in der App bzw. auf deinem Gerät löschen.")
                SettingsInfoBlock("Vor öffentlichem Release", "Eine finale Datenschutzerklärung braucht noch die verantwortliche Stelle, Kontaktmöglichkeit, Aufbewahrungsfristen und eine Prüfung der tatsächlich aktivierten Online-Dienste. Dieser Abschnitt ist technische Transparenz, keine Rechtsberatung.")
            }
            "Hilfe" -> {
                SettingsInfoBlock("Schnellstart", "Übersicht → Sammelalben → Set öffnen → Karte auswählen oder mit Scanner hinzufügen.")
                SettingsInfoBlock("Versehentlich hinzugefügt?", "Öffne die Karte im Binder und wähle „Aus Binder entfernen“. DomiDex fragt zweimal nach. Auch ein kompletter Binder ist erst nach zwei Bestätigungen löschbar.")
                SettingsInfoBlock("Unsicherer Scan", "Wähle das Set im Menü und tippe nur die Nummer von unten auf der Karte ein, zum Beispiel 122/084.")
                DiscordSupportCard(context)
            }
            else -> {
                SettingsInfoBlock("DomiDex", "Dein visueller Begleiter für Pokémon-Karten: Binder zuerst, Kartenplätze nachvollziehbar, Preise klar gekennzeichnet und Scanner-Hinweise ohne falsche Gewissheit.")
                SettingsInfoBlock("Unser Fokus", "DomiDex zeigt auch nicht besessene Karten als gedimmte Referenz, damit du deinen Fortschritt siehst – nicht nur eine Liste leerer Plätze.")
                SettingsInfoBlock("Grenzen, die zählen", "Keine professionelle Grading-Note, keine garantierten Marktpreise und keine Behauptung einer vollständigen Welt-Kartenabdeckung ohne nachweisbare Quelle.")
                SettingsInfoBlock("Beta", "Die App wächst mit verifizierten Katalogdaten und bewusst aktivierten Diensten. Kernfunktionen bleiben nicht hinter Login oder Unterstützung verborgen.")
            }
        } } }
        if (section == "Binder & Sync" || section == "Preise") item { Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text(if (sync.state == "RUNNING") "Aktualisierung läuft …" else "Jetzt aktualisieren") } }
    } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } })
}

@Composable private fun AndroidUpdateSettings(context: Context) {
    val scope = rememberCoroutineScope()
    val gateway = remember { UnconfiguredAndroidUpdateGateway }
    val githubGateway = remember { FirebaseGitHubAuthGateway() }
    val installedVersion = remember(context) { installedDomiDexVersion(context) }
    var checking by rememberSaveable { mutableStateOf(false) }
    var statusText by rememberSaveable { mutableStateOf<String?>(null) }
    var githubAccount by remember { mutableStateOf(githubGateway.currentAccount()) }
    var githubWorking by rememberSaveable { mutableStateOf(false) }
    val activity = context as? Activity

    SettingsInfoBlock(
        "Installierte Version",
        "DomiDex ${installedVersion.name} · Build ${installedVersion.code}"
    )
    SettingsInfoBlock(
        "Sicher aktualisieren",
        "DomiDex lädt Updates erst nach einer erfolgreichen, serverseitigen Berechtigungsprüfung. GitHub-Zugangsdaten werden niemals in der App gespeichert. Android zeigt vor einer Installation immer seine eigene Bestätigung an."
    )
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = VaultNightRaised),
        border = BorderStroke(1.dp, VaultOutline),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("GitHub-Zugang", color = MewRoseSoft, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(
                if (githubAccount.isSignedIn) "GitHub-Sitzung auf diesem Gerät aktiv${githubAccount.displayName?.let { ": $it" } ?: "."}"
                else "Melde dich einmal mit deinem freigegebenen GitHub-Konto an.",
                color = VaultTextMuted,
                fontSize = 12.sp,
            )
            if (!githubAccount.isSignedIn) {
                OutlinedButton(
                    onClick = {
                        val host = activity ?: return@OutlinedButton
                        githubWorking = true
                        scope.launch {
                            statusText = when (val result = githubGateway.signIn(host)) {
                                is GitHubLoginResult.Success -> { githubAccount = result.account; "GitHub-Sitzung ist auf diesem Gerät aktiv." }
                                GitHubLoginResult.Cancelled -> "GitHub-Anmeldung wurde abgebrochen."
                                is GitHubLoginResult.Failed -> result.safeMessage
                            }
                            githubWorking = false
                        }
                    },
                    enabled = !githubWorking && activity != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (githubWorking) "GitHub wird verbunden …" else "Mit GitHub anmelden") }
            }
        }
    }
    Button(
        onClick = {
            checking = true
            statusText = null
            scope.launch {
                statusText = when (val result = gateway.checkForUpdate(installedVersion.code)) {
                    AndroidUpdateCheckResult.UpToDate -> "DomiDex ist bereits auf dem neuesten Stand."
                    is AndroidUpdateCheckResult.Available -> "Version ${result.release.versionName} ist verfügbar."
                    is AndroidUpdateCheckResult.Unavailable -> result.reason
                    is AndroidUpdateCheckResult.Failed -> result.safeMessage
                }
                checking = false
            }
        },
        enabled = !checking,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (checking) "Prüfung läuft …" else "Nach Update suchen") }
    statusText?.let { message ->
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.outlinedCardColors(containerColor = VaultNightRaised),
            border = BorderStroke(1.dp, VaultOutline),
        ) { Text(message, modifier = Modifier.padding(14.dp), color = VaultTextMuted, fontSize = 12.sp) }
    }
}

private data class InstalledDomiDexVersion(val name: String, val code: Long)

@Suppress("DEPRECATION")
private fun installedDomiDexVersion(context: Context): InstalledDomiDexVersion = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    InstalledDomiDexVersion(info.versionName ?: "unbekannt", code)
}.getOrElse { InstalledDomiDexVersion("unbekannt", 0) }

/** A compact disclosure row keeps Settings usable; detailed guidance is opt-in. */
@Composable private fun SettingsInfoBlock(title: String, body: String) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    OutlinedCard(
        onClick = { expanded = !expanded },
    colors = CardDefaults.outlinedCardColors(containerColor = VaultNightRaised),
    border = BorderStroke(1.dp, VaultOutline),
) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("INFORMATION", color = MewRose.copy(alpha = .82f), fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.sp)
                Text(title, color = MewRoseSoft, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Text(if (expanded) "⌃" else "⌄", color = MewRoseSoft, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                HorizontalDivider(color = VaultOutline.copy(alpha = .55f))
                Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    } }
}

private const val COLLECTION_BACKUP_FORMAT = "domidex.collection.backup"
private const val COLLECTION_BACKUP_VERSION = 1

private fun collectionBackupJson(cards: List<CollectionCard>): String = JSONObject().apply {
    put("format", COLLECTION_BACKUP_FORMAT)
    put("version", COLLECTION_BACKUP_VERSION)
    put("createdAtEpochMillis", System.currentTimeMillis())
    put("entries", JSONArray().apply {
        cards.forEach { card -> put(JSONObject().apply {
            put("name", card.name); put("setName", card.set); put("collectorNumber", card.number)
            put("language", card.language.name); put("finish", card.finish); put("variant", card.variant)
            put("quantity", card.quantity); put("condition", card.condition); put("notes", card.notes)
            put("acquisitionPriceEur", card.acquisitionPriceEur ?: JSONObject.NULL)
            put("acquisitionDateEpochDay", card.acquisitionDateEpochDay ?: JSONObject.NULL)
            put("externalProductId", card.externalProductId ?: JSONObject.NULL)
            put("imageUrl", card.imageUrl ?: JSONObject.NULL)
        }) }
    })
}.toString(2)

private fun parseCollectionBackup(raw: String): List<ManualCardInput> {
    require(raw.length <= 5_000_000) { "Die Backup-Datei ist zu groß." }
    val root = JSONObject(raw)
    require(root.optString("format") == COLLECTION_BACKUP_FORMAT && root.optInt("version") == COLLECTION_BACKUP_VERSION) {
        "Diese Datei ist kein unterstütztes DomiDex-Backup."
    }
    val entries = root.getJSONArray("entries")
    require(entries.length() <= 5_000) { "Das Backup enthält zu viele Einträge." }
    return buildList {
        for (index in 0 until entries.length()) {
            val item = entries.getJSONObject(index)
            val name = item.optString("name").trim(); val setName = item.optString("setName").trim()
            if (name.isBlank() || setName.isBlank()) continue
            add(ManualCardInput(
                name = name, setName = setName, collectorNumber = item.optString("collectorNumber").trim(),
                language = CardLanguage.fromStorage(item.optString("language", CardLanguage.GERMAN.name)),
                finish = item.optString("finish", "Normal"), variant = item.optString("variant", "Standard"),
                quantity = item.optInt("quantity", 1).coerceAtLeast(1), condition = item.optString("condition"),
                notes = item.optString("notes"),
                acquisitionPriceEur = item.takeIf { it.has("acquisitionPriceEur") && !it.isNull("acquisitionPriceEur") }?.optDouble("acquisitionPriceEur"),
                acquisitionDateEpochDay = item.takeIf { it.has("acquisitionDateEpochDay") && !it.isNull("acquisitionDateEpochDay") }?.optLong("acquisitionDateEpochDay"),
                externalProductId = item.takeIf { it.has("externalProductId") && !it.isNull("externalProductId") }?.optLong("externalProductId")?.takeIf { it > 0 },
                imageUrl = item.takeIf { it.has("imageUrl") && !it.isNull("imageUrl") }?.optString("imageUrl"),
            ))
        }
    }
}

@Composable private fun CollectionBackupDialog(repository: CollectionRepository, cards: List<CollectionCard>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<List<ManualCardInput>?>(null) }
    var working by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            status = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(collectionBackupJson(cards)) } ?: error("Datei konnte nicht geöffnet werden.") }; "Backup gespeichert: ${cards.size} Sammlungseinträge." }.getOrElse { "Backup konnte nicht gespeichert werden." }
            working = false
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            val parsed = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { parseCollectionBackup(it.readText()) } ?: error("Datei konnte nicht geöffnet werden.") } }
            parsed.onSuccess { pendingImport = it; status = null }.onFailure { status = it.message ?: "Backup konnte nicht gelesen werden." }
            working = false
        }
    }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() }, title = { Text("Sammlung & Backup") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsInfoBlock("Lokales DomiDex-Backup", "Exportiert werden Karten, Set, Nummer, Zustand, Anzahl, Notizen, Kaufdaten und vorhandene Cardmarket-Verknüpfungen als lesbare JSON-Datei. Preise, Login, Scanner-Fotos und Zugangsdaten werden nicht exportiert.")
            SettingsInfoBlock("Sicher speichern", "Wähle beim Export selbst einen Speicherort, zum Beispiel Dateien, Downloads oder einen privaten Cloud-Ordner. DomiDex lädt die Datei nicht hoch.")
            SettingsInfoBlock("Sicher importieren", "Ein Import ergänzt deine bestehende Sammlung und überschreibt nichts automatisch. Gleiche, exakt verknüpfte Einträge können als Menge zusammengeführt werden.")
            Button(onClick = { exportLauncher.launch("DomiDex-Sammlung-${java.time.LocalDate.now()}.json") }, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text("Sammlung exportieren") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) }, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text("DomiDex-Backup importieren") }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let { Text(it, color = if (it.contains("nicht", true) || it.contains("konnte", true)) MaterialTheme.colorScheme.error else VaultPositive, fontSize = 12.sp) }
        } },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Fertig") } },
    )
    pendingImport?.let { entries -> AlertDialog(
        onDismissRequest = { pendingImport = null }, title = { Text("${entries.size} Einträge importieren?") },
        text = { Text("Die Einträge werden zu deiner aktuellen Sammlung hinzugefügt. Es wird nichts gelöscht oder überschrieben.") },
        confirmButton = { TextButton(onClick = {
            pendingImport = null; scope.launch {
                working = true
                val outcome = runCatching { withContext(Dispatchers.IO) { entries.forEach { repository.addManualCard(it) }; entries.size } }
                status = outcome.fold({ "$it Einträge wurden importiert." }, { "Import konnte nicht vollständig abgeschlossen werden." })
                working = false
            }
        }) { Text("Jetzt importieren") } },
        dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Abbrechen") } },
    ) }
}

@Composable private fun AdminSupportDialog(context: Context, portfolio: PortfolioSummary, sync: CatalogSyncStatus, showDiscordDevUpdate: Boolean, onDismiss: () -> Unit) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val packageInfo = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
    val online = remember { isOnline(context) }
    var showDevUpdateDialog by rememberSaveable { mutableStateOf(false) }
    val log = remember(portfolio.totalCards, sync.state, online) { buildString {
        appendLine("DomiDex Support Log")
        appendLine("App-Version: ${packageInfo.versionName} (${packageInfo.longVersionCode})")
        appendLine("Android: ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}")
        appendLine("Online: ${if (online) "OK" else "EINGESCHRÄNKT"}")
        appendLine("Google-Anmeldung: ${if (FirebaseAuth.getInstance().currentUser == null) "nicht aktiv" else "aktiv"}")
        appendLine("Datenbank/Binder: OK · Karten: ${portfolio.totalCards}")
        appendLine("Preisquelle: ${sync.state} · Produkte: ${sync.catalogProductCount}")
        appendLine("Scanner: lokale Kartenform-Erkennung verfügbar")
        append("Hinweis: Keine Tokens, Passwörter oder Kontodaten enthalten.")
    } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Support Log") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Kompakter Status zur manuellen Weitergabe. Keine automatische Übertragung.", color = VaultTextMuted, fontSize = 12.sp); Text(log, fontSize = 11.sp); if (showDiscordDevUpdate) OutlinedButton(onClick = { showDevUpdateDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("📤 Discord Dev Update") } } }, confirmButton = { TextButton(onClick = { clipboard.setPrimaryClip(ClipData.newPlainText("DomiDex Support Log", log)) }) { Text("Support Log kopieren") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Schließen") } })
    if (showDevUpdateDialog) DiscordDevUpdateDialog(context, onDismiss = { showDevUpdateDialog = false })
}

@Composable private fun DiscordDevUpdateDialog(context: Context, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val packageInfo = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
    val version = remember(packageInfo) { packageInfo.versionName ?: "Unbekannt" }
    val buildNumber = remember(packageInfo) { packageInfo.longVersionCode }
    var note by rememberSaveable { mutableStateOf("") }
    var screenshots by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var video by remember { mutableStateOf<Uri?>(null) }
    var publishStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var publishing by rememberSaveable { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        screenshots = uris.distinct().take(DiscordDevUpdatePayload.MAX_SCREENSHOTS)
        publishStatus = if (uris.size > DiscordDevUpdatePayload.MAX_SCREENSHOTS) "Es wurden nur die ersten ${DiscordDevUpdatePayload.MAX_SCREENSHOTS} Bilder übernommen." else null
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        video = uri
        publishStatus = null
    }
    val payload = remember(version, buildNumber, note, screenshots, video) {
        DiscordDevUpdatePayload(
            version = version,
            buildNumber = buildNumber,
            note = note.trim(),
            screenshotUris = screenshots.map(Uri::toString),
            videoUri = video?.toString(),
            timestampEpochMillis = System.currentTimeMillis(),
        )
    }
    AlertDialog(
        onDismissRequest = { if (!publishing) onDismiss() },
        title = { Column(verticalArrangement = Arrangement.spacedBy(3.dp)) { Text("📤 Discord Dev Update"); Text("Interne Vorschau · sicherer Versand über Backend erforderlich", color = MewRoseSoft, fontSize = 12.sp) } },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(max = 560.dp)) {
            item { SettingsInfoBlock("DOMIDEX DEV UPDATE", "Version: $version\nBuild: $buildNumber\n\nEs werden nur die unten ausdrücklich ausgewählten Anhänge und deine Notiz vorbereitet. Keine Konto-, Scanner-, Binder- oder Debugdaten werden automatisch erfasst.") }
            item { OutlinedTextField(note, { note = it.take(DiscordDevUpdatePayload.MAX_NOTE_LENGTH); publishStatus = null }, modifier = Modifier.fillMaxWidth(), label = { Text("Kurze zusätzliche Notiz") }, supportingText = { Text("${note.length}/${DiscordDevUpdatePayload.MAX_NOTE_LENGTH}") }, minLines = 3, maxLines = 5) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { OutlinedButton(onClick = { imagePicker.launch("image/*") }, enabled = !publishing, modifier = Modifier.weight(1f)) { Text("📸 Bilder (${screenshots.size}/5)") }; OutlinedButton(onClick = { videoPicker.launch("video/*") }, enabled = !publishing, modifier = Modifier.weight(1f)) { Text(if (video == null) "🎥 Clip wählen" else "🎥 Clip gewählt") } } }
            if (screenshots.isNotEmpty()) item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("Anhänge · Bilder", color = MewRoseSoft, fontWeight = FontWeight.SemiBold, fontSize = 12.sp); LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(screenshots, key = { it.toString() }) { uri -> AsyncImage(model = uri, contentDescription = "Ausgewählter Dev-Update-Screenshot", contentScale = ContentScale.Crop, modifier = Modifier.size(88.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, VaultOutline, RoundedCornerShape(14.dp))) } } } }
            video?.let { item { SettingsInfoBlock("Anhänge · Video", "Ein kurzer, von dir ausgewählter Screenclip ist vorbereitet. DomiDex schneidet oder bearbeitet das Video nicht automatisch.") } }
            item { SettingsInfoBlock("Sichere Veröffentlichung", "Die App enthält keinen Discord-Bot-Token und keine Webhook-URL. Erst ein authentifizierter Backend-Endpunkt darf diese strukturierte Vorschau an den Discord-Bot weitergeben.") }
            publishStatus?.let { status -> item { Text(status, color = if (status.contains("nicht", true) || status.contains("konnte", true)) VaultTextMuted else VaultPositive, fontSize = 12.sp) } }
            if (publishing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        } },
        confirmButton = { Button(onClick = { scope.launch { publishing = true; publishStatus = when (val result = UnconfiguredDiscordDevUpdatePublisher.publish(payload)) { DiscordDevUpdatePublishResult.Published -> "✅ Dev Update veröffentlicht."; is DiscordDevUpdatePublishResult.Unavailable -> result.reason; is DiscordDevUpdatePublishResult.Failed -> result.safeMessage }; publishing = false } }, enabled = !publishing) { Text("📤 Veröffentlichen") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !publishing) { Text("❌ Abbrechen") } },
    )
}

private fun isOnline(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = manager.activeNetwork ?: return false
    return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

private fun isDebuggableBuild(context: Context): Boolean =
    context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

@Composable private fun BinderScreen(cards: List<CollectionCard>, repository: CollectionRepository) {
    val context = LocalContext.current
    val binderPreferences = remember(context) { context.getSharedPreferences("cardvault_binders", Context.MODE_PRIVATE) }
    var hiddenReferenceBinders by rememberSaveable {
        mutableStateOf(binderPreferences.getStringSet("hidden_reference_binders", emptySet()).orEmpty())
    }
    // The source catalog currently exposes the set in English while the
    // app's official German master binder is named "Wachsendes Chaos". Keep
    // both spellings in one physical binder, never in competing 122/194-slot
    // binders.
    val grouped = cards.groupBy { card ->
        when {
            isWachsendesChaosSet(card.set) -> "Wachsendes Chaos"
            else -> card.set.ifBlank { "Ohne Set" }
        }
    }.toSortedMap().toMutableMap()
    val catalog = remember { PokemonVisualCatalog() }
    var catalogSets by remember { mutableStateOf<List<PokemonSet>>(emptyList()) }
    var localReferenceCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var selected by remember { mutableStateOf<Triple<String, List<CollectionCard>, Int>?>(null) }
    var openingBinder by remember { mutableStateOf<Triple<String, List<CollectionCard>, Int>?>(null) }
    var adding by remember { mutableStateOf(false) }
    var choosingCatalog by remember { mutableStateOf(false) }
    var catalogDraft by remember { mutableStateOf<CatalogSearchResult?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(openingBinder) {
        openingBinder?.let { pending ->
            delay(DomiMotion.Fast.toLong()) // UI-only cover opening beat; collection data is untouched.
            selected = pending
            openingBinder = null
        }
    }
    LaunchedEffect(Unit) {
        // The full automatic reference wall was intentionally removed: a Binder
        // exists only after the collector actually adds a card for that set.
        repository.clearGeneratedGermanMasterSetBinders()
        runCatching { catalog.sets() }
            .onSuccess { loaded -> Log.i("DomiDexMasterSets", "Loaded ${loaded.size} German master-set definitions"); catalogSets = loaded }
            .onFailure { error -> Log.w("DomiDexMasterSets", "German master-set index could not be loaded", error) }
    }
    LaunchedEffect(grouped.keys.toList()) {
        localReferenceCounts = grouped.keys.associateWith { setName ->
            runCatching { repository.binderReferenceCount(setName) }.getOrDefault(0)
        }
    }
    fun coverFor(name: String): String? {
        if (isWachsendesChaosSet(name)) return "https://www.pokemon.com/static-assets/content-assets/cms2-de-de/img/trading-card-game/series/me_series/me04/me04_logo_169_de.png"
        val normalized = name.lowercase().filter(Char::isLetterOrDigit)
        return catalogSets.firstOrNull { set ->
            val candidate = set.name.lowercase().filter(Char::isLetterOrDigit)
            candidate == normalized || candidate.contains(normalized) || normalized.contains(candidate)
        }?.logoUrl
    }
    fun cardCapacityFor(name: String, setCards: List<CollectionCard>): Int {
        if (isWachsendesChaosSet(name)) return 194
        localReferenceCounts[name]?.takeIf { it > 0 }?.let { return it }
        val normalized = name.lowercase().filter(Char::isLetterOrDigit)
        val catalogTotal = catalogSets.firstOrNull { set ->
            val candidate = set.name.lowercase().filter(Char::isLetterOrDigit)
            candidate == normalized || candidate.contains(normalized) || normalized.contains(candidate)
        }?.total ?: 0
        // A verified visual set defines its own binder size.  A mistyped or
        // promotional collector number must be an overflow card, never inflate
        // the official set or displace the numbered slots.
        if (catalogTotal > 0) return catalogTotal
        val printedDenominator = setCards.maxOfOrNull { it.number.substringAfter('/', "").toIntOrNull() ?: 0 } ?: 0
        val numberedCards = setCards.filter { collectorNumberOf(it.number) != null }
        val observedVariantSlots = numberedCards.size - numberedCards.distinctBy { collectorNumberOf(it.number) }.size
        return maxOf(1, setCards.size, maxOf(catalogTotal, printedDenominator) + observedVariantSlots)
    }
    selected?.let { (setName, setCards, capacity) ->
        BinderReaderScreen(
            setName = setName,
            cards = setCards,
            cardCapacity = capacity,
            coverUrl = coverFor(setName),
            repository = repository,
            onBack = { selected = null },
            onHideReferenceBinder = if (setCards.isEmpty()) {
                {
                    val updated = hiddenReferenceBinders + setName
                    binderPreferences.edit().putStringSet("hidden_reference_binders", updated).apply()
                    hiddenReferenceBinders = updated
                    selected = null
                }
            } else null,
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenHeader("DomiDex Binder", "Deine Binder", "Sets, Cover und freie Plätze auf einen Blick.") }
        item { BinderFocusHero() }
        item { Button(onClick = { choosingCatalog = true }, modifier = Modifier.fillMaxWidth(), shape = CardVaultShapes.card) { Text("Karte zum Binder hinzufügen", fontWeight = FontWeight.Bold) } }
        grouped.forEach { (setName, setCards) ->
            val capacity = cardCapacityFor(setName, setCards)
            val binderPages = (capacity + 8) / 9
            val progress = if (isWachsendesChaosSet(setName)) setCards.distinctBy { "${it.number}|${it.finish}|${it.variant}" }.size else setCards.distinctBy { it.number }.size
            item {
                OutlinedCard(onClick = { openingBinder = Triple(setName, setCards, capacity) }, shape = CardVaultShapes.card, colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface), border = BorderStroke(1.dp, if (openingBinder?.first == setName) MewRose else MewPlum.copy(alpha = .42f))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DomiBinderCover(setName, coverFor(setName), openingBinder?.first == setName)
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                val pricedCards = setCards.filter { it.priceUpdatedAtEpochMillis != null }
                                Text(setName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text("DomiDex-Sammelalbum · $binderPages Kartenseiten für $capacity Karten", color = VaultTextMuted, fontSize = 12.sp)
                                Text("Fortschritt: $progress/$capacity · ${setCards.sumOf { it.quantity }} im Besitz", color = MewIce, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text("Gesamtwert: ${if (pricedCards.isEmpty()) "Kein Marktwert verfügbar" else euro(pricedCards.sumOf { it.currentValueEur * it.quantity })} · ${pricedCards.sumOf { it.quantity }}/${setCards.sumOf { it.quantity }} bepreist", color = MewIce, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text("Öffnen und durchblättern ›", color = MewRoseSoft, fontSize = 11.sp)
                            }
                            MasterSetProgressRing(progress, capacity)
                        }
                    }
                }
            }
        }
    }
    if (choosingCatalog) CatalogProductPicker(repository, onDismiss = { choosingCatalog = false }, onSelected = { result ->
        // The visual set chosen by the user is authoritative for the Binder.
        // A Cardmarket expansion ID is pricing metadata, never a Binder name.
        scope.launch {
            repository.addManualCard(ManualCardInput(
                name = result.name,
                setName = result.visualSetName ?: "Ohne Setzuordnung",
                collectorNumber = result.collectorNumber.orEmpty(),
                language = CardLanguage.GERMAN,
                finish = "Normal",
                variant = "Standard",
                quantity = 1,
                condition = "Nicht geprüft",
                notes = "",
                acquisitionPriceEur = null,
                acquisitionDateEpochDay = null,
                externalProductId = result.productId.takeIf { it > 0 },
                imageUrl = result.imageUrl,
            ))
        }
        choosingCatalog = false
    })
    if (adding) ManualCardDialog(repository = repository, initialCatalog = catalogDraft, onDismiss = { adding = false; catalogDraft = null }, onSave = { input -> scope.launch { repository.addManualCard(input) }; adding = false; catalogDraft = null })
}

@Composable private fun BinderFocusHero() {
    val motion = rememberInfiniteTransition(label = "binder-focus")
    val pulse by motion.animateFloat(.92f, 1.08f, infiniteRepeatable(tween(1_900, easing = LinearEasing), RepeatMode.Reverse), label = "binder-glow")
    OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.CenterStart) {
            Canvas(Modifier.matchParentSize().clearAndSetSemantics { }) {
                val center = Offset(size.width * .83f, size.height * .5f)
                drawCircle(MewRose.copy(alpha = .10f), size.minDimension * .72f * pulse, center)
                drawCircle(MewIce.copy(alpha = .13f), size.minDimension * .42f * pulse, center, style = Stroke(3f))
                repeat(7) { index -> drawCircle(MewIce.copy(alpha = .46f), 3f, Offset(center.x + (index - 3) * 14f, center.y + kotlin.math.sin(index.toDouble()).toFloat() * 24f)) }
            }
            Column(Modifier.padding(18.dp).fillMaxWidth(.7f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("DEIN FOKUS", color = MewRoseSoft, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.4.sp)
                Text("Binder zuerst.", color = VaultText, fontWeight = FontWeight.Black, fontSize = 23.sp)
                Text("Sammeln, Fortschritt sehen, Karte öffnen.", color = VaultTextMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable private fun BinderReaderScreen(
    setName: String,
    cards: List<CollectionCard>,
    cardCapacity: Int,
    coverUrl: String?,
    repository: CollectionRepository,
    onBack: () -> Unit,
    onHideReferenceBinder: (() -> Unit)? = null,
) {
    val visualCatalog = remember { PokemonVisualCatalog() }
    var setReferences by remember(setName) { mutableStateOf<List<CollectionCard>>(emptyList()) }
    var referencesLoading by remember(setName) { mutableStateOf(true) }
    LaunchedEffect(setName) {
        if (isWachsendesChaosSet(setName)) {
            referencesLoading = false
            return@LaunchedEffect
        }
        referencesLoading = true
        try {
            // Only cached TCGdex slots carry source-confirmed German finishes.
            // Cardmarket rows are a valuable offline fallback but may omit a printed
            // number or finish, so they must never prevent a verified master-set
            // refresh from completing the normal/reverse/holo layout.
            val localReferences = repository.binderReferences(setName)
            // Keep an explicit local fallback visible while the verified refresh is
            // running or when the device is offline.
            setReferences = localReferences
            if (localReferences.any { it.id.startsWith("tcgdex-") }) {
                setReferences = localReferences
                return@LaunchedEffect
            }
            val normalized = setName.lowercase().filter(Char::isLetterOrDigit)
            val set = visualCatalog.sets().firstOrNull { candidate ->
                val candidateName = candidate.name.lowercase().filter(Char::isLetterOrDigit)
                candidateName == normalized || candidateName.contains(normalized) || normalized.contains(candidateName)
            }
            if (set == null) {
                setReferences = localReferences
                return@LaunchedEffect
            }
            val downloadedCards = visualCatalog.cards(set)
            // Cache source metadata and licensed image URLs locally for this
            // master set.  These are reference slots only, never owned cards.
            // Show the numbered grey slots immediately, then enrich them in
            // the background with only source-declared foil printings.  This
            // avoids inventing a Holo/Reverse slot for a card that has none.
            setReferences = downloadedCards.flatMap { reference -> reference.asBinderReferences() }
            // Detail endpoints provide the real printings. Fetch a small bounded
            // batch at a time; sequential requests made large German sets look like
            // one-slot binders for far too long, while an unbounded burst is unfair
            // to the public catalogue service.
            val detailedCards = downloadedCards.chunked(6).flatMap { batch ->
                batch.map { reference -> async { runCatching { visualCatalog.withDeclaredPrintVariants(reference) }.getOrDefault(reference) } }
                    .map { it.await() }
            }
            repository.cacheGermanMasterSetCards(
                GermanMasterSetDefinition(set.id, set.name, set.logoUrl),
                detailedCards.flatMap { reference -> reference.printVariants.map { printing ->
                    GermanMasterSetCardDefinition(
                        if (printing.id == "normal") reference.id else "${reference.id}-${printing.id}",
                        reference.name, reference.number, reference.largeImageUrl, printing.finish, printing.variant,
                        reference.rarity ?: "UNKNOWN",
                    )
                } },
            )
            detailedCards.flatMap { reference -> reference.asBinderReferences() }
                .also { setReferences = it }
        } catch (error: Exception) {
            // Preserve any explicit local catalogue fallback when offline. Never
            // erase an already rendered reference wall because one refresh failed.
            Log.w("DomiDexBinder", "German master-set variants could not be refreshed", error)
        } finally {
            referencesLoading = false
        }
    }
    val arrangedCards = remember(setName, cards, cardCapacity, setReferences) { arrangeBinderCards(setName, cards, cardCapacity, setReferences) }
    val binderPageCount = ((arrangedCards.size + 8) / 9).coerceAtLeast(1)
    val faces = remember(arrangedCards, binderPageCount) {
        buildList {
            add(BinderFace.White)
            repeat(binderPageCount) { pageIndex -> add(BinderFace.Pockets(arrangedCards.drop(pageIndex * 9).take(9))) }
            add(BinderFace.White)
        }
    }
    var page by rememberSaveable(setName) { mutableIntStateOf(0) }
    var selectedCard by remember { mutableStateOf<CollectionCard?>(null) }
    var addingReference by remember { mutableStateOf<CollectionCard?>(null) }
    var removalCandidate by remember { mutableStateOf<CollectionCard?>(null) }
    var removalStage by rememberSaveable { mutableIntStateOf(0) }
    val ownedBinderId = remember(cards) { cards.map { it.binderId }.filter { it.isNotBlank() }.distinct().singleOrNull() }
    val binderValue = remember(cards) { cards.filter { it.priceUpdatedAtEpochMillis != null }.sumOf { it.currentValueEur * it.quantity } }
    val pricedBinderCards = remember(cards) { cards.filter { it.priceUpdatedAtEpochMillis != null }.sumOf { it.quantity } }
    val binderCards = remember(cards) { cards.sumOf { it.quantity } }
    var binderDeletionStage by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Sammelalben") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(setName, color = VaultText, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Seite ${page + 1} von ${faces.size} · ${arrangedCards.size} Sammelplätze", color = VaultTextMuted, fontSize = 11.sp)
                if (referencesLoading) Text("Deutsche Setvarianten werden geprüft …", color = MewIce, fontSize = 10.sp)
                Text("Binderwert: ${if (pricedBinderCards == 0) "Kein Marktwert verfügbar" else euro(binderValue)} · $pricedBinderCards/$binderCards bepreist", color = MewIce, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            if (coverUrl != null) AsyncImage(coverUrl, "Set-Umschlag", Modifier.size(58.dp, 34.dp)) else Text("DM", color = MewRose, fontWeight = FontWeight.Black)
        }
        if (ownedBinderId != null || onHideReferenceBinder != null) TextButton(onClick = { binderDeletionStage = 1 }, modifier = Modifier.align(Alignment.End)) {
            Text("Binder löschen", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        AnimatedContent(targetState = page, modifier = Modifier.weight(1f), transitionSpec = {
            if (targetState > initialState) (slideInHorizontally(tween(DomiMotion.Normal)) { it } + fadeIn(tween(DomiMotion.Fast))) togetherWith (slideOutHorizontally(tween(DomiMotion.Normal)) { -it } + fadeOut(tween(DomiMotion.Fast)))
            else (slideInHorizontally(tween(DomiMotion.Normal)) { -it } + fadeIn(tween(DomiMotion.Fast))) togetherWith (slideOutHorizontally(tween(DomiMotion.Normal)) { it } + fadeOut(tween(DomiMotion.Fast)))
        }, label = "Vollbild-Sammelalbumseite") { index ->
            val pageModifier = Modifier.fillMaxSize().pointerInput(page) {
                var horizontalTravel = 0f
                detectDragGestures(
                    onDragStart = { horizontalTravel = 0f },
                    onDrag = { change, amount -> change.consume(); horizontalTravel += amount.x },
                    onDragEnd = {
                        when {
                            horizontalTravel < -80f && page < faces.lastIndex -> page++
                            horizontalTravel > 80f && page > 0 -> page--
                        }
                    },
                )
            }.clickable { if (page < faces.lastIndex) page++ }
            FullBinderFace(
                face = faces[index],
                coverUrl = coverUrl,
                setName = setName,
                modifier = pageModifier,
                onCardSelected = { selectedCard = it },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { page-- }, enabled = page > 0) { Text("‹ Zurück") }
            Text("DOMIDEX", color = MewRoseSoft, fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 2.sp)
            Button(onClick = { page++ }, enabled = page < faces.lastIndex) { Text("Weiter ›") }
        }
    }
    selectedCard?.let { card ->
        CardPreviewDialog(
            card = card,
            repository = repository,
            onDismiss = { selectedCard = null },
            onEdit = null,
            // Catalog/reference slots use entryId 0 and are intentionally never removable.
            onDelete = if (card.entryId > 0L) { { removalCandidate = card; removalStage = 1 } } else null,
            // Both empty reference slots and already owned prints support this
            // route.  For an owned print it simply adds another copy, retaining
            // its known Cardmarket product where one is already linked.
            onAdd = { selectedCard = null; addingReference = card },
        )
    }
    addingReference?.let { reference ->
        BinderCardAddDialog(
            reference = reference,
            repository = repository,
            onDismiss = { addingReference = null },
            onAdded = { addingReference = null },
        )
    }
    if (removalCandidate != null && removalStage == 1) AlertDialog(
        onDismissRequest = { removalCandidate = null; removalStage = 0 },
        title = { Text("Karte aus Binder entfernen?") },
        text = { Text("${removalCandidate!!.name} wird aus deiner Sammlung und diesem Binder entfernt. Referenzkarten bleiben davon unberührt.") },
        confirmButton = { TextButton(onClick = { removalStage = 2 }) { Text("Weiter") } },
        dismissButton = { TextButton(onClick = { removalCandidate = null; removalStage = 0 }) { Text("Abbrechen") } },
    )
    if (removalCandidate != null && removalStage == 2) AlertDialog(
        onDismissRequest = { removalCandidate = null; removalStage = 0 },
        title = { Text("Endgültig entfernen?") },
        text = { Text("Diese zweite Bestätigung entfernt ${removalCandidate!!.name} dauerhaft aus deinem Binder.") },
        confirmButton = { TextButton(onClick = { val entry = removalCandidate!!; scope.launch { repository.deleteEntry(entry.entryId) }; selectedCard = null; removalCandidate = null; removalStage = 0 }) { Text("Ja, endgültig entfernen", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { removalCandidate = null; removalStage = 0 }) { Text("Abbrechen") } },
    )
    if (binderDeletionStage == 1 && ownedBinderId != null) AlertDialog(
        onDismissRequest = { binderDeletionStage = 0 },
        title = { Text("Diesen Binder löschen?") },
        text = { Text("Alle Karten, die du in diesem Binder besitzt, werden aus deiner Sammlung entfernt. Der Kartenkatalog und seine ausgegrauten Referenzplätze bleiben erhalten.") },
        confirmButton = { TextButton(onClick = { binderDeletionStage = 2 }) { Text("Weiter") } },
        dismissButton = { TextButton(onClick = { binderDeletionStage = 0 }) { Text("Abbrechen") } },
    )
    if (binderDeletionStage == 2 && ownedBinderId != null) AlertDialog(
        onDismissRequest = { binderDeletionStage = 0 },
        title = { Text("Binder endgültig löschen?") },
        text = { Text("Die zweite Bestätigung löscht alle deine Einträge in diesem Binder dauerhaft.") },
        confirmButton = { TextButton(onClick = { scope.launch { repository.deleteBinder(ownedBinderId) }; binderDeletionStage = 0; onBack() }) { Text("Ja, Binder endgültig löschen", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { binderDeletionStage = 0 }) { Text("Abbrechen") } },
    )
    if (binderDeletionStage == 1 && ownedBinderId == null && onHideReferenceBinder != null) AlertDialog(
        onDismissRequest = { binderDeletionStage = 0 },
        title = { Text("Referenzbinder ausblenden?") },
        text = { Text("Dieser leere Binder enthält keine eigenen Karten. Er wird nur aus deiner Binder-Ansicht entfernt; Kartenkatalog und Sammlung bleiben unverändert.") },
        confirmButton = { TextButton(onClick = { binderDeletionStage = 2 }) { Text("Weiter") } },
        dismissButton = { TextButton(onClick = { binderDeletionStage = 0 }) { Text("Abbrechen") } },
    )
    if (binderDeletionStage == 2 && ownedBinderId == null && onHideReferenceBinder != null) AlertDialog(
        onDismissRequest = { binderDeletionStage = 0 },
        title = { Text("Binder wirklich ausblenden?") },
        text = { Text("Mit der zweiten Bestätigung verschwindet der leere Referenzbinder aus deiner Ansicht. Er erscheint automatisch wieder, sobald du eine Karte dieses Sets hinzufügst.") },
        confirmButton = { TextButton(onClick = { binderDeletionStage = 0; onHideReferenceBinder() }) { Text("Ja, ausblenden", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { binderDeletionStage = 0 }) { Text("Abbrechen") } },
    )
}

@Composable private fun FullBinderFace(face: BinderFace, coverUrl: String?, setName: String, modifier: Modifier = Modifier, onCardSelected: (CollectionCard) -> Unit = {}) {
    when (face) {
        BinderFace.White -> Box(modifier.background(Brush.linearGradient(listOf(Color(0xFFE9E6DE), Color(0xFFD8D6D0), Color(0xFFE4E1D9))), RoundedCornerShape(14.dp)).border(2.dp, Color(0xFFA9ABA7), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth(.72f)) {
                if (coverUrl != null) AsyncImage(coverUrl, "$setName Set-Logo", Modifier.fillMaxWidth().heightIn(max = 150.dp), contentScale = ContentScale.Fit) else Text(setName.uppercase(), color = Color(0xFF252736), fontWeight = FontWeight.Black, fontSize = 24.sp, textAlign = TextAlign.Center)
                Text("DOMIDEX", color = Color(0xFF1B2030).copy(alpha = .20f), fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
            }
        }
        is BinderFace.Pockets -> Box(modifier.background(Color(0xFF08090C), RoundedCornerShape(14.dp)).border(2.dp, Color(0xFF4ADEF0).copy(alpha = .32f), RoundedCornerShape(14.dp)).padding(10.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { row -> Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { column ->
                        val card = face.cards.getOrNull(row * 3 + column)
                        val owned = card?.entryId?.let { it > 0 } == true
                        // Binder thumbnails resolve exactly like the detail card, but render a
                        // static single-pass treatment. No per-slot infinite animation runs.
                        val thumbnailEffect = CardEffectResolver.resolve(card)
                        val slotPresence by animateFloatAsState(if (card != null) 1f else .94f, tween(DomiMotion.Normal), label = "binder-slot-$row-$column")
                        Box(Modifier.weight(1f).fillMaxHeight().graphicsLayer { alpha = slotPresence; scaleX = .96f + slotPresence * .04f; scaleY = .96f + slotPresence * .04f }.background(if (owned) Color(card!!.accent) else Color(0xFF111218), RoundedCornerShape(7.dp)).border(1.dp, if (owned) MewIce.copy(alpha = .30f) else MewPlum.copy(alpha = .16f), RoundedCornerShape(7.dp)).then(card?.let { Modifier.clickable { onCardSelected(it) } } ?: Modifier), contentAlignment = Alignment.Center) {
                            if (card?.imageUrl != null) AsyncImage(card.imageUrl, card.name, Modifier.fillMaxSize().graphicsLayer(alpha = if (owned) 1f else .24f), contentScale = ContentScale.Fit) else if (card != null) Text(card.name.take(1), color = Color.White.copy(alpha = if (owned) 1f else .25f), fontSize = 34.sp, fontWeight = FontWeight.Black)
                            if (!owned) Column(horizontalAlignment = Alignment.CenterHorizontally) { if (card != null) Text(card.number.substringBefore('/'), color = Color.White.copy(alpha = .65f), fontSize = 11.sp, fontWeight = FontWeight.Bold); Text("NICHT IM\nBESITZ", color = Color.White.copy(alpha = .48f), fontSize = 7.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, lineHeight = 8.sp) }
                            StaticCardEffectLayer(thumbnailEffect, Modifier.matchParentSize().clearAndSetSemantics { })
                            Text("DM", modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp), color = Color.White.copy(alpha = .45f), fontSize = 8.sp, fontWeight = FontWeight.Black)
                        }
                    }
                } }
            }
            Text("DOMIDEX", modifier = Modifier.align(Alignment.Center).graphicsLayer { rotationZ = -20f }, color = Color.White.copy(alpha = .06f), fontSize = 42.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
        }
    }
}

@Composable private fun BinderPagesDialog(setName: String, cards: List<CollectionCard>, coverUrl: String?, onDismiss: () -> Unit) {
    val pages = remember(cards) { physicalBinderSpreads(cards) }
    var page by rememberSaveable(setName) { mutableIntStateOf(0) }
    AlertDialog(onDismissRequest = onDismiss, title = { Column { Text(setName); Text("DomiDex · Seite ${page + 1} von ${pages.size}", color = VaultTextMuted, fontSize = 12.sp) } }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AnimatedContent(targetState = page, transitionSpec = {
                if (targetState > initialState) (slideInHorizontally(tween(DomiMotion.Normal)) { it } + fadeIn(tween(DomiMotion.Fast))) togetherWith (slideOutHorizontally(tween(DomiMotion.Normal)) { -it } + fadeOut(tween(DomiMotion.Fast)))
                else (slideInHorizontally(tween(DomiMotion.Normal)) { -it } + fadeIn(tween(DomiMotion.Fast))) togetherWith (slideOutHorizontally(tween(DomiMotion.Normal)) { it } + fadeOut(tween(DomiMotion.Fast)))
            }, label = "Sammelalbum-Seitenwechsel") { pageIndex ->
                Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF15151B), Color(0xFF07070A))), CardVaultShapes.card).border(2.dp, Color(0xFF4ADEF0).copy(alpha = .35f), CardVaultShapes.card).padding(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                        BinderFacePage(pages[pageIndex].left, Modifier.weight(1f))
                        Box(Modifier.width(10.dp).height(238.dp).background(Brush.horizontalGradient(listOf(Color.Black, Color(0xFF26222D), Color.Black)), RoundedCornerShape(4.dp)))
                        BinderFacePage(pages[pageIndex].right, Modifier.weight(1f))
                    }
                    Text("DOMIDEX", modifier = Modifier.align(Alignment.Center).graphicsLayer { rotationZ = -20f }, color = Color.White.copy(alpha = .07f), fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { page-- }, enabled = page > 0) { Text("‹ Zurück") }
                if (coverUrl != null) AsyncImage(coverUrl, "Set-Logo", Modifier.size(72.dp, 34.dp)) else Text(setName.uppercase(), color = MewRose, fontSize = 9.sp, fontWeight = FontWeight.Black, maxLines = 2)
                TextButton(onClick = { page++ }, enabled = page < pages.lastIndex) { Text("Weiter ›") }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } })
}

private sealed interface BinderFace {
    data object White : BinderFace
    data class Pockets(val cards: List<CollectionCard?>) : BinderFace
}

private fun collectorNumberOf(value: String): Int? = Regex("\\d{1,4}").find(value)?.value?.toIntOrNull()

private fun isWachsendesChaosSet(setName: String): Boolean = setName.equals("Wachsendes Chaos", ignoreCase = true) ||
    setName.equals("Chaos Rising", ignoreCase = true)

/** Every source-confirmed printing gets its own master-set slot. */
private fun PokemonVisualCard.asBinderReferences(): List<CollectionCard> = printVariants.map { printing ->
    CollectionCard(
        entryId = 0,
        id = "$id-${printing.id}",
        name = name,
        set = set.name,
        number = number,
        language = CardLanguage.GERMAN,
        finish = printing.finish,
        variant = printing.variant,
        condition = "Nicht im Besitz",
        quantity = 0,
        currentValueEur = 0.0,
        changePercent = 0.0,
        priceUpdatedAtEpochMillis = null,
        notes = "",
        acquisitionPriceEur = null,
        acquisitionDateEpochDay = null,
        accent = 0xFF24263A,
        imageUrl = largeImageUrl,
        rarity = rarity ?: "UNKNOWN",
    )
}

internal fun arrangeBinderCards(setName: String, cards: List<CollectionCard>, capacity: Int, references: List<CollectionCard> = emptyList()): List<CollectionCard?> {
    if (isWachsendesChaosSet(setName)) {
        return WachsendesChaosMasterSet.cards.map { reference ->
            cards.firstOrNull { owned ->
                normalizedCollectorNumber(owned.number) == normalizedCollectorNumber(reference.number) &&
                    when {
                        reference.finish.contains("reverse", true) || reference.variant.contains("reverse", true) ->
                            owned.finish.contains("reverse", true) || owned.variant.contains("reverse", true) || owned.variant.contains("parallel", true)
                        reference.finish.contains("holo", true) ->
                            owned.finish.contains("holo", true) && !owned.finish.contains("reverse", true) && !owned.variant.contains("reverse", true)
                        else ->
                            !owned.finish.contains("reverse", true) && !owned.variant.contains("reverse", true) &&
                                !owned.variant.contains("parallel", true) && !owned.finish.contains("holo", true)
                    }
            } ?: CollectionCard(
                entryId = 0,
                id = reference.id,
                name = reference.name,
                set = setName,
                number = reference.number,
                language = CardLanguage.GERMAN,
                finish = reference.finish,
                variant = reference.variant,
                condition = "Nicht im Besitz",
                quantity = 0,
                currentValueEur = 0.0,
                changePercent = 0.0,
                priceUpdatedAtEpochMillis = null,
                notes = "",
                acquisitionPriceEur = null,
                acquisitionDateEpochDay = null,
                accent = 0xFF24263A,
                imageUrl = reference.imageUrl,
            )
        }.take(capacity)
    }
    if (references.isNotEmpty()) {
        // A master binder is ordered by printed number, then its confirmed
        // printings (Normal, Holo, Reverse Holo). Existing variants replace
        // precisely their own slot rather than being appended at random.
        val orderedReferences = references.sortedWith(compareBy<CollectionCard>(
            { collectorNumberOf(it.number) ?: Int.MAX_VALUE },
            { masterFinishOrder(it) },
            { it.variant.lowercase(Locale.ROOT) },
        ))
        val claimedEntryIds = mutableSetOf<Long>()
        val slots = orderedReferences.map { reference ->
            cards.firstOrNull { owned -> owned.entryId !in claimedEntryIds && isSameMasterPrinting(reference, owned) }
                ?.also { claimedEntryIds += it.entryId }
                ?: reference
        }.toMutableList<CollectionCard?>()
        // The provider may lack a reference variant for a card the user owns.
        // Keep it visible after the verified slots, never in a wrong slot.
        slots += cards.filter { it.entryId !in claimedEntryIds }.sortedWith(compareBy(
            { collectorNumberOf(it.number) ?: Int.MAX_VALUE }, { masterFinishOrder(it) }, { it.variant },
        ))
        return slots
    }
    val slots = MutableList<CollectionCard?>(capacity) { null }
    val overflow = mutableListOf<CollectionCard>()
    cards.groupBy { collectorNumberOf(it.number) }.toSortedMap(compareBy<Int?> { it ?: Int.MAX_VALUE }).forEach { (number, sameNumber) ->
        val sorted = sameNumber.sortedWith(compareBy({ it.finish.contains("reverse", true) }, { it.variant }, { it.language.name }))
        val target = number?.minus(1)?.takeIf { it in slots.indices }
        if (target != null) slots[target] = sorted.first() else overflow += sorted.first()
        overflow += sorted.drop(1)
    }
    // Unknown numbers and variants must not silently take an early set slot.
    return slots + overflow
}

private fun isSameMasterPrinting(reference: CollectionCard, owned: CollectionCard): Boolean {
    // Synced Cardmarket slots do not always carry a printable number. Their stable
    // source product ID is then the only honest way to replace the exact slot.
    if (reference.externalProductId != null && reference.externalProductId == owned.externalProductId) return true
    if (normalizedCollectorNumber(reference.number) != normalizedCollectorNumber(owned.number)) return false
    val referenceFinish = masterFinishOrder(reference)
    val ownedFinish = masterFinishOrder(owned)
    if (referenceFinish != ownedFinish) return false
    // For special edition variants, the printed variant label must agree. The
    // ordinary Normal/Holo/Reverse slots are matched by their finish class.
    return referenceFinish in 0..2 || normalizeBinderMetadata(reference.variant) == normalizeBinderMetadata(owned.variant)
}

private fun masterFinishOrder(card: CollectionCard): Int = when {
    card.finish.contains("reverse", true) || card.variant.contains("reverse", true) || card.variant.contains("parallel", true) -> 2
    card.finish.contains("holo", true) -> 1
    else -> 0
}

private data class BinderSpread(val left: BinderFace, val right: BinderFace)

private fun physicalBinderSpreads(cards: List<CollectionCard>): List<BinderSpread> {
    val cardPages = cards.chunked(9).ifEmpty { listOf(emptyList()) }
    val spreads = mutableListOf(BinderSpread(BinderFace.White, BinderFace.Pockets(cardPages.first())))
    var index = 1
    while (index < cardPages.size) {
        val right = cardPages.getOrNull(index + 1)?.let(BinderFace::Pockets) ?: BinderFace.White
        spreads += BinderSpread(BinderFace.Pockets(cardPages[index]), right)
        index += 2
    }
    if (spreads.last().right !is BinderFace.White) spreads += BinderSpread(BinderFace.Pockets(emptyList()), BinderFace.White)
    return spreads
}

@Composable private fun BinderFacePage(face: BinderFace, modifier: Modifier = Modifier) {
    when (face) {
        BinderFace.White -> BinderWhitePage(modifier)
        is BinderFace.Pockets -> BinderPocketPage(face.cards, modifier)
    }
}

@Composable private fun BinderWhitePage(modifier: Modifier = Modifier) = Box(modifier.height(238.dp).background(Brush.linearGradient(listOf(Color(0xFFF7F7F2), Color(0xFFE2E4E1), Color.White)), RoundedCornerShape(5.dp)).border(1.dp, Color(0xFFBFC5C4), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
    Text("DOMIDEX", color = Color(0xFF1B2030).copy(alpha = .10f), fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
}

@Composable private fun BinderPocketPage(cards: List<CollectionCard?>, modifier: Modifier = Modifier) = Box(modifier.background(Color(0xFF0D0E12), RoundedCornerShape(5.dp)).padding(4.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { row -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(3) { column ->
                val card = cards.getOrNull(row * 3 + column)
                Box(Modifier.weight(1f).aspectRatio(.72f).background(if (card == null) Color(0xFF111218) else Color(card.accent), RoundedCornerShape(3.dp)).border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                    if (card?.imageUrl != null) AsyncImage(card.imageUrl, card.name, Modifier.fillMaxSize()) else if (card != null) Text(card.name.take(1), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black) else Text("+", color = Color.White.copy(alpha = .28f), fontSize = 16.sp)
                    Canvas(Modifier.matchParentSize()) { drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = .11f), Color.Transparent, Color.White.copy(alpha = .03f)))) }
                    Text("DM", modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp), color = Color.White.copy(alpha = .42f), fontSize = 6.sp, fontWeight = FontWeight.Black)
                }
            }
        } }
    }
}

@Composable private fun BinderPlaceholder(title: String, body: String) = OutlinedCard(shape = CardVaultShapes.card, colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface)) {
    Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("DM", color = MewRose, fontSize = 34.sp, fontWeight = FontWeight.Black); Text(title, fontWeight = FontWeight.Bold); Text(body, color = VaultTextMuted, fontSize = 13.sp) }
}

@Composable
private fun ArrivalMoment(onContinue: () -> Unit) {
    val motion = rememberInfiniteTransition(label = "launch-motion")
    val orbit by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(4_800, easing = LinearEasing), RepeatMode.Restart), label = "orbit")
    val pulse by motion.animateFloat(.88f, 1.12f, infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    val glow by motion.animateFloat(.18f, .74f, infiniteRepeatable(tween(1_750, easing = LinearEasing), RepeatMode.Reverse), label = "glow")
    var loading by remember { mutableFloatStateOf(.03f) }
    val progress by animateFloatAsState(loading, animationSpec = tween(3_000, easing = LinearEasing), label = "launch-load")
    LaunchedEffect(Unit) {
        loading = 1f
        delay(3_250)
        onContinue()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(VaultNight, Color(0xFF151326), VaultNight)))
            // Deliberately consume every tap while local startup work settles.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize().clearAndSetSemantics { }) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val unit = size.minDimension
            drawCircle(MewIce.copy(alpha = .04f + glow * .05f), radius = unit * (.44f + glow * .12f), center = center)
            drawCircle(MewRose.copy(alpha = .06f), radius = unit * .66f, center = Offset(size.width * .12f, size.height * .90f))
            repeat(3) { ring -> drawCircle(MewIce.copy(alpha = (.10f - ring * .02f).coerceAtLeast(.02f)), radius = unit * (.16f + ring * .085f + glow * .025f), center = center, style = Stroke(2.2f)) }
            repeat(20) { index ->
                val angle = (index / 20f + orbit) * (Math.PI * 2.0)
                val radius = unit * (.20f + (index % 4) * .055f)
                val x = center.x + kotlin.math.cos(angle).toFloat() * radius
                val y = center.y + kotlin.math.sin(angle).toFloat() * radius
                drawCircle(if (index % 2 == 0) MewIce else MewRose, radius = 2.5f + (index % 3), center = Offset(x, y), alpha = .45f + glow * .38f)
            }
        }
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    color = MewRose.copy(alpha = .14f + glow * .12f),
                    shape = RoundedCornerShape(32.dp),
                    border = BorderStroke(1.dp, MewIce.copy(alpha = .45f)),
                    modifier = Modifier.graphicsLayer(scaleX = pulse, scaleY = pulse),
                ) {
                    Text("DOMIDEX", modifier = Modifier.padding(horizontal = 25.dp, vertical = 15.dp), color = Color(0xFFFFE3EF), fontSize = 29.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                }
                Text("DEIN BINDER. DEIN FOKUS.", color = MewIce.copy(alpha = .82f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.1.sp)
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 44.dp, vertical = 54.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(99.dp)),
                color = MewRose,
                trackColor = Color.White.copy(alpha = .10f),
                strokeCap = StrokeCap.Round,
            )
        }
    }
}

@Composable private fun ScreenHeader(eyebrow: String, title: String, subtitle: String) = Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(eyebrow.uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.5.sp)
        Surface(color = MewRose.copy(alpha = .14f), shape = CardVaultShapes.thumbnail) {
            Text("D M", modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = MewRose, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
        }
    }
    Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = VaultText)
    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun DashboardScreen(portfolio: PortfolioSummary, contentUpdates: ContentUpdateState, focusChoice: String, onFocusChoice: (String) -> Unit, openBinder: () -> Unit, openScanner: () -> Unit) {
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(CardVaultSpacing.section)) {
    item { ScreenHeader(stringResource(R.string.dashboard_eyebrow), stringResource(R.string.dashboard_title), stringResource(R.string.dashboard_subtitle)) }
    item { PortfolioHero(portfolio, focusChoice, onFocusChoice) }
    item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { StatCard(stringResource(R.string.stat_cards), portfolio.totalCards.toString(), Modifier.weight(1f)); StatCard(stringResource(R.string.stat_unique), portfolio.cards.size.toString(), Modifier.weight(1f)); StatCard(stringResource(R.string.stat_languages), portfolio.cards.map { it.language }.distinct().size.toString(), Modifier.weight(1f)) } }
    // Keep the official community entry within the initial Home viewport, while
    // the Binder remains the main visual action directly beneath it.
    item { DiscordSupportCard(context) }
    item { Button(openBinder, Modifier.fillMaxWidth().height(68.dp), shape = RoundedCornerShape(22.dp), colors = ButtonDefaults.buttonColors(containerColor = MewRose, contentColor = VaultNight)) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text("▥", fontSize = 26.sp, fontWeight = FontWeight.Black); Column { Text("Mein Binder", fontWeight = FontWeight.Black, fontSize = 18.sp); Text("Sammlung ansehen und verwalten", fontSize = 11.sp) } } } }
    item { OutlinedButton(openScanner, Modifier.fillMaxWidth().height(CardVaultSpacing.buttonHeight), shape = CardVaultShapes.card) { Text(stringResource(R.string.scan_card), fontWeight = FontWeight.Bold) } }
    if (portfolio.cards.isNotEmpty()) {
        item { Text(stringResource(R.string.recent_cards), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        items(portfolio.cards.take(3)) { CardRow(it) }
    }
    item { Text(stringResource(R.string.catalog_growth_note, contentUpdates.revision), color = VaultTextMuted, fontSize = 10.sp, lineHeight = 14.sp) }
    }
}

@Composable private fun OwnerSupportModeCard() {
    var active by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = VaultNightRaised), shape = CardVaultShapes.card) {
        Column(Modifier.padding(CardVaultSpacing.card), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.owner_support_title), fontWeight = FontWeight.Bold)
            Text(stringResource(if (active) R.string.owner_support_active else R.string.owner_support_inactive), color = if (active) VaultPositive else VaultTextMuted, fontSize = 12.sp)
            Text(stringResource(R.string.owner_support_scope), color = VaultTextMuted, fontSize = 11.sp)
            OutlinedButton(
                onClick = { active = !active },
                enabled = OwnerSupportConfig.isSecureBackendConfigured,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (active) R.string.owner_support_stop else R.string.owner_support_start)) }
            Text(stringResource(R.string.owner_support_package), color = VaultTextMuted, fontSize = 10.sp)
            if (!OwnerSupportConfig.isSecureBackendConfigured) Text(stringResource(R.string.owner_support_unconfigured), color = VaultTextMuted, fontSize = 10.sp)
        }
    }
}

@Composable private fun ProjectSupportCard() = OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = VaultNightRaised), shape = CardVaultShapes.card) {
    Column(Modifier.padding(CardVaultSpacing.card), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.project_support_title), fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.project_support_body), color = VaultTextMuted, fontSize = 11.sp)
        OutlinedButton(onClick = {}, enabled = ProjectSupportConfig.isConfigured, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.project_support_action)) }
        if (!ProjectSupportConfig.isConfigured) Text(stringResource(R.string.project_support_unconfigured), color = VaultTextMuted, fontSize = 10.sp)
    }
}

@Composable private fun GoogleAccountCard() {
    val context = LocalContext.current
    val gateway = remember(context) { FirebaseGoogleAuthGateway(context) }
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(gateway.currentAccount()) }
    var working by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<Int?>(null) }
    OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = VaultNightRaised), shape = CardVaultShapes.card) {
        Column(Modifier.padding(CardVaultSpacing.card), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(stringResource(R.string.account_title), fontWeight = FontWeight.Bold)
            Text(
                if (account.isSignedIn) stringResource(R.string.account_signed_in, account.displayName ?: stringResource(R.string.account_name_hidden))
                else stringResource(R.string.account_signed_out),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
            )
            if (account.isSignedIn) {
                OutlinedButton(onClick = { gateway.signOut(); account = GoogleAccountState(false, null); feedback = null }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_sign_out)) }
            } else {
                Button(onClick = {
                    working = true; feedback = null
                    scope.launch {
                        when (val result = gateway.signIn()) {
                            is GoogleLoginResult.Success -> account = result.account
                            GoogleLoginResult.Cancelled -> feedback = R.string.account_login_cancelled
                            is GoogleLoginResult.Failed -> feedback = R.string.account_login_failed
                        }
                        working = false
                    }
                }, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (working) R.string.account_login_running else R.string.account_login_google)) }
            }
            feedback?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
            Text(stringResource(R.string.account_no_role), color = VaultTextMuted, fontSize = 10.sp)
        }
    }
}

@Composable private fun SupportInfoCard() {
    val context = LocalContext.current
    OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = VaultNightRaised), shape = CardVaultShapes.card) {
        Column(Modifier.padding(CardVaultSpacing.card), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(stringResource(R.string.support_title), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.support_operator), color = MewRoseSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(if (AppSupportConfig.isConfigured) R.string.support_ready else R.string.support_not_configured), color = VaultTextMuted, fontSize = 11.sp)
            OutlinedButton(onClick = { openSupportEmail(context) }, enabled = AppSupportConfig.isConfigured, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.support_contact)) }
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.support_inbox)) }
            Text(stringResource(R.string.support_inbox_not_configured), color = VaultTextMuted, fontSize = 10.sp)
            Text(stringResource(R.string.support_privacy), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        }
    }
}

@Composable private fun DiscordSupportCard(context: Context) = OutlinedCard(
    colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface),
    shape = CardVaultShapes.card,
) {
    Column(Modifier.padding(CardVaultSpacing.card), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("DomiDex-Community", fontWeight = FontWeight.Bold)
        Text("Fragen, Feedback und Support findest du auf dem offiziellen DomiDex-Discord.", color = VaultTextMuted, fontSize = 12.sp)
        OutlinedButton(onClick = { openDiscordCommunity(context) }, enabled = AppSupportConfig.hasCommunity, modifier = Modifier.fillMaxWidth()) {
            Text("Discord öffnen")
        }
        Text("Discord wird nur nach deinem Antippen geöffnet. DomiDex überträgt dabei keine Sammlung oder Scanner-Aufnahmen.", color = VaultTextMuted, fontSize = 10.sp)
    }
}

private fun openSupportEmail(context: Context) {
    if (!AppSupportConfig.isConfigured) return
    val uri = Uri.Builder().scheme("mailto").opaquePart(AppSupportConfig.email).build()
    val intent = Intent(Intent.ACTION_SENDTO, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "DomiDex – Supportanfrage")
        .putExtra(Intent.EXTRA_TEXT, "Problembeschreibung:\n\n\nApp-Version: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}\n\nKeine Diagnose- oder Sammlungsdaten wurden automatisch angehängt.")
    context.startActivity(intent)
}

private fun openDiscordCommunity(context: Context) {
    if (!AppSupportConfig.hasCommunity) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppSupportConfig.communityUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // Do not pre-filter with resolveActivity: package-visibility rules can make
    // a perfectly usable browser look unavailable and turn the button into a no-op.
    runCatching { context.startActivity(intent) }
}

@Composable private fun CatalogSyncCard(status: CatalogSyncStatus, refresh: () -> Unit) {
    val stale = status.lastSuccessAtEpochMillis?.let { System.currentTimeMillis() - it >= 24 * 60 * 60 * 1000L } == true
    val label = stringResource(when { status.state == "RUNNING" -> R.string.sync_running; status.state == "ERROR" -> R.string.sync_error; stale -> R.string.sync_stale; status.state == "SUCCESS" -> R.string.sync_current; else -> R.string.sync_never })
    val last = status.lastSuccessAtEpochMillis?.let { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, Locale.GERMANY).format(java.util.Date(it)) }
    OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = VaultNightRaised), shape = CardVaultShapes.card) {
        Column(Modifier.padding(CardVaultSpacing.card), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(stringResource(R.string.sync_title), fontWeight = FontWeight.Bold); Text(label, color = if (status.state == "ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }; TextButton(onClick = refresh, enabled = status.state != "RUNNING") { Text(stringResource(R.string.sync_refresh)) } }
            if (last != null) Text(stringResource(R.string.sync_last_success, last, status.dataCreatedAt ?: stringResource(R.string.unknown)), color = VaultTextMuted, fontSize = 11.sp)
            if (status.catalogProductCount > 0) Text(stringResource(R.string.sync_counts, status.catalogProductCount, status.priceRecordCount), color = VaultTextMuted, fontSize = 11.sp)
            status.errorMessage?.let { Text(stringResource(R.string.sync_failure_retention, it), color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
            Text(stringResource(R.string.sync_value_policy), color = VaultTextMuted, fontSize = 11.sp)
        }
    }
}

@Composable private fun PortfolioHero(portfolio: PortfolioSummary, focusChoice: String, onFocusChoice: (String) -> Unit) {
    var choosingFocus by remember { mutableStateOf(false) }
    val automatic = portfolio.cards.maxByOrNull { it.currentValueEur }
    val manual = focusChoice.removePrefix("entry:").toLongOrNull()?.let { id -> portfolio.cards.firstOrNull { it.entryId == id } }
    val focusCard = if (focusChoice == "auto") automatic else manual ?: automatic
    ElevatedCard(
        shape = CardVaultShapes.feature,
        colors = CardDefaults.elevatedCardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(CardVaultSpacing.heroHeight).background(Brush.linearGradient(listOf(Color(0xFF382442), Color(0xFF242348), Color(0xFF173647))))) {
            Canvas(Modifier.matchParentSize().clearAndSetSemantics { }) {
                drawCircle(MewIce.copy(alpha = .10f), radius = size.minDimension * .52f, center = Offset(size.width * .84f, size.height * .20f))
                drawCircle(MewRose.copy(alpha = .08f), radius = size.minDimension * .34f, center = Offset(size.width * .60f, size.height * .92f))
            }
            Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Surface(color = MewRose.copy(alpha = .16f), shape = CardVaultShapes.field) { Text(stringResource(R.string.portfolio_private_badge), Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = Color(0xFFFFD8E5), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    Text(stringResource(R.string.portfolio_estimated_value), color = VaultTextMuted, fontSize = 13.sp)
                    Text(if (portfolio.hasPricedCards) euro(portfolio.totalValue) else "Kein Marktwert verfügbar", fontSize = if (portfolio.hasPricedCards) 30.sp else 18.sp, fontWeight = FontWeight.Black, color = VaultText, maxLines = 1)
                    Text(
                        if (portfolio.history.size > 1) stringResource(R.string.portfolio_change, portfolio.changePercent) else "Noch nicht genügend echte Verlaufsdaten",
                        color = if (portfolio.changePercent >= 0) VaultPositive else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                    )
                    Text(
                        if (portfolio.unpricedCards == 0) "Cardmarket-Wert für alle ${portfolio.pricedCards} Karten verfügbar"
                        else "Bepreist: ${portfolio.pricedCards} von ${portfolio.totalCards} Karten",
                        color = VaultTextMuted,
                        fontSize = 10.sp,
                    )
                    TextButton(onClick = { choosingFocus = true }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) { Text(stringResource(if (focusChoice == "auto") R.string.focus_automatic else R.string.focus_manual), fontSize = 11.sp) }
                }
                InteractiveFocusCard(focusCard, Modifier.size(116.dp, 164.dp))
            }
        }
    }
    if (choosingFocus) AlertDialog(
        onDismissRequest = { choosingFocus = false }, title = { Text(stringResource(R.string.focus_choose_title)) },
        text = { LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedCard(onClick = { onFocusChoice("auto"); choosingFocus = false }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text(stringResource(R.string.focus_automatic), fontWeight = FontWeight.Bold); Text(stringResource(R.string.focus_automatic_hint), color = VaultTextMuted, fontSize = 11.sp) } } }
            items(portfolio.cards, key = { it.entryId }) { card -> OutlinedCard(onClick = { onFocusChoice("entry:${card.entryId}"); choosingFocus = false }, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { if (card.imageUrl != null) AsyncImage(card.imageUrl, null, Modifier.size(36.dp, 50.dp)) else NeutralCardFace(Modifier.size(36.dp, 50.dp)); Column { Text(card.name, fontWeight = FontWeight.Bold); Text("${card.set} · ${card.number}", color = VaultTextMuted, fontSize = 11.sp) } } } }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { choosingFocus = false }) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable private fun InteractiveFocusCard(
    card: CollectionCard?,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
) {
    val context = LocalContext.current
    val effect = remember(card?.id, card?.finish, card?.variant) { CardEffectResolver.resolve(card) }
    var sensorX by remember { mutableFloatStateOf(0f) }; var sensorY by remember { mutableFloatStateOf(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }; var dragY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val settledDragX by animateFloatAsState(dragX, spring(stiffness = androidx.compose.animation.core.Spring.StiffnessLow), label = "card-tilt-x")
    val settledDragY by animateFloatAsState(dragY, spring(stiffness = androidx.compose.animation.core.Spring.StiffnessLow), label = "card-tilt-y")
    DisposableEffect(context, interactive, effect.profile) {
        if (!interactive || effect.profile == CardEffectProfile.NONE) return@DisposableEffect onDispose { }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                val matrix = FloatArray(9); val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(matrix, event.values); SensorManager.getOrientation(matrix, orientation)
                sensorX = (orientation[1] * 9f).coerceIn(-7f, 7f); sensorY = (-orientation[2] * 9f).coerceIn(-7f, 7f)
            }
        }
        if (sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { manager.unregisterListener(listener) }
    }
    val tiltX = (sensorY / 8f + settledDragX).coerceIn(-1f, 1f)
    val tiltY = (sensorX / 8f + settledDragY).coerceIn(-1f, 1f)
    Box(modifier.graphicsLayer { rotationX = -tiltY * 10f; rotationY = tiltX * 10f; cameraDistance = 14 * density }
        .pointerInput(interactive, effect.profile) {
            if (interactive && effect.profile != CardEffectProfile.NONE) detectDragGestures(
                onDragStart = { isDragging = true },
                onDragEnd = { isDragging = false; dragX = 0f; dragY = 0f },
                onDragCancel = { isDragging = false; dragX = 0f; dragY = 0f },
            ) { change, _ ->
                change.consume()
                dragX = ((change.position.x / size.width) - .5f).coerceIn(-.5f, .5f) * 2f
                dragY = ((change.position.y / size.height) - .5f).coerceIn(-.5f, .5f) * 2f
            }
        }
        .background(VaultNightRaised, CardVaultShapes.card).border(1.dp, MewIce.copy(alpha = .65f), CardVaultShapes.card)) {
        if (card?.imageUrl != null) AsyncImage(card.imageUrl, stringResource(R.string.focus_card_description, card.name), Modifier.fillMaxSize()) else NeutralCardFace(Modifier.fillMaxSize())
        if (interactive) HoloEffectLayer(effect, tiltX, tiltY, isDragging, Modifier.matchParentSize().clearAndSetSemantics { })
        else StaticCardEffectLayer(effect, Modifier.matchParentSize().clearAndSetSemantics { })
    }
}

@Composable private fun NeutralCardFace(modifier: Modifier = Modifier) = Canvas(modifier.background(Brush.linearGradient(listOf(Color(0xFF1B2437), Color(0xFF352B4B))), CardVaultShapes.card).padding(10.dp)) {
    drawRoundRect(VaultOutline, style = Stroke(2f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f))
    val center = Offset(size.width / 2, size.height * .42f); val r = size.minDimension * .16f
    val gem = Path().apply { moveTo(center.x, center.y - r); lineTo(center.x + r, center.y); lineTo(center.x, center.y + r); lineTo(center.x - r, center.y); close() }
    drawPath(gem, MewIce.copy(alpha = .72f), style = Stroke(4f)); drawLine(VaultOutline, Offset(size.width * .2f, size.height * .72f), Offset(size.width * .8f, size.height * .72f), 3f); drawLine(VaultOutline.copy(alpha = .6f), Offset(size.width * .28f, size.height * .8f), Offset(size.width * .72f, size.height * .8f), 3f)
}

@Composable private fun StatCard(label: String, value: String, modifier: Modifier) = OutlinedCard(modifier, shape = CardVaultShapes.card, colors = CardDefaults.outlinedCardColors(containerColor = VaultNightRaised)) { Column(Modifier.padding(CardVaultSpacing.card)) { Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MewIce); Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable private fun ValueHistoryChart(points: List<PortfolioPoint>, modifier: Modifier) { Canvas(modifier) {
    if (points.size < 2) return@Canvas
    val min = points.minOf { it.value }; val range = (points.maxOf { it.value } - min).coerceAtLeast(1.0); val path = Path()
    points.forEachIndexed { i, p -> val x = i * size.width / (points.size - 1); val y = size.height - (((p.value - min) / range).toFloat() * size.height * .78f) - size.height * .1f; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
    drawLine(VaultOutline.copy(.45f), Offset(0f, size.height * .9f), Offset(size.width, size.height * .9f), 2f)
    drawPath(path, MewIce.copy(alpha = .20f), style = Stroke(14f, cap = StrokeCap.Round))
    drawPath(path, MewRose, style = Stroke(6f, cap = StrokeCap.Round))
} }

private enum class PriceHistoryRange(val label: String, val days: Long?) {
    DAYS_7("7T", 7), DAYS_30("30T", 30), MONTHS_3("3M", 90),
    MONTHS_6("6M", 180), YEAR("1J", 365), MAX("MAX", null),
}

/**
 * A detail-only chart over persisted Cardmarket observations. No data is
 * interpolated: one snapshot stays one actual point, and a single observation
 * remains an honest, non-chart state.
 */
@Composable private fun CardPriceHistorySection(card: CollectionCard, history: List<CardPricePoint>) {
    val current = history.lastOrNull()?.valueEur ?: card.currentValueEur.takeIf { card.priceUpdatedAtEpochMillis != null }
    val now = remember(history) { maxOf(System.currentTimeMillis(), history.lastOrNull()?.timestampEpochMillis ?: 0L) }
    val ranges = remember(history, now) {
        PriceHistoryRange.entries.filter { range ->
            range == PriceHistoryRange.MAX || history.count { it.timestampEpochMillis >= now - range.days!! * 86_400_000L } >= 2
        }
    }
    var selectedRangeName by rememberSaveable(card.entryId, card.id) { mutableStateOf(PriceHistoryRange.MAX.name) }
    val selectedRange = PriceHistoryRange.entries.firstOrNull { it.name == selectedRangeName }?.takeIf { it in ranges } ?: PriceHistoryRange.MAX
    val visibleHistory = remember(history, selectedRange, now) {
        selectedRange.days?.let { days -> history.filter { it.timestampEpochMillis >= now - days * 86_400_000L } } ?: history
    }
    val change = visibleHistory.takeIf { it.size >= 2 }?.let { points -> points.last().valueEur - points.first().valueEur }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AKTUELLER WERT", fontWeight = FontWeight.Bold, color = MewRoseSoft)
        if (current == null) {
            Text("Kein Marktwert verfügbar", color = VaultTextMuted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("Für diese konkrete deutsche Variante ist noch kein eindeutig verknüpfter Cardmarket-Wert gespeichert.", color = VaultTextMuted, fontSize = 11.sp)
        } else {
            Text(euro(current), color = MewIce, fontSize = 25.sp, fontWeight = FontWeight.Black)
            change?.let { delta ->
                val baseline = visibleHistory.first().valueEur
                val percent = if (baseline != 0.0) delta * 100 / baseline else null
                Text(
                    (if (delta >= 0) "+" else "") + euro(delta) + (percent?.let { " · " + (if (it >= 0) "+" else "") + "%.1f%%".format(Locale.GERMANY, it) } ?: ""),
                    color = if (delta >= 0) VaultPositive else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
            }
            Text(
                history.lastOrNull()?.let { point -> "Marktdaten: Cardmarket · ${formatPriceDate(point.timestampEpochMillis)}" }
                    ?: "Marktdaten: Cardmarket · aus lokalem Cache",
                color = VaultTextMuted,
                fontSize = 11.sp,
            )
        }
        if (visibleHistory.size >= 2) {
            Text("PREISVERLAUF", color = MewRoseSoft, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ranges.forEach { range ->
                    FilterChip(
                        selected = selectedRange == range,
                        onClick = { selectedRangeName = range.name },
                        label = { Text(range.label, fontSize = 10.sp) },
                    )
                }
            }
            CardPriceHistoryChart(visibleHistory, Modifier.fillMaxWidth().height(126.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatPriceDate(visibleHistory.first().timestampEpochMillis), color = VaultTextMuted, fontSize = 10.sp)
                Text(formatPriceDate(visibleHistory.last().timestampEpochMillis), color = VaultTextMuted, fontSize = 10.sp)
            }
        } else if (current != null) {
            Text("Noch nicht genügend echte Verlaufsdaten. Der Verlauf wächst mit weiteren Cardmarket-Snapshots.", color = VaultTextMuted, fontSize = 11.sp)
        }
    }
}

@Composable private fun CardPriceHistoryChart(points: List<CardPricePoint>, modifier: Modifier) = Canvas(modifier) {
    if (points.size < 2) return@Canvas
    val min = points.minOf { it.valueEur }
    val max = points.maxOf { it.valueEur }
    val range = (max - min).coerceAtLeast(.01)
    val left = 4.dp.toPx(); val right = size.width - 4.dp.toPx()
    val top = 10.dp.toPx(); val bottom = size.height - 18.dp.toPx()
    repeat(3) { index ->
        val y = top + (bottom - top) * index / 2f
        drawLine(VaultOutline.copy(alpha = .32f), Offset(left, y), Offset(right, y), 1.dp.toPx())
    }
    val path = Path()
    points.forEachIndexed { index, point ->
        val x = left + (right - left) * index / (points.size - 1)
        val y = bottom - (((point.valueEur - min) / range).toFloat() * (bottom - top))
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, MewIce.copy(alpha = .22f), style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
    drawPath(path, MewRose, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    points.forEachIndexed { index, point ->
        val x = left + (right - left) * index / (points.size - 1)
        val y = bottom - (((point.valueEur - min) / range).toFloat() * (bottom - top))
        drawCircle(MewIce, 3.dp.toPx(), Offset(x, y))
    }
}

private fun formatPriceDate(timestamp: Long): String = java.time.Instant.ofEpochMilli(timestamp)
    .atZone(java.time.ZoneId.systemDefault())
    .toLocalDate()
    .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yy", Locale.GERMANY))

@Composable private fun CollectionScreen(cards: List<CollectionCard>, repository: CollectionRepository) {
    var query by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var choosingAddMode by remember { mutableStateOf(false) }
    var choosingCatalogCard by remember { mutableStateOf(false) }
    var catalogDraft by remember { mutableStateOf<CatalogSearchResult?>(null) }
    var addOutcome by remember { mutableStateOf<AddCardOutcome?>(null) }
    var editing by remember { mutableStateOf<CollectionCard?>(null) }
    var viewing by remember { mutableStateOf<CollectionCard?>(null) }
    var deleting by remember { mutableStateOf<CollectionCard?>(null) }
    var linking by remember { mutableStateOf<CollectionCard?>(null) }
    val binders by repository.binders.collectAsState(initial = emptyList())
    var selectedBinderId by rememberSaveable { mutableStateOf<String?>(null) }
    var binderDeletionStage by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val filtered = cards.filter { (selectedBinderId == null || it.binderId == selectedBinderId) && (it.name.contains(query, true) || it.set.contains(query, true)) }
    LazyColumn(contentPadding = PaddingValues(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(CardVaultSpacing.card)) {
    item { val count = cards.sumOf { it.quantity }; ScreenHeader(stringResource(R.string.collection_eyebrow), if (selectedBinderId == null) stringResource(R.string.binder_title) else binders.firstOrNull { it.id == selectedBinderId }?.name ?: stringResource(R.string.binder_title), pluralStringResource(R.plurals.collection_cards_count, count, count) + " · " + pluralStringResource(R.plurals.collection_entries_count, cards.size, cards.size)) }
    if (selectedBinderId == null) item { LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) { items(binders, key = { it.id }) { binder -> OutlinedCard(onClick = { selectedBinderId = binder.id }, colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface)) { Column(Modifier.padding(14.dp).widthIn(min = 155.dp, max = 220.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(binder.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(stringResource(R.string.binder_progress, binder.ownedCards, binder.uniqueCards), color = VaultTextMuted, fontSize = 11.sp); Text(stringResource(R.string.binder_market_value, euro(binder.marketValueEur)), color = MewIce, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) } } } } }
    else item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { selectedBinderId = null }) { Text(stringResource(R.string.action_back) + " · " + stringResource(R.string.binder_all)) }
            TextButton(onClick = { binderDeletionStage = 1 }) { Text(stringResource(R.string.binder_manage)) }
        }
    }
    item { Button(onClick = { choosingAddMode = true }, modifier = Modifier.fillMaxWidth(), shape = CardVaultShapes.card) { Text(stringResource(R.string.collection_add), fontWeight = FontWeight.Bold) } }
    addOutcome?.let { outcome -> item { Text(stringResource(if (outcome == AddCardOutcome.QUANTITY_INCREMENTED) R.string.add_outcome_incremented else R.string.add_outcome_created), color = VaultPositive, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) } }
    item { OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.collection_search)) }, singleLine = true, shape = CardVaultShapes.field, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MewRose, cursorColor = MewRose, focusedLabelColor = MewRose, unfocusedContainerColor = VaultNightRaised, focusedContainerColor = VaultNightRaised), modifier = Modifier.fillMaxWidth()) }
    if (filtered.isEmpty()) item { Text(stringResource(R.string.collection_empty), modifier = Modifier.padding(vertical = 30.dp)) }
    items(filtered, key = { it.entryId }) { card -> CardRow(card) { viewing = card } }; item { DataNote() }
    }
    if (choosingAddMode) AddModeDialog(onDismiss = { choosingAddMode = false }, onCatalog = { choosingAddMode = false; choosingCatalogCard = true }, onManual = { choosingAddMode = false; catalogDraft = null; adding = true })
    if (choosingCatalogCard) CatalogProductPicker(repository, onDismiss = { choosingCatalogCard = false }, onSelected = { catalogDraft = it; choosingCatalogCard = false; adding = true })
    if (adding) ManualCardDialog(repository = repository, initialCatalog = catalogDraft, onDismiss = { adding = false; catalogDraft = null }, onSave = { input -> scope.launch { addOutcome = repository.addManualCard(input) }; adding = false; catalogDraft = null })
    viewing?.let { card -> CardPreviewDialog(card, repository, onDismiss = { viewing = null }, onEdit = { viewing = null; editing = card }) }
    editing?.let { card -> EditEntryDialog(card, onDismiss = { editing = null }, onDelete = { editing = null; deleting = card }, onLink = { editing = null; linking = card }, onSave = { update -> scope.launch { repository.updateEntry(card.entryId, update) }; editing = null }) }
    linking?.let { card -> CatalogLinkDialog(card, repository, onDismiss = { linking = null }, onLinked = { productId -> scope.launch { repository.linkCatalogProduct(card.entryId, productId) }; linking = null }) }
    deleting?.let { card -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete_entry_title)) }, text = { Text(stringResource(R.string.delete_entry_body, card.name)) }, confirmButton = { TextButton(onClick = { scope.launch { repository.deleteEntry(card.entryId) }; deleting = null }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } }) }
    val selectedBinder = binders.firstOrNull { it.id == selectedBinderId }
    if (binderDeletionStage == 1 && selectedBinder != null) AlertDialog(
        onDismissRequest = { binderDeletionStage = 0 },
        title = { Text(stringResource(R.string.binder_delete_first_title)) },
        text = { Text(stringResource(R.string.binder_delete_first_body, selectedBinder.name)) },
        confirmButton = { TextButton(onClick = { binderDeletionStage = 2 }) { Text(stringResource(R.string.binder_delete)) } },
        dismissButton = { TextButton(onClick = { binderDeletionStage = 0 }) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (binderDeletionStage == 2 && selectedBinder != null) AlertDialog(
        onDismissRequest = { binderDeletionStage = 0 },
        title = { Text(stringResource(R.string.binder_delete_second_title)) },
        text = { Text(stringResource(R.string.binder_delete_second_body)) },
        confirmButton = { TextButton(onClick = {
            scope.launch { repository.deleteBinder(selectedBinder.id) }
            selectedBinderId = null
            binderDeletionStage = 0
        }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { binderDeletionStage = 0 }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable private fun CardPreviewDialog(
    card: CollectionCard,
    repository: CollectionRepository,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val effect = remember(card.id, card.finish, card.variant) { CardEffectResolver.resolve(card) }
    val priceHistory by repository.priceHistory(card).collectAsState(initial = emptyList())
    val priceDate = card.priceUpdatedAtEpochMillis?.let { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, Locale.GERMANY).format(java.util.Date(it)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(card.name) }, text = { LazyColumn(Modifier.fillMaxWidth().heightIn(max = 570.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { InteractiveFocusCard(card, Modifier.size(174.dp, 246.dp), interactive = true) }
        item { Text("${card.set} · ${card.number}", fontWeight = FontWeight.Bold) }
        item { Text(stringResource(R.string.card_preview_variant, card.language.label, card.finish, card.variant), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
        item { Text("Karteneffekt automatisch: ${effect.profile.label}", color = MewIce, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        item { HorizontalDivider(color = VaultOutline) }
        item { CardPriceHistorySection(card, priceHistory) }
        item { PriceSourcesSection(card, priceDate, context) }
    } }, confirmButton = {
        Row {
            if (onAdd != null) TextButton(onClick = onAdd) { Text("Zum Binder hinzufügen") }
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Aus Binder entfernen", color = MaterialTheme.colorScheme.error) }
            if (onEdit != null) TextButton(onClick = onEdit) { Text(stringResource(R.string.card_preview_edit)) }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

/**
 * A reference slot is not enough to assign a value.  Adding it is deliberately
 * a two-step operation: only a Cardmarket product with the same printed name,
 * set and collector number may be linked automatically.  Anything else stays
 * visibly unpriced instead of borrowing a similarly named card's value.
 */
@Composable private fun BinderCardAddDialog(
    reference: CollectionCard,
    repository: CollectionRepository,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
) {
    var candidates by remember(reference.id) { mutableStateOf<List<CatalogSearchResult>>(emptyList()) }
    var loading by remember(reference.id) { mutableStateOf(true) }
    var adding by remember(reference.id) { mutableStateOf(false) }
    var message by remember(reference.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reference.id) {
        loading = true
        val catalogMatches = runCatching { repository.searchCatalog(reference.name, 60) }
            .getOrDefault(emptyList())
            .filter { product -> isExactBinderPriceMatch(reference, product) }
            .distinctBy { it.productId }
        // An already owned card with a saved product identity is stronger
        // evidence than a fresh text search.  Reusing it for another copy is
        // safe and keeps the existing exact Cardmarket linkage intact.
        val knownLinkedProduct = reference.externalProductId?.let { productId ->
            CatalogSearchResult(
                productId = productId,
                name = reference.name,
                expansionId = reference.set,
                trendPriceEur = reference.currentValueEur.takeIf { reference.priceUpdatedAtEpochMillis != null },
                currency = "EUR",
                visualSetName = reference.set,
                collectorNumber = reference.number,
                imageUrl = reference.imageUrl,
                language = reference.language.label,
                finish = reference.finish,
                variant = reference.variant,
            )
        }
        candidates = listOfNotNull(knownLinkedProduct).plus(catalogMatches).distinctBy { it.productId }
        loading = false
    }
    fun addReference(product: CatalogSearchResult?) {
        if (adding) return
        adding = true
        scope.launch {
            val outcome = runCatching {
                repository.addManualCard(
                    ManualCardInput(
                        name = reference.name,
                        setName = reference.set,
                        collectorNumber = reference.number,
                        language = reference.language,
                        finish = reference.finish,
                        variant = reference.variant,
                        quantity = 1,
                        condition = "Nicht geprüft",
                        notes = "",
                        acquisitionPriceEur = null,
                        acquisitionDateEpochDay = null,
                        externalProductId = product?.productId,
                        imageUrl = reference.imageUrl,
                    ),
                )
            }
            outcome.onSuccess { onAdded() }.onFailure {
                adding = false
                message = "Die Karte konnte gerade nicht hinzugefügt werden. Bitte erneut versuchen."
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!adding) onDismiss() },
        title = { Text("Karte zum Binder hinzufügen") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(reference.name, fontWeight = FontWeight.Bold)
                    Text("${reference.set} · ${reference.number}", color = VaultTextMuted, fontSize = 12.sp)
                    Text("Wähle nur das passende Cardmarket-Produkt. So wird kein Preis einer ähnlich benannten Karte übernommen.", color = VaultTextMuted, fontSize = 12.sp)
                }
                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (!loading && candidates.isNotEmpty()) {
                    val trendChoice = candidates.firstOrNull { it.trendPriceEur != null }
                    trendChoice?.let { product ->
                        item {
                            Text("Zuerst vorgeschlagen", color = MewIce, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            ElevatedCard(
                                onClick = { addReference(product) },
                                enabled = !adding,
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.elevatedCardColors(containerColor = MewPlumDeep),
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("Durchschnittspreis · Cardmarket-Trend", fontWeight = FontWeight.Bold)
                                    Text(euro(product.trendPriceEur!!), color = MewIce, fontSize = 20.sp, fontWeight = FontWeight.Black)
                                    Text("Passenden Druck auswählen und zum Binder hinzufügen", color = VaultTextMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    item { Text("Weitere passende Cardmarket-Drucke", color = MewIce, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                    items(candidates.filter { it.productId != trendChoice?.productId }, key = { it.productId }) { product ->
                        OutlinedCard(
                            onClick = { addReference(product) },
                            enabled = !adding,
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("${product.visualSetName ?: reference.set} · ${product.collectorNumber ?: reference.number}", fontWeight = FontWeight.Bold)
                                Text(
                                    listOfNotNull(product.language, product.finish, product.variant)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · ")
                                        .ifBlank { "Cardmarket-Produktdetails" },
                                    color = VaultTextMuted,
                                    fontSize = 11.sp,
                                )
                                Text(
                                    product.trendPriceEur?.let { "Cardmarket-Trendpreis: ${euro(it)}" } ?: "Cardmarket-Produkt ohne gespeicherten Trendpreis",
                                    color = if (product.trendPriceEur != null) MewIce else VaultTextMuted,
                                    fontSize = 13.sp,
                                )
                                Text("Auswählen und zum Binder hinzufügen", color = MewRoseSoft, fontSize = 11.sp)
                            }
                        }
                    }
                }
                if (!loading && candidates.isEmpty()) item {
                    Text("Für diese genaue Karte ist derzeit kein eindeutig passendes Cardmarket-Produkt im lokalen Katalog vorhanden.", color = VaultTextMuted, fontSize = 12.sp)
                    Text("Du kannst sie ohne Preis hinzufügen und später über die Kartendetails exakt verknüpfen. Es wird kein geschätzter oder fremder Preis eingesetzt.", color = VaultTextMuted, fontSize = 11.sp)
                    OutlinedButton(onClick = { addReference(null) }, enabled = !adding, modifier = Modifier.fillMaxWidth()) { Text("Ohne Preis hinzufügen") }
                }
                message?.let { item { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, enabled = !adding) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun isExactBinderPriceMatch(reference: CollectionCard, product: CatalogSearchResult): Boolean {
    val sameName = normalizeCardName(reference.name) == normalizeCardName(product.name)
    val sameNumber = normalizedCollectorNumber(reference.number) == normalizedCollectorNumber(product.collectorNumber.orEmpty())
    val sameSet = normalizeBinderMetadata(reference.set) == normalizeBinderMetadata(product.visualSetName ?: product.expansionId)
    return sameName && sameNumber && sameSet
}

private fun normalizedCollectorNumber(value: String): String = value
    .substringBefore('/')
    .filter(Char::isLetterOrDigit)
    .trimStart('0')
    .ifBlank { "0" }

private fun normalizeBinderMetadata(value: String): String = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

@Composable private fun PriceSourcesSection(card: CollectionCard, priceDate: String?, context: Context) = Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(stringResource(R.string.price_sources_title), fontWeight = FontWeight.Bold, color = MewRoseSoft)
    Text(if (priceDate == null) stringResource(R.string.raw_reference_unavailable) else stringResource(R.string.raw_reference_value, euro(card.currentValueEur), priceDate), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    if (card.externalProductId != null) {
        Button(onClick = { openOfficialPage(context, OfficialSourceLinks.cardmarketProduct(card.externalProductId)) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.open_cardmarket_current)) }
        Text(stringResource(R.string.cardmarket_external_exact_hint, card.externalProductId), color = VaultTextMuted, fontSize = 10.sp)
    } else {
        OutlinedButton(onClick = { openOfficialPage(context, OfficialSourceLinks.cardmarketSearch(card)) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.open_cardmarket_search)) }
        Text(stringResource(R.string.cardmarket_external_unlinked_hint), color = VaultTextMuted, fontSize = 10.sp)
    }
    OutlinedButton(onClick = {
        val query = OfficialSourceLinks.psaSearchText(card)
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PSA-Suchtext", query))
        openOfficialPage(context, OfficialSourceLinks.PSA_AUCTION_PRICES)
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.open_psa_research)) }
    Text(stringResource(R.string.psa_research_hint, OfficialSourceLinks.psaSearchText(card)), color = VaultTextMuted, fontSize = 10.sp)
    Text("Portfoliowert ausschließlich aus dem aktuellen Cardmarket-Trendpreis des ausdrücklich verknüpften Produkts. Holo nutzt nur den Cardmarket-Holo-Trend, wenn vorhanden. Ohne exakte Zuordnung zeigt DomiDex keinen geschätzten Preis.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
}

private fun openOfficialPage(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable private fun CardRow(card: CollectionCard, onClick: (() -> Unit)? = null) = ElevatedCard(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), shape = CardVaultShapes.card, colors = CardDefaults.elevatedCardColors(containerColor = VaultPlumSurface)) { Row(Modifier.padding(CardVaultSpacing.card), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CardVaultSpacing.card)) {
    Box(Modifier.size(54.dp, 75.dp).background(Color(card.accent), CardVaultShapes.thumbnail), contentAlignment = Alignment.Center) {
        if (card.imageUrl != null) AsyncImage(model = card.imageUrl, contentDescription = stringResource(R.string.collection_card_image_description, card.name, card.number), modifier = Modifier.fillMaxSize())
        else Text(card.name.take(1), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
    }
    Column(Modifier.weight(1f)) { Text(card.name, fontWeight = FontWeight.Bold); Text("${card.set} · ${card.number}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(stringResource(R.string.card_metadata, card.language.label, card.condition, card.quantity), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp); if (card.externalProductId != null) Text(stringResource(R.string.catalog_linked_badge), color = MewIce, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
    Column(horizontalAlignment = Alignment.End) { Text(if (card.priceUpdatedAtEpochMillis == null) stringResource(R.string.no_market_price) else euro(card.currentValueEur * card.quantity), fontWeight = FontWeight.Bold, fontSize = if (card.priceUpdatedAtEpochMillis == null) 11.sp else 14.sp); if (card.priceUpdatedAtEpochMillis != null) Text((if (card.changePercent >= 0) "+" else "") + "%.1f%%".format(Locale.US, card.changePercent), color = if (card.changePercent >= 0) VaultPositive else MaterialTheme.colorScheme.error, fontSize = 12.sp) }
} }

@Composable private fun AddModeDialog(onDismiss: () -> Unit, onCatalog: () -> Unit, onManual: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, title = { Text(stringResource(R.string.add_mode_title)) },
    text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onCatalog, modifier = Modifier.fillMaxWidth(), shape = CardVaultShapes.card) { Text(stringResource(R.string.add_from_catalog), fontWeight = FontWeight.Bold) }
        Text(stringResource(R.string.add_from_catalog_hint), color = VaultTextMuted, fontSize = 12.sp)
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth(), shape = CardVaultShapes.card) { Text(stringResource(R.string.add_without_catalog)) }
        Text(stringResource(R.string.add_without_catalog_hint), color = VaultTextMuted, fontSize = 12.sp)
    } }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
)

@Composable private fun CatalogProductPicker(repository: CollectionRepository, onDismiss: () -> Unit, onSelected: (CatalogSearchResult) -> Unit) {
    val visualCatalog = remember { PokemonVisualCatalog() }
    var query by rememberSaveable { mutableStateOf("") }
    var sets by remember { mutableStateOf<List<PokemonSet>>(emptyList()) }
    var selectedSet by remember { mutableStateOf<PokemonSet?>(null) }
    var cards by remember { mutableStateOf<List<PokemonVisualCard>>(emptyList()) }
    var selectedCard by remember { mutableStateOf<PokemonVisualCard?>(null) }
    var products by remember { mutableStateOf<List<CatalogSearchResult>>(emptyList()) }
    var searchByPokemon by rememberSaveable { mutableStateOf(false) }
    var showAllSets by rememberSaveable { mutableStateOf(false) }
    var setConfirmationHint by rememberSaveable { mutableStateOf(false) }
    var pokemonResults by remember { mutableStateOf<List<CatalogSearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }; var error by remember { mutableStateOf(false) }
    LaunchedEffect(selectedSet) {
        loading = true; error = false; query = ""
        runCatching { if (selectedSet == null) sets = visualCatalog.sets() else cards = visualCatalog.cards(selectedSet!!) }
            .onFailure { error = true }
        loading = false
    }
    LaunchedEffect(selectedCard) {
        val card = selectedCard ?: return@LaunchedEffect
        loading = true; error = false
        // A Cardmarket match is optional.  The TCGdex card selected above
        // already provides the German name, set and collector number needed
        // for a correct Binder entry, so an unavailable price lookup must not
        // trap the user on this screen.
        runCatching {
            repository.searchCatalog(card.name, 40)
                .filter { product -> isExactVisualCatalogPriceMatch(card, product) }
                .map {
                it.copy(
                    visualSetName = card.set.name,
                    collectorNumber = card.number,
                    imageUrl = card.largeImageUrl,
                )
            }
        }.onSuccess { products = it }
            .onFailure { products = emptyList(); error = true }
        loading = false
    }
    LaunchedEffect(query, searchByPokemon, selectedSet) {
        if (selectedSet == null && searchByPokemon && query.trim().length >= 2) pokemonResults = repository.searchCatalog(query, 20) else if (selectedSet == null) pokemonResults = emptyList()
    }
    val matchingSets = remember(sets, query) {
        sets.filter { query.isBlank() || it.name.contains(query, true) || it.series.contains(query, true) }
    }
    val visibleSets = remember(matchingSets, query, showAllSets) {
        if (query.isBlank() && !showAllSets) matchingSets.take(36) else matchingSets
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(when { selectedCard != null -> "Druck auswählen"; selectedSet != null -> selectedSet!!.name; else -> "Karte suchen" }) }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (selectedSet == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(selected = !searchByPokemon, onClick = { searchByPokemon = false; query = ""; setConfirmationHint = false }, label = { Text("Set auswählen") }); FilterChip(selected = searchByPokemon, onClick = { searchByPokemon = true; query = ""; setConfirmationHint = false }, label = { Text("Pokémon suchen") }) }
        if (selectedSet == null && !searchByPokemon) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !showAllSets, onClick = { showAllSets = false }, label = { Text("Aktuelle Sets") })
            FilterChip(selected = showAllSets, onClick = { showAllSets = true }, label = { Text("Alle Sets") })
        }
        if (setConfirmationHint) Text("Pokémon gefunden – bitte jetzt das Set wählen. So landet die Karte sicher im richtigen Binder.", color = MewIce, fontSize = 12.sp)
        if (selectedCard == null) OutlinedTextField(query, { query = it }, label = { Text(if (selectedSet == null && searchByPokemon) "Pokémonname" else if (selectedSet == null) "Set suchen" else "Kartenname oder Nummer") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = CardVaultShapes.field)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error) Text(stringResource(R.string.catalog_visual_error), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                selectedCard != null -> {
                    item { VisualCardRow(selectedCard!!) {} }
                    val selected = selectedCard!!
                    val trendProduct = products.firstOrNull { it.trendPriceEur != null }
                    item {
                        Button(
                            onClick = { onSelected(trendProduct ?: catalogResultForVisualCard(selected)) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = CardVaultShapes.card,
                        ) {
                            Text(
                                trendProduct?.trendPriceEur?.let { "Zum Binder hinzufügen · ${euro(it)}" }
                                    ?: "Zum Binder hinzufügen",
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    item {
                        Text(
                            trendProduct?.trendPriceEur?.let { "Cardmarket-Trendpreis für den exakt passenden Druck: ${euro(it)}" }
                                ?: "Karte ohne bestätigten Marktpreis speichern. Eine Cardmarket-Verknüpfung ist optional.",
                            color = VaultTextMuted,
                            fontSize = 11.sp,
                        )
                    }
                    item { Text("Optional: Cardmarket-Druck verknüpfen", color = MewIce, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    if (!loading && products.isEmpty()) item {
                        Text("Kein passender Cardmarket-Druck gefunden. Du kannst die Karte trotzdem direkt hinzufügen.", color = VaultTextMuted, fontSize = 12.sp)
                    }
                    items(products, key = { it.productId }) { product ->
                        OutlinedCard(onClick = { onSelected(product.copy(name = selected.name, visualSetName = selected.set.name, collectorNumber = selected.number, imageUrl = selected.largeImageUrl)) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Cardmarket-Trendpreis", fontWeight = FontWeight.Bold)
                            Text(product.trendPriceEur?.let(::euro) ?: "Noch kein Trendpreis", color = MewIce, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("Exaktes Produkt auswählen", color = MewRoseSoft, fontSize = 12.sp)
                        } }
                    }
                }
                selectedSet != null -> items(cards.filter { matchesCardInSelectedSet(it.name, it.number, query) }, key = { it.id }) { card -> VisualCardRow(card) { selectedCard = card } }
                searchByPokemon -> items(pokemonResults, key = { it.productId }) { result -> CatalogResultRow(result) {
                    // Marketplace search hits do not reliably carry an app catalog set identity.
                    // Require the visual set step instead of silently creating a technical/random binder.
                    searchByPokemon = false
                    query = ""
                    setConfirmationHint = true
                } }
                else -> {
                    if (query.isBlank() && !showAllSets) item {
                        Text("Aktuelle Erweiterungen zuerst · über „Alle Sets“ oder die Suche erreichst du den vollständigen deutschen Katalog.", color = VaultTextMuted, fontSize = 11.sp)
                    }
                    if (!loading && visibleSets.isEmpty()) item { Text("Kein deutsches Set passend gefunden.", color = VaultTextMuted, fontSize = 12.sp) }
                    items(visibleSets, key = { it.id }) { set ->
                    OutlinedCard(onClick = { selectedSet = set }, colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CatalogSetIcon(set, Modifier.size(64.dp, 40.dp)); Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(set.name, fontWeight = FontWeight.Bold); Text("${set.printedTotal} Karten${set.releaseDate.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}", color = VaultTextMuted, fontSize = 11.sp) }
                    } }
                    }
                }
            }
        }
    } }, confirmButton = {}, dismissButton = { Row { if (selectedCard != null || selectedSet != null) TextButton(onClick = { if (selectedCard != null) { selectedCard = null; products = emptyList() } else selectedSet = null }) { Text(stringResource(R.string.action_back)) }; TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } } })
}

/**
 * A visual-catalog card is safe to add on its own.  Product id 0 is a local
 * sentinel and is deliberately never persisted as a Cardmarket linkage.
 */
private fun catalogResultForVisualCard(card: PokemonVisualCard) = CatalogSearchResult(
    productId = 0L,
    name = card.name,
    expansionId = card.set.id,
    trendPriceEur = null,
    currency = null,
    visualSetName = card.set.name,
    collectorNumber = card.number,
    imageUrl = card.largeImageUrl,
    language = "Deutsch",
    rarity = card.rarity,
    hasMarketProduct = false,
    mainSetSize = card.set.total.takeIf { it > 0 },
    setCode = card.set.ptcgoCode,
)

private fun isExactVisualCatalogPriceMatch(card: PokemonVisualCard, product: CatalogSearchResult): Boolean =
    normalizeCardName(card.name) == normalizeCardName(product.name) &&
        normalizedCollectorNumber(card.number) == normalizedCollectorNumber(product.collectorNumber.orEmpty()) &&
        normalizeBinderMetadata(card.set.name) == normalizeBinderMetadata(product.visualSetName ?: product.expansionId)

@Composable private fun CatalogSetIcon(set: PokemonSet, modifier: Modifier = Modifier) = Box(
    modifier.background(VaultNightRaised, CardVaultShapes.thumbnail),
    contentAlignment = Alignment.Center,
) {
    Text(set.name.take(1).uppercase(), color = MewIce, fontWeight = FontWeight.Black, fontSize = 18.sp)
    set.logoUrl?.let { logoUrl ->
        AsyncImage(model = logoUrl, contentDescription = "${set.name} Set-Icon", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
    }
}

@Composable private fun VisualCardRow(card: PokemonVisualCard, onClick: () -> Unit) = OutlinedCard(onClick = onClick, colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface), modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(model = card.smallImageUrl, contentDescription = stringResource(R.string.catalog_card_image_description, card.name, card.number), modifier = Modifier.size(72.dp, 101.dp))
        Column(Modifier.weight(1f)) { Text(card.name, fontWeight = FontWeight.Bold); Text("#${card.number} · ${card.rarity ?: stringResource(R.string.unknown)}", color = VaultTextMuted, fontSize = 12.sp); Text(card.set.name, color = MewIce, fontSize = 11.sp) }
    }
}

@Composable private fun CatalogResultRow(result: CatalogSearchResult, onClick: () -> Unit) = OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface), shape = CardVaultShapes.card) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) { Text(result.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(stringResource(R.string.catalog_expansion_id, result.expansionId), color = VaultTextMuted, fontSize = 11.sp) }
        Text(result.trendPriceEur?.let(::euro) ?: stringResource(R.string.no_market_price), color = if (result.trendPriceEur == null) VaultTextMuted else MewIce, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable private fun ManualCardDialog(repository: CollectionRepository, initialCatalog: CatalogSearchResult? = null, onDismiss: () -> Unit, onSave: (ManualCardInput) -> Unit) {
    var name by rememberSaveable(initialCatalog?.productId) { mutableStateOf(initialCatalog?.name ?: "") }; var set by rememberSaveable(initialCatalog?.productId) { mutableStateOf(initialCatalog?.visualSetName ?: initialCatalog?.let { "Cardmarket-Erweiterung ${it.expansionId}" } ?: "") }; var number by rememberSaveable(initialCatalog?.productId) { mutableStateOf(initialCatalog?.collectorNumber ?: "") }
    var language by rememberSaveable { mutableStateOf(CardLanguage.GERMAN) }; var finish by rememberSaveable(initialCatalog?.productId) { mutableStateOf(initialCatalog?.finish ?: "Normal") }; var variant by rememberSaveable(initialCatalog?.productId) { mutableStateOf(initialCatalog?.variant ?: "Standard") }; var effectOverride by rememberSaveable(initialCatalog?.productId) { mutableStateOf<String?>(null) }
    var quantity by rememberSaveable { mutableStateOf("1") }; var condition by rememberSaveable { mutableStateOf("Nahezu neuwertig") }; var notes by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }; var date by rememberSaveable { mutableStateOf("") }
    var marketProducts by remember { mutableStateOf<List<CatalogSearchResult>>(emptyList()) }
    var marketLoading by remember { mutableStateOf(false) }
    var selectedMarketProductId by rememberSaveable { mutableStateOf<Long?>(initialCatalog?.productId?.takeIf { initialCatalog.hasMarketProduct }) }
    LaunchedEffect(name, set, number) {
        if (name.trim().length < 2 || set.isBlank() || number.isBlank()) {
            marketProducts = emptyList(); marketLoading = false; selectedMarketProductId = initialCatalog?.productId?.takeIf { initialCatalog.hasMarketProduct }
            return@LaunchedEffect
        }
        marketLoading = true
        val exactProducts = runCatching { repository.searchCatalog(name, 60) }
            .getOrDefault(emptyList())
            .filter { product -> isExactManualPriceMatch(name, set, number, product) }
            .distinctBy { it.productId }
        marketProducts = exactProducts
        selectedMarketProductId = selectedMarketProductId?.takeIf { id -> exactProducts.any { it.productId == id } }
            ?: exactProducts.firstOrNull { it.trendPriceEur != null }?.productId
            ?: exactProducts.firstOrNull()?.productId
        marketLoading = false
    }
    val selectedMarketProduct = marketProducts.firstOrNull { it.productId == selectedMarketProductId }
    val valid = name.isNotBlank() && set.isNotBlank() && number.isNotBlank() && condition.isNotBlank() && (quantity.toIntOrNull() ?: 0) > 0 && parseDate(date) != Long.MIN_VALUE && parsePrice(price) != Double.NEGATIVE_INFINITY
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.manual_add_title)) }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(stringResource(if (initialCatalog == null) R.string.manual_add_boundary else R.string.catalog_add_linked_boundary), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            item { FormField(name, { name = it }, R.string.field_card_name) }; item { FormField(set, { set = it }, R.string.field_set_name) }; item { FormField(number, { number = it }, R.string.field_card_number_printed); Text(stringResource(R.string.field_card_number_hint), color = VaultTextMuted, fontSize = 10.sp) }
            item { Text(stringResource(R.string.field_language), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("Deutsch", color = MewIce, fontWeight = FontWeight.SemiBold, fontSize = 12.sp); Text("DomiDex wird für deutsche Karten geführt.", color = VaultTextMuted, fontSize = 10.sp) }
            item { CardFinishAndEffectEditor(finish, { finish = it }, variant, { variant = it }, effectOverride, { effectOverride = it }) }
            item {
                Text("Cardmarket-Trendpreis", color = MewIce, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                when {
                    marketLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    marketProducts.isEmpty() -> Text("Für Name, Set und Kartennummer ist aktuell kein exakter Cardmarket-Eintrag im lokalen Katalog verfügbar.", color = VaultTextMuted, fontSize = 11.sp)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        marketProducts.forEach { product ->
                            OutlinedCard(
                                onClick = { selectedMarketProductId = product.productId },
                                colors = CardDefaults.outlinedCardColors(containerColor = if (product.productId == selectedMarketProductId) MewPlumDeep else VaultNightRaised),
                                border = BorderStroke(1.dp, if (product.productId == selectedMarketProductId) MewRose else VaultOutline),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(product.trendPriceEur?.let { "Cardmarket-Trend: ${euro(it)}" } ?: "Cardmarket-Produkt ohne gespeicherten Trendpreis", color = if (product.trendPriceEur != null) MewIce else VaultTextMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(listOfNotNull(product.finish, product.variant).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Exakter Druck" }, color = VaultTextMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
            item { FormField(quantity, { quantity = it.filter(Char::isDigit) }, R.string.field_quantity, KeyboardType.Number) }; item { FormField(condition, { condition = it }, R.string.field_condition) }
            item { FormField(price, { price = it }, R.string.field_acquisition_price, KeyboardType.Decimal) }; item { FormField(date, { date = it }, R.string.field_acquisition_date) }; item { FormField(notes, { notes = it }, R.string.field_notes, singleLine = false) }
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = { onSave(ManualCardInput(name, set, number, language, finish.ifBlank { "Normal" }, variant.ifBlank { "Standard" }, quantity.toInt(), condition, notes, parsePrice(price).takeUnless { it == Double.NEGATIVE_INFINITY }, parseDate(date).takeUnless { it == Long.MIN_VALUE }, selectedMarketProduct?.productId ?: initialCatalog?.productId?.takeIf { initialCatalog.hasMarketProduct }, initialCatalog?.imageUrl ?: selectedMarketProduct?.imageUrl, effectOverride = effectOverride)) }) { Text(stringResource(R.string.action_save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

private fun isExactManualPriceMatch(name: String, set: String, number: String, product: CatalogSearchResult): Boolean =
    normalizeCardName(name) == normalizeCardName(product.name) &&
        normalizedCollectorNumber(number) == normalizedCollectorNumber(product.collectorNumber.orEmpty()) &&
        normalizeBinderMetadata(set) == normalizeBinderMetadata(product.visualSetName ?: product.expansionId)

@Composable private fun EditEntryDialog(card: CollectionCard, onDismiss: () -> Unit, onDelete: () -> Unit, onLink: () -> Unit, onSave: (CollectionEntryUpdate) -> Unit) {
    var quantity by rememberSaveable(card.entryId) { mutableStateOf(card.quantity.toString()) }; var condition by rememberSaveable(card.entryId) { mutableStateOf(card.condition) }; var notes by rememberSaveable(card.entryId) { mutableStateOf(card.notes) }
    var price by rememberSaveable(card.entryId) { mutableStateOf(card.acquisitionPriceEur?.let { "%.2f".format(Locale.GERMANY, it) } ?: "") }; var date by rememberSaveable(card.entryId) { mutableStateOf(card.acquisitionDateEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "") }
    var finish by rememberSaveable(card.entryId) { mutableStateOf(card.finish) }; var variant by rememberSaveable(card.entryId) { mutableStateOf(card.variant) }; var effectOverride by rememberSaveable(card.entryId) { mutableStateOf(card.effectOverride) }
    val valid = (quantity.toIntOrNull() ?: 0) > 0 && condition.isNotBlank() && parseDate(date) != Long.MIN_VALUE && parsePrice(price) != Double.NEGATIVE_INFINITY
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.edit_entry_title)) }, text = { LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("${card.name} · ${card.set}", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold) }
        item { OutlinedButton(onClick = onLink, modifier = Modifier.fillMaxWidth()) { Text(if (card.externalProductId == null) stringResource(R.string.catalog_link_action) else stringResource(R.string.catalog_change_action)) }; Text(if (card.externalProductId == null) stringResource(R.string.catalog_unlinked_hint) else stringResource(R.string.catalog_linked_hint, card.externalProductId), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
        item { OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.grading_action)) }; Text(stringResource(R.string.grading_card_future), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
        item { CardFinishAndEffectEditor(finish, { finish = it }, variant, { variant = it }, effectOverride, { effectOverride = it }) }
        item { FormField(quantity, { quantity = it.filter(Char::isDigit) }, R.string.field_quantity, KeyboardType.Number) }; item { FormField(condition, { condition = it }, R.string.field_condition) }; item { FormField(price, { price = it }, R.string.field_acquisition_price, KeyboardType.Decimal) }; item { FormField(date, { date = it }, R.string.field_acquisition_date) }; item { FormField(notes, { notes = it }, R.string.field_notes, singleLine = false) }
    } }, confirmButton = { TextButton(enabled = valid, onClick = { onSave(CollectionEntryUpdate(quantity.toInt(), condition, notes, parsePrice(price).takeUnless { it == Double.NEGATIVE_INFINITY }, parseDate(date).takeUnless { it == Long.MIN_VALUE }, finish, variant, effectOverride)) }) { Text(stringResource(R.string.action_save)) } }, dismissButton = { Row { TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }; TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } } })
}

private data class EffectOverrideChoice(val value: String?, val label: String)
private val cardEffectChoices = listOf(
    EffectOverrideChoice(null, "Automatisch"), EffectOverrideChoice("NONE", "Kein Effekt"),
    EffectOverrideChoice("HOLO", "Holo"), EffectOverrideChoice("REVERSE_HOLO", "Reverse Holo"),
    EffectOverrideChoice("EX", "EX"), EffectOverrideChoice("FULL_ART", "Full Art"), EffectOverrideChoice("SPECIAL", "Special"),
)

@Composable private fun CardFinishAndEffectEditor(finish: String, onFinishChanged: (String) -> Unit, variant: String, onVariantChanged: (String) -> Unit, effectOverride: String?, onEffectOverrideChanged: (String?) -> Unit) {
    Text(stringResource(R.string.field_finish), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(listOf("Normal", "Holo", "Reverse Holo", "Sondervariante")) { option -> FilterChip(selected = finish.equals(option, true), onClick = { onFinishChanged(option) }, label = { Text(option, fontSize = 11.sp) }) } }
    FormField(variant, onVariantChanged, R.string.field_variant)
    Text("Holo-Effekt in DomiDex", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Automatisch übernimmt den Kartenabgleich. Eine Auswahl ändert nur die Darstellung, nicht die echte Variante, Binder-Slots oder Preise.", color = VaultTextMuted, fontSize = 10.sp)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(cardEffectChoices) { option -> FilterChip(selected = effectOverride == option.value, onClick = { onEffectOverrideChanged(option.value) }, label = { Text(option.label, fontSize = 11.sp) }) } }
}

@Composable private fun CatalogLinkDialog(card: CollectionCard, repository: CollectionRepository, onDismiss: () -> Unit, onLinked: (Long?) -> Unit) {
    var query by rememberSaveable(card.entryId) { mutableStateOf(card.name) }
    var results by remember { mutableStateOf<List<CatalogSearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); loading = false; return@LaunchedEffect }
        loading = true; delay(250); results = repository.searchCatalog(query); loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.catalog_link_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.catalog_link_explanation, card.name, card.number), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.catalog_search_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = CardVaultShapes.field)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!loading && results.isEmpty()) item { Text(stringResource(if (query.length < 2) R.string.catalog_search_prompt else R.string.catalog_search_empty), color = VaultTextMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 18.dp)) }
                items(results, key = { it.productId }) { result -> CatalogResultRow(result) { onLinked(result.productId) } }
            }
            Text(stringResource(R.string.catalog_link_warning), color = VaultTextMuted, fontSize = 11.sp)
        } },
        confirmButton = {},
        dismissButton = { Row { if (card.externalProductId != null) TextButton(onClick = { onLinked(null) }) { Text(stringResource(R.string.catalog_unlink_action), color = MaterialTheme.colorScheme.error) }; TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } } },
    )
}

@Composable private fun FormField(value: String, onValueChange: (String) -> Unit, @StringRes label: Int, keyboardType: KeyboardType = KeyboardType.Text, singleLine: Boolean = true) {
    OutlinedTextField(value, onValueChange, label = { Text(stringResource(label)) }, singleLine = singleLine, keyboardOptions = KeyboardOptions(keyboardType = keyboardType), shape = CardVaultShapes.field, modifier = Modifier.fillMaxWidth())
}

private fun parsePrice(value: String): Double? = if (value.isBlank()) null else value.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 } ?: Double.NEGATIVE_INFINITY
private fun parseDate(value: String): Long? = if (value.isBlank()) null else runCatching { LocalDate.parse(value).toEpochDay() }.getOrNull() ?: Long.MIN_VALUE

/** A locally verified suggestion waiting for the collector's explicit selection. */
private data class ScanRailResult(val candidate: CatalogSearchResult, val score: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ScannerScreen(repository: CollectionRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var analysisRunning by remember { mutableStateOf(false) }
    var analysis by remember { mutableStateOf<CardTextAnalysis?>(null) }
    var analysisFailed by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<CatalogSearchResult>>(emptyList()) }
    var candidateScores by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var candidatesLoading by remember { mutableStateOf(false) }
    var recentResults by remember { mutableStateOf<List<ScanRailResult>>(emptyList()) }
    var selectedCandidate by remember { mutableStateOf<CatalogSearchResult?>(null) }
    var manualEvidence by rememberSaveable { mutableStateOf(false) }
    val contributionPrefs = remember(context) { context.getSharedPreferences("cardvault_contribution", Context.MODE_PRIVATE) }
    var contributionOptIn by rememberSaveable { mutableStateOf(contributionPrefs.getBoolean("opt_in", false)) }
    var contributionEligible by remember { mutableStateOf(false) }
    var contributionMarkedLocal by remember { mutableStateOf(false) }
    var contributionPromptHandled by remember { mutableStateOf(false) }
    val textAnalyzer = remember(context) { CardTextAnalyzer(context) }
    val visualCatalog = remember { PokemonVisualCatalog() }
    DisposableEffect(textAnalyzer) { onDispose { textAnalyzer.close() } }
    LaunchedEffect(analysis) {
        val current = analysis ?: return@LaunchedEffect
        ScannerDebugState.snapshot = ScannerDebugState.snapshot.copy(
            state = AutoScanState.MATCHING,
            numberConfidence = current.numberConfidence,
            reason = "catalog_match",
            raritySymbolColor = current.raritySymbolEvidence.color,
            raritySymbolConfidence = current.raritySymbolEvidence.confidence,
        )
        candidatesLoading = true
        val found = linkedMapOf<Long, CatalogSearchResult>()
        runCatching {
            current.setIdentifier?.let { setCode -> current.collectorNumber?.let { number ->
                visualCatalog.findExact(setCode, number).forEach { visualCard ->
                    val products = repository.searchCatalog(visualCard.name, 40)
                    visualCard.printVariants.forEach { printing ->
                        // The public Cardmarket download does not provide a printed number or
                        // finish per product. Only use it when both are actually present; a
                        // same-name normal card must never be linked to an ex, holo or full-art.
                        val exactProducts = products.filter { product ->
                            product.collectorNumber?.isNotBlank() == true &&
                                normalizeCardName(product.name) == normalizeCardName(visualCard.name) &&
                                normalizedCollectorNumber(product.collectorNumber) == normalizedCollectorNumber(visualCard.number) &&
                                (product.finish.isNullOrBlank() || normalizeBinderMetadata(product.finish) == normalizeBinderMetadata(printing.finish))
                        }
                        if (exactProducts.isNotEmpty()) {
                            exactProducts.forEach { product ->
                                found.putIfAbsent(product.productId, product.copy(name = visualCard.name, visualSetName = visualCard.set.name, collectorNumber = visualCard.number, imageUrl = visualCard.largeImageUrl, finish = printing.finish, variant = printing.variant, rarity = visualCard.rarity, mainSetSize = visualCard.set.printedTotal, setCode = PokemonSetRegistry.metadata(visualCard.set).setCode))
                            }
                        } else {
                            // Set + printed number identifies the card. The finish remains a
                            // deliberate user choice when several legitimate printings share it.
                            val localId = -(("${visualCard.set.id}|${visualCard.id}|${visualCard.number}|${printing.id}".hashCode().toLong() and 0x7fff_ffffL) + 1L)
                            found.putIfAbsent(localId, CatalogSearchResult(
                                productId = localId,
                                name = visualCard.name,
                                expansionId = visualCard.set.id,
                                trendPriceEur = null,
                                currency = null,
                                visualSetName = visualCard.set.name,
                                collectorNumber = visualCard.number,
                                imageUrl = visualCard.largeImageUrl,
                                finish = printing.finish,
                                variant = printing.variant,
                                rarity = visualCard.rarity,
                                hasMarketProduct = false,
                                mainSetSize = visualCard.set.printedTotal,
                                setCode = PokemonSetRegistry.metadata(visualCard.set).setCode,
                            ))
                        }
                    }
                }
            } }
            // Never use arbitrary OCR lines as card names. Attack names, abilities and
            // rules text are common OCR hits; a candidate needs both a printed number
            // and a matching set marker before it may be proposed.
        }
        val ranked = found.values
            .distinctBy { "${normalizeCardName(it.name)}|${it.visualSetName ?: it.expansionId}|${it.collectorNumber.orEmpty()}|${it.finish.orEmpty()}|${it.variant.orEmpty()}" }
            .map { it to CardRecognitionRanker.score(current, it) }
            // A readable German name that contradicts the numbered candidate is
            // intentionally withheld. This is safer than a confident wrong card.
            .filter { (_, score) -> score >= 65 }
            .sortedByDescending { it.second }
            .take(8)
        candidates = ranked.map { it.first }
        candidateScores = ranked.associate { it.first.productId to it.second }
        candidatesLoading = false
        ScannerDebugState.snapshot = ScannerDebugState.snapshot.copy(
            state = when { ranked.isEmpty() -> AutoScanState.NOT_FOUND; ranked.first().second >= 90 -> AutoScanState.CONFIRMED; else -> AutoScanState.AMBIGUOUS },
            candidateCount = ranked.size,
            finalConfidence = ranked.firstOrNull()?.second ?: 0,
            reason = if (ranked.isEmpty()) "no_safe_candidate" else "set_number_name_ranked",
        )
        if (ranked.isNotEmpty()) {
            // Fast scan mode never adds a card silently. It clears the blocking
            // sheet and keeps the best evidence-backed result in the lower rail,
            // ready for a deliberate tap while the camera continues with the next.
            val result = ScanRailResult(ranked.first().first, ranked.first().second)
            recentResults = (listOf(result) + recentResults)
                .distinctBy { "${it.candidate.productId}|${it.candidate.collectorNumber}|${it.candidate.finish}" }
                .take(12)
            analysis = null
        }
        // A successful photo must never leave the collector at a dead end. When OCR
        // lacks sufficient evidence, immediately ask for the two printed markers
        // rather than pretending that a card identity can be guessed.
        if (candidates.isEmpty()) manualEvidence = true
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; denied = !it }
    Box(Modifier.fillMaxSize()) {
        if (granted) {
            LiveCameraScanner(
                modifier = Modifier.fillMaxSize(),
                onCaptureStarted = {
                    analysisRunning = true; analysis = null; analysisFailed = false; candidates = emptyList(); candidateScores = emptyMap()
                    ScannerDebugState.snapshot = ScannerDebugState.snapshot.copy(state = AutoScanState.CAPTURING, reason = "capture_started")
                },
                onCaptureSaved = { file, qualityEligible ->
                    contributionEligible = qualityEligible; contributionMarkedLocal = false; contributionPromptHandled = false
                    ScannerDebugState.snapshot = ScannerDebugState.snapshot.copy(state = AutoScanState.READING, reason = "roi_ocr")
                    textAnalyzer.analyze(file) { result ->
                        analysisRunning = false
                        result.onSuccess { analysis = it }.onFailure {
                            // Still provide the same exact manual continuation for a
                            // readable photo whose OCR happened to fail completely.
                            analysis = CardTextAnalysis(emptyList(), null, null, null, emptyList())
                            manualEvidence = true
                        }
                    }
                },
                onCaptureFailed = { analysisRunning = false; analysisFailed = true },
            )
        } else {
            Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.camera_permission_title), color = Color.White, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.camera_permission_body), color = Color(0xFFB9C9C5))
                Button({ launcher.launch(Manifest.permission.CAMERA) }) { Text(stringResource(if (denied) R.string.camera_permission_retry else R.string.camera_permission_allow)) }
            }
        }
        Surface(modifier = Modifier.align(Alignment.TopStart).padding(12.dp), color = VaultNight.copy(alpha = .78f), shape = CardVaultShapes.card) {
            TextButton(onClick = onBack) { Text("‹ Übersicht", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
        if (analysisRunning || analysisFailed || analysis != null) {
            ModalBottomSheet(
                onDismissRequest = { if (!analysisRunning) { analysis = null; analysisFailed = false } },
                containerColor = VaultNightRaised,
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Mögliche Karten", color = VaultText, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("Abgleich nur über Set-Kennung und gedruckte Nummer. Bitte Karte vor dem Auswählen prüfen.", color = VaultTextMuted, fontSize = 12.sp)
                    if (analysisRunning) AnalysisStatusCard(R.string.scanner_analysis_running)
                    if (analysisFailed) {
                        Text("Keine verwertbaren Textmerkmale erkannt. Du kannst die Karte erneut aufnehmen oder manuell fortfahren.", color = VaultTextMuted, fontSize = 13.sp)
                        OutlinedButton(onClick = { analysisFailed = false }) { Text("Zurück zum Scanner") }
                    }
                    analysis?.let { result ->
                        if (candidatesLoading) AnalysisStatusCard(R.string.scanner_candidates_searching)
                        else if (candidates.isEmpty()) {
                            Text("Für die exakte Zuordnung fehlen noch Set-Kennung oder Kartennummer. Sie können direkt vom unteren Kartenrand ergänzt werden.", color = VaultTextMuted, fontSize = 13.sp)
                            OutlinedButton(onClick = { manualEvidence = true }, modifier = Modifier.fillMaxWidth()) { Text("Set-Kennung und Nummer manuell eingeben") }
                            OutlinedButton(onClick = { analysis = null }) { Text("Weitere Aufnahme machen") }
                        } else {
                            Text("Sortiert nach lokalem OCR-Abgleich", color = MewRoseSoft, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            candidates.take(5).forEachIndexed { index, candidate ->
                                ElevatedCard(onClick = { selectedCandidate = candidate; analysis = null }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = VaultPlumSurface)) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("${index + 1}", color = MewRoseSoft, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                                        Column(Modifier.weight(1f)) { Text(candidate.name, color = VaultText, fontWeight = FontWeight.SemiBold); Text("${candidate.visualSetName ?: candidate.expansionId} · ${candidate.collectorNumber ?: "ohne Nummer"}", color = VaultTextMuted, fontSize = 12.sp); candidate.finish?.let { finish -> Text("$finish · ${candidate.variant ?: "Standard"}", color = MewRoseSoft, fontSize = 11.sp) }; val rarity = CardRarityResolver.fromMetadata(candidate.rarity, candidate.collectorNumber.orEmpty(), candidate.mainSetSize); if (rarity.aboveMainSet) Text("Oberhalb der Hauptsetnummerierung", color = VaultGold, fontSize = 10.sp, fontWeight = FontWeight.SemiBold); if (rarity.type != CardRarityType.UNKNOWN) Text(rarity.type.label, color = MewIce, fontSize = 10.sp); Text("Abgleich: ${CardRecognitionRanker.label(candidateScores[candidate.productId] ?: 0)}", color = MewIce, fontSize = 11.sp); if (!candidate.hasMarketProduct) Text("Katalogtreffer · Preis noch nicht verknüpft", color = VaultTextMuted, fontSize = 10.sp) }
                                        Text("Auswählen", color = MewRoseSoft, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        if (contributionEligible && !contributionPromptHandled && !contributionMarkedLocal) CommunityContributionCard(
                            optedIn = contributionOptIn,
                            onOptInChanged = { enabled -> contributionOptIn = enabled; contributionPrefs.edit().putBoolean("opt_in", enabled).apply() },
                            onPromptHandled = { contributionPromptHandled = true },
                            locallyMarked = contributionMarkedLocal,
                            onLocallyMark = { contributionMarkedLocal = true },
                            onWithdraw = { contributionMarkedLocal = false },
                        )
                    }
                }
            }
        }
        if (recentResults.isNotEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 92.dp),
                color = VaultNightRaised.copy(alpha = .96f),
                shape = CardVaultShapes.card,
                tonalElevation = 8.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Erkannte deutsche Karten", color = VaultText, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("${recentResults.size} bereit", color = MewIce, fontSize = 11.sp)
                        TextButton(onClick = { recentResults = emptyList() }) { Text("Leeren", fontSize = 11.sp) }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recentResults, key = { "${it.candidate.productId}-${it.candidate.collectorNumber}-${it.candidate.finish}" }) { result ->
                            OutlinedCard(
                                onClick = { selectedCandidate = result.candidate },
                                modifier = Modifier.width(152.dp),
                                colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface),
                            ) {
                                Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(result.candidate.name, color = VaultText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${result.candidate.visualSetName ?: result.candidate.expansionId} · ${result.candidate.collectorNumber ?: "Nr. prüfen"}", color = VaultTextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Abgleich: ${CardRecognitionRanker.label(result.score)}", color = MewIce, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    selectedCandidate?.let { candidate ->
        ScanAddDialog(candidate, onDismiss = { selectedCandidate = null }) { input ->
            scope.launch { repository.addManualCard(input) }
            selectedCandidate = null
        }
    }
    if (manualEvidence) {
        ManualScanEvidenceDialog(
            initialSetCode = analysis?.setIdentifier.orEmpty(),
            initialNumber = analysis?.collectorNumber.orEmpty(),
            onDismiss = { manualEvidence = false },
            onConfirm = { setCode, number ->
                analysis?.let { current ->
                    // Re-enters the same strict set + number candidate pipeline.
                    analysis = current.copy(setIdentifier = setCode, collectorNumber = number)
                    candidates = emptyList(); candidateScores = emptyMap()
                }
                manualEvidence = false
            },
        )
    }
}

/** A set choice carries its official total, so manual entry never asks for the redundant suffix. */
private data class ScannerSetChoice(val name: String, val code: String, val printedTotal: Int)

@Composable private fun ManualScanEvidenceDialog(initialSetCode: String, initialNumber: String, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var setCode by rememberSaveable { mutableStateOf(initialSetCode) }
    // Only the printed card position is entered.  The set itself determines its total.
    var number by rememberSaveable { mutableStateOf(initialNumber.substringBefore('/').filter(Char::isDigit).take(3)) }
    var setMenuOpen by remember { mutableStateOf(false) }
    var setSearch by rememberSaveable { mutableStateOf("") }
    var catalogSets by remember { mutableStateOf<List<PokemonSet>>(emptyList()) }
    val visualCatalog = remember { PokemonVisualCatalog() }
    LaunchedEffect(Unit) { runCatching { visualCatalog.sets() }.onSuccess { catalogSets = it } }
    val setChoices = remember(catalogSets) {
        // Local ME04 compatibility stays explicit; every fetched set code comes
        // from the central registry, never from a translated display name.
        (PokemonSetRegistry.legacyOfflineSets().map { set ->
            PokemonSetRegistry.metadata(set).let { metadata ->
                ScannerSetChoice(metadata.germanName, metadata.setCode ?: metadata.setId, metadata.mainSetSize)
            }
        } + catalogSets.mapNotNull { set ->
            PokemonSetRegistry.metadata(set).let { metadata ->
                metadata.setCode?.takeIf { it.isNotBlank() }
                    ?.let { ScannerSetChoice(metadata.germanName, it, metadata.mainSetSize.takeIf { total -> total > 0 } ?: metadata.totalKnownCards) }
            }
        }).distinctBy { it.code }
    }
    val filteredChoices = remember(setChoices, setSearch) {
        setChoices.filter { choice ->
            setSearch.isBlank() || choice.name.contains(setSearch, true) || choice.code.contains(setSearch, true)
        }.take(120)
    }
    val selectedSet = remember(setChoices, setCode) { setChoices.firstOrNull { it.code.equals(setCode.trim(), true) } }
    val totalLabel = selectedSet?.printedTotal?.takeIf { it > 0 }?.toString()?.padStart(3, '0')
    val valid = setCode.trim().length in 2..24 && number.length in 1..3
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set unten auf der Karte") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("1. Set-Kürzel auswählen.  2. Nur die ersten bis zu drei Ziffern unten auf der Karte eingeben, z. B. 122. Die Gesamtzahl des Sets ergänzt DomiDex automatisch.", color = VaultTextMuted, fontSize = 12.sp)
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { setMenuOpen = true }, modifier = Modifier.fillMaxWidth()) { Text(if (setCode.isBlank()) "Set aus Katalog auswählen" else "Set auswählen · $setCode") }
                DropdownMenu(expanded = setMenuOpen, onDismissRequest = { setMenuOpen = false }, modifier = Modifier.fillMaxWidth(.92f).heightIn(max = 330.dp)) {
                    OutlinedTextField(setSearch, { setSearch = it }, label = { Text("Set suchen") }, singleLine = true, modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth())
                    if (filteredChoices.isEmpty()) DropdownMenuItem(text = { Text("Kein Set passend – Set-Kürzel kann unten manuell eingegeben werden") }, onClick = { setMenuOpen = false })
                    else filteredChoices.forEach { choice -> DropdownMenuItem(text = { Column { Text(choice.name, fontWeight = FontWeight.SemiBold); Text("${choice.code} · ${choice.printedTotal} Karten", color = MewIce, fontSize = 11.sp) } }, onClick = { setCode = choice.code; setSearch = ""; setMenuOpen = false }) }
                }
            }
            OutlinedTextField(number, { number = it.filter(Char::isDigit).take(3) }, label = { Text("Kartennummer (erste 1–3 Ziffern), z. B. 122") }, supportingText = { Text(totalLabel?.let { total -> if (number.isBlank()) "Set hat insgesamt $total Karten." else "Ergibt: ${number.padStart(3, '0')} / $total" } ?: "Nach der Set-Auswahl ergänzt DomiDex die Set-Gesamtzahl.") }, singleLine = true)
        } },
        confirmButton = { TextButton(enabled = valid, onClick = { onConfirm(setCode.trim(), number) }) { Text("Karte suchen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable private fun ScanAddDialog(candidate: CatalogSearchResult, onDismiss: () -> Unit, onAdd: (ManualCardInput) -> Unit) {
    var quantity by rememberSaveable(candidate.productId) { mutableStateOf("1") }
    var condition by rememberSaveable(candidate.productId) { mutableStateOf("Nahezu neuwertig") }
    var language by rememberSaveable(candidate.productId) { mutableStateOf(CardLanguage.GERMAN) }
    var finish by rememberSaveable(candidate.productId) { mutableStateOf(candidate.finish ?: "Normal") }
    var variant by rememberSaveable(candidate.productId) { mutableStateOf(candidate.variant ?: "Standard") }
    var effectOverride by rememberSaveable(candidate.productId) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zum Binder hinzufügen") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(candidate.name, fontWeight = FontWeight.Bold)
            Text("${candidate.visualSetName ?: candidate.expansionId} · ${candidate.collectorNumber ?: "ohne Nummer"}", color = VaultTextMuted, fontSize = 13.sp)
            CardRarityResolver.fromMetadata(candidate.rarity, candidate.collectorNumber.orEmpty(), candidate.mainSetSize)
                .takeIf { it.type != CardRarityType.UNKNOWN }
                ?.let { Text("Quelle meldet: ${it.type.label}", color = MewIce, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            Text("Name, Set und Nummer stammen aus dem lokalen Abgleich. Finish, Variante und sichtbarer Holo-Effekt bleiben deine Auswahl.", color = VaultTextMuted, fontSize = 12.sp)
            Text("Deutsch", color = MewIce, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text("Scanner-Zuordnungen werden ausschließlich als deutsche Karte gespeichert.", color = VaultTextMuted, fontSize = 11.sp)
            CardFinishAndEffectEditor(finish, { finish = it }, variant, { variant = it }, effectOverride, { effectOverride = it })
            FormField(quantity, { quantity = it.filter(Char::isDigit) }, R.string.field_quantity, KeyboardType.Number)
            FormField(condition, { condition = it }, R.string.field_condition)
        } },
        confirmButton = { TextButton(enabled = (quantity.toIntOrNull() ?: 0) > 0, onClick = {
            onAdd(ManualCardInput(candidate.name, candidate.visualSetName ?: candidate.expansionId, candidate.collectorNumber.orEmpty(), language, finish, variant, quantity.toInt(), condition, "", null, null, candidate.productId.takeIf { candidate.hasMarketProduct }, candidate.imageUrl, candidate.rarity, effectOverride))
        }) { Text("Zum Binder hinzufügen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun normalizeCardName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .lowercase()
    .replace("ß", "ss")
    .filter(Char::isLetterOrDigit)

@Composable private fun CommunityContributionCard(
    optedIn: Boolean,
    onOptInChanged: (Boolean) -> Unit,
    onPromptHandled: () -> Unit,
    locallyMarked: Boolean,
    onLocallyMark: () -> Unit,
    onWithdraw: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface), shape = CardVaultShapes.card) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.community_contribution_title), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.community_contribution_eligible), color = VaultTextMuted, fontSize = 12.sp)
            Text(stringResource(R.string.community_contribution_optional), color = VaultTextMuted, fontSize = 11.sp)
            if (!locallyMarked) {
                Button(onClick = { if (!optedIn) onOptInChanged(true); onPromptHandled(); confirm = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.community_contribution_review)) }
                TextButton(onClick = onPromptHandled, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.community_contribution_not_now)) }
                Text(stringResource(R.string.community_contribution_not_live), color = VaultTextMuted, fontSize = 10.sp)
            } else {
                Text(stringResource(R.string.community_contribution_local_pending), color = MewIce, fontSize = 11.sp)
                TextButton(onClick = onWithdraw) { Text(stringResource(R.string.community_contribution_withdraw)) }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.community_contribution_confirm_title)) },
        text = { Text(stringResource(R.string.community_contribution_confirm_body)) },
        confirmButton = { TextButton(onClick = { confirm = false; onLocallyMark() }) { Text(stringResource(R.string.community_contribution_confirm_action)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable private fun AnalysisStatusCard(@StringRes message: Int, error: Boolean = false) = OutlinedCard(
    modifier = Modifier.fillMaxWidth(), shape = CardVaultShapes.card,
    colors = CardDefaults.outlinedCardColors(containerColor = VaultNightRaised),
    border = BorderStroke(1.dp, if (error) MaterialTheme.colorScheme.error else VaultOutline),
) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    if (!error) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    Text(stringResource(message), color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
} }

@Composable private fun ScannerAnalysisCard(result: CardTextAnalysis, candidates: List<CatalogSearchResult>, candidatesLoading: Boolean, onSelect: (CatalogSearchResult) -> Unit) = OutlinedCard(
    modifier = Modifier.fillMaxWidth(), shape = CardVaultShapes.card,
    colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface), border = BorderStroke(1.dp, MewPlum.copy(alpha = .55f)),
) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(stringResource(R.string.scanner_analysis_title), fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.scanner_analysis_local), color = VaultPositive, fontSize = 12.sp)
    result.likelyScript?.let { script -> Text(stringResource(R.string.scanner_script_suggestion, stringResource(when (script) { CardScript.LATIN -> R.string.scanner_script_latin; CardScript.JAPANESE -> R.string.scanner_script_japanese; CardScript.CHINESE -> R.string.scanner_script_chinese })), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
    result.collectorNumber?.let { Text(stringResource(R.string.scanner_number_found, it), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
    result.setIdentifier?.let { Text("Set-Kennung erkannt: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
    if (candidatesLoading) Text(stringResource(R.string.scanner_candidates_searching), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    else if (candidates.isEmpty()) Text(stringResource(R.string.scanner_no_candidates), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    else {
        Text(stringResource(R.string.scanner_candidates_title), color = MewRoseSoft, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        candidates.take(5).forEach { candidate -> CatalogResultRow(candidate) { onSelect(candidate) } }
        Text(stringResource(R.string.scanner_candidates_boundary), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
} }

private data class GradeCategory(@param:StringRes val name: Int, @param:StringRes val prompt: Int)
@Composable private fun GradingScreen() {
    val context = LocalContext.current
    val categories = remember { listOf(GradeCategory(R.string.grading_centering, R.string.grading_centering_prompt), GradeCategory(R.string.grading_corners, R.string.grading_corners_prompt), GradeCategory(R.string.grading_edges, R.string.grading_edges_prompt), GradeCategory(R.string.grading_surface, R.string.grading_surface_prompt)) }
    var completed by rememberSaveable { mutableStateOf(setOf<Int>()) }
    val latestCapture = LatestCardCapture.path
    // Front and back remain deliberately separate: centering is assessed per side,
    // never inferred from the other image.
    var editingFront by rememberSaveable { mutableStateOf(true) }
    var frontPhotoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var backPhotoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var frontBoundary by remember { mutableStateOf<CenteringFrames?>(null) }
    var backBoundary by remember { mutableStateOf<CenteringFrames?>(null) }
    var editBoundary by remember { mutableStateOf(false) }
    val gradingPhotoUri = if (editingFront) frontPhotoUri else backPhotoUri
    val confirmedBoundary = if (editingFront) frontBoundary else backBoundary
    fun setPhoto(uri: Uri) {
        if (editingFront) { frontPhotoUri = uri; frontBoundary = null }
        else { backPhotoUri = uri; backBoundary = null }
        editBoundary = true
    }
    fun setBoundary(boundary: CenteringFrames) {
        if (editingFront) frontBoundary = boundary else backBoundary = boundary
    }
    var centeringCameraOpen by rememberSaveable { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) setPhoto(uri) }
    val centeringCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) centeringCameraOpen = true }
    LazyColumn(contentPadding = PaddingValues(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenHeader("Centering", "Centering", "Vorder- oder Rückseite präzise mit Außen- und Innenrahmen ausrichten.") }
        item { ElevatedCard(shape = CardVaultShapes.card, colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Text(stringResource(R.string.grading_disclaimer), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onPrimaryContainer) } }
        item { OutlinedCard(shape = CardVaultShapes.card, colors = CardDefaults.outlinedCardColors(containerColor = VaultNightRaised), border = BorderStroke(1.dp, MewPlum.copy(alpha = .55f))) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Kartenrahmen", fontWeight = FontWeight.Bold)
            Text("Vorder- und Rückseite werden getrennt geprüft. Das Gitternetz hilft nur beim Ausrichten; reflektierende Hüllen, Schatten und ähnliche Rechtecke können die Automatik täuschen.", color = VaultTextMuted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = editingFront, onClick = { editingFront = true }, label = { Text("Vorderseite") })
                FilterChip(selected = !editingFront, onClick = { editingFront = false }, label = { Text("Rückseite") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { photoPicker.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("Galerie") }
                OutlinedButton(onClick = { if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) centeringCameraOpen = true else centeringCameraPermission.launch(Manifest.permission.CAMERA) }, modifier = Modifier.weight(1f)) { Text("Foto aufnehmen") }
                if (gradingPhotoUri == null && latestCapture != null) OutlinedButton(onClick = { setPhoto(Uri.fromFile(java.io.File(latestCapture))) }) { Text("Letzte Aufnahme") }
            }
            gradingPhotoUri?.let { uri -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AsyncImage(model = uri, contentDescription = "Foto für die Zustandsprüfung", contentScale = ContentScale.Crop, modifier = Modifier.size(60.dp, 84.dp).clip(CardVaultShapes.card))
                Column(Modifier.weight(1f)) { Text(if (confirmedBoundary == null) "Rahmen noch nicht bestätigt" else "Rahmen manuell bestätigt", color = if (confirmedBoundary == null) Color(0xFFFFC857) else VaultPositive, fontSize = 12.sp); Text("Automatik immer vor der Prüfung kontrollieren.", color = VaultTextMuted, fontSize = 11.sp) }
                TextButton(onClick = { editBoundary = true }) { Text("Ausrichten") }
            } }
        } } }
        latestCapture?.let { path -> item {
            OutlinedCard(shape = CardVaultShapes.card, colors = CardDefaults.outlinedCardColors(containerColor = VaultNightRaised)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AsyncImage(model = java.io.File(path), contentDescription = stringResource(R.string.grading_latest_capture_description), modifier = Modifier.size(76.dp, 106.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(stringResource(R.string.grading_latest_capture), fontWeight = FontWeight.Bold); Text(stringResource(R.string.grading_capture_not_analyzed), color = VaultTextMuted, fontSize = 12.sp) }
                }
            }
        } }
        item { Text(stringResource(R.string.photo_checklist), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        items(categories) { category -> val checked = category.name in completed; OutlinedCard(Modifier.fillMaxWidth().clickable { completed = if (checked) completed - category.name else completed + category.name }, shape = CardVaultShapes.card, colors = CardDefaults.outlinedCardColors(containerColor = if (checked) MewPlumDeep.copy(alpha = .52f) else VaultNightRaised), border = BorderStroke(1.dp, if (checked) MewPlum.copy(alpha = .70f) else VaultOutline)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, { completed = if (it) completed + category.name else completed - category.name }, colors = CheckboxDefaults.colors(checkedColor = MewRose, checkmarkColor = MaterialTheme.colorScheme.onPrimary)); Column(Modifier.weight(1f)) { Text(stringResource(category.name), fontWeight = FontWeight.Bold); Text(stringResource(category.prompt), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }; Text(stringResource(if (checked) R.string.grading_ready else R.string.grading_add_photos), color = if (checked) VaultPositive else MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold) } } }
        item { LinearProgressIndicator(progress = { completed.size / categories.size.toFloat() }, modifier = Modifier.fillMaxWidth()) }
        item { ElevatedCard(shape = CardVaultShapes.card, colors = CardDefaults.elevatedCardColors(containerColor = if (completed.size == categories.size) VaultPositive.copy(alpha = .18f) else VaultPlumSurface)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (completed.size == categories.size) "Foto-Check vollständig" else "Foto-Check in Arbeit", fontWeight = FontWeight.Bold)
            Text(if (completed.size == categories.size) "Alle sichtbaren Bereiche wurden dokumentiert. Prüfe den bestätigten Kartenrahmen und bewahre die Fotos für die Einreichung auf." else "Dokumentiere Zentrierung, Ecken, Kanten und Oberfläche. Fehlende oder unscharfe Bildbereiche bleiben offen.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text("Dies ist keine professionelle Note und keine Garantie für ein Grading-Ergebnis.", color = VaultTextMuted, fontSize = 12.sp)
        } } }
        item { OutlinedCard(shape = CardVaultShapes.card, colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.submission_check_title), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.submission_check_body), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text(stringResource(R.string.submission_check_outcome), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        } } }
        item { Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.analysis_later)) } }
        item { Text(stringResource(R.string.grading_boundary), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
    }
    if (editBoundary && gradingPhotoUri != null) CardBoundaryEditor(gradingPhotoUri!!, confirmedBoundary ?: CenteringFrames(CardBoundary.Default, CardBoundary.InnerDefault), onDismiss = { editBoundary = false }, onApply = { setBoundary(it); editBoundary = false })
    if (centeringCameraOpen) CenteringCameraCapture(onDismiss = { centeringCameraOpen = false }, onCaptured = { uri -> centeringCameraOpen = false; setPhoto(uri) })
}

@Composable private fun DataNote() = Text(stringResource(R.string.demo_data_note), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
private fun euro(value: Double) = NumberFormat.getCurrencyInstance(Locale.GERMANY).format(value)
