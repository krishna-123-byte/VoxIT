package com.voxit.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.voxit.app.live.*
import com.voxit.app.phase2.RealAudioQuality
import com.voxit.app.ui.theme.*

@Composable
fun LiveProtectionScreen(
    vm: LiveProtectionViewModel,
    onBack: () -> Unit,
    onOpenModelManager: () -> Unit,
    bubblePreferred: Boolean,
    setBubblePreferred: (Boolean) -> Unit,
    alertNotificationsEnabled: Boolean,
    vibrationEnabled: Boolean,
    alertThreshold: Int,
    selectedModelId: String?,
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val activity = context as Activity
    var showMicrophoneExplanation by rememberSaveable { mutableStateOf(false) }
    var showOverlayChoice by rememberSaveable { mutableStateOf(false) }
    var pendingStart by rememberSaveable { mutableStateOf(false) }
    var overlayChoiceForStart by rememberSaveable { mutableStateOf(false) }
    var permissionNote by rememberSaveable { mutableStateOf<String?>(null) }
    var lastAlertShown by rememberSaveable { mutableLongStateOf(0L) }

    fun options(useBubble: Boolean) = LiveStartOptions(useBubble, alertNotificationsEnabled, vibrationEnabled, alertThreshold, selectedModelId)
    fun finishStartAfterOverlay() {
        if (bubblePreferred && !Settings.canDrawOverlays(context)) { overlayChoiceForStart = true; showOverlayChoice = true }
        else vm.start(options(bubblePreferred && Settings.canDrawOverlays(context)))
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionNote = LivePermissionPolicy.notificationMessage(granted)
        finishStartAfterOverlay()
    }
    fun requestNotificationThenStart() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else finishStartAfterOverlay()
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestNotificationThenStart()
        else {
            val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            val decision = LivePermissionPolicy.microphoneState(false, canAskAgain)
            permissionNote = decision.second
            vm.markPermissionRequired(decision.second)
        }
    }
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val granted = Settings.canDrawOverlays(context)
        setBubblePreferred(granted)
        permissionNote = if (granted) "Floating bubble permission granted." else "Overlay permission denied. The foreground notification remains available."
        if (pendingStart) { pendingStart = false; vm.start(options(granted)) }
        else if (granted && state.microphoneActive) vm.showBubble()
    }

    if (showMicrophoneExplanation) AlertDialog(
        onDismissRequest = { showMicrophoneExplanation = false },
        title = { Text("Start microphone analysis?") },
        text = { Text("VoxIT will use the microphone only after you continue. It analyses ambient audio locally, saves no raw PCM, and cannot reliably access protected cellular or VoIP call audio.") },
        confirmButton = { Button(onClick = { showMicrophoneExplanation = false; if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) requestNotificationThenStart() else microphonePermission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Continue") } },
        dismissButton = { OutlinedButton(onClick = { showMicrophoneExplanation = false }) { Text("Not Now") } },
    )
    if (showOverlayChoice) AlertDialog(
        onDismissRequest = { showOverlayChoice = false; pendingStart = false; if (overlayChoiceForStart) vm.start(options(false)); overlayChoiceForStart = false },
        title = { Text("Optional floating bubble") },
        text = { Text("The VoxIT bubble requires Android's separate display-over-other-apps permission. Live Protection works with its persistent notification when this permission is denied.") },
        confirmButton = { Button(onClick = { showOverlayChoice = false; pendingStart = overlayChoiceForStart; overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))); overlayChoiceForStart = false }) { Text("Open Overlay Settings") } },
        dismissButton = { OutlinedButton(onClick = { showOverlayChoice = false; pendingStart = false; if (overlayChoiceForStart) vm.start(options(false)); overlayChoiceForStart = false }) { Text(if (overlayChoiceForStart) "Continue without bubble" else "Not Now") } },
    )
    if (state.status == LiveSessionStatus.ALERT && state.alertSequence > lastAlertShown) {
        AlertDialog(
            onDismissRequest = { lastAlertShown = state.alertSequence; vm.continueMonitoring() },
            title = { Text("Potential scam indicators detected") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(state.conversationRisk.warnings.lastOrNull()?.category ?: "Suspicious confirmed transcript context"); Text(LiveProtectionService.ALERT_TEXT) } },
            confirmButton = { Button(onClick = { lastAlertShown = state.alertSequence; vm.continueMonitoring() }) { Text("Continue monitoring") } },
            dismissButton = { Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                OutlinedButton(onClick = { lastAlertShown = state.alertSequence; vm.stop() }) { Text("Stop monitoring") }
                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL)) }) { Text("Verify Caller") }
                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }) { Text("Open Phone Controls") }
            } },
        )
    }

    Scaffold(containerColor = Navy, topBar = { LiveTopBar(onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("User-started Live Protection", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            LiveAtGlanceCard(state)
            LiveInfo("Live AI-voice analysis is not included in this prototype. Live Protection currently checks microphone availability, speech, transcription, and scam-language indicators.", SignalBlue)
            LiveInfo(state.sourceNotice, Amber)
            permissionNote?.let { LiveInfo(it, SignalBlue) }
            LiveWaveform(state.waveform)
            Text("Recent real microphone waveform • teal indicates detected speech", color = SignalMuted, style = MaterialTheme.typography.bodySmall)
            LiveQualityCard(state)

            Text("Live transcription", color = SignalBlue, fontWeight = FontWeight.SemiBold)
            LiveInfo(state.transcriptionStatus, if (state.transcriptionModel == null) Amber else SafeGreen)
            state.transcriptionModel?.let { LiveDetail("Actual loaded model", it) }
            state.transcriptionLanguage?.let { LiveDetail("Selected language", it) }
            state.transcriptionModelId?.let { LiveDetail("Loaded model ID", it) }
            state.transcriptionPathIdentifier?.let { LiveDetail("Private path", it) }
            if (state.microphoneActive && selectedModelId != state.transcriptionModelId) {
                LiveInfo("Restart Live Protection to apply the selected model.", Amber)
            }
            if (state.partialTranscript.isNotBlank()) {
                Text("Partial — not used for alerts", color = SignalMuted, style = MaterialTheme.typography.labelMedium)
                Text(state.partialTranscript)
            }
            if (state.confirmedTranscript.isEmpty()) Text("No confirmed transcript. Transcription absence is not a low-risk result.", color = SignalMuted)
            else state.confirmedTranscript.takeLast(8).forEach { segment ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) { Text("${segment.timestamp} • ${segment.language} • confirmed", color = SignalMuted, fontSize = 11.sp); Text(segment.text) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::clearTranscript, modifier = Modifier.weight(1f)) { Text("Clear Transcript") }
                OutlinedButton(onClick = onOpenModelManager, modifier = Modifier.weight(1f)) { Text("Model Manager") }
            }

            Text("Experimental conversation-risk warning", color = Amber, fontWeight = FontWeight.SemiBold)
            LiveInfo(state.conversationRisk.explanation, Amber)
            state.conversationRisk.warnings.takeLast(5).forEach { warning ->
                Card(colors = CardDefaults.cardColors(containerColor = AlertRed.copy(.15f)), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) { Text("${warning.timestamp} • ${warning.category}", color = AlertRed, fontWeight = FontWeight.SemiBold); Text(warning.evidence); Text(warning.explanation, color = SignalMuted); Text("Confidence ${warning.confidence}% • ${warning.detectorMode}", color = SignalMuted, fontSize = 11.sp) }
                }
            }
            LiveDetectorCard("Live AI-voice analysis", "Not included", "AASIST-L runs only for uploaded recordings", Modifier.fillMaxWidth())
            LiveInfo("Speaker verification not included in this prototype", SignalMuted)

            Text("Floating bubble", color = SignalBlue, fontWeight = FontWeight.SemiBold)
            LiveDetail("Status", state.bubbleStatus.label())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (Settings.canDrawOverlays(context)) { setBubblePreferred(true); vm.showBubble() }
                    else { overlayChoiceForStart = false; showOverlayChoice = true; pendingStart = false }
                }, modifier = Modifier.weight(1f)) { Text("Enable Bubble") }
                OutlinedButton(onClick = { setBubblePreferred(false); vm.hideBubble() }, modifier = Modifier.weight(1f)) { Text("Hide Bubble") }
            }

            when (state.status) {
                LiveSessionStatus.IDLE, LiveSessionStatus.STOPPED, LiveSessionStatus.PERMISSION_REQUIRED, LiveSessionStatus.ERROR, LiveSessionStatus.AUDIO_UNAVAILABLE -> Button(onClick = { showMicrophoneExplanation = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Start Protection") }
                LiveSessionStatus.PAUSED -> Button(onClick = vm::resume, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Resume") }
                else -> OutlinedButton(onClick = vm::pause, enabled = state.microphoneActive, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Pause") }
            }
            OutlinedButton(onClick = vm::stop, enabled = state.status !in setOf(LiveSessionStatus.IDLE, LiveSessionStatus.STOPPED, LiveSessionStatus.PERMISSION_REQUIRED), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Stop Protection") }
            if (state.status == LiveSessionStatus.PERMISSION_REQUIRED && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)) {
                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth()) { Text("Open App Permission Settings") }
            }
            LiveInfo("Speakerphone-call support is device-dependent and experimental. VoxIT never claims both participants were captured unless the input can actually be verified. Live PCM is bounded in memory, never written to disk, and cleared on Stop.", SignalBlue)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun LiveTopBar(onBack: () -> Unit) = CenterAlignedTopAppBar(title = { Text("Live Protection", fontWeight = FontWeight.SemiBold) }, navigationIcon = { OutlinedButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) { Text("Back") } }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Navy))

@Composable private fun LiveAtGlanceCard(state: LiveProtectionState) {
    val presentation = LiveStatusPresentationPolicy.evaluate(state)
    val accent = when (presentation.headline) {
        LiveHeadline.HIGH_CAUTION -> AlertRed
        LiveHeadline.SUSPICIOUS_LANGUAGE -> Amber
        LiveHeadline.NO_STRONG_SCAM_SIGNS -> SafeGreen
        LiveHeadline.AUDIO_UNAVAILABLE -> SignalMuted
        LiveHeadline.PAUSED -> Amber
        LiveHeadline.MONITORING -> SignalBlue
    }
    val symbol = when (presentation.headline) {
        LiveHeadline.HIGH_CAUTION -> "!"
        LiveHeadline.SUSPICIOUS_LANGUAGE -> "?"
        LiveHeadline.NO_STRONG_SCAM_SIGNS -> "✓"
        LiveHeadline.AUDIO_UNAVAILABLE -> "—"
        LiveHeadline.PAUSED -> "Ⅱ"
        LiveHeadline.MONITORING -> "●"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = .14f)),
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "Live Protection status. ${presentation.headline.label}. ${presentation.reason}"
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(accent.copy(alpha = .22f), androidx.compose.foundation.shape.CircleShape), contentAlignment = androidx.compose.ui.Alignment.Center) { Text(symbol, color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                Text(presentation.headline.label, color = accent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            }
            Text(presentation.reason)
            LiveDetail("Microphone", if (state.microphoneActive) "Active" else "Inactive")
            LiveDetail("Transcription", state.transcriptionStatus)
            LiveDetail("Selected language model", state.transcriptionModel?.let { "$it • ${state.transcriptionLanguage ?: "language unavailable"}" } ?: "Unavailable")
            LiveDetail("Elapsed", liveTime(state.elapsedMs))
            LiveDetail("Usable speech", liveTime(state.usableSpeechMs))
            LiveDetail("Speech state", if (state.speechDetected) "Speech detected" else "Silence / listening")
            state.conversationRisk.warnings.lastOrNull()?.let { LiveDetail("Latest warning", it.category) }
        }
    }
}

