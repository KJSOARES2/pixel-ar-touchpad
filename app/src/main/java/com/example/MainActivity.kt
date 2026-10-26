package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CardSurfaceSlate
import com.example.ui.theme.ElevatedHudSurface
import com.example.ui.theme.EmeraldReady
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.SkyCyan
import com.example.ui.theme.TextIceWhite
import com.example.ui.theme.TrackpadBlack
import com.example.ui.theme.TrackpadBorderSlate

class MainActivity : ComponentActivity(), DisplayManager.DisplayListener {

    private val viewModel: TouchpadViewModel by viewModels()
    private lateinit var displayManager: DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep Pixel awake so USB-C AR glasses desktop and video playback never suspend
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(this, mainHandler)
        TouchpadAccessibilityService.syncDisplaysFromContext(this)

        setContent {
            MyApplicationTheme {
                PixelArTouchpadScreen(viewModel = viewModel)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Allow waking the phone display from OLED Blackout Mode with Volume keys or Back
        if (viewModel.uiState.value.isBlackoutMode &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            viewModel.setBlackoutMode(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        TouchpadAccessibilityService.syncDisplaysFromContext(this)
    }

    override fun onDestroy() {
        if (::displayManager.isInitialized) {
            runCatching { displayManager.unregisterDisplayListener(this) }
        }
        super.onDestroy()
    }

    override fun onDisplayAdded(displayId: Int) {
        TouchpadAccessibilityService.syncDisplaysFromContext(this)
    }

    override fun onDisplayRemoved(displayId: Int) {
        TouchpadAccessibilityService.syncDisplaysFromContext(this)
    }

    override fun onDisplayChanged(displayId: Int) {
        TouchpadAccessibilityService.syncDisplaysFromContext(this)
    }
}

@Composable
fun PixelArTouchpadScreen(
    viewModel: TouchpadViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isA11yConnected by TouchpadAccessibilityService.isServiceConnected.collectAsStateWithLifecycle()
    val externalDisplay by TouchpadAccessibilityService.activeArDisplay.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current

    // Synchronize hardware display brightness and immersive system bars with OLED Blackout Mode
    // so the phone screen turns completely off (#000000 + 0% backlight) without putting the
    // external AR glasses display or video playback to sleep!
    LaunchedEffect(uiState.isBlackoutMode) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        val insetsController = WindowCompat.getInsetsController(window, view)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val lp = window.attributes
        if (uiState.isBlackoutMode) {
            lp.screenBrightness = 0.0f
            window.attributes = lp
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Back gesture exits OLED Blackout Mode and restores phone screen brightness
    BackHandler(enabled = uiState.isBlackoutMode) {
        viewModel.setBlackoutMode(false)
    }

    val gestureCallback = remember(viewModel) {
        object : DeXCrosshairTrackpadView.GestureCallback {
            override fun onPointerMove(dx: Float, dy: Float, rawVelocityPxPerMs: Float) {
                viewModel.onPointerMove(dx, dy)
            }

            override fun onLeftClick() {
                viewModel.onLeftClick()
            }

            override fun onRightClick() {
                viewModel.onRightClick()
            }

            override fun onTwoFingerScroll(scrollDx: Float, scrollDy: Float, rawFingerDy: Float) {
                viewModel.onTwoFingerScroll(scrollDx, scrollDy, rawFingerDy)
            }

            override fun onOneFingerDragEnd(totalDragDx: Float, totalDragDy: Float) {
                viewModel.onOneFingerDragEnd(totalDragDx, totalDragDy)
            }
        }
    }

    val bgColor = if (uiState.isBlackoutMode) Color.Black else TrackpadBlack

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
        containerColor = bgColor,
        contentWindowInsets = if (uiState.isBlackoutMode) WindowInsets(0.dp) else WindowInsets.safeDrawing
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets(0.dp))
                .background(bgColor)
        ) {
            // 1. Minimalist Top Bar (hidden in OLED Blackout Mode so 100% of phone OLED pixels turn off)
            if (!uiState.isBlackoutMode) {
                TouchpadTopBar(
                    isA11yConnected = isA11yConnected,
                    externalDisplay = externalDisplay,
                    onOpenDisclosure = { viewModel.setDisclosureDialogOpen(true) },
                    onEnterBlackoutMode = { viewModel.setBlackoutMode(true) },
                    onOpenCalibration = { viewModel.setCalibrationSheetOpen(true) }
                )
            }

            // 2. Full-Screen DeX-Inspired Crosshair Trackpad Surface (Compose AndroidView)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(if (uiState.isBlackoutMode) 0.dp else 12.dp)
            ) {
                AndroidView(
                    factory = { ctx ->
                        DeXCrosshairTrackpadView(ctx).apply {
                            this.gestureCallback = gestureCallback
                            this.pointerSensitivity = uiState.calibration.sensitivity
                            this.scrollSpeed = uiState.calibration.scrollSpeed
                            this.naturalScrolling = uiState.calibration.naturalScrolling
                            this.tapToClick = uiState.calibration.tapToClick
                            this.isBlackoutMode = uiState.isBlackoutMode
                        }
                    },
                    update = { trackpadView ->
                        trackpadView.gestureCallback = gestureCallback
                        trackpadView.pointerSensitivity = uiState.calibration.sensitivity
                        trackpadView.scrollSpeed = uiState.calibration.scrollSpeed
                        trackpadView.naturalScrolling = uiState.calibration.naturalScrolling
                        trackpadView.tapToClick = uiState.calibration.tapToClick
                        trackpadView.isBlackoutMode = uiState.isBlackoutMode
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("dex_crosshair_trackpad_view")
                )
            }
        }

        // Calibration Modal Bottom Sheet
        if (uiState.isCalibrationSheetOpen && !uiState.isBlackoutMode) {
            CalibrationBottomSheet(
                calibration = uiState.calibration,
                isA11yConnected = isA11yConnected,
                onSensitivityChange = viewModel::updateSensitivity,
                onScrollSpeedChange = viewModel::updateScrollSpeed,
                onNaturalScrollingChange = viewModel::updateNaturalScrolling,
                onTapToClickChange = viewModel::updateTapToClick,
                onOpenDisclosure = {
                    viewModel.setCalibrationSheetOpen(false)
                    viewModel.setDisclosureDialogOpen(true)
                },
                onResetDefaults = viewModel::resetCalibrationDefaults,
                onDismiss = { viewModel.setCalibrationSheetOpen(false) }
            )
        }

        // Google Play Policy Prominent Disclosure Dialog for AccessibilityService
        if (uiState.isDisclosureDialogOpen && !isA11yConnected && !uiState.isBlackoutMode) {
            AccessibilityDisclosureDialog(
                onAcceptAndOpenSettings = {
                    viewModel.setDisclosureDialogOpen(false)
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }
                },
                onDecline = {
                    viewModel.setDisclosureDialogOpen(false)
                }
            )
        }
    }
}

