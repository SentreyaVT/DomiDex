package com.dominik.cardvault.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.dominik.cardvault.R
import com.dominik.cardvault.auth.FirebaseGoogleAuthGateway
import com.dominik.cardvault.auth.GoogleAccountState
import com.dominik.cardvault.auth.GoogleLoginResult
import com.dominik.cardvault.model.*
import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import com.dominik.cardvault.ui.theme.*
import coil3.compose.AsyncImage
import java.text.NumberFormat
import java.util.Locale
import java.time.LocalDate
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private enum class Destination(@param:StringRes val label: Int, val glyph: String) {
    DASHBOARD(R.string.nav_portfolio, "⌂"), SCANNER(R.string.nav_scanner, "▣"),
    COLLECTION(R.string.nav_collection, "▤"), GRADING(R.string.nav_grading, "◇")
}

@Composable fun CardVaultApp(repository: CollectionRepository, refreshCatalog: () -> Unit = {}) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("cardvault_ui", Context.MODE_PRIVATE) }
    var focusChoice by rememberSaveable { mutableStateOf(preferences.getString("dashboard_focus", "auto") ?: "auto") }
    var destination by rememberSaveable { mutableStateOf(Destination.DASHBOARD) }
    val portfolio by repository.portfolio.collectAsState(initial = PortfolioSummary.Empty)
    val syncStatus by repository.catalogSyncStatus.collectAsState(initial = CatalogSyncStatus("IDLE", null, null, 0, 0, null))
    Scaffold(containerColor = Color.Transparent, bottomBar = {
        NavigationBar(containerColor = VaultNightRaised, tonalElevation = 0.dp) { Destination.entries.forEach { item -> NavigationBarItem(
            selected = destination == item,
            onClick = { destination = item },
            icon = { Text(item.glyph, modifier = Modifier.clearAndSetSemantics { }, fontSize = 21.sp, fontWeight = FontWeight.Bold) },
            label = { Text(stringResource(item.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = NavigationBarItemDefaults.colors(selectedIconColor = MewRose, selectedTextColor = VaultText, indicatorColor = MewPlumDeep, unselectedIconColor = VaultTextMuted, unselectedTextColor = VaultTextMuted),
        ) } }
    }) { padding -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(VaultNight, Color(0xFF121024), VaultNight))).padding(padding)) {
        when (destination) {
            Destination.DASHBOARD -> DashboardScreen(portfolio, syncStatus, refreshCatalog, focusChoice, { choice -> focusChoice = choice; preferences.edit().putString("dashboard_focus", choice).apply() }) { destination = Destination.SCANNER }
            Destination.SCANNER -> ScannerScreen(repository)
            Destination.COLLECTION -> CollectionScreen(portfolio.cards, repository)
            Destination.GRADING -> GradingScreen()
        }
    } }
}

@Composable private fun ScreenHeader(eyebrow: String, title: String, subtitle: String) = Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(eyebrow.uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.5.sp)
    Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = VaultText)
    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun DashboardScreen(portfolio: PortfolioSummary, syncStatus: CatalogSyncStatus, refreshCatalog: () -> Unit, focusChoice: String, onFocusChoice: (String) -> Unit, openScanner: () -> Unit) = LazyColumn(contentPadding = PaddingValues(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(CardVaultSpacing.section)) {
    item { ScreenHeader(stringResource(R.string.dashboard_eyebrow), stringResource(R.string.dashboard_title), stringResource(R.string.dashboard_subtitle)) }
    item { PortfolioHero(portfolio, focusChoice, onFocusChoice) }
    item { CatalogSyncCard(syncStatus, refreshCatalog) }
    item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { StatCard(stringResource(R.string.stat_cards), portfolio.totalCards.toString(), Modifier.weight(1f)); StatCard(stringResource(R.string.stat_unique), portfolio.cards.size.toString(), Modifier.weight(1f)); StatCard(stringResource(R.string.stat_languages), portfolio.cards.map { it.language }.distinct().size.toString(), Modifier.weight(1f)) } }
    item { ElevatedCard(shape = CardVaultShapes.card, colors = CardDefaults.elevatedCardColors(containerColor = VaultPlumSurface)) { Column(Modifier.padding(16.dp)) { Text(stringResource(R.string.portfolio_history), color = VaultTextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); ValueHistoryChart(portfolio.history, Modifier.fillMaxWidth().height(92.dp)) } } }
    item { Button(openScanner, Modifier.fillMaxWidth().height(CardVaultSpacing.buttonHeight), shape = CardVaultShapes.card) { Text(stringResource(R.string.scan_card), fontWeight = FontWeight.Bold) } }
    item { SupportInfoCard() }
    item { GoogleAccountCard() }
    item { Text(stringResource(R.string.recent_cards), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
    items(portfolio.cards.take(3)) { CardRow(it) }; item { DataNote() }
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
            Text(stringResource(if (AppSupportConfig.isConfigured) R.string.support_ready else R.string.support_not_configured), color = VaultTextMuted, fontSize = 11.sp)
            OutlinedButton(onClick = { openSupportEmail(context) }, enabled = AppSupportConfig.isConfigured, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.support_contact)) }
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.support_inbox)) }
            Text(stringResource(R.string.support_inbox_not_configured), color = VaultTextMuted, fontSize = 10.sp)
            Text(stringResource(R.string.support_privacy), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        }
    }
}

