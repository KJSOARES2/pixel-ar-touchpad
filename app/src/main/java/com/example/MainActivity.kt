package com.example

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.CardSurfaceSlate
import com.example.ui.theme.ElevatedHudSurface
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
        // Keep Pixel screen awake while using AR glasses without requiring WAKE_LOCK permission
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

    override fun onResume() {
        super.onResume()
        TouchpadAccessibilityService.syncDisplaysFromContext(this)
    }

    override fun onDestroy() {
        if (::displayManager.isInitialized) {
            displayManager.unregisterDisplayListener(this)
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

            override fun onTwoFingerScroll(scrollDx: Float, scrollDy: Float) {
                viewModel.onTwoFingerScroll(scrollDx, scrollDy)
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(TrackpadBlack),
        containerColor = TrackpadBlack,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets(0.dp))
                .background(TrackpadBlack)
        ) {
            // 1. Minimalist Top Bar (Title + Calibration Button only)
            TouchpadTopBar(
                onOpenCalibration = { viewModel.setCalibrationSheetOpen(true) }
            )

            // 2. Full-Screen DeX-Inspired Crosshair Trackpad Surface (Compose AndroidView)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                AndroidView(
                    factory = { ctx ->
                        DeXCrosshairTrackpadView(ctx).apply {
                            this.gestureCallback = gestureCallback
                            this.pointerSensitivity = uiState.calibration.sensitivity
                            this.scrollSpeed = uiState.calibration.scrollSpeed
                            this.naturalScrolling = uiState.calibration.naturalScrolling
                            this.tapToClick = uiState.calibration.tapToClick
                        }
                    },
                    update = { view ->
                        view.gestureCallback = gestureCallback
                        view.pointerSensitivity = uiState.calibration.sensitivity
                        view.scrollSpeed = uiState.calibration.scrollSpeed
                        view.naturalScrolling = uiState.calibration.naturalScrolling
                        view.tapToClick = uiState.calibration.tapToClick
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("dex_crosshair_trackpad_view")
                )
            }
        }

        // Calibration Modal Bottom Sheet
        if (uiState.isCalibrationSheetOpen) {
            CalibrationBottomSheet(
                calibration = uiState.calibration,
                onSensitivityChange = viewModel::updateSensitivity,
                onScrollSpeedChange = viewModel::updateScrollSpeed,
                onNaturalScrollingChange = viewModel::updateNaturalScrolling,
                onTapToClickChange = viewModel::updateTapToClick,
                onResetDefaults = viewModel::resetCalibrationDefaults,
                onDismiss = { viewModel.setCalibrationSheetOpen(false) }
            )
        }
    }
}

@Composable
private fun TouchpadTopBar(
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

        Spacer(modifier = Modifier.width(8.dp))

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
