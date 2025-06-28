package com.niyaz.zario


import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.niyaz.zario.navigation.Screen
import com.niyaz.zario.ui.screens.AuthDecisionScreen
import com.niyaz.zario.ui.screens.HomeScreen
import com.niyaz.zario.ui.screens.LoginScreen
import com.niyaz.zario.ui.screens.RegisterScreen
import com.niyaz.zario.ui.theme.ZarioTheme
import com.niyaz.zario.utils.StudyStateManager


class MainActivity : ComponentActivity() {


    private lateinit var auth: FirebaseAuth // Declare FirebaseAuth instance


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = Firebase.auth // Initialize FirebaseAuth


        // --- Determine Start Destination ---
        val startDestination = if (auth.currentUser != null) {
            // User is logged in, but also check if study state is valid (e.g., wasn't cleared unexpectedly)
            // If user exists but local state is cleared, log them out for safety.
            if (StudyStateManager.getUserId(this) == null) {
                auth.signOut() // Sign out if local state missing despite auth user
                Screen.AuthDecision.route // Go to login/register
            } else {
                Screen.Home.route // Go directly to home
            }
        } else {
            Screen.AuthDecision.route // No user, go to login/register
        }
        // --- End Start Destination Logic ---


        setContent {
            ZarioTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Pass the determined start destination
                    AppNavigation(startDestination = startDestination)
                }
            }
        }
    }
}


@Composable
// Modify AppNavigation to accept startDestination
fun AppNavigation(startDestination: String) {
    // Creates a NavController instance, remembers it across recompositions
    val navController = rememberNavController()


    // NavHost defines the navigation graph
    NavHost(
        navController = navController,
        // Set the starting screen dynamically
        startDestination = startDestination
    ) {
        // Define each screen (composable) linked to a route (no changes needed here)
        composable(route = Screen.AuthDecision.route) {
            AuthDecisionScreen(navController = navController)
        }
        composable(route = Screen.Login.route) {
            LoginScreen(navController = navController)
        }
        composable(route = Screen.Register.route) {
            RegisterScreen(navController = navController)
        }
        composable(route = Screen.Home.route) {
            HomeScreen(navController = navController)
        }
        // Add routes for other screens if/when they are created (e.g., AnalyticsScreen)
        // composable(route = Screen.Analytics.route) { // Example placeholder
        //    AnalyticsScreen(navController = navController)
        // }
    }
}