private fun openSupportEmail(context: Context) {
    if (!AppSupportConfig.isConfigured) return
    val uri = Uri.Builder().scheme("mailto").opaquePart(AppSupportConfig.email).build()
    val intent = Intent(Intent.ACTION_SENDTO, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "CardVault – Supportanfrage")
        .putExtra(Intent.EXTRA_TEXT, "Problembeschreibung:\n\n\nApp-Version: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}\n\nKeine Diagnose- oder Sammlungsdaten wurden automatisch angehängt.")
    context.startActivity(intent)
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
                    Text(euro(portfolio.totalValue), fontSize = 30.sp, fontWeight = FontWeight.Black, color = VaultText, maxLines = 1)
                    Text(stringResource(R.string.portfolio_change, portfolio.changePercent), color = VaultPositive, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
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

@Composable private fun InteractiveFocusCard(card: CollectionCard?, modifier: Modifier = Modifier, shineEnabled: Boolean = true) {
    val context = LocalContext.current
    var sensorX by remember { mutableFloatStateOf(0f) }; var sensorY by remember { mutableFloatStateOf(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }; var dragY by remember { mutableFloatStateOf(0f) }
    DisposableEffect(context) {
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
    Box(modifier.graphicsLayer { rotationX = sensorX + dragY; rotationY = sensorY + dragX; cameraDistance = 14 * density }
        .pointerInput(Unit) { detectDragGestures(onDragEnd = { dragX = 0f; dragY = 0f }) { change, amount -> change.consume(); dragX = (dragX + amount.x / 8f).coerceIn(-12f, 12f); dragY = (dragY - amount.y / 8f).coerceIn(-12f, 12f) } }
        .background(VaultNightRaised, CardVaultShapes.card).border(1.dp, MewIce.copy(alpha = .65f), CardVaultShapes.card)) {
        if (card?.imageUrl != null) AsyncImage(card.imageUrl, stringResource(R.string.focus_card_description, card.name), Modifier.fillMaxSize()) else NeutralCardFace(Modifier.fillMaxSize())
        if (shineEnabled) Canvas(Modifier.matchParentSize().clearAndSetSemantics { }) { drawRect(Brush.linearGradient(listOf(Color.Transparent, MewIce.copy(alpha = .18f), MewRose.copy(alpha = .10f), Color.Transparent)), alpha = .7f) }
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
    val scope = rememberCoroutineScope()
    val filtered = cards.filter { it.name.contains(query, true) || it.set.contains(query, true) }
    LazyColumn(contentPadding = PaddingValues(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(CardVaultSpacing.card)) {
    item { val count = cards.sumOf { it.quantity }; ScreenHeader(stringResource(R.string.collection_eyebrow), stringResource(R.string.collection_title), pluralStringResource(R.plurals.collection_cards_count, count, count) + " · " + pluralStringResource(R.plurals.collection_entries_count, cards.size, cards.size)) }
    item { Button(onClick = { choosingAddMode = true }, modifier = Modifier.fillMaxWidth(), shape = CardVaultShapes.card) { Text(stringResource(R.string.collection_add), fontWeight = FontWeight.Bold) } }
    addOutcome?.let { outcome -> item { Text(stringResource(if (outcome == AddCardOutcome.QUANTITY_INCREMENTED) R.string.add_outcome_incremented else R.string.add_outcome_created), color = VaultPositive, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) } }
    item { OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.collection_search)) }, singleLine = true, shape = CardVaultShapes.field, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MewRose, cursorColor = MewRose, focusedLabelColor = MewRose, unfocusedContainerColor = VaultNightRaised, focusedContainerColor = VaultNightRaised), modifier = Modifier.fillMaxWidth()) }
    if (filtered.isEmpty()) item { Text(stringResource(R.string.collection_empty), modifier = Modifier.padding(vertical = 30.dp)) }
    items(filtered, key = { it.entryId }) { card -> CardRow(card) { viewing = card } }; item { DataNote() }
    }
    if (choosingAddMode) AddModeDialog(onDismiss = { choosingAddMode = false }, onCatalog = { choosingAddMode = false; choosingCatalogCard = true }, onManual = { choosingAddMode = false; catalogDraft = null; adding = true })
    if (choosingCatalogCard) CatalogProductPicker(repository, onDismiss = { choosingCatalogCard = false }, onSelected = { catalogDraft = it; choosingCatalogCard = false; adding = true })
    if (adding) ManualCardDialog(initialCatalog = catalogDraft, onDismiss = { adding = false; catalogDraft = null }, onSave = { input -> scope.launch { addOutcome = repository.addManualCard(input) }; adding = false; catalogDraft = null })
    viewing?.let { card -> CardPreviewDialog(card, onDismiss = { viewing = null }, onEdit = { viewing = null; editing = card }) }
    editing?.let { card -> EditEntryDialog(card, onDismiss = { editing = null }, onDelete = { editing = null; deleting = card }, onLink = { editing = null; linking = card }, onSave = { update -> scope.launch { repository.updateEntry(card.entryId, update) }; editing = null }) }
    linking?.let { card -> CatalogLinkDialog(card, repository, onDismiss = { linking = null }, onLinked = { productId -> scope.launch { repository.linkCatalogProduct(card.entryId, productId) }; linking = null }) }
    deleting?.let { card -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete_entry_title)) }, text = { Text(stringResource(R.string.delete_entry_body, card.name)) }, confirmButton = { TextButton(onClick = { scope.launch { repository.deleteEntry(card.entryId) }; deleting = null }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } }) }
}

