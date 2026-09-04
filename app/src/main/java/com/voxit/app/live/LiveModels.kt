package com.voxit.app.live

import com.voxit.app.domain.TranscriptSegment
import com.voxit.app.phase2.ConversationRiskResult
import com.voxit.app.phase2.RealAudioQuality
import com.voxit.app.phase2.TranscriptWarning

enum class LiveSessionStatus {
    IDLE, PERMISSION_REQUIRED, PREPARING, STARTING_SERVICE, LISTENING,
    COLLECTING_SPEECH, TRANSCRIBING, PAUSED, AUDIO_UNAVAILABLE, AUDIO_BLOCKED,
    LOW_QUALITY, ALERT, STOPPING, STOPPED, ERROR
}

enum class BubbleStatus { DISABLED, PERMISSION_REQUIRED, LISTENING, PAUSED, WARNING, ERROR, HIDDEN }

data class LiveWaveformPoint(val amplitude: Float, val speech: Boolean)

data class LiveProtectionState(
    val sessionId: Long = 0,
    val status: LiveSessionStatus = LiveSessionStatus.IDLE,
    val microphoneActive: Boolean = false,
    val elapsedMs: Long = 0,
    val usableSpeechMs: Long = 0,
    val speechDetected: Boolean = false,
    val waveform: List<LiveWaveformPoint> = emptyList(),
    val quality: RealAudioQuality = RealAudioQuality.INSUFFICIENT_SPEECH,
    val qualityExplanation: String = "Waiting for user to start Live Protection.",
    val rms: Float = 0f,
    val clippingPercent: Float = 0f,
    val partialTranscript: String = "",
    val confirmedTranscript: List<TranscriptSegment> = emptyList(),
    val conversationRisk: ConversationRiskResult = ConversationRiskResult(null, emptyList(), "No confirmed transcript is available."),
    val transcriptionModelId: String? = null,
    val transcriptionModel: String? = null,
    val transcriptionLanguage: String? = null,
    val transcriptionPathIdentifier: String? = null,
    val transcriptionStatus: String = "Transcription model not installed",
    val bubbleStatus: BubbleStatus = BubbleStatus.DISABLED,
    val alertSequence: Long = 0,
    val errorMessage: String? = null,
    val sourceNotice: String = "Ambient microphone input only. Protected call audio may be unavailable or blocked by Android.",
)

data class LiveStartOptions(
    val bubbleRequested: Boolean,
    val alertNotificationsEnabled: Boolean,
    val vibrationEnabled: Boolean,
    val alertThreshold: Int,
    val selectedModelId: String?,
)

interface LiveAudioSource {
    val sourceSampleRate: Int
    suspend fun start()
    suspend fun readFrame(): FloatArray?
    suspend fun pause()
    suspend fun resume()
    fun close()
}

fun interface LiveAudioSourceFactory { fun create(): LiveAudioSource }

class FloatRingBuffer(private val capacity: Int) {
    private val values = FloatArray(capacity.coerceAtLeast(1))
    private var writeIndex = 0
    private var count = 0

    fun add(frame: FloatArray) {
        frame.forEach { value -> values[writeIndex] = value; writeIndex = (writeIndex + 1) % values.size; count = (count + 1).coerceAtMost(values.size) }
    }

    fun snapshot(): FloatArray {
        val output = FloatArray(count)
        val start = (writeIndex - count + values.size) % values.size
        for (index in 0 until count) output[index] = values[(start + index) % values.size]
        return output
    }

    fun clear() { values.fill(0f); writeIndex = 0; count = 0 }
    val size: Int get() = count
}

class RollingRiskAnalyzer(
    private val windowMs: Long = 60_000,
    private val delegate: com.voxit.app.phase2.OfflineConversationRiskEngine = com.voxit.app.phase2.OfflineConversationRiskEngine(),
) {
    fun analyse(segments: List<TranscriptSegment>, nowMs: Long): ConversationRiskResult {
        val recent = segments.filter { nowMs - it.endMs <= windowMs && it.endMs <= nowMs }.sortedBy { it.startMs }
        return delegate.analyse(recent)
    }
}

class AlertGate(
    private val threshold: Int,
    private val cooldownMs: Long = 30_000,
    private val minimumSpeechMs: Long = 1_500,
) {
    private var lastAlertMs = Long.MIN_VALUE

    fun shouldAlert(risk: ConversationRiskResult, usableSpeechMs: Long, adequateQuality: Boolean, nowMs: Long): Boolean {
        val severe = risk.warnings.any { it.category == "Sensitive credential request" || it.category == "Remote-access request" }
        val supported = risk.warnings.map(TranscriptWarning::category).distinct().size >= 2 || severe
        val cooledDown = lastAlertMs == Long.MIN_VALUE || nowMs - lastAlertMs >= cooldownMs
        val allowed = usableSpeechMs >= minimumSpeechMs && adequateQuality && risk.score != null && risk.score >= threshold && supported && cooledDown
        if (allowed) lastAlertMs = nowMs
        return allowed
    }

    fun reset() { lastAlertMs = Long.MIN_VALUE }
}

class BoundedFrameQueue(capacity: Int = 8) {
    private val frames = kotlinx.coroutines.channels.Channel<FloatArray>(capacity, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    fun offer(frame: FloatArray): Boolean = frames.trySend(frame).isSuccess
    suspend fun receive(): FloatArray = frames.receive()
    fun close() = frames.close()
}

object LivePermissionPolicy {
    fun microphoneState(granted: Boolean, canAskAgain: Boolean): Pair<LiveSessionStatus, String> = when {
        granted -> LiveSessionStatus.PREPARING to "Microphone permission granted."
        canAskAgain -> LiveSessionStatus.PERMISSION_REQUIRED to "Microphone permission is required only while Live Protection is running."
        else -> LiveSessionStatus.PERMISSION_REQUIRED to "Microphone permission is blocked. Enable it in Android app settings to use Live Protection."
    }

    fun notificationMessage(granted: Boolean) = if (granted) "Live status notification enabled."
    else "Notification permission denied. Android still shows foreground-service status in system controls."

    fun overlayState(granted: Boolean) = if (granted) BubbleStatus.LISTENING else BubbleStatus.PERMISSION_REQUIRED
}
