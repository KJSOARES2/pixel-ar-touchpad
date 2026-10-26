package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

data class ExternalArDisplayInfo(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val refreshRate: Int
)

/**
 * Write-only AccessibilityService + DisplayManager.DisplayListener + External AR Cursor Overlay.
 *
 * Supports:
 * - Real-time pass-through DeX mouse cursor overlay (`TYPE_ACCESSIBILITY_OVERLAY`) on the external AR display.
 * - Top status bar Notification & Quick Settings pull-down when clicking or pulling down near the top edge
 *   of the AR desktop (`externalCursorY <= 48f`).
 * - 1-finger double-tap-and-drag strokes (`dispatchExternalDrag`).
 * - 2-finger scroll accumulation past `touchSlop`.
 */
class TouchpadAccessibilityService : AccessibilityService(), DisplayManager.DisplayListener {

    private lateinit var displayManager: DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Single pre-allocated Path instance reused across all gesture dispatches (zero Path allocation)
    private val reusablePath = Path()
    private val isGestureInFlight = AtomicBoolean(false)

    // External AR display overlay state
    private var overlayWindowManager: WindowManager? = null
    private var overlayView: ArExternalCursorOverlayView? = null
    private var overlayDisplayId: Int = Display.INVALID_DISPLAY

    // Live cursor coordinates on the external AR display
    private var externalCursorX: Float = 960f
    private var externalCursorY: Float = 540f
    private var externalWidth: Float = 1920f
    private var externalHeight: Float = 1080f

    // Scroll accumulator so micro 240Hz deltas combine into strokes that exceed Android touchSlop
    private var pendingScrollDx: Float = 0f
    private var pendingScrollDy: Float = 0f

