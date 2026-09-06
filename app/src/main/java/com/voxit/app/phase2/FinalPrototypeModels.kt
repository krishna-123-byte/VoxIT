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
    NO_STRONG_WARNING("NO STRONG WARNING SIGNS"),
    REVIEW("REVIEW THIS RECORDING"),
    HIGH_CAUTION("HIGH CAUTION RECOMMENDED"),
    INCOMPLETE("ANALYSIS INCOMPLETE"),
}

data class OverallGuidance(val level: GuidanceLevel, val reasons: List<String>)

enum class VoiceResultState(val label: String, val description: String) {
    LIKELY_MANIPULATED(
        "LIKELY AI-GENERATED OR MANIPULATED",
        "The recording contains acoustic patterns that may be associated with synthetic or manipulated speech.",
    ),
    LIKELY_HUMAN(
        "LIKELY HUMAN VOICE",
        "No strong AI-generated speech indicators were detected in the analysed sections.",
    ),
    INCONCLUSIVE(
        "INCONCLUSIVE",
        "The recording quality or available evidence was insufficient for a reliable result.",
    ),
    NOT_PERFORMED(
        "AI VOICE CHECK NOT PERFORMED",
        "Install the AASIST-L voice-integrity model to check this recording for AI-generated or manipulated speech.",
    ),
    FAILED(
        "ANALYSIS FAILED",
        "The acoustic model could not complete this check. No AI-voice conclusion was produced.",
    ),
}

enum class FraudResultState(val label: String, val description: String) {
    HIGH_RISK(
        "HIGH SCAM RISK",
        "Confirmed transcript context contains strong scam-language indicators. This does not prove identity or criminal intent.",
    ),
    SUSPICIOUS(
        "SUSPICIOUS LANGUAGE DETECTED",
        "Confirmed transcript context contains language worth independently verifying. This is not confirmed fraud.",
    ),
    NO_STRONG_LANGUAGE(
        "NO STRONG SCAM LANGUAGE DETECTED",
        "No strong scam-language pattern was found in the available transcript. This is not a guarantee of safety.",
    ),
    INCONCLUSIVE(
        "FRAUD CHECK INCONCLUSIVE",
        "A transcript was available, but the conversation-risk check could not produce a reliable result.",
    ),
    NOT_PERFORMED(
        "FRAUD CHECK NOT PERFORMED",
        "Fraud-language analysis was not performed because no transcript was available.",
    ),
}

data class ResultPresentation(
    val voice: VoiceResultState,
    val fraud: FraudResultState,
    val overall: OverallGuidance,
)

/** Maps real detector outputs to cautious user-facing states. It never changes detector scores. */
object ResultPresentationPolicy {
    fun evaluate(result: RealAnalysisResult): ResultPresentation {
        val voice = voiceState(result.voiceIntegrity)
        val fraud = fraudState(result)
        return ResultPresentation(voice, fraud, overall(result, voice, fraud))
    }

    fun voiceState(result: VoiceIntegrityResult): VoiceResultState = when (result) {
        is VoiceIntegrityResult.Available -> when (result.conclusion) {
            IntegrityConclusion.LIKELY_AUTHENTIC -> VoiceResultState.LIKELY_HUMAN
            IntegrityConclusion.POSSIBLE_MANIPULATION -> VoiceResultState.LIKELY_MANIPULATED
            IntegrityConclusion.INCONCLUSIVE -> VoiceResultState.INCONCLUSIVE
            IntegrityConclusion.UNAVAILABLE -> VoiceResultState.NOT_PERFORMED
            IntegrityConclusion.FAILED -> VoiceResultState.FAILED
        }
        is VoiceIntegrityResult.Failed -> VoiceResultState.FAILED
        is VoiceIntegrityResult.Unavailable -> if (result.reason.containsEvidenceLimitation()) {
            VoiceResultState.INCONCLUSIVE
        } else {
            VoiceResultState.NOT_PERFORMED
        }
    }

