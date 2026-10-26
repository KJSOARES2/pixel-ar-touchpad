package com.example

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CardSurfaceSlate
import com.example.ui.theme.ElectricIndigo
import com.example.ui.theme.ElevatedHudSurface
import com.example.ui.theme.EmeraldReady
import com.example.ui.theme.SkyCyan
import com.example.ui.theme.TextIceWhite
import com.example.ui.theme.TextMutedSlate
import com.example.ui.theme.TrackpadBlack
import com.example.ui.theme.TrackpadBorderSlate

/**
 * Google Play Policy-compliant Prominent Disclosure Dialog for `AccessibilityService`.
 *
 * Clearly discloses:
 * 1. Why the AccessibilityService API is used (cross-display gesture dispatch for USB-C AR glasses).
 * 2. That it is strictly write-only (`canRetrieveWindowContent="false"`, `accessibilityEventTypes=""`).
 * 3. That zero personal or sensitive user data is collected, stored, or shared (zero manifest permissions).
 * Requires explicit affirmative user consent before opening system Accessibility Settings.
 */
@Composable
fun AccessibilityDisclosureDialog(
    onAcceptAndOpenSettings: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDecline,
        containerColor = ElevatedHudSurface,
        titleContentColor = TextIceWhite,
        textContentColor = TextMutedSlate,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .border(1.dp, TrackpadBorderSlate, RoundedCornerShape(16.dp))
            .testTag("a11y_prominent_disclosure_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CardSurfaceSlate)
                        .border(1.dp, SkyCyan.copy(alpha = 0.7f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = null,
                        tint = SkyCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.disclosure_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextIceWhite
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.disclosure_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextIceWhite
                )

                DisclosurePointCard(
                    title = stringResource(R.string.disclosure_point_1_title),
                    body = stringResource(R.string.disclosure_point_1_body),
                    accentColor = SkyCyan
                )

                DisclosurePointCard(
                    title = stringResource(R.string.disclosure_point_2_title),
                    body = stringResource(R.string.disclosure_point_2_body),
                    accentColor = ElectricIndigo
                )

                DisclosurePointCard(
                    title = stringResource(R.string.disclosure_point_3_title),
                    body = stringResource(R.string.disclosure_point_3_body),
                    accentColor = EmeraldReady
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAcceptAndOpenSettings,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SkyCyan,
                    contentColor = TrackpadBlack
                ),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("accept_a11y_disclosure_button")
            ) {
                Text(
                    text = stringResource(R.string.disclosure_accept),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDecline,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, TrackpadBorderSlate),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextIceWhite),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("decline_a11y_disclosure_button")
            ) {
                Text(
                    text = stringResource(R.string.disclosure_decline),
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }
    )
}

@Composable
private fun DisclosurePointCard(
    title: String,
    body: String,
    accentColor: androidx.compose.ui.graphics.Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CardSurfaceSlate)
            .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = accentColor,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMutedSlate
        )
    }
}
