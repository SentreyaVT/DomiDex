package com.dominik.cardvault.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
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
import com.dominik.cardvault.model.*
import com.dominik.cardvault.ui.theme.*
import java.text.NumberFormat
import java.util.Locale
import java.time.LocalDate
import kotlinx.coroutines.launch

private enum class Destination(@param:StringRes val label: Int, val glyph: String) {
    DASHBOARD(R.string.nav_portfolio, "⌂"), SCANNER(R.string.nav_scanner, "▣"),
    COLLECTION(R.string.nav_collection, "▤"), GRADING(R.string.nav_grading, "◇")
}

@Composable fun CardVaultApp(repository: CollectionRepository, refreshCatalog: () -> Unit = {}) {
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
            Destination.DASHBOARD -> DashboardScreen(portfolio, syncStatus, refreshCatalog) { destination = Destination.SCANNER }
            Destination.SCANNER -> ScannerScreen()
            Destination.COLLECTION -> CollectionScreen(portfolio.cards, repository)
            Destination.GRADING -> GradingScreen()
        }
    } }
}

@Composable private fun ScreenHeader(eyebrow: String, title: String, subtitle: String) = Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(eyebrow.uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.5.sp)
    Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun DashboardScreen(portfolio: PortfolioSummary, syncStatus: CatalogSyncStatus, refreshCatalog: () -> Unit, openScanner: () -> Unit) = LazyColumn(contentPadding = PaddingValues(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(CardVaultSpacing.section)) {
    item { ScreenHeader(stringResource(R.string.dashboard_eyebrow), stringResource(R.string.dashboard_title), stringResource(R.string.dashboard_subtitle)) }
    item { PortfolioHero(portfolio) }
    item { CatalogSyncCard(syncStatus, refreshCatalog) }
    item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { StatCard(stringResource(R.string.stat_cards), portfolio.totalCards.toString(), Modifier.weight(1f)); StatCard(stringResource(R.string.stat_unique), portfolio.cards.size.toString(), Modifier.weight(1f)); StatCard(stringResource(R.string.stat_languages), portfolio.cards.map { it.language }.distinct().size.toString(), Modifier.weight(1f)) } }
    item { ElevatedCard(shape = CardVaultShapes.card, colors = CardDefaults.elevatedCardColors(containerColor = VaultPlumSurface)) { Column(Modifier.padding(16.dp)) { Text(stringResource(R.string.portfolio_history), color = VaultTextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); ValueHistoryChart(portfolio.history, Modifier.fillMaxWidth().height(92.dp)) } } }
    item { Button(openScanner, Modifier.fillMaxWidth().height(CardVaultSpacing.buttonHeight), shape = CardVaultShapes.card) { Text(stringResource(R.string.scan_card), fontWeight = FontWeight.Bold) } }
    item { Text(stringResource(R.string.recent_cards), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
    items(portfolio.cards.take(3)) { CardRow(it) }; item { DataNote() }
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

@Composable private fun PortfolioHero(portfolio: PortfolioSummary) {
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
            Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Surface(color = MewRose.copy(alpha = .16f), shape = CardVaultShapes.field) { Text(stringResource(R.string.portfolio_private_badge), Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = Color(0xFFFFD8E5), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    Text(stringResource(R.string.portfolio_estimated_value), color = VaultTextMuted, fontSize = 13.sp)
                    Text(euro(portfolio.totalValue), fontSize = 30.sp, fontWeight = FontWeight.Black, color = VaultText, maxLines = 1)
                    Text(stringResource(R.string.portfolio_change, portfolio.changePercent), color = VaultPositive, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
                Image(painter = painterResource(R.drawable.brand_dashboard_hero), contentDescription = stringResource(R.string.brand_dashboard_hero_description), modifier = Modifier.size(CardVaultSpacing.heroArt))
            }
        }
    }
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
    var editing by remember { mutableStateOf<CollectionCard?>(null) }
    var deleting by remember { mutableStateOf<CollectionCard?>(null) }
    val scope = rememberCoroutineScope()
    val filtered = cards.filter { it.name.contains(query, true) || it.set.contains(query, true) }
    LazyColumn(contentPadding = PaddingValues(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(CardVaultSpacing.card)) {
    item { val count = cards.sumOf { it.quantity }; ScreenHeader(stringResource(R.string.collection_eyebrow), stringResource(R.string.collection_title), pluralStringResource(R.plurals.collection_cards_count, count, count) + " · " + pluralStringResource(R.plurals.collection_entries_count, cards.size, cards.size)) }
    item { Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth(), shape = CardVaultShapes.card) { Text(stringResource(R.string.manual_add), fontWeight = FontWeight.Bold) } }
    item { OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.collection_search)) }, singleLine = true, shape = CardVaultShapes.field, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MewRose, cursorColor = MewRose, focusedLabelColor = MewRose, unfocusedContainerColor = VaultNightRaised, focusedContainerColor = VaultNightRaised), modifier = Modifier.fillMaxWidth()) }
    if (filtered.isEmpty()) item { Text(stringResource(R.string.collection_empty), modifier = Modifier.padding(vertical = 30.dp)) }
    items(filtered, key = { it.entryId }) { card -> CardRow(card) { editing = card } }; item { DataNote() }
    }
    if (adding) ManualCardDialog(onDismiss = { adding = false }, onSave = { input -> scope.launch { repository.addManualCard(input) }; adding = false })
    editing?.let { card -> EditEntryDialog(card, onDismiss = { editing = null }, onDelete = { editing = null; deleting = card }, onSave = { update -> scope.launch { repository.updateEntry(card.entryId, update) }; editing = null }) }
    deleting?.let { card -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete_entry_title)) }, text = { Text(stringResource(R.string.delete_entry_body, card.name)) }, confirmButton = { TextButton(onClick = { scope.launch { repository.deleteEntry(card.entryId) }; deleting = null }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } }) }
}

