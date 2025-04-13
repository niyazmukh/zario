package com.niyaz.zario.ui.screens // Or ui/screens/home_phases

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.niyaz.zario.R
import com.niyaz.zario.data.model.AppBaselineInfo
import com.niyaz.zario.utils.Constants
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Composable function representing the UI content for the Goal Setting phase.
 * Displays baseline analysis results (suggested app, app list, hourly usage),
 * allows the user to select a target app, shows the calculated goal, and provides
 * a button to confirm the selection.
 *
 * @param isLoading Indicates if the goal confirmation process is currently saving.
 * @param suggestedApp The [AppBaselineInfo] suggested as the target (can be null).
 * @param baselineAppList The list of [AppBaselineInfo] objects derived from baseline usage.
 * @param hourlyUsageData A map where keys are hours (0-23) and values are total usage duration (ms)
 *                        for that hour during the baseline period.
 * @param onConfirmGoal Callback invoked when the user confirms their selection, passing the chosen [AppBaselineInfo].
 */
@OptIn(ExperimentalLayoutApi::class) // For FlowRow
@Composable
fun GoalSettingPhaseContent(
    isLoading: Boolean,
    suggestedApp: AppBaselineInfo?,
    baselineAppList: List<AppBaselineInfo>,
    hourlyUsageData: Map<Int, Long>,
    onConfirmGoal: (selectedApp: AppBaselineInfo) -> Unit
) {
    // State to track the currently selected app by the user in this UI
    var selectedAppForGoal by remember(suggestedApp) { mutableStateOf(suggestedApp) } // Use suggested as initial

    // Limit the number of app icons shown based on the constant
    val appsToShow = baselineAppList.take(Constants.GOAL_SETTING_APP_ICON_COUNT)

    // --- Main Column for Goal Setting UI ---
    Column(
        modifier = Modifier
            .fillMaxSize() // Take available space within the parent (HomeScreen)
            .padding(top = 8.dp), // Add some top padding below the phase title
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Screen Title
        Text(
            text = stringResource(R.string.goal_setting_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Suggestion Text (Dynamically includes suggested app name if available)
        val suggestionText = if (suggestedApp != null) {
            stringResource(R.string.goal_setting_suggestion_with_app, suggestedApp.appName)
        } else {
            stringResource(R.string.goal_setting_suggestion_no_app)
        }
        Text(
            text = suggestionText,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp) // Padding for longer text
        )
        Spacer(modifier = Modifier.height(20.dp)) // More space before icons

        // --- App Icon Selection Section ---
        Text(
            text = stringResource(R.string.goal_setting_selection_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(12.dp))

        // FlowRow arranges app icons, wrapping to the next line if needed
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            maxItemsInEachRow = Constants.GOAL_SETTING_APP_ICON_COUNT // Control items per row
        ) {
            appsToShow.forEach { appInfo ->
                AppIconItem( // Reusable composable for displaying each app icon
                    appInfo = appInfo,
                    isSelected = selectedAppForGoal?.packageName == appInfo.packageName,
                    onClick = { selectedAppForGoal = appInfo } // Update local selection state
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp)) // More space after icons

        // --- Display Info for Currently Selected App ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .heightIn(min = 80.dp), // Ensure minimum height for text area
            contentAlignment = Alignment.Center
        ) {
            if (selectedAppForGoal != null) {
                // Show baseline average and calculated goal if an app is selected
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(
                            R.string.goal_setting_selected_app_info,
                            selectedAppForGoal!!.appName, // Use !! as null check is done
                            formatDuration(selectedAppForGoal!!.averageDailyUsageMs) // Format baseline duration
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.goal_setting_selected_app_goal,
                            // Calculate and format the goal string on the fly
                            calculateGoalString(selectedAppForGoal)
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Prompt user to select an app if none is selected yet
                Text(
                    text = stringResource(R.string.goal_setting_select_prompt),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        } // End Selected App Info Box

        // --- Hourly Usage Chart Section ---
        if (hourlyUsageData.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.goal_setting_chart_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            HourlyUsageBarChart( // Reusable composable for the bar chart
                usageData = hourlyUsageData,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp) // Fixed height for the chart area
                    .padding(horizontal = 8.dp)
            )
        }
        // --- End Hourly Usage Chart ---

        // Spacer to push the confirmation button towards the bottom
        Spacer(modifier = Modifier.weight(1f))

        // --- Confirmation Button ---
        Button(
            onClick = { selectedAppForGoal?.let { onConfirmGoal(it) } }, // Invoke callback with selected app
            enabled = selectedAppForGoal != null && !isLoading, // Enable only if an app is selected and not saving
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp) // Padding below button
        ) {
            if (isLoading) {
                // Show loading indicator if saving is in progress
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary // Ensure visibility on button
                )
            } else {
                // Dynamically update button text based on selection
                val buttonText = selectedAppForGoal?.appName?.let {
                    stringResource(R.string.goal_setting_confirm_button, it)
                } ?: stringResource(R.string.goal_setting_confirm_button_default)
                Text(buttonText)
            }
        } // End Confirmation Button
    } // End Main Column
}

/**
 * Composable function for displaying a single application icon item, typically used
 * in the goal-setting selection grid. Handles displaying the icon, indicating selection
 * state with a border, and responding to clicks.
 *
 * @param appInfo The [AppBaselineInfo] containing details (name, icon) of the app to display.
 * @param isSelected Boolean indicating if this item is the currently selected one.
 * @param onClick Lambda function invoked when the item is clicked.
 * @param iconSize The desired size for the icon item (default: 64dp).
 */
@Composable
private fun AppIconItem(
    appInfo: AppBaselineInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    iconSize: Dp = 64.dp // Make icon size configurable
) {
    val borderStroke = if (isSelected) {
        BorderStroke(3.dp, MaterialTheme.colorScheme.primary) // Selected border
    } else {
        BorderStroke(0.dp, Color.Transparent) // No border when not selected
    }

    Box(
        modifier = Modifier
            .size(iconSize)
            .clip(CircleShape) // Clip to circle shape
            .background(MaterialTheme.colorScheme.surfaceVariant) // Subtle background
            .border(borderStroke, CircleShape) // Apply conditional border
            .clickable(onClick = onClick, enabled = true), // Handle click events
        contentAlignment = Alignment.Center // Center the image within the Box
    ) {
        Image(
            painter = rememberAsyncImagePainter(
                model = appInfo.icon,
                // Provide fallback/error drawables if needed
                // error = painterResource(id = R.drawable.ic_placeholder),
                // placeholder = painterResource(id = R.drawable.ic_placeholder)
            ),
            // Content description for accessibility
            contentDescription = stringResource(R.string.goal_setting_app_icon_description, appInfo.appName),
            modifier = Modifier
                .padding(4.dp) // Padding inside the circle for the icon itself
                .fillMaxSize() // Let the image fill the Box
        )
    }
}

/**
 * Composable function that draws a simple bar chart visualizing hourly usage data.
 * Represents usage duration per hour (0-23) based on baseline data.
 *
 * @param usageData A map where keys are hours (0-23) and values are total usage duration (ms).
 * @param modifier Modifier to apply to the Canvas.
 * @param barColor The color of the bars in the chart.
 * @param axisLabelColor The color of the hour labels on the X-axis.
 * @param axisLabelFontSize The font size for the hour labels.
 */
@Composable
private fun HourlyUsageBarChart(
    usageData: Map<Int, Long>, // Hour (0-23) -> Duration Ms
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    axisLabelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    axisLabelFontSize: TextUnit = 10.sp
) {
    // Handle empty data case
    if (usageData.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.goal_setting_chart_empty),
                style = MaterialTheme.typography.bodySmall,
                color = axisLabelColor // Use axis color for consistency
            )
        }
        return
    }

    // TextMeasurer for calculating label sizes
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // Convert SP font size to Px for accurate calculations within Canvas
    val axisLabelFontSizePx = remember(axisLabelFontSize) {
        with(density) { axisLabelFontSize.toPx() }
    }

    // Find the maximum usage value across all hours to scale the bars appropriately.
    // Ensure maxUsageMs is at least 1 to avoid division by zero or NaN scaling issues.
    val maxUsageMs = remember(usageData) { max(1L, usageData.values.maxOrNull() ?: 1L) }

    // Draw the chart using Jetpack Compose Canvas API
    Canvas(modifier = modifier) {
        val chartHeight = size.height
        val chartWidth = size.width
        val barCount = 24 // 24 hours

        // --- Calculate Dimensions ---
        // Reserve space at the bottom for X-axis labels
        val labelHeight = axisLabelFontSizePx * 1.8f // Estimate label height needed (adjust buffer as needed)
        val barAreaHeight = (chartHeight - labelHeight).coerceAtLeast(0f) // Drawing area for bars

        // Calculate bar width and spacing based on available width
        val totalSpacingRatio = 0.1f // e.g., 10% of total width reserved for spacing
        val totalSpacing = chartWidth * totalSpacingRatio
        val totalBarWidth = chartWidth - totalSpacing
        val barWidth = (totalBarWidth / barCount).coerceAtLeast(1f) // Ensure minimum 1px width
        val barSpacing = (totalSpacing / (barCount + 1)).coerceAtLeast(0f) // Space between bars and at ends

        // --- Draw X-axis Labels ---
        drawAxisLabels(
            drawScope = this,
            textMeasurer = textMeasurer,
            hoursToLabel = listOf(0, 6, 12, 18, 23), // Hours to display labels for
            axisLabelStyle = TextStyle(color = axisLabelColor, fontSize = axisLabelFontSize),
            barAreaHeight = barAreaHeight,
            labelHeight = labelHeight,
            barWidth = barWidth,
            barSpacing = barSpacing,
            chartWidth = chartWidth
        )

        // --- Draw Bars ---
        for (hour in 0 until barCount) {
            val usageMs = usageData.getOrDefault(hour, 0L)
            if (usageMs > 0) { // Only draw bars for hours with usage
                // Calculate bar height relative to max usage and available area
                val barHeight = ((usageMs.toFloat() / maxUsageMs.toFloat()) * barAreaHeight)
                    .coerceAtLeast(0f) // Ensure non-negative height
                // Calculate top-left X position for the bar
                val xOffset = barSpacing + hour * (barWidth + barSpacing)

                if (barHeight > 0) { // Avoid drawing zero-height rectangles
                    drawRect(
                        color = barColor,
                        topLeft = Offset(xOffset, barAreaHeight - barHeight), // Draw from bottom up
                        size = Size(barWidth, barHeight)
                    )
                }
            }
        } // End bar drawing loop
    } // End Canvas
}

