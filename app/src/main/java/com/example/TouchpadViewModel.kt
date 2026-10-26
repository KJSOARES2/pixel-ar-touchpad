package com.example

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

data class CalibrationConfig(
    val sensitivity: Float = 1.4f,
    val scrollSpeed: Float = 1.2f,
    val naturalScrolling: Boolean = true,
    val tapToClick: Boolean = true
)

data class TouchpadUiState(
    val isCalibrationSheetOpen: Boolean = false,
    val isDisclosureDialogOpen: Boolean = !TouchpadAccessibilityService.isServiceConnected.value,
    val isBlackoutMode: Boolean = false,
    val calibration: CalibrationConfig = CalibrationConfig()
)

class TouchpadViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(TouchpadUiState())
    val uiState: StateFlow<TouchpadUiState> = _uiState.asStateFlow()

    var cursorX: Float = 960f
        private set
    var cursorY: Float = 540f
        private set

    fun setCalibrationSheetOpen(open: Boolean) {
        _uiState.update { it.copy(isCalibrationSheetOpen = open) }
    }

    fun setDisclosureDialogOpen(open: Boolean) {
        _uiState.update { it.copy(isDisclosureDialogOpen = open) }
    }

    fun setBlackoutMode(enabled: Boolean) {
        _uiState.update { it.copy(isBlackoutMode = enabled) }
    }

    fun updateSensitivity(value: Float) {
        val clamped = (value * 10f).roundToInt() / 10f
        _uiState.update {
            it.copy(calibration = it.calibration.copy(sensitivity = clamped.coerceIn(0.5f, 3.0f)))
        }
    }

    fun updateScrollSpeed(value: Float) {
        val clamped = (value * 10f).roundToInt() / 10f
        _uiState.update {
            it.copy(calibration = it.calibration.copy(scrollSpeed = clamped.coerceIn(0.5f, 3.0f)))
        }
    }

    fun updateNaturalScrolling(enabled: Boolean) {
        _uiState.update {
            it.copy(calibration = it.calibration.copy(naturalScrolling = enabled))
        }
    }

    fun updateTapToClick(enabled: Boolean) {
        _uiState.update {
            it.copy(calibration = it.calibration.copy(tapToClick = enabled))
        }
    }

    fun resetCalibrationDefaults() {
        _uiState.update {
            it.copy(
                calibration = CalibrationConfig(
                    sensitivity = 1.4f,
                    scrollSpeed = 1.2f,
                    naturalScrolling = true,
                    tapToClick = true
                )
            )
        }
    }

    fun onPointerMove(dx: Float, dy: Float) {
        val svc = TouchpadAccessibilityService.instance
        if (svc != null) {
            val (extX, extY) = svc.moveExternalCursorByDelta(dx, dy)
            cursorX = extX
            cursorY = extY
        } else {
            cursorX = (cursorX + dx * 1.45f).coerceIn(0f, AR_VIEWPORT_WIDTH)
            cursorY = (cursorY + dy * 1.45f).coerceIn(0f, AR_VIEWPORT_HEIGHT)
        }
    }

    fun onLeftClick() {
        TouchpadAccessibilityService.instance?.dispatchExternalTap()
    }

    fun onRightClick() {
        TouchpadAccessibilityService.instance?.dispatchExternalRightClick()
    }

    fun onTwoFingerScroll(scrollDx: Float, scrollDy: Float, rawFingerDy: Float) {
        TouchpadAccessibilityService.instance?.dispatchExternalScroll(scrollDx, scrollDy, rawFingerDy)
    }

    fun onOneFingerDragEnd(totalDragDx: Float, totalDragDy: Float) {
        TouchpadAccessibilityService.instance?.dispatchExternalDrag(totalDragDx, totalDragDy)
    }

    companion object {
        const val AR_VIEWPORT_WIDTH = 1920f
        const val AR_VIEWPORT_HEIGHT = 1080f
    }
}