@Composable private fun CardRow(card: CollectionCard, onClick: (() -> Unit)? = null) = ElevatedCard(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), shape = CardVaultShapes.card, colors = CardDefaults.elevatedCardColors(containerColor = VaultPlumSurface)) { Row(Modifier.padding(CardVaultSpacing.card), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CardVaultSpacing.card)) {
    Box(Modifier.size(48.dp, 66.dp).background(Color(card.accent), CardVaultShapes.thumbnail), contentAlignment = Alignment.Center) { Text(card.name.take(1), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black) }
    Column(Modifier.weight(1f)) { Text(card.name, fontWeight = FontWeight.Bold); Text("${card.set} · ${card.number}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(stringResource(R.string.card_metadata, card.language.label, card.condition, card.quantity), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
    Column(horizontalAlignment = Alignment.End) { Text(if (card.priceUpdatedAtEpochMillis == null) stringResource(R.string.no_market_price) else euro(card.currentValueEur * card.quantity), fontWeight = FontWeight.Bold, fontSize = if (card.priceUpdatedAtEpochMillis == null) 11.sp else 14.sp); if (card.priceUpdatedAtEpochMillis != null) Text((if (card.changePercent >= 0) "+" else "") + "%.1f%%".format(Locale.US, card.changePercent), color = if (card.changePercent >= 0) VaultPositive else MaterialTheme.colorScheme.error, fontSize = 12.sp) }
} }

@Composable private fun ManualCardDialog(onDismiss: () -> Unit, onSave: (ManualCardInput) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }; var set by rememberSaveable { mutableStateOf("") }; var number by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf(CardLanguage.GERMAN) }; var finish by rememberSaveable { mutableStateOf("Normal") }; var variant by rememberSaveable { mutableStateOf("Standard") }
    var quantity by rememberSaveable { mutableStateOf("1") }; var condition by rememberSaveable { mutableStateOf("Nahezu neuwertig") }; var notes by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }; var date by rememberSaveable { mutableStateOf("") }
    val valid = name.isNotBlank() && set.isNotBlank() && number.isNotBlank() && condition.isNotBlank() && (quantity.toIntOrNull() ?: 0) > 0 && parseDate(date) != Long.MIN_VALUE && parsePrice(price) != Double.NEGATIVE_INFINITY
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.manual_add_title)) }, text = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(stringResource(R.string.manual_add_boundary), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            item { FormField(name, { name = it }, R.string.field_card_name) }; item { FormField(set, { set = it }, R.string.field_set_name) }; item { FormField(number, { number = it }, R.string.field_collector_number) }
            item { Text(stringResource(R.string.field_language), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { CardLanguage.entries.forEach { item -> FilterChip(selected = language == item, onClick = { language = item }, label = { Text(item.label.take(2), fontSize = 11.sp) }) } } }
            item { FormField(finish, { finish = it }, R.string.field_finish) }; item { FormField(variant, { variant = it }, R.string.field_variant) }
            item { FormField(quantity, { quantity = it.filter(Char::isDigit) }, R.string.field_quantity, KeyboardType.Number) }; item { FormField(condition, { condition = it }, R.string.field_condition) }
            item { FormField(price, { price = it }, R.string.field_acquisition_price, KeyboardType.Decimal) }; item { FormField(date, { date = it }, R.string.field_acquisition_date) }; item { FormField(notes, { notes = it }, R.string.field_notes, singleLine = false) }
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = { onSave(ManualCardInput(name, set, number, language, finish.ifBlank { "Normal" }, variant.ifBlank { "Standard" }, quantity.toInt(), condition, notes, parsePrice(price).takeUnless { it == Double.NEGATIVE_INFINITY }, parseDate(date).takeUnless { it == Long.MIN_VALUE })) }) { Text(stringResource(R.string.action_save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable private fun EditEntryDialog(card: CollectionCard, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (CollectionEntryUpdate) -> Unit) {
    var quantity by rememberSaveable(card.entryId) { mutableStateOf(card.quantity.toString()) }; var condition by rememberSaveable(card.entryId) { mutableStateOf(card.condition) }; var notes by rememberSaveable(card.entryId) { mutableStateOf(card.notes) }
    var price by rememberSaveable(card.entryId) { mutableStateOf(card.acquisitionPriceEur?.let { "%.2f".format(Locale.GERMANY, it) } ?: "") }; var date by rememberSaveable(card.entryId) { mutableStateOf(card.acquisitionDateEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "") }
    val valid = (quantity.toIntOrNull() ?: 0) > 0 && condition.isNotBlank() && parseDate(date) != Long.MIN_VALUE && parsePrice(price) != Double.NEGATIVE_INFINITY
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.edit_entry_title)) }, text = { LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("${card.name} · ${card.set}", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold) }
        item { OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.grading_action)) }; Text(stringResource(R.string.grading_card_future), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
        item { FormField(quantity, { quantity = it.filter(Char::isDigit) }, R.string.field_quantity, KeyboardType.Number) }; item { FormField(condition, { condition = it }, R.string.field_condition) }; item { FormField(price, { price = it }, R.string.field_acquisition_price, KeyboardType.Decimal) }; item { FormField(date, { date = it }, R.string.field_acquisition_date) }; item { FormField(notes, { notes = it }, R.string.field_notes, singleLine = false) }
    } }, confirmButton = { TextButton(enabled = valid, onClick = { onSave(CollectionEntryUpdate(quantity.toInt(), condition, notes, parsePrice(price).takeUnless { it == Double.NEGATIVE_INFINITY }, parseDate(date).takeUnless { it == Long.MIN_VALUE })) }) { Text(stringResource(R.string.action_save)) } }, dismissButton = { Row { TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }; TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } } })
}