@Composable private fun CardPreviewDialog(card: CollectionCard, onDismiss: () -> Unit, onEdit: () -> Unit) {
    var shine by rememberSaveable(card.entryId) { mutableStateOf(false) }
    val context = LocalContext.current
    val priceDate = card.priceUpdatedAtEpochMillis?.let { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, Locale.GERMANY).format(java.util.Date(it)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(card.name) }, text = { LazyColumn(Modifier.fillMaxWidth().heightIn(max = 570.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { InteractiveFocusCard(card, Modifier.size(174.dp, 246.dp), shineEnabled = shine) }
        item { Text("${card.set} · ${card.number}", fontWeight = FontWeight.Bold) }
        item { Text(stringResource(R.string.card_preview_variant, card.language.label, card.finish, card.variant), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(stringResource(R.string.card_preview_shine), fontWeight = FontWeight.SemiBold); Text(stringResource(R.string.card_preview_shine_hint), color = VaultTextMuted, fontSize = 10.sp) }; Switch(shine, { shine = it }) } }
        item { HorizontalDivider(color = VaultOutline) }
        item { PriceSourcesSection(card, priceDate, context) }
    } }, confirmButton = { TextButton(onClick = onEdit) { Text(stringResource(R.string.card_preview_edit)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

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
    Text(stringResource(R.string.source_separation_explanation), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
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
    var loading by remember { mutableStateOf(true) }; var error by remember { mutableStateOf(false) }
    LaunchedEffect(selectedSet) {
        loading = true; error = false; query = ""
        runCatching { if (selectedSet == null) sets = visualCatalog.sets() else cards = visualCatalog.cards(selectedSet!!) }
            .onFailure { error = true }
        loading = false
    }
    LaunchedEffect(selectedCard) {
        val card = selectedCard ?: return@LaunchedEffect
        loading = true
        products = repository.searchCatalog(card.name, 40).map { it.copy(visualSetName = card.set.name, collectorNumber = card.number, imageUrl = card.largeImageUrl) }
        loading = false
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(when { selectedCard != null -> stringResource(R.string.catalog_choose_product); selectedSet != null -> selectedSet!!.name; else -> stringResource(R.string.catalog_choose_set) }) }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(when { selectedCard != null -> R.string.catalog_confirm_price_identity; selectedSet != null -> R.string.catalog_choose_card_hint; else -> R.string.catalog_choose_set_hint }), color = VaultTextMuted, fontSize = 12.sp)
        if (selectedSet == null) Text(stringResource(R.string.catalog_coverage_boundary), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        if (selectedCard == null) OutlinedTextField(query, { query = it }, label = { Text(stringResource(if (selectedSet == null) R.string.catalog_set_search else R.string.catalog_card_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = CardVaultShapes.field)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error) Text(stringResource(R.string.catalog_visual_error), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        if (!loading && !error && selectedSet == null) Text(stringResource(R.string.catalog_set_count, sets.size), color = VaultPositive, fontSize = 11.sp)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                selectedCard != null -> {
                    item { VisualCardRow(selectedCard!!) {} }
                    if (!loading && products.isEmpty()) item { Text(stringResource(R.string.catalog_no_price_match), color = VaultTextMuted, fontSize = 12.sp) }
                    items(products, key = { it.productId }) { result -> CatalogResultRow(result) { onSelected(result) } }
                }
                selectedSet != null -> items(cards.filter { matchesCardInSelectedSet(it.name, it.number, query) }, key = { it.id }) { card -> VisualCardRow(card) { selectedCard = card } }
                else -> items(sets.filter { query.isBlank() || it.name.contains(query, true) || it.series.contains(query, true) }, key = { it.id }) { set ->
                    OutlinedCard(onClick = { selectedSet = set }, colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = set.logoUrl, contentDescription = null, modifier = Modifier.size(64.dp, 40.dp)); Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(set.name, fontWeight = FontWeight.Bold); Text("${set.series} · ${set.printedTotal} Karten · ${set.releaseDate}", color = VaultTextMuted, fontSize = 11.sp) }
                    } }
                }
            }
        }
    } }, confirmButton = {}, dismissButton = { Row { if (selectedCard != null || selectedSet != null) TextButton(onClick = { if (selectedCard != null) { selectedCard = null; products = emptyList() } else selectedSet = null }) { Text(stringResource(R.string.action_back)) }; TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } } })
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

