package com.voxit.app.live

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.voxit.app.MainActivity
import com.voxit.app.R
import kotlin.math.abs

class FloatingBubbleController(
    private val context: Context,
    private val onStop: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var icon: FrameLayout? = null
    private var statusMark: TextView? = null
    private var controls: LinearLayout? = null
    private var parameters: WindowManager.LayoutParams? = null

    fun show(): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        if (root != null) return true
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
            background = rounded(Color.rgb(10, 25, 48), 24f)
        }
        val badge = AccessibleBubbleFrameLayout(context).apply {
            contentDescription = "VoxIT Live Protection bubble"
            background = rounded(Color.rgb(232, 242, 255), 80f, Color.rgb(39, 170, 210))
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        }
        val emblem = ImageView(context).apply {
            setImageResource(R.drawable.voxit_emblem)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "VoxIT waveform emblem"
        }
        badge.addView(emblem, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))
        val indicator = TextView(context).apply {
            text = "●"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = rounded(Color.rgb(39, 170, 210), 40f)
        }
        badge.addView(indicator, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.END or Gravity.BOTTOM))
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(action("Open") { openApp() })
            addView(action("Stop") { onStop() })
            addView(action("Hide") { hide(); LiveProtectionStore.update { it.copy(bubbleStatus = BubbleStatus.HIDDEN) } })
        }
        panel.addView(badge); panel.addView(actions)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 280 }
        badge.setOnClickListener {
            actions.visibility = if (actions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        badge.setOnTouchListener(DragTouchListener(panel, params))
        return try {
            windowManager.addView(panel, params)
            root = panel; icon = badge; statusMark = indicator; controls = actions; parameters = params
            true
        } catch (_: Exception) { root = null; false }
    }

    fun update(status: BubbleStatus) {
        if (!Settings.canDrawOverlays(context)) { hide(); return }
        val accent = when (status) { BubbleStatus.WARNING, BubbleStatus.ERROR -> Color.rgb(210, 74, 64); BubbleStatus.PAUSED -> Color.rgb(221, 145, 45); else -> Color.rgb(39, 170, 210) }
        icon?.apply {
            contentDescription = "VoxIT Live Protection bubble: ${status.name.lowercase().replace('_', ' ')}"
            background = rounded(Color.rgb(232, 242, 255), 80f, accent)
        }
        statusMark?.apply {
            text = when (status) { BubbleStatus.PAUSED -> "Ⅱ"; BubbleStatus.WARNING -> "!"; BubbleStatus.ERROR -> "×"; else -> "●" }
            background = rounded(accent, 40f)
        }
    }

    fun hide() {
        root?.let { try { windowManager.removeViewImmediate(it) } catch (_: Exception) { } }
        root = null; icon = null; statusMark = null; controls = null; parameters = null
    }

    fun isShowing(): Boolean = root != null

    private fun openApp() {
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            action = LiveProtectionService.ACTION_OPEN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
    }

    private fun action(label: String, block: () -> Unit) = TextView(context).apply {
        text = label; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(18, 16, 18, 16); setOnClickListener { block() }
    }

    private fun rounded(color: Int, radius: Float, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; strokeColor?.let { setStroke(dp(2), it) }
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private inner class DragTouchListener(
        private val view: View,
        private val params: WindowManager.LayoutParams,
    ) : View.OnTouchListener {
        private var initialX = 0; private var initialY = 0; private var downX = 0f; private var downY = 0f
        override fun onTouch(ignored: View?, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { initialX = params.x; initialY = params.y; downX = event.rawX; downY = event.rawY; return true }
                MotionEvent.ACTION_MOVE -> {
                    val metrics = context.resources.displayMetrics
                    params.x = (initialX + (event.rawX - downX).toInt()).coerceIn(0, (metrics.widthPixels - view.width).coerceAtLeast(0))
                    params.y = (initialY + (event.rawY - downY).toInt()).coerceIn(0, (metrics.heightPixels - view.height).coerceAtLeast(0))
                    try { windowManager.updateViewLayout(view, params) } catch (_: Exception) { hide() }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - downX) < 12 && abs(event.rawY - downY) < 12) {
                        ignored?.performClick()
                    }
                    return true
                }
            }
            return false
        }
    }
}

internal class AccessibleBubbleFrameLayout(context: Context) : FrameLayout(context) {
    override fun performClick(): Boolean = super.performClick()
}