    fun fraudState(result: RealAnalysisResult): FraudResultState {
        if (result.transcript.isEmpty()) return FraudResultState.NOT_PERFORMED
        val score = result.conversationRisk.score ?: return FraudResultState.INCONCLUSIVE
        return when {
            score >= 70 -> FraudResultState.HIGH_RISK
            score >= 35 -> FraudResultState.SUSPICIOUS
            else -> FraudResultState.NO_STRONG_LANGUAGE
        }
    }

    private fun overall(
        result: RealAnalysisResult,
        voice: VoiceResultState,
        fraud: FraudResultState,
    ): OverallGuidance {
        val reasons = mutableListOf<String>()
        if (voice == VoiceResultState.LIKELY_MANIPULATED) reasons += "The acoustic model detected possible AI-generated or manipulated voice patterns."
        when (fraud) {
            FraudResultState.HIGH_RISK -> reasons += "High-risk scam language was detected in confirmed transcript context."
            FraudResultState.SUSPICIOUS -> reasons += "Suspicious language was detected in confirmed transcript context."
            else -> Unit
        }
        result.conversationRisk.warnings.map { it.category }.distinct().take(3).forEach { reasons += "$it was flagged by the transcript check." }

        val high = voice == VoiceResultState.LIKELY_MANIPULATED || fraud == FraudResultState.HIGH_RISK
        if (high) return OverallGuidance(GuidanceLevel.HIGH_CAUTION, reasons.distinct())

        val hardQualityLimit = result.quality.quality in setOf(RealAudioQuality.INSUFFICIENT_SPEECH, RealAudioQuality.UNSUPPORTED)
        val incomplete = voice in setOf(VoiceResultState.NOT_PERFORMED, VoiceResultState.FAILED) ||
            fraud == FraudResultState.NOT_PERFORMED || hardQualityLimit
        if (incomplete) {
            if (voice == VoiceResultState.NOT_PERFORMED) reasons += "AI voice-integrity analysis was not performed."
            if (voice == VoiceResultState.FAILED) reasons += "AI voice-integrity analysis failed without producing a conclusion."
            if (fraud == FraudResultState.NOT_PERFORMED) reasons += "Fraud-language analysis was not performed because no transcript was available."
            if (hardQualityLimit) reasons += "The recording did not contain enough supported, usable speech."
            return OverallGuidance(GuidanceLevel.INCOMPLETE, reasons.distinct())
        }

        val limitedQuality = result.quality.quality in setOf(RealAudioQuality.NOISY, RealAudioQuality.CLIPPED, RealAudioQuality.TOO_QUIET)
        val review = voice == VoiceResultState.INCONCLUSIVE || fraud in setOf(FraudResultState.SUSPICIOUS, FraudResultState.INCONCLUSIVE) || limitedQuality
        if (review) {
            if (voice == VoiceResultState.INCONCLUSIVE) reasons += "The acoustic result is inconclusive."
            if (fraud == FraudResultState.INCONCLUSIVE) reasons += "The fraud-language result is inconclusive."
            if (limitedQuality) reasons += "Audio-quality limitations reduce confidence in this recording."
            return OverallGuidance(GuidanceLevel.REVIEW, reasons.distinct())
        }

        reasons += "Both available checks completed without a strong warning signal."
        reasons += "This does not guarantee that the speaker or request is safe."
        return OverallGuidance(GuidanceLevel.NO_STRONG_WARNING, reasons.distinct())
    }

    private fun String.containsEvidenceLimitation(): Boolean = listOf(
        "insufficient", "quality", "speech window", "valid speech", "too short", "quiet", "clipped", "noisy",
    ).any { contains(it, ignoreCase = true) }
}

/** Combines explanations, never detector values. It is deliberately not a fraud probability. */
object OverallGuidancePolicy {
    fun evaluate(result: RealAnalysisResult): OverallGuidance {
        return ResultPresentationPolicy.evaluate(result).overall
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