@Composable private fun LiveStatusCard(state: LiveProtectionState) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { LiveDetail("Status", state.status.label()); LiveDetail("Microphone", if (state.microphoneActive) "Active — visible foreground service" else "Inactive"); LiveDetail("Elapsed", liveTime(state.elapsedMs)); LiveDetail("Usable speech", liveTime(state.usableSpeechMs)); LiveDetail("Signal", if (state.speechDetected) "Speech detected" else "Silence / listening") } }
@Composable private fun LiveQualityCard(state: LiveProtectionState) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("Audio quality: ${state.quality.label}", color = state.quality.color(), fontWeight = FontWeight.SemiBold); Text(state.qualityExplanation, color = SignalMuted); LiveDetail("RMS", "%.4f".format(state.rms)); LiveDetail("Clipping", "%.2f%%".format(state.clippingPercent)) } }
@Composable private fun LiveWaveform(points: List<LiveWaveformPoint>) = Canvas(Modifier.fillMaxWidth().height(130.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(10.dp).semantics { contentDescription = "Real recent microphone waveform" }) { val center = size.height / 2; if (points.isEmpty()) drawLine(SignalMuted, Offset(0f, center), Offset(size.width, center), 2f) else points.forEachIndexed { index, point -> val x = (index + .5f) * size.width / points.size; val height = 4f + point.amplitude * size.height * .42f; drawLine(if (point.speech) SafeGreen else SignalBlue, Offset(x, center - height), Offset(x, center + height), 3f, StrokeCap.Round) } }
@Composable private fun LiveDetectorCard(title: String, value: String, detail: String, modifier: Modifier) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = modifier) { Column(Modifier.padding(9.dp)) { Text(title, color = SignalMuted, fontSize = 10.sp); Text(value, fontWeight = FontWeight.Bold, fontSize = if (value.length < 4) 22.sp else 12.sp); Text(detail, color = SignalMuted, fontSize = 9.sp) } }
@Composable private fun LiveInfo(text: String, accent: androidx.compose.ui.graphics.Color) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) { Text(text, modifier = Modifier.padding(13.dp), color = accent) }
@Composable private fun LiveDetail(label: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = SignalMuted); Text(value, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 230.dp)) }
private fun LiveSessionStatus.label() = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
private fun BubbleStatus.label() = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
private fun liveTime(ms: Long) = "%02d:%02d".format(ms / 60_000, ms / 1000 % 60)
private fun RealAudioQuality.color() = when (this) { RealAudioQuality.GOOD -> SafeGreen; RealAudioQuality.ACCEPTABLE -> SignalBlue; RealAudioQuality.NOISY, RealAudioQuality.CLIPPED, RealAudioQuality.TOO_QUIET, RealAudioQuality.INSUFFICIENT_SPEECH -> Amber; RealAudioQuality.UNSUPPORTED -> AlertRed }
