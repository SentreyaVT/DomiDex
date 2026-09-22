package com.dominik.cardvault.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.dominik.cardvault.BuildConfig
import com.dominik.cardvault.R
import com.dominik.cardvault.auth.FirebaseGoogleAuthGateway
import com.dominik.cardvault.auth.GoogleAccountState
import com.dominik.cardvault.auth.GoogleLoginResult
import com.dominik.cardvault.auth.GoogleLogoutResult
import com.dominik.cardvault.auth.canonicalCollectionOwnerUid
import com.dominik.cardvault.auth.currentFirebaseEntitlementAccount
import com.dominik.cardvault.model.*
import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.data.catalog.PokemonPrintVariant
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import com.dominik.cardvault.data.catalog.PokemonSetRegistry
import com.dominik.cardvault.data.catalog.classic30thLocalIdForPrintedCollector
import com.dominik.cardvault.data.catalog.displayCardCount
import com.dominik.cardvault.data.WachsendesChaosMasterSet
import com.dominik.cardvault.data.content.ContentUpdateState
import com.dominik.cardvault.data.feedback.ScanFeedback
import com.dominik.cardvault.data.feedback.ScanFeedbackAnnotation
import com.dominik.cardvault.data.feedback.ScanFeedbackAnnotationType
import com.dominik.cardvault.data.feedback.ScanFeedbackStore
import com.dominik.cardvault.data.knowledge.FirebaseScanKnowledge
import com.dominik.cardvault.data.storage.CatalogContributionReceipt
import com.dominik.cardvault.data.storage.FirebaseDomiDexStorageGateway
import com.dominik.cardvault.data.storage.StorageFailureReason
import com.dominik.cardvault.data.storage.StorageUploadResult
import com.dominik.cardvault.discord.DiscordDevUpdatePayload
import com.dominik.cardvault.discord.DiscordDevUpdatePublishResult
import com.dominik.cardvault.discord.UnconfiguredDiscordDevUpdatePublisher
import com.dominik.cardvault.ui.theme.*
import coil3.compose.AsyncImage
import java.text.NumberFormat
import java.text.Normalizer
import java.util.Locale
import java.time.LocalDate
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import com.google.firebase.auth.FirebaseAuth

private enum class Destination(@param:StringRes val label: Int, val icon: DomiIconKind, val showInNavigation: Boolean = true) {
    DASHBOARD(R.string.nav_portfolio, DomiIconKind.HOME),
    SCANNER(R.string.nav_scanner, DomiIconKind.SCAN),
    BINDER(R.string.nav_binder, DomiIconKind.BINDER),
    COLLECTION(R.string.nav_collection, DomiIconKind.COLLECTION),
    SETTINGS(R.string.nav_settings, DomiIconKind.SETTINGS),
    GRADING(R.string.nav_grading, DomiIconKind.CARD, showInNavigation = false),
}

private data class TutorialStep(val title: String, val body: String, val destination: Destination)
private data class VisualCardMarketPrice(val trendPriceEur: Double?, val exact: Boolean, val hasCardmarketProduct: Boolean)

private val relaxedTutorialSteps = listOf(
    TutorialStep("Dein Binder", "Hier siehst du deine Sets wie in einem echten Album. Dunkle Karten sind noch nicht in deiner Sammlung – antippen zeigt trotzdem ihre Details.", Destination.BINDER),
    TutorialStep("Karte scannen", "Karte ruhig in den Rahmen legen und Aufnahme drücken. DomiDex liest unten Set-Kürzel und Nummer; bei Unsicherheit wählst du sie einfach aus.", Destination.SCANNER),
    TutorialStep("Zustand ermitteln – bald im Web", "Die Zustandsermittlung kommt mit einem sehr baldigen Update in die DomiDex-Web-App. In der Android-App ist diese Funktion nicht verfügbar.", Destination.DASHBOARD),
    TutorialStep("Alles bei dir", "Preise, Binder und Konto findest du in Settings. Deine Sammlung bleibt lokal nutzbar, auch ohne Anmeldung.", Destination.SETTINGS),
)

