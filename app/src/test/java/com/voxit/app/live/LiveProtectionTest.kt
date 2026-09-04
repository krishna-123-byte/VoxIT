package com.voxit.app.live

import com.voxit.app.domain.TranscriptSegment
import com.voxit.app.phase2.EnergySpeechActivityDetector
import com.voxit.app.phase2.InstalledModel
import com.voxit.app.phase2.ModelValidationStatus
import com.voxit.app.phase2.OfflineConversationRiskEngine
import com.voxit.app.phase2.VoskModelIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LiveProtectionTest {
    @Test fun permissionDenialStatesAreExplicit() {
        assertEquals(LiveSessionStatus.PERMISSION_REQUIRED, LivePermissionPolicy.microphoneState(false, true).first)
        assertTrue(LivePermissionPolicy.microphoneState(false, false).second.contains("app settings"))
        assertTrue(LivePermissionPolicy.notificationMessage(false).contains("denied"))
        assertEquals(BubbleStatus.PERMISSION_REQUIRED, LivePermissionPolicy.overlayState(false))
    }

    @Test fun silentAndSpeechLikeFramesAreDistinguished() {
        val detector = EnergySpeechActivityDetector()
        assertTrue(detector.detect(FloatArray(32_000), 16_000).isEmpty())
        val speech = FloatArray(32_000) { index -> if (index < 4_000 || index > 28_000) 0f else (.2 * sin(2 * PI * 180 * index / 16_000)).toFloat() }
        assertTrue(detector.detect(speech, 16_000).isNotEmpty())
    }

    @Test fun boundedQueueDropsOldestUnderBackpressure() = runBlocking {
        val queue = BoundedFrameQueue(2)
        queue.offer(floatArrayOf(1f)); queue.offer(floatArrayOf(2f)); queue.offer(floatArrayOf(3f))
        assertEquals(2f, queue.receive().first(), 0f)
        assertEquals(3f, queue.receive().first(), 0f)
        queue.close()
        Unit
    }

    @Test fun fakeSourceSupportsPauseResumeStopAndInitializationFailure() = runBlocking {
        val source = FakeLiveAudioSource(listOf(FloatArray(320) { .1f }))
        source.start(); assertNotNull(source.readFrame()); source.pause(); assertNull(source.readFrame()); source.resume(); assertNotNull(source.readFrame()); source.close()
        assertTrue(source.closed); assertTrue(source.frames.all { frame -> frame.all { it == 0f } })
        val failing = FakeLiveAudioSource(emptyList(), failOnStart = true)
        assertTrue(runCatching { failing.start() }.isFailure)
    }

    @Test fun newStateDoesNotCarryPreviousSessionRiskOrWaveform() {
        val old = LiveProtectionState(sessionId = 4, waveform = listOf(LiveWaveformPoint(.8f, true)), confirmedTranscript = listOf(segment("previous session", 1_000)), partialTranscript = "partial", transcriptionModelId = "hi-model", conversationRisk = OfflineConversationRiskEngine().analyse(listOf(segment("Tell me your OTP", 1_000))))
        val fresh = LiveProtectionState(sessionId = old.sessionId + 1, status = LiveSessionStatus.PREPARING)
        assertTrue(fresh.waveform.isEmpty()); assertNull(fresh.conversationRisk.score); assertTrue(fresh.confirmedTranscript.isEmpty()); assertTrue(fresh.partialTranscript.isEmpty()); assertNull(fresh.transcriptionModelId)
    }

    @Test fun rollingRiskRisesAndExpiresWithoutTimeBasedInflation() {
        val analyzer = RollingRiskAnalyzer(windowMs = 10_000)
        val evidence = listOf(segment("Tell me your OTP immediately", 1_000))
        assertTrue((analyzer.analyse(evidence, 2_000).score ?: 0) > 0)
        assertNull(analyzer.analyse(evidence, 20_000).score)
    }

    @Test fun alertRequiresSpeechQualityThresholdAndCooldown() {
        val risk = OfflineConversationRiskEngine().analyse(listOf(segment("Tell me your OTP immediately", 1_000)))
        val gate = AlertGate(threshold = 40, cooldownMs = 5_000)
        assertFalse(gate.shouldAlert(risk, 500, true, 2_000))
        assertFalse(gate.shouldAlert(risk, 2_000, false, 2_000))
        assertTrue(gate.shouldAlert(risk, 2_000, true, 2_000))
        assertFalse(gate.shouldAlert(risk, 2_000, true, 3_000))
        assertTrue(gate.shouldAlert(risk, 2_000, true, 8_000))
    }

    @Test fun realLiveStateHasNoDemoScoresOrTranscript() {
        val state = LiveProtectionState(status = LiveSessionStatus.LISTENING)
        assertNull(state.conversationRisk.score)
        assertTrue(state.confirmedTranscript.isEmpty())
        assertFalse(state.sourceNotice.contains("demo", true))
    }

    @Test fun modelIdentitiesAreStableAndUnambiguousAcrossLanguages() {
        val english = VoskModelIdentity.stableId("vosk-model-small-en-us-0.15", "en-US")
        val hindi = VoskModelIdentity.stableId("vosk-model-small-hi-0.22", "hi-IN")
        assertEquals(english, VoskModelIdentity.stableId("vosk-model-small-en-us-0.15", "en-US"))
        assertNotEquals(english, hindi)
        assertEquals("en-US", VoskModelIdentity.detect("vosk-model-small-en-us-0.15.zip")?.languageCode)
        assertEquals("hi-IN", VoskModelIdentity.detect("vosk-model-small-hi-0.22.zip")?.languageCode)
        assertNotNull(VoskModelIdentity.validateExpected("English", VoskModelIdentity.detect("vosk-model-small-hi-0.22")))
    }

    @Test fun closingOldLiveRecognizerAndModelPreventsReuseByNewSession() {
        val hindiRuntime = FakeVoskRuntime()
        val hindiSession = LiveVoskSession.create(installed("hi", "Hindi"), hindiRuntime)
        assertEquals("hi", hindiSession.installedModel.id)
        hindiSession.close()
        assertTrue(hindiRuntime.recognizerClosed)
        assertTrue(hindiRuntime.modelClosed)

        val englishRuntime = FakeVoskRuntime()
        val englishSession = LiveVoskSession.create(installed("en", "English"), englishRuntime)
        assertEquals("en", englishSession.installedModel.id)
        assertFalse(englishRuntime.recognizerClosed)
        englishSession.close()
        assertTrue(englishRuntime.recognizerClosed)
        assertTrue(englishRuntime.modelClosed)
    }

    private fun segment(text: String, start: Long) = TranscriptSegment("00:01", text, "English", confirmed = true, startMs = start, endMs = start + 1_000)
    private fun installed(id: String, language: String) = InstalledModel(id, "/test/$id", "$language model", language, id, "test", "$language model", 1L, ModelValidationStatus.VALID, "vosk-models/$id")
}

private class FakeVoskRuntime : LiveVoskRuntime {
    var modelClosed = false
    var recognizerClosed = false
    override fun openModel(path: String) = object : VoskModelHandle { override fun close() { modelClosed = true } }
    override fun openRecognizer(model: VoskModelHandle, sampleRate: Float) = object : VoskRecognizerHandle {
        override fun acceptWaveForm(bytes: ByteArray, length: Int) = false
        override val result = "{\"text\":\"\"}"
        override val partialResult = "{\"partial\":\"\"}"
        override val finalResult = "{\"text\":\"\"}"
        override fun close() { recognizerClosed = true }
    }
}

class FakeLiveAudioSource(
    val frames: List<FloatArray>,
    private val failOnStart: Boolean = false,
) : LiveAudioSource {
    override val sourceSampleRate = 16_000
    private var paused = false
    var closed = false; private set
    private var index = 0
    override suspend fun start() { if (failOnStart) error("Synthetic initialization failure") }
    override suspend fun readFrame(): FloatArray? = if (paused || closed) null else frames.getOrNull(index++) ?: frames.lastOrNull()
    override suspend fun pause() { paused = true }
    override suspend fun resume() { paused = false }
    override fun close() { frames.forEach { it.fill(0f) }; closed = true }
}
