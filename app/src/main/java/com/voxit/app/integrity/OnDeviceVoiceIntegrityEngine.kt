package com.voxit.app.integrity

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.voxit.app.domain.VoiceIntegrityEngine
import com.voxit.app.phase2.AudioQualityMetrics
import com.voxit.app.phase2.RealAudioQuality
import com.voxit.app.phase2.SpeechRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.exp

class OnDeviceVoiceIntegrityEngine(private val store: IntegrityModelStore) : VoiceIntegrityEngine {
    private val mutex = Mutex()

    override suspend fun analyse(samples: FloatArray, sampleRate: Int, speechRegions: List<SpeechRegion>, quality: AudioQualityMetrics): VoiceIntegrityResult = withContext(Dispatchers.Default) {
        if (sampleRate != 16_000) return@withContext VoiceIntegrityResult.Unavailable("Voice-integrity preprocessing requires 16 kHz mono PCM.")
        if (quality.usableSpeechMs < 1_500) return@withContext VoiceIntegrityResult.Unavailable("Insufficient speech for voice-integrity analysis.")
        if (quality.quality in setOf(RealAudioQuality.TOO_QUIET, RealAudioQuality.CLIPPED, RealAudioQuality.UNSUPPORTED)) return@withContext VoiceIntegrityResult.Unavailable("Audio quality is insufficient for voice-integrity analysis.")
        val state = store.state()
        val metadata = state.metadata
        val file = store.modelFileOrNull()
        if (state.status != IntegrityModelStatus.READY || metadata == null || file == null) return@withContext VoiceIntegrityResult.Unavailable(state.message)
        val windows = IntegrityWindowBuilder.build(samples, sampleRate, speechRegions)
        if (windows.isEmpty()) return@withContext VoiceIntegrityResult.Unavailable("No model-compatible speech window was available.")
        mutex.withLock {
            try {
                val env = OrtEnvironment.getEnvironment()
                val loadStart = System.nanoTime()
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2)
                    env.createSession(file.absolutePath, options).use { session ->
                        val initializationMs = (System.nanoTime() - loadStart) / 1_000_000
                        var inferenceTotal = 0L
                        val segmentResults = windows.map { window ->
                            currentCoroutineContext().ensureActive()
                            val start = System.nanoTime()
                            val probability = infer(env, session, window.samples)
                            inferenceTotal += (System.nanoTime() - start) / 1_000_000
                            IntegritySegmentResult(window.startMs, window.endMs, probability)
                        }
                        val aggregated = IntegrityAggregator.aggregate(segmentResults.map { it.syntheticProbability }, quality.quality)
                        VoiceIntegrityResult.Available(
                            score = aggregated.first, conclusion = aggregated.second, confidence = aggregated.third.first,
                            threshold = IntegrityAggregator.CLASSIFICATION_THRESHOLD, analysedSpeechMs = speechRegions.sumOf { it.durationMs },
                            validWindows = windows.size, agreement = aggregated.third.second, segments = segmentResults,
                            model = metadata, initializationMs = initializationMs,
                            meanInferenceMs = inferenceTotal / windows.size.coerceAtLeast(1),
                        )
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { VoiceIntegrityResult.Failed("Voice-integrity inference failed safely. The model may be incompatible or corrupt.") }
        }
    }

    private fun infer(env: OrtEnvironment, session: OrtSession, samples: FloatArray): Float {
        require(samples.size == IntegrityWindowBuilder.WINDOW_SAMPLES)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { tensor ->
            session.run(mapOf("audio" to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST") val output = result[0].value as Array<FloatArray>
                require(output.size == 1 && output[0].size == 2 && output[0].all(Float::isFinite))
                // Official AASIST uses class 1 as bona fide; class 0 is spoof.
                val spoof = output[0][0]; val bonaFide = output[0][1]
                val max = maxOf(spoof, bonaFide)
                val spoofExp = exp((spoof - max).toDouble())
                return (spoofExp / (spoofExp + exp((bonaFide - max).toDouble()))).toFloat()
            }
        }
    }
}
