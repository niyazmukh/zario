package com.niyaz.zario.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.niyaz.zario.R // Resource imports
import com.niyaz.zario.navigation.Screen // Navigation route definitions

/**
 * Composable function displaying the Authentication Decision Screen.
 *
 * This screen is the initial landing point for unauthenticated users, offering
 * navigation pathways to either log in or register for the study.
 *
 * @param navController The Jetpack Compose [NavController] used to navigate to the
 *                      [Screen.Login] or [Screen.Register] destinations.
 */
@Composable
fun AuthDecisionScreen(navController: NavController) {
    Column(
        modifier = Modifier
            .fillMaxSize() // Ensure the column takes up the whole screen
            .padding(16.dp), // Standard padding around the content
        verticalArrangement = Arrangement.Center, // Center the buttons vertically
        horizontalAlignment = Alignment.CenterHorizontally // Center the buttons horizontally
    ) {
        // Welcome text defined in strings.xml
        Text(stringResource(R.string.auth_decision_welcome))
        Spacer(modifier = Modifier.height(32.dp)) // Spacing between text and buttons

        // Login Button
        Button(
            onClick = {
                // Navigate to the Login screen when clicked
                navController.navigate(Screen.Login.route)
            }
        ) {
            Text(stringResource(R.string.auth_decision_login_button))
        }

        Spacer(modifier = Modifier.height(16.dp)) // Spacing between buttons

        // Register Button
        Button(
            onClick = {
                // Navigate to the Register screen when clicked
                navController.navigate(Screen.Register.route)
            }
        ) {
            Text(stringResource(R.string.auth_decision_register_button))
        }
    }
}