package com.niyaz.zario.ui.screens // Or ui/screens/home_phases

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.niyaz.zario.R
import com.niyaz.zario.utils.Constants
import kotlin.math.roundToInt // Import for rounding Float to Int

/**
 * Composable function displaying the UI for setting up the stakes (points earned/lost)
 * for participants in the Flexible Deposit intervention condition.
 *
 * Provides sliders for the user to choose their desired points for success and failure,
 * within the predefined ranges specified in [Constants].
 *
 * @param onConfirm Callback invoked when the user confirms their chosen stakes, passing the selected
 *                  earn points (Int) and lose points (Int).
 */
@Composable
fun FlexibleDepositSetupUI(
    onConfirm: (earn: Int, lose: Int) -> Unit
) {
    // State for slider values, initialized using defaults from Constants
    var earnSliderValue by remember { mutableFloatStateOf(Constants.DEFAULT_FLEX_EARN_SLIDER_VALUE) }
    var loseSliderValue by remember { mutableFloatStateOf(Constants.DEFAULT_FLEX_LOSE_SLIDER_VALUE) }

    // --- Main Column for Flexible Deposit Setup ---
    Column(
        modifier = Modifier
            .fillMaxWidth() // Takes full width available in its parent
            .padding(horizontal = 16.dp, vertical = 24.dp), // Padding around the setup UI
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Title and Explanation
        Text(
            text = stringResource(R.string.flex_setup_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.flex_setup_message),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))

        // --- Earn Points Slider ---
        Text(
            // Display the current integer value of the slider
            text = stringResource(R.string.flex_setup_earn_label, earnSliderValue.roundToInt()),
            style = MaterialTheme.typography.titleMedium
        )
        Slider(
            value = earnSliderValue,
            onValueChange = { earnSliderValue = it }, // Update state as slider moves
            // Define the allowed range using constants
            valueRange = Constants.FLEX_STAKES_MIN_EARN.toFloat()..Constants.FLEX_STAKES_MAX_EARN.toFloat(),
            // Calculate the number of steps for discrete values (steps = range_size - 1)
            steps = (Constants.FLEX_STAKES_MAX_EARN - Constants.FLEX_STAKES_MIN_EARN - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp)) // More spacing between sliders

        // --- Lose Points Slider ---
        Text(
            // Display the current integer value of the slider
            text = stringResource(R.string.flex_setup_lose_label, loseSliderValue.roundToInt()),
            style = MaterialTheme.typography.titleMedium
        )
        Slider(
            value = loseSliderValue,
            onValueChange = { loseSliderValue = it }, // Update state as slider moves
            // Define the allowed range using constants
            valueRange = Constants.FLEX_STAKES_MIN_LOSE.toFloat()..Constants.FLEX_STAKES_MAX_LOSE.toFloat(),
            // Calculate the number of steps for discrete values
            steps = (Constants.FLEX_STAKES_MAX_LOSE - Constants.FLEX_STAKES_MIN_LOSE - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(32.dp)) // Spacing before confirm button

        // --- Confirmation Button ---
        Button(
            onClick = {
                // Invoke the callback with the final integer values from the sliders
                onConfirm(earnSliderValue.roundToInt(), loseSliderValue.roundToInt())
            }
        ) {
            Text(stringResource(R.string.flex_setup_confirm_button))
        }
    } // End Column
}