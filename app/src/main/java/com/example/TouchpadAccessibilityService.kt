package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

data class ExternalArDisplayInfo(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val refreshRate: Int
)

/**
 * Write-only AccessibilityService + DisplayManager.DisplayListener.
 *
 * Security & Performance Design:
 * - Strictly write-only (`canRetrieveWindowContent="false"`, `accessibilityEventTypes=""`, `exported="false"`).
 * - Detects USB-C DisplayPort Alt-Mode AR glasses presentation displays
 *   (`Display.FLAG_PRESENTATION` or `displayId != Display.DEFAULT_DISPLAY`).
 * - Automatically launches `MainActivity` on `Display.DEFAULT_DISPLAY` (0) when AR glasses connect.
 * - Reuses a single pre-allocated `android.graphics.Path` (`reusablePath.rewind()` / `reset()`)
 *   and guards concurrent gesture dispatches via an `AtomicBoolean` gate to prevent binder queue
 *   saturation during 240Hz high-velocity gestures.
 */
class TouchpadAccessibilityService : AccessibilityService(), DisplayManager.DisplayListener {

    private lateinit var displayManager: DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Single pre-allocated Path instance reused across all gesture dispatches (zero Path allocation)
    private val reusablePath = Path()
    private val isGestureInFlight = AtomicBoolean(false)

    private val gestureResultCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            isGestureInFlight.set(false)
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            isGestureInFlight.set(false)
        }
    }

    private var externalCursorX: Float = 960f
    private var externalCursorY: Float = 540f
    private var externalWidth: Float = 1920f
    private var externalHeight: Float = 1080f

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
        if (::displayManager.isInitialized) {
            runCatching { displayManager.unregisterDisplayListener(this) }
        }
        isGestureInFlight.set(false)
        _isServiceConnected.value = false
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
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
        refreshExternalDisplays(autoLaunchIfFound = false)
    }

    override fun onDisplayChanged(displayId: Int) {
        refreshExternalDisplays(autoLaunchIfFound = false)
    }

    private fun refreshExternalDisplays(autoLaunchIfFound: Boolean): ExternalArDisplayInfo? {
        if (!::displayManager.isInitialized) return null
        val found = findExternalArDisplay(displayManager)
        _activeArDisplay.value = found
        if (found != null) {
            externalWidth = found.width.toFloat().coerceAtLeast(100f)
            externalHeight = found.height.toFloat().coerceAtLeast(100f)
            externalCursorX = externalCursorX.coerceIn(1f, externalWidth - 1f)
            externalCursorY = externalCursorY.coerceIn(1f, externalHeight - 1f)
            if (autoLaunchIfFound) {
                launchTouchpadOnDefaultDisplay(this)
            }
        }
        return found
    }

    /**
     * Dispatches a pointer tap (Left Click) on the external AR display using reusablePath.reset().
     */
    fun dispatchExternalTap(normalizedX: Float, normalizedY: Float) {
        val targetDisplay = _activeArDisplay.value ?: return
        if (!normalizedX.isFinite() || !normalizedY.isFinite()) return

        val x = (normalizedX * externalWidth).coerceIn(1f, externalWidth - 1f)
        val y = (normalizedY * externalHeight).coerceIn(1f, externalHeight - 1f)
        externalCursorX = x
        externalCursorY = y

        reusablePath.reset()
        reusablePath.moveTo(x, y)

        val stroke = GestureDescription.StrokeDescription(reusablePath, 0L, 24L)
        dispatchStrokeToDisplay(stroke, targetDisplay.displayId, forceReplace = true)
    }

    /**
     * Dispatches a Right Click (3-finger tap) on the external AR display using reusablePath.reset().
     */
    fun dispatchExternalRightClick(normalizedX: Float, normalizedY: Float) {
        val targetDisplay = _activeArDisplay.value ?: return
        if (!normalizedX.isFinite() || !normalizedY.isFinite()) return

        val x = (normalizedX * externalWidth).coerceIn(1f, externalWidth - 1f)
        val y = (normalizedY * externalHeight).coerceIn(1f, externalHeight - 1f)
        externalCursorX = x
        externalCursorY = y

        reusablePath.reset()
        reusablePath.moveTo(x, y)

        val stroke = GestureDescription.StrokeDescription(reusablePath, 0L, 420L)
        dispatchStrokeToDisplay(stroke, targetDisplay.displayId, forceReplace = true)
    }

    /**
     * Dispatches a two-finger scroll stroke at the current external AR cursor position using reusablePath.reset().
     */
    fun dispatchExternalScroll(normalizedX: Float, normalizedY: Float, scrollDx: Float, scrollDy: Float) {
        val targetDisplay = _activeArDisplay.value ?: return
        if (!normalizedX.isFinite() || !normalizedY.isFinite() || !scrollDx.isFinite() || !scrollDy.isFinite()) return
        if (isGestureInFlight.get()) return

        val startX = (normalizedX * externalWidth).coerceIn(40f, externalWidth - 40f)
        val startY = (normalizedY * externalHeight).coerceIn(40f, externalHeight - 40f)
        val endX = (startX + scrollDx).coerceIn(10f, externalWidth - 10f)
        val endY = (startY + scrollDy).coerceIn(10f, externalHeight - 10f)
        if (startX == endX && startY == endY) return

        reusablePath.reset()
        reusablePath.moveTo(startX, startY)
        reusablePath.lineTo(endX, endY)

        val stroke = GestureDescription.StrokeDescription(reusablePath, 0L, 48L)
        dispatchStrokeToDisplay(stroke, targetDisplay.displayId, forceReplace = false)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val found = findExternalArDisplay(dm)
            _activeArDisplay.value = found
            return found
        }

        @Suppress("DEPRECATION")
        fun findExternalArDisplay(displayManager: DisplayManager): ExternalArDisplayInfo? {
            val displays = displayManager.displays ?: return null
            var candidate: Display? = null
            for (i in displays.indices) {
                val d = displays[i] ?: continue
                val isPresentation = (d.flags and Display.FLAG_PRESENTATION) != 0
                if (isPresentation || d.displayId != Display.DEFAULT_DISPLAY) {
                    candidate = d
                    if (isPresentation) break
                }
            }
            val display = candidate ?: return null
            val mode = display.mode
            val width = max(mode?.physicalWidth ?: 1920, mode?.physicalHeight ?: 1080)
            val height = min(mode?.physicalWidth ?: 1920, mode?.physicalHeight ?: 1080)
            val refresh = mode?.refreshRate?.toInt() ?: 60
            return ExternalArDisplayInfo(
                displayId = display.displayId,
                name = display.name ?: "USB-C AR Glasses",
                width = width,
                height = height,
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
