package com.example

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Hardware-accelerated Samsung DeX-inspired touchpad surface (`DeXCrosshairTrackpadView`).
 *
 * Performance & Rendering Architecture:
 * - Uses hardware layer acceleration (`LAYER_TYPE_HARDWARE`) and pre-cached static `Path` geometry
 *   (`centerHairlinePath`, `outerBorderPath`, `cornerBracketsPath`, `reticlePath`) rebuilt only
 *   in `onSizeChanged()`.
 * - Refactored 128-sample ring buffer (320ms decay window) renders high-velocity 240Hz trajectories
 *   using pre-allocated, hardware-accelerated `android.graphics.Path` objects (`rewind()` +
 *   quadratic Bézier midpoint smoothing `quadTo`) bucketed across 4 decay alpha bands—eliminating
 *   hundreds of per-segment `drawLine` JNI calls and ensuring C1-continuous curves at 240Hz.
 * - Tracks active pointers by stable pointer ID (`findPointerIndex`) with zero heap allocations
 *   on the `onTouchEvent` / `onDraw` hot paths.
 */
class DeXCrosshairTrackpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface GestureCallback {
        fun onPointerMove(dx: Float, dy: Float, rawVelocityPxPerMs: Float)
        fun onLeftClick()
        fun onRightClick()
        fun onTwoFingerScroll(scrollDx: Float, scrollDy: Float)
    }

    var gestureCallback: GestureCallback? = null

    // Configurable calibration parameters (defaults per DeX spec)
    var pointerSensitivity: Float = 1.4f
    var scrollSpeed: Float = 1.2f
    var naturalScrolling: Boolean = true
    var tapToClick: Boolean = true

    private val density = resources.displayMetrics.density
    private val oneDp = 1f * density
    private val twoDp = 2f * density
    private val cornerBracketLength = 24f * density
    private val reticleOuterRadius = 18f * density
    private val reticleInnerRadius = 3.5f * density
    private val reticleTickLength = 7f * density

    // Tap distance thresholds in pixels
    private val oneFingerTapSlopPx = 10f * density
    private val threeFingerTapSlopPx = 18f * density

    // Pre-allocated Paints (zero allocation in onDraw)
    private val surfacePaint = Paint().apply {
        color = COLOR_SURFACE_BLACK
        style = Paint.Style.FILL
    }

    private val outerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_OUTER_BORDER
        style = Paint.Style.STROKE
        strokeWidth = oneDp
    }

    private val centerHairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_CENTER_HAIRLINE
        style = Paint.Style.STROKE
        strokeWidth = oneDp
    }

    private val cornerBracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_SKY_CYAN
        style = Paint.Style.STROKE
        strokeWidth = twoDp
        strokeCap = Paint.Cap.SQUARE
    }

    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_SKY_CYAN
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        alpha = 115
    }

    private val reticleCenterDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_SKY_CYAN
        style = Paint.Style.FILL
        alpha = 180
    }

    private val cyanTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_SKY_CYAN
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val indigoTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_ELECTRIC_INDIGO
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val tetherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_ELECTRIC_INDIGO
        style = Paint.Style.STROKE
        strokeWidth = oneDp
        alpha = 90
    }

    private val haloFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val haloStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    // Pre-allocated hardware-accelerated static HUD Paths (rebuilt only on size change)
    private val centerHairlinePath = Path()
    private val outerBorderPath = Path()
    private val cornerBracketsPath = Path()
    private val reticlePath = Path()

    // Pre-allocated hardware-accelerated Trail Paths across 4 decay bands (reused via rewind())
    private val cyanDecayPaths = Array(DECAY_BANDS) { Path() }
    private val indigoDecayPaths = Array(DECAY_BANDS) { Path() }
    private val cyanBandHasGeometry = BooleanArray(DECAY_BANDS)
    private val indigoBandHasGeometry = BooleanArray(DECAY_BANDS)
    private val multiTouchTetherPath = Path()

    // Pre-computed alpha and stroke widths for each decay band (0 = oldest, DECAY_BANDS-1 = newest)
    private val bandAlpha = IntArray(DECAY_BANDS) { band ->
        val fraction = (band + 1).toFloat() / DECAY_BANDS.toFloat()
        (fraction * 225f).toInt().coerceIn(28, 235)
    }
    private val bandStrokeWidth = FloatArray(DECAY_BANDS) { band ->
        val fraction = (band + 1).toFloat() / DECAY_BANDS.toFloat()
        (1.4f + 3.1f * fraction) * density
    }

    // Zero-allocation 128-sample ring buffer for decaying trails (320ms decay window)
    private val ringX0 = FloatArray(RING_CAPACITY)
    private val ringY0 = FloatArray(RING_CAPACITY)
    private val ringX1 = FloatArray(RING_CAPACITY)
    private val ringY1 = FloatArray(RING_CAPACITY)
    private val ringX2 = FloatArray(RING_CAPACITY)
    private val ringY2 = FloatArray(RING_CAPACITY)
    private val ringTimeMs = LongArray(RING_CAPACITY)
    private val ringPointerCount = IntArray(RING_CAPACITY)
    private var ringWriteIndex = 0
    private var ringCount = 0
    private var newestSampleTimeMs = 0L

    // Active touch contact state & stable pointer IDs
    private var activePointers = 0
    private var pointerId0 = MotionEvent.INVALID_POINTER_ID
    private var pointerId1 = MotionEvent.INVALID_POINTER_ID
    private var pointerId2 = MotionEvent.INVALID_POINTER_ID

    private var liveX0 = 0f
    private var liveY0 = 0f
    private var liveX1 = 0f
    private var liveY1 = 0f
    private var liveX2 = 0f
    private var liveY2 = 0f

    // Gesture tracking state (primitive fields only -> zero heap allocations)
    private var gestureStartTimeMs = 0L
    private var maxPointersInGesture = 0
    private var downX0 = 0f
    private var downY0 = 0f
    private var downX1 = 0f
    private var downY1 = 0f
    private var downX2 = 0f
    private var downY2 = 0f
    private var maxMoveDistance0 = 0f
    private var maxMoveDistance1 = 0f
    private var maxMoveDistance2 = 0f

    private var prevX0 = 0f
    private var prevY0 = 0f
    private var prevX1 = 0f
    private var prevY1 = 0f
    private var prevX2 = 0f
    private var prevY2 = 0f
    private var prevSampleTimeMs = 0L

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = true
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wf = w.toFloat()
        val hf = h.toFloat()
        if (wf <= 0f || hf <= 0f) return

        val cx = wf * 0.5f
        val cy = hf * 0.5f

        // 1. Center Hairlines Path (#0F172A)
        centerHairlinePath.rewind()
        centerHairlinePath.moveTo(0f, cy)
        centerHairlinePath.lineTo(wf, cy)
        centerHairlinePath.moveTo(cx, 0f)
        centerHairlinePath.lineTo(cx, hf)

        // 2. Outer Square Border Path (1dp #1E293B)
        val halfBorder = oneDp * 0.5f
        outerBorderPath.rewind()
        outerBorderPath.addRect(
            halfBorder,
            halfBorder,
            wf - halfBorder,
            hf - halfBorder,
            Path.Direction.CW
        )

        // 3. Precision 2dp Sky-Cyan (#38BDF8) Corner L-Brackets Path
        val inset = twoDp * 0.5f
        val len = cornerBracketLength
        cornerBracketsPath.rewind()
        // Top-Left
        cornerBracketsPath.moveTo(inset, len)
        cornerBracketsPath.lineTo(inset, inset)
        cornerBracketsPath.lineTo(len, inset)
        // Top-Right
        cornerBracketsPath.moveTo(wf - len, inset)
        cornerBracketsPath.lineTo(wf - inset, inset)
        cornerBracketsPath.lineTo(wf - inset, len)
        // Bottom-Left
        cornerBracketsPath.moveTo(inset, hf - len)
        cornerBracketsPath.lineTo(inset, hf - inset)
        cornerBracketsPath.lineTo(len, hf - inset)
        // Bottom-Right
        cornerBracketsPath.moveTo(wf - len, hf - inset)
        cornerBracketsPath.lineTo(wf - inset, hf - inset)
        cornerBracketsPath.lineTo(wf - inset, hf - len)

        // 4. Center Crosshair Reticle Ring & Ticks Path (#38BDF8)
        reticlePath.rewind()
        reticlePath.addCircle(cx, cy, reticleOuterRadius, Path.Direction.CW)
        // Left tick
        reticlePath.moveTo(cx - reticleOuterRadius - reticleTickLength, cy)
        reticlePath.lineTo(cx - reticleOuterRadius + reticleTickLength * 0.5f, cy)
        // Right tick
        reticlePath.moveTo(cx + reticleOuterRadius - reticleTickLength * 0.5f, cy)
        reticlePath.lineTo(cx + reticleOuterRadius + reticleTickLength, cy)
        // Top tick
        reticlePath.moveTo(cx, cy - reticleOuterRadius - reticleTickLength)
        reticlePath.lineTo(cx, cy - reticleOuterRadius + reticleTickLength * 0.5f)
        // Bottom tick
        reticlePath.moveTo(cx, cy + reticleOuterRadius - reticleTickLength * 0.5f)
        reticlePath.lineTo(cx, cy + reticleOuterRadius + reticleTickLength)
    }

    internal fun recordRingSample(
        pointerCount: Int,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        timestampMs: Long
    ) {
        if (!x0.isFinite() || !y0.isFinite()) return
        val idx = ringWriteIndex
        ringPointerCount[idx] = pointerCount
        ringX0[idx] = x0
        ringY0[idx] = y0
        ringX1[idx] = if (x1.isFinite()) x1 else 0f
        ringY1[idx] = if (y1.isFinite()) y1 else 0f
        ringX2[idx] = if (x2.isFinite()) x2 else 0f
        ringY2[idx] = if (y2.isFinite()) y2 else 0f
        ringTimeMs[idx] = timestampMs

        ringWriteIndex = (idx + 1) and RING_MASK
        if (ringCount < RING_CAPACITY) {
            ringCount++
        }
        newestSampleTimeMs = timestampMs
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerCount = event.pointerCount.coerceAtMost(3)
        val eventTime = event.eventTime

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                gestureStartTimeMs = event.downTime
                maxPointersInGesture = 1

                pointerId0 = event.getPointerId(0)
                pointerId1 = MotionEvent.INVALID_POINTER_ID
                pointerId2 = MotionEvent.INVALID_POINTER_ID

                val x0 = event.getX(0)
                val y0 = event.getY(0)
                downX0 = x0
                downY0 = y0
                prevX0 = x0
                prevY0 = y0
                maxMoveDistance0 = 0f
                maxMoveDistance1 = 0f
                maxMoveDistance2 = 0f
                prevSampleTimeMs = eventTime

                activePointers = 1
                liveX0 = x0
                liveY0 = y0
                recordRingSample(1, x0, y0, 0f, 0f, 0f, 0f, eventTime)
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount > maxPointersInGesture) {
                    maxPointersInGesture = pointerCount
                }
                syncActivePointerIds(event)

                val idx0 = event.findPointerIndex(pointerId0)
                val x0 = if (idx0 >= 0) event.getX(idx0) else event.getX(0)
                val y0 = if (idx0 >= 0) event.getY(idx0) else event.getY(0)
                prevX0 = x0
                prevY0 = y0
                liveX0 = x0
                liveY0 = y0

                if (pointerCount >= 2) {
                    val idx1 = event.findPointerIndex(pointerId1)
                    val x1 = if (idx1 >= 0) event.getX(idx1) else event.getX(1)
                    val y1 = if (idx1 >= 0) event.getY(idx1) else event.getY(1)
                    if (maxMoveDistance1 == 0f) {
                        downX1 = x1
                        downY1 = y1
                    }
                    prevX1 = x1
                    prevY1 = y1
                    liveX1 = x1
                    liveY1 = y1
                }
                if (pointerCount >= 3) {
                    val idx2 = event.findPointerIndex(pointerId2)
                    val x2 = if (idx2 >= 0) event.getX(idx2) else event.getX(2)
                    val y2 = if (idx2 >= 0) event.getY(idx2) else event.getY(2)
                    if (maxMoveDistance2 == 0f) {
                        downX2 = x2
                        downY2 = y2
                    }
                    prevX2 = x2
                    prevY2 = y2
                    liveX2 = x2
                    liveY2 = y2
                }
                activePointers = pointerCount
                prevSampleTimeMs = eventTime
                recordRingSample(pointerCount, liveX0, liveY0, liveX1, liveY1, liveX2, liveY2, eventTime)
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val idx0 = event.findPointerIndex(pointerId0).let { if (it >= 0) it else 0 }
                val idx1 = if (pointerCount >= 2) {
                    event.findPointerIndex(pointerId1).let { if (it >= 0) it else 1 }
                } else -1
                val idx2 = if (pointerCount >= 3) {
                    event.findPointerIndex(pointerId2).let { if (it >= 0) it else 2 }
                } else -1

                val historySize = event.historySize
                // Process historical batched samples at up to 240Hz with zero heap allocations
                for (h in 0 until historySize) {
                    val hTime = event.getHistoricalEventTime(h)
                    val hx0 = event.getHistoricalX(idx0, h)
                    val hy0 = event.getHistoricalY(idx0, h)
                    val hx1 = if (idx1 >= 0) event.getHistoricalX(idx1, h) else 0f
                    val hy1 = if (idx1 >= 0) event.getHistoricalY(idx1, h) else 0f
                    val hx2 = if (idx2 >= 0) event.getHistoricalX(idx2, h) else 0f
                    val hy2 = if (idx2 >= 0) event.getHistoricalY(idx2, h) else 0f

                    processMoveSample(pointerCount, hx0, hy0, hx1, hy1, hx2, hy2, hTime)
                }

                // Process current sample
                val cx0 = event.getX(idx0)
                val cy0 = event.getY(idx0)
                val cx1 = if (idx1 >= 0) event.getX(idx1) else 0f
                val cy1 = if (idx1 >= 0) event.getY(idx1) else 0f
                val cx2 = if (idx2 >= 0) event.getX(idx2) else 0f
                val cy2 = if (idx2 >= 0) event.getY(idx2) else 0f

                processMoveSample(pointerCount, cx0, cy0, cx1, cy1, cx2, cy2, eventTime)
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val upIndex = event.actionIndex
                val remaining = (event.pointerCount - 1).coerceIn(0, 3)
                activePointers = remaining

                pointerId0 = MotionEvent.INVALID_POINTER_ID
                pointerId1 = MotionEvent.INVALID_POINTER_ID
                pointerId2 = MotionEvent.INVALID_POINTER_ID

                var slot = 0
                for (i in 0 until event.pointerCount) {
                    if (i == upIndex) continue
                    val pid = event.getPointerId(i)
                    val px = event.getX(i)
                    val py = event.getY(i)
                    if (slot == 0) {
                        pointerId0 = pid
                        prevX0 = px
                        prevY0 = py
                        liveX0 = px
                        liveY0 = py
                    } else if (slot == 1) {
                        pointerId1 = pid
                        prevX1 = px
                        prevY1 = py
                        liveX1 = px
                        liveY1 = py
                    } else if (slot == 2) {
                        pointerId2 = pid
                        prevX2 = px
                        prevY2 = py
                        liveX2 = px
                        liveY2 = py
                    }
                    slot++
                }
                prevSampleTimeMs = eventTime
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_UP -> {
                activePointers = 0
                pointerId0 = MotionEvent.INVALID_POINTER_ID
                pointerId1 = MotionEvent.INVALID_POINTER_ID
                pointerId2 = MotionEvent.INVALID_POINTER_ID

                val durationMs = eventTime - gestureStartTimeMs
                if (tapToClick) {
                    if (maxPointersInGesture == 1 &&
                        durationMs < ONE_FINGER_TAP_TIMEOUT_MS &&
                        maxMoveDistance0 < oneFingerTapSlopPx
                    ) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        performClick()
                        gestureCallback?.onLeftClick()
                    } else if (maxPointersInGesture == 3 &&
                        durationMs < THREE_FINGER_TAP_TIMEOUT_MS &&
                        maxMoveDistance0 < threeFingerTapSlopPx &&
                        maxMoveDistance1 < threeFingerTapSlopPx &&
                        maxMoveDistance2 < threeFingerTapSlopPx
                    ) {
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        gestureCallback?.onRightClick()
                    }
                }
                postInvalidateOnAnimation()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                activePointers = 0
                pointerId0 = MotionEvent.INVALID_POINTER_ID
                pointerId1 = MotionEvent.INVALID_POINTER_ID
                pointerId2 = MotionEvent.INVALID_POINTER_ID
                postInvalidateOnAnimation()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun syncActivePointerIds(event: MotionEvent) {
        val count = event.pointerCount
        if (count >= 1 && (pointerId0 == MotionEvent.INVALID_POINTER_ID || event.findPointerIndex(pointerId0) < 0)) {
            pointerId0 = event.getPointerId(0)
        }
        if (count >= 2 && (pointerId1 == MotionEvent.INVALID_POINTER_ID || event.findPointerIndex(pointerId1) < 0)) {
            for (i in 0 until count) {
                val pid = event.getPointerId(i)
                if (pid != pointerId0) {
                    pointerId1 = pid
                    break
                }
            }
        }
        if (count >= 3 && (pointerId2 == MotionEvent.INVALID_POINTER_ID || event.findPointerIndex(pointerId2) < 0)) {
            for (i in 0 until count) {
                val pid = event.getPointerId(i)
                if (pid != pointerId0 && pid != pointerId1) {
                    pointerId2 = pid
                    break
                }
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun processMoveSample(
        pointerCount: Int,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        sampleTimeMs: Long
    ) {
        if (!x0.isFinite() || !y0.isFinite()) return

        val dtMs = (sampleTimeMs - prevSampleTimeMs).coerceIn(1L, 50L).toFloat()
        prevSampleTimeMs = sampleTimeMs

        liveX0 = x0
        liveY0 = y0
        liveX1 = x1
        liveY1 = y1
        liveX2 = x2
        liveY2 = y2
        activePointers = pointerCount

        // Track maximum displacement from initial touch down for tap classification
        val dist0 = hypot(x0 - downX0, y0 - downY0)
        if (dist0 > maxMoveDistance0) maxMoveDistance0 = dist0

        if (pointerCount == 1 && maxPointersInGesture == 1) {
            val rawDx = x0 - prevX0
            val rawDy = y0 - prevY0
            val stepDist = hypot(rawDx, rawDy)
            if (stepDist > 0.05f && stepDist.isFinite()) {
                val velocityPxPerMs = stepDist / dtMs
                // Samsung DeX-inspired dynamic velocity-adaptive pointer acceleration curve
                val velocityBoost = (0.85f + min(velocityPxPerMs * 0.55f, 2.35f))
                val gain = pointerSensitivity * velocityBoost
                gestureCallback?.onPointerMove(rawDx * gain, rawDy * gain, velocityPxPerMs)
            }
        } else if (pointerCount == 2 && maxPointersInGesture == 2) {
            val dist1 = hypot(x1 - downX1, y1 - downY1)
            if (dist1 > maxMoveDistance1) maxMoveDistance1 = dist1

            val avgDx = ((x0 - prevX0) + (x1 - prevX1)) * 0.5f
            val avgDy = ((y0 - prevY0) + (y1 - prevY1)) * 0.5f
            if ((abs(avgDx) > 0.1f || abs(avgDy) > 0.1f) && avgDx.isFinite() && avgDy.isFinite()) {
                val directionSign = if (naturalScrolling) 1f else -1f
                val scaledDx = avgDx * scrollSpeed * directionSign
                val scaledDy = avgDy * scrollSpeed * directionSign
                gestureCallback?.onTwoFingerScroll(scaledDx, scaledDy)
            }
        } else if (pointerCount >= 3) {
            val dist1 = hypot(x1 - downX1, y1 - downY1)
            if (dist1 > maxMoveDistance1) maxMoveDistance1 = dist1
            val dist2 = hypot(x2 - downX2, y2 - downY2)
            if (dist2 > maxMoveDistance2) maxMoveDistance2 = dist2
        }

        prevX0 = x0
        prevY0 = y0
        prevX1 = x1
        prevY1 = y1
        prevX2 = x2
        prevY2 = y2

        recordRingSample(pointerCount, x0, y0, x1, y1, x2, y2, sampleTimeMs)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Deep AMOLED Slate-Black Surface (#08090C)
        canvas.drawRect(0f, 0f, w, h, surfacePaint)

        // 2. Hardware-Accelerated Cached Static HUD Paths (Single drawPath per layer)
        canvas.drawPath(centerHairlinePath, centerHairlinePaint)
        canvas.drawPath(outerBorderPath, outerBorderPaint)
        canvas.drawPath(cornerBracketsPath, cornerBracketPaint)
        canvas.drawPath(reticlePath, reticlePaint)
        canvas.drawCircle(w * 0.5f, h * 0.5f, reticleInnerRadius, reticleCenterDotPaint)

        // 3. Hardware-Accelerated Path Ring Buffer Rendering (128 samples, 320ms decay window)
        val nowMs = SystemClock.uptimeMillis()
        val hasActiveDecay = buildAndDrawHardwareTrailPaths(canvas, nowMs)

        // 4. Multi-finger & active pointer contact halos + hardware Path tether
        if (activePointers >= 1) {
            drawContactHalo(canvas, liveX0, liveY0, COLOR_SKY_CYAN, isMultiTouch = activePointers >= 2)
            if (activePointers >= 2) {
                drawContactHalo(canvas, liveX1, liveY1, COLOR_ELECTRIC_INDIGO, isMultiTouch = true)
                multiTouchTetherPath.rewind()
                multiTouchTetherPath.moveTo(liveX0, liveY0)
                multiTouchTetherPath.lineTo(liveX1, liveY1)
                if (activePointers >= 3) {
                    drawContactHalo(canvas, liveX2, liveY2, COLOR_SKY_CYAN, isMultiTouch = true)
                    multiTouchTetherPath.lineTo(liveX2, liveY2)
                }
                canvas.drawPath(multiTouchTetherPath, tetherPaint)
            }
        }

        // Schedule next 240Hz/120Hz animation frame ONLY while trails are actively decaying
        val newestAgeMs = (nowMs - newestSampleTimeMs).coerceAtLeast(0L)
        if (hasActiveDecay || activePointers > 0 || (ringCount > 0 && newestAgeMs < DECAY_WINDOW_MS)) {
            postInvalidateOnAnimation()
        } else if (ringCount > 0) {
            // All samples have completely decayed — reset ring count so idle frames are O(1)
            ringCount = 0
            ringWriteIndex = 0
        }
    }

    /**
     * Builds smooth quadratic Bézier `Path` curves across `DECAY_BANDS` opacity bands from the
     * 128-sample ring buffer using `Path.rewind()` (retaining native Skia vertex buffers without
     * heap or native allocations) and renders them via hardware-accelerated `canvas.drawPath()`.
     */
    internal fun buildAndDrawHardwareTrailPaths(canvas: Canvas, nowMs: Long): Boolean {
        if (ringCount <= 1) return false

        for (b in 0 until DECAY_BANDS) {
            cyanDecayPaths[b].rewind()
            indigoDecayPaths[b].rewind()
            cyanBandHasGeometry[b] = false
            indigoBandHasGeometry[b] = false
        }

        var hasActiveDecay = false
        val oldestIndex = (ringWriteIndex - ringCount + RING_CAPACITY) and RING_MASK
        var prevIdx = oldestIndex
        var prevMidX0 = ringX0[oldestIndex]
        var prevMidY0 = ringY0[oldestIndex]
        var prevMidX1 = ringX1[oldestIndex]
        var prevMidY1 = ringY1[oldestIndex]
        var prevMidX2 = ringX2[oldestIndex]
        var prevMidY2 = ringY2[oldestIndex]

        for (step in 1 until ringCount) {
            val currIdx = (oldestIndex + step) and RING_MASK
            // Clamp negative delta caused by 1-2ms input resampling lead so newest sample never drops
            val ageMs = (nowMs - ringTimeMs[currIdx]).coerceAtLeast(0L)

            val pCount = ringPointerCount[currIdx]
            val prevPCount = ringPointerCount[prevIdx]
            val dtSegment = ringTimeMs[currIdx] - ringTimeMs[prevIdx]

            val x0 = ringX0[currIdx]
            val y0 = ringY0[currIdx]
            val px0 = ringX0[prevIdx]
            val py0 = ringY0[prevIdx]
            val midX0 = (px0 + x0) * 0.5f
            val midY0 = (py0 + y0) * 0.5f

            val x1 = ringX1[currIdx]
            val y1 = ringY1[currIdx]
            val px1 = ringX1[prevIdx]
            val py1 = ringY1[prevIdx]
            val midX1 = (px1 + x1) * 0.5f
            val midY1 = (py1 + y1) * 0.5f

            val x2 = ringX2[currIdx]
            val y2 = ringY2[currIdx]
            val px2 = ringX2[prevIdx]
            val py2 = ringY2[prevIdx]
            val midX2 = (px2 + x2) * 0.5f
            val midY2 = (py2 + y2) * 0.5f

            if (ageMs < DECAY_WINDOW_MS && pCount == prevPCount && dtSegment in 0L..80L) {
                hasActiveDecay = true
                val lifeFraction = 1f - (ageMs.toFloat() / DECAY_WINDOW_MS.toFloat())
                val band = ((lifeFraction * DECAY_BANDS).toInt()).coerceIn(0, DECAY_BANDS - 1)

                // Primary finger quadratic Bézier curve (Sky-Cyan #38BDF8)
                val cPath = cyanDecayPaths[band]
                cPath.moveTo(prevMidX0, prevMidY0)
                cPath.quadTo(px0, py0, midX0, midY0)
                cyanBandHasGeometry[band] = true

                // Multi-touch finger 2 & 3 quadratic Bézier curves (Electric Indigo #818CF8)
                if (pCount >= 2) {
                    val iPath = indigoDecayPaths[band]
                    iPath.moveTo(prevMidX1, prevMidY1)
                    iPath.quadTo(px1, py1, midX1, midY1)
                    if (pCount >= 3) {
                        iPath.moveTo(prevMidX2, prevMidY2)
                        iPath.quadTo(px2, py2, midX2, midY2)
                    }
                    indigoBandHasGeometry[band] = true
                }
            }

            prevMidX0 = midX0
            prevMidY0 = midY0
            prevMidX1 = midX1
            prevMidY1 = midY1
            prevMidX2 = midX2
            prevMidY2 = midY2
            prevIdx = currIdx
        }

        if (hasActiveDecay) {
            for (b in 0 until DECAY_BANDS) {
                if (cyanBandHasGeometry[b]) {
                    cyanTrailPaint.alpha = bandAlpha[b]
                    cyanTrailPaint.strokeWidth = bandStrokeWidth[b]
                    canvas.drawPath(cyanDecayPaths[b], cyanTrailPaint)
                }
                if (indigoBandHasGeometry[b]) {
                    indigoTrailPaint.alpha = bandAlpha[b]
                    indigoTrailPaint.strokeWidth = bandStrokeWidth[b]
                    canvas.drawPath(indigoDecayPaths[b], indigoTrailPaint)
                }
            }
        }

        return hasActiveDecay
    }

    private fun drawContactHalo(canvas: Canvas, x: Float, y: Float, color: Int, isMultiTouch: Boolean) {
        val outerR = if (isMultiTouch) 22f * density else 14f * density
        val innerR = if (isMultiTouch) 6f * density else 4f * density

        haloFillPaint.color = color
        haloFillPaint.alpha = if (isMultiTouch) 45 else 32
        canvas.drawCircle(x, y, outerR, haloFillPaint)

        haloStrokePaint.color = color
        haloStrokePaint.alpha = if (isMultiTouch) 220 else 170
        canvas.drawCircle(x, y, outerR, haloStrokePaint)

        haloFillPaint.alpha = 240
        canvas.drawCircle(x, y, innerR, haloFillPaint)
    }

    companion object {
        private const val RING_CAPACITY = 128
        private const val RING_MASK = RING_CAPACITY - 1
        private const val DECAY_WINDOW_MS = 320L
        private const val DECAY_BANDS = 4

        private const val ONE_FINGER_TAP_TIMEOUT_MS = 180L
        private const val THREE_FINGER_TAP_TIMEOUT_MS = 240L

        private const val COLOR_SURFACE_BLACK = 0xFF08090C.toInt()
        private const val COLOR_CENTER_HAIRLINE = 0xFF0F172A.toInt()
        private const val COLOR_OUTER_BORDER = 0xFF1E293B.toInt()
        private const val COLOR_SKY_CYAN = 0xFF38BDF8.toInt()
        private const val COLOR_ELECTRIC_INDIGO = 0xFF818CF8.toInt()
    }
}
