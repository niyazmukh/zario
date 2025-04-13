package com.niyaz.zario.ui.screens // Or ui/screens/home_phases

// Essential Compose imports

// Layout composables

// Material Components

// Graphics & Canvas

// Platform specific

// App specific imports

// Other necessary imports
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niyaz.zario.R
import com.niyaz.zario.StudyPhase
import com.niyaz.zario.utils.AppInfoHelper
import com.niyaz.zario.utils.Constants
import java.util.concurrent.TimeUnit

/**
 * Composable function displaying the main dashboard UI during the intervention phases
 * (Control, Deposit, Flexible Deposit).
 *
 * Shows the user's selected target app, daily goal, current points balance,
 * a visual progress indicator for daily usage, and text summarizing their daily commitment rules.
 *
 * @param currentPhase The specific intervention phase the user is currently in.
 * @param points The user's current points balance.
 * @param targetAppPackageName The package name of the user's selected target application (can be null if not set).
 * @param dailyGoalMs The user's set daily usage goal in milliseconds (can be null if not set).
 * @param todayUsageMs The accumulated usage duration (in milliseconds) for the target app today.
 * @param flexStakes The chosen flexible stakes (earn, lose) if applicable (Pair<Int?, Int?>).
 */
@Composable
fun InterventionPhaseUI(
    currentPhase: StudyPhase,
    points: Int,
    targetAppPackageName: String?,
    dailyGoalMs: Long?,
    todayUsageMs: Long,
    flexStakes: Pair<Int?, Int?> // (earn, lose) points for flexible condition
) {
    val context = LocalContext.current

    // Resolve target app name using AppInfoHelper (provides fallback)
    val targetAppName = remember(targetAppPackageName, context) { // Added context to remember key
        targetAppPackageName?.let { AppInfoHelper.getAppName(context, it) }
            ?: context.getString(R.string.dashboard_target_app_fallback) // Use defined string resource
    }

    // --- Main Column for Intervention Dashboard ---
    Column(
        modifier = Modifier
            .fillMaxSize() // Fill available space from parent
            .padding(horizontal = 8.dp, vertical = 8.dp), // Padding around the content
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Dashboard Title
        Text(
            text = stringResource(R.string.dashboard_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // 2. Goal and Points Information Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceAround, // Space out items evenly
            verticalAlignment = Alignment.Top // Align tops of the columns
        ) {
            // Goal Section Column
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f) // Give equal weight
            ) {
                Text(
                    text = stringResource(R.string.dashboard_goal_section_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (dailyGoalMs != null) {
                        // Format goal string using helper
                        stringResource(R.string.dashboard_goal_text, targetAppName, formatDuration(dailyGoalMs))
                    } else {
                        stringResource(R.string.dashboard_goal_not_set)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.heightIn(min = 40.dp) // Ensure consistent height
                )
            }

            // Points Section Column
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f) // Give equal weight
            ) {
                Text(
                    text = stringResource(R.string.dashboard_points_section_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    // Display current points balance
                    text = "$points", // Points value is dynamic
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        } // End Goal and Points Row

        // 3. Circular Progress Indicator for Daily Usage
        if (dailyGoalMs != null && dailyGoalMs > 0) {
            // Only show progress if a valid goal is set
            UsageProgressIndicator(
                todayUsageMs = todayUsageMs,
                dailyGoalMs = dailyGoalMs,
                modifier = Modifier
                    .fillMaxWidth(0.6f) // Control indicator size relative to screen width
                    .aspectRatio(1f) // Ensure it's drawn in a square for a perfect circle
                    .padding(bottom = 24.dp) // Spacing below the indicator
            )
        } else {
            // Placeholder or message if goal isn't set
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .aspectRatio(1f)
                    .padding(bottom = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.dashboard_progress_goal_not_set),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        } // End Circular Progress

        // 4. Daily Commitment Information Section
        Text(
            text = stringResource(R.string.dashboard_commitment_section_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Display commitment rules within a Card for visual grouping
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), // Subtle elevation
            shape = MaterialTheme.shapes.medium // Rounded corners
        ) {
            // Determine the commitment text based on the current phase and flex stakes
            val commitmentText = getCommitmentText(
                phase = currentPhase,
                flexEarn = flexStakes.first, // Pass earn points
                flexLose = flexStakes.second // Pass lose points
            )
            Text(
                text = commitmentText,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(16.dp) // Padding inside the card
                    .fillMaxWidth()
            )
        } // End Commitment Card
        Spacer(modifier = Modifier.height(16.dp)) // Space at the bottom

    } // End Main Column
}

/**
 * Composable function that draws the circular progress indicator showing remaining daily usage time.
 * (Implementation remains the same as before)
 */
@Composable
private fun UsageProgressIndicator(
    todayUsageMs: Long,
    dailyGoalMs: Long,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 12.dp, // Use Dp type
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val remainingMs = (dailyGoalMs - todayUsageMs).coerceAtLeast(0L)
    val progressFraction = (todayUsageMs.toFloat() / dailyGoalMs.toFloat()).coerceIn(0f, 1f)
    val sweepAngle = progressFraction * 360f

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth.toPx())
            )
            if (sweepAngle > 0f) {
                drawArc(
                    color = primaryColor,
                    startAngle = -90f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        Text(
            text = stringResource(R.string.dashboard_progress_text, formatDuration(remainingMs)),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Determines the appropriate commitment description string based on the study phase and flexible stakes.
 * (Implementation remains the same as before)
 */
@Composable
private fun getCommitmentText(phase: StudyPhase, flexEarn: Int?, flexLose: Int?): String {
    val controlEarn = Constants.DEFAULT_CONTROL_EARN_POINTS
    val depositEarn = Constants.DEFAULT_DEPOSIT_EARN_POINTS
    val depositLose = Constants.DEFAULT_DEPOSIT_LOSE_POINTS

    // Pass arguments to string resources correctly
    return when (phase) {
        StudyPhase.INTERVENTION_CONTROL ->
            stringResource(R.string.dashboard_commitment_control, controlEarn)
        StudyPhase.INTERVENTION_DEPOSIT ->
            stringResource(R.string.dashboard_commitment_deposit, depositEarn, depositLose)
        StudyPhase.INTERVENTION_FLEXIBLE ->
            stringResource(
                R.string.dashboard_commitment_flex,
                flexEarn ?: "-",
                flexLose ?: "-"
            )
        else -> stringResource(R.string.dashboard_commitment_fallback)
    }
}

/**
 * Helper function to format a duration in milliseconds into a human-readable string (e.g., "1h 23m", "45m").
 * Standard Long comparisons should work fine. The previous compiler error about 'compareTo' might have
 * been related to missing imports or a tooling issue.
 * @param millis The duration in milliseconds.
 * @return A formatted string representation of the duration.
 */
@Composable // Mark as Composable if using stringResource inside, otherwise make it a plain function
private fun formatDuration(millis: Long): String {
    if (millis < 0L) return stringResource(R.string.duration_zero_minutes) // Use 0m for negative, maybe from resource?

    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millis) // Use imported TimeUnit
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L

    // Standard Long comparisons:
    return when {
        hours > 0L && minutes > 0L -> stringResource(R.string.duration_hours_minutes, hours, minutes) // "%1$dh %2$dm"
        hours > 0L && minutes == 0L -> stringResource(R.string.duration_hours_only, hours) // "%1$dh"
        else -> stringResource(R.string.duration_minutes_only, minutes) // "%1$dm"
    }
}