    private val gestureResultCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            isGestureInFlight.set(false)
            flushPendingScrollIfNeeded()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            isGestureInFlight.set(false)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true

        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(this, mainHandler)
        refreshExternalDisplays(autoLaunchIfFound = false)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Strictly write-only service: accessibilityEventTypes="" and canRetrieveWindowContent="false"
    }

    override fun onInterrupt() {
        isGestureInFlight.set(false)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        removeExternalCursorOverlay()
        if (::displayManager.isInitialized) {
            runCatching { displayManager.unregisterDisplayListener(this) }
        }
        isGestureInFlight.set(false)
        _isServiceConnected.value = false
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeExternalCursorOverlay()
        if (::displayManager.isInitialized) {
            runCatching { displayManager.unregisterDisplayListener(this) }
        }
        isGestureInFlight.set(false)
        _isServiceConnected.value = false
        instance = null
        super.onDestroy()
    }

    override fun onDisplayAdded(displayId: Int) {
        val addedArDisplay = refreshExternalDisplays(autoLaunchIfFound = true)
        if (addedArDisplay != null && addedArDisplay.displayId == displayId) {
            launchTouchpadOnDefaultDisplay(this)
        }
    }

    override fun onDisplayRemoved(displayId: Int) {
        if (displayId == overlayDisplayId) {
            removeExternalCursorOverlay()
        }
        refreshExternalDisplays(autoLaunchIfFound = false)
    }

    override fun onDisplayChanged(displayId: Int) {
        refreshExternalDisplays(autoLaunchIfFound = false)
    }

    private fun refreshExternalDisplays(autoLaunchIfFound: Boolean): ExternalArDisplayInfo? {
        if (!::displayManager.isInitialized) return null
        val displayObj = findExternalDisplayObject(displayManager)
        val found = displayObj?.let { extractDisplayInfo(this, it) }
        _activeArDisplay.value = found

        if (found != null && displayObj != null) {
            externalWidth = found.width.toFloat().coerceAtLeast(100f)
            externalHeight = found.height.toFloat().coerceAtLeast(100f)
            externalCursorX = externalCursorX.coerceIn(1f, externalWidth - 1f)
            externalCursorY = externalCursorY.coerceIn(1f, externalHeight - 1f)

            attachOrUpdateExternalCursorOverlay(displayObj)

            if (autoLaunchIfFound) {
                launchTouchpadOnDefaultDisplay(this)
            }
        } else {
            removeExternalCursorOverlay()
        }
        return found
    }

    private fun attachOrUpdateExternalCursorOverlay(targetDisplay: Display) {
        if (overlayView != null && overlayDisplayId == targetDisplay.displayId) {
            overlayView?.updateCursorPosition(externalCursorX, externalCursorY)
            return
        }
        removeExternalCursorOverlay()

        runCatching {
            val displayContext = createDisplayContext(targetDisplay)
            val wm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    val windowCtx = displayContext.createWindowContext(
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        null
                    )
                    windowCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                }.getOrElse {
                    displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                }
            } else {
                displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            }

            val view = ArExternalCursorOverlayView(displayContext).apply {
                updateCursorPosition(externalCursorX, externalCursorY)
            }

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            wm.addView(view, lp)
            overlayWindowManager = wm
            overlayView = view
            overlayDisplayId = targetDisplay.displayId
        }
    }

    private fun removeExternalCursorOverlay() {
        val wm = overlayWindowManager
        val view = overlayView
        overlayWindowManager = null
        overlayView = null
        overlayDisplayId = Display.INVALID_DISPLAY
        if (wm != null && view != null) {
            runCatching { wm.removeViewImmediate(view) }
        }
    }

    /**
     * Moves the live cursor on the external AR glasses display in real time during 240Hz 1-finger moves.
     */
    fun moveExternalCursorByDelta(dx: Float, dy: Float): Pair<Float, Float> {
        if (!dx.isFinite() || !dy.isFinite()) return externalCursorX to externalCursorY
        val viewW = overlayView?.width?.toFloat() ?: 0f
        val viewH = overlayView?.height?.toFloat() ?: 0f
        if (viewW > 100f && viewH > 100f) {
            externalWidth = viewW
            externalHeight = viewH
        }

        externalCursorX = (externalCursorX + dx * 1.45f).coerceIn(0f, externalWidth - 1f)
        externalCursorY = (externalCursorY + dy * 1.45f).coerceIn(0f, externalHeight - 1f)
        overlayView?.updateCursorPosition(externalCursorX, externalCursorY)
        return externalCursorX to externalCursorY
    }

    /**
     * Dispatches a pointer tap (Left Click) at the current external AR cursor position.
     */
    fun dispatchExternalTap() {
        val targetDisplayId = resolveTargetDisplayId()
        val x = externalCursorX.coerceIn(1f, externalWidth - 2f)
        val y = externalCursorY.coerceIn(1f, externalHeight - 2f)

        overlayView?.triggerClickPulse(rightClick = false)

        reusablePath.reset()
        reusablePath.moveTo(x, y)
        reusablePath.lineTo(x + 0.5f, y + 0.5f)

        val stroke = GestureDescription.StrokeDescription(reusablePath, 0L, 45L)
        dispatchStrokeToDisplay(stroke, targetDisplayId, forceReplace = true)
    }

    /**
     * Dispatches a Right Click (2-finger tap -> context click / long press) at the current external AR cursor position.
     */
    fun dispatchExternalRightClick() {
        val targetDisplayId = resolveTargetDisplayId()
        val x = externalCursorX.coerceIn(1f, externalWidth - 2f)
        val y = externalCursorY.coerceIn(1f, externalHeight - 2f)

        overlayView?.triggerClickPulse(rightClick = true)

        reusablePath.reset()
        reusablePath.moveTo(x, y)
        reusablePath.lineTo(x + 0.5f, y + 0.5f)

        val stroke = GestureDescription.StrokeDescription(reusablePath, 0L, 500L)
        dispatchStrokeToDisplay(stroke, targetDisplayId, forceReplace = true)
    }

    /**
     * Dispatches a 1-finger double-tap-and-drag stroke (or top-edge notification/quick-settings pull-down).
     */
    fun dispatchExternalDrag(totalDragDx: Float, totalDragDy: Float) {
        val targetDisplayId = resolveTargetDisplayId()
        val endX = externalCursorX.coerceIn(2f, externalWidth - 2f)
        val endY = externalCursorY.coerceIn(2f, externalHeight - 2f)
        val startX = (endX - totalDragDx).coerceIn(2f, externalWidth - 2f)
        val startY = (endY - totalDragDy).coerceIn(0f, externalHeight - 2f)

        // If user dragged downward starting from the top status bar area (startY <= 56f),
        // pull down the desktop Notification / Quick Settings shade
        if (startY <= 56f && totalDragDy > 40f) {
            dispatchTopBarShadePullDown(startX, targetDisplayId)
            return
        }

        if (hypot(endX - startX, endY - startY) < 8f) return

        reusablePath.reset()
        reusablePath.moveTo(startX, startY)
        reusablePath.lineTo(endX, endY)

        val stroke = GestureDescription.StrokeDescription(reusablePath, 0L, 160L)
        dispatchStrokeToDisplay(stroke, targetDisplayId, forceReplace = true)
    }

    /**
     * Pulls down the desktop Notification / Quick Settings shade when triggered at the top edge.
     */
    private fun dispatchTopBarShadePullDown(xCoord: Float, targetDisplayId: Int) {
        val x = xCoord.coerceIn(20f, externalWidth - 20f)
        reusablePath.reset()
        reusablePath.moveTo(x, 1f)
        reusablePath.lineTo(x, (externalHeight * 0.62f).coerceAtLeast(300f))

        val stroke = GestureDescription.StrokeDescription(reusablePath, 0L, 180L)
        dispatchStrokeToDisplay(stroke, targetDisplayId, forceReplace = true)
    }

    /**
     * Accumulates 240Hz two-finger scroll deltas and dispatches a swipe stroke that exceeds Android's touchSlop.
     * Also detects 2-finger downward pull when the cursor is parked on the top status bar to open
     * Notifications / Quick Settings easily.
     */
    fun dispatchExternalScroll(scrollDx: Float, scrollDy: Float, rawFingerDy: Float) {
        if (!scrollDx.isFinite() || !scrollDy.isFinite()) return
        overlayView?.notifyActivity()

        // If cursor is in the top status bar (top 48px) and fingers pull downward, pull down Notification/Quick Settings shade
        if (externalCursorY <= 48f && rawFingerDy > 6f && !isGestureInFlight.get()) {
            pendingScrollDx = 0f
            pendingScrollDy = 0f
            dispatchTopBarShadePullDown(externalCursorX, resolveTargetDisplayId())
            return
        }

        pendingScrollDx = (pendingScrollDx + scrollDx * 2.2f).coerceIn(-420f, 420f)
        pendingScrollDy = (pendingScrollDy + scrollDy * 2.2f).coerceIn(-420f, 420f)
        flushPendingScrollIfNeeded()
    }

    private fun flushPendingScrollIfNeeded() {
        if (isGestureInFlight.get()) return
        val mag = hypot(pendingScrollDx, pendingScrollDy)
        if (mag < 18f) return

        val scale = if (mag < 96f) 96f / mag else 1f
        val strokeDx = (pendingScrollDx * scale).coerceIn(-360f, 360f)
        val strokeDy = (pendingScrollDy * scale).coerceIn(-360f, 360f)
        pendingScrollDx = 0f
        pendingScrollDy = 0f

        val targetDisplayId = resolveTargetDisplayId()
        val margin = 48f
        val startX = externalCursorX.coerceIn(margin, (externalWidth - margin).coerceAtLeast(margin + 1f))
        val startY = externalCursorY.coerceIn(margin, (externalHeight - margin).coerceAtLeast(margin + 1f))
        val endX = (startX + strokeDx).coerceIn(4f, externalWidth - 4f)
        val endY = (startY + strokeDy).coerceIn(4f, externalHeight - 4f)
        if (hypot(endX - startX, endY - startY) < 12f) return

        reusablePath.reset()
        reusablePath.moveTo(startX, startY)
        reusablePath.lineTo(endX, endY)

        val stroke = GestureDescription.StrokeDescription(reusablePath, 0L, 85L)
        dispatchStrokeToDisplay(stroke, targetDisplayId, forceReplace = false)
    }

    private fun resolveTargetDisplayId(): Int {
        val active = _activeArDisplay.value
        if (active != null) return active.displayId
        val refreshed = refreshExternalDisplays(autoLaunchIfFound = false)
        return refreshed?.displayId ?: Display.DEFAULT_DISPLAY
    }

    private fun dispatchStrokeToDisplay(
        stroke: GestureDescription.StrokeDescription,
        displayId: Int,
        forceReplace: Boolean
    ) {
        if (!forceReplace && !isGestureInFlight.compareAndSet(false, true)) {
            return
        }
        isGestureInFlight.set(true)
        runCatching {
            val builder = GestureDescription.Builder().addStroke(stroke)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && displayId != Display.DEFAULT_DISPLAY) {
                builder.setDisplayId(displayId)
            }
            val dispatched = dispatchGesture(builder.build(), gestureResultCallback, mainHandler)
            if (!dispatched) {
                isGestureInFlight.set(false)
            }
        }.onFailure {
            isGestureInFlight.set(false)
        }
    }

    companion object {
        @Volatile
        var instance: TouchpadAccessibilityService? = null
            private set

        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        private val _activeArDisplay = MutableStateFlow<ExternalArDisplayInfo?>(null)
        val activeArDisplay: StateFlow<ExternalArDisplayInfo?> = _activeArDisplay.asStateFlow()

        fun syncDisplaysFromContext(context: Context): ExternalArDisplayInfo? {
            val svc = instance
            if (svc != null) {
                return svc.refreshExternalDisplays(autoLaunchIfFound = false)
            }
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val displayObj = findExternalDisplayObject(dm)
            val found = displayObj?.let { extractDisplayInfo(context, it) }
            _activeArDisplay.value = found
            return found
        }

        private fun findExternalDisplayObject(displayManager: DisplayManager): Display? {
            val allDisplays = LinkedHashMap<Int, Display>()
            displayManager.displays?.forEach { d ->
                if (d != null) allDisplays[d.displayId] = d
            }
            displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.forEach { d ->
                if (d != null) allDisplays[d.displayId] = d
            }

            var candidate: Display? = null
            for (d in allDisplays.values) {
                if (d.displayId == Display.DEFAULT_DISPLAY) continue
                val isPresentation = (d.flags and Display.FLAG_PRESENTATION) != 0
                candidate = d
                if (isPresentation) break
            }
            return candidate
        }

        @Suppress("DEPRECATION")
        private fun extractDisplayInfo(context: Context, display: Display): ExternalArDisplayInfo {
            val metrics = DisplayMetrics()
            runCatching {
                context.createDisplayContext(display).resources.displayMetrics.let { dm ->
                    metrics.setTo(dm)
                }
            }.onFailure {
                display.getRealMetrics(metrics)
            }

            val mode = display.mode
            val w = if (metrics.widthPixels > 0) metrics.widthPixels else (mode?.physicalWidth ?: 1920)
            val h = if (metrics.heightPixels > 0) metrics.heightPixels else (mode?.physicalHeight ?: 1080)
            val refresh = mode?.refreshRate?.toInt() ?: 60

            return ExternalArDisplayInfo(
                displayId = display.displayId,
                name = display.name ?: "USB-C AR Glasses",
                width = w,
                height = h,
                refreshRate = refresh
            )
        }

        fun launchTouchpadOnDefaultDisplay(context: Context) {
            runCatching {
                val intent = Intent(context, MainActivity::class.java).apply {
                    setPackage(context.packageName)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val options = ActivityOptions.makeBasic()
                        .setLaunchDisplayId(Display.DEFAULT_DISPLAY)
                    context.startActivity(intent, options.toBundle())
                } else {
                    context.startActivity(intent)
                }
            }
        }
    }
}