@Composable
private fun TouchpadTopBar(
    isA11yConnected: Boolean,
    externalDisplay: ExternalArDisplayInfo?,
    onOpenDisclosure: () -> Unit,
    onEnterBlackoutMode: () -> Unit,
    onOpenCalibration: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ElevatedHudSurface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CardSurfaceSlate)
                    .border(1.dp, SkyCyan.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.DesktopWindows,
                    contentDescription = null,
                    tint = SkyCyan,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = TextIceWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Compact connection badge in top bar
        val badgeColor = when {
            !isA11yConnected -> AmberWarning
            externalDisplay != null -> EmeraldReady
            else -> SkyCyan
        }
        val badgeText = when {
            !isA11yConnected -> "ENABLE A11Y"
            externalDisplay != null -> "DISPLAY #${externalDisplay.displayId}"
            else -> "MIRROR / NO EXT"
        }

        Row(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(8.dp))
                .background(CardSurfaceSlate)
                .border(1.dp, badgeColor.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                .clickable(enabled = !isA11yConnected, onClick = onOpenDisclosure)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag("top_bar_status_pill"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(badgeColor)
            )
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // OLED Screen Off / Blackout Button (turns off phone OLED pixels while keeping AR glasses & video active)
        IconButton(
            onClick = onEnterBlackoutMode,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CardSurfaceSlate)
                .border(1.dp, TrackpadBorderSlate, RoundedCornerShape(8.dp))
                .testTag("oled_blackout_button")
        ) {
            Icon(
                imageVector = Icons.Filled.Bedtime,
                contentDescription = "Turn off phone OLED screen while keeping AR glasses active",
                tint = SkyCyan
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Calibration Bottom Sheet Trigger (48dp touch target)
        IconButton(
            onClick = onOpenCalibration,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CardSurfaceSlate)
                .border(1.dp, TrackpadBorderSlate, RoundedCornerShape(8.dp))
                .testTag("open_calibration_button")
        ) {
            Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = stringResource(R.string.calibrate_button_desc),
                tint = SkyCyan
            )
        }
    }
}