/** Helper scope function to draw X-axis labels within the HourlyUsageBarChart Canvas. */
private fun drawAxisLabels(
    drawScope: DrawScope,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    hoursToLabel: List<Int>,
    axisLabelStyle: TextStyle,
    barAreaHeight: Float,
    labelHeight: Float,
    barWidth: Float,
    barSpacing: Float,
    chartWidth: Float
) {
    drawScope.apply { // Use DrawScope receiver
        hoursToLabel.forEach { hour ->
            val label = "${hour}h"
            val textLayoutResult: TextLayoutResult = textMeasurer.measure(
                text = label,
                style = axisLabelStyle
            )
            // Calculate centered X position for the label under its corresponding hour cluster
            val xPos = barSpacing + hour * (barWidth + barSpacing) + (barWidth / 2f) - (textLayoutResult.size.width / 2f)
            // Ensure the label doesn't draw outside the chart boundaries
            val clampedXPos = xPos.coerceIn(0f, chartWidth - textLayoutResult.size.width)
            // Position the label below the bar area
            val yPos = barAreaHeight + (labelHeight - textLayoutResult.size.height) / 2f // Center vertically in label area

            drawText(
                textLayoutResult = textLayoutResult,
                topLeft = Offset(clampedXPos, yPos)
            )
        }
    }
}


