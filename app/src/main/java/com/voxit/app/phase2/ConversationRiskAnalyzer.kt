package com.voxit.app.phase2

import com.voxit.app.domain.ConversationRiskEngine
import com.voxit.app.domain.TranscriptSegment
import java.util.Locale

object SensitiveDataRedactor {
    private val password = Regex("(?i)\\b(password|passcode)\\s*(?:is|:|=)?\\s*([A-Za-z0-9@#_$%!-]{4,})")
    private val otpPin = Regex("(?i)\\b(otp|pin|cvv|verification code)\\s*(?:is|:|=)?\\s*(\\d{3,8})\\b")
    private val card = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")
    private val account = Regex("(?i)\\b(account(?: number| no\\.?)?)\\s*(?:is|:|=)?\\s*(\\d{6,18})\\b")
    private val phone = Regex("(?<!\\d)(?:\\+?91[ -]?)?[6-9]\\d{9}(?!\\d)")

    fun redact(text: String): String = text
        .replace(password) { "${it.groupValues[1]} [REDACTED]" }
        .replace(otpPin) { "${it.groupValues[1]} [REDACTED]" }
        .replace(account) { "${it.groupValues[1]} [REDACTED]" }
        .replace(card, "[REDACTED CARD NUMBER]")
        .replace(phone, "[REDACTED PHONE NUMBER]")
}

class OfflineConversationRiskEngine : ConversationRiskEngine {
    private data class Rule(val category: String, val weight: Int, val explanation: String, val pattern: Regex)

    private val advice = Regex("(?i)(\\b(never share|do not share|don't share|must not share|will never ask|should not tell|avoid sharing|kisi ko mat (batao|dena))\\b|कभी साझा न करें|किसी को मत (बताओ|देना)|(ओटीपी|पिन|पासवर्ड).{0,20}मत.{0,12}(बताओ|देना|भेजो)|बैंक.{0,20}कभी.{0,20}नहीं)")
    private val rules = listOf(
        Rule("Sensitive credential request", 42, "The speaker appears to request a security credential.", Regex("(?i)(share|tell|give|send|provide|read out|batao|bhejo|bol do|de do|बताओ|भेजो|दे दो).{0,48}(\\b(otp|pin|password|cvv|verification code)\\b|ओटीपी|पिन|पासवर्ड)|((\\b(otp|pin|password|cvv)\\b|ओटीपी|पिन|पासवर्ड).{0,32}(share|tell|give|send|batao|bhejo|de do|बताओ|भेजो|दे दो))")),
        Rule("Banking or card details", 34, "Bank, account, or card information appears to be requested.", Regex("(?i)(share|tell|give|send|provide|confirm).{0,55}\\b(card number|account number|bank details|expiry date|credit card|debit card)\\b")),
        Rule("Urgent money transfer", 32, "A transfer or payment is requested with urgency or pressure.", Regex("(?i)(\\b(transfer|send|pay|bhejo)\\b|भेजो|भुगतान).{0,45}(\\b(money|funds|rupees|payment|upi)\\b|पैसे|रुपये).{0,45}(\\b(now|immediately|urgent|today|abhi|jaldi)\\b|अभी|तुरंत|जल्दी)|(\\b(now|immediately|urgent|abhi|jaldi)\\b|अभी|तुरंत|जल्दी).{0,45}(\\b(transfer|payment|upi|money)\\b|पैसे|भुगतान)")),
        Rule("Threat or coercion", 30, "A threat involving closure, arrest, a fine, or disconnection creates pressure.", Regex("(?i)\\b(account (?:will be )?(?:closed|blocked|locked)|you (?:will|may|could) be arrested|police (?:will|may) arrest|pay (?:a )?(?:fine|penalty)|connection (?:will be )?(?:disconnected|cut))\\b|खाता.{0,24}(बंद|ब्लॉक)|गिरफ्तार.{0,24}(करेंगे|हो जाएंगे)|जुर्माना.{0,24}(भरना|देना)|कनेक्शन.{0,24}(काट|बंद)")),
        Rule("Remote-access request", 40, "Installing remote-control software can expose the device and accounts.", Regex("(?i)\\b(install|download|open).{0,35}\\b(anydesk|teamviewer|quicksupport|remote access|screen.?share)\\b")),
        Rule("Impersonation", 20, "The speaker claims authority or a trusted identity; verify independently.", Regex("(?i)\\b(this is|calling from|speaking from|main .* se bol raha).{0,35}\\b(bank|police|government|income tax|rbi|company|your son|your daughter|relative)\\b|(मैं|हम).{0,30}(बैंक|पुलिस|सरकार|आयकर|कंपनी).{0,20}(से|बोल रहा|बोल रही)")),
        Rule("Secrecy request", 24, "The speaker asks that the conversation or action be kept secret.", Regex("(?i)\\b(don't tell|do not tell|keep this secret|between us|kisi ko mat batana)\\b|किसी को मत बताना")),
        Rule("Blocks verification", 34, "The speaker discourages independent verification or ending the call.", Regex("(?i)\\b(don't hang up|do not hang up|stay on the line|don't call the bank|do not verify|phone mat rakhna|call mat karna)\\b|फोन मत (काटना|रखना)|बैंक को (फोन|कॉल) मत करना")),
        Rule("Repeated urgency", 18, "Repeated urgency language may be intended to prevent careful verification.", Regex("(?i)\\b(urgent|immediately|right now|act now|last chance|abhi|jaldi|turant)\\b|अभी|तुरंत|जल्दी")),
    )

    fun analyse(segments: List<TranscriptSegment>): ConversationRiskResult {
        if (segments.isEmpty()) return ConversationRiskResult(null, emptyList(), "No real transcript was available for conversation-risk analysis.")
        val warnings = mutableListOf<TranscriptWarning>()
        var total = 0
        var urgencyHits = 0
        segments.sortedBy { it.startMs }.forEach { segment ->
            val normalized = segment.text.lowercase(Locale.ROOT)
            if (advice.containsMatchIn(normalized)) return@forEach
            rules.forEach { rule ->
                if (rule.pattern.containsMatchIn(normalized)) {
                    if (rule.category == "Repeated urgency") urgencyHits++
                    if (rule.category != "Repeated urgency" || urgencyHits >= 2) {
                        total += rule.weight
                        warnings += TranscriptWarning(
                            category = rule.category,
                            timestamp = segment.timestamp,
                            evidence = SensitiveDataRedactor.redact(segment.text).take(180),
                            explanation = rule.explanation,
                            confidence = when { rule.weight >= 40 -> 88; rule.weight >= 30 -> 78; else -> 65 },
                        )
                    }
                }
            }
        }
        val contextBonus = if (warnings.map { it.category }.distinct().size >= 2) 12 else 0
        val score = (total + contextBonus).coerceIn(0, 100)
        return ConversationRiskResult(
            score = score,
            warnings = warnings.distinctBy { it.category to it.timestamp },
            explanation = if (warnings.isEmpty()) "No contextual scam pattern was found in the available transcript. This is not a guarantee of safety."
            else "Experimental conversation-risk warning based on ${warnings.size} explainable transcript pattern(s); it is not confirmed fraud.",
        )
    }
}
