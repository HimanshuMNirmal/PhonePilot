package com.phonepilot.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity 
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Floating assistant bubble overlay that floats over any application.
 * Features:
 * - Touch & Drag with smooth edge-docking snapping
 * - 4 Visual states: IDLE, LISTENING, THINKING, SPEAKING
 * - High-FPS pulse & ripple animations
 * - Expandable transcript / response banner
 */
class FloatingBubbleManager(private val context: Context) {

    companion object {
        private const val TAG = "PhonePilot-Bubble"
    }

    enum class BubbleState {
        PAUSED_STANDBY,     // Mic is OFF, bubble is idle/docked (dim amber/cyan)
        ALWAYS_LISTENING,   // Mic is ON continuously listening for "Hey Pilot"
        ONE_SHOT_LISTENING, // Mic is ON for 1 command triggered by single-tap
        WOKE,               // Wake word detected
        THINKING,
        SPEAKING
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootContainer: FrameLayout? = null
    private var bubbleOrbView: BubbleOrbView? = null
    private var transcriptPill: TextView? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null

    private var isAdded = false
    private var currentState = BubbleState.PAUSED_STANDBY

    var onSingleTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    // Screen dimensions
    private val displayMetrics = context.resources.displayMetrics
    private val screenWidth = displayMetrics.widthPixels
    private val screenHeight = displayMetrics.heightPixels

    // Density helpers
    private fun dp(dp: Float): Int = (dp * displayMetrics.density).toInt()

    fun show() {
        if (isAdded) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show floating bubble: SYSTEM_ALERT_WINDOW permission not granted")
            return
        }

