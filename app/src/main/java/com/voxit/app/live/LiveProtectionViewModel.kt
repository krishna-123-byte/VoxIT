package com.voxit.app.live

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel

class LiveProtectionViewModel(private val context: Context) : ViewModel() {
    val state = LiveProtectionStore.state

    fun markPermissionRequired(message: String) {
        LiveProtectionStore.update { it.copy(status = LiveSessionStatus.PERMISSION_REQUIRED, errorMessage = message) }
    }

    fun start(options: LiveStartOptions) {
        val current = state.value.status
        if (!LiveStartPolicy.canStart(current)) return
        LiveProtectionStore.update { LiveProtectionState(sessionId = it.sessionId + 1, status = LiveSessionStatus.STARTING_SERVICE, transcriptionStatus = "Preparing model status…") }
        ContextCompat.startForegroundService(context, serviceIntent(LiveProtectionService.ACTION_START).apply {
            putExtra(LiveProtectionService.EXTRA_BUBBLE, options.bubbleRequested)
            putExtra(LiveProtectionService.EXTRA_ALERT_NOTIFICATIONS, options.alertNotificationsEnabled)
            putExtra(LiveProtectionService.EXTRA_VIBRATION, options.vibrationEnabled)
            putExtra(LiveProtectionService.EXTRA_THRESHOLD, options.alertThreshold)
            putExtra(LiveProtectionService.EXTRA_MODEL_ID, options.selectedModelId)
        })
    }

    fun pause() = command(LiveProtectionService.ACTION_PAUSE)
    fun resume() = command(LiveProtectionService.ACTION_RESUME)
    fun continueMonitoring() = command(LiveProtectionService.ACTION_CONTINUE)
    fun stop() = command(LiveProtectionService.ACTION_STOP)
    fun showBubble() = command(LiveProtectionService.ACTION_SHOW_BUBBLE)
    fun hideBubble() = command(LiveProtectionService.ACTION_HIDE_BUBBLE)

    fun clearTranscript() {
        LiveProtectionStore.update { it.copy(partialTranscript = "", confirmedTranscript = emptyList(), conversationRisk = com.voxit.app.phase2.ConversationRiskResult(null, emptyList(), "Transcript cleared. Risk is unavailable until new confirmed speech is transcribed.")) }
    }

    private fun command(action: String) {
        if (state.value.status in setOf(LiveSessionStatus.IDLE, LiveSessionStatus.STOPPED) && action != LiveProtectionService.ACTION_STOP) return
        context.startService(serviceIntent(action))
    }

    private fun serviceIntent(action: String) = Intent(context, LiveProtectionService::class.java).setAction(action)
}

object LiveStartPolicy {
    private val restartable = setOf(
        LiveSessionStatus.IDLE,
        LiveSessionStatus.STOPPED,
        LiveSessionStatus.PERMISSION_REQUIRED,
        LiveSessionStatus.ERROR,
        LiveSessionStatus.AUDIO_UNAVAILABLE,
    )
    fun canStart(status: LiveSessionStatus): Boolean = status in restartable
}
