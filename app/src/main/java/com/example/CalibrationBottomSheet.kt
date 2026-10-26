package com.example

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CardSurfaceSlate
import com.example.ui.theme.ElectricIndigo
import com.example.ui.theme.ElevatedHudSurface
import com.example.ui.theme.SkyCyan
import com.example.ui.theme.TextIceWhite
import com.example.ui.theme.TextMutedSlate
import com.example.ui.theme.TrackpadBlack
import com.example.ui.theme.TrackpadBorderSlate
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationBottomSheet(
    calibration: CalibrationConfig,
    onSensitivityChange: (Float) -> Unit,
    onScrollSpeedChange: (Float) -> Unit,
    onNaturalScrollingChange: (Boolean) -> Unit,
    onTapToClickChange: (Boolean) -> Unit,
    onResetDefaults: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ElevatedHudSurface,
        contentColor = TextIceWhite,
        modifier = Modifier.testTag("calibration_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.calibration_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextIceWhite
                )
                Text(
                    text = stringResource(R.string.calibration_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMutedSlate
                )
            }

            HorizontalDivider(color = TrackpadBorderSlate)

            // Pointer Sensitivity Slider (Default 1.4x)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.calibration_sensitivity),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextIceWhite
                    )
                    Text(
                        text = String.format(Locale.US, "%.1fx", calibration.sensitivity),
                        style = MaterialTheme.typography.labelMedium,
                        color = SkyCyan,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = calibration.sensitivity,
                    onValueChange = onSensitivityChange,
                    valueRange = 0.5f..3.0f,
                    steps = 24,
                    colors = SliderDefaults.colors(
                        thumbColor = SkyCyan,
                        activeTrackColor = SkyCyan,
                        inactiveTrackColor = CardSurfaceSlate
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("slider_pointer_sensitivity")
                )
            }

            // Two-Finger Scroll Speed Slider (Default 1.2x)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.calibration_scroll_speed),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextIceWhite
                    )
                    Text(
                        text = String.format(Locale.US, "%.1fx", calibration.scrollSpeed),
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricIndigo,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = calibration.scrollSpeed,
                    onValueChange = onScrollSpeedChange,
                    valueRange = 0.5f..3.0f,
                    steps = 24,
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricIndigo,
                        activeTrackColor = ElectricIndigo,
                        inactiveTrackColor = CardSurfaceSlate
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("slider_scroll_speed")
                )
            }

            HorizontalDivider(color = TrackpadBorderSlate)

            // Natural Scrolling Switch (Default true)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.calibration_natural_scrolling),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextIceWhite
                    )
                    Text(
                        text = stringResource(R.string.calibration_natural_scrolling_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMutedSlate
                    )
                }
                Switch(
                    checked = calibration.naturalScrolling,
                    onCheckedChange = onNaturalScrollingChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TrackpadBlack,
                        checkedTrackColor = SkyCyan,
                        uncheckedThumbColor = TextMutedSlate,
                        uncheckedTrackColor = CardSurfaceSlate
                    ),
                    modifier = Modifier.testTag("switch_natural_scrolling")
                )
            }

            // Tap-to-Click Switch (Default true)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.calibration_tap_to_click),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextIceWhite
                    )
                    Text(
                        text = stringResource(R.string.calibration_tap_to_click_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMutedSlate
                    )
                }
                Switch(
                    checked = calibration.tapToClick,
                    onCheckedChange = onTapToClickChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TrackpadBlack,
                        checkedTrackColor = SkyCyan,
                        uncheckedThumbColor = TextMutedSlate,
                        uncheckedTrackColor = CardSurfaceSlate
                    ),
                    modifier = Modifier.testTag("switch_tap_to_click")
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onResetDefaults,
                    border = BorderStroke(1.dp, TrackpadBorderSlate),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextIceWhite),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("reset_calibration_button")
                ) {
                    Text(
                        text = stringResource(R.string.calibration_reset),
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SkyCyan,
                        contentColor = TrackpadBlack
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("close_calibration_button")
                ) {
                    Text(
                        text = stringResource(R.string.calibration_close),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