/**
 * Helper function to calculate the goal string representation based on baseline average and reduction factor.
 * @param appInfo The [AppBaselineInfo] containing the baseline average usage.
 * @return A formatted string representing the daily time goal (e.g., "< 36m"). Returns "N/A" if input is null.
 */
private fun calculateGoalString(appInfo: AppBaselineInfo?): String {
    if (appInfo == null) return "N/A"
    val baselineAverageMs = appInfo.averageDailyUsageMs
    // Use constants for reduction factor and minimum goal duration
    val dailyGoalMs = (baselineAverageMs * Constants.GOAL_REDUCTION_FACTOR)
        .toLong()
        .coerceAtLeast(Constants.MINIMUM_GOAL_DURATION_MS)
    // Format the duration using the helper function
    return formatDuration(dailyGoalMs)
}

/**
 * Helper function to format a duration in milliseconds into a human-readable string (e.g., "1h 23m", "45m").
 * Consider moving this to a shared UI utility file if used in multiple places.
 * @param millis The duration in milliseconds.
 * @return A formatted string representation of the duration.
 */
private fun formatDuration(millis: Long): String {
    if (millis < 0) return "0m" // Handle negative durations gracefully
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millis)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60

    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m" // e.g., "2h 15m"
        hours > 0 && minutes == 0L -> "${hours}h" // e.g., "1h"
        else -> "${minutes}m" // e.g., "45m", "0m"
    }
}