@Composable private fun ManualCardDialog(initialCatalog: CatalogSearchResult? = null, onDismiss: () -> Unit, onSave: (ManualCardInput) -> Unit) {
    var name by rememberSaveable(initialCatalog?.productId) { mutableStateOf(initialCatalog?.name ?: "") }; var set by rememberSaveable(initialCatalog?.productId) { mutableStateOf(initialCatalog?.visualSetName ?: initialCatalog?.let { "Cardmarket-Erweiterung ${it.expansionId}" } ?: "") }; var number by rememberSaveable(initialCatalog?.productId) { mutableStateOf(initialCatalog?.collectorNumber ?: "") }
    var language by rememberSaveable { mutableStateOf(CardLanguage.GERMAN) }; var finish by rememberSaveable { mutableStateOf("Normal") }; var variant by rememberSaveable { mutableStateOf("Standard") }
    var quantity by rememberSaveable { mutableStateOf("1") }; var condition by rememberSaveable { mutableStateOf("Nahezu neuwertig") }; var notes by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }; var date by rememberSaveable { mutableStateOf("") }
    val valid = name.isNotBlank() && set.isNotBlank() && number.isNotBlank() && condition.isNotBlank() && (quantity.toIntOrNull() ?: 0) > 0 && parseDate(date) != Long.MIN_VALUE && parsePrice(price) != Double.NEGATIVE_INFINITY
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.manual_add_title)) }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(stringResource(if (initialCatalog == null) R.string.manual_add_boundary else R.string.catalog_add_linked_boundary), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            item { FormField(name, { name = it }, R.string.field_card_name) }; item { FormField(set, { set = it }, R.string.field_set_name) }; item { FormField(number, { number = it }, R.string.field_card_number_printed); Text(stringResource(R.string.field_card_number_hint), color = VaultTextMuted, fontSize = 10.sp) }
            item { Text(stringResource(R.string.field_language), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(CardLanguage.GERMAN, CardLanguage.ENGLISH).forEach { item -> FilterChip(selected = language == item, onClick = { language = item }, label = { Text(item.label, fontSize = 11.sp) }) } }; Text(stringResource(R.string.language_scope_de_en), color = VaultTextMuted, fontSize = 10.sp) }
            item { Text(stringResource(R.string.field_finish), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(listOf("Normal", "Holo", "Reverse-Holo", "Sondervariante")) { option -> FilterChip(selected = finish == option, onClick = { finish = option }, label = { Text(option, fontSize = 11.sp) }) } } }; item { FormField(variant, { variant = it }, R.string.field_variant) }
            item { FormField(quantity, { quantity = it.filter(Char::isDigit) }, R.string.field_quantity, KeyboardType.Number) }; item { FormField(condition, { condition = it }, R.string.field_condition) }
            item { FormField(price, { price = it }, R.string.field_acquisition_price, KeyboardType.Decimal) }; item { FormField(date, { date = it }, R.string.field_acquisition_date) }; item { FormField(notes, { notes = it }, R.string.field_notes, singleLine = false) }
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = { onSave(ManualCardInput(name, set, number, language, finish.ifBlank { "Normal" }, variant.ifBlank { "Standard" }, quantity.toInt(), condition, notes, parsePrice(price).takeUnless { it == Double.NEGATIVE_INFINITY }, parseDate(date).takeUnless { it == Long.MIN_VALUE }, initialCatalog?.productId, initialCatalog?.imageUrl)) }) { Text(stringResource(R.string.action_save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable private fun EditEntryDialog(card: CollectionCard, onDismiss: () -> Unit, onDelete: () -> Unit, onLink: () -> Unit, onSave: (CollectionEntryUpdate) -> Unit) {
    var quantity by rememberSaveable(card.entryId) { mutableStateOf(card.quantity.toString()) }; var condition by rememberSaveable(card.entryId) { mutableStateOf(card.condition) }; var notes by rememberSaveable(card.entryId) { mutableStateOf(card.notes) }
    var price by rememberSaveable(card.entryId) { mutableStateOf(card.acquisitionPriceEur?.let { "%.2f".format(Locale.GERMANY, it) } ?: "") }; var date by rememberSaveable(card.entryId) { mutableStateOf(card.acquisitionDateEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "") }
    val valid = (quantity.toIntOrNull() ?: 0) > 0 && condition.isNotBlank() && parseDate(date) != Long.MIN_VALUE && parsePrice(price) != Double.NEGATIVE_INFINITY
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.edit_entry_title)) }, text = { LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("${card.name} · ${card.set}", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold) }
        item { OutlinedButton(onClick = onLink, modifier = Modifier.fillMaxWidth()) { Text(if (card.externalProductId == null) stringResource(R.string.catalog_link_action) else stringResource(R.string.catalog_change_action)) }; Text(if (card.externalProductId == null) stringResource(R.string.catalog_unlinked_hint) else stringResource(R.string.catalog_linked_hint, card.externalProductId), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
        item { OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.grading_action)) }; Text(stringResource(R.string.grading_card_future), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
        item { FormField(quantity, { quantity = it.filter(Char::isDigit) }, R.string.field_quantity, KeyboardType.Number) }; item { FormField(condition, { condition = it }, R.string.field_condition) }; item { FormField(price, { price = it }, R.string.field_acquisition_price, KeyboardType.Decimal) }; item { FormField(date, { date = it }, R.string.field_acquisition_date) }; item { FormField(notes, { notes = it }, R.string.field_notes, singleLine = false) }
    } }, confirmButton = { TextButton(enabled = valid, onClick = { onSave(CollectionEntryUpdate(quantity.toInt(), condition, notes, parsePrice(price).takeUnless { it == Double.NEGATIVE_INFINITY }, parseDate(date).takeUnless { it == Long.MIN_VALUE })) }) { Text(stringResource(R.string.action_save)) } }, dismissButton = { Row { TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }; TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } } })
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

