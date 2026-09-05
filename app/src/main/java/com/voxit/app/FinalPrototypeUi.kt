package com.voxit.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voxit.app.integrity.IntegrityModelStatus
import com.voxit.app.phase2.*
import com.voxit.app.ui.theme.*
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedModelManagerScreen(vm: Phase2ViewModel, onBack: () -> Unit) {
    val catalog by vm.modelCatalog.collectAsState()
    val modelState by vm.modelState.collectAsState()
    val integrity by vm.integrityModelState.collectAsState()
    var language by rememberSaveable { mutableStateOf("English") }
    var deleteVoskId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteIntegrity by rememberSaveable { mutableStateOf(false) }
    val voskPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importModel(it, language) } }
    val integrityPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importIntegrityModel) }

    if (deleteVoskId != null) ConfirmDeleteDialog(
        title = "Delete transcription model?",
        detail = "The selected language becomes explicitly unavailable if this is the active model. VoxIT will not fall back to another model.",
        onConfirm = { vm.deleteModel(deleteVoskId); deleteVoskId = null },
        onDismiss = { deleteVoskId = null },
    )
    if (deleteIntegrity) ConfirmDeleteDialog(
        title = "Delete voice-integrity model?",
        detail = "Uploaded recordings will show Voice-integrity model not installed until a verified model is imported again.",
        onConfirm = { vm.deleteIntegrityModel(); deleteIntegrity = false },
        onDismiss = { deleteIntegrity = false },
    )

    Scaffold(containerColor = Navy, topBar = { FinalTopBar("Model Manager", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Offline models", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Models remain in separate app-private directories. No model or recording is uploaded.", color = SignalMuted)

            Text("Transcription — Vosk", color = SignalBlue, fontWeight = FontWeight.SemiBold)
            if (catalog.models.isEmpty()) FinalInfo("English model: unavailable\nHindi model: unavailable", Amber)
            catalog.models.forEach { model ->
                val active = catalog.selectedModelId == model.id
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(model.displayName, fontWeight = FontWeight.Bold)
                        Text("${model.language} • ${model.validationStatus.name.lowercase()} • ${if (active) "ACTIVE" else "inactive"}", color = if (model.validationStatus == ModelValidationStatus.VALID) SafeGreen else AlertRed)
                        Text("ID: ${model.id}\nStorage: ${model.pathIdentifier}\nVersion: ${model.version}\nLicence: Apache-2.0 (Vosk runtime/model terms should be checked for the imported model)", color = SignalMuted, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { vm.selectModel(model.id) }, enabled = !active && model.validationStatus == ModelValidationStatus.VALID, modifier = Modifier.weight(1f)) { Text(if (active) "Selected" else "Select") }
                            OutlinedButton(onClick = { deleteVoskId = model.id }, modifier = Modifier.weight(1f)) { Text("Delete") }
                        }
                    }
                }
            }
            if (modelState is ModelImportState.Error) FinalInfo((modelState as ModelImportState.Error).message, AlertRed)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("English", "Hindi", "Hinglish").forEach { item -> FilterChip(selected = language == item, onClick = { language = item }, label = { Text(if (item == "Hinglish") "Hinglish*" else item) }, modifier = Modifier.weight(1f)) }
            }
            Button(onClick = { voskPicker.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = modelState !is ModelImportState.Importing, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Import or replace Vosk model") }
            Text("Known model names are checked against the chosen language. Hinglish is experimental and depends on the selected model vocabulary. Stop Live Protection before changing models.", color = SignalMuted, style = MaterialTheme.typography.bodySmall)

            HorizontalDivider(color = SignalMuted.copy(.25f))
            Text("Voice integrity — AASIST-L", color = SignalBlue, fontWeight = FontWeight.SemiBold)
            val integrityColor = when (integrity.status) { IntegrityModelStatus.READY -> SafeGreen; IntegrityModelStatus.CORRUPT, IntegrityModelStatus.INCOMPATIBLE, IntegrityModelStatus.ERROR -> AlertRed; else -> Amber }
            FinalInfo("${integrity.status.name.replace('_', ' ')} — ${integrity.message}" + (integrity.metadata?.let { "\n${it.name} • ${it.version}\nID: ${it.id}\nPurpose: uploaded voice integrity\nStorage: ${it.pathIdentifier}\n${it.licence} • ${it.sizeBytes / 1024} KB" } ?: ""), integrityColor)
            Button(onClick = { integrityPicker.launch(arrayOf("application/onnx", "application/octet-stream")) }, enabled = integrity.status !in setOf(IntegrityModelStatus.IMPORTING, IntegrityModelStatus.VALIDATING), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text(if (integrity.status == IntegrityModelStatus.READY) "Replace verified AASIST-L model" else "Import verified AASIST-L model") }
            if (integrity.metadata != null) OutlinedButton(onClick = { deleteIntegrity = true }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Delete voice-integrity model") }
            Text("Only the checksum-locked VoxIT export with the expected ONNX input/output shape can become active. AASIST-L is used for uploaded recordings only.", color = SignalMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinalHistoryScreen(vm: Phase2ViewModel, onBack: () -> Unit) {
    val entries by vm.history.collectAsState()
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var clearAll by rememberSaveable { mutableStateOf(false) }
    if (deleteId != null) ConfirmDeleteDialog("Delete this result?", "Only locally retained metadata will be removed.", { vm.deleteHistoryEntry(deleteId!!); deleteId = null }, { deleteId = null })
    if (clearAll) ConfirmDeleteDialog("Clear all history?", "This permanently removes all locally retained analysis metadata.", { vm.clearHistory(); clearAll = false }, { clearAll = false })
    Scaffold(containerColor = Navy, topBar = { FinalTopBar("Analysis History", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Real analysis metadata", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            FinalInfo("History is opt-in. It stores result metadata only—not the original recording, URI, filename, PCM, or transcript.")
            if (entries.isEmpty()) Text("No retained real results.", color = SignalMuted)
            entries.forEach { entry ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.source, fontWeight = FontWeight.Bold)
                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.createdAtEpochMs)), color = SignalMuted)
                        Text("Duration ${formatFinalDuration(entry.durationMs)} • Quality ${entry.audioQuality}")
                        Text("Voice integrity: ${entry.voiceIntegrityConclusion}${entry.manipulationScore?.let { " • $it/100" } ?: ""}")
                        Text("Scam-language risk: ${entry.scamRiskScore?.let { "$it/100" } ?: "Unavailable"}")
                        Text("Transcription: ${entry.transcriptionModel ?: "Unavailable"}\nIntegrity model: ${entry.integrityModel ?: "Unavailable"}", color = SignalMuted, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { deleteId = entry.id }, modifier = Modifier.fillMaxWidth()) { Text("Delete result") }
                    }
                }
            }
            OutlinedButton(onClick = { clearAll = true }, enabled = entries.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Clear all history") }
            HorizontalDivider(color = SignalMuted.copy(.25f))
            Text("Demo history is never saved", color = SignalBlue, fontWeight = FontWeight.SemiBold)
            Text("The six deterministic scenarios remain available from Demo Mode. Simulated results never enter real history.", color = SignalMuted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HowVoxITWorksScreen(onBack: () -> Unit) {
    Scaffold(containerColor = Navy, topBar = { FinalTopBar("How VoxIT works", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Two workflows, separate evidence", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            FinalInfo("Uploaded recording → local decoding → audio quality → speech regions → optional Vosk transcript and scam-language analysis → optional AASIST-L acoustic analysis.")
            FinalInfo("Live Protection → explicit microphone start → foreground service → speech/quality monitoring → optional Vosk transcript → rolling scam-language warnings. Live AASIST-L inference is not enabled.")
            FinalInfo("Transcript risk examines confirmed words and context. Voice integrity examines speech acoustics. Neither proves fraud, caller identity, or a specific manipulation subtype.", Amber)
            Text("Privacy", color = SignalBlue, fontWeight = FontWeight.SemiBold)
            Text("Processing is local. Raw live PCM is bounded in memory and cleared on Stop. Audio and transcripts are not uploaded or saved by default.", color = SignalMuted)
            Text("Android limitation", color = SignalBlue, fontWeight = FontWeight.SemiBold)
            Text("A normal Android app cannot reliably access protected cellular or VoIP call audio. Live Protection analyses only microphone audio supplied by Android; speakerphone behaviour is device-dependent and experimental.", color = SignalMuted)
            Text("Prototype limits", color = SignalBlue, fontWeight = FontWeight.SemiBold)
            Text("AASIST-L is an uncalibrated research detector evaluated primarily on ASVspoof 2019 Logical Access. Speaker verification is not included. Results may contain false positives and false negatives.", color = SignalMuted)
        }
    }
}

@Composable
fun DeleteAllAnalysisDataButton(vm: Phase2ViewModel) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    if (confirm) ConfirmDeleteDialog(
        "Delete all local analysis data?",
        "This clears the current uploaded-audio result, transcript, and retained history. Imported models are managed and deleted separately in Model Manager.",
        { vm.reset(); vm.clearHistory(); confirm = false },
        { confirm = false },
    )
    OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Delete all local analysis data") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun FinalTopBar(title: String, onBack: () -> Unit) = CenterAlignedTopAppBar(
    title = { Text(title, fontWeight = FontWeight.SemiBold) },
    navigationIcon = { OutlinedButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) { Text("Back") } },
    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Navy),
)

@Composable private fun FinalInfo(text: String, color: androidx.compose.ui.graphics.Color = SignalBlue) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) { Text(text, modifier = Modifier.padding(14.dp), color = color) }

@Composable private fun ConfirmDeleteDialog(title: String, detail: String, onConfirm: () -> Unit, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(detail) },
    confirmButton = { Button(onClick = onConfirm) { Text("Delete") } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
)

private fun formatFinalDuration(ms: Long) = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)