@Composable fun CardVaultApp(repository: CollectionRepository, refreshCatalog: () -> Unit = {}, contentUpdateState: StateFlow<ContentUpdateState>? = null, localDataReady: Boolean = true) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("cardvault_ui", Context.MODE_PRIVATE) }
    var focusChoice by rememberSaveable { mutableStateOf(preferences.getString("dashboard_focus", "auto") ?: "auto") }
    var destination by rememberSaveable { mutableStateOf(Destination.DASHBOARD) }
    // Only actual local initialization gates entry. Catalogue networking stays in the background.
    var showArrival by rememberSaveable { mutableStateOf(true) }
    var showTutorialPrompt by rememberSaveable { mutableStateOf(false) }
    var tutorialStep by rememberSaveable { mutableIntStateOf(-1) }
    val portfolio by repository.portfolio.collectAsState(initial = PortfolioSummary.Empty)
    val binders by repository.binders.collectAsState(initial = emptyList())
    val syncStatus by repository.catalogSyncStatus.collectAsState(initial = CatalogSyncStatus("IDLE", null, null, 0, 0, null))
    val contentUpdates by (contentUpdateState ?: remember { kotlinx.coroutines.flow.MutableStateFlow(ContentUpdateState()) }).collectAsState()
    // Top-level destinations use lightweight local state instead of a NavHost. Keep Android's
    // system back button predictable: nested dialogs and the open Binder register later handlers,
    // while a top-level screen returns to the dashboard instead of finishing the activity.
    BackHandler(
        enabled = !showArrival && !showTutorialPrompt && tutorialStep !in relaxedTutorialSteps.indices && destination != Destination.DASHBOARD,
    ) { destination = Destination.DASHBOARD }
    Scaffold(containerColor = Color.Transparent, bottomBar = {
        // The launch moment is deliberately the only visible surface. Navigation
        // appears only after local warm-up has completed.
        if (!showArrival && destination != Destination.SCANNER) DomiDexBottomNavigation(destination) { destination = it }
    }) { padding -> DomiDexBackdrop(Modifier.padding(padding)) {
        DomiDexAdaptiveContent {
            AnimatedContent(
                targetState = destination,
                transitionSpec = { (fadeIn(tween(DomiMotion.Fast)) + slideInHorizontally(tween(DomiMotion.Fast)) { it / 12 }) togetherWith (fadeOut(tween(DomiMotion.Fast)) + slideOutHorizontally(tween(DomiMotion.Fast)) { -it / 16 }) },
                label = "domidex-destination",
            ) { shownDestination ->
                when (shownDestination) {
                    Destination.DASHBOARD -> DashboardScreen(portfolio, binders, contentUpdates, focusChoice, { choice -> focusChoice = choice; preferences.edit().putString("dashboard_focus", choice).apply() }, openBinder = { destination = Destination.BINDER }, openScanner = { destination = Destination.SCANNER })
                    Destination.SCANNER -> ScannerScreen(repository) { destination = Destination.DASHBOARD }
                    Destination.BINDER -> BinderScreen(portfolio.cards, repository)
                    Destination.COLLECTION -> CollectionScreen(portfolio.cards, repository)
                    // Retain the destination for old saved navigation, but never reopen the Android tool.
                    Destination.GRADING -> GradingWebNotice(Modifier.padding(CardVaultSpacing.screen))
                    Destination.SETTINGS -> SettingsScreen(repository, portfolio, refreshCatalog)
                }
            }
        }
        AnimatedVisibility(
            visible = showArrival,
            enter = fadeIn(animationSpec = tween(durationMillis = 180)),
            exit = fadeOut(animationSpec = tween(durationMillis = 320)),
        ) {
            ArrivalMoment(ready = localDataReady, onContinue = {
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
    DomiDexBottomBarFrame {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = CardVaultSpacing.navHorizontal, vertical = CardVaultSpacing.navVertical),
            color = VaultGlassHigh,
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, VaultOutline.copy(alpha = .72f)),
            shadowElevation = 8.dp,
        ) {
            Row(Modifier.fillMaxWidth().height(CardVaultSpacing.bottomNavHeight).padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Destination.entries.filter { it.showInNavigation }.forEach { item ->
                    val active = selected == item
                    val iconLift by animateFloatAsState(if (active) -2f else 0f, tween(DomiMotion.Fast), label = "nav-lift-${item.name}")
                    val iconScale by animateFloatAsState(if (active) if (item == Destination.BINDER) 1.1f else 1.04f else 1f, tween(DomiMotion.Fast), label = "nav-scale-${item.name}")
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .semantics(mergeDescendants = true) {
                                role = Role.Tab
                                this.selected = active
                            }
                            .clickable(interactionSource = interaction, indication = null) { onSelected(item) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.graphicsLayer { translationY = iconLift }) {
                            val iconBox = if (item == Destination.BINDER) 38.dp else 30.dp
                            Box(
                                Modifier.size(iconBox).graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                                    .background(
                                        if (active) MewPlumDeep.copy(alpha = .82f) else Color.Transparent,
                                        RoundedCornerShape(if (item == Destination.BINDER) 13.dp else 10.dp),
                                    )
                                    .then(
                                        if (active) Modifier.border(1.dp, MewRose.copy(alpha = .48f), RoundedCornerShape(if (item == Destination.BINDER) 13.dp else 10.dp))
                                        else Modifier,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                DomiLineIcon(item.icon, if (active) MewRose else VaultTextMuted, Modifier.size(if (item == Destination.BINDER) 21.dp else 18.dp))
                            }
                            Text(stringResource(item.label), color = if (active) VaultText else VaultTextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    var showAdminTools by rememberSaveable { mutableStateOf(false) }
    val firebaseAuth = remember { FirebaseAuth.getInstance() }
    var accountActive by remember { mutableStateOf(canonicalCollectionOwnerUid(firebaseAuth.currentUser) != null) }
    var adminAllowed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(firebaseAuth) {
        var roleCheck: Job? = null
        val listener = FirebaseAuth.IdTokenListener { auth ->
            accountActive = canonicalCollectionOwnerUid(auth.currentUser) != null
            adminAllowed = false
            showAdminTools = false
            roleCheck?.cancel()
            if (BuildConfig.DEBUG && accountActive) roleCheck = scope.launch {
                try {
                    adminAllowed = auth.currentFirebaseEntitlementAccount()?.entitlements?.admin == true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { adminAllowed = false }
            }
        }
        firebaseAuth.addIdTokenListener(listener)
        onDispose { roleCheck?.cancel(); firebaseAuth.removeIdTokenListener(listener) }
    }
    data class SettingEntry(val title: String, val subtitle: String, val icon: DomiIconKind, val value: String? = null)
    val groups = listOf(
        "Konto & Datenschutz" to listOf(
            SettingEntry("Konto", if (accountActive) "Google-Anmeldung auf diesem Gerät aktiv" else "Optional · Sammlung bleibt lokal nutzbar", DomiIconKind.PROFILE, if (accountActive) "Aktiv" else null),
            SettingEntry("Datenschutz", "Datenschutzerklärung und deine Daten", DomiIconKind.SHIELD),
            SettingEntry("Scanner-Verbesserung", "Einwilligung ansehen und ändern", DomiIconKind.SCAN),
        ),
        "DomiDex" to listOf(
            SettingEntry("App", "Sprache und lokale Inhalte", DomiIconKind.HOME, "Deutsch"),
            SettingEntry("Update", "App-Updates über Google Play", DomiIconKind.SYNC),
            SettingEntry("Scanner", "Kamera, Fotos und Erkennung", DomiIconKind.SCAN),
        ),
        "Sammlung" to listOf(
            SettingEntry("Binder & Sync", if (accountActive) "Kontogebundener Abgleich · Status unter Konto" else "Lokale Sammlung und Katalogstatus", DomiIconKind.BINDER, if (accountActive) "Konto" else "Lokal"),
            SettingEntry("Preise", "Cardmarket-Quelle und Aktualität", DomiIconKind.MARKET, "Cardmarket"),
            SettingEntry("Sammlung & Backup", "Lokale Sicherung als DomiDex-Datei", DomiIconKind.COLLECTION),
        ),
        "Support & App-Informationen" to listOf(
            SettingEntry("Support kontaktieren", "Fragen und Datenschutzkontakt", DomiIconKind.INFO),
            SettingEntry("Hilfe", "Schnellstart, Korrekturen und Support", DomiIconKind.INFO),
            SettingEntry("Über DomiDex", "Pre-Beta · privat · Non-Profit · Version & Build", DomiIconKind.CARD),
        ),
        "Konto- & Datenverwaltung" to listOf(
            SettingEntry("Konto löschen", "Löschanfrage · nichts wird sofort gelöscht", DomiIconKind.SHIELD),
        ),
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(CardVaultSpacing.section)) {
        item { DomiScreenTitle("Einstellungen", eyebrow = "DomiDex", subtitle = "Ruhig, verständlich und vollständig bei dir.") }
        groups.forEach { (groupTitle, entries) ->
            item(key = "header-$groupTitle") { DomiSectionTitle(groupTitle) }
            item(key = "group-$groupTitle") {
                DomiGlassCard(Modifier.fillMaxWidth()) {
                    Column {
                        entries.forEachIndexed { index, entry ->
                            DomiSettingRow(entry.icon, entry.title, entry.subtitle, entry.value) { detail = entry.title }
                            if (index != entries.lastIndex) HorizontalDivider(color = VaultOutlineSoft, modifier = Modifier.padding(start = 60.dp))
                        }
                    }
                }
            }
        }
        if (BuildConfig.DEBUG && adminAllowed) {
            item {
                DomiGlassCard(Modifier.fillMaxWidth()) {
                    DomiSettingRow(DomiIconKind.SHIELD, "Admin / Entwickler", "Intern · bestätigte Admin-Berechtigung") { showAdminTools = true }
                }
            }
        }
    }
    detail?.let { section ->
        if (section == "Konto") AccountSettingsDialog(repository, onDismiss = { detail = null })
        else if (section == "Konto löschen") AccountDeletionRequestDialog(onDismiss = { detail = null })
        else if (section == "Sammlung & Backup") CollectionBackupDialog(repository, portfolio.cards, onDismiss = { detail = null })
        else SettingsDetailDialog(section, sync, context, onRefresh = refreshCatalog, onDismiss = { detail = null })
    }
    if (BuildConfig.DEBUG && adminAllowed && showAdminTools) {
        AdminSupportDialog(context, portfolio, sync, showDiscordDevUpdate = true, onDismiss = { showAdminTools = false })
    }
}

@Composable private fun AccountSettingsDialog(repository: CollectionRepository, onDismiss: () -> Unit) {
    val cloudState by repository.cloudState.collectAsState(initial = com.dominik.cardvault.data.sync.CollectionCloudState(com.dominik.cardvault.data.sync.CollectionCloudStatus.CHECKING))
    val guestEntries by repository.guestEntryCount.collectAsState(initial = 0)
    val syncConflicts by repository.syncConflictCount.collectAsState(initial = 0)
    val syncErrors by repository.syncErrorCount.collectAsState(initial = 0)
    val auth = remember { FirebaseAuth.getInstance() }
    var signedIn by remember { mutableStateOf(canonicalCollectionOwnerUid(auth.currentUser) != null) }
    var confirmClaim by rememberSaveable { mutableStateOf(false) }
    var confirmKeepLocal by rememberSaveable { mutableStateOf(false) }
    var confirmKeepCloud by rememberSaveable { mutableStateOf(false) }
    var claiming by remember { mutableStateOf(false) }
    var syncWorking by remember { mutableStateOf(false) }
    var claimMessage by remember { mutableStateOf<String?>(null) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var accountSessionGeneration by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    fun isCurrentAccountAction(uid: String, generation: Long): Boolean =
        generation == accountSessionGeneration && canonicalCollectionOwnerUid(auth.currentUser) == uid
    DisposableEffect(auth) {
        var previousUid = canonicalCollectionOwnerUid(auth.currentUser)
        val listener = FirebaseAuth.AuthStateListener {
            val uid = canonicalCollectionOwnerUid(it.currentUser)
            // Also invalidate callbacks if the same account starts a new authentication session.
            accountSessionGeneration++
            signedIn = uid != null
            if (uid != previousUid) {
                confirmClaim = false
                confirmKeepLocal = false
                confirmKeepCloud = false
                claimMessage = null
                syncMessage = null
            }
            previousUid = uid
        }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Konto") },
        text = { LazyColumn(modifier = Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Mit deinem Google-Konto erhält deine Sammlung einen eigenen, geschützten Synchronisationsbereich.", color = VaultTextMuted, fontSize = 12.sp) }
            item { GoogleAccountCard() }
            item { CollectionCloudStatusContent(cloudState) }
            item { SettingsInfoBlock("Firebase / Sync-Status", when {
                !signedIn -> "Nicht angemeldet · Gastbestand bleibt lokal. Keine Kontosynchronisierung."
                syncErrors > 0 -> "$syncErrors lokale Syncfehler gemeldet. Änderungen bleiben auf diesem Gerät."
                syncConflicts > 0 -> "$syncConflicts Konflikte warten auf deine Entscheidung."
                else -> "Firebase-Anmeldung aktiv · keine lokalen Syncfehler gemeldet. Das bestätigt noch keine vollständig abgeschlossene Cloud-Übertragung."
            }) }
            item { SettingsInfoBlock("Sammlung & Offline-Modus", "Angemeldete Kartenänderungen werden zuerst sicher lokal gespeichert und bei verfügbarer Verbindung kontogebunden mit Firebase abgeglichen.") }
            if (signedIn && syncConflicts > 0) {
                item { SettingsInfoBlock("Synchronisationskonflikt", "$syncConflicts lokale Änderungen treffen auf eine neuere Cloud-Fassung. DomiDex überschreibt keine Seite automatisch.") }
                item { OutlinedButton(
                    onClick = { confirmKeepLocal = true },
                    enabled = signedIn && !syncWorking,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Meine lokalen Änderungen behalten") } }
                item { OutlinedButton(
                    onClick = { confirmKeepCloud = true },
                    enabled = signedIn && !syncWorking,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Aktuelle Cloud-Fassung behalten") } }
            }
            if (signedIn && syncErrors > 0) {
                item { SettingsInfoBlock("Synchronisierung angehalten", "$syncErrors Änderungen konnten wegen einer Konto-, Sicherheitsregel- oder Datenkonfiguration nicht übertragen werden. Sie bleiben lokal gespeichert.") }
                item { OutlinedButton(
                    onClick = {
                        val actionUid = canonicalCollectionOwnerUid(auth.currentUser) ?: return@OutlinedButton
                        val actionGeneration = accountSessionGeneration
                        syncWorking = true
                        scope.launch {
                            runCatching { repository.retrySyncErrors() }
                                .onSuccess { count ->
                                    if (isCurrentAccountAction(actionUid, actionGeneration)) {
                                        syncMessage = if (count > 0) "$count Änderungen werden erneut gesendet." else "Keine übertragbare Änderung konnte erneut gestartet werden."
                                    }
                                }
                                .onFailure {
                                    if (isCurrentAccountAction(actionUid, actionGeneration)) syncMessage = "Die Synchronisierung konnte nicht erneut gestartet werden."
                                }
                            syncWorking = false
                        }
                    },
                    enabled = signedIn && !syncWorking,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (syncWorking) "Erneuter Versuch läuft …" else "Übertragung erneut versuchen") } }
            }
            if (guestEntries > 0) {
                item { SettingsInfoBlock("Lokaler Bestand gefunden", "$guestEntries lokale Einträge bleiben getrennt, bis du ihre Übernahme ausdrücklich bestätigst.") }
                item { Button(
                    onClick = { confirmClaim = true },
                    enabled = signedIn && cloudState.accessConfirmed && !claiming,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (signedIn) "Lokalen Bestand übernehmen" else "Zum Übernehmen zuerst anmelden") } }
            } else {
                item { SettingsInfoBlock("Bestehender lokaler Bestand", "Ein älterer lokaler Bestand wird niemals automatisch einem Konto zugeordnet.") }
            }
            claimMessage?.let { message -> item { Text(message, color = MewIce, fontSize = 11.sp) } }
            syncMessage?.let { message -> item { Text(message, color = MewIce, fontSize = 11.sp) } }
            item { SettingsInfoBlock("Was privat bleibt", "Scanner-Aufnahmen, lokale Diagnosebilder und Anmeldedaten werden nicht als Sammlungsinhalt übertragen. Andere Konten können deinen Bestand nicht lesen.") }
            item { SettingsInfoBlock("Kein versteckter Sonderzugriff", "Eine Google-Anmeldung verleiht keine Admin-, Support- oder Pro-Rechte. Solche Rollen müssten serverseitig separat bestätigt werden.") }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
    )
    if (confirmClaim) AlertDialog(
        onDismissRequest = { if (!claiming) confirmClaim = false },
        title = { Text("Lokalen Bestand übernehmen?") },
        text = { Text("Die $guestEntries lokalen Einträge werden diesem Google-Konto zugeordnet und anschließend sicher synchronisiert. Dieser Schritt erfolgt nicht automatisch.") },
        confirmButton = {
            TextButton(
                enabled = !claiming && signedIn,
                onClick = {
                    val actionUid = canonicalCollectionOwnerUid(auth.currentUser) ?: return@TextButton
                    val actionGeneration = accountSessionGeneration
                    claiming = true
                    scope.launch {
                        runCatching { repository.claimGuestCollectionForCurrentUser() }
                            .onSuccess { count ->
                                if (isCurrentAccountAction(actionUid, actionGeneration)) claimMessage = "$count Einträge wurden übernommen."
                            }
                            .onFailure {
                                if (isCurrentAccountAction(actionUid, actionGeneration)) claimMessage = "Die Übernahme konnte nicht abgeschlossen werden. Bitte erneut versuchen."
                            }
                        claiming = false
                        confirmClaim = false
                    }
                },
            ) { Text(if (claiming) "Übernahme läuft …" else "Übernehmen") }
        },
        dismissButton = { TextButton(onClick = { confirmClaim = false }, enabled = !claiming) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (confirmKeepLocal) AlertDialog(
        onDismissRequest = { if (!syncWorking) confirmKeepLocal = false },
        title = { Text("Lokale Fassung behalten?") },
        text = { Text("Damit ersetzt du bei bearbeiteten Karten bewusst die neuere Cloud-Fassung. Löschkonflikte bleiben aus Sicherheitsgründen unangetastet. Es erfolgt keine automatische Zusammenführung.") },
        confirmButton = {
            TextButton(
                enabled = !syncWorking && signedIn,
                onClick = {
                    val actionUid = canonicalCollectionOwnerUid(auth.currentUser) ?: return@TextButton
                    val actionGeneration = accountSessionGeneration
                    syncWorking = true
                    scope.launch {
                        runCatching { repository.retryConflictsKeepingLocal() }
                            .onSuccess { count ->
                                if (isCurrentAccountAction(actionUid, actionGeneration)) {
                                    syncMessage = if (count > 0) "$count lokale Änderungen werden bewusst erneut gesendet." else "Kein bearbeitbarer Konflikt wurde verändert."
                                }
                            }
                            .onFailure {
                                if (isCurrentAccountAction(actionUid, actionGeneration)) syncMessage = "Der Konflikt konnte nicht erneut gesendet werden."
                            }
                        syncWorking = false
                        confirmKeepLocal = false
                    }
                },
            ) { Text(if (syncWorking) "Wird vorbereitet …" else "Lokale Fassung senden") }
        },
        dismissButton = { TextButton(onClick = { confirmKeepLocal = false }, enabled = !syncWorking) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (confirmKeepCloud) AlertDialog(
        onDismissRequest = { if (!syncWorking) confirmKeepCloud = false },
        title = { Text("Cloud-Fassung behalten?") },
        text = { Text("DomiDex lädt für jeden Konflikt die aktuelle Cloud-Fassung neu. Die dazugehörige lokale Änderung wird danach ersetzt. Nicht erreichbare oder inzwischen erneut geänderte Einträge bleiben sicher im Konfliktstatus.") },
        confirmButton = {
            TextButton(
                enabled = !syncWorking && signedIn,
                onClick = {
                    val actionUid = canonicalCollectionOwnerUid(auth.currentUser) ?: return@TextButton
                    val actionGeneration = accountSessionGeneration
                    syncWorking = true
                    scope.launch {
                        runCatching { repository.keepCloudVersionsForConflicts() }
                            .onSuccess { count ->
                                if (isCurrentAccountAction(actionUid, actionGeneration)) {
                                    syncMessage = if (count > 0) "$count aktuelle Cloud-Fassungen wurden übernommen." else "Kein Konflikt konnte aus der Cloud übernommen werden."
                                }
                            }
                            .onFailure {
                                if (isCurrentAccountAction(actionUid, actionGeneration)) syncMessage = "Die Cloud-Fassung konnte nicht sicher geladen werden."
                            }
                        syncWorking = false
                        confirmKeepCloud = false
                    }
                },
            ) { Text(if (syncWorking) "Cloud wird geprüft …" else "Cloud-Fassung übernehmen") }
        },
        dismissButton = { TextButton(onClick = { confirmKeepCloud = false }, enabled = !syncWorking) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable private fun SettingsDetailDialog(section: String, sync: CatalogSyncStatus, context: Context, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val online = remember { isOnline(context) }
    val binderPreferences = remember(context) { context.getSharedPreferences("cardvault_binders", Context.MODE_PRIVATE) }
    var binderEffectsAnimated by rememberSaveable {
        mutableStateOf(binderPreferences.getBoolean(PRIVATE_BINDER_EFFECTS_PREFERENCE, true))
    }
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
            "Binder & Sync" -> {
                SettingsInfoBlock("Dein Stand", "${if (online) "Online" else "Offline"}. Binder bleiben lokal nutzbar; angemeldete Änderungen werden kontogebunden abgeglichen, sobald eine Verbindung besteht. Katalogstatus: ${sync.state}.")
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Animierte Binder-Effekte", fontWeight = FontWeight.SemiBold)
                        Text("Bei Aus bleiben Cover und Holo statisch sichtbar.", color = VaultTextMuted, fontSize = 10.sp)
                    }
                    Switch(
                        checked = binderEffectsAnimated,
                        onCheckedChange = { enabled ->
                            binderEffectsAnimated = enabled
                            binderPreferences.edit().putBoolean(PRIVATE_BINDER_EFFECTS_PREFERENCE, enabled).apply()
                        },
                        modifier = Modifier.semantics { contentDescription = "Animierte Binder-Effekte" },
                    )
                }
            }
            "Preise" -> SettingsInfoBlock("Eine Quelle, klar benannt", "Der Marktwert nutzt ausschließlich den zuletzt bekannten Cardmarket-Trendpreis eines ausdrücklich zugeordneten Produkts. Ohne exakte Zuordnung zeigt DomiDex keinen Preis; tatsächliche Verkaufspreise können abweichen.")
            "Scanner-Verbesserung" -> ScannerAutomaticReportControl(rememberScannerReportFlow(), showAllAccess = true)
            "Support kontaktieren" -> SupportSettingsContent()
            "Datenschutz" -> {
                PrivacySettingsContent()
                SettingsInfoBlock("Sammlung und Firebase", "Ohne Google-Anmeldung bleibt dein Bestand lokal. Mit Google-Anmeldung werden Kartenidentität, Anzahl, Variante, Zustand, Notiz, Binder- und Kaufangaben kontogebunden mit Firebase abgeglichen. Ein älterer lokaler Bestand wird erst nach deiner ausdrücklichen Bestätigung übernommen.")
                SettingsInfoBlock("Kamera und Galerie", "Scanner-Aufnahmen gehören nicht zur Sammlungssynchronisierung. Berechtigte Tester können Fehlscans über „An ScanLab schicken“ separat senden. Erst nach ausdrücklicher Bestätigung wird das gewählte Foto hochgeladen. Es findet kein automatischer Fehlscan-Upload statt.")
                SettingsInfoBlock("Google-Anmeldung", "Firebase Authentication verarbeitet die für deine Anmeldung notwendigen Kontodaten. DomiDex erhält keine Zahlungsdaten und keine geheimen Google-Zugangsdaten. Andere Konten dürfen deinen synchronisierten Bestand nicht lesen.")
                SettingsInfoBlock("Deine DSGVO-Rechte", "Du kannst Auskunft, Berichtigung, Löschung, Einschränkung und Datenübertragbarkeit verlangen sowie Einwilligungen widerrufen. Lokale Karten und Fotos kannst du direkt in der App beziehungsweise auf deinem Gerät löschen.")
                SettingsInfoBlock("Vor öffentlichem Release", "Eine finale Datenschutzerklärung braucht noch die verantwortliche Stelle, Kontaktmöglichkeit, Aufbewahrungsfristen und eine Prüfung der tatsächlich aktivierten Online-Dienste. Dieser Abschnitt ist technische Transparenz, keine Rechtsberatung.")
            }
            "Hilfe" -> {
                SettingsInfoBlock("Schnellstart", "Übersicht → Sammelalben → Set öffnen → Karte auswählen oder mit Scanner hinzufügen.")
                SettingsInfoBlock("Versehentlich hinzugefügt?", "Öffne die Karte im Binder und wähle „Aus Binder entfernen“. DomiDex fragt zweimal nach. Auch ein kompletter Binder ist erst nach zwei Bestätigungen löschbar.")
                SettingsInfoBlock("Unsicherer Scan", "Wähle das Set im Menü und tippe nur die Nummer von unten auf der Karte ein, zum Beispiel 122/084.")
                DiscordSupportCard(context)
            }
            else -> {
                val version = remember(context) { installedDomiDexVersion(context) }
                Text("Pre-Beta · privat · Non-Profit", fontWeight = FontWeight.SemiBold)
                Text("Version ${version.name}\nBuild ${version.code}")
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
    val installedVersion = remember(context) { installedDomiDexVersion(context) }
    Text("Installierte Version", fontWeight = FontWeight.SemiBold)
    Text("DomiDex ${installedVersion.name} · Build ${installedVersion.code}")
    Text("App-Updates über Google Play", fontWeight = FontWeight.SemiBold)
    Text("Neue DomiDex-Versionen erhältst du über Google Play, sobald sie für deinen Testzugang freigegeben sind. Prüfe verfügbare Updates im Play Store.", color = VaultTextMuted)
    Text("Firebase-Synchronisierung", fontWeight = FontWeight.SemiBold)
    Text("Firebase ist für den Datenabgleich zuständig – nicht für die Installation von App-Updates. Den Status deiner Kontosynchronisierung findest du unter Konto.", color = VaultTextMuted)
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
            put("sourceSetId", card.sourceSetId ?: JSONObject.NULL)
            put("sourceCardId", card.sourceCardId ?: JSONObject.NULL)
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
            require(name.isNotBlank() && setName.isNotBlank()) { "Ein Backup-Eintrag enthält keine eindeutige Kartenangabe." }
            require(item.optInt("quantity", 1) in 1..999_999) { "Ungültige Kartenmenge im Backup." }
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
                sourceSetId = item.takeIf { it.has("sourceSetId") && !it.isNull("sourceSetId") }?.optString("sourceSetId")?.takeIf { it.isNotBlank() },
                sourceCardId = item.takeIf { it.has("sourceCardId") && !it.isNull("sourceCardId") }?.optString("sourceCardId")?.takeIf { it.isNotBlank() },
            ))
        }
    }
}

private data class BackupImportPayload(val raw: String, val version: Int, val entryCount: Int, val entries: List<ManualCardInput> = emptyList(), val zipEntries: Map<String, ByteArray> = emptyMap())

private fun writeDomiDexBackup(output: OutputStream, snapshot: com.dominik.cardvault.data.backup.BackupPhotos.Export, feedbackStore: ScanFeedbackStore): Int {
    val bounded = object : java.io.FilterOutputStream(output) {
        var written = 0L
        override fun write(value: Int) { require(++written <= 28L * 1024 * 1024) { "Das Backup überschreitet das sichere Dateilimit (28 MB)." }; out.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            written += length
            require(written <= 28L * 1024 * 1024) { "Das Backup überschreitet das sichere Dateilimit (28 MB)." }
            out.write(bytes, offset, length)
        }
    }
    ZipOutputStream(bounded.buffered()).use { zip ->
        zip.putNextEntry(java.util.zip.ZipEntry("collection.json"))
        zip.write(snapshot.raw.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        snapshot.files.forEach { (path, bytes) -> zip.putNextEntry(java.util.zip.ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
        return feedbackStore.exportTo(zip)
    }
}

private fun readDomiDexBackup(input: InputStream, context: Context): BackupImportPayload {
    val files = com.dominik.cardvault.data.backup.BoundedBackupReader.read(input)
    val collection = files["collection.json"] ?: error("Die Sammlung fehlt im Backup.")
    val raw = collection.toString(Charsets.UTF_8)
    val root = JSONObject(raw)
    val version = root.getInt("version")
    ScanFeedbackStore(context).validateImport(files, requireAllImages = version >= 2)
    com.dominik.cardvault.data.backup.BackupPhotos.validate(raw, files)
    return when (version) {
        1 -> parseCollectionBackup(raw).let { BackupImportPayload(raw, version, it.size, it, files) }
        2 -> {
            com.dominik.cardvault.data.backup.UserBackupStore(com.dominik.cardvault.data.local.CardVaultDatabase.get(context), com.dominik.cardvault.data.local.GUEST_OWNER_UID).validate(raw)
            BackupImportPayload(raw, version, root.getJSONObject("tables").getJSONArray("collection_entries").length(), zipEntries = files)
        }
        else -> error("Dieses Backup benötigt eine neuere DomiDex-Version.")
    }
}

@Composable private fun CollectionBackupDialog(repository: CollectionRepository, cards: List<CollectionCard>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedbackStore = remember(context) { ScanFeedbackStore(context) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<BackupImportPayload?>(null) }
    var working by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            status = runCatching {
                val feedbackCount = withContext(Dispatchers.IO) {
                    val snapshot = com.dominik.cardvault.data.backup.BackupPhotos.export(context,
                        repository.exportUserBackup(com.dominik.cardvault.data.backup.BackupSettings.export(context).toString()))
                    val verifiedArchive = File.createTempFile("domidex-export-", ".zip", context.cacheDir)
                    try {
                        val count = verifiedArchive.outputStream().use { writeDomiDexBackup(it, snapshot, feedbackStore) }
                        verifiedArchive.inputStream().use { com.dominik.cardvault.data.backup.BoundedBackupReader.read(it) }
                        context.contentResolver.openOutputStream(uri)?.use { target -> verifiedArchive.inputStream().use { it.copyTo(target) } }
                            ?: error("Datei konnte nicht geöffnet werden.")
                        count
                    } finally { verifiedArchive.delete() }
                }
                "Backup gespeichert: ${cards.size} Sammlungseinträge · $feedbackCount lokale Scan-Feedbacks."
            }.getOrElse { "Backup konnte nicht gespeichert werden. ${it.message.orEmpty()}" }
            working = false
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            val parsed = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { readDomiDexBackup(it, context) } ?: error("Datei konnte nicht geöffnet werden.") } }
            parsed.onSuccess { pendingImport = it; status = null }.onFailure { status = it.message ?: "Backup konnte nicht gelesen werden." }
            working = false
        }
    }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() }, title = { Text("Sammlung & Backup") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsInfoBlock("Lokales DomiDex-Backup", "Backup V2 enthält deine Sammlung, Varianten, Kaufdaten, Notizen, private Binder, Cover, Seiten, Kartenplätze, Holo-Einstellungen und Darstellungseinstellungen. Lokales Scan-Feedback wird mit seinen Bildern ergänzt. Marktpreis-Caches, Login und Einwilligungen werden nicht übernommen.")
            SettingsInfoBlock("Sicher speichern", "Wähle beim Export selbst einen Speicherort, zum Beispiel Dateien, Downloads oder einen privaten Cloud-Ordner. DomiDex lädt die Datei nicht hoch.")
            SettingsInfoBlock("Sicher importieren", "Backup V2 ergänzt fehlende Einträge. Bereits vorhandene Identitäten, Mengen und Binder bleiben erhalten. Ein erneuter Import desselben Backups erhöht keine Mengen. Ältere Backups werden einmalig innerhalb einer gemeinsamen Transaktion übernommen.")
            Button(onClick = { exportLauncher.launch("DomiDex-Backup-${java.time.LocalDate.now()}.zip") }, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text("Sammlung & Scan-Feedback exportieren") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/json", "text/json", "text/plain")) }, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text("DomiDex-Backup importieren") }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let { Text(it, color = if (it.contains("nicht", true) || it.contains("konnte", true)) MaterialTheme.colorScheme.error else VaultPositive, fontSize = 12.sp) }
        } },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Fertig") } },
    )
    pendingImport?.let { payload -> AlertDialog(
        onDismissRequest = { pendingImport = null }, title = { Text("${payload.entryCount} Einträge importieren?") },
        text = { Text("Die Einträge werden zu deiner aktuellen Sammlung hinzugefügt. Lokale Scan-Feedbacks werden nur ergänzt, wenn ihre ID noch nicht vorhanden ist. Es wird nichts gelöscht oder überschrieben.") },
        confirmButton = { TextButton(onClick = {
            pendingImport = null; scope.launch {
                working = true
                val outcome = runCatching { withContext(Dispatchers.IO) {
                    val database = com.dominik.cardvault.data.local.CardVaultDatabase.get(context)
                    com.dominik.cardvault.data.backup.BackupImportJournal(context, database).restore { beforeCreate, journalKey ->
                        feedbackStore.withImportedFeedback(payload.zipEntries, beforeCreate) { feedback ->
                            val count = if (payload.version == 2) com.dominik.cardvault.data.backup.BackupPhotos.withRestored(context, payload.raw, payload.zipEntries, beforeCreate) { prepared -> repository.restoreUserBackup(prepared, journalKey).addedCards }
                                else repository.restoreLegacyBackup(payload.raw, payload.entries, journalKey)
                            count to feedback
                        }
                    }.also {
                        com.dominik.cardvault.data.backup.BackupSettings.applyPending(context, com.dominik.cardvault.data.local.CardVaultDatabase.get(context))
                    }
                } }
                status = outcome.fold({ "${it.first} neue Einträge und ${it.second} Scan-Feedbacks wurden importiert. Binder und Einstellungen sind übernommen; bestehende Einträge bleiben erhalten." }, { "Import fehlgeschlagen. ${it.message.orEmpty()}" })
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
        appendLine("App-Version: ${packageInfo.versionName} (${PackageInfoCompat.getLongVersionCode(packageInfo)})")
        appendLine("Android: ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}")
        appendLine("Online: ${if (online) "OK" else "EINGESCHRÄNKT"}")
        appendLine("Google-Anmeldung: ${if (canonicalCollectionOwnerUid(FirebaseAuth.getInstance().currentUser) == null) "nicht aktiv" else "aktiv"}")
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
    val buildNumber = remember(packageInfo) { PackageInfoCompat.getLongVersionCode(packageInfo) }
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

@Composable private fun BinderScreen(cards: List<CollectionCard>, repository: CollectionRepository) {
    val context = LocalContext.current
    val binderPreferences = remember(context) { context.getSharedPreferences("cardvault_binders", Context.MODE_PRIVATE) }
    // An empty Room emission is a valid loaded state, but the collectAsState initial value is not.
    // Keep that distinction so restored navigation is not cleared during the first frame.
    val privateBinderState by produceState<List<PrivateBinderSummary>?>(initialValue = null, repository) {
        repository.privateBinders.collect { value = it }
    }
    val privateBinderSummaries = privateBinderState.orEmpty()
    val setBinderVisibility by repository.setBinderVisibility.collectAsState(initial = emptyMap())
    val visibleSetCards = remember(cards, setBinderVisibility) {
        cards.filter { card -> setBinderVisibility[card.entryId] != false }
    }
    var hiddenReferenceBinders by rememberSaveable {
        mutableStateOf(binderPreferences.getStringSet("hidden_reference_binders", emptySet()).orEmpty())
    }
    // The source catalog currently exposes the set in English while the
    // app's official German master binder is named "Wachsendes Chaos". Keep
    // both spellings in one physical binder, never in competing 122/194-slot
    // binders.
    val grouped = remember(visibleSetCards) {
        visibleSetCards.groupBy { card ->
            when {
                isWachsendesChaosSet(card.set) -> "Wachsendes Chaos"
                else -> card.set.ifBlank { "Ohne Set" }
            }
        }.toSortedMap()
    }
    val catalog = remember { PokemonVisualCatalog() }
    var catalogSets by remember { mutableStateOf<List<PokemonSet>>(emptyList()) }
    var localReferenceCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    // Keep only the selected set name. Its cards must always be derived from the
    // live portfolio state so a just-added card immediately replaces its shadow slot.
    var selectedSetName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPrivateBinderId by rememberSaveable { mutableStateOf<String?>(null) }
    var openingBinder by rememberSaveable { mutableStateOf<String?>(null) }
    var openingPrivateBinderId by rememberSaveable { mutableStateOf<String?>(null) }
    var openingStage by remember { mutableStateOf(BinderPresentationStage.IDLE) }
    val motionEnabled = rememberDomiMotionEnabled()
    var adding by remember { mutableStateOf(false) }
    var creatingPrivateBinder by rememberSaveable { mutableStateOf(false) }
    var privateBinderSaving by remember { mutableStateOf(false) }
    var privateBinderError by remember { mutableStateOf<String?>(null) }
    var overviewSort by rememberSaveable {
        mutableStateOf(BinderSortOption.fromStorage(binderPreferences.getString("overview_sort", null)))
    }
    var overviewFilter by rememberSaveable {
        mutableStateOf(BinderFilterOption.fromStorage(binderPreferences.getString("overview_filter", null)))
    }
    var overviewTab by rememberSaveable {
        val storedTab = binderPreferences.getString("overview_tab", null)
        val migratedTab = storedTab ?: if (overviewFilter == BinderFilterOption.PRIVATE_BINDERS) {
            BinderOverviewTab.PRIVATE_BINDERS.storageValue
        } else {
            BinderOverviewTab.SET_BINDERS.storageValue
        }
        mutableStateOf(BinderOverviewTab.fromStorage(migratedTab))
    }
    var setLastOpenedRevision by remember { mutableIntStateOf(0) }
    var choosingCatalog by remember { mutableStateOf(false) }
    var catalogDraft by remember { mutableStateOf<CatalogSearchResult?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(openingBinder, motionEnabled) {
        openingBinder?.let { pendingSetName ->
            binderPresentationBeats(motionEnabled).forEach { beat ->
                openingStage = beat.stage
                if (beat.durationMillis > 0L) delay(beat.durationMillis)
            }
            selectedSetName = pendingSetName
            openingBinder = null
            openingStage = BinderPresentationStage.IDLE
        }
    }
    LaunchedEffect(openingPrivateBinderId, motionEnabled) {
        val binderId = openingPrivateBinderId ?: return@LaunchedEffect
        val summary = privateBinderSummaries.firstOrNull { it.id == binderId } ?: run {
            openingPrivateBinderId = null
            return@LaunchedEffect
        }
        binderPresentationBeats(motionEnabled).forEach { beat ->
            openingStage = beat.stage
            if (beat.durationMillis > 0L) delay(beat.durationMillis)
        }
        selectedPrivateBinderId = summary.id
        openingPrivateBinderId = null
        openingStage = BinderPresentationStage.IDLE
    }
    LaunchedEffect(selectedPrivateBinderId, privateBinderState?.map(PrivateBinderSummary::id)) {
        if (privateBinderState != null && selectedPrivateBinderId != null && privateBinderSummaries.none { it.id == selectedPrivateBinderId }) {
            selectedPrivateBinderId = null
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
        resolveBinderSourceSet(name, grouped[name].orEmpty(), catalogSets)?.logoUrl?.takeIf(String::isNotBlank)?.let { return it }
        if (isWachsendesChaosSet(name)) return "https://www.pokemon.com/static-assets/content-assets/cms2-de-de/img/trading-card-game/series/me_series/me04/me04_logo_169_de.png"
        return null
    }
    fun cardCapacityFor(name: String, setCards: List<CollectionCard>): Int {
        if (isWachsendesChaosSet(name)) return WachsendesChaosMasterSet.cards.size
        localReferenceCounts[name]?.takeIf { it > 0 }?.let { return it }
        val catalogTotal = resolveBinderSourceSet(name, setCards, catalogSets)?.total ?: 0
        // A verified visual set defines its own binder size.  A mistyped or
        // promotional collector number must be an overflow card, never inflate
        // the official set or displace the numbered slots.
        if (catalogTotal > 0) return catalogTotal
        val printedDenominator = setCards.maxOfOrNull { it.number.substringAfter('/', "").toIntOrNull() ?: 0 } ?: 0
        val numberedCards = setCards.filter { collectorNumberOf(it.number) != null }
        val observedVariantSlots = numberedCards.size - numberedCards.distinctBy { collectorNumberOf(it.number) }.size
        return maxOf(1, setCards.size, maxOf(catalogTotal, printedDenominator) + observedVariantSlots)
    }
    val selectedPrivateBinder = privateBinderSummaries.firstOrNull { it.id == selectedPrivateBinderId }
    if (selectedPrivateBinder != null) {
        PrivateBinderReaderScreen(
            binder = selectedPrivateBinder,
            collectionCards = cards,
            repository = repository,
            onBack = { selectedPrivateBinderId = null },
        )
        return
    }
    selectedSetName?.let { setName ->
        val setCards = grouped[setName].orEmpty()
        val capacity = cardCapacityFor(setName, setCards)
        BinderReaderScreen(
            setName = setName,
            cards = setCards,
            cardCapacity = capacity,
            coverUrl = coverFor(setName),
            repository = repository,
            onBack = { selectedSetName = null },
            onHideReferenceBinder = if (setCards.isEmpty()) {
                {
                    val updated = hiddenReferenceBinders + setName
                    binderPreferences.edit().putStringSet("hidden_reference_binders", updated).apply()
                    hiddenReferenceBinders = updated
                    selectedSetName = null
                }
            } else null,
        )
        return
    }
    val setLastOpenedByName = remember(grouped.keys.toList(), setLastOpenedRevision) {
        grouped.keys.associateWith { setName ->
            binderPreferences.getLong("last_opened_set:$setName", 0L).takeIf { it > 0L }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { DomiScreenTitle("Deine Binder", eyebrow = "DomiDex Binder", subtitle = "Sammeln, öffnen und wie in einem echten Album blättern.") }
            item { BinderFocusHero() }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { choosingCatalog = true }, modifier = Modifier.weight(1f).height(CardVaultSpacing.buttonHeight), shape = CardVaultShapes.card, colors = ButtonDefaults.buttonColors(containerColor = MewPlumDeep, contentColor = VaultText), border = BorderStroke(1.dp, MewRose.copy(alpha = .72f))) {
                        DomiLineIcon(DomiIconKind.ADD, MaterialTheme.colorScheme.onPrimary, Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Karte hinzufügen", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = { privateBinderError = null; creatingPrivateBinder = true }, modifier = Modifier.weight(1f).height(CardVaultSpacing.buttonHeight), shape = CardVaultShapes.card) {
                        DomiLineIcon(DomiIconKind.BINDER, MewIce, Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Privater Binder", maxLines = 1)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = overviewTab == BinderOverviewTab.SET_BINDERS,
                        onClick = {
                            overviewTab = BinderOverviewTab.SET_BINDERS
                            binderPreferences.edit().putString("overview_tab", BinderOverviewTab.SET_BINDERS.storageValue).apply()
                        },
                        label = { Text("Set-Binder") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = overviewTab == BinderOverviewTab.PRIVATE_BINDERS,
                        onClick = {
                            overviewTab = BinderOverviewTab.PRIVATE_BINDERS
                            binderPreferences.edit().putString("overview_tab", BinderOverviewTab.PRIVATE_BINDERS.storageValue).apply()
                        },
                        label = { Text("Private Binder") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                val visibleFilter = when (overviewFilter) {
                    BinderFilterOption.COMPLETE, BinderFilterOption.INCOMPLETE -> overviewFilter
                    else -> BinderFilterOption.ALL
                }
                BinderOverviewControls(
                    sort = overviewSort,
                    filter = visibleFilter,
                    onSortChanged = { option ->
                        overviewSort = option
                        binderPreferences.edit().putString("overview_sort", option.storageValue).apply()
                    },
                    onFilterChanged = { option ->
                        overviewFilter = option
                        binderPreferences.edit().putString("overview_filter", option.storageValue).apply()
                    },
                    showFilter = overviewTab == BinderOverviewTab.SET_BINDERS,
                    filterOptions = listOf(BinderFilterOption.ALL, BinderFilterOption.COMPLETE, BinderFilterOption.INCOMPLETE),
                )
            }
            val setRecords = grouped.map { (setName, setCards) ->
                val capacity = cardCapacityFor(setName, setCards)
                val progress = completedBinderSlotCount(setCards)
                BinderOverviewRecord(
                    id = "set:$setName",
                    name = setName,
                    kind = BinderOverviewKind.SET,
                    ownedCards = setCards.sumOf { it.quantity },
                    totalSlots = capacity,
                    marketValueEur = setCards.filter { it.priceUpdatedAtEpochMillis != null }.sumOf { it.currentValueEur * it.quantity },
                    lastOpenedAtEpochMillis = setLastOpenedByName[setName],
                    completedSlots = progress,
                )
            }
            val privateRecords = privateBinderSummaries.map { binder ->
                BinderOverviewRecord(
                    id = "private:${binder.id}",
                    name = binder.name,
                    kind = BinderOverviewKind.PRIVATE,
                    ownedCards = binder.ownedCards,
                    marketValueEur = binder.marketValueEur,
                    lastOpenedAtEpochMillis = binder.lastOpenedAtEpochMillis,
                )
            }
            val recordsForTab = binderOverviewRecordsForTab(setRecords + privateRecords, overviewTab)
            val effectiveFilter = if (overviewTab == BinderOverviewTab.SET_BINDERS) {
                when (overviewFilter) {
                    BinderFilterOption.COMPLETE, BinderFilterOption.INCOMPLETE -> overviewFilter
                    else -> BinderFilterOption.ALL
                }
            } else {
                BinderFilterOption.ALL
            }
            val orderedBinders = orderBinderOverview(recordsForTab, overviewSort, effectiveFilter)
            if (overviewTab == BinderOverviewTab.SET_BINDERS && grouped.isEmpty()) item {
                DomiEmptyState(
                    title = "Noch kein Set-Binder",
                    body = "Füge eine Karte hinzu, damit ihr passender Set-Binder erscheint.",
                    actionLabel = "Karte hinzufügen",
                    onAction = { choosingCatalog = true },
                )
            }
            if (overviewTab == BinderOverviewTab.PRIVATE_BINDERS && privateBinderSummaries.isEmpty()) item {
                DomiEmptyState(
                    title = "Du hast noch keinen privaten Binder.",
                    body = "Erstelle einen freien Binder und ordne deine Lieblingskarten unabhängig vom Set an.",
                    actionLabel = "Privaten Binder erstellen",
                    onAction = { privateBinderError = null; creatingPrivateBinder = true },
                )
            } else if (orderedBinders.isEmpty() && recordsForTab.isNotEmpty()) item {
                DomiEmptyState("Keine passenden Binder", "Passe den Filter an, um deine Binder wieder einzublenden.")
            }
            orderedBinders.forEach { record ->
                item(key = record.id) {
                    if (record.kind == BinderOverviewKind.SET) {
                        val setName = record.name
                        val setCards = grouped[setName].orEmpty()
                        val capacity = record.totalSlots ?: cardCapacityFor(setName, setCards)
                        val pricedCards = setCards.filter { it.priceUpdatedAtEpochMillis != null }
                        DomiBinderShelfCard(
                            title = setName,
                            owned = record.completedSlots ?: record.ownedCards,
                            total = capacity,
                            cardCount = setCards.sumOf { it.quantity },
                            marketValue = if (pricedCards.isEmpty()) "Kein Trendpreis verfügbar" else "Binderwert ${euro(record.marketValueEur)}",
                            logoUrl = coverFor(setName),
                            selected = openingBinder == setName,
                            onClick = {
                                if (openingBinder == null && openingPrivateBinderId == null) {
                                    binderPreferences.edit().putLong("last_opened_set:$setName", System.currentTimeMillis()).apply()
                                    setLastOpenedRevision++
                                    openingBinder = setName
                                }
                            },
                        )
                    } else {
                        privateBinderSummaries.firstOrNull { "private:${it.id}" == record.id }?.let { binder ->
                            PrivateBinderShelfCard(
                                binder = binder,
                                selected = openingPrivateBinderId == binder.id,
                                onClick = {
                                    if (openingBinder == null && openingPrivateBinderId == null) openingPrivateBinderId = binder.id
                                },
                            )
                        }
                    }
                }
            }
        }
        openingBinder?.let { setName -> BinderOpeningOverlay(setName, openingStage) }
        openingPrivateBinderId?.let { binderId ->
            privateBinderSummaries.firstOrNull { it.id == binderId }?.let {
                PrivateBinderOpeningOverlay(it.name, it.coverStyle, openingStage)
            }
        }
    }
    if (choosingCatalog) CatalogProductPicker(repository, onDismiss = { choosingCatalog = false }, onSelected = { result ->
        // Never shortcut a catalog tap into a fixed normal-card insertion. The existing dialog
        // is the single entry point for finish, Holo effect, condition and the exact price link.
        // The source-confirmed visual set stays authoritative for the Binder name.
        catalogDraft = result
        choosingCatalog = false
        adding = true
    })
    if (adding) ManualCardDialog(
        repository = repository,
        initialCatalog = catalogDraft,
        onDismiss = { adding = false; catalogDraft = null },
    )
    if (creatingPrivateBinder) PrivateBinderEditorDialog(
        saving = privateBinderSaving,
        error = privateBinderError,
        onDismiss = { creatingPrivateBinder = false; privateBinderError = null },
        onSave = { name, description, coverThemeId ->
            privateBinderSaving = true
            privateBinderError = null
            scope.launch {
                try {
                    repository.createPrivateBinder(name, description, coverThemeId)
                    creatingPrivateBinder = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    privateBinderError = binderUiError(error, "Der private Binder konnte nicht erstellt werden.")
                } finally {
                    privateBinderSaving = false
                }
            }
        },
    )
}

@Composable private fun BinderFocusHero() {
    DomiGlassCard(Modifier.fillMaxWidth(), highlighted = true) {
        Box(Modifier.fillMaxWidth().height(152.dp), contentAlignment = Alignment.CenterStart) {
            Canvas(Modifier.matchParentSize().clearAndSetSemantics { }) {
                val center = Offset(size.width * .83f, size.height * .5f)
                drawCircle(MewRose.copy(alpha = .08f), size.minDimension * .72f, center)
                drawCircle(MewIce.copy(alpha = .10f), size.minDimension * .42f, center, style = Stroke(2f))
            }
            Column(Modifier.padding(18.dp).fillMaxWidth(.62f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("DEIN FOKUS", color = MewRose, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.4.sp)
                Text("Binder zuerst.", color = VaultText, style = MaterialTheme.typography.headlineMedium)
                Text("Deine Karten bleiben sichtbar, greifbar und nach Sets oder ganz frei geordnet.", color = VaultTextMuted, fontSize = 11.sp, lineHeight = 15.sp)
            }
            DomiPhysicalBinder("DomiDex", 0f, Modifier.align(Alignment.CenterEnd).padding(end = 13.dp).size(112.dp, 112.dp))
        }
    }
}

@Composable internal fun BinderReaderScreen(
    setName: String,
    cards: List<CollectionCard>,
    cardCapacity: Int,
    coverUrl: String?,
    repository: CollectionRepository,
    onBack: () -> Unit,
    onHideReferenceBinder: (() -> Unit)? = null,
    visualCatalog: PokemonVisualCatalog = remember { PokemonVisualCatalog() },
) {
    val privateBinderSummaries by repository.privateBinders.collectAsState(initial = emptyList())
    var setReferences by remember(setName) { mutableStateOf<List<CollectionCard>>(emptyList()) }
    var sourceSetCards by remember(setName) { mutableStateOf(wachsendesChaosSourceCards(setName)) }
    var refreshedCoverUrl by remember(setName) { mutableStateOf<String?>(null) }
    val displayedCoverUrl = fallbackCardImageUrl(refreshedCoverUrl, coverUrl)
    var referencesLoading by remember(setName) { mutableStateOf(true) }
    var refreshVersion by rememberSaveable(setName) { mutableIntStateOf(0) }
    LaunchedEffect(setName, refreshVersion) {
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
            val set = resolveBinderSourceSet(setName, cards, visualCatalog.sets())
            if (set == null) {
                setReferences = localReferences
                return@LaunchedEffect
            }
            refreshedCoverUrl = set.logoUrl
            val downloadedCards = visualCatalog.cards(set)
            sourceSetCards = downloadedCards
            // Cache source metadata and licensed image URLs locally for this
            // master set.  These are reference slots only, never owned cards.
            // Show the numbered grey slots immediately, then enrich them in
            // the background with only source-declared foil printings.  This
            // avoids inventing a Holo/Reverse slot for a card that has none.
            setReferences = downloadedCards.flatMap { reference -> reference.asBinderReferences() }
            // Set membership comes from the maintained registry, never card-title similarity.
            val resolvedMarketExpansionId = set.cardmarketExpansionId
            if (resolvedMarketExpansionId != null) {
                setReferences = downloadedCards.flatMap { reference ->
                    reference.asBinderReferences(resolvedMarketExpansionId)
                }
            }
            // Detail endpoints provide the real printings. Fetch a small bounded
            // batch at a time; sequential requests made large German sets look like
            // one-slot binders for far too long, while an unbounded burst is unfair
            // to the public catalogue service.
            val detailedCards = downloadedCards.chunked(6).flatMap { batch ->
                batch.map { reference -> async { runCatching { visualCatalog.withDeclaredPrintVariants(reference) }.getOrDefault(reference) } }
                    .map { it.await() }
            }
            sourceSetCards = detailedCards
            repository.cacheGermanMasterSetCards(
                GermanMasterSetDefinition(set.id, set.name, set.logoUrl),
                detailedCards.flatMap { reference -> reference.binderPrintVariants().map { printing ->
                    GermanMasterSetCardDefinition(
                        if (printing.id == "normal") reference.id else "${reference.id}-${printing.id}",
                        reference.name, reference.number, reference.largeImageUrl, printing.finish, printing.variant,
                        reference.rarity ?: "UNKNOWN",
                    )
                } },
            )
            detailedCards.flatMap { reference -> reference.asBinderReferences(resolvedMarketExpansionId) }
                .also { setReferences = it }
        } catch (error: Exception) {
            // Preserve any explicit local catalogue fallback when offline. Never
            // erase an already rendered reference wall because one refresh failed.
            Log.w("DomiDexBinder", "German master-set variants could not be refreshed", error)
        } finally {
            referencesLoading = false
        }
    }
    val arrangedCards = remember(setName, cards, cardCapacity, setReferences, refreshVersion) { arrangeBinderCards(setName, cards, cardCapacity, setReferences) }
    val binderPageCount = ((arrangedCards.size + 8) / 9).coerceAtLeast(1)
    val faces = remember(arrangedCards, binderPageCount) {
        buildList {
            add(BinderFace.White)
            arrangedCards.chunked(9).ifEmpty { listOf(emptyList()) }.forEach { add(BinderFace.Pockets(it)) }
            add(BinderFace.White)
        }
    }
    var page by rememberSaveable(setName) { mutableIntStateOf(0) }
    // Refresh can temporarily replace the full set with a smaller local reference list.
    // Reconcile presentation state before rendering or handling page navigation.
    if (page !in faces.indices) page = page.coerceIn(0, faces.lastIndex)
    var selectedCard by remember { mutableStateOf<CollectionCard?>(null) }
    var choosingAddCard by remember { mutableStateOf(false) }
    var selectedAddCard by remember { mutableStateOf<CatalogSearchResult?>(null) }
    var pendingReferenceAdd by remember { mutableStateOf<Pair<CollectionCard, CatalogSearchResult>?>(null) }
    var referenceAddError by remember { mutableStateOf<String?>(null) }
    var addingReferenceId by remember { mutableStateOf<String?>(null) }
    var editingCard by remember { mutableStateOf<CollectionCard?>(null) }
    var linkingCard by remember { mutableStateOf<CollectionCard?>(null) }
    var removalCandidate by remember { mutableStateOf<CollectionCard?>(null) }
    var removalStage by rememberSaveable { mutableIntStateOf(0) }
    val ownedBinderId = remember(cards) { cards.map { it.binderId }.filter { it.isNotBlank() }.distinct().singleOrNull() }
    val binderValue = remember(cards) { cards.filter { it.priceUpdatedAtEpochMillis != null }.sumOf { it.currentValueEur * it.quantity } }
    val pricedBinderCards = remember(cards) { cards.filter { it.priceUpdatedAtEpochMillis != null }.sumOf { it.quantity } }
    val binderCards = remember(cards) { cards.sumOf { it.quantity } }
    var binderDeletionStage by rememberSaveable { mutableIntStateOf(0) }
    var pageDragFraction by remember { mutableFloatStateOf(0f) }
    var insertionCard by remember { mutableStateOf<CollectionCard?>(null) }
    var readerStage by remember { mutableStateOf(BinderPresentationStage.READY) }
    val motionEnabled = rememberDomiMotionEnabled()
    val scope = rememberCoroutineScope()
    fun closeBinder() {
        if (readerStage in setOf(BinderPresentationStage.CLOSING, BinderPresentationStage.RETURNING)) return
        if (!motionEnabled) onBack() else scope.launch {
            readerStage = BinderPresentationStage.CLOSING
            delay(230)
            readerStage = BinderPresentationStage.RETURNING
            delay(120)
            onBack()
        }
    }
    BackHandler(onBack = ::closeBinder)
    Box(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize().padding(CardVaultSpacing.screenCompact), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(onClick = ::closeBinder, color = VaultNightRaised, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, VaultOutlineSoft)) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { DomiLineIcon(DomiIconKind.BACK, VaultText, Modifier.size(18.dp)) }
            }
            Column(Modifier.weight(1f)) {
                Text(setName, color = VaultText, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (page) {
                        0 -> "Vorderseite · ${arrangedCards.size} Sammelplätze"
                        faces.lastIndex -> "Rückseite · ${arrangedCards.size} Sammelplätze"
                        else -> "Kartenseite $page von $binderPageCount"
                    },
                    color = VaultTextMuted,
                    fontSize = 10.sp,
                )
            }
            Surface(onClick = { visualCatalog.invalidate(); refreshVersion++ }, enabled = !referencesLoading, modifier = Modifier.semantics { contentDescription = "Binder aktualisieren" }, color = VaultNightRaised, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, VaultOutlineSoft)) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { DomiLineIcon(DomiIconKind.SYNC, if (referencesLoading) VaultTextMuted else MewIce, Modifier.size(18.dp)) }
            }
            if (displayedCoverUrl != null) AsyncImage(displayedCoverUrl, "Set-Umschlag", Modifier.size(52.dp, 30.dp)) else DomiDexBrandMark(size = 34.dp)
        }
        DomiGlassCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (pricedBinderCards == 0) "Kein Marktwert verfügbar" else euro(binderValue), color = if (pricedBinderCards == 0) VaultTextMuted else VaultText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Cardmarket-Trendwert · $pricedBinderCards/$binderCards bepreist", color = VaultTextMuted, fontSize = 9.sp)
                    if (referencesLoading) Text("Deutsche Setvarianten werden geprüft …", color = MewIce, fontSize = 9.sp)
                }
                if (ownedBinderId != null || onHideReferenceBinder != null) TextButton(onClick = { binderDeletionStage = 1 }) {
                    Text("Verwalten", color = VaultTextMuted, fontSize = 10.sp)
                }
            }
        }
        // Each animated page owns its face snapshot; an outgoing index must never
        // be dereferenced against a newer (possibly shorter) reference list.
        AnimatedContent(targetState = page to faces[page], contentKey = { it.first }, modifier = Modifier.weight(1f), transitionSpec = {
            if (targetState.first > initialState.first) (slideInHorizontally(tween(DomiMotion.Normal)) { it } + fadeIn(tween(DomiMotion.Fast))) togetherWith (slideOutHorizontally(tween(DomiMotion.Normal)) { -it } + fadeOut(tween(DomiMotion.Fast)))
            else (slideInHorizontally(tween(DomiMotion.Normal)) { -it } + fadeIn(tween(DomiMotion.Fast))) togetherWith (slideOutHorizontally(tween(DomiMotion.Normal)) { it } + fadeOut(tween(DomiMotion.Fast)))
        }, label = "Vollbild-Sammelalbumseite") { (_, face) ->
            val pageModifier = Modifier.fillMaxSize().graphicsLayer {
                rotationY = -pageDragFraction * 16f
                transformOrigin = TransformOrigin(if (pageDragFraction < 0f) 1f else 0f, .5f)
                cameraDistance = 18f * density
                shadowElevation = kotlin.math.abs(pageDragFraction) * 14.dp.toPx()
            }.pointerInput(page, faces.lastIndex) {
                var horizontalTravel = 0f
                detectDragGestures(
                    onDragStart = { horizontalTravel = 0f; readerStage = BinderPresentationStage.PAGE_TURNING },
                    onDrag = { change, amount ->
                        change.consume()
                        horizontalTravel += amount.x
                        pageDragFraction = (horizontalTravel / size.width).coerceIn(-1f, 1f)
                    },
                    onDragEnd = {
                        when {
                            horizontalTravel < -80f && page < faces.lastIndex -> page++
                            horizontalTravel > 80f && page > 0 -> page--
                        }
                        pageDragFraction = 0f
                        readerStage = BinderPresentationStage.READY
                    },
                    onDragCancel = { pageDragFraction = 0f; readerStage = BinderPresentationStage.READY },
                )
            }.clickable { if (page < faces.lastIndex) page++ }
            FullBinderFace(
                face = face,
                coverUrl = displayedCoverUrl,
                setName = setName,
                modifier = pageModifier,
                onCardSelected = { selectedCard = it },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { page-- }, enabled = page > 0) { Text("‹ Zurück") }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val visibleDots = minOf(faces.size, 9)
                repeat(visibleDots) { dot ->
                    val represented = if (faces.size <= visibleDots) dot else (dot * faces.lastIndex / (visibleDots - 1))
                    Box(Modifier.size(if (represented == page) 14.dp else 4.dp, 4.dp).background(if (represented == page) MewRose else VaultOutline, RoundedCornerShape(99.dp)))
                }
            }
            Button(onClick = { page++ }, enabled = page < faces.lastIndex) { Text("Weiter ›") }
        }
      }
      insertionCard?.let { BinderInsertionOverlay(it) }
      if (readerStage in setOf(BinderPresentationStage.CLOSING, BinderPresentationStage.RETURNING)) BinderOpeningOverlay(setName, readerStage)
    }
    selectedCard?.let { clickedCard ->
        val card = if (clickedCard.entryId == 0L) {
            refreshedBinderSelection(clickedCard, setReferences)
        } else cards.firstOrNull { it.entryId == clickedCard.entryId } ?: clickedCard
        CardPreviewDialog(
            card = card,
            repository = repository,
            sourceSetCards = sourceSetCards,
            visualCatalog = visualCatalog,
            onDismiss = { selectedCard = null },
            onEdit = card.takeIf { it.entryId > 0L }?.let { { selectedCard = null; editingCard = card } },
            // Catalog/reference slots use entryId 0 and are intentionally never removable.
            onDelete = if (card.entryId > 0L) { { removalCandidate = card; removalStage = 1 } } else null,
            // A reference slot is only added after the collector explicitly
            // confirms the one visible Cardmarket trend price in this preview.
            onAdd = if (card.entryId == 0L) { { product ->
                if (addingReferenceId != null) return@CardPreviewDialog
                referenceAddError = null
                pendingReferenceAdd = card to product
                selectedCard = null
            } } else null,
            addInProgress = addingReferenceId == card.id,
            onChangeSelection = if (card.entryId == 0L) { { selectedCard = null; choosingAddCard = true } } else null,
        )
    }
    pendingReferenceAdd?.let { (card, product) ->
        ReferenceBinderTargetDialog(
            card = card,
            product = product,
            privateBinders = privateBinderSummaries,
            saving = addingReferenceId != null,
            error = referenceAddError,
            onDismiss = { if (addingReferenceId == null) { pendingReferenceAdd = null; referenceAddError = null } },
            onConfirm = { target ->
                if (addingReferenceId != null) return@ReferenceBinderTargetDialog
                addingReferenceId = card.id
                referenceAddError = null
                scope.launch {
                    try {
                        repository.addManualCard(
                            ManualCardInput(
                                name = card.name,
                                setName = card.set,
                                collectorNumber = card.number,
                                language = card.language,
                                finish = card.finish,
                                variant = card.variant,
                                quantity = 1,
                                condition = "Nicht geprüft",
                                notes = "",
                                acquisitionPriceEur = null,
                                acquisitionDateEpochDay = null,
                                externalProductId = product.productId,
                                imageUrl = card.imageUrl,
                                cardmarketExpansionId = product.expansionId,
                                sourceSetId = card.sourceSetId,
                                sourceCardId = card.sourceCardId,
                            ),
                            target,
                        )
                        pendingReferenceAdd = null
                        if (target == BinderTarget.MatchingSetBinder) {
                            readerStage = BinderPresentationStage.CARD_INSERTING
                            insertionCard = card
                            delay(850)
                            insertionCard = null
                            readerStage = BinderPresentationStage.READY
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        referenceAddError = binderUiError(error, "Die Karte konnte nicht gespeichert werden.")
                    } finally {
                        addingReferenceId = null
                    }
                }
            },
        )
    }
    if (choosingAddCard) CatalogProductPicker(repository, onDismiss = { choosingAddCard = false }) { selection ->
        choosingAddCard = false
        selectedAddCard = selection
    }
    selectedAddCard?.let { selection ->
        ManualCardDialog(repository, initialCatalog = selection, onDismiss = { selectedAddCard = null })
    }
    editingCard?.let { card ->
        EditEntryDialog(
            card = card,
            onDismiss = { editingCard = null },
            onDelete = { editingCard = null; removalCandidate = card; removalStage = 1 },
            onLink = { editingCard = null; linkingCard = card },
            onSave = { update -> scope.launch { repository.updateEntry(card.entryId, update) }; editingCard = null },
        )
    }
    linkingCard?.let { card ->
        CatalogLinkDialog(
            card = card,
            repository = repository,
            onDismiss = { linkingCard = null },
            onLinked = { productId -> scope.launch { repository.linkCatalogProduct(card.entryId, productId) }; linkingCard = null },
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
        BinderFace.White -> Box(
            modifier
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF05060D), Color(0xFF17102C), Color(0xFF071923), Color(0xFF05060D)),
                    ),
                    RoundedCornerShape(14.dp),
                )
                .border(
                    2.dp,
                    Brush.linearGradient(listOf(MewRose.copy(alpha = .62f), MewIce.copy(alpha = .42f), MewPlum.copy(alpha = .55f))),
                    RoundedCornerShape(14.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.matchParentSize()) {
                drawCircle(MewPlum.copy(alpha = .12f), radius = size.minDimension * .38f, center = center)
                drawCircle(MewIce.copy(alpha = .09f), radius = size.minDimension * .29f, center = center, style = Stroke(2.dp.toPx()))
                repeat(16) { index ->
                    val x = size.width * (((index * 37) % 97) / 97f)
                    val y = size.height * (((index * 61 + 13) % 101) / 101f)
                    drawCircle(if (index % 3 == 0) MewIce else MewRose, radius = if (index % 4 == 0) 2.2f else 1.2f, center = Offset(x, y), alpha = .22f)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth(.72f)) {
                if (coverUrl != null) AsyncImage(coverUrl, "$setName Set-Logo", Modifier.fillMaxWidth().heightIn(max = 150.dp), contentScale = ContentScale.Fit) else Text(setName.uppercase(), color = VaultText, fontWeight = FontWeight.Black, fontSize = 24.sp, textAlign = TextAlign.Center)
                Text("DOMIDEX", color = VaultText.copy(alpha = .32f), fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
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
                        Box(Modifier.weight(1f).fillMaxHeight().graphicsLayer { alpha = slotPresence; scaleX = .96f + slotPresence * .04f; scaleY = .96f + slotPresence * .04f }.background(if (owned) Color(card!!.accent) else Color(0xFF111218), RoundedCornerShape(7.dp)).border(1.dp, if (owned) Color.Transparent else MewPlum.copy(alpha = .16f), RoundedCornerShape(7.dp)).then(card?.let { Modifier.clickable { onCardSelected(it) } } ?: Modifier), contentAlignment = Alignment.Center) {
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

/** Immediate source identity for the fixed binder while live detail metadata loads. */
internal fun wachsendesChaosSourceCards(setName: String): List<PokemonVisualCard> {
    if (!isWachsendesChaosSet(setName)) return emptyList()
    val set = PokemonSetRegistry.legacyOfflineSet("me04") ?: return emptyList()
    return WachsendesChaosMasterSet.cards.groupBy { it.sourceCardId }.values.map { printings ->
        val primary = printings.first()
        PokemonVisualCard(
            id = primary.sourceCardId,
            name = primary.name,
            number = primary.number.substringBefore('/'),
            rarity = null,
            smallImageUrl = primary.imageUrl,
            largeImageUrl = primary.imageUrl,
            set = set,
            printVariants = printings.map { printing ->
                PokemonPrintVariant(printing.id, printing.finish, printing.variant)
            },
        )
    }
}

/** Source identity survives translated or edited display names in the Binder. */
internal fun resolveBinderSourceSet(setName: String, cards: List<CollectionCard>, sets: List<PokemonSet>): PokemonSet? {
    val sourceSetId = cards.mapNotNull { it.sourceSetId?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank) }
        .distinct().singleOrNull()
    sourceSetId?.let { source -> sets.firstOrNull { it.id.equals(source, ignoreCase = true) }?.let { return it } }
    val normalized = normalizeBinderMetadata(setName)
    if (normalized.isBlank()) return null
    return sets.firstOrNull { normalizeBinderMetadata(it.name) == normalized }
        ?: sets.firstOrNull { candidate ->
            val candidateName = normalizeBinderMetadata(candidate.name)
            candidateName.isNotBlank() && (candidateName.contains(normalized) || normalized.contains(candidateName))
        }
}

/**
 * Binder invariant: a numbered main-set card may occupy at most two physical print slots.
 * EX/evolution-rule cards, special rarities and cards above the printed main set stay singular.
 */
internal fun PokemonVisualCard.binderPrintVariants(): List<PokemonPrintVariant> {
    val distinct = printVariants.distinctBy { printing ->
        "${normalizeBinderMetadata(printing.finish)}|${normalizeBinderMetadata(printing.variant)}"
    }
    if (distinct.isEmpty()) return listOf(PokemonPrintVariant("normal", "Normal", "Standard"))
    // Stamp, special-foil and error printings retain their exact product identity
    // for manual selection, but they must not create extra canonical master slots.
    // Pick one source printing per established Normal/Holo/Reverse slot and prefer
    // the ordinary printing when the source also declares a special edition.
    val canonicalByFinish = distinct
        .groupBy { masterFinishOrder(it.finish, it.variant) }
        .mapValues { (_, printings) ->
            printings.minWithOrNull(
                compareBy<PokemonPrintVariant> { printing ->
                    listOf("untertyp:", "stempel:", "folie:").count { marker ->
                        printing.variant.contains(marker, ignoreCase = true)
                    }
                }.thenBy { distinct.indexOf(it) },
            )!!
        }
    val number = collectorNumberOf(this.number)
    val inMainSet = number != null && set.printedTotal > 0 && number <= set.printedTotal
    val normalizedName = name.trim().lowercase(Locale.ROOT)
    val normalizedRarity = rarity.orEmpty().lowercase(Locale.ROOT)
    val isRuleBoxPokemon = Regex("(?:^|\\s|-)(ex|gx|v|vmax|vstar)$", RegexOption.IGNORE_CASE).containsMatchIn(normalizedName)
    val isSpecialRarity = listOf("ace", "special", "illustration", "ultra", "hyper", "secret", "rainbow", "gold")
        .any(normalizedRarity::contains)
    val canonical = listOfNotNull(canonicalByFinish[0], canonicalByFinish[1], canonicalByFinish[2])
    if (!inMainSet || isRuleBoxPokemon || isSpecialRarity) return canonical.take(1)

    val normal = canonicalByFinish[0]
    val reverse = canonicalByFinish[2]
    return if (normal != null && reverse != null) listOf(normal, reverse) else canonical.take(2)
}

/** Every allowed source-confirmed printing gets its own master-set slot. */
private fun PokemonVisualCard.asBinderReferences(
    marketExpansionId: String? = set.cardmarketExpansionId,
): List<CollectionCard> = binderPrintVariants().map { printing ->
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
        marketSearchName = marketSearchName,
        marketExpansionId = marketExpansionId,
        sourceSetId = set.id.lowercase(),
        sourceCardId = id.lowercase(),
    )
}

internal fun arrangeBinderCards(setName: String, cards: List<CollectionCard>, capacity: Int, references: List<CollectionCard> = emptyList()): List<CollectionCard?> {
    if (isWachsendesChaosSet(setName)) {
        val fixedSlots = WachsendesChaosMasterSet.cards.take(capacity).map { reference ->
            CollectionCard(
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
                sourceSetId = reference.sourceSetId,
                sourceCardId = reference.sourceCardId,
            )
        }
        val claimedIndices = mutableSetOf<Int>()
        val populatedSlots = fixedSlots.map { reference ->
            cards.indices.firstOrNull { index ->
                index !in claimedIndices && isSameWachsendesChaosSlot(reference, cards[index])
            }?.let { index ->
                claimedIndices += index
                cards[index].withWachsendesChaosReference(reference)
            } ?: reference
        }
        // Extra copies/conditions and legacy rows must remain visible without
        // displacing any of the 194 official master-set positions.
        return populatedSlots + cards.filterIndexed { index, _ -> index !in claimedIndices }
    }
    if (references.isNotEmpty()) {
        // A master binder is ordered by printed number, then its confirmed
        // printings (Normal, Holo, Reverse Holo). Existing variants replace
        // precisely their own slot rather than being appended at random.
        // The cached printing row and the canonical row created by a collection add
        // can describe the same source printing. They are one target slot, not two.
        // Coalesce references before claiming owned entries; never alter collection rows.
        val orderedReferences = references.distinctBy(::masterReferenceIdentity).sortedWith(compareBy<CollectionCard>(
            { masterBinderCollectorOrder(it) },
            { masterFinishOrder(it) },
            { it.variant.lowercase(Locale.ROOT) },
        ))
        val claimedEntryIds = mutableSetOf<Long>()
        val slots = orderedReferences.map { reference ->
            cards.filter { owned -> owned.entryId !in claimedEntryIds && isSameMasterPrinting(reference, owned) }
                .maxByOrNull { owned -> if (hasSameMasterSourceIdentity(reference, owned)) 1 else 0 }
                ?.also { claimedEntryIds += it.entryId }
                ?.withReferenceImage(reference.imageUrl)
                ?: reference
        }
        val extraOrder = compareBy<CollectionCard>(
            { masterBinderCollectorOrder(it) }, { masterFinishOrder(it) }, { it.variant },
        )
        val insertBefore = mutableMapOf<Int, MutableList<CollectionCard>>()
        val overflow = mutableListOf<CollectionCard>()
        cards.filter { it.entryId !in claimedEntryIds }.sortedWith(extraOrder).forEach { owned ->
            // Additional conditions/copies or an omitted source printing belong beside the
            // verified card. Conflicting source identities never borrow a numbered slot.
            val matchingIndices = orderedReferences.indices.filter { isSameMasterCard(orderedReferences[it], owned) }
            if (matchingIndices.isEmpty()) {
                overflow += owned
            } else {
                val insertionIndex = matchingIndices.firstOrNull { masterFinishOrder(orderedReferences[it]) > masterFinishOrder(owned) }
                    ?: (matchingIndices.last() + 1)
                insertBefore.getOrPut(insertionIndex) { mutableListOf() } += owned
            }
        }
        return buildList {
            slots.forEachIndexed { index, card ->
                addAll(insertBefore[index].orEmpty())
                add(card)
            }
            addAll(insertBefore[slots.size].orEmpty())
            addAll(overflow)
        }
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

/** Classic Collection displays historic numbers but keeps its official 001-030 binder order. */
private fun masterBinderCollectorOrder(card: CollectionCard): Int {
    if (card.sourceSetId.equals("30th-c", ignoreCase = true)) {
        Regex("(?i)^30th-c-(\\d{1,3})$").matchEntire(card.sourceCardId.orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    }
    return collectorNumberOf(card.number) ?: Int.MAX_VALUE
}

/** Enrich only the displayed, already matched slot; the saved collection entry is unchanged. */
private fun CollectionCard.withReferenceImage(referenceImageUrl: String?): CollectionCard =
    if (imageUrl.isNullOrBlank() && !referenceImageUrl.isNullOrBlank()) copy(imageUrl = referenceImageUrl) else this

/** Stable source identity is display-enriched only; persisted collection rows are untouched. */
private fun CollectionCard.withWachsendesChaosReference(reference: CollectionCard): CollectionCard = copy(
    imageUrl = imageUrl?.takeIf(String::isNotBlank) ?: reference.imageUrl,
    sourceSetId = sourceSetId?.takeIf(String::isNotBlank) ?: reference.sourceSetId,
    sourceCardId = sourceCardId?.takeIf(String::isNotBlank) ?: reference.sourceCardId,
    finish = if (adoptsLegacyWachsendesChaosGold(reference)) reference.finish else finish,
    variant = if (adoptsLegacyWachsendesChaosGold(reference)) reference.variant else variant,
)

private fun CollectionCard.adoptsLegacyWachsendesChaosGold(reference: CollectionCard): Boolean =
    reference.sourceCardId.equals("me04-122", ignoreCase = true) &&
        masterEdition(reference).isNotEmpty() && masterEdition(this).isEmpty()

private fun isSameWachsendesChaosSlot(reference: CollectionCard, owned: CollectionCard): Boolean {
    if (!owned.sourceSetId.isNullOrBlank() && !owned.sourceSetId.equals(reference.sourceSetId, ignoreCase = true)) return false
    if (!owned.sourceCardId.isNullOrBlank() && !owned.sourceCardId.equals(reference.sourceCardId, ignoreCase = true)) return false
    if (owned.sourceSetId.isNullOrBlank() && owned.sourceCardId.isNullOrBlank() && !isWachsendesChaosSet(owned.set)) return false
    if (normalizedCollectorNumber(owned.number) != normalizedCollectorNumber(reference.number)) return false
    val referenceIsReverse = masterFinishOrder(reference) == 2
    val ownedIsReverse = masterFinishOrder(owned) == 2
    if (referenceIsReverse != ownedIsReverse) return false
    val referenceEdition = masterEdition(reference)
    val ownedEdition = masterEdition(owned)
    // Preserve Normal/Holo compatibility for legacy base rows, but never let a
    // stamped or special-foil copy displace the ordinary fixed master slot.
    val legacyGoldBase = reference.sourceCardId.equals("me04-122", ignoreCase = true) &&
        referenceEdition.isNotEmpty() && ownedEdition.isEmpty()
    return legacyGoldBase || (referenceEdition.isEmpty() && ownedEdition.isEmpty()) ||
        (referenceEdition.isNotEmpty() && referenceEdition == ownedEdition)
}

private fun isSameMasterPrinting(reference: CollectionCard, owned: CollectionCard): Boolean {
    if (!isSameMasterCard(reference, owned) || masterFinishOrder(reference) != masterFinishOrder(owned)) return false
    return masterEdition(reference) == masterEdition(owned)
}

// Ordinary finish labels are aliases; a named special edition remains a separate slot.
private fun masterEdition(card: CollectionCard): String = normalizeBinderMetadata(card.variant).let { variant ->
    if (variant in setOf("", "unknown", "unbekannt", "standard", "normal", "holo", "holofoil", "reverse", "reverseholo", "reverseholofoil", "parallel")) "" else variant
}

private fun masterReferenceIdentity(card: CollectionCard): List<String> {
    val source = if (!card.sourceSetId.isNullOrBlank() && !card.sourceCardId.isNullOrBlank()) {
        listOf("source", card.sourceSetId.lowercase(Locale.ROOT), card.sourceCardId.lowercase(Locale.ROOT))
    } else {
        // No guessed identity by name/number: unverified legacy references stay distinct.
        listOf("reference", card.id)
    }
    return source + listOf(card.language.name, masterFinishOrder(card).toString(), masterEdition(card))
}

/** Counts physical master-set slots without treating extra copies as extra progress. */
internal fun completedBinderSlotCount(cards: List<CollectionCard>): Int = cards.distinctBy { card ->
    val sourceIdentity = if (!card.sourceSetId.isNullOrBlank() && !card.sourceCardId.isNullOrBlank()) {
        listOf("source", card.sourceSetId.lowercase(Locale.ROOT), card.sourceCardId.lowercase(Locale.ROOT))
    } else {
        val printedNumber = classic30thSourceLocalId(card)?.let { "classic:$it" }
            ?: normalizedCollectorNumber(card.number)
        if (printedNumber.isNotBlank()) {
            listOf("printed", normalizeBinderMetadata(card.set), printedNumber)
        } else {
            listOf("entry", card.id)
        }
    }
    sourceIdentity + listOf(card.language.name, masterFinishOrder(card).toString(), masterEdition(card))
}.size

private fun hasSameMasterSourceIdentity(reference: CollectionCard, owned: CollectionCard): Boolean =
    !reference.sourceSetId.isNullOrBlank() && !reference.sourceCardId.isNullOrBlank() &&
        reference.sourceSetId.equals(owned.sourceSetId, ignoreCase = true) &&
        reference.sourceCardId.equals(owned.sourceCardId, ignoreCase = true)

/** Detail loading replaces provisional index IDs; an open preview must follow its source slot. */
internal fun refreshedBinderSelection(clicked: CollectionCard, references: List<CollectionCard>): CollectionCard {
    references.firstOrNull { it.id == clicked.id }?.let { return it }
    val sameCard = references.filter { hasSameMasterSourceIdentity(clicked, it) }
    sameCard.firstOrNull { masterFinishOrder(it) == masterFinishOrder(clicked) && masterEdition(it) == masterEdition(clicked) }
        ?.let { return it }
    // The lightweight index has no finish metadata and creates this provisional
    // Normal slot. Only that placeholder may adopt the now-declared base printing.
    if (clicked.id == "${clicked.sourceCardId}-normal" && clicked.finish == "Normal" && clicked.variant == "Standard") {
        sameCard.filter { masterFinishOrder(it) != 2 }.singleOrNull()?.let { return it }
    }
    return clicked
}

private fun isSameMasterCard(reference: CollectionCard, owned: CollectionCard): Boolean {
    if (hasSameMasterSourceIdentity(reference, owned)) return true
    if (!reference.sourceSetId.isNullOrBlank() && !owned.sourceSetId.isNullOrBlank() &&
        !reference.sourceSetId.equals(owned.sourceSetId, ignoreCase = true)) return false
    if (!reference.sourceCardId.isNullOrBlank() && !owned.sourceCardId.isNullOrBlank() &&
        !reference.sourceCardId.equals(owned.sourceCardId, ignoreCase = true)) return false
    if (normalizeBinderMetadata(reference.set) != normalizeBinderMetadata(owned.set)) return false
    if (isClassic30thCollection(reference) || isClassic30thCollection(owned)) {
        val referenceLocalId = classic30thSourceLocalId(reference) ?: return false
        val ownedLocalId = classic30thSourceLocalId(owned) ?: return false
        return referenceLocalId == ownedLocalId
    }
    if (reference.number.any(Char::isDigit) && owned.number.any(Char::isDigit)) {
        return normalizedCollectorNumber(reference.number).equals(normalizedCollectorNumber(owned.number), ignoreCase = true)
    }
    // Legacy Cardmarket references can lack numbers. A product identifies the card,
    // while isSameMasterPrinting still verifies finish and edition independently.
    return reference.externalProductId != null && reference.externalProductId == owned.externalProductId
}

private fun masterFinishOrder(card: CollectionCard): Int = masterFinishOrder(card.finish, card.variant)

private fun masterFinishOrder(finish: String, variant: String): Int = when {
    finish.contains("reverse", true) || variant.contains("reverse", true) || variant.contains("parallel", true) -> 2
    finish.contains("holo", true) -> 1
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
private fun ArrivalMoment(ready: Boolean, onContinue: () -> Unit) {
    LaunchedEffect(ready) {
        if (ready) onContinue()
    }
    DomiDexBackdrop(
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                DomiDexBrandMark(size = 92.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("DomiDex", color = VaultText, fontSize = 36.sp, fontWeight = FontWeight.Black, letterSpacing = (-.6).sp)
                    Text("DEINE KARTEN. DEIN BINDER.", color = MewRose, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                    Text("DomiDex wird vorbereitet…", color = VaultTextMuted, fontSize = 13.sp)
                }
            }
            LinearProgressIndicator(
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 58.dp, vertical = 50.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(99.dp)),
                color = MewPlum,
                trackColor = VaultOutlineSoft,
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

@Composable internal fun DashboardScreen(portfolio: PortfolioSummary, binders: List<BinderSummary>, contentUpdates: ContentUpdateState, focusChoice: String, onFocusChoice: (String) -> Unit, openBinder: () -> Unit, openScanner: () -> Unit) {
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(CardVaultSpacing.section)) {
    item { DomiScreenTitle(stringResource(R.string.dashboard_title), eyebrow = stringResource(R.string.dashboard_eyebrow), subtitle = stringResource(R.string.dashboard_subtitle), trailing = { DomiDexBrandMark(size = 42.dp) }) }
    item { PortfolioHero(portfolio, focusChoice, onFocusChoice) }
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            DashboardQuickAction("Scannen", DomiIconKind.SCAN, Modifier.weight(1f), openScanner)
            DashboardQuickAction("Binder", DomiIconKind.BINDER, Modifier.weight(1f), openBinder, highlighted = true)
        }
    }
    item { GradingWebNotice() }
    item { CurrentBinderValueCard(binders, openBinder) }
    item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { StatCard(stringResource(R.string.stat_cards), portfolio.totalCards.toString(), Modifier.weight(1f)); StatCard(stringResource(R.string.stat_unique), portfolio.cards.size.toString(), Modifier.weight(1f)); StatCard(stringResource(R.string.stat_languages), portfolio.cards.map { it.language }.distinct().size.toString(), Modifier.weight(1f)) } }
    if (portfolio.cards.isNotEmpty()) {
        item { DomiSectionTitle(stringResource(R.string.recent_cards)) }
        items(portfolio.cards.take(3)) { CardRow(it) }
    }
    item { DiscordSupportCard(context) }
    item { Text(stringResource(R.string.catalog_growth_note, contentUpdates.revision), color = VaultTextMuted, fontSize = 10.sp, lineHeight = 14.sp) }
    }
}

@Composable internal fun GradingWebNotice(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.grading_web_notice), modifier = modifier, color = VaultTextMuted, fontSize = 12.sp)
}

@Composable private fun DashboardQuickAction(label: String, icon: DomiIconKind, modifier: Modifier, onClick: () -> Unit, highlighted: Boolean = false) {
    DomiGlassCard(modifier, highlighted = highlighted, onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(34.dp).background(if (highlighted) MewPlumDeep else VaultNightRaised, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                DomiLineIcon(icon, if (highlighted) MewRose else MewIce, Modifier.size(18.dp))
            }
            Text(label, color = VaultText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Shows only locally available Cardmarket data; missing prices are never estimated. */
@Composable private fun CurrentBinderValueCard(binders: List<BinderSummary>, openBinder: () -> Unit) {
    val bindersWithCards = binders.filter { it.ownedCards > 0 }
    val pricedBinders = bindersWithCards.filter { it.lastPriceUpdatedAtEpochMillis != null }
    val currentValue = pricedBinders.sumOf { it.marketValueEur }
    DomiGlassCard(Modifier.fillMaxWidth(), onClick = openBinder) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(CardVaultSpacing.card),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("AKTUELLER BINDERWERT", color = MewRose, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                when {
                    bindersWithCards.isEmpty() -> Text("Noch keine Karten im Binder", color = VaultTextMuted, fontSize = 13.sp)
                    pricedBinders.isEmpty() -> Text("Trendpreise werden noch synchronisiert", color = VaultTextMuted, fontSize = 13.sp)
                    else -> Text(euro(currentValue), color = VaultText, fontSize = 25.sp, fontWeight = FontWeight.Black)
                }
                if (bindersWithCards.isNotEmpty()) {
                    Text(
                        if (pricedBinders.isEmpty()) "Sobald Cardmarket-Trendpreise vorliegen, erscheint hier der echte Gesamtwert."
                        else "Cardmarket-Trendpreise: ${pricedBinders.size}/${bindersWithCards.size} Binder aktuell",
                        color = VaultTextMuted,
                        fontSize = 10.sp,
                    )
                }
            }
            Box(Modifier.size(40.dp).background(MewPlumDeep.copy(alpha = .65f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                DomiLineIcon(DomiIconKind.BINDER, MewRose, Modifier.size(20.dp))
            }
        }
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
    val auth = remember { FirebaseAuth.getInstance() }
    val gateway = remember(context, auth) { FirebaseGoogleAuthGateway(context, auth) }
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(gateway.currentAccount()) }
    var working by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(auth, gateway) {
        val listener = FirebaseAuth.AuthStateListener {
            account = gateway.currentAccount()
            feedback = null
        }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    GoogleAccountCardContent(
        account, working, feedback,
        onSignOut = {
            working = true
            feedback = null
            scope.launch {
                try {
                    feedback = when (gateway.signOut()) {
                        GoogleLogoutResult.Success -> null
                        is GoogleLogoutResult.CredentialStateWarning -> R.string.account_logout_credential_warning
                    }
                    account = gateway.currentAccount()
                } finally { working = false }
            }
        },
        onSwitchAccount = {
            working = true
            feedback = null
            scope.launch {
                try {
                    when (gateway.switchAccount()) {
                        is GoogleLoginResult.Success -> account = gateway.currentAccount()
                        GoogleLoginResult.Cancelled -> feedback = R.string.account_login_cancelled
                        is GoogleLoginResult.Failed -> feedback = R.string.account_switch_failed
                    }
                } finally { working = false }
            }
        },
        onSignIn = {
            working = true
            feedback = null
            scope.launch {
                try {
                    when (gateway.signIn()) {
                        is GoogleLoginResult.Success -> account = gateway.currentAccount()
                        GoogleLoginResult.Cancelled -> feedback = R.string.account_login_cancelled
                        is GoogleLoginResult.Failed -> feedback = R.string.account_login_failed
                    }
                } finally { working = false }
            }
        },
    )
}

@Composable internal fun GoogleAccountCardContent(
    account: GoogleAccountState,
    working: Boolean,
    feedback: Int?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSwitchAccount: () -> Unit,
) {
    OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = VaultNightRaised), shape = CardVaultShapes.card) {
        Column(Modifier.padding(CardVaultSpacing.card), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(stringResource(R.string.account_title), fontWeight = FontWeight.Bold)
            Text(
                if (account.isSignedIn) "Mit Google angemeldet"
                else stringResource(R.string.account_signed_out),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
            )
            if (account.isSignedIn) {
                account.displayName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                account.email?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                OutlinedButton(onClick = onSwitchAccount, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_switch)) }
                OutlinedButton(onClick = onSignOut, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_sign_out)) }
            } else {
                Button(onClick = onSignIn, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (working) R.string.account_login_running else R.string.account_login_google)) }
            }
            feedback?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
            Text(stringResource(R.string.account_no_role), color = VaultTextMuted, fontSize = 10.sp)
        }
    }
}

@Composable private fun SupportInfoCard() {
    OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = VaultNightRaised), shape = CardVaultShapes.card) {
        Column(Modifier.padding(CardVaultSpacing.card), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            SupportSettingsContent()
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
    DomiGlassCard(Modifier.fillMaxWidth(), highlighted = true) {
        Box(Modifier.fillMaxWidth().height(CardVaultSpacing.heroHeight).background(Brush.linearGradient(listOf(Color(0xFF161124), Color(0xFF0D0D18), Color(0xFF0A131B))))) {
            Canvas(Modifier.matchParentSize().clearAndSetSemantics { }) {
                drawCircle(MewIce.copy(alpha = .10f), radius = size.minDimension * .52f, center = Offset(size.width * .84f, size.height * .20f))
                drawCircle(MewRose.copy(alpha = .08f), radius = size.minDimension * .34f, center = Offset(size.width * .60f, size.height * .92f))
            }
            Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Surface(color = MewPlumDeep.copy(alpha = .72f), shape = CardVaultShapes.pill, border = BorderStroke(1.dp, MewRose.copy(alpha = .32f))) { Text(stringResource(R.string.portfolio_private_badge), Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = VaultText, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    Text("Gesamtportfolio · alle Binder", color = VaultTextMuted, fontSize = 13.sp)
                    Text(if (portfolio.hasPricedCards) euro(portfolio.totalValue) else "Kein Trendpreis verfügbar", fontSize = if (portfolio.hasPricedCards) 30.sp else 18.sp, fontWeight = FontWeight.Black, color = VaultText, maxLines = 1)
                    Text(
                        if (portfolio.history.size > 1) "Preisänderung des heutigen Bestands: ${String.format(Locale.GERMANY, "%+.1f", portfolio.changePercent)} %" else "Noch nicht genügend echte Preisverlaufsdaten",
                        color = if (portfolio.changePercent >= 0) VaultPositive else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                    )
                    Text(
                        if (portfolio.unpricedCards == 0) "Cardmarket-Trendpreis für alle ${portfolio.pricedCards} Karten verfügbar"
                        else "Trendpreis: ${portfolio.pricedCards} von ${portfolio.totalCards} Karten",
                        color = VaultTextMuted,
                        fontSize = 10.sp,
                    )
                    TextButton(onClick = { choosingFocus = true }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) { Text(stringResource(if (focusChoice == "auto") R.string.focus_automatic else R.string.focus_manual), color = MewRose, fontSize = 10.sp) }
                }
                InteractiveFocusCard(
                    focusCard,
                    Modifier.size(116.dp, 164.dp),
                    interactive = true,
                    sensorEnabled = false,
                )
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
    sensorEnabled: Boolean = interactive,
) {
    val context = LocalContext.current
    val effect = remember(card?.id, card?.finish, card?.variant, card?.rarity, card?.effectOverride) {
        CardEffectResolver.resolve(card)
    }
    var sensorX by remember { mutableFloatStateOf(0f) }; var sensorY by remember { mutableFloatStateOf(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }; var dragY by remember { mutableFloatStateOf(0f) }
    val settledDragX by animateFloatAsState(dragX, spring(stiffness = androidx.compose.animation.core.Spring.StiffnessLow), label = "card-tilt-x")
    val settledDragY by animateFloatAsState(dragY, spring(stiffness = androidx.compose.animation.core.Spring.StiffnessLow), label = "card-tilt-y")
    DisposableEffect(context, sensorEnabled, effect.profile) {
        if (!sensorEnabled || effect.profile == CardEffectProfile.NONE) return@DisposableEffect onDispose { }
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
    Box(modifier.graphicsLayer {
            rotationX = -tiltY * 10f
            rotationY = tiltX * 10f
            cameraDistance = 14 * density
            compositingStrategy = CompositingStrategy.Offscreen
        }
        .pointerInput(interactive) {
            if (interactive) detectDragGestures(
                onDragStart = { },
                onDragEnd = { dragX = 0f; dragY = 0f },
                onDragCancel = { dragX = 0f; dragY = 0f },
            ) { change, _ ->
                change.consume()
                dragX = ((change.position.x / size.width) - .5f).coerceIn(-.5f, .5f) * 2f
                dragY = ((change.position.y / size.height) - .5f).coerceIn(-.5f, .5f) * 2f
            }
        }
        .clip(CardVaultShapes.card)
        .background(VaultNightRaised, CardVaultShapes.card)) {
        if (card?.imageUrl != null) AsyncImage(card.imageUrl, stringResource(R.string.focus_card_description, card.name), Modifier.fillMaxSize()) else NeutralCardFace(Modifier.fillMaxSize())
        if (interactive) HoloEffectLayer(effect, tiltX, tiltY, interactive, Modifier.matchParentSize().clearAndSetSemantics { })
        else StaticCardEffectLayer(effect, Modifier.matchParentSize().clearAndSetSemantics { })
    }
}

@Composable private fun NeutralCardFace(modifier: Modifier = Modifier) = Canvas(modifier.background(Brush.linearGradient(listOf(Color(0xFF1B2437), Color(0xFF352B4B))), CardVaultShapes.card).padding(10.dp)) {
    drawRoundRect(VaultOutline, style = Stroke(2f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f))
    val center = Offset(size.width / 2, size.height * .42f); val r = size.minDimension * .16f
    val gem = Path().apply { moveTo(center.x, center.y - r); lineTo(center.x + r, center.y); lineTo(center.x, center.y + r); lineTo(center.x - r, center.y); close() }
    drawPath(gem, MewIce.copy(alpha = .72f), style = Stroke(4f)); drawLine(VaultOutline, Offset(size.width * .2f, size.height * .72f), Offset(size.width * .8f, size.height * .72f), 3f); drawLine(VaultOutline.copy(alpha = .6f), Offset(size.width * .28f, size.height * .8f), Offset(size.width * .72f, size.height * .8f), 3f)
}

@Composable private fun StatCard(label: String, value: String, modifier: Modifier) = DomiGlassCard(modifier) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = VaultText, maxLines = 1)
        Text(label, fontSize = 9.sp, color = VaultTextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

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
@Composable private fun CardPriceHistorySection(
    card: CollectionCard,
    history: List<CardPricePoint>,
    resolvedTrendPrice: Double? = null,
) {
    // A binder reference may have been saved before its own price snapshot was
    // persisted. In that case show the verified Cardmarket product price that
    // the preview just resolved, instead of presenting a misleading empty state.
    val current = history.lastOrNull()?.valueEur
        ?: card.currentValueEur.takeIf { card.priceUpdatedAtEpochMillis != null && it > 0.0 }
        ?: resolvedTrendPrice
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
            Text("Für diese konkrete deutsche Variante ist noch kein eindeutig verknüpfter Cardmarket-Trendpreis gespeichert.", color = VaultTextMuted, fontSize = 11.sp)
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

internal fun collectionCardsByDisplayedMarketValue(cards: List<CollectionCard>): List<CollectionCard> {
    fun displayedValue(card: CollectionCard): Double? {
        if (card.priceUpdatedAtEpochMillis == null) return null
        return (card.currentValueEur * card.quantity).takeIf(Double::isFinite)
    }

    return cards.sortedWith(
        compareByDescending<CollectionCard> { displayedValue(it) != null }
            .thenByDescending { displayedValue(it) ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.name.lowercase(Locale.ROOT) }
            .thenBy { it.set.lowercase(Locale.ROOT) }
            .thenBy { it.number }
            .thenBy { it.entryId },
    )
}

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
    val filtered = remember(cards, selectedBinderId, query) {
        collectionCardsByDisplayedMarketValue(
            cards.filter { (selectedBinderId == null || it.binderId == selectedBinderId) && (it.name.contains(query, true) || it.set.contains(query, true)) },
        )
    }
    LazyColumn(contentPadding = PaddingValues(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(CardVaultSpacing.card)) {
    item {
        val count = cards.sumOf { it.quantity }
        DomiScreenTitle(
            title = if (selectedBinderId == null) "Sammlung" else binders.firstOrNull { it.id == selectedBinderId }?.name ?: "Sammlung",
            eyebrow = stringResource(R.string.collection_eyebrow),
            subtitle = pluralStringResource(R.plurals.collection_cards_count, count, count) + " · " + pluralStringResource(R.plurals.collection_entries_count, cards.size, cards.size),
            trailing = { DomiDexBrandMark(size = 40.dp) },
        )
    }
    if (selectedBinderId == null && binders.isNotEmpty()) item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DomiSectionTitle("Nach Binder")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(binders, key = { it.id }) { binder ->
                    DomiGlassCard(Modifier.widthIn(min = 166.dp, max = 220.dp), onClick = { selectedBinderId = binder.id }) {
                        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(34.dp).background(MewPlumDeep.copy(alpha = .65f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { DomiLineIcon(DomiIconKind.BINDER, MewRose, Modifier.size(18.dp)) }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(binder.name, color = VaultText, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(R.string.binder_progress, binder.ownedCards, binder.uniqueCards), color = VaultTextMuted, fontSize = 10.sp)
                                Text(stringResource(R.string.binder_market_value, euro(binder.marketValueEur)), color = MewIce, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
    else item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { selectedBinderId = null }) { Text(stringResource(R.string.action_back) + " · " + stringResource(R.string.binder_all)) }
            TextButton(onClick = { binderDeletionStage = 1 }) { Text(stringResource(R.string.binder_manage)) }
        }
    }
    item { Button(onClick = { choosingAddMode = true }, modifier = Modifier.fillMaxWidth().height(CardVaultSpacing.buttonHeight), shape = CardVaultShapes.card) { DomiLineIcon(DomiIconKind.ADD, MaterialTheme.colorScheme.onPrimary, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.collection_add), fontWeight = FontWeight.Bold) } }
    addOutcome?.let { outcome -> item { Text(stringResource(if (outcome == AddCardOutcome.QUANTITY_INCREMENTED) R.string.add_outcome_incremented else R.string.add_outcome_created), color = VaultPositive, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) } }
    item { OutlinedTextField(query, { query = it }, leadingIcon = { DomiLineIcon(DomiIconKind.SEARCH, VaultTextMuted, Modifier.size(18.dp)) }, placeholder = { Text(stringResource(R.string.collection_search), color = VaultTextMuted) }, singleLine = true, shape = CardVaultShapes.field, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MewRose, cursorColor = MewRose, unfocusedBorderColor = VaultOutlineSoft, unfocusedContainerColor = VaultNightRaised, focusedContainerColor = VaultNightRaised), modifier = Modifier.fillMaxWidth()) }
    if (filtered.isEmpty()) item { DomiEmptyState("Keine Karten gefunden", if (cards.isEmpty()) "Füge deine erste Karte über Set-Auswahl, Suche oder Scanner hinzu." else "Passe deine Suche oder die Binder-Auswahl an.") }
    else item { DomiSectionTitle(if (selectedBinderId == null) "Alle Karten" else "Karten im Binder") }
    items(filtered, key = { it.entryId }) { card -> CardRow(card) { viewing = card } }; item { DataNote() }
    }
    if (choosingAddMode) AddModeDialog(onDismiss = { choosingAddMode = false }, onCatalog = { choosingAddMode = false; choosingCatalogCard = true }, onManual = { choosingAddMode = false; catalogDraft = null; adding = true })
    if (choosingCatalogCard) CatalogProductPicker(repository, onDismiss = { choosingCatalogCard = false }, onSelected = { catalogDraft = it; choosingCatalogCard = false; adding = true })
    if (adding) ManualCardDialog(
        repository = repository,
        initialCatalog = catalogDraft,
        onDismiss = { adding = false; catalogDraft = null },
        onSaved = { addOutcome = it },
    )
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

@Composable internal fun CardPreviewDialog(
    card: CollectionCard,
    repository: CollectionRepository,
    sourceSetCards: List<PokemonVisualCard> = emptyList(),
    visualCatalog: PokemonVisualCatalog = remember { PokemonVisualCatalog() },
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)? = null,
    onAdd: ((CatalogSearchResult) -> Unit)? = null,
    addInProgress: Boolean = false,
    onChangeSelection: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val effect = remember(card.id, card.finish, card.variant, card.rarity, card.effectOverride) {
        CardEffectResolver.resolve(card)
    }
    val historyFlow = remember(repository, card.entryId, card.id, card.finish) { repository.priceHistory(card) }
    val priceHistory by historyFlow.collectAsState(initial = emptyList())
    var referenceProducts by remember(card.id, card.marketExpansionId) { mutableStateOf<List<CatalogSearchResult>>(emptyList()) }
    var selectedMarketProductId by rememberSaveable(card.entryId, card.sourceSetId, card.sourceCardId, card.finish, card.variant) { mutableStateOf(card.externalProductId) }
    var priceChoicesExpanded by rememberSaveable(card.entryId) { mutableStateOf(false) }
    var priceLookupComplete by remember(card.id, card.sourceCardId, card.finish, card.variant) { mutableStateOf(false) }
    val catalogSync by repository.catalogSyncStatus.collectAsState(initial = CatalogSyncStatus("IDLE", null, null, 0, 0, null))
    val isReferenceAdd = onAdd != null
    LaunchedEffect(card.id, card.externalProductId, card.marketExpansionId, card.marketSearchName, card.finish, card.variant, sourceSetCards, catalogSync.lastSuccessAtEpochMillis) {
        priceLookupComplete = false
        val query = card.marketSearchName ?: card.name
        referenceProducts = if (isReferenceAdd) {
            val sourceCard = sourceSetCards.firstOrNull {
                it.set.id.equals(card.sourceSetId, ignoreCase = true) && it.id.equals(card.sourceCardId, ignoreCase = true)
            }
            sourceCard?.let { source ->
                val detailed = try { visualCatalog.withDeclaredPrintVariants(source) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { source }
                val matches = try { loadVisualCardmarketCandidates(detailed, repository) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyList() }
                binderReferenceCardmarketProducts(card, detailed, sourceSetCards, matches)
            }.orEmpty()
        } else if (card.externalProductId != null) {
            // Reopening an owned card must retain its explicit product, not pick
            // the first cheaper main-set namesake returned by a name search.
            val products = try { repository.catalogProducts(listOf(card.externalProductId)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
            products.map { it.copy(trendPriceEur = it.realTrendFor(card.finish)) }
        } else {
            val matches = runCatching { repository.searchCatalog(query, 100) }.getOrDefault(emptyList())
            val linkedProduct = card.externalProductId?.let { productId -> matches.firstOrNull { it.productId == productId } }
            val verifiedExpansion = card.marketExpansionId ?: linkedProduct?.expansionId
            verifiedExpansion?.let { expansion ->
                matches.filter { it.expansionId == expansion && isSameNamePriceCandidate(query, it) }
            } ?: linkedProduct?.let(::listOf).orEmpty()
        }
        priceLookupComplete = true
    }
    val marketPriceChoices = binderCardmarketPriceChoices(card, referenceProducts)
    val preferredMarketProduct = defaultBinderCardmarketProduct(card, referenceProducts)
        ?.takeIf { it.trendPriceEur != null }
    val selectedMarketProduct = marketPriceChoices.firstOrNull { it.productId == selectedMarketProductId }
        ?: preferredMarketProduct
    val storedTrendPrice = card.currentValueEur.takeIf { card.priceUpdatedAtEpochMillis != null && it > 0.0 }
    val displayedTrendPrice = selectedMarketProduct?.trendPriceEur ?: storedTrendPrice
    val priceDate = card.priceUpdatedAtEpochMillis?.let { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, Locale.GERMANY).format(java.util.Date(it)) }
    AlertDialog(
        onDismissRequest = { if (!addInProgress) onDismiss() },
        containerColor = VaultNightRaised,
        shape = CardVaultShapes.feature,
        title = { DomiScreenTitle(card.name, eyebrow = "Kartendetails", subtitle = "${card.set} · ${card.number}") },
        text = { LazyColumn(Modifier.fillMaxWidth().heightIn(max = if (isReferenceAdd) 430.dp else 590.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { InteractiveFocusCard(card, Modifier.size(174.dp, 246.dp), interactive = true) }
        item {
            DomiGlassCard(Modifier.fillMaxWidth(), highlighted = displayedTrendPrice != null) {
                Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(36.dp).background(MewPlumDeep.copy(alpha = .68f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { DomiLineIcon(DomiIconKind.MARKET, MewRose, Modifier.size(19.dp)) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("CARDMARKET-TRENDPREIS", color = VaultTextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
                        Text(displayedTrendPrice?.let(::euro) ?: if (priceLookupComplete) "Preis nicht verfügbar" else "Wird abgeglichen …", color = if (displayedTrendPrice != null) VaultText else VaultTextMuted, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    }
                    if (displayedTrendPrice != null) DomiLineIcon(DomiIconKind.CHECK, VaultPositive, Modifier.size(18.dp))
                }
            }
        }
        if (isReferenceAdd) item {
            BinderDestinationSummary(card.set, card.number, card.finish,
                sourceConfirmed = !card.sourceSetId.isNullOrBlank() && !card.sourceCardId.isNullOrBlank(),
                onChange = onChangeSelection,
                enabled = !addInProgress)
        }
        if (!isReferenceAdd) {
            if (card.entryId > 0L && marketPriceChoices.isNotEmpty()) item {
                DomiGlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { priceChoicesExpanded = !priceChoicesExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            DomiLineIcon(DomiIconKind.MARKET, MewIce, Modifier.size(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Cardmarket-Preis auswählen", color = VaultText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Standardmäßig wird der Trendpreis verwendet.", color = VaultTextMuted, fontSize = 10.sp)
                            }
                            Text(if (priceChoicesExpanded) "▲" else "▼", color = VaultTextMuted, fontSize = 11.sp)
                        }
                        if (priceChoicesExpanded) {
                            HorizontalDivider(color = VaultOutlineSoft)
                            LazyColumn(
                                Modifier.fillMaxWidth().heightIn(max = 190.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(marketPriceChoices, key = { it.productId }) { product ->
                                    val selected = product.productId == selectedMarketProduct?.productId
                                    Surface(
                                        onClick = {
                                            selectedMarketProductId = product.productId
                                            scope.launch { repository.linkCatalogProduct(card.entryId, product.productId) }
                                        },
                                        color = if (selected) MewPlumDeep.copy(alpha = .72f) else Color.Transparent,
                                        shape = CardVaultShapes.cardCompact,
                                        border = BorderStroke(1.dp, if (selected) MewRoseSoft else VaultOutlineSoft),
                                    ) {
                                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            RadioButton(
                                                selected = selected,
                                                onClick = null,
                                                colors = RadioButtonDefaults.colors(selectedColor = MewRose, unselectedColor = VaultTextMuted),
                                            )
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(product.name, color = VaultText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                Text(product.visualSetName ?: "Cardmarket-Erweiterung ${product.expansionId}", color = VaultTextMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            Text(euro(product.trendPriceEur!!), color = MewIce, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.card_preview_variant, card.language.label, card.finish, card.variant), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            item { Text("Visueller Effekt: ${effect.profile.label}", color = MewIce, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            item { HorizontalDivider(color = VaultOutline) }
            item { CardPriceHistorySection(card, priceHistory, displayedTrendPrice) }
            item { PriceSourcesSection(card, priceDate, context) }
        }
    } }, confirmButton = {
        Row {
            if (onAdd != null) Button(enabled = preferredMarketProduct != null && !addInProgress, onClick = { onAdd(preferredMarketProduct!!) }) { Text(if (addInProgress) "Wird hinzugefügt …" else "In diesen Binder hinzufügen") }
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Aus Binder entfernen", color = MaterialTheme.colorScheme.error) }
            if (onEdit != null) TextButton(onClick = onEdit) { Text(stringResource(R.string.card_preview_edit)) }
        }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !addInProgress) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun isExactBinderPriceMatch(reference: CollectionCard, product: CatalogSearchResult): Boolean {
    val sameName = normalizeCardName(reference.marketSearchName ?: reference.name) == normalizeCardName(product.name)
    val sameNumber = normalizedCollectorNumber(reference.number) == normalizedCollectorNumber(product.collectorNumber.orEmpty())
    val sameSet = normalizeBinderMetadata(reference.set) == normalizeBinderMetadata(product.visualSetName ?: product.expansionId)
    return sameName && sameNumber && sameSet
}

/**
 * Prefer a price-bearing exact product; otherwise retain Cardmarket's stable
 * catalogue order for the verified set/name match.  This makes adding a Binder
 * card one action while retaining the saved external product link for edits.
 */
private fun defaultBinderCardmarketProduct(
    reference: CollectionCard,
    candidates: List<CatalogSearchResult>,
): CatalogSearchResult? = candidates
    .sortedWith(
        compareByDescending<CatalogSearchResult> { isExactBinderPriceMatch(reference, it) }
            .thenByDescending { it.trendPriceEur != null }
            .thenBy { it.productId },
    )
    .firstOrNull()

/**
 * An owned Binder card may be relinked only to source-backed, priced Cardmarket
 * products that already passed the verified set/name filter. Trend remains the
 * default; this list merely lets the collector choose another real printing.
 */
internal fun binderCardmarketPriceChoices(
    reference: CollectionCard,
    candidates: List<CatalogSearchResult>,
): List<CatalogSearchResult> = candidates
    .asSequence()
    .filter { it.hasMarketProduct && it.trendPriceEur != null }
    .distinctBy { it.productId }
    .sortedWith(
        compareByDescending<CatalogSearchResult> { it.productId == reference.externalProductId }
            .thenByDescending { isExactBinderPriceMatch(reference, it) }
            .thenBy { it.name.lowercase(Locale.ROOT) }
            .thenBy { it.productId },
    )
    .toList()

private fun isSameNamePriceCandidate(name: String, product: CatalogSearchResult): Boolean =
    // Cardmarket frequently appends a printing/variant description in brackets.
    // A binder reference contains the printed card name only, so compare the
    // same canonical base name used by the visual-card picker.
    cardmarketNameMatches(product.name, name)

private fun normalizedCollectorNumber(value: String): String = value
    .substringBefore('/')
    .filter(Char::isLetterOrDigit)
    .trimStart('0')
    .ifBlank { "0" }

private fun isClassic30thCollection(card: CollectionCard): Boolean {
    if (card.sourceSetId.equals("30th-c", ignoreCase = true)) return true
    return normalizeBinderMetadata(card.set) in setOf(
        "30jahreklassischesammlung",
        "30yearsclassiccollection",
        "30thclassiccollection",
    )
}

private fun classic30thSourceLocalId(card: CollectionCard): String? {
    if (!isClassic30thCollection(card)) return null
    Regex("(?i)^30th-c-(\\d{1,3})$").matchEntire(card.sourceCardId.orEmpty())
        ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..30 }?.let { return it.toString() }
    classic30thLocalIdForPrintedCollector(card.number)?.let { return it }
    if ('/' !in card.number) {
        card.number.filter(Char::isDigit).toIntOrNull()?.takeIf { it in 1..30 }?.let { return it.toString() }
    }
    return null
}

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
    Text("Portfoliowert nutzt ausschließlich den Cardmarket-Trendpreis des ausdrücklich verknüpften Produkts. Normal und Holo nutzen den Produkt-Trendpreis, Reverse Holo den zugehörigen Reverse-Preis. Ohne Verknüpfung wird kein Wert geschätzt.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
}

private fun openOfficialPage(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable private fun CardRow(card: CollectionCard, onClick: (() -> Unit)? = null) {
    val effect = remember(card.id, card.finish, card.variant, card.rarity, card.effectOverride) {
        CardEffectResolver.resolve(card)
    }
    DomiGlassCard(Modifier.fillMaxWidth(), onClick = onClick) { Row(Modifier.padding(CardVaultSpacing.card), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CardVaultSpacing.card)) {
    Box(Modifier.size(54.dp, 75.dp).background(Color(card.accent), CardVaultShapes.thumbnail), contentAlignment = Alignment.Center) {
        if (card.imageUrl != null) AsyncImage(model = card.imageUrl, contentDescription = stringResource(R.string.collection_card_image_description, card.name, card.number), modifier = Modifier.fillMaxSize())
        else Text(card.name.take(1), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
        StaticCardEffectLayer(effect, Modifier.matchParentSize().clearAndSetSemantics { })
    }
    Column(Modifier.weight(1f)) { Text(card.name, fontWeight = FontWeight.Bold); Text("${card.set} · ${card.number}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(stringResource(R.string.card_metadata, card.language.label, card.condition, card.quantity), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp); if (card.externalProductId != null) Text(stringResource(R.string.catalog_linked_badge), color = MewIce, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
    Column(horizontalAlignment = Alignment.End) { Text(if (card.priceUpdatedAtEpochMillis == null) stringResource(R.string.no_market_price) else euro(card.currentValueEur * card.quantity), fontWeight = FontWeight.Bold, fontSize = if (card.priceUpdatedAtEpochMillis == null) 11.sp else 14.sp); if (card.priceUpdatedAtEpochMillis != null) Text((if (card.changePercent >= 0) "+" else "") + "%.1f%%".format(Locale.US, card.changePercent), color = if (card.changePercent >= 0) VaultPositive else MaterialTheme.colorScheme.error, fontSize = 12.sp) }
} } }

@Composable private fun AddModeDialog(onDismiss: () -> Unit, onCatalog: () -> Unit, onManual: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, title = { Text(stringResource(R.string.add_mode_title)) },
    text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onCatalog, modifier = Modifier.fillMaxWidth(), shape = CardVaultShapes.card) { Text(stringResource(R.string.add_from_catalog), fontWeight = FontWeight.Bold) }
        Text(stringResource(R.string.add_from_catalog_hint), color = VaultTextMuted, fontSize = 12.sp)
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth(), shape = CardVaultShapes.card) { Text(stringResource(R.string.add_without_catalog)) }
        Text(stringResource(R.string.add_without_catalog_hint), color = VaultTextMuted, fontSize = 12.sp)
    } }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
)

@Composable internal fun CatalogProductPicker(repository: CollectionRepository, onDismiss: () -> Unit,
    visualCatalog: PokemonVisualCatalog = remember { PokemonVisualCatalog() }, onSelected: (CatalogSearchResult) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var sets by remember { mutableStateOf<List<PokemonSet>>(emptyList()) }
    var selectedSet by remember { mutableStateOf<PokemonSet?>(null) }
    var cards by remember { mutableStateOf<List<PokemonVisualCard>>(emptyList()) }
    var selectedCard by remember { mutableStateOf<PokemonVisualCard?>(null) }
    var products by remember { mutableStateOf<List<CatalogSearchResult>>(emptyList()) }
    var setCardPrices by remember { mutableStateOf<Map<String, VisualCardMarketPrice>>(emptyMap()) }
    var resolvedCardmarketExpansionId by remember { mutableStateOf<String?>(null) }
    var searchByPokemon by rememberSaveable { mutableStateOf(false) }
    var showAllSets by rememberSaveable { mutableStateOf(false) }
    var setConfirmationHint by rememberSaveable { mutableStateOf(false) }
    var pokemonResults by remember { mutableStateOf<List<CatalogSearchResult>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }; var error by remember { mutableStateOf(false) }
    val catalogSync by repository.catalogSyncStatus.collectAsState(initial = CatalogSyncStatus("IDLE", null, null, 0, 0, null))
    // The initial Cardmarket import runs in the background. Repeat the active
    // lookup once it finishes so a search opened immediately after app launch
    // receives its real trend prices without requiring the user to type again.
    val catalogRevision = catalogSync.lastSuccessAtEpochMillis
    LaunchedEffect(selectedSet) {
        cards = emptyList()
        loading = true; error = false; query = ""; resolvedCardmarketExpansionId = selectedSet?.cardmarketExpansionId
        runCatching { if (selectedSet == null) sets = visualCatalog.sets() else cards = visualCatalog.cards(selectedSet!!) }
            .onFailure { error = true }
        loading = false
    }
    LaunchedEffect(selectedCard?.id, catalogRevision, resolvedCardmarketExpansionId) {
        val card = selectedCard ?: return@LaunchedEffect
        loading = true; error = false; products = emptyList()
        // The set index has no declared foil data. Load the selected card's existing
        // detail record before handing its physical printing to the add dialog.
        val detailed = try { visualCatalog.withDeclaredPrintVariants(card) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { card }
        if (selectedCard?.id != card.id) return@LaunchedEffect
        selectedCard = detailed
        // Only a source-declared product/printing identity can supply the preview price.
        runCatching {
            val candidates = loadVisualCardmarketCandidates(detailed, repository)
            visualCardmarketProducts(detailed, cards, candidates, resolvedCardmarketExpansionId)
        }.onSuccess { products = it }
            .onFailure { products = emptyList(); error = true }
        loading = false
    }
    LaunchedEffect(cards, selectedSet, catalogRevision) {
        if (selectedSet == null || cards.isEmpty()) { setCardPrices = emptyMap(); return@LaunchedEffect }
        // Resolve one source-verified Cardmarket printing per visual card. We never
        // use a similarly named product from another expansion as a fallback.
        val resolved = withContext(Dispatchers.IO) {
            // Batch exact product identities; no speculative per-name SQL probes.
            val declaredProductIds = cards.flatMap { card ->
                card.printVariants.mapNotNull(PokemonPrintVariant::cardmarketProductId)
            }.distinct()
            val declaredProducts = repository.catalogProducts(declaredProductIds)
            val expansionId = selectedSet?.cardmarketExpansionId
            expansionId to cards.associate { card ->
                val options = visualCardmarketProducts(card, cards, declaredProducts, expansionId)
                val product = preferredVisualCardmarketProduct(card, options)
                card.id to VisualCardMarketPrice(product?.trendPriceEur, product != null, product != null)
            }
        }
        resolvedCardmarketExpansionId = resolved.first
        setCardPrices = resolved.second
    }
    LaunchedEffect(query, searchByPokemon, selectedSet, catalogRevision) {
        if (selectedSet == null) {
            pokemonResults = emptyList()
            if (searchByPokemon && query.trim().length >= 2) {
                delay(250)
                pokemonResults = repository.searchCatalog(query, 20)
            }
        }
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
                    val preferredProduct = preferredVisualCardmarketProduct(selected, products)
                    val visibleTrendProduct = preferredProduct
                    item {
                        Button(
                            onClick = {
                                val selection = catalogResultForVisualCard(selected, preferredProduct)
                                onSelected(selection)
                            },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                            shape = CardVaultShapes.card,
                        ) {
                            Text(
                                visibleTrendProduct?.trendPriceEur?.let { "Zum Binder hinzufügen · ${euro(it)}" }
                                    ?: "Zum Binder hinzufügen",
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    item {
                        Text(
                            visibleTrendProduct?.trendPriceEur?.let {
                                "Cardmarket-Trendpreis für den passenden Druck: ${euro(it)}"
                            }
                                ?: when {
                                    loading -> "Cardmarket-Trendpreis wird geladen …"
                                    products.isNotEmpty() -> "Für diesen Cardmarket-Druck liegt aktuell kein Trendpreis vor."
                                    else -> "Kein passender Cardmarket-Druck gefunden."
                                },
                            color = VaultTextMuted,
                            fontSize = 11.sp,
                        )
                    }
                    if (!loading && products.isNotEmpty()) item {
                        Text(
                            "Passender Cardmarket-Druck gefunden",
                            color = MewIce,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (!loading && products.isEmpty()) item {
                        Text("Kein passender Cardmarket-Druck gefunden. Du kannst die Karte trotzdem direkt hinzufügen.", color = VaultTextMuted, fontSize = 12.sp)
                    }
                    items(products, key = { "${it.productId}|${it.finish}|${it.variant}" }) { product ->
                        OutlinedCard(onClick = { onSelected(catalogResultForVisualCard(selected, product)) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Cardmarket-Trendpreis", fontWeight = FontWeight.Bold)
                            Text(product.trendPriceEur?.let(::euro) ?: "Noch kein Trendpreis", color = MewIce, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(listOfNotNull(product.finish, product.variant).filter(String::isNotBlank).joinToString(" · "), color = VaultTextMuted, fontSize = 11.sp)
                            Text(if (isExactVisualCatalogPriceMatch(selected, product)) "Exaktes Produkt auswählen" else "Gleichnamigen Druck prüfen und auswählen", color = if (isExactVisualCatalogPriceMatch(selected, product)) MewRoseSoft else VaultGold, fontSize = 12.sp)
                        } }
                    }
                }
                selectedSet != null -> items(cards.filter { matchesCardInSelectedSet(it.name, it.number, query) }, key = { it.id }) { card -> VisualCardRow(card, setCardPrices[card.id]) { selectedCard = card } }
                searchByPokemon -> items(pokemonResults, key = { it.productId }) { result -> CatalogResultRow(result) {
                    // The compact market catalogue has no printed number/source card ID.
                    // Confirm the actual set/card before assigning an official Binder slot.
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
                        CatalogSetIcon(set, Modifier.size(64.dp, 40.dp)); Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(set.name, fontWeight = FontWeight.Bold); Text("${set.displayCardCount()} Karten${set.releaseDate.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}", color = VaultTextMuted, fontSize = 11.sp) }
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
internal fun catalogResultForVisualCard(card: PokemonVisualCard, marketProduct: CatalogSearchResult? = null): CatalogSearchResult {
    // Cardmarket's catalogue variant ("Cardmarket-Produkt") is not a physical
    // printing. Only the selected source printing determines the Binder slot.
    val printings = card.binderPrintVariants()
    // A deliberately selected special printing may live outside the canonical
    // Binder wall. Preserve its source-declared identity so it appears as an
    // owned overflow card instead of borrowing the ordinary slot and price.
    val exactLinkedPrintings = card.printVariants.filter {
        marketProduct != null && it.cardmarketProductId == marketProduct.productId &&
            it.finish.equals(marketProduct.finish, ignoreCase = true) &&
            it.variant.equals(marketProduct.variant, ignoreCase = true)
    }
    val linkedPrintings = exactLinkedPrintings.ifEmpty {
        card.printVariants.filter { marketProduct != null && it.cardmarketProductId == marketProduct.productId }
    }
    val eligiblePrintings = linkedPrintings.ifEmpty { printings }
    val printing = eligiblePrintings.firstOrNull { it.finish.equals(marketProduct?.finish, ignoreCase = true) }
        ?: eligiblePrintings.first()
    return CatalogSearchResult(
        productId = marketProduct?.productId ?: 0L,
        name = card.name,
        expansionId = marketProduct?.expansionId ?: card.set.id,
        trendPriceEur = marketProduct?.realTrendFor(printing.finish),
        currency = marketProduct?.currency,
        visualSetName = card.set.name,
        collectorNumber = card.number,
        imageUrl = fallbackCardImageUrl(card.largeImageUrl, marketProduct?.imageUrl),
        language = "Deutsch",
        finish = printing.finish,
        variant = printing.variant,
        rarity = card.rarity,
        hasMarketProduct = marketProduct?.hasMarketProduct ?: false,
        mainSetSize = card.set.total.takeIf { it > 0 },
        setCode = card.set.ptcgoCode,
        marketSearchName = card.marketSearchName,
        holoTrendPriceEur = marketProduct?.holoTrendPriceEur,
        sourceSetId = card.set.id.lowercase(),
        sourceCardId = card.id.lowercase(),
    )
}

private fun isExactVisualCatalogPriceMatch(card: PokemonVisualCard, product: CatalogSearchResult): Boolean =
    normalizeCardName(card.name) == normalizeCardName(product.name) &&
        normalizedCollectorNumber(card.number) == normalizedCollectorNumber(product.collectorNumber.orEmpty()) &&
        normalizeBinderMetadata(card.set.name) == normalizeBinderMetadata(product.visualSetName ?: product.expansionId)

/** Editing the selected set/number must not carry an old source identity into another slot. */
internal fun CatalogSearchResult.matchesManualSourceSelection(set: String, number: String): Boolean =
    !collectorNumber.isNullOrBlank() && number.isNotBlank() &&
        normalizeBinderMetadata(visualSetName ?: expansionId) == normalizeBinderMetadata(set) &&
        normalizedCollectorNumber(collectorNumber).equals(normalizedCollectorNumber(number), ignoreCase = true)

internal fun fallbackCardImageUrl(imageUrl: String?, fallback: String?): String? =
    imageUrl?.takeIf(String::isNotBlank) ?: fallback?.takeIf(String::isNotBlank)

@Composable private fun CatalogSetIcon(set: PokemonSet, modifier: Modifier = Modifier) = Box(
    modifier.background(VaultNightRaised, CardVaultShapes.thumbnail),
    contentAlignment = Alignment.Center,
) {
    Text(set.name.take(1).uppercase(), color = MewIce, fontWeight = FontWeight.Black, fontSize = 18.sp)
    set.logoUrl?.let { logoUrl ->
        AsyncImage(model = logoUrl, contentDescription = "${set.name} Set-Icon", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
    }
}

@Composable private fun VisualCardRow(card: PokemonVisualCard, marketPrice: VisualCardMarketPrice? = null, onClick: () -> Unit) = OutlinedCard(onClick = onClick, colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface), modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(model = card.smallImageUrl, contentDescription = stringResource(R.string.catalog_card_image_description, card.name, card.number), modifier = Modifier.size(72.dp, 101.dp))
        Column(Modifier.weight(1f)) {
            Text(card.name, fontWeight = FontWeight.Bold)
            Text("#${card.number} · ${card.rarity ?: stringResource(R.string.unknown)}", color = VaultTextMuted, fontSize = 12.sp)
            Text(card.set.name, color = MewIce, fontSize = 11.sp)
            val priceLabel = when {
                marketPrice == null -> "Cardmarket-Preis wird geladen …"
                marketPrice.trendPriceEur != null && marketPrice.exact -> "Cardmarket-Trendpreis: ${euro(marketPrice.trendPriceEur)}"
                marketPrice.trendPriceEur != null -> "Cardmarket-Trendpreis ab ${euro(marketPrice.trendPriceEur)} · Druck prüfen"
                marketPrice.hasCardmarketProduct -> "Kein Cardmarket-Trendpreis verfügbar"
                else -> "Kein passender Cardmarket-Preis verfügbar"
            }
            Text(priceLabel, color = if (marketPrice?.trendPriceEur != null) MewIce else VaultTextMuted, fontSize = 11.sp, fontWeight = if (marketPrice?.trendPriceEur != null) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable private fun CatalogResultRow(result: CatalogSearchResult, onClick: () -> Unit) = OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface), shape = CardVaultShapes.card) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) { Text(result.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(stringResource(R.string.catalog_expansion_id, result.expansionId), color = VaultTextMuted, fontSize = 11.sp) }
        Text(result.trendPriceEur?.let(::euro) ?: stringResource(R.string.no_market_price), color = if (result.trendPriceEur == null) VaultTextMuted else MewIce, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable private fun BinderDestinationSummary(
    setName: String,
    number: String,
    finish: String,
    sourceConfirmed: Boolean,
    onChange: (() -> Unit)?,
    enabled: Boolean = true,
) {
    DomiGlassCard(Modifier.fillMaxWidth(), highlighted = sourceConfirmed) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Erkannte Set- und Slot-Identität", color = VaultTextMuted, fontSize = 11.sp)
            Text(setName.ifBlank { "Set auswählen" }, color = VaultText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (number.isNotBlank()) {
                Text(
                    "${if (sourceConfirmed) "Offizieller Sammelplatz" else "Kartennummer"} $number · $finish",
                    color = MewIce,
                    fontSize = 12.sp,
                )
            }
            Text("Diese Angaben bestimmen die exakte Karten- und Preiszuordnung.", color = VaultTextMuted, fontSize = 11.sp)
            if (onChange != null) TextButton(onClick = onChange, enabled = enabled) { Text("Karte/Set ändern") }
        }
    }
}

@Composable internal fun ManualCardDialog(
    repository: CollectionRepository,
    initialCatalog: CatalogSearchResult? = null,
    onDismiss: () -> Unit,
    onSaved: (AddCardOutcome) -> Unit = {},
) {
    val privateBinderSummaries by repository.privateBinders.collectAsState(initial = emptyList())
    var selectedPrivateBinderId by rememberSaveable(initialCatalog?.productId) { mutableStateOf<String?>(null) }
    var selectedCatalog by remember(initialCatalog) { mutableStateOf(initialCatalog) }
    var choosingCatalog by remember { mutableStateOf(false) }
    var name by rememberSaveable(initialCatalog?.productId) { mutableStateOf(initialCatalog?.name ?: "") }; var set by rememberSaveable(initialCatalog?.productId) { mutableStateOf(initialCatalog?.visualSetName ?: initialCatalog?.let { "Cardmarket-Erweiterung ${it.expansionId}" } ?: "") }; var number by rememberSaveable(initialCatalog?.productId) { mutableStateOf(initialCatalog?.collectorNumber ?: "") }
    var language by rememberSaveable { mutableStateOf(CardLanguage.GERMAN) }; var finish by rememberSaveable(initialCatalog?.productId) { mutableStateOf(initialCatalog?.finish ?: "Normal") }; var variant by rememberSaveable(initialCatalog?.productId) { mutableStateOf(initialCatalog?.variant ?: "Standard") }; var effectOverride by rememberSaveable(initialCatalog?.productId) { mutableStateOf<String?>(null) }
    var quantity by rememberSaveable { mutableStateOf("1") }; var condition by rememberSaveable { mutableStateOf("Nahezu neuwertig") }; var notes by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }; var date by rememberSaveable { mutableStateOf("") }
    var marketProducts by remember { mutableStateOf<List<CatalogSearchResult>>(emptyList()) }
    var marketLoading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var selectedMarketProductId by rememberSaveable { mutableStateOf<Long?>(initialCatalog?.productId?.takeIf { initialCatalog.hasMarketProduct }) }
    val scope = rememberCoroutineScope()
    val catalogSync by repository.catalogSyncStatus.collectAsState(initial = CatalogSyncStatus("IDLE", null, null, 0, 0, null))
    val identitySelection = selectedCatalog?.takeIf { it.matchesManualCatalogSelection(name, set, number) }
    val sourceSelection = identitySelection?.takeIf { !it.sourceSetId.isNullOrBlank() && !it.sourceCardId.isNullOrBlank() }
    val sourceConfirmed = sourceSelection != null
    val priceSearchName = identitySelection?.marketSearchName ?: name
    fun clearCatalogSelection() {
        selectedCatalog = null
        selectedMarketProductId = null
        marketProducts = emptyList()
        saveError = null
    }
    LaunchedEffect(selectedCatalog, name, set, number, finish, priceSearchName, catalogSync.lastSuccessAtEpochMillis) {
        marketProducts = emptyList()
        if (name.trim().length < 2 || set.isBlank() || number.isBlank()) {
            marketLoading = false
            selectedMarketProductId = identitySelection?.takeIf { it.hasMarketProduct }?.productId
            return@LaunchedEffect
        }
        marketLoading = true
        try {
            val declaredProductId = identitySelection?.takeIf { it.hasMarketProduct && it.productId > 0L }?.productId
            val matches = if (declaredProductId != null) {
                // The selected source product remains exact even when its English name is
                // absent from a truncated name search or differs from the German display.
                repository.catalogProducts(listOf(declaredProductId))
            } else {
                repository.searchCatalog(priceSearchName, 60)
                    .filter { isExactManualPriceMatch(priceSearchName, set, number, it) }
            }
            marketProducts = matches.distinctBy { it.productId }.map { it.copy(trendPriceEur = it.realTrendFor(finish)) }
            selectedMarketProductId = selectedMarketProductId?.takeIf { id -> marketProducts.any { it.productId == id } }
                ?: marketProducts.firstOrNull { it.trendPriceEur != null }?.productId
                ?: marketProducts.firstOrNull()?.productId
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            marketProducts = emptyList()
        } finally {
            marketLoading = false
        }
    }
    val selectedMarketProduct = marketProducts.firstOrNull { it.productId == selectedMarketProductId }
    val valid = name.isNotBlank() && set.isNotBlank() && number.isNotBlank() && condition.isNotBlank() && (quantity.toIntOrNull() ?: 0) > 0 && parseDate(date) != Long.MIN_VALUE && parsePrice(price) != Double.NEGATIVE_INFINITY
    if (choosingCatalog) {
        CatalogProductPicker(repository, onDismiss = { choosingCatalog = false }, onSelected = { selection ->
            selectedCatalog = selection
            name = selection.name
            set = selection.visualSetName ?: "Cardmarket-Erweiterung ${selection.expansionId}"
            number = selection.collectorNumber.orEmpty()
            finish = selection.finish?.takeUnless { it.equals("UNKNOWN", true) } ?: "Normal"
            variant = selection.variant?.takeUnless { it.equals("UNKNOWN", true) } ?: "Standard"
            effectOverride = null
            selectedMarketProductId = selection.productId.takeIf { selection.hasMarketProduct }
            marketProducts = emptyList()
            saveError = null
            choosingCatalog = false
        })
        return
    }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(stringResource(R.string.manual_add_title)) }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            saveError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) } }
            item { BinderDestinationSummary(set, number, finish, sourceConfirmed, onChange = { choosingCatalog = true }, enabled = !saving) }
            item {
                BinderTargetPicker(
                    setName = set,
                    privateBinders = privateBinderSummaries,
                    selectedPrivateBinderId = selectedPrivateBinderId,
                    onSelectedPrivateBinderId = { selectedPrivateBinderId = it },
                    enabled = !saving,
                )
            }
            item { OutlinedTextField(name, { name = it; clearCatalogSelection() }, readOnly = sourceConfirmed, enabled = !saving, label = { Text(stringResource(R.string.field_card_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = CardVaultShapes.field) }
            item { OutlinedTextField(set, { set = it; clearCatalogSelection() }, readOnly = sourceConfirmed, enabled = !saving, label = { Text(stringResource(R.string.field_set_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = CardVaultShapes.field) }
            item { OutlinedTextField(number, { number = it; clearCatalogSelection() }, readOnly = sourceConfirmed, enabled = !saving, label = { Text(stringResource(R.string.field_card_number_printed)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = CardVaultShapes.field); Text(stringResource(R.string.field_card_number_hint), color = VaultTextMuted, fontSize = 10.sp) }
            item { Text(stringResource(R.string.field_language), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("Deutsch", color = MewIce, fontWeight = FontWeight.SemiBold, fontSize = 12.sp); Text("DomiDex wird für deutsche Karten geführt.", color = VaultTextMuted, fontSize = 10.sp) }
            item { CardFinishAndEffectEditor(finish, { finish = it }, variant, { variant = it }, effectOverride, { effectOverride = it }) }
            item {
                Text("Cardmarket-Trendpreis", color = MewIce, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                when {
                    marketLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    marketProducts.isEmpty() -> Text("Cardmarket-Preise werden noch synchronisiert oder dieses Produkt muss exakt verknüpft werden.", color = VaultTextMuted, fontSize = 11.sp)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        marketProducts.forEach { product ->
                            OutlinedCard(
                                onClick = { selectedMarketProductId = product.productId },
                                colors = CardDefaults.outlinedCardColors(containerColor = if (product.productId == selectedMarketProductId) MewPlumDeep else VaultNightRaised),
                                border = BorderStroke(1.dp, if (product.productId == selectedMarketProductId) MewRose else VaultOutline),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(product.trendPriceEur?.let { "Cardmarket-Trendpreis: ${euro(it)}" } ?: "Cardmarket-Trendpreis wird geladen", color = if (product.trendPriceEur != null) MewIce else VaultTextMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("$finish · $variant", color = VaultTextMuted, fontSize = 11.sp)
                                    Text("Passendes Preisprodukt für diese Karte", color = MewIce, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
            item { FormField(quantity, { quantity = it.filter(Char::isDigit) }, R.string.field_quantity, KeyboardType.Number) }; item { FormField(condition, { condition = it }, R.string.field_condition) }
            item { FormField(price, { price = it }, R.string.field_acquisition_price, KeyboardType.Decimal) }; item { FormField(date, { date = it }, R.string.field_acquisition_date) }; item { FormField(notes, { notes = it }, R.string.field_notes, singleLine = false) }
        }
    }, confirmButton = {
        TextButton(enabled = valid && !saving, onClick = {
            val selectedLink = selectedMarketProduct ?: identitySelection?.takeIf { it.hasMarketProduct }
            val input = ManualCardInput(name, set, number, language, finish.ifBlank { "Normal" }, variant.ifBlank { "Standard" }, quantity.toInt(), condition, notes, parsePrice(price).takeUnless { it == Double.NEGATIVE_INFINITY }, parseDate(date).takeUnless { it == Long.MIN_VALUE }, selectedLink?.productId, fallbackCardImageUrl(identitySelection?.imageUrl, selectedMarketProduct?.imageUrl), rarity = identitySelection?.rarity, effectOverride = effectOverride, cardmarketExpansionId = selectedLink?.expansionId, sourceSetId = sourceSelection?.sourceSetId, sourceCardId = sourceSelection?.sourceCardId)
            saveError = null
            saving = true
            scope.launch {
                try {
                    val result = repository.addManualCard(input, binderTarget(selectedPrivateBinderId))
                    val outcome = if (result.quantityIncremented) AddCardOutcome.QUANTITY_INCREMENTED else AddCardOutcome.CREATED
                    saving = false
                    onSaved(outcome)
                    onDismiss()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e("DomiDexCollection", "Manual card persistence failed", error)
                    saving = false
                    saveError = "Karte konnte nicht gespeichert werden. Bitte erneut versuchen."
                }
            }
        }) {
            if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("In diesen Binder hinzufügen")
        }
    }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

/** Also guard restored draft text: an earlier selected product cannot price a different card. */
internal fun CatalogSearchResult.matchesManualCatalogSelection(name: String, set: String, number: String): Boolean =
    normalizeCardName(this.name) == normalizeCardName(name) && matchesManualSourceSelection(set, number)

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
        item { GradingWebNotice() }
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
private data class SelectedScanKnowledgeContext(
    val analysis: CardTextAnalysis,
    val candidates: List<ScanCandidate>,
)

private data class PendingDuplicateScanAdd(
    val scanId: String,
    val candidate: CatalogSearchResult,
    val input: ManualCardInput,
    val target: BinderTarget,
)

internal enum class ScannerCameraEntryStep { EXPLAIN, REQUEST_SYSTEM_PERMISSION, READY }

internal fun scannerCameraEntryStep(introAccepted: Boolean, cameraGranted: Boolean): ScannerCameraEntryStep = when {
    !introAccepted -> ScannerCameraEntryStep.EXPLAIN
    !cameraGranted -> ScannerCameraEntryStep.REQUEST_SYSTEM_PERMISSION
    else -> ScannerCameraEntryStep.READY
}

private fun artworkLabel(result: ArtworkVerification): String = when (result.match) {
    ArtworkMatch.CORROBORATES -> "passt zur Referenz"
    ArtworkMatch.CONTRADICTS -> "abweichend – bitte prüfen"
    ArtworkMatch.INCONCLUSIVE -> "nicht eindeutig"
    ArtworkMatch.UNAVAILABLE -> "Referenz nicht verfügbar"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ScannerScreen(repository: CollectionRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reportFlow = rememberScannerReportFlow()
    var reportSettingsOpen by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val uiPreferences = remember(context) { context.getSharedPreferences("cardvault_ui", Context.MODE_PRIVATE) }
    var scannerIntroAccepted by rememberSaveable {
        mutableStateOf(uiPreferences.getBoolean("scanner_camera_intro_accepted_v1", false))
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        denied = !it
    }
    var analysisRunning by remember { mutableStateOf(false) }
    var pendingCameraCaptures by remember { mutableIntStateOf(0) }
    var galleryImportPending by remember { mutableStateOf(false) }
    var analysis by remember { mutableStateOf<CardTextAnalysis?>(null) }
    var analysisFailed by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<CatalogSearchResult>>(emptyList()) }
    var candidateScores by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var artworkScores by remember { mutableStateOf<Map<String, ArtworkVerification>>(emptyMap()) }
    var artworkLoading by remember { mutableStateOf(false) }
    var candidatesLoading by remember { mutableStateOf(false) }
    var queuedScans by remember { mutableStateOf<List<QueuedCardScan>>(emptyList()) }
    var scanSession by remember { mutableStateOf(ScanAnalysisSession()) }
    val scanJobs = remember { mutableMapOf<String, Job>() }
    var selectedScanId by remember { mutableStateOf<String?>(null) }
    var queueReviewMode by remember { mutableStateOf(false) }
    var queueExpanded by remember { mutableStateOf(false) }
    var selectedCandidate by remember { mutableStateOf<CatalogSearchResult?>(null) }
    var selectedCandidateScanId by remember { mutableStateOf<String?>(null) }
    var pendingDuplicateAdd by remember { mutableStateOf<PendingDuplicateScanAdd?>(null) }
    var sessionSummaryOpen by remember { mutableStateOf(false) }
    var selectedKnowledgeContext by remember { mutableStateOf<SelectedScanKnowledgeContext?>(null) }
    var scanAddRunning by remember { mutableStateOf(false) }
    var scanAddError by remember { mutableStateOf<String?>(null) }
    var manualEvidence by rememberSaveable { mutableStateOf(false) }
    var latestScanFile by remember { mutableStateOf<File?>(null) }
    var feedbackConsentPending by remember { mutableStateOf(false) }
    var feedbackEditorPath by remember { mutableStateOf<String?>(null) }
    val contributionPrefs = remember(context) { context.getSharedPreferences("cardvault_contribution", Context.MODE_PRIVATE) }
    var contributionOptIn by rememberSaveable { mutableStateOf(contributionPrefs.getBoolean("opt_in", false)) }
    var contributionEligible by remember { mutableStateOf(false) }
    var contributionMarkedLocal by remember { mutableStateOf(false) }
    var contributionPromptHandled by remember { mutableStateOf(false) }
    var contributionUploading by remember { mutableStateOf(false) }
    var contributionReceipt by remember { mutableStateOf<CatalogContributionReceipt?>(null) }
    var contributionFailure by remember { mutableStateOf<StorageFailureReason?>(null) }
    val contributionStorage = remember(context) { FirebaseDomiDexStorageGateway(context) }
    val scanKnowledge = remember(context) { FirebaseScanKnowledge(context) }
    val scannerAppVersion = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }
    val textAnalyzer = remember(context) { CardTextAnalyzer(context) }
    val visualCatalog = remember { PokemonVisualCatalog() }
    val hasSessionActivity = scanSession.analyzed > 0 || queuedScans.isNotEmpty() ||
        pendingCameraCaptures > 0 || galleryImportPending
    val requestScannerExit = {
        if (hasSessionActivity) sessionSummaryOpen = true else onBack()
    }
    BackHandler(onBack = requestScannerExit)
    // Warm the small German set index as soon as the scanner opens so printed
    // totals are immediately available for safe cross-set fallback. Official
    // abbreviations are registered when the corresponding set detail is read.
    LaunchedEffect(visualCatalog) { runCatching { visualCatalog.sets() } }
    // The camera captures independently. One bounded worker serializes OCR/artwork so a fast
    // scan series cannot multiply native OpenCV/ML Kit memory pressure.
    val processingSlots = remember { Semaphore(permits = ScanQueuePolicy.MAX_PARALLEL_WORKERS) }
    val latestQueuedScans = rememberUpdatedState(queuedScans)
    DisposableEffect(textAnalyzer) { onDispose {
        scanJobs.values.forEach(Job::cancel)
        scanJobs.clear()
        textAnalyzer.close()
        latestQueuedScans.value.forEach { scan ->
            scan.file.delete()
            scan.evidenceFiles.forEach(File::delete)
        }
    } }
    fun updateQueuedScan(id: String, change: (QueuedCardScan) -> QueuedCardScan) {
        queuedScans = queuedScans.map { scan -> if (scan.id == id) change(scan) else scan }
        scanSession = scanSession.retainExactScanIds(queuedScans.map(QueuedCardScan::id))
    }
    fun releaseQueuedScan(scan: QueuedCardScan, cancelPendingWork: Boolean = false) {
        if (cancelPendingWork) scanJobs.remove(scan.id)?.cancel()
        else scanJobs.remove(scan.id)
        if (!scan.stage.isTerminal) {
            scanSession = scanSession.recordTerminal(scan.id, ScanPipelineStage.FAILED)
        }
        reportFlow.releaseCapture(scan.id)
        scan.file.delete()
        scan.evidenceFiles.forEach(File::delete)
    }
    fun clearSelectedQueueResult() {
        selectedScanId = null
        selectedCandidateScanId = null
        latestScanFile = null
        queueReviewMode = false
    }
    LaunchedEffect(queuedScans.map { it.id to it.stage }, selectedScanId, reportFlow.activeScanId, reportFlow.uploadingScanId, reportFlow.outcomeRevision, reportFlow.automaticEnabled) {
        val protectedReports = reportFlow.protectedScanIds(queuedScans)
        val resolved = queuedScans.filter {
            it.id != selectedScanId && it.id !in protectedReports && it.stage == ScanPipelineStage.SAVED
        }
        if (resolved.isNotEmpty()) {
            resolved.forEach(::releaseQueuedScan)
            val resolvedIds = resolved.mapTo(mutableSetOf(), QueuedCardScan::id)
            queuedScans = queuedScans.filterNot { it.id in resolvedIds }
        }
    }
    // Only the annotation editor moves to the Web. Explicit Set + Number remains available.
    LaunchedEffect(reportFlow.access, feedbackConsentPending, feedbackEditorPath) {
        if (reportFlow.access == ScannerFeedbackAccess.WEB_REPORT &&
            (feedbackConsentPending || feedbackEditorPath != null)) {
            queuedScans.firstOrNull { it.id == selectedScanId || it.file == latestScanFile }?.let(reportFlow::open)
            feedbackConsentPending = false
            feedbackEditorPath = null
        }
    }
    LaunchedEffect(analysis) {
        val current = analysis ?: return@LaunchedEffect
        if (queueReviewMode) return@LaunchedEffect
        if (!current.manualIdentityConfirmed && (!current.imageQuality.cardDetected || current.imageQuality.reason.isNotBlank())) {
            candidates = emptyList()
            candidateScores = emptyMap()
            candidatesLoading = false
            manualEvidence = false
            feedbackConsentPending = latestScanFile != null
            ScannerDebugState.snapshot = ScannerDebugState.snapshot.copy(
                state = AutoScanState.NOT_FOUND,
                frameConfidence = current.imageQuality.frameConfidence,
                finalConfidence = 0,
                reason = current.imageQuality.reason.ifBlank { "card_frame_missing" },
            )
            return@LaunchedEffect
        }
        ScannerDebugState.snapshot = ScannerDebugState.snapshot.copy(
            state = AutoScanState.MATCHING,
            numberConfidence = current.numberConfidence,
            reason = "catalog_match",
            raritySymbolColor = current.raritySymbolEvidence.color,
            raritySymbolConfidence = current.raritySymbolEvidence.confidence,
        )
        candidatesLoading = true
        artworkScores = emptyMap()
        artworkLoading = false
        // Manual evidence correction re-enters exactly the same source-backed
        // resolver as a fresh queued scan. This keeps cross-set denominator
        // fallback, source-code learning and language fallback consistent.
        val resolution = runCatching {
            withContext(Dispatchers.IO) { CardScanCandidateResolver.resolve(current, repository, visualCatalog) }
        }.getOrNull()
        val ranked = resolution?.candidates.orEmpty().map { it.card to it.score }
        candidates = ranked.map { it.first }
        candidateScores = ranked.associate { it.first.scanIdentityKey() to it.second }
        candidatesLoading = false
        // Module 4 is deliberately asynchronous: a public reference image may be slow or absent,
        // but it must never hold the camera or price refresh queue. It only corroborates an
        // already strict set + number candidate and is surfaced before the collector adds a card.
        current.normalizedCard?.takeIf { ranked.isNotEmpty() }?.let { captured ->
            val visualCandidates = ranked.map { it.first }.filter { !it.imageUrl.isNullOrBlank() }.take(ScanQueuePolicy.MAX_VISUAL_CANDIDATES)
            if (visualCandidates.isNotEmpty()) {
                artworkLoading = true
                scope.launch {
                    val verified = try {
                        val descriptor = withContext(Dispatchers.Default) { CardArtworkVerifier.describe(captured) }
                        CardArtworkVerifier.verifyCandidates(listOf(descriptor), visualCandidates.associate { it.scanIdentityKey() to it.imageUrl })
                    } finally { artworkLoading = false }
                    artworkScores = verified
                    val bestArtwork = verified[ranked.first().first.scanIdentityKey()]
                    ScannerDebugState.snapshot = ScannerDebugState.snapshot.copy(
                        artworkConfidence = bestArtwork?.confidence ?: 0f,
                        artworkMatch = bestArtwork?.match?.name.orEmpty(),
                    )
                }
            }
        }
        val decision = constrainManualWholeImageDecision(
            current,
            resolution?.decision ?: ScannerDecisionResult(ScannerDecision.REJECTED, reason = "catalog_lookup_unavailable"),
        )
        ScannerDebugState.snapshot = ScannerDebugState.snapshot.copy(
            state = when (decision.decision) { ScannerDecision.ACCEPTED -> AutoScanState.CONFIRMED; ScannerDecision.AMBIGUOUS -> AutoScanState.AMBIGUOUS; ScannerDecision.REJECTED -> AutoScanState.NOT_FOUND },
            candidateCount = ranked.size,
            finalConfidence = decision.bestScore,
            secondBestConfidence = decision.secondScore ?: 0,
            decision = decision.decision.name,
            reason = decision.reason,
        )
        if (decision.decision == ScannerDecision.ACCEPTED && ranked.isNotEmpty()) {
            // Fast scan mode never adds a card silently. It clears the blocking
            // sheet and keeps the best evidence-backed result in the lower rail,
            // ready for a deliberate tap while the camera continues with the next.
            val resultScanId = selectedScanId ?: latestScanFile?.name ?: UUID.randomUUID().toString()
            val acceptedCandidate = ranked.first().first
            scanSession = scanSession.registerIdentity(resultScanId, acceptedCandidate.scanSessionIdentityKey()).session
            updateQueuedScan(resultScanId) { scan ->
                scan.copy(
                    stage = ScanPipelineStage.MATCHED,
                    analysis = current.copy(normalizedCard = null, visualDescriptors = emptyList()),
                    candidates = resolution?.candidates.orEmpty(),
                    decision = decision,
                    message = "${CardRecognitionRanker.label(ranked.first().second)} · zum Hinzufügen bereit",
                )
            }
            analysis = null
            queueReviewMode = false
        }
        // A successful photo must never leave the collector at a dead end. When OCR
        // lacks sufficient evidence, immediately ask for the two printed markers
        // rather than pretending that a card identity can be guessed.
        if (decision.decision == ScannerDecision.REJECTED) {
            manualEvidence = false
            feedbackConsentPending = latestScanFile != null && !current.manualIdentityConfirmed
        }
    }
    fun enqueueScan(
        file: File,
        qualityEligible: Boolean,
        evidenceFiles: List<File> = emptyList(),
        origin: ScanCaptureOrigin,
    ) {
        val protectedIds = reportFlow.protectedScanIds(queuedScans) + listOfNotNull(selectedScanId)
        if (origin == ScanCaptureOrigin.AUTOMATIC && !ScanQueuePolicy.allowsAutomaticCapture(queuedScans, pendingCameraCaptures > 0, protectedIds)) {
            scanSession = scanSession.recordTerminal("rejected:${file.name}", ScanPipelineStage.FAILED)
            file.delete()
            evidenceFiles.forEach(File::delete)
            ScannerDiagnosticLog.event("QUEUE_REJECTED", file.name, "reason" to "automatic_backlog_full", "queueSize" to queuedScans.size)
            return
        }
        while (queuedScans.size >= ScanQueuePolicy.MAX_TOTAL_BACKLOG) {
            val eviction = if (origin == ScanCaptureOrigin.AUTOMATIC) {
                ScanQueuePolicy.automaticEvictionCandidate(queuedScans, protectedIds)
            } else {
                ScanQueuePolicy.evictionCandidateForManualCapture(queuedScans.filterNot { it.id in protectedIds }, selectedScanId)
            }
            if (eviction == null) {
                scanSession = scanSession.recordTerminal("rejected:${file.name}", ScanPipelineStage.FAILED)
                file.delete()
                evidenceFiles.forEach(File::delete)
                analysisFailed = true
                ScannerDiagnosticLog.event("QUEUE_REJECTED", file.name, "reason" to "total_backlog_full", "queueSize" to queuedScans.size)
                return
            }
            releaseQueuedScan(eviction, cancelPendingWork = eviction.stage == ScanPipelineStage.CAPTURED)
            queuedScans = queuedScans.filterNot { it.id == eviction.id }
            ScannerDiagnosticLog.event("QUEUE_EVICTED", eviction.id, "reason" to "manual_capture_reserved_slot", "state" to eviction.stage.name)
        }
        // Rapid scan never covers the camera with old results. The person explicitly pulls the
        // bounded result rail up only when they want to review it.
        queueExpanded = false
        val scan = QueuedCardScan(file = file, evidenceFiles = evidenceFiles, qualityEligible = qualityEligible, origin = origin)
        reportFlow.registerCapture(scan.id)
        queuedScans = listOf(scan) + queuedScans
        scanSession = scanSession.retainExactScanIds(queuedScans.map(QueuedCardScan::id))
        ScannerDiagnosticLog.event(
            event = "QUEUED",
            scanId = scan.id,
            "captureKey" to file.name,
            "captureTimestamp" to scan.capturedAtMillis,
            "frameCount" to (evidenceFiles.size + 1),
            "queueSize" to (latestQueuedScans.value.size + 1),
            "qualityEligible" to qualityEligible,
            "origin" to origin.name,
        )
        ScannerDebugState.snapshot = ScannerDebugState.snapshot.copy(state = AutoScanState.READING, reason = "queued:${scan.id.take(8)}", scanId = scan.id)
        val scanJob = scope.launch {
            processingSlots.withPermit {
                val pipelineStarted = SystemClock.elapsedRealtime()
                val durations = linkedMapOf<ScanPipelineStage, Long>()
                var activeStage = ScanPipelineStage.CAPTURED
                var activeStageStarted = pipelineStarted
                fun stage(next: ScanPipelineStage, message: String = next.label) {
                    val now = SystemClock.elapsedRealtime()
                    durations[activeStage] = (durations[activeStage] ?: 0L) + now - activeStageStarted
                    activeStage = next
                    activeStageStarted = now
                    updateQueuedScan(scan.id) { current ->
                        if (current.stage.canTransitionTo(next) || current.stage == next) current.copy(stage = next, message = message, stageDurationsMillis = durations.toMap()) else current
                    }
                    ScannerDiagnosticLog.event(
                        event = "STAGE",
                        scanId = scan.id,
                        "state" to next.name,
                        "worker" to Thread.currentThread().name,
                        "queueSize" to latestQueuedScans.value.size,
                    )
                }
                fun terminal(
                    next: ScanPipelineStage,
                    message: String,
                    identityKey: String? = null,
                    change: (QueuedCardScan) -> QueuedCardScan = { it },
                ) {
                    if (latestQueuedScans.value.firstOrNull { it.id == scan.id }?.stage?.isTerminal == true) return
                    stage(next, message)
                    val now = SystemClock.elapsedRealtime()
                    durations[next] = (durations[next] ?: 0L) + now - activeStageStarted
                    val totalMillis = now - pipelineStarted
                    updateQueuedScan(scan.id) { current -> change(current).copy(stage = next, message = message, stageDurationsMillis = durations.toMap(), totalProcessingMillis = totalMillis) }
                    scanSession = scanSession.recordTerminal(scan.id, next, identityKey)
                    ScannerDebugState.snapshot = ScannerDebugState.snapshot.copy(
                        state = when (next) {
                            ScanPipelineStage.MATCHED -> AutoScanState.CONFIRMED
                            ScanPipelineStage.AMBIGUOUS -> AutoScanState.AMBIGUOUS
                            ScanPipelineStage.MANUAL_REVIEW, ScanPipelineStage.FAILED -> AutoScanState.NOT_FOUND
                            else -> ScannerDebugState.snapshot.state
                        },
                        reason = message,
                        processingMillis = totalMillis,
                        stageTimings = durations.entries.joinToString(" · ") { (pipelineStage, elapsed) -> "${pipelineStage.name.take(4)} ${elapsed}ms" },
                        scanId = scan.id,
                    )
                    ScannerDiagnosticLog.event(
                        event = "TERMINAL",
                        scanId = scan.id,
                        "state" to next.name,
                        "message" to message,
                        "totalProcessingMs" to totalMillis,
                        "stageTimings" to durations.entries.joinToString(",") { (pipelineStage, elapsed) -> "${pipelineStage.name}:$elapsed" },
                        "queueSize" to latestQueuedScans.value.size,
                    )
                }

                var activeNormalizedCard: android.graphics.Bitmap? = null
                runScanWorkerAnalysis(
                    onFailure = { failure ->
                        evidenceFiles.forEach(File::delete)
                        activeNormalizedCard?.let { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
                        activeNormalizedCard = null
                        val current = latestQueuedScans.value.firstOrNull { it.id == scan.id }
                        if (current?.stage?.isTerminal != true) {
                            terminal(
                                ScanPipelineStage.FAILED,
                                if (failure is ScanPipelineTimeoutException || failure is java.util.concurrent.TimeoutException)
                                    "Analyse dauert zu lange. Bitte erneut versuchen oder Set und Nummer wählen."
                                else "Die Aufnahme konnte nicht analysiert werden. Bitte erneut versuchen oder Set und Nummer wählen.",
                            )
                        }
                        ScannerDiagnosticLog.event(
                            event = "WORKER_FAILED",
                            scanId = scan.id,
                            "failureType" to failure.javaClass.simpleName,
                            "state" to current?.stage?.name,
                        )
                    },
                    analysis = worker@{
                stage(ScanPipelineStage.PREPROCESSING)
                // One worker owns all heavyweight OCR/OpenCV work. Even legacy multi-frame
                // entries are evaluated serially so native recognizers cannot spike together.
                val textResults = (listOf(file) + evidenceFiles).map { evidenceFile ->
                    textAnalyzer.analyzeAwait(
                        evidenceFile,
                        allowManualWholeImageFallback = scan.origin != ScanCaptureOrigin.AUTOMATIC,
                    )
                }
                evidenceFiles.forEach(File::delete)
                val successfulAnalyses = textResults.mapNotNull(Result<CardTextAnalysis>::getOrNull)
                val currentAnalysis = TemporalRecognitionEvidence.merge(successfulAnalyses)
                if (currentAnalysis == null) {
                    val error = textResults.firstNotNullOfOrNull(Result<CardTextAnalysis>::exceptionOrNull)
                    terminal(ScanPipelineStage.FAILED, "Texterkennung fehlgeschlagen: ${error?.javaClass?.simpleName ?: "Unbekannt"}")
                    return@worker
                }
                activeNormalizedCard = currentAnalysis.normalizedCard
                val knowledgeFingerprint = scanKnowledgeFingerprint(currentAnalysis)
                val latestKnowledge = AtomicReference(knowledgeFingerprint?.let(scanKnowledge::cached))
                val knowledgeListener = knowledgeFingerprint?.let { fingerprint ->
                    scanKnowledge.observeRelevant(fingerprint, latestKnowledge::set)
                }
                try {
                ScannerDebugState.snapshot = ScannerDebugState.snapshot.copy(
                    geometryScore = currentAnalysis.imageQuality.geometryScore,
                    perspectiveScore = currentAnalysis.imageQuality.perspective,
                    frameQualityScore = currentAnalysis.imageQuality.frameQualityScore,
                    glarePercentage = currentAnalysis.imageQuality.glare,
                    usedFallbackGeometry = currentAnalysis.imageQuality.usedFallbackGeometry,
                    usedDynamicOcr = currentAnalysis.usedDynamicOcr,
                    usedMultiFrameEvidence = currentAnalysis.usedMultiFrameEvidence,
                )
                val quality = currentAnalysis.imageQuality
                ScannerDiagnosticLog.event(
                    event = "ANALYSIS",
                    scanId = scan.id,
                    "frameCount" to (evidenceFiles.size + 1),
                    "cardBoundaryFound" to quality.cardDetected,
                    "geometryScore" to quality.geometryScore,
                    "cardCoverage" to quality.cardCoverage,
                    "aspectRatio" to quality.aspectRatio,
                    "cornerConfidence" to quality.cornerConfidence,
                    "perspectiveScore" to quality.perspective,
                    "usedGeometryFallback" to quality.usedFallbackGeometry,
                    "sharpnessScore" to quality.sharpness,
                    "glarePercentage" to quality.glare,
                    "clippingPercentage" to quality.clippingPercentage,
                    "motionScore" to quality.motionScore,
                    "selectedBestFrame" to quality.selectedBestFrame,
                    "rawNameOcr" to currentAnalysis.roiEvidence.firstOrNull { it.region == CardOcrRegion.NAME }?.rawValues?.joinToString(" | "),
                    "normalizedName" to currentAnalysis.searchQueries.firstOrNull(),
                    "rawCollectorNumber" to currentAnalysis.roiEvidence.firstOrNull { it.region == CardOcrRegion.NUMBER }?.rawValues?.joinToString(" | "),
                    "normalizedCollectorNumber" to currentAnalysis.collectorNumber,
                    "rawSetOcr" to currentAnalysis.roiEvidence.firstOrNull { it.region == CardOcrRegion.SET }?.rawValues?.joinToString(" | "),
                    "detectedSet" to currentAnalysis.setIdentifier,
                    "detectedLanguage" to currentAnalysis.likelyScript,
                    "numberOcrConfidence" to currentAnalysis.numberConfidence,
                    "roiUsed" to currentAnalysis.roiEvidence.filter { it.rawValues.isNotEmpty() }.joinToString(",") { it.region.name },
                    "geometryDurationMs" to quality.geometryDurationMillis,
                    "perspectiveDurationMs" to quality.perspectiveDurationMillis,
                    "ocrDurationMs" to currentAnalysis.ocrDurationMillis,
                    "descriptorDurationMs" to currentAnalysis.descriptorDurationMillis,
                    "multiFrame" to currentAnalysis.usedMultiFrameEvidence,
                )
                stage(ScanPipelineStage.OCR)
                // Queue/UI state retains evidence, never the full normalized bitmap. The worker
                // owns that native memory only for the duration of this recognition job.
                updateQueuedScan(scan.id) {
                    it.copy(
                        analysis = currentAnalysis.copy(normalizedCard = null, visualDescriptors = emptyList()),
                        message = ScanPipelineStage.OCR.label,
                    )
                }
                val manualWholeImageFallback = currentAnalysis.imageQuality.isManualRecognitionFallback
                if (!manualWholeImageFallback && (!currentAnalysis.imageQuality.cardDetected || currentAnalysis.imageQuality.reason.isNotBlank())) {
                    val message = if (currentAnalysis.imageQuality.reason == MANUAL_WHOLE_IMAGE_FALLBACK_REASON) {
                        "Ganzes Foto gelesen – Set und Nummer bitte bestätigen"
                    } else "Kartenfläche nicht sicher erkannt"
                    terminal(ScanPipelineStage.MANUAL_REVIEW, message)
                    return@worker
                }
                stage(ScanPipelineStage.SET_ANALYSIS)
                if (currentAnalysis.collectorNumber == null) {
                    terminal(ScanPipelineStage.MANUAL_REVIEW, "Kartennummer fehlt")
                    return@worker
                }
                stage(ScanPipelineStage.CANDIDATE_SEARCH)
                val resolution = withContext(Dispatchers.IO) { CardScanCandidateResolver.resolve(currentAnalysis, repository, visualCatalog) }
                ScannerDiagnosticLog.event(
                    event = "CANDIDATES_BEFORE_VISUAL",
                    scanId = scan.id,
                    "candidateCount" to resolution.candidates.size,
                    "topCandidates" to resolution.candidates.take(3).joinToString(" | ") { "${it.card.name}:${it.card.setCode}/${it.card.collectorNumber}:${it.score}" },
                )
                updateQueuedScan(scan.id) { it.copy(candidates = resolution.candidates, decision = resolution.decision) }
                if (resolution.candidates.isEmpty()) {
                    terminal(ScanPipelineStage.MANUAL_REVIEW, "Kein verlässlicher Katalogtreffer")
                    return@worker
                }
                stage(ScanPipelineStage.ARTWORK_VERIFICATION)
                val artwork = currentAnalysis.normalizedCard?.let { captured ->
                    val descriptors = currentAnalysis.visualDescriptors.ifEmpty {
                        listOf(withContext(Dispatchers.Default) { CardArtworkVerifier.describe(captured) })
                    }
                    CardArtworkVerifier.verifyCandidates(descriptors,
                        resolution.candidates.take(ScanQueuePolicy.MAX_VISUAL_CANDIDATES)
                            .associate { it.card.scanIdentityKey() to it.card.imageUrl })
                }.orEmpty()
                val fusionStarted = SystemClock.elapsedRealtime()
                val fused = ScanKnowledgeCandidateRanker.apply(
                    HybridCandidateFusion.rank(currentAnalysis, resolution.candidates, artwork),
                    latestKnowledge.get(),
                )
                val finalDecision = constrainManualWholeImageDecision(
                    currentAnalysis,
                    HybridCandidateFusion.decide(fused),
                )
                val fusionDuration = SystemClock.elapsedRealtime() - fusionStarted
                updateQueuedScan(scan.id) { it.copy(candidates = fused, artwork = artwork, decision = finalDecision) }
                stage(ScanPipelineStage.VARIANT_ANALYSIS)
                stage(ScanPipelineStage.PRICE_LOOKUP)
                val finalStage = when (finalDecision.decision) {
                    ScannerDecision.ACCEPTED -> ScanPipelineStage.MATCHED
                    ScannerDecision.AMBIGUOUS -> ScanPipelineStage.AMBIGUOUS
                    ScannerDecision.REJECTED -> ScanPipelineStage.MANUAL_REVIEW
                }
                val best = fused.firstOrNull()
                val confidenceLabel = best?.evidence?.confidence?.userLabel ?: "Nicht erkannt"
                terminal(
                    finalStage,
                    if (finalStage == ScanPipelineStage.MATCHED) "$confidenceLabel · zum Hinzufügen bereit" else confidenceLabel,
                    best?.card?.scanSessionIdentityKey(),
                ) { current ->
                    current.copy(analysis = currentAnalysis.copy(normalizedCard = null, visualDescriptors = emptyList()), candidates = fused, artwork = artwork, decision = finalDecision)
                }
                val bestArtwork = best?.let { artwork[it.card.scanIdentityKey()] }
                val evidence = best?.evidence
                ScannerDiagnosticLog.event(
                    event = "DECISION",
                    scanId = scan.id,
                    "candidateCountBeforeVisual" to resolution.candidates.size,
                    "candidateCountAfterVisual" to fused.size,
                    "top1" to best?.let { "${it.card.name}:${it.card.setCode}/${it.card.collectorNumber}" },
                    "top1Score" to finalDecision.bestScore,
                    "top2" to fused.getOrNull(1)?.let { "${it.card.name}:${it.card.setCode}/${it.card.collectorNumber}" },
                    "top2Score" to finalDecision.secondScore,
                    "scoreMargin" to finalDecision.absoluteMargin,
                    "relativeMargin" to finalDecision.relativeMargin,
                    "contradictions" to evidence?.contradictions?.joinToString(","),
                    "finalConfidence" to evidence?.confidence,
                    "finalState" to finalDecision.decision,
                    "reason" to finalDecision.reason,
                    "fullCardVisualScore" to bestArtwork?.fullCardSimilarity,
                    "artworkScore" to bestArtwork?.artworkSimilarity,
                    "descriptorScore" to bestArtwork?.similarity,
                    "visualMatchingDurationMs" to (durations[ScanPipelineStage.ARTWORK_VERIFICATION] ?: 0L),
                    "candidateFusionDurationMs" to fusionDuration,
                )
                ScannerDebugState.snapshot = ScannerDebugState.snapshot.copy(
                    state = when (finalStage) { ScanPipelineStage.MATCHED -> AutoScanState.CONFIRMED; ScanPipelineStage.AMBIGUOUS -> AutoScanState.AMBIGUOUS; else -> AutoScanState.NOT_FOUND },
                    candidateCount = fused.size,
                    finalConfidence = finalDecision.bestScore,
                    secondBestConfidence = finalDecision.secondScore ?: 0,
                    decision = finalDecision.decision.name,
                    reason = finalDecision.reason,
                    artworkMatch = bestArtwork?.match?.name.orEmpty(),
                    artworkConfidence = bestArtwork?.confidence ?: 0f,
                    visualScore = evidence?.visualScore ?: 0,
                    visualFullCardScore = ((bestArtwork?.fullCardSimilarity ?: 0f) * 100).toInt(),
                    visualArtworkScore = ((bestArtwork?.artworkSimilarity ?: 0f) * 100).toInt(),
                    numberScore = evidence?.numberScore ?: 0,
                    collectorNumberScore = evidence?.numberScore ?: 0,
                    setScore = evidence?.setScore ?: 0,
                    nameScore = evidence?.nameScore ?: 0,
                    languageScore = evidence?.languageScore ?: 0,
                    processingMillis = SystemClock.elapsedRealtime() - pipelineStarted,
                    scoreMargin = finalDecision.absoluteMargin.takeUnless { it == Int.MAX_VALUE } ?: 100,
                    contradictionFlags = evidence?.contradictions?.joinToString(",").orEmpty(),
                    glarePercentage = maxOf(currentAnalysis.imageQuality.glare, bestArtwork?.glarePercentage ?: 0f),
                    usedDynamicOcr = currentAnalysis.usedDynamicOcr,
                    usedMultiFrameEvidence = currentAnalysis.usedMultiFrameEvidence,
                    stageTimings = durations.entries.joinToString(" · ") { (pipelineStage, elapsed) -> "${pipelineStage.name.take(4)} ${elapsed}ms" },
                    scanId = scan.id,
                )
                } finally {
                    knowledgeListener?.close()
                    currentAnalysis.normalizedCard?.let { bitmap ->
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                    activeNormalizedCard = null
                }
                    },
                )
            }
        }
        scanJobs[scan.id] = scanJob
        scanJob.invokeOnCompletion {
            scope.launch {
                if (scanJobs[scan.id] === scanJob) scanJobs.remove(scan.id)
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        galleryImportPending = true
        scope.launch {
            var importedFile: File? = null
            try {
                val file = withContext(Dispatchers.IO) {
                    File(context.cacheDir, "domidex-gallery-${System.currentTimeMillis()}.jpg").also { target ->
                        importedFile = target
                        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use(input::copyTo) }
                    }
                }
                if (file.exists() && file.length() > 0L) enqueueScan(
                    file,
                    qualityEligible = false,
                    origin = ScanCaptureOrigin.GALLERY,
                )
                else {
                    scanSession = scanSession.recordTerminal("gallery:${file.name}", ScanPipelineStage.FAILED)
                    file.delete()
                    analysisFailed = true
                }
            } catch (_: Exception) {
                scanSession = scanSession.recordTerminal("gallery:${importedFile?.name ?: UUID.randomUUID()}", ScanPipelineStage.FAILED)
                importedFile?.delete()
                analysisFailed = true
            } finally {
                galleryImportPending = false
            }
        }
    }
    val cameraEntryStep = scannerCameraEntryStep(scannerIntroAccepted, granted)
    val automaticCaptureAllowed = !sessionSummaryOpen && ScanQueuePolicy.allowsAutomaticCapture(
        queuedScans,
        pendingCameraCaptures > 0,
        reportFlow.protectedScanIds(queuedScans) + listOfNotNull(selectedScanId),
    )
    val manualCaptureAllowed = !sessionSummaryOpen && pendingCameraCaptures == 0 &&
        (queuedScans.size < ScanQueuePolicy.MAX_TOTAL_BACKLOG ||
            ScanQueuePolicy.evictionCandidateForManualCapture(
                queuedScans.filterNot { it.id in reportFlow.protectedScanIds(queuedScans) },
                selectedScanId,
            ) != null)
    Box(Modifier.fillMaxSize()) {
        if (cameraEntryStep == ScannerCameraEntryStep.READY) {
            LiveCameraScanner(
                modifier = Modifier.fillMaxSize(),
                autoCaptureAllowed = automaticCaptureAllowed,
                manualCaptureAllowed = manualCaptureAllowed,
                onCaptureStarted = { _ ->
                    // Capture is intentionally independent from prior work items. Do not clear an
                    // ongoing queue entry here: the camera is free as soon as its JPEG is saved.
                    pendingCameraCaptures = 1
                    ScannerDebugState.snapshot = ScannerDebugState.snapshot.copy(state = AutoScanState.CAPTURING, reason = "capture_started")
                },
                onCaptureSaved = { file, eligible, temporalFiles, automatic ->
                    pendingCameraCaptures = 0
                    enqueueScan(
                        file,
                        eligible,
                        temporalFiles,
                        origin = if (automatic) ScanCaptureOrigin.AUTOMATIC else ScanCaptureOrigin.MANUAL,
                    )
                },
                onCaptureFailed = {
                    pendingCameraCaptures = 0
                    scanSession = scanSession.recordTerminal("camera:${UUID.randomUUID()}", ScanPipelineStage.FAILED)
                    analysisFailed = true
                },
            )
        } else if (cameraEntryStep == ScannerCameraEntryStep.REQUEST_SYSTEM_PERMISSION) {
            Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.camera_permission_title), color = Color.White, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.camera_permission_body), color = Color(0xFFB9C9C5))
                Button({ launcher.launch(Manifest.permission.CAMERA) }) { Text(stringResource(if (denied) R.string.camera_permission_retry else R.string.camera_permission_allow)) }
            }
        }
        Surface(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp), color = VaultGlassHigh, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, VaultOutlineSoft)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(onClick = requestScannerExit, color = Color.Transparent, shape = RoundedCornerShape(10.dp)) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { DomiLineIcon(DomiIconKind.BACK, VaultText, Modifier.size(18.dp)) }
                }
                Column(Modifier.weight(1f)) {
                    Text("SCANNER", color = VaultText, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                    Text("Karte ruhig und vollständig ins Bild halten", color = VaultTextMuted, fontSize = 9.sp)
                }
                if (reportFlow.access == ScannerFeedbackAccess.WEB_REPORT) TextButton(onClick = { reportSettingsOpen = true }) {
                    Text("Feedback", fontSize = 10.sp)
                }
                Surface(
                    onClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !galleryImportPending,
                    color = MewPlumDeep.copy(alpha = .62f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(if (galleryImportPending) "Import …" else "Galerie", Modifier.padding(horizontal = 10.dp, vertical = 8.dp), color = MewIce, fontSize = 10.sp)
                }
            }
        }
        // Detailed scanner evidence remains available in local DomiDexScanner logs. Keeping the
        // debug panel off the camera preview prevents it from covering the card and guidance.
        if (analysisRunning || analysisFailed || analysis != null) {
            DomiDexModalBottomSheet(
                onDismissRequest = {
                    if (!analysisRunning) {
                        analysis = null
                        analysisFailed = false
                        clearSelectedQueueResult()
                    }
                },
                containerColor = VaultNightRaised,
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Mögliche Karten", color = VaultText, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("Abgleich nur über Set-Kennung und gedruckte Nummer. Bitte Karte vor dem Auswählen prüfen.", color = VaultTextMuted, fontSize = 12.sp)
                    if (analysisRunning) AnalysisStatusCard(R.string.scanner_analysis_running)
                    if (analysisFailed) {
                        Text(if (reportFlow.allowsLocalEditor) "Keine verwertbaren Textmerkmale erkannt. Du kannst die Karte erneut aufnehmen oder manuell fortfahren." else "Keine verwertbaren Textmerkmale erkannt. Fehlscans kannst du in der Warteschlange an das Scanner-Lab senden.", color = VaultTextMuted, fontSize = 13.sp)
                        OutlinedButton(onClick = { analysisFailed = false }) { Text("Zurück zum Scanner") }
                    }
                    analysis?.let { result ->
                        if (candidatesLoading) AnalysisStatusCard(R.string.scanner_candidates_searching)
                        else if (candidates.isEmpty()) {
                            Text(if (result.manualIdentityConfirmed) "Für dieses Set und diese Nummer konnte gerade keine Karte aus dem Katalog geladen werden. Bitte Verbindung prüfen oder die Auswahl erneut versuchen." else "Set auswählen und Kartennummer eingeben, um die Karte aus dem Katalog hinzuzufügen.", color = VaultTextMuted, fontSize = 13.sp)
                            OutlinedButton(onClick = { manualEvidence = true }, modifier = Modifier.fillMaxWidth()) { Text("Set-Kennung und Nummer manuell eingeben") }
                            OutlinedButton(onClick = {
                                analysis = null
                                clearSelectedQueueResult()
                            }) { Text("Weitere Aufnahme machen") }
                        } else {
                            Text("Sortiert nach lokalem OCR-Abgleich", color = MewRoseSoft, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            candidates.take(5).forEachIndexed { index, candidate ->
                                ElevatedCard(onClick = {
                                    selectedKnowledgeContext = analysis?.let { current ->
                                        SelectedScanKnowledgeContext(
                                            current,
                                            candidates.map { item -> ScanCandidate(item, candidateScores[item.scanIdentityKey()] ?: 0) },
                                        )
                                    }
                                    selectedCandidateScanId = selectedScanId ?: latestScanFile?.name ?: UUID.randomUUID().toString()
                                    selectedCandidate = candidate
                                    analysis = null
                                    queueReviewMode = false
                                }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = VaultPlumSurface)) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("${index + 1}", color = MewRoseSoft, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(candidate.name, color = VaultText, fontWeight = FontWeight.SemiBold)
                                            Text("${candidate.visualSetName ?: candidate.expansionId} · ${candidate.collectorNumber ?: "ohne Nummer"}", color = VaultTextMuted, fontSize = 12.sp)
                                            candidate.finish?.let { finish -> Text("$finish · ${candidate.variant ?: "Standard"}", color = MewRoseSoft, fontSize = 11.sp) }
                                            val rarity = CardRarityResolver.fromMetadata(candidate.rarity, candidate.collectorNumber.orEmpty(), candidate.mainSetSize)
                                            if (rarity.aboveMainSet) Text("Oberhalb der Hauptsetnummerierung", color = VaultGold, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                            if (rarity.type != CardRarityType.UNKNOWN) Text(rarity.type.label, color = MewIce, fontSize = 10.sp)
                                            Text("Abgleich: ${CardRecognitionRanker.label(candidateScores[candidate.scanIdentityKey()] ?: 0)}", color = MewIce, fontSize = 11.sp)
                                            val artwork = artworkScores[candidate.scanIdentityKey()]
                                            if (artwork != null) Text("Bildabgleich: ${artworkLabel(artwork)}", color = if (artwork.match == ArtworkMatch.CONTRADICTS) VaultGold else MewIce, fontSize = 10.sp)
                                            else if (artworkLoading) Text("Bildabgleich läuft im Hintergrund", color = VaultTextMuted, fontSize = 10.sp)
                                            val price = candidate.trendPriceEur
                                            if (price != null) Text("Cardmarket-Trendpreis: ${euro(price)}", color = MewIce, fontSize = 10.sp)
                                            else if (!candidate.hasMarketProduct) Text("Katalogtreffer · Preis wird separat abgeglichen", color = VaultTextMuted, fontSize = 10.sp)
                                        }
                                        Text("Auswählen", color = MewRoseSoft, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        if (contributionEligible && (!contributionPromptHandled || contributionUploading || contributionMarkedLocal || contributionFailure != null)) CommunityContributionCard(
                            optedIn = contributionOptIn,
                            onOptInChanged = { enabled -> contributionOptIn = enabled; contributionPrefs.edit().putBoolean("opt_in", enabled).apply() },
                            onPromptHandled = { contributionPromptHandled = true },
                            locallyMarked = contributionMarkedLocal,
                            uploading = contributionUploading,
                            failure = contributionFailure,
                            onContribute = {
                                val source = latestScanFile
                                if (source == null) contributionFailure = StorageFailureReason.FILE_MISSING
                                else {
                                    contributionUploading = true
                                    contributionFailure = null
                                    scope.launch {
                                        when (val result = contributionStorage.uploadCatalogContribution(source, scannerAppVersion)) {
                                            is StorageUploadResult.Uploaded -> {
                                                contributionReceipt = result.receipt
                                                contributionMarkedLocal = true
                                                contributionPromptHandled = true
                                            }
                                            is StorageUploadResult.Failed -> contributionFailure = result.reason
                                        }
                                        contributionUploading = false
                                    }
                                }
                            },
                            onWithdraw = {
                                val receipt = contributionReceipt
                                if (receipt == null) {
                                    contributionMarkedLocal = false
                                    contributionPromptHandled = true
                                } else scope.launch {
                                    contributionUploading = true
                                    if (contributionStorage.deleteCatalogContribution(receipt)) {
                                        contributionReceipt = null
                                        contributionMarkedLocal = false
                                        contributionPromptHandled = true
                                        contributionFailure = null
                                    } else contributionFailure = StorageFailureReason.UNKNOWN
                                    contributionUploading = false
                                }
                            },
                        )
                    }
                }
            }
        }
        if (queuedScans.isNotEmpty() || pendingCameraCaptures > 0 || galleryImportPending) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 14.dp, vertical = 92.dp)
                    .pointerInput(Unit) {
                        var verticalDrag = 0f
                        detectDragGestures(
                            onDragStart = { verticalDrag = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                verticalDrag += amount.y
                                if (verticalDrag <= -28f) queueExpanded = true
                                if (verticalDrag >= 28f) queueExpanded = false
                            },
                        )
                    },
                color = VaultNightRaised.copy(alpha = .96f),
                shape = CardVaultShapes.card,
                tonalElevation = 8.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    val pendingInputCount = pendingCameraCaptures + if (galleryImportPending) 1 else 0
                    val processingCount = pendingInputCount + queuedScans.count { it.stage in setOf(ScanPipelineStage.CAPTURED, ScanPipelineStage.PREPROCESSING, ScanPipelineStage.OCR, ScanPipelineStage.SET_ANALYSIS, ScanPipelineStage.CANDIDATE_SEARCH, ScanPipelineStage.ARTWORK_VERIFICATION, ScanPipelineStage.VARIANT_ANALYSIS, ScanPipelineStage.PRICE_LOOKUP) }
                    Row(
                        Modifier.fillMaxWidth().clickable { queueExpanded = !queueExpanded }.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("Scan-Warteschlange", color = VaultText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("${scanSession.analyzed} analysiert · ${scanSession.unique} eindeutig · ${scanSession.review} prüfen · ${scanSession.skipped} übersprungen", color = MewIce, fontSize = 10.sp, maxLines = 1)
                            if (processingCount > 0) Text("$processingCount aktuell in Analyse", color = VaultTextMuted, fontSize = 9.sp, maxLines = 1)
                            if (scanSession.duplicates > 0) Text("${scanSession.duplicates} Duplikat${if (scanSession.duplicates == 1) "" else "e"} erkannt", color = VaultGold, fontSize = 9.sp, maxLines = 1)
                            if (queuedScans.size >= ScanQueuePolicy.MAX_TOTAL_BACKLOG) Text("Aufnahme pausiert · bitte ein Ergebnis bearbeiten", color = VaultGold, fontSize = 9.sp, maxLines = 1)
                        }
                        DomiLineIcon(DomiIconKind.CHEVRON, MewRose, Modifier.size(15.dp).graphicsLayer { rotationZ = if (queueExpanded) 90f else -90f })
                    }
                    AnimatedVisibility(visible = queueExpanded) {
                        Column(Modifier.fillMaxWidth().padding(bottom = 5.dp)) {
                            if (pendingInputCount > 0) {
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                                Text("Aufnahme wird direkt an die Analyse übergeben …", color = MewIce, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                            }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${queuedScans.size + pendingInputCount} Ergebnisse & Aufnahmen", color = VaultTextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                            }
                            when (reportFlow.access) {
                                ScannerFeedbackAccess.CHECKING -> Text("Scanner-Berechtigung wird geprüft …", color = VaultTextMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
                                ScannerFeedbackAccess.UNAVAILABLE -> TextButton(onClick = reportFlow.retryAccess) { Text("Berechtigung erneut prüfen", fontSize = 11.sp) }
                                ScannerFeedbackAccess.WEB_REPORT -> Column(Modifier.padding(horizontal = 12.dp)) {
                                    Text("Kein automatischer Upload · Du entscheidest pro Aufnahme", color = VaultTextMuted, fontSize = 10.sp)
                                    Text("Antippen → manuell mit Set + Nummer hinzufügen", color = MewIce, fontSize = 11.sp)
                                }
                                ScannerFeedbackAccess.LOCAL -> Unit
                            }
                            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(queuedScans, key = { it.id }) { scan ->
                            val best = scan.candidates.firstOrNull()
                            val actionable = scan.stage == ScanPipelineStage.MATCHED || scan.stage.needsScannerReview
                            val removable = scan.stage.isTerminal && scan.stage != ScanPipelineStage.SAVED &&
                                scan.id != selectedScanId && scan.id !in reportFlow.protectedScanIds(queuedScans)
                            OutlinedCard(
                                onClick = {
                                    if (!actionable) return@OutlinedCard
                                    if (scan.stage.needsScannerReview && reportFlow.access == ScannerFeedbackAccess.WEB_REPORT) {
                                        selectedScanId = scan.id
                                        latestScanFile = scan.file
                                        queueReviewMode = true
                                        analysis = scan.analysis ?: CardTextAnalysis(emptyList(), null, null, null, emptyList())
                                        candidates = emptyList()
                                        manualEvidence = true
                                        return@OutlinedCard
                                    }
                                    selectedScanId = scan.id
                                    latestScanFile = scan.file
                                    contributionEligible = scan.qualityEligible
                                    contributionMarkedLocal = false
                                    contributionPromptHandled = false
                                    contributionUploading = false
                                    contributionReceipt = null
                                    contributionFailure = null
                                    when (scan.stage) {
                                        ScanPipelineStage.MATCHED -> best?.let {
                                            selectedKnowledgeContext = scan.analysis?.let { current -> SelectedScanKnowledgeContext(current, scan.candidates) }
                                            selectedCandidateScanId = scan.id
                                            selectedCandidate = it.card
                                        }
                                        ScanPipelineStage.AMBIGUOUS -> {
                                            queueReviewMode = true
                                            analysis = scan.analysis
                                            candidates = scan.candidates.map(ScanCandidate::card)
                                            candidateScores = scan.candidates.associate { it.card.scanIdentityKey() to it.score }
                                            artworkScores = scan.artwork
                                        }
                                        ScanPipelineStage.MANUAL_REVIEW, ScanPipelineStage.FAILED -> {
                                            queueReviewMode = true
                                            analysis = scan.analysis ?: CardTextAnalysis(emptyList(), null, null, null, emptyList())
                                            manualEvidence = true
                                        }
                                        else -> Unit
                                    }
                                },
                                modifier = Modifier.width(176.dp),
                                enabled = actionable,
                                colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface),
                            ) {
                                Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    AsyncImage(
                                        model = if (scan.stage == ScanPipelineStage.SAVED) best?.card?.imageUrl ?: scan.file else scan.file,
                                        contentDescription = "Lokale Vorschau der aufgenommenen Karte",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(8.dp)).background(VaultNight),
                                    )
                                    Text(best?.card?.name ?: "Karte wird verarbeitet", color = VaultText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(best?.card?.let { "${it.visualSetName ?: it.expansionId} · ${it.collectorNumber ?: "Nr. prüfen"}" } ?: scan.message, color = VaultTextMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(scan.stage.label, color = if (scan.stage == ScanPipelineStage.FAILED) VaultGold else MewIce, fontSize = 10.sp)
                                    best?.let { candidate ->
                                        Text(candidate.evidence?.confidence?.userLabel ?: "Abgleich: ${CardRecognitionRanker.label(candidate.score)}", color = MewIce, fontSize = 10.sp)
                                        candidate.card.trendPriceEur?.let { price -> Text("Cardmarket: ${euro(price)}", color = MewIce, fontSize = 10.sp) }
                                    }
                                    if (reportFlow.access == ScannerFeedbackAccess.WEB_REPORT) {
                                        val reportOutcome = reportFlow.outcome(scan.id)
                                        when {
                                            reportOutcome?.reportId != null -> Text("An Scanner-Lab gesendet", color = MewIce, fontSize = 10.sp)
                                            reportFlow.uploadingScanId == scan.id -> Text("Fehlscan wird hochgeladen …", color = MewIce, fontSize = 10.sp)
                                            reportOutcome?.error != null -> Text("Upload nicht bestätigt", color = VaultGold, fontSize = 10.sp)
                                        }
                                        if (scan.stage.needsScannerReview) Text("Antippen für Set + Nummer", color = MewRoseSoft, fontSize = 10.sp)
                                        ScannerReportButton(reportFlow, scan)
                                    } else if (reportFlow.allowsLocalEditor) {
                                        if (scan.stage == ScanPipelineStage.AMBIGUOUS) Text("Antippen zum Prüfen", color = MewRoseSoft, fontSize = 10.sp)
                                        if (scan.stage == ScanPipelineStage.MANUAL_REVIEW) Text("Antippen für Set + Nummer", color = MewRoseSoft, fontSize = 10.sp)
                                    }
                                    if (scan.stage.isTerminal && scan.stage != ScanPipelineStage.SAVED) {
                                        TextButton(
                                            onClick = {
                                                scanSession = scanSession.recordSkipped(scan.id)
                                                releaseQueuedScan(scan)
                                                queuedScans = queuedScans.filterNot { it.id == scan.id }
                                            },
                                            enabled = removable,
                                        ) { Text("Überspringen", fontSize = 10.sp) }
                                    }
                                }
                            }
                        }
                            }
                        }
                    }
                }
            }
        }
    }
    if (cameraEntryStep == ScannerCameraEntryStep.EXPLAIN) {
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text(stringResource(R.string.scanner_camera_intro_title)) },
            text = { Text(stringResource(R.string.scanner_camera_intro_body)) },
            confirmButton = {
                Button(onClick = {
                    uiPreferences.edit().putBoolean("scanner_camera_intro_accepted_v1", true).apply()
                    scannerIntroAccepted = true
                    if (!granted) launcher.launch(Manifest.permission.CAMERA)
                }) { Text(stringResource(R.string.scanner_camera_intro_continue)) }
            },
            dismissButton = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
        )
    }
    LaunchedEffect(selectedCandidate?.scanIdentityKey()) {
        scanAddRunning = false
        scanAddError = null
    }
    fun persistScanCandidate(candidate: CatalogSearchResult, input: ManualCardInput, scanId: String, target: BinderTarget) {
        if (scanAddRunning) return
        if (scanSession.wasAdded(scanId)) {
            scanAddError = "Diese Aufnahme wurde bereits hinzugefügt."
            return
        }
        scanAddRunning = true
        scanAddError = null
        scope.launch {
            runCatching { repository.addManualCard(input, target) }
                .onSuccess {
                    selectedKnowledgeContext?.takeIf {
                        input.sourceSetId == candidate.sourceSetId && input.sourceCardId == candidate.sourceCardId &&
                            input.finish == candidate.finish && input.variant == candidate.variant
                    }?.let { knowledgeContext ->
                        val evidence = buildScanKnowledgeEvidence(
                            knowledgeContext.analysis,
                            knowledgeContext.candidates,
                            candidate,
                        )
                        if (evidence != null) scanKnowledge.enqueue(evidence)
                    }
                    scanSession = scanSession
                        .registerIdentity(scanId, candidate.scanSessionIdentityKey()).session
                        .recordAdded(scanId)
                    updateQueuedScan(scanId) { scan ->
                        if (scan.stage.isTerminal && scan.stage != ScanPipelineStage.SAVED) {
                            if (scan.id !in reportFlow.protectedScanIds(queuedScans)) scan.file.delete()
                            scan.copy(stage = ScanPipelineStage.SAVED, message = ScanPipelineStage.SAVED.label)
                        } else scan
                    }
                    selectedCandidate = null
                    selectedCandidateScanId = null
                    selectedKnowledgeContext = null
                    selectedScanId = null
                }
                .onFailure { error ->
                    scanAddError = error.message ?: "Die Karte konnte nicht gespeichert werden."
                }
            scanAddRunning = false
        }
    }
    selectedCandidate?.let { candidate ->
        ScanAddDialog(
            candidate = candidate,
            repository = repository,
            adding = scanAddRunning,
            error = scanAddError,
            onDismiss = {
                if (!scanAddRunning) {
                    selectedCandidate = null
                    selectedKnowledgeContext = null
                    clearSelectedQueueResult()
                }
            },
        ) { chosenCandidate, input, target ->
            if (scanAddRunning) return@ScanAddDialog
            val scanId = selectedCandidateScanId ?: selectedScanId ?: latestScanFile?.name ?: UUID.randomUUID().toString()
            selectedCandidateScanId = scanId
            if (scanSession.isDuplicate(scanId, chosenCandidate.scanSessionIdentityKey())) {
                pendingDuplicateAdd = PendingDuplicateScanAdd(scanId, chosenCandidate, input, target)
            } else persistScanCandidate(chosenCandidate, input, scanId, target)
        }
    }
    pendingDuplicateAdd?.let { pending ->
        AlertDialog(
            onDismissRequest = { if (!scanAddRunning) pendingDuplicateAdd = null },
            title = { Text("Karte bereits erfasst") },
            text = { Text("Diese Karte wurde in dieser Analyse bereits erfasst.") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDuplicateAdd = null
                        persistScanCandidate(pending.candidate, pending.input, pending.scanId, pending.target)
                    },
                    enabled = !scanAddRunning,
                ) { Text("Trotzdem hinzufügen") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDuplicateAdd = null }, enabled = !scanAddRunning) { Text("Abbrechen") }
            },
        )
    }
    if (reportSettingsOpen && reportFlow.access == ScannerFeedbackAccess.WEB_REPORT) AlertDialog(
        onDismissRequest = { reportSettingsOpen = false },
        title = { Text("Scanner-Feedback") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { ScannerAutomaticReportControl(reportFlow) } },
        confirmButton = { TextButton(onClick = { reportSettingsOpen = false }) { Text("Schließen") } },
    )
    ScannerReportDialog(reportFlow, onManualAdd = {
        reportFlow.selected?.let { scan ->
            selectedScanId = scan.id
            latestScanFile = scan.file
            queueReviewMode = true
            analysis = scan.analysis ?: CardTextAnalysis(emptyList(), null, null, null, emptyList())
            candidates = emptyList()
            manualEvidence = true
        }
        reportFlow.dismiss()
    })
    if (manualEvidence) {
        ManualScanEvidenceDialog(
            initialSetCode = analysis?.setIdentifier.orEmpty(),
            initialNumber = analysis?.collectorNumber.orEmpty(),
            onDismiss = {
                manualEvidence = false
                clearSelectedQueueResult()
            },
            onConfirm = { setSourceId, setCode, number ->
                queueReviewMode = false
                // The explicit catalogue id prevents duplicate printed abbreviations from
                // opening the wrong expansion. Manual identity can also recover a rejected photo.
                analysis = withManualScanIdentity(analysis, setSourceId, setCode, number)
                candidates = emptyList(); candidateScores = emptyMap()
                manualEvidence = false
            },
        )
    }
    if (feedbackConsentPending && reportFlow.allowsLocalEditor) ScanFeedbackConsentDialog(
        onHelp = {
            feedbackConsentPending = false
            feedbackEditorPath = latestScanFile?.absolutePath
        },
        onDecline = {
            feedbackConsentPending = false
            latestScanFile?.delete()
            latestScanFile = null
            manualEvidence = true
        },
        onManualSearch = {
            feedbackConsentPending = false
            latestScanFile?.delete()
            latestScanFile = null
            manualEvidence = true
        },
    )
    feedbackEditorPath?.takeIf { reportFlow.allowsLocalEditor }?.let { path -> ScanFeedbackEditorDialog(
        imageFile = File(path),
        analysis = analysis,
        onDismiss = { File(path).delete(); feedbackEditorPath = null; manualEvidence = true },
        onConfirmed = { File(path).delete(); feedbackEditorPath = null; manualEvidence = true },
    ) }
    if (sessionSummaryOpen) {
        val unfinishedIds = buildList {
            addAll(queuedScans.filterNot { it.stage.isTerminal }.map(QueuedCardScan::id))
            repeat(pendingCameraCaptures) { add("pending-camera-$it") }
            if (galleryImportPending) add("pending-gallery")
        }
        val summary = scanSession.withSkipped(unfinishedIds)
        AlertDialog(
            onDismissRequest = { sessionSummaryOpen = false },
            title = { Text("Analyse-Session abschließen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("${summary.analyzed} Karten analysiert", fontWeight = FontWeight.Bold)
                    Text("${summary.unique} eindeutig")
                    Text("${summary.duplicates} Duplikate")
                    Text("${summary.review} prüfen")
                    Text("${summary.skipped} übersprungen")
                    Text("${summary.added} hinzugefügt")
                    if (unfinishedIds.isNotEmpty()) {
                        Text("Noch laufende Aufnahmen werden beim Abschließen als übersprungen gewertet.", color = VaultGold, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = { Button(onClick = onBack) { Text("Session abschließen") } },
            dismissButton = { TextButton(onClick = { sessionSummaryOpen = false }) { Text("Weiter scannen") } },
        )
    }
}

@Composable private fun ScannerDebugOverlay(snapshot: ScannerDebugSnapshot, analysis: CardTextAnalysis?, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(modifier = modifier.widthIn(max = 250.dp), color = VaultNightRaised.copy(alpha = .94f), shape = CardVaultShapes.card, border = BorderStroke(1.dp, MewPlum.copy(alpha = .55f))) {
        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "Scanner-Diagnose ▴" else "Scanner-Diagnose ▾", color = MewIce, fontSize = 11.sp) }
            if (expanded) {
                Text("Status: ${snapshot.state} · ${snapshot.reason}", color = VaultTextMuted, fontSize = 10.sp)
                Text("Rahmen ${"%.0f".format(snapshot.frameConfidence * 100)}% · Schärfe ${"%.0f".format(snapshot.frameSharpness * 100)}% · Stabilität ${"%.0f".format(snapshot.frameStability * 100)}%", color = VaultTextMuted, fontSize = 10.sp)
                analysis?.let { current ->
                    Text("M1: ${current.imageQuality.reason.ifBlank { "normalisiert" }} · Reflexion ${"%.0f".format(current.imageQuality.glare * 100)}%", color = VaultTextMuted, fontSize = 10.sp)
                    current.roiEvidence.forEach { evidence -> Text("M2 ${evidence.region}: ${evidence.normalizedValue ?: "—"} (${(evidence.confidence * 100).toInt()}%)", color = VaultTextMuted, fontSize = 10.sp) }
                }
                Text("M3: ${snapshot.candidateCount} Kandidaten · ${snapshot.finalConfidence}/${snapshot.secondBestConfidence} · ${snapshot.decision}", color = VaultTextMuted, fontSize = 10.sp)
                if (snapshot.artworkMatch.isNotBlank()) Text("M4: ${snapshot.artworkMatch} (${(snapshot.artworkConfidence * 100).toInt()}%)", color = VaultTextMuted, fontSize = 10.sp)
                Text("Geometry ${(snapshot.geometryScore * 100).toInt()} · Perspektive ${(snapshot.perspectiveScore * 100).toInt()} · Qualität ${(snapshot.frameQualityScore * 100).toInt()} · Glare ${(snapshot.glarePercentage * 100).toInt()}%", color = VaultTextMuted, fontSize = 9.sp)
                if (snapshot.scanId.isNotBlank()) {
                    Text("Scan ${snapshot.scanId.take(8)} · ${snapshot.processingMillis} ms", color = VaultTextMuted, fontSize = 10.sp)
                    if (snapshot.stageTimings.isNotBlank()) Text(snapshot.stageTimings, color = VaultTextMuted, fontSize = 9.sp)
                    Text("Signale: Bild ${snapshot.visualScore} (${snapshot.visualFullCardScore}/${snapshot.visualArtworkScore}) · Nr ${snapshot.collectorNumberScore} · Set ${snapshot.setScore} · Name ${snapshot.nameScore} · Sprache ${snapshot.languageScore}", color = VaultTextMuted, fontSize = 10.sp)
                    Text("Margin ${snapshot.scoreMargin} · Widerspruch ${snapshot.contradictionFlags.ifBlank { "—" }} · Fallback ${snapshot.usedFallbackGeometry} · dynOCR ${snapshot.usedDynamicOcr} · MultiFrame ${snapshot.usedMultiFrameEvidence}", color = VaultTextMuted, fontSize = 9.sp)
                }
            }
        }
    }
}

/** A set choice carries its official total, so manual entry never asks for the redundant suffix. */
internal data class ScannerSetChoice(val id: String, val name: String, val code: String, val printedTotal: Int)

internal fun resolveScannerSetChoice(
    choices: List<ScannerSetChoice>,
    selectedIdentity: String,
    collectorNumber: String,
): ScannerSetChoice? {
    choices.firstOrNull { it.id.equals(selectedIdentity.trim(), true) }?.let { return it }
    val codeMatches = choices.filter { it.code.equals(selectedIdentity.trim(), true) }
    if (codeMatches.size <= 1) return codeMatches.singleOrNull()
    if (classic30thLocalIdForPrintedCollector(collectorNumber) != null) {
        return codeMatches.firstOrNull { it.id.equals("30th-c", true) }
    }
    val denominator = Regex("\\d{1,4}\\s*/\\s*(\\d{1,4})").find(collectorNumber)
        ?.groupValues?.get(1)?.toIntOrNull()
    return denominator?.let { total -> codeMatches.singleOrNull { it.printedTotal == total } }
}

private data class ScanFeedbackMark(
    val category: String = "Kartenname",
    val negative: Boolean = false,
    val left: Float = .2f,
    val top: Float = .2f,
    val width: Float = .5f,
    val height: Float = .14f,
    val detectedText: String = "",
    val correctedText: String = "",
)

private enum class ScanFeedbackDragMode {
    MOVE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
}

private const val SCAN_FEEDBACK_MIN_ZOOM = 1f
private const val SCAN_FEEDBACK_MAX_ZOOM = 6f

private fun Offset.distanceTo(other: Offset): Float = kotlin.math.hypot(
    (x - other.x).toDouble(),
    (y - other.y).toDouble(),
).toFloat()

/**
 * All editor geometry remains tied to the original, normalised card plane.
 * The preview is scaled uniformly around its centre, so a saved value never
 * depends on device pixels, the current pan offset, or the active zoom level.
 */
internal fun scanFeedbackPointInViewport(
    normalizedPoint: Offset,
    viewport: Size,
    zoom: Float,
    pan: Offset,
): Offset = Offset(
    viewport.width / 2f + (normalizedPoint.x - .5f) * viewport.width * zoom + pan.x,
    viewport.height / 2f + (normalizedPoint.y - .5f) * viewport.height * zoom + pan.y,
)

internal fun clampScanFeedbackPan(viewport: Size, zoom: Float, requested: Offset): Offset {
    val safeZoom = zoom.coerceIn(SCAN_FEEDBACK_MIN_ZOOM, SCAN_FEEDBACK_MAX_ZOOM)
    val maxX = viewport.width * (safeZoom - 1f) / 2f
    val maxY = viewport.height * (safeZoom - 1f) / 2f
    return Offset(
        if (maxX == 0f) 0f else requested.x.coerceIn(-maxX, maxX),
        if (maxY == 0f) 0f else requested.y.coerceIn(-maxY, maxY),
    )
}

private data class ScanFeedbackViewportBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

private fun ScanFeedbackMark.viewportBounds(viewport: Size, zoom: Float, pan: Offset): ScanFeedbackViewportBounds {
    val topLeft = scanFeedbackPointInViewport(Offset(left, top), viewport, zoom, pan)
    return ScanFeedbackViewportBounds(
        left = topLeft.x,
        top = topLeft.y,
        right = topLeft.x + width * viewport.width * zoom,
        bottom = topLeft.y + height * viewport.height * zoom,
    )
}

@Composable private fun ScanFeedbackConsentDialog(onHelp: () -> Unit, onDecline: () -> Unit, onManualSearch: () -> Unit) = AlertDialog(
    onDismissRequest = onDecline,
    title = { Text("Karte nicht sicher erkannt") },
    text = { Text("Karte konnte nicht sicher erkannt werden. Du kannst diesen Scan ausdrücklich lokal für Verbesserungen markieren, ihn verwerfen oder die Karte manuell suchen. Es findet kein automatischer Upload statt.") },
    confirmButton = { TextButton(onClick = onHelp) { Text("Scan für Verbesserung speichern") } },
    dismissButton = { Row { TextButton(onClick = onManualSearch) { Text("Manuell suchen") }; TextButton(onClick = onDecline) { Text("Nicht speichern") } } },
)

@Composable private fun ScanFeedbackEditorDialog(imageFile: File, analysis: CardTextAnalysis?, onDismiss: () -> Unit, onConfirmed: () -> Unit) {
    val context = LocalContext.current
    var marks by remember(imageFile) { mutableStateOf(listOf(
        ScanFeedbackMark("Kartenname", left = .08f, top = .07f, width = .68f, height = .12f, detectedText = analysis?.roiEvidence?.firstOrNull { it.region == CardOcrRegion.NAME }?.normalizedValue.orEmpty()),
        ScanFeedbackMark("HP", left = .73f, top = .07f, width = .18f, height = .12f),
        ScanFeedbackMark("Artwork", left = .08f, top = .22f, width = .84f, height = .31f),
        ScanFeedbackMark("Attacke 1", left = .08f, top = .56f, width = .84f, height = .13f),
        ScanFeedbackMark("Kartennummer", left = .18f, top = .82f, width = .38f, height = .10f, detectedText = analysis?.collectorNumber.orEmpty()),
        ScanFeedbackMark("Setkennung", left = .05f, top = .82f, width = .17f, height = .10f, detectedText = analysis?.setIdentifier.orEmpty()),
    )) }
    var selected by remember(imageFile) { mutableIntStateOf(0) }
    var categoryOpen by remember { mutableStateOf(false) }
    var editBoxes by remember(imageFile) { mutableStateOf(true) }
    var zoom by remember(imageFile) { mutableFloatStateOf(1f) }
    var pan by remember(imageFile) { mutableStateOf(Offset.Zero) }
    var editorStep by remember(imageFile) { mutableIntStateOf(0) }
    val categories = listOf("Kartenname", "HP", "Kartentyp", "Artwork", "Attacke 1", "Schaden Attacke 1", "Attacke 2", "Schaden Attacke 2", "Regeltext", "Kartennummer", "Setkennung", "Seltenheit", "Variantensymbol", "Copyright/Hersteller", "Sonstiges")
    val active = marks.getOrNull(selected)
    val latestMarks = rememberUpdatedState(marks)
    val latestZoom = rememberUpdatedState(zoom)
    val latestPan = rememberUpdatedState(pan)
    val editorScrollState = rememberScrollState()
    fun updateActive(change: (ScanFeedbackMark) -> ScanFeedbackMark) { marks = marks.mapIndexed { index, mark -> if (index == selected) change(mark) else mark } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Scanner-Feedback · ${editorStep + 1}/3") }, text = {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(editorScrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (editorStep == 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(editBoxes, { editBoxes = true }, label = { Text("Felder bearbeiten") })
                FilterChip(!editBoxes, { editBoxes = false }, label = { Text("Ansicht · Zoom/Pan") })
                TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("Karte einpassen") }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = 420.dp), contentAlignment = Alignment.Center) {
            val cardWidth = minOf(maxWidth, maxHeight * .72f)
            Box(Modifier.width(cardWidth).aspectRatio(.72f).clip(RoundedCornerShape(12.dp)).background(Color.Black).pointerInput(imageFile, editBoxes) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    val viewport = Size(size.width.toFloat(), size.height.toFloat())
                    val handleRadius = 34f
                    val current = latestMarks.value
                    var dragIndex = if (editBoxes) current.indices.reversed().firstOrNull { index ->
                        val bounds = current[index].viewportBounds(viewport, latestZoom.value, latestPan.value)
                        firstDown.position.x in (bounds.left - handleRadius)..(bounds.right + handleRadius) &&
                            firstDown.position.y in (bounds.top - handleRadius)..(bounds.bottom + handleRadius)
                    } ?: -1 else -1
                    var dragMode = ScanFeedbackDragMode.MOVE
                    if (dragIndex >= 0) {
                        selected = dragIndex
                        val bounds = current[dragIndex].viewportBounds(viewport, latestZoom.value, latestPan.value)
                        fun near(x: Float, y: Float) = firstDown.position.distanceTo(Offset(x, y)) <= handleRadius
                        dragMode = when {
                            near(bounds.left, bounds.top) -> ScanFeedbackDragMode.TOP_LEFT
                            near(bounds.right, bounds.top) -> ScanFeedbackDragMode.TOP_RIGHT
                            near(bounds.left, bounds.bottom) -> ScanFeedbackDragMode.BOTTOM_LEFT
                            near(bounds.right, bounds.bottom) -> ScanFeedbackDragMode.BOTTOM_RIGHT
                            kotlin.math.abs(firstDown.position.x - bounds.left) <= handleRadius && firstDown.position.y in bounds.top..bounds.bottom -> ScanFeedbackDragMode.LEFT
                            kotlin.math.abs(firstDown.position.x - bounds.right) <= handleRadius && firstDown.position.y in bounds.top..bounds.bottom -> ScanFeedbackDragMode.RIGHT
                            kotlin.math.abs(firstDown.position.y - bounds.top) <= handleRadius && firstDown.position.x in bounds.left..bounds.right -> ScanFeedbackDragMode.TOP
                            kotlin.math.abs(firstDown.position.y - bounds.bottom) <= handleRadius && firstDown.position.x in bounds.left..bounds.right -> ScanFeedbackDragMode.BOTTOM
                            else -> ScanFeedbackDragMode.MOVE
                        }
                    }
                    var previousCentroid: Offset? = null
                    var previousSpan: Float? = null
                    var transformed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            // Two fingers always own the viewport. This prevents a
                            // handle from moving when the user intended to zoom.
                            transformed = true
                            val first = pressed[0].position
                            val second = pressed[1].position
                            val centroid = (first + second) / 2f
                            val span = first.distanceTo(second).coerceAtLeast(1f)
                            previousCentroid?.let { oldCentroid ->
                                val scaleChange = span / (previousSpan ?: span)
                                val nextZoom = (latestZoom.value * scaleChange).coerceIn(SCAN_FEEDBACK_MIN_ZOOM, SCAN_FEEDBACK_MAX_ZOOM)
                                zoom = nextZoom
                                pan = clampScanFeedbackPan(viewport, nextZoom, latestPan.value + (centroid - oldCentroid))
                            }
                            previousCentroid = centroid
                            previousSpan = span
                            pressed.forEach { it.consume() }
                            continue
                        }
                        val change = pressed.single()
                        val delta = change.position - change.previousPosition
                        if (transformed) { change.consume(); continue }
                        if (dragIndex < 0) {
                            pan = clampScanFeedbackPan(viewport, latestZoom.value, latestPan.value + delta)
                        } else {
                            val mark = latestMarks.value.getOrNull(dragIndex) ?: continue
                            val horizontal = delta.x / (viewport.width * latestZoom.value).coerceAtLeast(1f)
                            val vertical = delta.y / (viewport.height * latestZoom.value).coerceAtLeast(1f)
                            val changed = when (dragMode) {
                                ScanFeedbackDragMode.MOVE -> mark.copy(
                                    left = (mark.left + horizontal).coerceIn(0f, 1f - mark.width),
                                    top = (mark.top + vertical).coerceIn(0f, 1f - mark.height),
                                )
                                else -> {
                                    val minWidth = .04f
                                    val minHeight = .035f
                                    var left = mark.left
                                    var top = mark.top
                                    var right = mark.left + mark.width
                                    var bottom = mark.top + mark.height
                                    if (dragMode in setOf(ScanFeedbackDragMode.LEFT, ScanFeedbackDragMode.TOP_LEFT, ScanFeedbackDragMode.BOTTOM_LEFT)) left = (left + horizontal).coerceIn(0f, right - minWidth)
                                    if (dragMode in setOf(ScanFeedbackDragMode.RIGHT, ScanFeedbackDragMode.TOP_RIGHT, ScanFeedbackDragMode.BOTTOM_RIGHT)) right = (right + horizontal).coerceIn(left + minWidth, 1f)
                                    if (dragMode in setOf(ScanFeedbackDragMode.TOP, ScanFeedbackDragMode.TOP_LEFT, ScanFeedbackDragMode.TOP_RIGHT)) top = (top + vertical).coerceIn(0f, bottom - minHeight)
                                    if (dragMode in setOf(ScanFeedbackDragMode.BOTTOM, ScanFeedbackDragMode.BOTTOM_LEFT, ScanFeedbackDragMode.BOTTOM_RIGHT)) bottom = (bottom + vertical).coerceIn(top + minHeight, 1f)
                                    mark.copy(left = left, top = top, width = right - left, height = bottom - top)
                                }
                            }
                            marks = latestMarks.value.mapIndexed { itemIndex, item -> if (itemIndex == dragIndex) changed else item }
                        }
                        change.consume()
                    }
                }
            }) {
                Box(Modifier.fillMaxSize().graphicsLayer { transformOrigin = TransformOrigin(.5f, .5f); scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }) {
                AsyncImage(model = imageFile, contentDescription = "Scanbild für Feedback", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    marks.forEachIndexed { index, mark ->
                        val color = if (mark.negative) VaultGold else MewIce
                        val topLeft = Offset(mark.left * size.width, mark.top * size.height)
                        val markSize = androidx.compose.ui.geometry.Size(mark.width * size.width, mark.height * size.height)
                        val bottomRight = Offset(topLeft.x + markSize.width, topLeft.y + markSize.height)
                        drawRect(color.copy(alpha = if (index == selected) .18f else .10f), topLeft, markSize)
                        drawRect(color, topLeft, markSize, style = Stroke(if (index == selected) 5f else 3f))
                        val topRight = Offset(bottomRight.x, topLeft.y)
                        val bottomLeft = Offset(topLeft.x, bottomRight.y)
                        val middleTop = Offset((topLeft.x + bottomRight.x) / 2f, topLeft.y)
                        val middleBottom = Offset((topLeft.x + bottomRight.x) / 2f, bottomRight.y)
                        val middleLeft = Offset(topLeft.x, (topLeft.y + bottomRight.y) / 2f)
                        val middleRight = Offset(bottomRight.x, (topLeft.y + bottomRight.y) / 2f)
                        val handleColor = if (index == selected) MewRose else color
                        listOf(topLeft, topRight, bottomLeft, bottomRight, middleTop, middleBottom, middleLeft, middleRight)
                            .forEach { drawCircle(handleColor, if (index == selected) 11f else 6f, it) }
                    }
                }
                }
            }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { marks = marks + ScanFeedbackMark(top = (.18f + marks.size * .08f).coerceAtMost(.75f)); selected = marks.lastIndex + 1 }) { Text("Bereich hinzufügen") }; TextButton(enabled = marks.size > 1, onClick = { marks = marks.filterIndexed { index, _ -> index != selected }; selected = (selected - 1).coerceAtLeast(0) }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } }
            if (marks.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { marks.indices.forEach { index -> FilterChip(selected == index, { selected = index }, label = { Text("${index + 1}") }) } }
            }
            active?.let { mark ->
                if (editorStep == 1) {
                    Box { OutlinedButton(onClick = { categoryOpen = true }, modifier = Modifier.fillMaxWidth()) { Text(mark.category, modifier = Modifier.weight(1f)); Text("⌄") }; DropdownMenu(expanded = categoryOpen, onDismissRequest = { categoryOpen = false }) { categories.forEach { category -> DropdownMenuItem(text = { Text(category) }, onClick = { updateActive { it.copy(category = category) }; categoryOpen = false }) } } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(!mark.negative, { updateActive { it.copy(negative = false) } }, label = { Text("Cyan · hilfreich") }); FilterChip(mark.negative, { updateActive { it.copy(negative = true) } }, label = { Text("Gelb · Falscherkennung") }) }
                } else if (editorStep == 2) {
                    OutlinedTextField(mark.detectedText, { value -> updateActive { it.copy(detectedText = value) } }, label = { Text("Erkannter Text") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(mark.correctedText, { value -> updateActive { it.copy(correctedText = value) } }, label = { Text("Korrigierter Text") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FeedbackSlider("Breite", mark.width) { value -> updateActive { it.copy(width = value.coerceIn(.06f, 1f - it.left)) } }
                    FeedbackSlider("Höhe", mark.height) { value -> updateActive { it.copy(height = value.coerceIn(.05f, 1f - it.top)) } }
                }
            }
        }
    }, confirmButton = { TextButton(enabled = marks.isNotEmpty(), onClick = { if (editorStep < 2) editorStep++ else runCatching { persistScanFeedback(context, imageFile, marks, analysis) }.onSuccess { onConfirmed() } }) { Text(if (editorStep < 2) "Weiter" else "Lokal speichern") } }, dismissButton = { TextButton(onClick = { if (editorStep > 0) editorStep-- else onDismiss() }) { Text(if (editorStep > 0) "Zurück" else "Abbrechen") } })
}

@Composable private fun FeedbackLegendSwatch(color: Color, label: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(color))
    Text(label, color = VaultTextMuted, fontSize = 10.sp)
}

@Composable private fun FeedbackSlider(label: String, value: Float, onValueChange: (Float) -> Unit) = Column { Text(label, color = VaultTextMuted, fontSize = 11.sp); Slider(value = value, onValueChange = onValueChange, valueRange = .05f..1f) }

private fun persistScanFeedback(context: Context, imageFile: File, marks: List<ScanFeedbackMark>, analysis: CardTextAnalysis?) {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val record = ScanFeedback(
        id = UUID.randomUUID().toString(),
        createdAtEpochMillis = System.currentTimeMillis(),
        imageRelativePath = "",
        originalPrediction = analysis?.searchQueries?.firstOrNull(),
        originalConfidence = ScannerDebugState.snapshot.finalConfidence.takeIf { it > 0 },
        originalOcrResults = analysis?.roiEvidence?.flatMap { it.rawValues }?.filter { it.isNotBlank() }.orEmpty(),
        annotations = marks.map { mark -> ScanFeedbackAnnotation(
            x = mark.left, y = mark.top, width = mark.width, height = mark.height,
            label = mark.category.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_'),
            type = if (mark.negative) ScanFeedbackAnnotationType.NEGATIVE else ScanFeedbackAnnotationType.POSITIVE,
            detectedText = mark.detectedText.trim().takeIf { it.isNotBlank() },
            correctedText = mark.correctedText.trim().takeIf { it.isNotBlank() },
        ) },
        appVersion = packageInfo.versionName ?: "unbekannt",
        notes = null,
    )
    ScanFeedbackStore(context).save(imageFile, record)
}

@Composable private fun ManualScanEvidenceDialog(initialSetCode: String, initialNumber: String, onDismiss: () -> Unit, onConfirm: (String, String, String) -> Unit) {
    var selectedSetIdentity by rememberSaveable { mutableStateOf(initialSetCode) }
    // Classic Collection keeps historic denominators; regular sets derive theirs from metadata.
    var number by rememberSaveable { mutableStateOf(initialNumber.filter { it.isDigit() || it == '/' }.take(9)) }
    var setMenuOpen by remember { mutableStateOf(false) }
    var setSearch by rememberSaveable { mutableStateOf("") }
    var catalogSets by remember { mutableStateOf<List<PokemonSet>>(emptyList()) }
    var setsLoading by remember { mutableStateOf(true) }
    val visualCatalog = remember { PokemonVisualCatalog() }
    LaunchedEffect(Unit) {
        runCatching { visualCatalog.sets() }.onSuccess { catalogSets = it }
        setsLoading = false
    }
    val setChoices = remember(catalogSets) {
        // Local ME04 compatibility stays explicit; every fetched set code comes
        // from the central registry, never from a translated display name.
        (PokemonSetRegistry.legacyOfflineSets().map { set ->
            PokemonSetRegistry.metadata(set).let { metadata ->
                ScannerSetChoice(metadata.setId, metadata.germanName, metadata.setCode ?: metadata.setId.uppercase(), metadata.mainSetSize)
            }
        } + catalogSets.map { set ->
            PokemonSetRegistry.metadata(set).let { metadata ->
                ScannerSetChoice(
                    id = metadata.setId,
                    name = metadata.germanName,
                    code = metadata.setCode?.takeIf { it.isNotBlank() } ?: metadata.setId.uppercase(),
                    printedTotal = metadata.mainSetSize.takeIf { total -> total > 0 } ?: metadata.totalKnownCards,
                )
            }
        }).distinctBy { PokemonSetRegistry.normalize(it.id) }
    }
    val filteredChoices = remember(setChoices, setSearch) {
        setChoices.filter { choice ->
            setSearch.isBlank() || choice.name.contains(setSearch, true) || choice.code.contains(setSearch, true) || choice.id.contains(setSearch, true)
        }.take(120)
    }
    val selectedSet = remember(setChoices, selectedSetIdentity, number) {
        resolveScannerSetChoice(setChoices, selectedSetIdentity, number)
    }
    val selectedClassicCollection = selectedSet?.id.equals("30th-c", ignoreCase = true)
    val totalLabel = selectedSet?.printedTotal?.takeIf { it > 0 }?.toString()?.padStart(3, '0')
    val canonicalNumber = selectedSet?.let { manualCollectorNumber(number, it.printedTotal, it.id, it.code) }
    val valid = selectedSet != null && canonicalNumber != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set unten auf der Karte") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("1. Set auswählen.  2. Kartennummer eingeben. DomiDex sucht danach ausschließlich in diesem Set.", color = VaultTextMuted, fontSize = 12.sp)
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { setMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedSet?.let { "${it.name} · ${it.code}" } ?: "Set aus Katalog auswählen")
                }
                DropdownMenu(expanded = setMenuOpen, onDismissRequest = { setMenuOpen = false }, modifier = Modifier.fillMaxWidth(.92f).heightIn(max = 330.dp)) {
                    OutlinedTextField(setSearch, { setSearch = it }, label = { Text("Set suchen") }, singleLine = true, modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth())
                    if (setsLoading) DropdownMenuItem(text = { Text("Sets werden geladen …") }, enabled = false, onClick = {})
                    else if (filteredChoices.isEmpty()) DropdownMenuItem(text = { Text("Kein passendes Set gefunden") }, enabled = false, onClick = {})
                    else filteredChoices.forEach { choice -> DropdownMenuItem(text = { Column { Text(choice.name, fontWeight = FontWeight.SemiBold); Text("${choice.code} · ${choice.printedTotal} Karten", color = MewIce, fontSize = 11.sp) } }, onClick = { selectedSetIdentity = choice.id; setSearch = ""; setMenuOpen = false }) }
                }
            }
            OutlinedTextField(
                number,
                { value -> number = value.filter { it.isDigit() || (selectedClassicCollection && it == '/') }.take(if (selectedClassicCollection) 9 else 4) },
                enabled = selectedSet != null,
                label = { Text(if (selectedClassicCollection) "Historische Nummer oder Subset-Platz" else "Kartennummer, z. B. 122") },
                supportingText = { Text(canonicalNumber?.let { "Gesucht wird: ${selectedSet?.name} · $it" } ?: if (selectedClassicCollection) "Zum Beispiel 89/149 oder Subset-Platz 17." else totalLabel?.let { "Set hat insgesamt $it Karten." } ?: "Zuerst ein Set auswählen.") },
                singleLine = true,
            )
        } },
        confirmButton = { TextButton(enabled = valid, onClick = { selectedSet?.let { onConfirm(it.id, it.code, canonicalNumber!!) } }) { Text("Karte suchen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable private fun ScanAddDialog(
    candidate: CatalogSearchResult,
    repository: CollectionRepository,
    adding: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onAdd: (CatalogSearchResult, ManualCardInput, BinderTarget) -> Unit,
) {
    val privateBinderSummaries by repository.privateBinders.collectAsState(initial = emptyList())
    var selectedPrivateBinderId by rememberSaveable(candidate.scanIdentityKey()) { mutableStateOf<String?>(null) }
    var selectedCandidate by remember(candidate.scanIdentityKey()) { mutableStateOf(candidate) }
    var choosingCatalog by remember { mutableStateOf(false) }
    if (choosingCatalog) {
        CatalogProductPicker(repository, onDismiss = { choosingCatalog = false }, onSelected = {
            selectedCandidate = it
            choosingCatalog = false
        })
        return
    }
    val selected = selectedCandidate
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zum Binder hinzufügen") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(selected.name, fontWeight = FontWeight.Bold)
            BinderDestinationSummary(
                setName = selected.visualSetName ?: selected.expansionId,
                number = selected.collectorNumber.orEmpty(),
                finish = selected.finish ?: "Normal",
                sourceConfirmed = !selected.sourceSetId.isNullOrBlank() && !selected.sourceCardId.isNullOrBlank(),
                onChange = { choosingCatalog = true },
                enabled = !adding,
            )
            BinderTargetPicker(
                setName = selected.visualSetName ?: selected.expansionId,
                privateBinders = privateBinderSummaries,
                selectedPrivateBinderId = selectedPrivateBinderId,
                onSelectedPrivateBinderId = { selectedPrivateBinderId = it },
                enabled = !adding,
            )
            selected.trendPriceEur?.let { price ->
                Text("Cardmarket-Trendpreis: ${euro(price)}", color = MewIce, fontWeight = FontWeight.SemiBold)
            } ?: Text(
                if (selected.hasMarketProduct) "Für dieses Cardmarket-Produkt liegt aktuell kein Trendpreis vor."
                else "Noch kein eindeutig passendes Cardmarket-Produkt – DomiDex erfindet keinen Preis.",
                color = VaultTextMuted,
                fontSize = 12.sp,
            )
            if (adding) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        } },
        confirmButton = { TextButton(enabled = !adding, onClick = {
            onAdd(
                selected,
                ManualCardInput(selected.name, selected.visualSetName ?: selected.expansionId, selected.collectorNumber.orEmpty(), CardLanguage.GERMAN, selected.finish ?: "Normal", selected.variant ?: "Standard", 1, "Nahezu neuwertig", "", null, null, selected.productId.takeIf { selected.hasMarketProduct }, selected.imageUrl, selected.rarity, null, selected.expansionId.takeIf { selected.hasMarketProduct }, sourceSetId = selected.sourceSetId, sourceCardId = selected.sourceCardId),
                binderTarget(selectedPrivateBinderId),
            )
        }) { Text("In diesen Binder hinzufügen") } },
        dismissButton = { TextButton(enabled = !adding, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
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
    uploading: Boolean,
    failure: StorageFailureReason?,
    onContribute: () -> Unit,
    onWithdraw: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface), shape = CardVaultShapes.card) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.community_contribution_title), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.community_contribution_eligible), color = VaultTextMuted, fontSize = 12.sp)
            Text(stringResource(R.string.community_contribution_optional), color = VaultTextMuted, fontSize = 11.sp)
            failure?.let { Text(contributionFailureMessage(it), color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
            if (!locallyMarked) {
                Button(onClick = { if (!optedIn) onOptInChanged(true); confirm = true }, enabled = !uploading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (uploading) R.string.community_contribution_uploading else R.string.community_contribution_review)) }
                TextButton(onClick = onPromptHandled, enabled = !uploading, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.community_contribution_not_now)) }
                Text(stringResource(R.string.community_contribution_not_live), color = VaultTextMuted, fontSize = 10.sp)
            } else {
                Text(stringResource(R.string.community_contribution_local_pending), color = MewIce, fontSize = 11.sp)
                TextButton(onClick = onWithdraw, enabled = !uploading) { Text(stringResource(R.string.community_contribution_withdraw)) }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.community_contribution_confirm_title)) },
        text = { Text(stringResource(R.string.community_contribution_confirm_body)) },
        confirmButton = { TextButton(onClick = { confirm = false; onContribute() }) { Text(stringResource(R.string.community_contribution_confirm_action)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable private fun contributionFailureMessage(reason: StorageFailureReason): String = when (reason) {
    StorageFailureReason.NOT_SIGNED_IN -> stringResource(R.string.community_contribution_sign_in_required)
    StorageFailureReason.FILE_TOO_LARGE -> stringResource(R.string.community_contribution_too_large)
    StorageFailureReason.UNSUPPORTED_IMAGE, StorageFailureReason.EMPTY_FILE -> stringResource(R.string.community_contribution_invalid_image)
    StorageFailureReason.PERMISSION_DENIED -> stringResource(R.string.community_contribution_permission_denied)
    StorageFailureReason.NETWORK -> stringResource(R.string.community_contribution_network_failed)
    StorageFailureReason.FILE_MISSING, StorageFailureReason.INVALID_PATH, StorageFailureReason.UNKNOWN -> stringResource(R.string.community_contribution_upload_failed)
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
