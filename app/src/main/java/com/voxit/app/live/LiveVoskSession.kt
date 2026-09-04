package com.voxit.app.live

import com.voxit.app.domain.TranscriptSegment
import com.voxit.app.phase2.AudioLimits
import com.voxit.app.phase2.InstalledModel
import com.voxit.app.phase2.SensitiveDataRedactor
import com.voxit.app.phase2.formatTime
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class LiveTranscriptionUpdate(val partial: String = "", val finalSegment: TranscriptSegment? = null)

interface VoskModelHandle : Closeable

interface VoskRecognizerHandle : Closeable {
    fun acceptWaveForm(bytes: ByteArray, length: Int): Boolean
    val result: String
    val partialResult: String
    val finalResult: String
}

interface LiveVoskRuntime {
    fun openModel(path: String): VoskModelHandle
    fun openRecognizer(model: VoskModelHandle, sampleRate: Float): VoskRecognizerHandle
}

object AndroidLiveVoskRuntime : LiveVoskRuntime {
    private class AndroidModel(val delegate: Model) : VoskModelHandle { override fun close() = delegate.close() }
    private class AndroidRecognizer(private val delegate: Recognizer) : VoskRecognizerHandle {
        override fun acceptWaveForm(bytes: ByteArray, length: Int) = delegate.acceptWaveForm(bytes, length)
        override val result: String get() = delegate.result
        override val partialResult: String get() = delegate.partialResult
        override val finalResult: String get() = delegate.finalResult
        override fun close() = delegate.close()
    }

    override fun openModel(path: String): VoskModelHandle = AndroidModel(Model(path))
    override fun openRecognizer(model: VoskModelHandle, sampleRate: Float): VoskRecognizerHandle {
        val androidModel = model as? AndroidModel ?: error("Incompatible Vosk model handle")
        return AndroidRecognizer(Recognizer(androidModel.delegate, sampleRate).also { it.setWords(true) })
    }
}

class LiveVoskSession private constructor(
    val installedModel: InstalledModel,
    private val model: VoskModelHandle,
    private val recognizer: VoskRecognizerHandle,
) : Closeable {
    val modelLabel: String get() = "${installedModel.displayName} • ${installedModel.language}"
    private var closed = false

    fun accept(samples: FloatArray): LiveTranscriptionUpdate {
        check(!closed) { "Recognizer is closed" }
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { pcm.putShort((it.coerceIn(-1f, 1f) * 32767f).toInt().toShort()) }
        return if (recognizer.acceptWaveForm(pcm.array(), pcm.position())) {
            LiveTranscriptionUpdate(finalSegment = parseFinal(recognizer.result))
        } else {
            LiveTranscriptionUpdate(partial = LiveVoskResultParser.partial(recognizer.partialResult))
        }
    }

    fun finish(): TranscriptSegment? = if (closed) null else parseFinal(recognizer.finalResult)
    private fun parseFinal(json: String) = LiveVoskResultParser.final(json, installedModel.language)

    override fun close() {
        if (closed) return
        closed = true
        try { recognizer.close() } finally { model.close() }
    }

    companion object {
        fun create(installed: InstalledModel, runtime: LiveVoskRuntime = AndroidLiveVoskRuntime): LiveVoskSession {
            val model = runtime.openModel(installed.directory)
            return try {
                LiveVoskSession(installed, model, runtime.openRecognizer(model, AudioLimits.TARGET_SAMPLE_RATE.toFloat()))
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
