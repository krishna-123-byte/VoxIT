package com.voxit.app.live

import com.voxit.app.domain.TranscriptSegment
import com.voxit.app.phase2.AudioLimits
import com.voxit.app.phase2.InstalledModel
import com.voxit.app.phase2.SensitiveDataRedactor
import com.voxit.app.phase2.VoskModelStore
import com.voxit.app.phase2.formatTime
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class LiveTranscriptionUpdate(val partial: String = "", val finalSegment: TranscriptSegment? = null)

class LiveVoskSession private constructor(
    private val installed: InstalledModel,
    private val model: Model,
    private val recognizer: Recognizer,
) : Closeable {
    val modelLabel: String get() = "${installed.displayName} • ${installed.language}"

    fun accept(samples: FloatArray): LiveTranscriptionUpdate {
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { pcm.putShort((it.coerceIn(-1f, 1f) * 32767f).toInt().toShort()) }
        return if (recognizer.acceptWaveForm(pcm.array(), pcm.position())) {
            LiveTranscriptionUpdate(finalSegment = parseFinal(recognizer.result))
        } else {
            val partial = LiveVoskResultParser.partial(recognizer.partialResult)
            LiveTranscriptionUpdate(partial = partial)
        }
    }

    fun finish(): TranscriptSegment? = parseFinal(recognizer.finalResult)

    private fun parseFinal(json: String): TranscriptSegment? {
        return LiveVoskResultParser.final(json, installed.language)
    }

    override fun close() { try { recognizer.close() } finally { model.close() } }

    companion object {
        fun create(store: VoskModelStore): LiveVoskSession? {
            val installed = store.installedModel() ?: return null
            val model = Model(installed.directory)
            return try {
                val recognizer = Recognizer(model, AudioLimits.TARGET_SAMPLE_RATE.toFloat()).also { it.setWords(true) }
                LiveVoskSession(installed, model, recognizer)
            } catch (error: Exception) {
                model.close()
                throw error
            }
        }
    }
}

object LiveVoskResultParser {
    fun partial(json: String): String = SensitiveDataRedactor.redact(JSONObject(json).optString("partial").trim())

    fun final(json: String, language: String): TranscriptSegment? {
        val root = JSONObject(json)
        val text = root.optString("text").trim()
        if (text.isEmpty()) return null
        val words = root.optJSONArray("result")
        val startMs = if (words != null && words.length() > 0) (words.getJSONObject(0).optDouble("start") * 1000).toLong() else 0L
        val endMs = if (words != null && words.length() > 0) (words.getJSONObject(words.length() - 1).optDouble("end") * 1000).toLong() else startMs
        return TranscriptSegment(formatTime(startMs), SensitiveDataRedactor.redact(text), language, confirmed = true, startMs = startMs, endMs = endMs)
    }
}