@Composable private fun FormField(value: String, onValueChange: (String) -> Unit, @StringRes label: Int, keyboardType: KeyboardType = KeyboardType.Text, singleLine: Boolean = true) {
    OutlinedTextField(value, onValueChange, label = { Text(stringResource(label)) }, singleLine = singleLine, keyboardOptions = KeyboardOptions(keyboardType = keyboardType), shape = CardVaultShapes.field, modifier = Modifier.fillMaxWidth())
}

private fun parsePrice(value: String): Double? = if (value.isBlank()) null else value.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 } ?: Double.NEGATIVE_INFINITY
private fun parseDate(value: String): Long? = if (value.isBlank()) null else runCatching { LocalDate.parse(value).toEpochDay() }.getOrNull() ?: Long.MIN_VALUE

@Composable private fun ScannerScreen() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; denied = !it }
    Column(Modifier.fillMaxSize().padding(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(CardVaultSpacing.section)) {
        ScreenHeader(stringResource(R.string.scanner_eyebrow), stringResource(R.string.scanner_title), stringResource(R.string.scanner_subtitle))
        Box(Modifier.fillMaxWidth().weight(1f).background(Brush.linearGradient(listOf(Color(0xFF111326), Color(0xFF221B35))), CardVaultShapes.feature).border(1.dp, VaultOutline.copy(alpha = .75f), CardVaultShapes.feature), contentAlignment = Alignment.Center) {
            if (granted) {
                Box(Modifier.fillMaxSize().padding(34.dp).border(2.dp, MewIce, RoundedCornerShape(18.dp)))
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(stringResource(R.string.camera_ready), color = Color.White, fontWeight = FontWeight.Bold); Text(stringResource(R.string.camera_preview_boundary), color = Color(0xFFB9C9C5), fontSize = 13.sp) }
            } else Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.camera_permission_title), color = Color.White, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.camera_permission_body), color = Color(0xFFB9C9C5))
                Button({ launcher.launch(Manifest.permission.CAMERA) }) { Text(stringResource(if (denied) R.string.camera_permission_retry else R.string.camera_permission_allow)) }
            }
        }
        AssistChip(onClick = {}, label = { Text(stringResource(R.string.scanner_stability_planned)) }, enabled = false)
        Text(stringResource(R.string.scanner_boundary), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

private data class GradeCategory(@param:StringRes val name: Int, @param:StringRes val prompt: Int)
@Composable private fun GradingScreen() {
    val categories = remember { listOf(GradeCategory(R.string.grading_centering, R.string.grading_centering_prompt), GradeCategory(R.string.grading_corners, R.string.grading_corners_prompt), GradeCategory(R.string.grading_edges, R.string.grading_edges_prompt), GradeCategory(R.string.grading_surface, R.string.grading_surface_prompt)) }
    var completed by rememberSaveable { mutableStateOf(setOf<Int>()) }
    LazyColumn(contentPadding = PaddingValues(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenHeader(stringResource(R.string.grading_eyebrow), stringResource(R.string.grading_title), stringResource(R.string.grading_subtitle)) }
        item { ElevatedCard(shape = CardVaultShapes.card, colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Text(stringResource(R.string.grading_disclaimer), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onPrimaryContainer) } }
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
