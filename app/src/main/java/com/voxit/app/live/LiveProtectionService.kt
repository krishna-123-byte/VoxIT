package com.voxit.app.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.voxit.app.MainActivity
import com.voxit.app.R
import com.voxit.app.domain.TranscriptSegment
import com.voxit.app.phase2.AudioLimits
import com.voxit.app.phase2.AudioPipelineException
import com.voxit.app.phase2.AudioQualityAnalyzer
import com.voxit.app.phase2.EnergySpeechActivityDetector
import com.voxit.app.phase2.RealAudioQuality
import com.voxit.app.phase2.VoskModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class LiveProtectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val speechDetector = EnergySpeechActivityDetector()
    private var source: LiveAudioSource? = null
    private var queue: BoundedFrameQueue? = null
    private var captureJob: Job? = null
    private var processingJob: Job? = null
    private var timerJob: Job? = null
    private var transcriber: LiveVoskSession? = null
    private var bubble: FloatingBubbleController? = null
    private var paused = false
    private var stopping = false
    private var sessionStartedAt = 0L
    private var pausedStartedAt = 0L
    private var totalPausedMs = 0L
    private var options = LiveStartOptions(false, true, false, 70, null)
    private var alertGate = AlertGate(70)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bubble = FloatingBubbleController(this) { dispatch(ACTION_STOP) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> startSession(intent ?: Intent(this, LiveProtectionService::class.java))
            ACTION_PAUSE -> pauseSession()
            ACTION_RESUME, ACTION_CONTINUE -> resumeSession()
            ACTION_STOP -> stopSession()
            ACTION_SHOW_BUBBLE -> showBubble()
            ACTION_HIDE_BUBBLE -> { bubble?.hide(); LiveProtectionStore.update { it.copy(bubbleStatus = BubbleStatus.HIDDEN) } }
        }
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent) {
        if (captureJob?.isActive == true || processingJob?.isActive == true || stopping) return
        options = LiveStartOptions(
            bubbleRequested = intent.getBooleanExtra(EXTRA_BUBBLE, false),
            alertNotificationsEnabled = intent.getBooleanExtra(EXTRA_ALERT_NOTIFICATIONS, true),
            vibrationEnabled = intent.getBooleanExtra(EXTRA_VIBRATION, false),
            alertThreshold = intent.getIntExtra(EXTRA_THRESHOLD, 70).coerceIn(35, 100),
            selectedModelId = intent.getStringExtra(EXTRA_MODEL_ID),
        )
        alertGate = AlertGate(options.alertThreshold)
        val sessionId = SystemClock.elapsedRealtime()
        LiveProtectionStore.replace(LiveProtectionState(sessionId = sessionId, status = LiveSessionStatus.STARTING_SERVICE, transcriptionStatus = "Checking offline transcription model…"))
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Starting Live Protection", paused = false, alert = false),
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                ForegroundServiceApiPolicy.supportsMicrophoneType(Build.VERSION.SDK_INT)
            ) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
        stopping = false; paused = false; totalPausedMs = 0L; sessionStartedAt = SystemClock.elapsedRealtime()
        val audioSource = LiveAudioSourceProvider.create(applicationContext)
        source = audioSource
        val frameQueue = BoundedFrameQueue(8)
        queue = frameQueue

        processingJob = scope.launch { processFrames(frameQueue, sessionId) }
        captureJob = scope.launch(Dispatchers.IO) {
            try {
                LiveProtectionStore.update { it.copy(status = LiveSessionStatus.PREPARING) }
                audioSource.start()
                LiveProtectionStore.update { it.copy(status = LiveSessionStatus.LISTENING, microphoneActive = true, errorMessage = null) }
                if (options.bubbleRequested) showBubble()
                updateNotification()
                while (isActive) {
                    if (paused) { delay(80); continue }
                    val frame = audioSource.readFrame() ?: throw AudioPipelineException("Microphone input ended unexpectedly.")
                    if (frame.isNotEmpty()) frameQueue.offer(frame)
                }
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (error: SecurityException) {
                if (!stopping) fail(LiveSessionStatus.PERMISSION_REQUIRED, "Microphone permission was removed while Live Protection was running.")
            } catch (error: Exception) {
                if (!stopping) fail(LiveSessionStatus.AUDIO_UNAVAILABLE, error.message ?: "Microphone audio is unavailable.")
            } finally {
                if (!stopping) {
                    audioSource.close()
                    frameQueue.close()
                    timerJob?.cancel()
                }
            }
        }
        timerJob = scope.launch {
            while (isActive) {
                delay(1_000)
                if (!paused && !stopping) LiveProtectionStore.update { it.copy(elapsedMs = activeElapsed()) }
                updateNotification()
            }
        }
    }

    private suspend fun processFrames(frameQueue: BoundedFrameQueue, sessionId: Long) {
        val ring = FloatRingBuffer(AudioLimits.TARGET_SAMPLE_RATE * 2)
        val waveform = ArrayDeque<LiveWaveformPoint>(120)
        val confirmed = ArrayDeque<TranscriptSegment>(80)
        val riskAnalyzer = RollingRiskAnalyzer()
        var silentMs = 0L
        var usableSpeechMs = 0L
        var lastUiUpdate = 0L
        var lastSpeechUpdate = 0L
        try { transcriber?.close() } catch (_: Exception) { }
        transcriber = null
        val modelStore = VoskModelStore(applicationContext)
        val requestedModelId = options.selectedModelId
        val selectedModel = requestedModelId?.let(modelStore::resolve)
        var modelLoadError: String? = null
        if (requestedModelId != null && selectedModel == null) modelLoadError = "Selected model unavailable."
        transcriber = if (selectedModel == null) null else try {
            LiveVoskSession.create(selectedModel)
        } catch (_: Exception) {
            modelLoadError = "Selected model unavailable."
            null
        }
        LiveProtectionStore.update {
            val loaded = transcriber?.installedModel
            it.copy(
                transcriptionModelId = loaded?.id,
                transcriptionModel = loaded?.displayName,
                transcriptionLanguage = loaded?.language,
                transcriptionPathIdentifier = loaded?.pathIdentifier,
                transcriptionStatus = when {
                    loaded != null -> "Live offline transcription ready: ${loaded.displayName} • ${loaded.language}"
                    modelLoadError != null -> modelLoadError!!
                    else -> "Transcription model not installed"
                },
            )
        }
        try {
            while (scope.isActive && !stopping) {
                val frame = frameQueue.receive()
                if (paused || LiveProtectionStore.state.value.sessionId != sessionId) continue
                ring.add(frame)
                val frameMs = frame.size * 1000L / AudioLimits.TARGET_SAMPLE_RATE
                val peak = frame.maxOfOrNull { abs(it) } ?: 0f
                silentMs = if (peak < .00015f) silentMs + frameMs else 0L
                val now = activeElapsed()
                if (silentMs >= 3_000) {
                    LiveProtectionStore.update { it.copy(status = LiveSessionStatus.AUDIO_BLOCKED, microphoneActive = true, speechDetected = false, quality = RealAudioQuality.TOO_QUIET, qualityExplanation = "Call audio unavailable or blocked by Android. No risk conclusion is available.", conversationRisk = it.conversationRisk.copy(score = null)) }
                    bubble?.update(BubbleStatus.ERROR)
                }

                transcriber?.let { recognizer ->
                    val update = try { recognizer.accept(frame) } catch (_: Exception) { null }
                    if (update == null) LiveProtectionStore.update { it.copy(transcriptionStatus = "Installed transcription model became unavailable.", partialTranscript = "") }
                    else if (update.finalSegment != null) {
                        confirmed.addLast(update.finalSegment)
                        while (confirmed.size > 80) confirmed.removeFirst()
                        val risk = riskAnalyzer.analyse(confirmed.toList(), update.finalSegment.endMs)
                        LiveProtectionStore.update { it.copy(partialTranscript = "", confirmedTranscript = confirmed.toList(), conversationRisk = risk) }
                        val currentQuality = LiveProtectionStore.state.value.quality
                        if (alertGate.shouldAlert(risk, usableSpeechMs, currentQuality in setOf(RealAudioQuality.GOOD, RealAudioQuality.ACCEPTABLE), now)) triggerAlert(risk.warnings.size)
                    } else LiveProtectionStore.update { it.copy(partialTranscript = update.partial) }
                }

                val wallNow = SystemClock.elapsedRealtime()
                if (wallNow - lastUiUpdate >= 200) {
                    lastUiUpdate = wallNow
                    val recent = ring.snapshot()
                    val durationMs = recent.size * 1000L / AudioLimits.TARGET_SAMPLE_RATE
                    val regions = speechDetector.detect(recent, AudioLimits.TARGET_SAMPLE_RATE)
                    val speechNow = regions.lastOrNull()?.endMs?.let { durationMs - it <= 300 } == true
                    if (speechNow) usableSpeechMs += (wallNow - lastSpeechUpdate).coerceIn(0, 500)
                    lastSpeechUpdate = wallNow
                    val analysisRegions = if (regions.isEmpty()) emptyList() else regions
                    val measured = AudioQualityAnalyzer.analyse(recent, AudioLimits.TARGET_SAMPLE_RATE, analysisRegions)
                    val quickQuality = when {
                        silentMs >= 3_000 -> RealAudioQuality.TOO_QUIET
                        measured.clippingPercent > 1f -> RealAudioQuality.CLIPPED
                        measured.rmsEnergy < .006f -> RealAudioQuality.TOO_QUIET
                        measured.approximateNoiseRms > .04f -> RealAudioQuality.NOISY
                        usableSpeechMs < AudioLimits.MIN_USABLE_SPEECH_MS -> RealAudioQuality.ACCEPTABLE
                        else -> RealAudioQuality.GOOD
                    }
                    val qualityText = when (quickQuality) {
                        RealAudioQuality.GOOD -> "Audio is usable for local live analysis."
                        RealAudioQuality.ACCEPTABLE -> "Collecting more clear speech before alerting."
                        RealAudioQuality.NOISY -> "Background noise may reduce transcription accuracy."
                        RealAudioQuality.CLIPPED -> "Microphone input is clipping; move away from the audio source."
                        RealAudioQuality.TOO_QUIET -> if (silentMs >= 3_000) "Call audio unavailable or blocked by Android." else "Input is too quiet for reliable analysis."
                        else -> "Insufficient usable speech."
                    }
                    waveform.addLast(LiveWaveformPoint(peak.coerceIn(0f, 1f), speechNow))
                    while (waveform.size > 120) waveform.removeFirst()
                    val nextStatus = when {
                        silentMs >= 3_000 -> LiveSessionStatus.AUDIO_BLOCKED
                        quickQuality in setOf(RealAudioQuality.NOISY, RealAudioQuality.CLIPPED, RealAudioQuality.TOO_QUIET) -> LiveSessionStatus.LOW_QUALITY
                        speechNow && transcriber != null -> LiveSessionStatus.TRANSCRIBING
                        speechNow -> LiveSessionStatus.COLLECTING_SPEECH
                        else -> LiveSessionStatus.LISTENING
                    }
                    val rollingRisk = riskAnalyzer.analyse(confirmed.toList(), now)
                    LiveProtectionStore.update { current ->
                        val measuredState = current.copy(microphoneActive = true, elapsedMs = now, usableSpeechMs = usableSpeechMs, speechDetected = speechNow, waveform = waveform.toList(), quality = quickQuality, qualityExplanation = qualityText, rms = measured.rmsEnergy, clippingPercent = measured.clippingPercent, conversationRisk = rollingRisk)
                        if (current.status == LiveSessionStatus.ALERT) measuredState.copy(status = LiveSessionStatus.ALERT) else measuredState.copy(status = nextStatus)
                    }
                    bubble?.update(if (nextStatus == LiveSessionStatus.LOW_QUALITY || nextStatus == LiveSessionStatus.AUDIO_BLOCKED) BubbleStatus.ERROR else BubbleStatus.LISTENING)
                }
            }
        } catch (_: ClosedReceiveChannelException) { }
    }

    private fun pauseSession() {
        if (paused || stopping || source == null) return
        paused = true; pausedStartedAt = SystemClock.elapsedRealtime()
        scope.launch {
            try { source?.pause(); LiveProtectionStore.update { it.copy(status = LiveSessionStatus.PAUSED, microphoneActive = false, partialTranscript = "", bubbleStatus = if (bubble?.isShowing() == true) BubbleStatus.PAUSED else it.bubbleStatus) }; bubble?.update(BubbleStatus.PAUSED); updateNotification() }
            catch (error: Exception) { fail(LiveSessionStatus.ERROR, error.message ?: "Live Protection could not pause.") }
        }
    }

    private fun resumeSession() {
        if (!paused || stopping || source == null) {
            if (LiveProtectionStore.state.value.status == LiveSessionStatus.ALERT) { LiveProtectionStore.update { it.copy(status = LiveSessionStatus.LISTENING) }; bubble?.update(BubbleStatus.LISTENING); updateNotification() }
            return
        }
        scope.launch {
            try { source?.resume(); totalPausedMs += SystemClock.elapsedRealtime() - pausedStartedAt; paused = false; LiveProtectionStore.update { it.copy(status = LiveSessionStatus.LISTENING, microphoneActive = true, bubbleStatus = if (bubble?.isShowing() == true) BubbleStatus.LISTENING else it.bubbleStatus) }; bubble?.update(BubbleStatus.LISTENING); updateNotification() }
            catch (error: Exception) { fail(LiveSessionStatus.AUDIO_UNAVAILABLE, error.message ?: "Microphone could not resume.") }
        }
    }

    private fun stopSession() {
        if (stopping) return
        stopping = true
        LiveProtectionStore.update { it.copy(status = LiveSessionStatus.STOPPING, microphoneActive = false, partialTranscript = "") }
        source?.close()
        captureJob?.cancel(); processingJob?.cancel(); timerJob?.cancel(); queue?.close()
        captureJob = null; processingJob = null; timerJob = null; queue = null; source = null
        try { transcriber?.finish() } catch (_: Exception) { }
        try { transcriber?.close() } catch (_: Exception) { }
        transcriber = null
        bubble?.hide()
        LiveProtectionStore.replace(LiveProtectionState(sessionId = LiveProtectionStore.state.value.sessionId, status = LiveSessionStatus.STOPPED, qualityExplanation = "Live Protection stopped. Captured PCM and temporary transcript state were cleared.", transcriptionStatus = "Session stopped", bubbleStatus = BubbleStatus.HIDDEN))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun triggerAlert(evidenceCount: Int) {
        LiveProtectionStore.update { it.copy(status = LiveSessionStatus.ALERT, alertSequence = it.alertSequence + 1) }
        bubble?.update(BubbleStatus.WARNING)
        val backgroundAlert = !LiveProtectionStore.appVisible && options.alertNotificationsEnabled
        notificationManager.notify(
            NOTIFICATION_ID,
            notification(
                if (backgroundAlert) "Warning: $evidenceCount confirmed conversation pattern(s)" else "Open VoxIT to review confirmed conversation evidence",
                paused = false,
                alert = backgroundAlert,
            ),
        )
        if (!LiveProtectionStore.appVisible && options.vibrationEnabled) vibrate()
    }

    private fun fail(status: LiveSessionStatus, message: String) {
        LiveProtectionStore.update { it.copy(status = status, microphoneActive = false, errorMessage = message, qualityExplanation = message, conversationRisk = it.conversationRisk.copy(score = null), bubbleStatus = BubbleStatus.ERROR) }
        bubble?.update(BubbleStatus.ERROR)
        updateNotification()
    }

    private fun showBubble() {
        val shown = Settings.canDrawOverlays(this) && bubble?.show() == true
        LiveProtectionStore.update { it.copy(bubbleStatus = if (shown) if (paused) BubbleStatus.PAUSED else BubbleStatus.LISTENING else BubbleStatus.PERMISSION_REQUIRED) }
    }

    private fun activeElapsed() = (SystemClock.elapsedRealtime() - sessionStartedAt - totalPausedMs - if (paused) SystemClock.elapsedRealtime() - pausedStartedAt else 0L).coerceAtLeast(0)

    private fun updateNotification() {
        val state = LiveProtectionStore.state.value
        val backgroundAlert = state.status == LiveSessionStatus.ALERT && !LiveProtectionStore.appVisible && options.alertNotificationsEnabled
        notificationManager.notify(NOTIFICATION_ID, notification(state.status.notificationText(), paused, backgroundAlert))
    }

    private fun notification(text: String, paused: Boolean, alert: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).apply { action = ACTION_OPEN; addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP) }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pauseResume = servicePending(if (paused) ACTION_RESUME else ACTION_PAUSE, 2)
        val stop = servicePending(ACTION_STOP, 3)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_voxit)
            .setContentTitle(if (alert) "VoxIT conversation warning" else "VoxIT Live Protection")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (alert) ALERT_TEXT else text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(if (alert) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Open VoxIT", open)
            .addAction(0, if (paused) "Resume" else "Pause", pauseResume)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun servicePending(action: String, request: Int) = PendingIntent.getService(this, request, Intent(this, LiveProtectionService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Live Protection", NotificationManager.IMPORTANCE_LOW).apply { description = "Persistent microphone and safe warning status" })
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
        vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun dispatch(action: String) { startService(Intent(this, LiveProtectionService::class.java).setAction(action)) }

    override fun onTaskRemoved(rootIntent: Intent?) { stopSession(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() { if (!stopping) stopSession(); bubble?.hide(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.voxit.app.live.START"
        const val ACTION_PAUSE = "com.voxit.app.live.PAUSE"
        const val ACTION_RESUME = "com.voxit.app.live.RESUME"
        const val ACTION_CONTINUE = "com.voxit.app.live.CONTINUE"
        const val ACTION_STOP = "com.voxit.app.live.STOP"
        const val ACTION_OPEN = "com.voxit.app.live.OPEN"
        const val ACTION_SHOW_BUBBLE = "com.voxit.app.live.SHOW_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "com.voxit.app.live.HIDE_BUBBLE"
        const val EXTRA_BUBBLE = "bubble"
        const val EXTRA_ALERT_NOTIFICATIONS = "alert_notifications"
        const val EXTRA_VIBRATION = "vibration"
        const val EXTRA_THRESHOLD = "threshold"
        const val EXTRA_MODEL_ID = "model_id"
        private const val CHANNEL_ID = "voxit_live_protection"
        private const val NOTIFICATION_ID = 3107
        const val ALERT_TEXT = "Suspicious conversation patterns were detected. This is a warning, not proof. Do not share OTPs, PINs, passwords or payment details. Verify the caller through an official number."
    }
}

/** Keeps the API boundary testable without starting an Android service. */
object ForegroundServiceApiPolicy {
    fun supportsMicrophoneType(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.R
}

private fun LiveSessionStatus.notificationText() = when (this) {
    LiveSessionStatus.PAUSED -> "Microphone paused"
    LiveSessionStatus.AUDIO_BLOCKED -> "Call audio unavailable or blocked by Android"
    LiveSessionStatus.LOW_QUALITY -> "Audio quality is limited"
    LiveSessionStatus.ALERT -> "Suspicious conversation patterns detected — warning, not proof"
    LiveSessionStatus.ERROR, LiveSessionStatus.AUDIO_UNAVAILABLE -> "Microphone unavailable"
    LiveSessionStatus.STOPPING, LiveSessionStatus.STOPPED -> "Stopping Live Protection"
    else -> "Microphone active • ambient input only"
}
