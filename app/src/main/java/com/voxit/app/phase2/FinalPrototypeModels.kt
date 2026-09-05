package com.voxit.app.phase2

import android.content.Context
import com.voxit.app.integrity.IntegrityConclusion
import com.voxit.app.integrity.VoiceIntegrityResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class GuidanceLevel(val label: String) {
    NO_STRONG_WARNING("No strong warning detected"),
    REVIEW("Review recommended"),
    HIGH_CAUTION("High caution recommended"),
}

data class OverallGuidance(val level: GuidanceLevel, val reasons: List<String>)

/** Combines explanations, never detector values. It is deliberately not a fraud probability. */
object OverallGuidancePolicy {
    fun evaluate(result: RealAnalysisResult): OverallGuidance {
        val reasons = mutableListOf<String>()
        val integrity = result.voiceIntegrity as? VoiceIntegrityResult.Available
        val poorAudio = result.quality.quality in setOf(
            RealAudioQuality.NOISY,
            RealAudioQuality.CLIPPED,
            RealAudioQuality.TOO_QUIET,
            RealAudioQuality.INSUFFICIENT_SPEECH,
            RealAudioQuality.UNSUPPORTED,
        )
        if (poorAudio) reasons += "Audio quality is insufficient for a firm acoustic conclusion."
        when (integrity?.conclusion) {
            IntegrityConclusion.POSSIBLE_MANIPULATION -> reasons += "The acoustic model found repeated synthetic/spoof evidence."
            IntegrityConclusion.INCONCLUSIVE -> reasons += "The acoustic model result is inconclusive."
            IntegrityConclusion.LIKELY_AUTHENTIC -> reasons += "The acoustic model found a low indication of manipulation in analysed speech."
            else -> if (result.voiceIntegrity is VoiceIntegrityResult.Unavailable || result.voiceIntegrity is VoiceIntegrityResult.Failed) {
                reasons += "Voice-integrity analysis is unavailable."
            }
        }
        result.conversationRisk.score?.let { score ->
            if (score >= 70) reasons += "Confirmed transcript segments contain high-risk scam-language patterns."
            else if (score >= 35) reasons += "Confirmed transcript segments contain language worth independently verifying."
        } ?: run { reasons += "Transcript-based scam risk is unavailable." }

        val high = integrity?.conclusion == IntegrityConclusion.POSSIBLE_MANIPULATION ||
            (result.conversationRisk.score ?: -1) >= 70
        val review = poorAudio || integrity == null || integrity.conclusion == IntegrityConclusion.INCONCLUSIVE ||
            (result.conversationRisk.score ?: -1) >= 35
        return OverallGuidance(
            when { high -> GuidanceLevel.HIGH_CAUTION; review -> GuidanceLevel.REVIEW; else -> GuidanceLevel.NO_STRONG_WARNING },
            reasons.distinct(),
        )
    }
}

data class LocalHistoryEntry(
    val id: String,
    val createdAtEpochMs: Long,
    val source: String,
    val durationMs: Long,
    val audioQuality: String,
    val voiceIntegrityConclusion: String,
    val manipulationScore: Int?,
    val scamRiskScore: Int?,
    val transcriptionModel: String?,
    val integrityModel: String?,
)

/** Persists user-approved result metadata only. It never stores a URI, filename, transcript, or audio. */
class LocalHistoryStore(context: Context, preferencesName: String = "local_analysis_history") {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow(read())
    val entries = _entries.asStateFlow()

    fun retain(result: RealAnalysisResult): LocalHistoryEntry {
        val integrity = result.voiceIntegrity as? VoiceIntegrityResult.Available
        val entry = LocalHistoryEntry(
            id = UUID.randomUUID().toString(),
            createdAtEpochMs = System.currentTimeMillis(),
            source = "Uploaded recording",
            durationMs = result.metadata.durationMs,
            audioQuality = result.quality.quality.label,
            voiceIntegrityConclusion = when (val value = result.voiceIntegrity) {
                is VoiceIntegrityResult.Available -> value.conclusion.label
                is VoiceIntegrityResult.Failed -> "Analysis failed"
                is VoiceIntegrityResult.Unavailable -> "Unavailable"
            },
            manipulationScore = integrity?.score,
            scamRiskScore = result.conversationRisk.score,
            transcriptionModel = result.transcriptionModel,
            integrityModel = integrity?.model?.name,
        )
        write(listOf(entry) + _entries.value.filterNot { it.id == entry.id })
        return entry
    }

    fun delete(id: String) = write(_entries.value.filterNot { it.id == id })
    fun clear() = write(emptyList())

    private fun write(entries: List<LocalHistoryEntry>) {
        val limited = entries.take(MAX_ENTRIES)
        val array = JSONArray()
        limited.forEach { entry ->
            array.put(JSONObject()
                .put("id", entry.id).put("createdAt", entry.createdAtEpochMs).put("source", entry.source)
                .put("durationMs", entry.durationMs).put("audioQuality", entry.audioQuality)
                .put("integrityConclusion", entry.voiceIntegrityConclusion)
                .put("manipulationScore", entry.manipulationScore ?: JSONObject.NULL)
                .put("scamRiskScore", entry.scamRiskScore ?: JSONObject.NULL)
                .put("transcriptionModel", entry.transcriptionModel ?: JSONObject.NULL)
                .put("integrityModel", entry.integrityModel ?: JSONObject.NULL))
        }
        preferences.edit().putString(KEY, array.toString()).apply()
        _entries.value = limited
    }

    private fun read(): List<LocalHistoryEntry> = runCatching {
        val array = JSONArray(preferences.getString(KEY, "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            LocalHistoryEntry(
                item.getString("id"), item.getLong("createdAt"), item.optString("source", "Uploaded recording"),
                item.optLong("durationMs"), item.optString("audioQuality", "Unknown"),
                item.optString("integrityConclusion", "Unavailable"),
                item.optIntOrNull("manipulationScore"), item.optIntOrNull("scamRiskScore"),
                item.optStringOrNull("transcriptionModel"), item.optStringOrNull("integrityModel"),
            )
        }
    }.getOrDefault(emptyList())

    private fun JSONObject.optIntOrNull(key: String) = if (isNull(key) || !has(key)) null else optInt(key)
    private fun JSONObject.optStringOrNull(key: String) = if (isNull(key) || !has(key)) null else optString(key).takeIf(String::isNotBlank)

    companion object { private const val KEY = "entries_v1"; private const val MAX_ENTRIES = 50 }
}
