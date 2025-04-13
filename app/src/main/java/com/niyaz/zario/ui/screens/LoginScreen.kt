package com.niyaz.zario.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.niyaz.zario.R
import com.niyaz.zario.data.local.AppDatabase
import com.niyaz.zario.data.repository.StudyRepository
import com.niyaz.zario.data.repository.StudyRepositoryImpl
import com.niyaz.zario.navigation.Screen
import kotlinx.coroutines.launch

/**
 * Composable function for the Login Screen.
 *
 * Allows existing users to authenticate using their email and password.
 * Provides validation, handles login attempts via Firebase Authentication,
 * fetches study state from Firestore via the repository upon successful login,
 * and includes a "Forgot Password?" feature.
 *
 * @param navController The [NavController] used for navigation (back to AuthDecision or forward to Home).
 * @comment Repository is instantiated directly here. In a production app, consider using
 *          Dependency Injection (e.g., Hilt) for better testability and decoupling.
 */
@Composable
fun LoginScreen(navController: NavController) {
    // --- State Variables ---
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var generalError by remember { mutableStateOf<String?>(null) } // For Firebase/logic errors
    var isLoading by remember { mutableStateOf(false) }

    // Password Reset Dialog State
    var showResetDialog by remember { mutableStateOf(false) }
    var resetEmail by rememberSaveable { mutableStateOf("") }
    var resetEmailError by remember { mutableStateOf<String?>(null) }
    var isResettingPassword by remember { mutableStateOf(false) } // Renamed for clarity
    var resetSuccessMessage by remember { mutableStateOf<String?>(null) }

    // --- Dependencies & Context ---
    val context = LocalContext.current
    val auth: FirebaseAuth = Firebase.auth
    val coroutineScope = rememberCoroutineScope()
    // Instantiate repository directly (see composable KDoc comment)
    val repository: StudyRepository = remember {
        StudyRepositoryImpl(
            context = context.applicationContext,
            usageStatDao = AppDatabase.getDatabase(context.applicationContext).usageStatDao(),
            firestore = Firebase.firestore
        )
    }
    val TAG = "LoginScreen" // For logging

    // --- Password Reset Dialog ---
    if (showResetDialog) {
        PasswordResetDialog(
            initialEmail = email, // Pass current email as potential initial value
            isResetting = isResettingPassword,
            resetEmailError = resetEmailError,
            onDismissRequest = { if (!isResettingPassword) showResetDialog = false },
            onSendRequest = { emailToSend ->
                isResettingPassword = true
                resetEmailError = null // Clear previous errors
                auth.sendPasswordResetEmail(emailToSend)
                    .addOnCompleteListener { task ->
                        isResettingPassword = false
                        if (task.isSuccessful) {
                            Log.i(TAG, "Password reset email sent successfully to $emailToSend")
                            resetSuccessMessage = context.getString(R.string.login_reset_dialog_toast_success)
                            showResetDialog = false // Close dialog on success
                        } else {
                            Log.w(TAG, "sendPasswordResetEmail failure", task.exception)
                            resetEmailError = context.getString(R.string.login_reset_dialog_toast_failure)
                        }
                    }
            }
        )
    }

    // Show success toast if reset email was sent
    LaunchedEffect(resetSuccessMessage) {
        resetSuccessMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            resetSuccessMessage = null // Consume the message
        }
    }

    // --- Main Login Screen UI ---
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()), // Enable scrolling for smaller screens
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Title
        Text(
            text = stringResource(R.string.login_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(24.dp))

        // Email Field
        OutlinedTextField(
            value = email,
            onValueChange = { email = it.trim(); emailError = null; generalError = null },
            label = { Text(stringResource(R.string.email_address_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            isError = emailError != null || generalError != null, // Show error if either field or general error exists
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        )
        emailError?.let { // Display specific field error below
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Password Field
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; passwordError = null; generalError = null },
            label = { Text(stringResource(R.string.password_label)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            isError = passwordError != null || generalError != null, // Show error if either field or general error exists
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        )
        passwordError?.let { // Display specific field error below
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Forgot Password Button
        TextButton(
            onClick = {
                // Pre-fill dialog with current valid email if any
                resetEmail = if (android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) email else ""
                resetEmailError = null
                resetSuccessMessage = null
                showResetDialog = true
            },
            modifier = Modifier.align(Alignment.End),
            enabled = !isLoading && !isResettingPassword // Disable if loading or resetting
        ) {
            Text(stringResource(R.string.login_forgot_password_button))
        }
        Spacer(modifier = Modifier.height(16.dp))

        // General Error Message Area
        generalError?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Login Button
        Button(
            onClick = {
                // 1. Clear previous errors
                emailError = null
                passwordError = null
                generalError = null

                // 2. Basic Client-Side Validation
                var isValid = true
                if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    emailError = context.getString(R.string.validation_invalid_email)
                    isValid = false
                }
                if (password.isBlank()) {
                    passwordError = context.getString(R.string.validation_password_empty)
                    isValid = false
                }

                if (isValid) {
                    isLoading = true
                    Log.d(TAG, "Client validation passed. Attempting Firebase login for: $email")

                    // 3. Firebase Authentication Attempt
                    auth.signInWithEmailAndPassword(email, password)
                        .addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                val user = auth.currentUser
                                val userId = user?.uid
                                Log.i(TAG, "Firebase signIn successful for User ID: $userId")

                                if (userId != null) {
                                    // 4. Fetch Study State from Firestore via Repository
                                    coroutineScope.launch { // Use launch for suspend function call
                                        val fetchSuccess = repository.fetchAndSaveStateFromFirestore(userId)
                                        isLoading = false // Stop loading indicator *after* fetch attempt

                                        if (fetchSuccess) {
                                            Log.i(TAG, "Successfully fetched and saved state for User $userId. Navigating to Home.")
                                            // Navigate to home only after state is fetched and saved
                                            navController.navigate(Screen.Home.route) {
                                                // Clear back stack up to AuthDecision (inclusive)
                                                popUpTo(Screen.AuthDecision.route) { inclusive = true }
                                                launchSingleTop = true // Avoid multiple Home instances
                                            }
                                        } else {
                                            // Handle case where Firestore data couldn't be fetched/found
                                            Log.e(TAG, "Failed to fetch/save state for User $userId after login. Logging out.")
                                            generalError = context.getString(R.string.login_error_load_study_data)
                                            // Log out the user for safety, as their state isn't usable
                                            auth.signOut()
                                            // Clear any potentially partially saved local state
                                            // *** CORRECTION: Wrap suspend function call in launch ***
                                            coroutineScope.launch { repository.clearStudyState() }
                                            // *** END CORRECTION ***
                                        }
                                    } // End coroutineScope.launch for fetch
                                } else {
                                    // Should not happen if task.isSuccessful, but handle defensively
                                    Log.e(TAG, "Firebase signIn successful but user or UID is null.")
                                    generalError = context.getString(R.string.login_error_session_verification)
                                    isLoading = false
                                }
                            } else {
                                // Handle Firebase Sign In Failure
                                Log.w(TAG, "Firebase signIn failed for email: $email", task.exception)
                                generalError = when (task.exception) {
                                    is FirebaseAuthInvalidUserException,
                                    is FirebaseAuthInvalidCredentialsException ->
                                        context.getString(R.string.login_error_invalid_credentials)
                                    // Add other specific Firebase exceptions if needed
                                    else -> context.getString(R.string.error_generic_message) // Generic fallback
                                }
                                isLoading = false
                            }
                        } // End addOnCompleteListener
                } else {
                    Log.w(TAG, "Client validation failed for login.")
                    isLoading = false // Ensure loading is off if validation fails
                }
            }, // End onClick
            enabled = !isLoading, // Disable button while loading
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary // Ensure indicator is visible on button
                )
            } else {
                Text(stringResource(R.string.login_button))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Back Button (to AuthDecision)
        TextButton(
            onClick = { if (!isLoading) navController.popBackStack() }, // Go back if not loading
            enabled = !isLoading
        ) {
            Text(stringResource(R.string.back))
        }
    } // End Column
}

/**
 * Composable function for the Password Reset Dialog.
 * (Implementation unchanged from previous correct version)
 */
@Composable
private fun PasswordResetDialog(
    initialEmail: String,
    isResetting: Boolean,
    resetEmailError: String?,
    onDismissRequest: () -> Unit,
    onSendRequest: (email: String) -> Unit
) {
    var resetEmail by rememberSaveable { mutableStateOf(initialEmail) }
    var currentError by remember { mutableStateOf(resetEmailError) }
    val context = LocalContext.current

    LaunchedEffect(resetEmailError) {
        currentError = resetEmailError
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.login_reset_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.login_reset_dialog_message))
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = resetEmail,
                    onValueChange = {
                        resetEmail = it.trim()
                        currentError = null // Clear error on change
                    },
                    label = { Text(stringResource(R.string.email_address_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true,
                    isError = currentError != null,
                    enabled = !isResetting,
                    modifier = Modifier.fillMaxWidth()
                )
                currentError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (resetEmail.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(resetEmail).matches()) {
                        currentError = context.getString(R.string.validation_invalid_email)
                    } else {
                        currentError = null
                        onSendRequest(resetEmail)
                    }
                },
                enabled = !isResetting
            ) {
                if (isResetting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.login_reset_dialog_send_button))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest, enabled = !isResetting) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}