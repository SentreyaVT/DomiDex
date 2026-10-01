package com.dominik.cardvault.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dominik.cardvault.data.sharing.BinderShareOptions
import com.dominik.cardvault.data.sharing.BinderShareSummary
import com.dominik.cardvault.data.sharing.BinderSharingException
import com.dominik.cardvault.data.sharing.BinderSharingSyncException
import com.dominik.cardvault.data.sharing.BinderSharingGateway
import com.dominik.cardvault.data.sharing.CreatedBinderShare
import com.dominik.cardvault.data.sharing.FirebaseBinderSharingGateway
import com.dominik.cardvault.auth.canonicalCollectionOwnerUid
import com.dominik.cardvault.model.PrivateBinderSummary
import com.google.firebase.auth.FirebaseAuth
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A visible entry in the real reader; merely opening it never creates a public link. */
@Composable
internal fun PrivateBinderSharingAction(binder: PrivateBinderSummary, enabled: Boolean) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val gateway = remember(context.applicationContext) { FirebaseBinderSharingGateway(context.applicationContext) }
    var currentUid by remember { mutableStateOf(canonicalCollectionOwnerUid(auth.currentUser)) }
    var opened by remember(binder.id, binder.ownerUid) { mutableStateOf(false) }
    var managing by remember(binder.id, binder.ownerUid) { mutableStateOf(false) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { currentUid = canonicalCollectionOwnerUid(it.currentUser) }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }
    TextButton(onClick = { managing = false; opened = true }, enabled = enabled) { Text("Teilen") }
    if (!opened) return
    val owner = binder.ownerUid
    if (owner == null || owner != currentUid) {
        AlertDialog(
            onDismissRequest = { opened = false },
            title = { Text("Privaten Binder teilen") },
            text = { Text("Melde dich mit dem Konto dieses Binders an und synchronisiere ihn. Dein Binder bleibt privat. Bei einem Kontowechsel öffne den Binder bitte erneut.") },
            confirmButton = { TextButton(onClick = { opened = false }) { Text("Schließen") } },
        )
        return
    }
    val copyLink: (String) -> Unit = { url ->
        check(canonicalCollectionOwnerUid(auth.currentUser) == owner)
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("DomiDex Binderlink", url))
    }
    val shareLink: (String) -> Unit = { url ->
        check(canonicalCollectionOwnerUid(auth.currentUser) == owner)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }, "Binderlink teilen"))
    }
    if (managing) PrivateBinderShareDialog(
        binderId = binder.id, binderName = binder.name, expectedUid = owner, gateway = gateway,
        onDismiss = { opened = false }, onCopyLink = copyLink, onShareLink = shareLink,
    ) else PrivateBinderQuickShareDialog(
        binderId = binder.id, binderName = binder.name, expectedUid = owner, gateway = gateway,
        onDismiss = { opened = false }, onManage = { managing = true },
        onCopyLink = copyLink, onShareLink = shareLink,
    )
}

/** Shown only after an explicit Teilen click; opening a binder never publishes it. */
@Composable
internal fun PrivateBinderQuickShareDialog(
    binderId: String, binderName: String, expectedUid: String, gateway: BinderSharingGateway,
    onDismiss: () -> Unit, onManage: () -> Unit,
    onCopyLink: (String) -> Unit, onShareLink: (String) -> Unit,
) = key(binderId, expectedUid, gateway) {
    // Memory-only, single attempt for this explicit click. Never retry an uncertain POST.
    var created by remember { mutableStateOf<CreatedBinderShare?>(null) }
    var busy by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            created = gateway.create(expectedUid, binderId, BinderShareOptions(), null, confirmed = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            notice = safeSharingMessage(error, "Der Link konnte nicht bestätigt werden. Prüfe die Freigaben, bevor du erneut teilst.")
        } finally { busy = false }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Binder teilen") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(binderName, fontWeight = FontWeight.Bold)
                Text("Dein Link zeigt Cover, Karten und Seiten – nur zum Ansehen. Ohne Preise, Anzeigename, private Fotos oder Notizen.")
                if (busy) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text("Dein Binder und seine Karten werden bevorzugt synchronisiert; danach wird dein Link erstellt …")
                }
                notice?.let { Text(it, Modifier.testTag("quick-sharing-notice")) }
                created?.let { share ->
                    OutlinedTextField(share.url, {}, readOnly = true, label = { Text("Binderlink") }, modifier = Modifier.fillMaxWidth())
                    Text("Öffnet den Binder in DomiDex oder im Browser. Kopiere oder teile den Link jetzt; nach dem Schließen wird er hier nicht gespeichert.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            try { onCopyLink(share.url); notice = "Binderlink kopiert." }
                            catch (_: Exception) { notice = "Der Link konnte nicht kopiert werden. Bitte Konto prüfen." }
                        }) { Text("Kopieren") }
                        Button(onClick = {
                            try { onShareLink(share.url) }
                            catch (_: Exception) { notice = "Teilen konnte nicht geöffnet werden. Du kannst den Link kopieren." }
                        }) { Text("Link teilen") }
                    }
                }
                TextButton(onClick = onManage, enabled = !busy) { Text("Freigaben verwalten") }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Schließen") } },
    )
}

