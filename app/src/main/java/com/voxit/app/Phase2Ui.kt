package com.voxit.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxit.app.phase2.*
import com.voxit.app.integrity.*
import com.voxit.app.ui.theme.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Phase2UploadScreen(vm: Phase2ViewModel, onBack: () -> Unit, onOpenResult: () -> Unit) {
    val state by vm.uiState.collectAsState()
    val modelState by vm.modelState.collectAsState()
    val integrityState by vm.integrityModelState.collectAsState()
    var importLanguage by rememberSaveable { mutableStateOf("English") }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::selectAudio) }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importModel(it, importLanguage) } }
    val integrityPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importIntegrityModel) }
    Scaffold(containerColor = Navy, topBar = { Phase2TopBar("Upload recording", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Real uploaded-audio analysis", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Audio stays on this device. Android's secure file picker supplies the content URI directly.", color = SignalMuted)
            OutlinedButton(onClick = { audioPicker.launch(arrayOf("audio/wav", "audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg", "audio/*")) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Select Audio File") }
            when (val current = state) {
                Phase2UiState.Idle -> Phase2Info("No recording selected.")
                is Phase2UiState.FileSelected -> SelectedFileCard(current.file)
                is Phase2UiState.Working -> {
                    Text(current.stage.label, fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(progress = { current.stage.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(current.stage.progress * 100).roundToInt()}%", color = SignalMuted)
                    OutlinedButton(onClick = vm::cancelAnalysis, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Cancel Processing") }
                }
                Phase2UiState.Cancelled -> { Phase2Info("Processing cancelled. Decoder and file resources were released.", Amber); RetryActions(vm, audioPicker) }
                is Phase2UiState.Error -> { Phase2Info(current.message, AlertRed); RetryActions(vm, audioPicker) }
                is Phase2UiState.ModelRequired -> {
                    ResultReadyCard(current.result, "Audio analysis complete. ${current.result.transcriptionMessage}")
                    Button(onClick = onOpenResult, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Open Real Audio Result") }
                    OutlinedButton(onClick = vm::retry, enabled = modelState is ModelImportState.Ready, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Retry With Installed Model") }
                }
                is Phase2UiState.Complete -> {
                    ResultReadyCard(current.result, "Audio and offline transcription complete.")
                    Button(onClick = onOpenResult, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Open Real Result") }
                }
            }
            if (state is Phase2UiState.FileSelected) {
                Text("Transcription uses the exact selected model shown below; changing a label cannot change recognizer language.", color = SignalMuted, style = MaterialTheme.typography.bodySmall)
                Button(onClick = vm::analyse, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Analyse Recording") }
            }
            HorizontalDivider(color = SignalMuted.copy(.25f))
            Text("Offline transcription model", color = SignalBlue, fontWeight = FontWeight.SemiBold)
            ModelStatus(modelState)
            ModelChooser(vm)
            Text("Import a Vosk small-model ZIP. Suggested: vosk-model-small-en-us-0.15 (~40 MB) or vosk-model-small-hi-0.22 (~42 MB). Runtime memory is approximately 300 MB.", color = SignalMuted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = importLanguage == "English", onClick = { importLanguage = "English" }, label = { Text("English") }, modifier = Modifier.weight(1f))
                FilterChip(selected = importLanguage == "Hindi", onClick = { importLanguage = "Hindi" }, label = { Text("Hindi") }, modifier = Modifier.weight(1f))
                FilterChip(selected = importLanguage == "Hinglish", onClick = { importLanguage = "Hinglish" }, label = { Text("Hinglish*") }, modifier = Modifier.weight(1f))
            }
            OutlinedButton(onClick = { modelPicker.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = modelState !is ModelImportState.Importing, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Import Model ZIP") }
            Text("*Hinglish recognition is experimental and depends on the vocabulary of the imported single-language model.", color = SignalMuted, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(color = SignalMuted.copy(.25f))
            Text("Acoustic voice-integrity model", color = SignalBlue, fontWeight = FontWeight.SemiBold)
            IntegrityModelPanel(integrityState, onImport = { integrityPicker.launch(arrayOf("application/onnx", "application/octet-stream")) }, onDelete = vm::deleteIntegrityModel)
        }
    }
}

@Composable
fun Phase2RealResultScreen(vm: Phase2ViewModel, onBack: () -> Unit) {
    val state by vm.uiState.collectAsState()
    val result = when (val current = state) { is Phase2UiState.Complete -> current.result; is Phase2UiState.ModelRequired -> current.result; else -> null }
    Scaffold(containerColor = Navy, topBar = { Phase2TopBar("Real audio result", onBack) }) { padding ->
        if (result == null) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("No real audio result is available.") }
        else RealResultContent(result, vm, Modifier.padding(padding))
    }
}

@Composable
private fun RealResultContent(result: RealAnalysisResult, vm: Phase2ViewModel, modifier: Modifier) {
    var selectedTime by rememberSaveable { mutableLongStateOf(0L) }
    var search by rememberSaveable { mutableStateOf("") }
    var retained by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val guidance = OverallGuidancePolicy.evaluate(result)
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this analysis?") },
        text = { Text("The in-memory result and transcript will be cleared. The original recording is never copied into VoxIT storage.") },
        confirmButton = { Button(onClick = { confirmDelete = false; vm.reset() }) { Text("Delete") } },
        dismissButton = { OutlinedButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("REAL MODE", color = SignalBlue, fontWeight = FontWeight.Bold)
        Text(result.metadata.fileName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("1. Audio quality", color = SignalBlue, fontWeight = FontWeight.SemiBold)
        RealWaveform(result.waveform, selectedTime) { selectedTime = it }
        Text("Seek preview: ${formatTime(selectedTime)} • teal bars are detected speech; muted bars are silence.", color = SignalMuted, style = MaterialTheme.typography.bodySmall)
        MetadataCard(result.metadata)
        Phase2Info("${result.quality.quality.label}: ${result.quality.quality.explanation}", qualityColor(result.quality.quality))
        MetricsCard(result)
        Text("Experimental audio information", color = SignalBlue, fontWeight = FontWeight.SemiBold)
        SignalCard(result.features)
        Text("These signal observations are not proof of AI generation and are not converted into a manipulation percentage.", color = SignalMuted, style = MaterialTheme.typography.bodySmall)
        Text("2. Transcript", color = SignalBlue, fontWeight = FontWeight.SemiBold)
        Text("Transcript not saved", color = SafeGreen, fontWeight = FontWeight.SemiBold)
        Text(result.transcriptionMessage, color = SignalMuted)
        if (result.transcript.isNotEmpty()) {
            OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Search transcript") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            result.transcript.filter { search.isBlank() || it.text.contains(search, true) }.forEach { segment ->
                val suspicious = result.conversationRisk.warnings.any { it.timestamp == segment.timestamp }
                Card(colors = CardDefaults.cardColors(containerColor = if (suspicious) AlertRed.copy(.16f) else MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth().clickable { selectedTime = segment.startMs }) {
                    Column(Modifier.padding(14.dp)) { Text("${segment.timestamp} • ${segment.language} • confirmed", color = SignalMuted, fontSize = 12.sp); Text(segment.text, fontWeight = if (suspicious) FontWeight.SemiBold else FontWeight.Normal); if (suspicious) Text("Experimental conversation-risk warning", color = AlertRed, fontSize = 12.sp) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { val text = result.transcript.joinToString("\n") { "${it.timestamp} ${it.text}" }; (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("VoxIT transcript", text)) }, modifier = Modifier.weight(1f)) { Text("Copy") }
                OutlinedButton(onClick = vm::deleteTranscript, modifier = Modifier.weight(1f)) { Text("Delete") }
            }
        }
        Text("3. Scam-language / context risk", color = SignalBlue, fontWeight = FontWeight.SemiBold)
        Text("Experimental conversation-risk warning", color = Amber, fontWeight = FontWeight.SemiBold)
        Phase2Info(result.conversationRisk.explanation, Amber)
        result.conversationRisk.warnings.forEach { warning -> WarningCard(warning) }
        Text("Transcription model", color = SignalBlue, fontWeight = FontWeight.SemiBold)
        Text(if (result.transcriptionModel == null) "Not installed" else "${result.transcriptionModel} • ${result.transcriptionVersion}")
        Text("4. Voice-integrity analysis", color = SignalBlue, fontWeight = FontWeight.SemiBold)
        IntegrityResultCard(result.voiceIntegrity)
        Phase2Info("Speaker verification not included in this prototype", SignalMuted)
        Text("5. Overall safety guidance", color = SignalBlue, fontWeight = FontWeight.SemiBold)
        Phase2Info(guidance.level.label, when (guidance.level) { GuidanceLevel.NO_STRONG_WARNING -> SafeGreen; GuidanceLevel.REVIEW -> Amber; GuidanceLevel.HIGH_CAUTION -> AlertRed })
        guidance.reasons.forEach { Text("• $it", color = SignalMuted) }
        Text("This guidance is not a fraud probability. It explains independent acoustic, transcript, and quality signals; unavailable detectors are never treated as zero risk.", color = SignalMuted, style = MaterialTheme.typography.bodySmall)
        Text("Limitations", color = SignalBlue, fontWeight = FontWeight.SemiBold)
        Phase2Info("Acoustic integrity and transcript scam analysis are separate warnings. AASIST-L is a binary bona-fide/spoof research model, not a reliable subtype classifier. Verify suspicious calls using an official number.")
        Button(onClick = { retained = vm.retainCurrentResult() }, enabled = !retained, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text(if (retained) "Result metadata retained locally" else "Retain result metadata locally") }
        Text("Retains only duration, quality, detector conclusions/scores, model names, and time—not filename, URI, audio, PCM, or transcript.", color = SignalMuted, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Delete result and transcript") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Phase2TopBar(title: String, onBack: () -> Unit) = CenterAlignedTopAppBar(title = { Text(title, fontWeight = FontWeight.SemiBold) }, navigationIcon = { OutlinedButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) { Text("Back") } }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Navy))

@Composable private fun RetryActions(vm: Phase2ViewModel, picker: androidx.activity.result.ActivityResultLauncher<Array<String>>) { OutlinedButton(onClick = vm::retry, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Retry") }; OutlinedButton(onClick = { vm.reset(); picker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Select Another File") } }
@Composable private fun SelectedFileCard(file: SelectedAudio) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(file.name, fontWeight = FontWeight.SemiBold); Text("${file.mimeType} • ${formatBytes(file.sizeBytes)}", color = SignalMuted) } }
@Composable private fun ResultReadyCard(result: RealAnalysisResult, message: String) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(message, fontWeight = FontWeight.SemiBold); Text("${formatDuration(result.metadata.durationMs)} • ${result.metadata.sampleRate} Hz • ${result.metadata.channelCount} channel(s)", color = SignalMuted); Text("${result.speechRegions.size} speech region(s) • ${formatDuration(result.quality.usableSpeechMs)} usable speech", color = SignalMuted) } }
@Composable private fun ModelStatus(state: ModelImportState) = when (state) { ModelImportState.Idle -> Phase2Info("No offline transcription model installed.", Amber); is ModelImportState.Importing -> Column { LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth()); Text("Importing and validating model… ${(state.progress * 100).roundToInt()}%", color = SignalMuted) }; is ModelImportState.Ready -> Phase2Info("Selected: ${state.model.displayName} • ${state.model.language}\nModel ID: ${state.model.id}\nPrivate path: ${state.model.pathIdentifier}", SafeGreen); is ModelImportState.Error -> Phase2Info(state.message, AlertRed) }

@Composable private fun ModelChooser(vm: Phase2ViewModel) {
    val catalog by vm.modelCatalog.collectAsState()
    if (catalog.models.isNotEmpty()) {
        Text("Choose the exact model used by uploaded and live transcription:", color = SignalMuted, style = MaterialTheme.typography.bodySmall)
        catalog.models.forEach { model ->
            FilterChip(
                selected = catalog.selectedModelId == model.id,
                onClick = { vm.selectModel(model.id) },
                enabled = model.validationStatus == ModelValidationStatus.VALID,
                label = { Text("${model.displayName} • ${model.language} • ${model.validationStatus.name.lowercase()}") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable fun ModelManagementPanel(vm: Phase2ViewModel) { val state by vm.modelState.collectAsState(); Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Offline transcription model", color = SignalBlue, fontWeight = FontWeight.SemiBold); ModelStatus(state); ModelChooser(vm); if (state is ModelImportState.Ready) OutlinedButton(onClick = { vm.deleteModel() }, modifier = Modifier.fillMaxWidth()) { Text("Delete selected model") }; Text("Models use separate app-private directories and are never uploaded. Switching is blocked while Live Protection is active.", color = SignalMuted, style = MaterialTheme.typography.bodySmall) } }
@Composable private fun Phase2Info(text: String, color: androidx.compose.ui.graphics.Color = SignalBlue) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) { Text(text, modifier = Modifier.padding(14.dp), color = color) }

@Composable private fun RealWaveform(points: List<WaveformPoint>, selectedMs: Long, onSeek: (Long) -> Unit) { val maxTime = points.lastOrNull()?.timeMs?.coerceAtLeast(1) ?: 1L; Canvas(Modifier.fillMaxWidth().height(150.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).pointerInput(points) { awaitEachGesture { val down = awaitFirstDown(); onSeek((down.position.x / size.width * maxTime).toLong().coerceIn(0, maxTime)); do { val event = awaitPointerEvent(); event.changes.firstOrNull()?.let { change -> onSeek((change.position.x / size.width * maxTime).toLong().coerceIn(0, maxTime)); change.consume() } } while (event.changes.any { it.pressed }) } }.padding(10.dp)) { if (points.isNotEmpty()) { val center = size.height / 2; points.forEachIndexed { index, point -> val x = index * size.width / points.size; val height = 5f + point.amplitude * size.height * .42f; drawLine(if (point.isSpeech) SafeGreen else SignalMuted, Offset(x, center - height), Offset(x, center + height), strokeWidth = 3f, cap = StrokeCap.Round) }; val markerX = selectedMs.toFloat() / maxTime * size.width; drawLine(Amber, Offset(markerX, 0f), Offset(markerX, size.height), strokeWidth = 4f) } } }
@Composable private fun MetadataCard(metadata: AudioMetadata) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Phase2Detail("MIME type", metadata.mimeType); Phase2Detail("File size", formatBytes(metadata.fileSizeBytes)); Phase2Detail("Duration", formatDuration(metadata.durationMs)); Phase2Detail("Source sample rate", "${metadata.sampleRate} Hz"); Phase2Detail("Channels", metadata.channelCount.toString()) } }
@Composable private fun MetricsCard(result: RealAnalysisResult) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Phase2Detail("RMS energy", "%.4f".format(result.quality.rmsEnergy)); Phase2Detail("Peak amplitude", "%.3f".format(result.quality.peakAmplitude)); Phase2Detail("Clipping", "%.2f%%".format(result.quality.clippingPercent)); Phase2Detail("Silence", "%.1f%%".format(result.quality.silencePercent)); Phase2Detail("Noise estimate", "%.4f RMS".format(result.quality.approximateNoiseRms)); Phase2Detail("Usable speech", formatDuration(result.quality.usableSpeechMs)); Text(result.speechRegions.joinToString { "${formatTime(it.startMs)}–${formatTime(it.endMs)}" }, color = SignalMuted, style = MaterialTheme.typography.bodySmall) } }
@Composable private fun UnavailableScores(result: RealAnalysisResult) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Phase2Score("Voice manipulation", result.manipulationScore?.let { "$it%" } ?: "Unavailable", if (result.manipulationScore == null) "No validated model result" else "Acoustic model", Modifier.weight(1f)); Phase2Score("Speaker mismatch", result.speakerMismatchScore?.toString() ?: "Not configured", "No reference speaker", Modifier.weight(1f)); Phase2Score("Scam risk", result.conversationRisk.score?.let { "$it%" } ?: "Unavailable", if (result.conversationRisk.score == null) "No real transcript" else "Transcript rules", Modifier.weight(1f)) }

@Composable private fun IntegrityModelPanel(state: IntegrityModelState, onImport: () -> Unit, onDelete: () -> Unit) {
    val color = if (state.status == IntegrityModelStatus.READY) SafeGreen else if (state.status in setOf(IntegrityModelStatus.CORRUPT, IntegrityModelStatus.INCOMPATIBLE, IntegrityModelStatus.ERROR)) AlertRed else Amber
    Phase2Info("${state.status.name.replace('_', ' ')} — ${state.message}" + (state.metadata?.let { "\n${it.name} • ${it.version}\nMIT • ${(it.sizeBytes / 1024)} KB • ${it.pathIdentifier}" } ?: ""), color)
    OutlinedButton(onClick = onImport, enabled = state.status !in setOf(IntegrityModelStatus.IMPORTING, IntegrityModelStatus.VALIDATING), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text(if (state.status == IntegrityModelStatus.READY) "Replace AASIST-L ONNX model" else "Import verified AASIST-L ONNX model") }
    if (state.status == IntegrityModelStatus.READY) OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Delete voice-integrity model") }
    Text("Required file: the reproducible VoxIT export of the official AASIST-L checkpoint. SHA-256 and tensor contract are verified before activation. It stays in app-private storage.", color = SignalMuted, style = MaterialTheme.typography.bodySmall)
}

@Composable private fun IntegrityResultCard(result: VoiceIntegrityResult) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("Acoustic voice integrity", color = SignalBlue, fontWeight = FontWeight.SemiBold)
        when (result) {
            is VoiceIntegrityResult.Unavailable -> Text("Unavailable — ${result.reason}", color = Amber)
            is VoiceIntegrityResult.Failed -> Text("Analysis failed — ${result.reason}", color = AlertRed)
            is VoiceIntegrityResult.Available -> {
                Text(result.conclusion.label, fontWeight = FontWeight.Bold, color = when (result.conclusion) { IntegrityConclusion.LIKELY_AUTHENTIC -> SafeGreen; IntegrityConclusion.POSSIBLE_MANIPULATION -> AlertRed; else -> Amber })
                Phase2Detail("Model score", "${result.score}% synthetic/spoof")
                Phase2Detail("Confidence", "${result.confidence}%")
                Phase2Detail("Threshold", "${(result.threshold * 100).toInt()}%")
                Phase2Detail("Windows", "${result.validWindows} • agreement ${(result.agreement * 100).toInt()}%")
                Phase2Detail("Speech analysed", formatDuration(result.analysedSpeechMs))
                Phase2Detail("Runtime", "load ${result.initializationMs} ms • window ${result.meanInferenceMs} ms")
                Text("${result.model.name} • ${result.model.version} • ${result.model.licence}", color = SignalMuted, style = MaterialTheme.typography.bodySmall)
                Text(result.calibration, color = Amber, style = MaterialTheme.typography.bodySmall)
                Text(result.limitations, color = SignalMuted, style = MaterialTheme.typography.bodySmall)
                Text("This acoustic warning can be wrong and does not prove fraud or identify a caller.", color = SignalMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
@Composable private fun Phase2Score(title: String, value: String, detail: String, modifier: Modifier) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = modifier) { Column(Modifier.padding(10.dp)) { Text(title, color = SignalMuted, fontSize = 11.sp); Text(value, fontWeight = FontWeight.Bold, fontSize = if (value.length < 4) 26.sp else 14.sp); Text(detail, color = SignalMuted, fontSize = 10.sp) } }
@Composable private fun SignalCard(features: SignalFeatures) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Phase2Detail("Zero-crossing rate", "%.4f".format(features.zeroCrossingRate)); Phase2Detail("Spectral centroid", "%.0f Hz".format(features.spectralCentroidHz)); Phase2Detail("Spectral roll-off", "%.0f Hz".format(features.spectralRolloffHz)); Phase2Detail("Spectral flatness", "%.3f".format(features.spectralFlatness)); Phase2Detail("Spectrum", "Low %.0f%% • Mid %.0f%% • High %.0f%%".format(features.lowBandPercent, features.midBandPercent, features.highBandPercent)); Phase2Detail("Pitch", features.pitchHz?.let { "%.1f Hz".format(it) } ?: "Unavailable"); Phase2Detail("Pitch stability", features.pitchStability?.toString() ?: "Insufficient observations") } }
@Composable private fun WarningCard(warning: TranscriptWarning) = Card(colors = CardDefaults.cardColors(containerColor = AlertRed.copy(.15f)), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("${warning.timestamp} • ${warning.category}", color = AlertRed, fontWeight = FontWeight.SemiBold); Text(warning.evidence); Text(warning.explanation, color = SignalMuted); Text("Confidence ${warning.confidence}% • ${warning.detectorMode}", color = SignalMuted, fontSize = 12.sp) } }
@Composable private fun Phase2Detail(label: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = SignalMuted); Text(value, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 220.dp)) }
private fun qualityColor(quality: RealAudioQuality) = when (quality) { RealAudioQuality.GOOD -> SafeGreen; RealAudioQuality.ACCEPTABLE -> SignalBlue; RealAudioQuality.NOISY, RealAudioQuality.CLIPPED, RealAudioQuality.TOO_QUIET, RealAudioQuality.INSUFFICIENT_SPEECH -> Amber; RealAudioQuality.UNSUPPORTED -> AlertRed }
private fun formatBytes(bytes: Long) = when { bytes < 0 -> "Size unavailable"; bytes < 1024 -> "$bytes B"; bytes < 1024 * 1024 -> "${bytes / 1024} KB"; else -> "%.1f MB".format(bytes / 1024f / 1024f) }
private fun formatDuration(ms: Long) = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)
