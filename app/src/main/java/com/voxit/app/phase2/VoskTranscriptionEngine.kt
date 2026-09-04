package com.voxit.app.phase2

import com.voxit.app.domain.TranscriptSegment
import com.voxit.app.domain.TranscriptionEngine
import com.voxit.app.domain.TranscriptionOutput
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoskTranscriptionEngine(private val modelStore: VoskModelStore) : TranscriptionEngine {
    override suspend fun transcribe(samples: FloatArray, sampleRate: Int, language: String): TranscriptionOutput {
        val selectedId = modelStore.selectedModelId()
            ?: return TranscriptionOutput.Unavailable("Offline transcription model not installed.")
        val installed = modelStore.resolve(selectedId)
            ?: return TranscriptionOutput.Unavailable("Selected model unavailable.")
        if (sampleRate != AudioLimits.TARGET_SAMPLE_RATE) return TranscriptionOutput.Failed("Transcription requires 16 kHz mono samples.")
        var model: Model? = null; var recognizer: Recognizer? = null
        return try {
            model = Model(installed.directory)
            recognizer = Recognizer(model, sampleRate.toFloat())
            recognizer.setWords(true)
            val segments = mutableListOf<TranscriptSegment>()
            val chunkSamples = 4_000
            var offset = 0
            while (offset < samples.size) {
                currentCoroutineContext().ensureActive()
                val end = (offset + chunkSamples).coerceAtMost(samples.size)
                val pcm = ByteBuffer.allocate((end - offset) * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (index in offset until end) pcm.putShort((samples[index].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                val bytes = pcm.array()
                if (recognizer.acceptWaveForm(bytes, bytes.size)) parseResult(recognizer.result, installed.language, segments)
                offset = end
            }
            parseResult(recognizer.finalResult, installed.language, segments)
            TranscriptionOutput.Available(segments.sortedBy { it.startMs }, installed.displayName, installed.version)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            TranscriptionOutput.Failed("The offline Vosk model could not transcribe this recording: ${error.message ?: "inference failed"}")
        } finally { try { recognizer?.close() } catch (_: Exception) {}; try { model?.close() } catch (_: Exception) {} }
    }

    private fun parseResult(json: String, language: String, output: MutableList<TranscriptSegment>) {
        val root = JSONObject(json)
        val text = root.optString("text").trim()
        if (text.isEmpty()) return
        val words = root.optJSONArray("result")
        val start = if (words != null && words.length() > 0) (words.getJSONObject(0).optDouble("start") * 1000).toLong() else output.lastOrNull()?.endMs ?: 0L
        val end = if (words != null && words.length() > 0) (words.getJSONObject(words.length() - 1).optDouble("end") * 1000).toLong() else start
        output += TranscriptSegment(formatTime(start), SensitiveDataRedactor.redact(text), language, confirmed = true, startMs = start, endMs = end)
    }
}

fun formatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