@Composable
internal fun PrivateBinderShareDialog(
    binderId: String,
    binderName: String,
    expectedUid: String,
    gateway: BinderSharingGateway,
    onDismiss: () -> Unit,
    onCopyLink: (String) -> Unit,
    onShareLink: (String) -> Unit,
) = key(binderId, expectedUid, gateway) {
    PrivateBinderShareContent(binderId, binderName, expectedUid, gateway, onDismiss, onCopyLink, onShareLink)
}

@Composable
private fun PrivateBinderShareContent(
    binderId: String, binderName: String, expectedUid: String, gateway: BinderSharingGateway,
    onDismiss: () -> Unit, onCopyLink: (String) -> Unit, onShareLink: (String) -> Unit,
) {
    // Deliberately memory-only: neither consent nor bearer-like links survive a session/account change.
    var options by remember { mutableStateOf(BinderShareOptions()) }
    var displayName by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var shares by remember { mutableStateOf<List<BinderShareSummary>?>(null) }
    var newShare by remember { mutableStateOf<CreatedBinderShare?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var mustRefresh by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var revokeId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) {
        loading = true
        try {
            shares = gateway.list(expectedUid, binderId)
            if (newShare != null && shares.orEmpty().none { it.shareId == newShare?.share?.shareId }) newShare = null
            mustRefresh = false
            notice = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A pending sync must not hide existing links or prevent their revocation.
            (error as? BinderSharingSyncException)?.activeShares?.let { shares = it }
            mustRefresh = true
            notice = safeSharingMessage(error, "Freigaben konnten nicht geladen werden. Bitte erneut versuchen.")
        } finally { loading = false }
    }
    val validName = !options.showDisplayName ||
        (displayName.trim().isNotEmpty() && displayName.trim().length <= 60 && displayName.none { it in "@\r\n" })
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Privaten Binder teilen") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 510.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(binderName, fontWeight = FontWeight.Bold)
                Text("Wer den Link erhält, kann Bindername, Cover, Karten und Seiten ansehen – nicht bearbeiten. Besitzmengen, Kaufdaten, private Notizen und private Fotos werden nicht geteilt.")
                Text("Freigaben zeigen den synchronisierten Stand. Ohne deine Bestätigung bleibt der Binder privat.")
                if (loading) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text("Freigaben werden geprüft. Ein neuer Binder wird bei Bedarf zuerst synchronisiert …")
                    Text("Dafür ist eine Internetverbindung nötig. Dies kann kurz dauern.")
                }
                notice?.let { Text(it, modifier = Modifier.testTag("sharing-notice")) }
                OutlinedButton(onClick = {
                    if (!busy && !loading) { loading = true; reload++ }
                }, enabled = !busy && !loading) { Text("Freigaben aktualisieren") }
                if (shares != null) {
                    ShareOption("Kartenpreise anzeigen", "share-prices", options.showPrices, !busy) {
                        options = options.copy(showPrices = it); confirmed = false
                    }
                    ShareOption("Binder-Gesamtwert anzeigen", "share-total", options.showTotal, !busy) {
                        options = options.copy(showTotal = it); confirmed = false
                    }
                    ShareOption("Anzeigename anzeigen", "share-name", options.showDisplayName, !busy) {
                        options = options.copy(showDisplayName = it); confirmed = false
                    }
                    if (options.showDisplayName) OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it.take(60); confirmed = false },
                        label = { Text("Öffentlicher Anzeigename, keine E-Mail") },
                        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    )
                    ShareOption("Ich gebe diesen Binder mit diesen Angaben per Link zum Ansehen frei.", "share-confirm", confirmed, !busy) { confirmed = it }
                    Button(
                        enabled = confirmed && validName && !busy && !loading && !mustRefresh,
                        modifier = Modifier.fillMaxWidth().testTag("share-create"),
                        onClick = {
                            if (busy || loading || mustRefresh || !confirmed || !validName) return@Button
                            busy = true
                            notice = null
                            scope.launch {
                                try {
                                    notice = "Der synchronisierte Stand wird geprüft. Der Link wird erst danach erstellt …"
                                    val result = gateway.create(expectedUid, binderId, options, displayName.trim().takeIf { options.showDisplayName }, confirmed)
                                    newShare = result
                                    shares = shares.orEmpty() + result.share
                                    notice = "Freigabe erstellt. Speichere den Link jetzt; er wird nur einmal angezeigt."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    mustRefresh = true
                                    notice = safeSharingMessage(error, "Freigabe nicht bestätigt.") + " Aktualisiere die Freigaben vor einem weiteren Versuch."
                                } finally { confirmed = false; busy = false }
                            }
                        },
                    ) { Text(if (busy) "Wird verarbeitet …" else "Freigabelink erstellen") }
                    newShare?.let { created ->
                        OutlinedTextField(created.url, {}, readOnly = true, label = { Text("Neuer Binderlink") }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !busy && !loading, onClick = {
                                try { onCopyLink(created.url); notice = "Binderlink kopiert." }
                                catch (_: Exception) { notice = "Der Link konnte nicht kopiert werden. Bitte Konto und Zwischenablage prüfen." }
                            }) { Text("Kopieren") }
                            Button(enabled = !busy && !loading, onClick = {
                                try { onShareLink(created.url) }
                                catch (_: Exception) { notice = "Teilen konnte nicht geöffnet werden. Du kannst den Link kopieren." }
                            }) { Text("Link teilen") }
                        }
                    }
                    Text("Aktive Freigaben (${shares.orEmpty().size})", fontWeight = FontWeight.Bold)
                    shares.orEmpty().forEach { share ->
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.GERMANY).format(Date(share.createdAt)))
                        Text(listOfNotNull(
                            "Kartenpreise".takeIf { share.options.showPrices },
                            "Binderwert".takeIf { share.options.showTotal },
                            "Anzeigename".takeIf { share.options.showDisplayName },
                        ).joinToString(" · ").ifEmpty { "Ohne Preise, Binderwert und Anzeigename" })
                        if (revokeId == share.shareId) {
                            Text("Diesen Link wirklich ungültig machen?")
                            Button(enabled = !busy && !loading, onClick = {
                                if (busy || loading) return@Button
                                busy = true
                                scope.launch {
                                    try {
                                        gateway.revoke(expectedUid, share.shareId)
                                        shares = shares.orEmpty().filterNot { it.shareId == share.shareId }
                                        if (newShare?.share?.shareId == share.shareId) newShare = null
                                        revokeId = null
                                        notice = "Freigabe beendet. Der Link ist ungültig."
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (error: Exception) { notice = safeSharingMessage(error, "Freigabe konnte nicht beendet werden. Bitte erneut versuchen.") }
                                    finally { busy = false }
                                }
                            }) { Text("Beenden bestätigen") }
                            TextButton(enabled = !busy, onClick = { revokeId = null }) { Text("Abbrechen") }
                        } else OutlinedButton(enabled = !busy && !loading, onClick = { if (!busy && !loading) revokeId = share.shareId }) { Text("Freigabe beenden") }
                    }
                }
                Text("Ein Widerruf sperrt weitere Abrufe. Bereits angesehene oder gespeicherte Inhalte lassen sich nicht zurückholen.")
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Schließen") } },
    )
}

@Composable
private fun ShareOption(label: String, tag: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

private fun safeSharingMessage(error: Exception, fallback: String): String =
    (error as? BinderSharingException)?.message ?: fallback
