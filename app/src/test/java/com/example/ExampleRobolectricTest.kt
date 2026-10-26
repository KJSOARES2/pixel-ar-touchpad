package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Pixel AR Touchpad", appName)
    }

    @Test
    fun `verify DeX calibration defaults, prominent disclosure dialog, and pointer movement`() {
        val vm = TouchpadViewModel()
        val initial = vm.uiState.value

        // DeX default calibration values
        assertEquals(1.4f, initial.calibration.sensitivity, 0.001f)
        assertEquals(1.2f, initial.calibration.scrollSpeed, 0.001f)
        assertTrue(initial.calibration.naturalScrolling)
        assertTrue(initial.calibration.tapToClick)

        // Prominent disclosure dialog open initially when AccessibilityService is not yet connected
        assertTrue(initial.isDisclosureDialogOpen)
        vm.setDisclosureDialogOpen(false)
        assertFalse(vm.uiState.value.isDisclosureDialogOpen)

        // 1F Move updates internal cursor coordinates without mutating UI state
        vm.onPointerMove(100f, -50f)
        assertTrue(vm.cursorX > 960f)
        assertTrue(vm.cursorY < 540f)
    }

    @Test
    fun `verify hardware-accelerated Path ring buffer renders active decay trails`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = DeXCrosshairTrackpadView(context)
        view.layout(0, 0, 800, 1200)

        val now = SystemClock.uptimeMillis()
        view.recordRingSample(1, 100f, 150f, 0f, 0f, 0f, 0f, now - 40L)
        view.recordRingSample(1, 180f, 220f, 0f, 0f, 0f, 0f, now - 20L)
        view.recordRingSample(1, 260f, 310f, 0f, 0f, 0f, 0f, now)

        val bmp = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val hasActiveDecay = view.buildAndDrawHardwareTrailPaths(canvas, now)

        assertTrue(hasActiveDecay)
    }
}
