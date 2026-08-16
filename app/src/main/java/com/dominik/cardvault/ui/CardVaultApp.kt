package com.dominik.cardvault.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.dominik.cardvault.model.*
import java.text.NumberFormat
import java.util.Locale

private enum class Destination(val label: String, val glyph: String) { DASHBOARD("Portfolio", "⌂"), SCANNER("Scanner", "▣"), COLLECTION("Collection", "▤"), GRADING("Grade check", "◇") }

@Composable fun CardVaultApp(repository: CollectionRepository = SampleCollectionRepository) {
    var destination by rememberSaveable { mutableStateOf(Destination.DASHBOARD) }
    val portfolio = remember(repository) { repository.portfolio() }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        NavigationBar { Destination.entries.forEach { item -> NavigationBarItem(selected = destination == item, onClick = { destination = item }, icon = { Text(item.glyph, fontSize = 21.sp, fontWeight = FontWeight.Bold) }, label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }) } }
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

@Composable private fun DashboardScreen(portfolio: PortfolioSummary, openScanner: () -> Unit) = LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
    item { ScreenHeader("CardVault", "Your collection", "Private, offline-first and always yours.") }
    item { ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Estimated portfolio value"); Text(euro(portfolio.totalValue), fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text("▲ ${"%.1f".format(Locale.US, portfolio.changePercent)}% · 6 month sample", color = Color(0xFF177245), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp)); ValueHistoryChart(portfolio.history, Modifier.fillMaxWidth().height(110.dp))
    } } }
    item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { StatCard("Cards", portfolio.totalCards.toString(), Modifier.weight(1f)); StatCard("Unique", portfolio.cards.size.toString(), Modifier.weight(1f)); StatCard("Languages", portfolio.cards.map { it.language }.distinct().size.toString(), Modifier.weight(1f)) } }
    item { Button(openScanner, Modifier.fillMaxWidth().height(54.dp)) { Text("Scan a card") } }
    item { Text("Recent cards", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
    items(portfolio.cards.take(3)) { CardRow(it) }; item { DataNote() }
}

@Composable private fun StatCard(label: String, value: String, modifier: Modifier) = OutlinedCard(modifier) { Column(Modifier.padding(14.dp)) { Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable private fun ValueHistoryChart(points: List<PortfolioPoint>, modifier: Modifier) { val color = MaterialTheme.colorScheme.primary; Canvas(modifier) {
    if (points.size < 2) return@Canvas
    val min = points.minOf { it.value }; val range = (points.maxOf { it.value } - min).coerceAtLeast(1.0); val path = Path()
    points.forEachIndexed { i, p -> val x = i * size.width / (points.size - 1); val y = size.height - (((p.value - min) / range).toFloat() * size.height * .78f) - size.height * .1f; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
    drawLine(color.copy(.16f), Offset(0f, size.height * .9f), Offset(size.width, size.height * .9f), 2f); drawPath(path, color, style = Stroke(7f, cap = StrokeCap.Round))
} }

@Composable private fun CollectionScreen(cards: List<CollectionCard>) { var query by rememberSaveable { mutableStateOf("") }; val filtered = cards.filter { it.name.contains(query, true) || it.set.contains(query, true) }; LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    item { ScreenHeader("Library", "Collection", "${cards.sumOf { it.quantity }} cards across ${cards.size} entries") }
    item { OutlinedTextField(query, { query = it }, label = { Text("Search cards or sets") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
    if (filtered.isEmpty()) item { Text("No cards match your search.", modifier = Modifier.padding(vertical = 30.dp)) }
    items(filtered) { CardRow(it) }; item { DataNote() }
} }

@Composable private fun CardRow(card: CollectionCard) = ElevatedCard(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    Box(Modifier.size(48.dp, 66.dp).background(Color(card.accent), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) { Text(card.name.take(1), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black) }
    Column(Modifier.weight(1f)) { Text(card.name, fontWeight = FontWeight.Bold); Text("${card.set} · ${card.number}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${card.language.label} · ${card.condition} · ×${card.quantity}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
    Column(horizontalAlignment = Alignment.End) { Text(euro(card.currentValueEur * card.quantity), fontWeight = FontWeight.Bold); Text((if (card.changePercent >= 0) "+" else "") + "%.1f%%".format(Locale.US, card.changePercent), color = if (card.changePercent >= 0) Color(0xFF177245) else MaterialTheme.colorScheme.error, fontSize = 12.sp) }
} }

@Composable private fun ScannerScreen() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; denied = !it }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ScreenHeader("Capture", "Card scanner", "Frame one card in even, glare-free light.")
        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF101B1A), RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
            if (granted) {
                Box(Modifier.fillMaxSize().padding(34.dp).border(2.dp, Color(0xFF6DE2C2), RoundedCornerShape(18.dp)))
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Camera access ready", color = Color.White, fontWeight = FontWeight.Bold); Text("Live preview is the next implementation step", color = Color(0xFFB9C9C5), fontSize = 13.sp) }
            } else Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Camera permission required", color = Color.White, fontWeight = FontWeight.Bold)
                Text("CardVault only needs the camera while you scan. Photos stay on this device.", color = Color(0xFFB9C9C5))
                Button({ launcher.launch(Manifest.permission.CAMERA) }) { Text(if (denied) "Try again" else "Allow camera") }
            }
        }
        AssistChip(onClick = {}, label = { Text("Planned: stable-frame automatic capture") }, enabled = false)
        Text("Identification is not active yet. This Phase‑1 screen establishes permission and capture UX; no card data is inferred or uploaded.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}

private data class GradeCategory(val name: String, val prompt: String)
@Composable private fun GradingScreen() {
    val categories = remember { listOf(GradeCategory("Centering", "Borders and card alignment"), GradeCategory("Corners", "Whitening, bends and wear"), GradeCategory("Edges", "Chips and edge consistency"), GradeCategory("Surface", "Scratches, dents and print issues")) }
    var completed by rememberSaveable { mutableStateOf(setOf<String>()) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenHeader("Pre-screen", "Grading check", "A guided visual review before professional grading.") }
        item { ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) { Text("This is an estimate, not a guaranteed PSA grade. Final grades depend on the grading company’s expert inspection.", Modifier.padding(16.dp)) } }
        item { Text("Photo checklist", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        items(categories) { category -> val checked = category.name in completed; OutlinedCard(Modifier.fillMaxWidth().clickable { completed = if (checked) completed - category.name else completed + category.name }) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, { completed = if (it) completed + category.name else completed - category.name }); Column(Modifier.weight(1f)) { Text(category.name, fontWeight = FontWeight.Bold); Text(category.prompt, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }; Text(if (checked) "Ready" else "Add photos", color = if (checked) Color(0xFF177245) else MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold) } } }
        item { LinearProgressIndicator(progress = { completed.size / categories.size.toFloat() }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Submission value check", fontWeight = FontWeight.Bold)
            Text("The future result will compare raw value with plausible values across the estimated grade range, then subtract configurable grading, shipping and insurance costs.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Text("Recommendation: submit, borderline, or keep raw — with confidence and reasons", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        } } }
        item { Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Analysis coming in a later phase") } }
        item { Text("Future analysis will use guided front, back and angled photos and explain centering, corners, edges and surface findings. The checklist works now; photo analysis and PSA range estimation do not.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
    }
}

@Composable private fun DataNote() = Text("Demo values are stored locally and are not live prices. A later importer will consume permitted daily Cardmarket catalog and price-guide files—never scraped listings.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
private fun euro(value: Double) = NumberFormat.getCurrencyInstance(Locale.GERMANY).format(value)