@Composable private fun ScannerScreen(repository: CollectionRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var analysisRunning by remember { mutableStateOf(false) }
    var analysis by remember { mutableStateOf<CardTextAnalysis?>(null) }
    var analysisFailed by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<CatalogSearchResult>>(emptyList()) }
    var candidatesLoading by remember { mutableStateOf(false) }
    var selectedCandidate by remember { mutableStateOf<CatalogSearchResult?>(null) }
    val textAnalyzer = remember(context) { CardTextAnalyzer(context) }
    DisposableEffect(textAnalyzer) { onDispose { textAnalyzer.close() } }
    LaunchedEffect(analysis) {
        val current = analysis ?: return@LaunchedEffect
        candidatesLoading = true
        val found = linkedMapOf<Long, CatalogSearchResult>()
        runCatching {
            current.searchQueries.forEach { query ->
                if (found.size < 8) repository.searchCatalog(query, 8).forEach { found.putIfAbsent(it.productId, it) }
            }
        }
        candidates = found.values.take(8)
        candidatesLoading = false
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; denied = !it }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(CardVaultSpacing.section)) {
        item { ScreenHeader(stringResource(R.string.scanner_eyebrow), stringResource(R.string.scanner_title), stringResource(R.string.scanner_subtitle)) }
        item { Box(Modifier.fillMaxWidth().height(620.dp).background(Brush.linearGradient(listOf(Color(0xFF111326), Color(0xFF221B35))), CardVaultShapes.feature).border(1.dp, VaultOutline.copy(alpha = .75f), CardVaultShapes.feature), contentAlignment = Alignment.Center) {
            if (granted) {
                LiveCameraScanner(Modifier.fillMaxSize()) { file ->
                    analysisRunning = true; analysis = null; analysisFailed = false; candidates = emptyList()
                    textAnalyzer.analyze(file) { result ->
                        analysisRunning = false
                        result.onSuccess { analysis = it }.onFailure { analysisFailed = true }
                    }
                }
            } else Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.camera_permission_title), color = Color.White, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.camera_permission_body), color = Color(0xFFB9C9C5))
                Button({ launcher.launch(Manifest.permission.CAMERA) }) { Text(stringResource(if (denied) R.string.camera_permission_retry else R.string.camera_permission_allow)) }
            }
        } }
        if (analysisRunning) item { AnalysisStatusCard(R.string.scanner_analysis_running) }
        if (analysisFailed) item { AnalysisStatusCard(R.string.scanner_analysis_failed, error = true) }
        analysis?.let { result -> item { ScannerAnalysisCard(result, candidates, candidatesLoading) { selectedCandidate = it } } }
        item { AssistChip(onClick = {}, label = { Text(stringResource(R.string.scanner_quality_active)) }, enabled = false) }
        item { Text(stringResource(R.string.scanner_boundary), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
    }
    selectedCandidate?.let { candidate ->
        ManualCardDialog(initialCatalog = candidate, onDismiss = { selectedCandidate = null }, onSave = { input -> scope.launch { repository.addManualCard(input) }; selectedCandidate = null })
    }
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
    val categories = remember { listOf(GradeCategory(R.string.grading_centering, R.string.grading_centering_prompt), GradeCategory(R.string.grading_corners, R.string.grading_corners_prompt), GradeCategory(R.string.grading_edges, R.string.grading_edges_prompt), GradeCategory(R.string.grading_surface, R.string.grading_surface_prompt)) }
    var completed by rememberSaveable { mutableStateOf(setOf<Int>()) }
    val latestCapture = LatestCardCapture.path
    LazyColumn(contentPadding = PaddingValues(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenHeader(stringResource(R.string.grading_eyebrow), stringResource(R.string.grading_title), stringResource(R.string.grading_subtitle)) }
        item { ElevatedCard(shape = CardVaultShapes.card, colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Text(stringResource(R.string.grading_disclaimer), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onPrimaryContainer) } }
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
        item { OutlinedCard(shape = CardVaultShapes.card, colors = CardDefaults.outlinedCardColors(containerColor = VaultPlumSurface)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.submission_check_title), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.submission_check_body), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text(stringResource(R.string.submission_check_outcome), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        } } }
        item { Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.analysis_later)) } }
        item { Text(stringResource(R.string.grading_boundary), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
    }
}

@Composable private fun DataNote() = Text(stringResource(R.string.demo_data_note), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
private fun euro(value: Double) = NumberFormat.getCurrencyInstance(Locale.GERMANY).format(value)
