package com.dominik.cardvault.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.dominik.cardvault.R
import com.dominik.cardvault.model.*
import com.dominik.cardvault.ui.theme.CardVaultShapes
import com.dominik.cardvault.ui.theme.CardVaultSpacing
import java.text.NumberFormat
import java.util.Locale

private enum class Destination(@param:StringRes val label: Int, val glyph: String) {
    DASHBOARD(R.string.nav_portfolio, "⌂"), SCANNER(R.string.nav_scanner, "▣"),
    COLLECTION(R.string.nav_collection, "▤"), GRADING(R.string.nav_grading, "◇")
}

@Composable fun CardVaultApp(repository: CollectionRepository = SampleCollectionRepository) {
    var destination by rememberSaveable { mutableStateOf(Destination.DASHBOARD) }
    val portfolio = remember(repository) { repository.portfolio() }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        NavigationBar { Destination.entries.forEach { item -> NavigationBarItem(selected = destination == item, onClick = { destination = item }, icon = { Text(item.glyph, modifier = Modifier.clearAndSetSemantics { }, fontSize = 21.sp, fontWeight = FontWeight.Bold) }, label = { Text(stringResource(item.label), maxLines = 1, overflow = TextOverflow.Ellipsis) }) } }
    }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) {
        when (destination) {
            Destination.DASHBOARD -> DashboardScreen(portfolio) { destination = Destination.SCANNER }
            Destination.SCANNER -> ScannerScreen()
            Destination.COLLECTION -> CollectionScreen(portfolio.cards)
            Destination.GRADING -> GradingScreen()
        }
    } }
}

@Composable private fun ScreenHeader(eyebrow: String, title: String, subtitle: String) = Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(eyebrow.uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.5.sp)
    Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun DashboardScreen(portfolio: PortfolioSummary, openScanner: () -> Unit) = LazyColumn(contentPadding = PaddingValues(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(CardVaultSpacing.section)) {
    item { ScreenHeader(stringResource(R.string.dashboard_eyebrow), stringResource(R.string.dashboard_title), stringResource(R.string.dashboard_subtitle)) }
    item { ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.portfolio_estimated_value)); Text(euro(portfolio.totalValue), fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.portfolio_change, portfolio.changePercent), color = Color(0xFF177245), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp)); ValueHistoryChart(portfolio.history, Modifier.fillMaxWidth().height(110.dp))
    } } }
    item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { StatCard(stringResource(R.string.stat_cards), portfolio.totalCards.toString(), Modifier.weight(1f)); StatCard(stringResource(R.string.stat_unique), portfolio.cards.size.toString(), Modifier.weight(1f)); StatCard(stringResource(R.string.stat_languages), portfolio.cards.map { it.language }.distinct().size.toString(), Modifier.weight(1f)) } }
    item { Button(openScanner, Modifier.fillMaxWidth().height(54.dp)) { Text(stringResource(R.string.scan_card)) } }
    item { Text(stringResource(R.string.recent_cards), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
    items(portfolio.cards.take(3)) { CardRow(it) }; item { DataNote() }
}

@Composable private fun StatCard(label: String, value: String, modifier: Modifier) = OutlinedCard(modifier) { Column(Modifier.padding(14.dp)) { Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable private fun ValueHistoryChart(points: List<PortfolioPoint>, modifier: Modifier) { val color = MaterialTheme.colorScheme.primary; Canvas(modifier) {
    if (points.size < 2) return@Canvas
    val min = points.minOf { it.value }; val range = (points.maxOf { it.value } - min).coerceAtLeast(1.0); val path = Path()
    points.forEachIndexed { i, p -> val x = i * size.width / (points.size - 1); val y = size.height - (((p.value - min) / range).toFloat() * size.height * .78f) - size.height * .1f; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
    drawLine(color.copy(.16f), Offset(0f, size.height * .9f), Offset(size.width, size.height * .9f), 2f); drawPath(path, color, style = Stroke(7f, cap = StrokeCap.Round))
} }

@Composable private fun CollectionScreen(cards: List<CollectionCard>) { var query by rememberSaveable { mutableStateOf("") }; val filtered = cards.filter { it.name.contains(query, true) || it.set.contains(query, true) }; LazyColumn(contentPadding = PaddingValues(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(CardVaultSpacing.card)) {
    item { ScreenHeader(stringResource(R.string.collection_eyebrow), stringResource(R.string.collection_title), stringResource(R.string.collection_summary, cards.sumOf { it.quantity }, cards.size)) }
    item { OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.collection_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
    if (filtered.isEmpty()) item { Text(stringResource(R.string.collection_empty), modifier = Modifier.padding(vertical = 30.dp)) }
    items(filtered) { CardRow(it) }; item { DataNote() }
} }

@Composable private fun CardRow(card: CollectionCard) = ElevatedCard(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    Box(Modifier.size(48.dp, 66.dp).background(Color(card.accent), CardVaultShapes.thumbnail), contentAlignment = Alignment.Center) { Text(card.name.take(1), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black) }
    Column(Modifier.weight(1f)) { Text(card.name, fontWeight = FontWeight.Bold); Text("${card.set} · ${card.number}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(stringResource(R.string.card_metadata, card.language.label, card.condition, card.quantity), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
    Column(horizontalAlignment = Alignment.End) { Text(euro(card.currentValueEur * card.quantity), fontWeight = FontWeight.Bold); Text((if (card.changePercent >= 0) "+" else "") + "%.1f%%".format(Locale.US, card.changePercent), color = if (card.changePercent >= 0) Color(0xFF177245) else MaterialTheme.colorScheme.error, fontSize = 12.sp) }
} }

@Composable private fun ScannerScreen() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; denied = !it }
    Column(Modifier.fillMaxSize().padding(CardVaultSpacing.screen), verticalArrangement = Arrangement.spacedBy(CardVaultSpacing.section)) {
        ScreenHeader(stringResource(R.string.scanner_eyebrow), stringResource(R.string.scanner_title), stringResource(R.string.scanner_subtitle))
        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF101B1A), CardVaultShapes.feature), contentAlignment = Alignment.Center) {
            if (granted) {
                Box(Modifier.fillMaxSize().padding(34.dp).border(2.dp, Color(0xFF6DE2C2), RoundedCornerShape(18.dp)))
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
        item { ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) { Text(stringResource(R.string.grading_disclaimer), Modifier.padding(16.dp)) } }
        item { Text(stringResource(R.string.photo_checklist), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        items(categories) { category -> val checked = category.name in completed; OutlinedCard(Modifier.fillMaxWidth().clickable { completed = if (checked) completed - category.name else completed + category.name }) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, { completed = if (it) completed + category.name else completed - category.name }); Column(Modifier.weight(1f)) { Text(stringResource(category.name), fontWeight = FontWeight.Bold); Text(stringResource(category.prompt), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }; Text(stringResource(if (checked) R.string.grading_ready else R.string.grading_add_photos), color = if (checked) Color(0xFF177245) else MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold) } } }
        item { LinearProgressIndicator(progress = { completed.size / categories.size.toFloat() }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
