package com.niyaz.zario.navigation


// Defines the unique route strings for each screen in the navigation graph.
sealed class Screen(val route: String) {
   // Represents the initial screen users see, potentially deciding where to go next (Login/Main)
   // For now, let's assume it goes directly to a temporary "Home" or "Auth" choice screen.
   // Later this could be a Splash screen or logic to check login status.
   object AuthDecision : Screen("auth_decision_screen")


   object Login : Screen("login_screen")
   object Register : Screen("register_screen")


   // Placeholder for the main screen after login/registration
   // This will eventually host the dashboard, goal tracking, etc.
   object Home : Screen("home_screen")


   // We can add more routes here later (e.g., GoalSetting, History)
}