        mainHandler.post {
            try {
                createViewHierarchy()
                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = screenWidth - dp(68f)
                    y = screenHeight / 3
                }
                windowLayoutParams = params

                windowManager.addView(rootContainer, params)
                isAdded = true
                setState(BubbleState.PAUSED_STANDBY)
                Log.i(TAG, "Floating bubble overlay displayed at x=${params.x}, y=${params.y}")
            } catch (e: Exception) {
                Log.e(TAG, "Error displaying floating bubble overlay: ${e.message}", e)
            }
        }
    }

    fun hide() {
        if (!isAdded) return
        mainHandler.post {
            try {
                rootContainer?.let {
                    bubbleOrbView?.stopPulse()
                    windowManager.removeView(it)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error removing floating bubble overlay: ${e.message}")
            } finally {
                isAdded = false
                rootContainer = null
            }
        }
    }

    fun setState(state: BubbleState, text: String? = null) {
        currentState = state
        mainHandler.post {
            bubbleOrbView?.updateState(state)
            updateTranscriptPill(state, text)
        }
    }

    /**
     * Updates the floating pill with live speech-to-text transcript as words are spoken.
     */
    fun updateTranscript(text: String) {
        mainHandler.post {
            val pill = transcriptPill ?: return@post
            pill.visibility = View.VISIBLE
            pill.text = text
        }
    }

    private fun updateTranscriptPill(state: BubbleState, text: String?) {
        val pill = transcriptPill ?: return
        when (state) {
            BubbleState.PAUSED_STANDBY -> {
                if (text != null) {
                    pill.visibility = View.VISIBLE
                    pill.text = text
                    pill.setTextColor(Color.parseColor("#FBBF24"))
                    pill.background = createPillBackground(Color.parseColor("#E6292524"), Color.parseColor("#F59E0B"))
                    // Auto-hide pill after 3.5s in standby
                    mainHandler.postDelayed({
                        if (currentState == BubbleState.PAUSED_STANDBY) pill.visibility = View.GONE
                    }, 3500)
                } else {
                    pill.visibility = View.GONE
                }
            }
            BubbleState.ALWAYS_LISTENING -> {
                pill.visibility = View.VISIBLE
                pill.text = text ?: "🎙 Always Listening ('Hey Pilot')"
                pill.setTextColor(Color.parseColor("#38BDF8"))
                pill.background = createPillBackground(Color.parseColor("#E6082F49"), Color.parseColor("#0284C7"))
                // Fade pill after 3.5s to keep screen clean
                mainHandler.postDelayed({
                    if (currentState == BubbleState.ALWAYS_LISTENING) pill.visibility = View.GONE
                }, 3500)
            }
            BubbleState.ONE_SHOT_LISTENING -> {
                pill.visibility = View.VISIBLE
                pill.text = text ?: "🎙 Listening for command..."
                pill.setTextColor(Color.parseColor("#00E5FF"))
                pill.background = createPillBackground(Color.parseColor("#E60A1929"), Color.parseColor("#00E5FF"))
            }
            BubbleState.WOKE -> {
                pill.visibility = View.VISIBLE
                pill.text = text ?: "★ Woke: 'Hey Pilot'!"
                pill.setTextColor(Color.parseColor("#38BDF8"))
                pill.background = createPillBackground(Color.parseColor("#E60C4A6E"), Color.parseColor("#0284C7"))
            }
            BubbleState.THINKING -> {
                pill.visibility = View.VISIBLE
                pill.text = text ?: "🧠 Thinking..."
                pill.setTextColor(Color.parseColor("#B388FF"))
                pill.background = createPillBackground(Color.parseColor("#E61E1435"), Color.parseColor("#7C4DFF"))
            }
            BubbleState.SPEAKING -> {
                pill.visibility = View.VISIBLE
                pill.text = text ?: "🔊 Speaking..."
                pill.setTextColor(Color.parseColor("#00E676"))
                pill.background = createPillBackground(Color.parseColor("#E6082415"), Color.parseColor("#00E676"))
            }
        }
    }

    private fun createPillBackground(bgColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14f).toFloat()
            setColor(bgColor)
            setStroke(dp(1.5f), strokeColor)
        }
    }

    private fun createViewHierarchy() {
        val root = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }

        val rowLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        val orb = BubbleOrbView(context).apply {
            val size = dp(60f)
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        bubbleOrbView = orb

        val pill = TextView(context).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            visibility = View.GONE
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8f)
                marginEnd = dp(8f)
            }
            layoutParams = params
        }
        transcriptPill = pill

        rowLayout.addView(orb)
        rowLayout.addView(pill)
        root.addView(rowLayout)

        setupTouchListener(root)
        rootContainer = root
    }

    private fun setupTouchListener(view: View) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false
            private var isLongPressTriggered = false

            private val longPressRunnable = Runnable {
                isLongPressTriggered = true
                bubbleOrbView?.setTouchHighlight(false)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                        vib?.vibrate(android.os.VibrationEffect.createOneShot(70, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                } catch (_: Exception) {}
                onLongPress?.invoke()
            }

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                val params = windowLayoutParams ?: return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        isLongPressTriggered = false

                        bubbleOrbView?.setTouchHighlight(true)
                        mainHandler.postDelayed(longPressRunnable, 600L)
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()

                        if (Math.hypot(dx.toDouble(), dy.toDouble()) > dp(8f)) {
                            mainHandler.removeCallbacks(longPressRunnable)
                            isDragging = true
                        }

                        if (isDragging) {
                            params.x = initialX + dx
                            params.y = initialY + dy
                            try {
                                windowManager.updateViewLayout(rootContainer, params)
                            } catch (_: Exception) {}
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        mainHandler.removeCallbacks(longPressRunnable)
                        bubbleOrbView?.setTouchHighlight(false)

                        if (isLongPressTriggered) {
                            // Already handled by long press
                            return true
                        }

                        if (isDragging) {
                            snapToNearestEdge(params)
                        } else {
                            // Single Tap!
                            v?.performClick()
                            onSingleTap?.invoke()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun snapToNearestEdge(params: WindowManager.LayoutParams) {
        val currentX = params.x
        val targetX = if (currentX + dp(30f) < screenWidth / 2) dp(8f) else screenWidth - dp(68f)

        val animator = ValueAnimator.ofInt(currentX, targetX).apply {
            duration = 220
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { va ->
                params.x = va.animatedValue as Int
                try {
                    windowManager.updateViewLayout(rootContainer, params)
                } catch (_: Exception) {}
            }
        }
        animator.start()
    }

    /**
     * Custom rendered Assistant Orb View with high-precision glowing canvas rendering.
     */
    private inner class BubbleOrbView(ctx: Context) : View(ctx) {

        private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val innerCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        private var pulseFraction = 0f
        private var pulseAnimator: ValueAnimator? = null
        private var isTouchPressed = false

        init {
            startIdlePulse()
        }

        fun setTouchHighlight(pressed: Boolean) {
            isTouchPressed = pressed
            invalidate()
        }

        fun updateState(state: BubbleState) {
            when (state) {
                BubbleState.PAUSED_STANDBY -> startIdlePulse(durationMs = 3000)
                BubbleState.ALWAYS_LISTENING -> startIdlePulse(durationMs = 1800)
                BubbleState.ONE_SHOT_LISTENING -> startPulse(durationMs = 600)
                BubbleState.WOKE -> startPulse(durationMs = 400)
                BubbleState.THINKING -> startPulse(durationMs = 900)
                BubbleState.SPEAKING -> startPulse(durationMs = 450)
            }
            invalidate()
        }

        private fun startIdlePulse(durationMs: Long = 2400) {
            pulseAnimator?.cancel()
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    pulseFraction = it.animatedValue as Float
                    invalidate()
                }
            }
            pulseAnimator?.start()
        }

        private fun startPulse(durationMs: Long) {
            pulseAnimator?.cancel()
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    pulseFraction = it.animatedValue as Float
                    invalidate()
                }
            }
            pulseAnimator?.start()
        }

        fun stopPulse() {
            pulseAnimator?.cancel()
            pulseAnimator = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val baseRadius = width / 2f - dp(6f)

            val (coreColor, glowColor, iconText) = when (currentState) {
                BubbleState.PAUSED_STANDBY -> Triple(
                    Color.parseColor("#1C1917"),
                    Color.parseColor("#D97706"),
                    "💤"
                )
                BubbleState.ALWAYS_LISTENING -> Triple(
                    Color.parseColor("#082F49"),
                    Color.parseColor("#0284C7"),
                    "✈"
                )
                BubbleState.ONE_SHOT_LISTENING -> Triple(
                    Color.parseColor("#0A385C"),
                    Color.parseColor("#00E5FF"),
                    "🎙"
                )
                BubbleState.WOKE -> Triple(
                    Color.parseColor("#0C4A6E"),
                    Color.parseColor("#38BDF8"),
                    "★"
                )
                BubbleState.THINKING -> Triple(
                    Color.parseColor("#2E1065"),
                    Color.parseColor("#B388FF"),
                    "✦"
                )
                BubbleState.SPEAKING -> Triple(
                    Color.parseColor("#064E3B"),
                    Color.parseColor("#00E676"),
                    "🔊"
                )
            }

            // 1. Outer Pulse Aura Ring
            val auraExtra = dp(4f) * pulseFraction
            val auraAlpha = when (currentState) {
                BubbleState.PAUSED_STANDBY -> (30 + 30 * pulseFraction).toInt()
                BubbleState.ALWAYS_LISTENING -> (80 + 60 * pulseFraction).toInt()
                else -> (140 + 100 * pulseFraction).toInt()
            }
            outerGlowPaint.color = glowColor
            outerGlowPaint.alpha = auraAlpha
            outerGlowPaint.strokeWidth = dp(2f) + dp(1.5f) * pulseFraction
            canvas.drawCircle(cx, cy, baseRadius + auraExtra, outerGlowPaint)

            // 2. Main Spherical Orb Body
            bodyPaint.color = coreColor
            canvas.drawCircle(cx, cy, baseRadius, bodyPaint)

            // 3. Inner Glowing Core
            innerCorePaint.color = glowColor
            innerCorePaint.alpha = if (isTouchPressed) 220 else (80 + 60 * pulseFraction).toInt()
            canvas.drawCircle(cx, cy, baseRadius * 0.72f, innerCorePaint)

            // 4. Center Glyph
            iconPaint.textSize = dp(18f).toFloat()
            val textOffset = (iconPaint.descent() + iconPaint.ascent()) / 2
            canvas.drawText(iconText, cx, cy - textOffset, iconPaint)
        }
    }
}
