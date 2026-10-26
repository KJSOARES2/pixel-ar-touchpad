package com.example

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View

/**
 * Hardware-accelerated full-screen pass-through cursor overlay rendered directly onto the
 * external USB-C AR glasses display via `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY`.
 *
 * - Clean, compact DeX pointer arrow with NO circle/halo around the cursor tip.
 * - Automatically fades out smoothly after 1.8s of inactivity (fully hidden by 2.5s) and stops
 *   scheduling frames until the next touch interaction.
 */
class ArExternalCursorOverlayView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density.coerceAtLeast(1f)

    private var cursorX: Float = 960f
    private var cursorY: Float = 540f
    private var lastClickTimeMs: Long = 0L
    private var lastActivityTimeMs: Long = SystemClock.uptimeMillis()
    private var isRightClickPulse: Boolean = false

    private val cursorPath = Path()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF08090C.toInt()
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF38BDF8.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }

    // Runnable to kick off the fade-out animation after IDLE_HOLD_MS
    private val fadeTriggerRunnable = Runnable {
        postInvalidateOnAnimation()
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun updateCursorPosition(x: Float, y: Float) {
        cursorX = x
        cursorY = y
        notifyActivity()
    }

    fun triggerClickPulse(rightClick: Boolean) {
        val now = SystemClock.uptimeMillis()
        lastClickTimeMs = now
        isRightClickPulse = rightClick
        notifyActivity(now)
    }

    fun notifyActivity(nowMs: Long = SystemClock.uptimeMillis()) {
        lastActivityTimeMs = nowMs
        removeCallbacks(fadeTriggerRunnable)
        postDelayed(fadeTriggerRunnable, IDLE_HOLD_MS)
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(fadeTriggerRunnable)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = SystemClock.uptimeMillis()
        val idleMs = (now - lastActivityTimeMs).coerceAtLeast(0L)

        // Completely invisible after IDLE_HOLD_MS + FADE_DURATION_MS
        if (idleMs >= IDLE_HOLD_MS + FADE_DURATION_MS) {
            return
        }

        val visibilityFactor = if (idleMs <= IDLE_HOLD_MS) {
            1f
        } else {
            (1f - (idleMs - IDLE_HOLD_MS).toFloat() / FADE_DURATION_MS.toFloat()).coerceIn(0f, 1f)
        }

        val cx = cursorX.coerceIn(0f, w)
        val cy = cursorY.coerceIn(0f, h)

        // Optional transient click feedback ring (only during 220ms after an actual click)
        val clickElapsed = now - lastClickTimeMs
        val isPulseAnimating = clickElapsed in 0L..220L
        if (isPulseAnimating) {
            val progress = clickElapsed.toFloat() / 220f
            val radius = (3.5f + 11f * progress) * density
            val alpha = ((1f - progress) * 190f * visibilityFactor).toInt().coerceIn(0, 255)
            pulsePaint.color = if (isRightClickPulse) 0xFF818CF8.toInt() else 0xFF38BDF8.toInt()
            pulsePaint.alpha = alpha
            canvas.drawCircle(cx, cy, radius, pulsePaint)
        }

        // Sleek DeX vector pointer arrow (no persistent circle around tip)
        val s = density * 0.72f
        cursorPath.rewind()
        cursorPath.moveTo(cx, cy)
        cursorPath.lineTo(cx, cy + 17.5f * s)
        cursorPath.lineTo(cx + 5.0f * s, cy + 13.4f * s)
        cursorPath.lineTo(cx + 8.8f * s, cy + 20.0f * s)
        cursorPath.lineTo(cx + 11.6f * s, cy + 18.4f * s)
        cursorPath.lineTo(cx + 7.7f * s, cy + 11.6f * s)
        cursorPath.lineTo(cx + 13.4f * s, cy + 11.6f * s)
        cursorPath.close()

        fillPaint.alpha = (235f * visibilityFactor).toInt().coerceIn(0, 255)
        strokePaint.alpha = (255f * visibilityFactor).toInt().coerceIn(0, 255)

        canvas.drawPath(cursorPath, fillPaint)
        canvas.drawPath(cursorPath, strokePaint)

        // Keep animating while click pulse is active or while in the fade-out window
        if (isPulseAnimating || idleMs in IDLE_HOLD_MS..(IDLE_HOLD_MS + FADE_DURATION_MS)) {
            postInvalidateOnAnimation()
        }
    }

    companion object {
        private const val IDLE_HOLD_MS = 1800L
        private const val FADE_DURATION_MS = 700L
    }
}
