package com.niyaz.zario.ui.screens

import android.util.Log
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.niyaz.zario.R
import com.niyaz.zario.StudyPhase
import com.niyaz.zario.data.local.AppDatabase // Needed for direct repo instantiation
import com.niyaz.zario.data.repository.StudyRepository
import com.niyaz.zario.data.repository.StudyRepositoryImpl
import com.niyaz.zario.navigation.Screen
import com.niyaz.zario.utils.Constants // Import Constants
import kotlinx.coroutines.launch // Ensure launch is imported
import java.util.Calendar
import kotlin.random.Random // For random condition assignment

/**
 * Composable function for the Registration Screen.
 *
 * Allows new users to create an account for the study by providing their
 * year of birth, gender, email, and password. Performs validation, handles
 * account creation via Firebase Authentication, and initializes the user's
 * profile and study state via the repository.
 *
 * @param navController The [NavController] used for navigation (back to AuthDecision or forward to Home).
 * @comment Repository is instantiated directly here. In a production app, consider using
 *          Dependency Injection (e.g., Hilt) for better testability and decoupling.
 */
@OptIn(ExperimentalMaterial3Api::class) // For ExposedDropdownMenuBox
@Composable
fun RegisterScreen(navController: NavController) {
    // --- State Variables ---
    var yearOfBirth by rememberSaveable { mutableStateOf("") }
    var gender by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var yearError by remember { mutableStateOf<String?>(null) }
    var genderError by remember { mutableStateOf<String?>(null) }
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var confirmPasswordError by remember { mutableStateOf<String?>(null) }
    var genderExpanded by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var generalError by remember { mutableStateOf<String?>(null) } // For Firebase/logic errors

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
    val TAG = "RegisterScreen" // For logging

    // Gender options list - Defined within Composable context using stringResource
    val genderOptions = listOf(
        stringResource(R.string.register_gender_male),
        stringResource(R.string.register_gender_female),
        stringResource(R.string.register_gender_other),
        stringResource(R.string.register_gender_prefer_not_say)
    )

    // --- Main Registration Screen UI ---
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()), // Make column scrollable
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Title
        Text(
            text = stringResource(R.string.register_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(24.dp))

        // Year of Birth Field
        OutlinedTextField(
            value = yearOfBirth,
            onValueChange = { input ->
                // Allow only digits, max 4 characters
                if (input.all { it.isDigit() } && input.length <= 4) {
                    yearOfBirth = input
                    yearError = null // Clear error on valid input change
                    generalError = null
                }
            },
            label = { Text(stringResource(R.string.register_yob_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = yearError != null,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        )
        yearError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Gender Dropdown (ExposedDropdownMenuBox)
        ExposedDropdownMenuBox(
            expanded = genderExpanded,
            onExpandedChange = { genderExpanded = !genderExpanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField( // ReadOnly text field acting as dropdown anchor
                value = gender, // Display selected gender
                onValueChange = { }, // Input ignored as readOnly
                readOnly = true,
                label = { Text(stringResource(R.string.register_gender_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = genderExpanded) },
                modifier = Modifier
                    .menuAnchor() // Essential modifier for anchoring the dropdown menu
                    .fillMaxWidth(),
                isError = genderError != null,
                enabled = !isLoading
            )
            ExposedDropdownMenu(
                expanded = genderExpanded,
                onDismissRequest = { genderExpanded = false } // Close dropdown when clicked outside
            ) {
                // Create menu items from the gender options list
                genderOptions.forEach { selectionOption ->
                    DropdownMenuItem(
                        text = { Text(selectionOption) },
                        onClick = {
                            gender = selectionOption
                            genderExpanded = false // Close dropdown after selection
                            genderError = null // Clear error on selection
                            generalError = null
                        }
                    )
                }
            }
        }
        genderError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Email Field
        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it.trim()
                emailError = null
                generalError = null
            },
            label = { Text(stringResource(R.string.email_address_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            isError = emailError != null,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        )
        emailError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Password Field
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it // Don't trim passwords generally
                passwordError = null
                generalError = null
            },
            label = { Text(stringResource(R.string.password_label)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            isError = passwordError != null,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        )
        passwordError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Confirm Password Field
        OutlinedTextField(
            value = confirmPassword,
            onValueChange = {
                confirmPassword = it
                confirmPasswordError = null
                generalError = null
            },
            label = { Text(stringResource(R.string.register_confirm_password_label)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            isError = confirmPasswordError != null,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        )
        confirmPasswordError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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

        // Register Button
        Button(
            onClick = {
                // 1. Clear previous errors
                yearError = null
                genderError = null
                emailError = null
                passwordError = null
                confirmPasswordError = null
                generalError = null

                // 2. Client-Side Validation
                var isValid = true
                val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                val birthYear = yearOfBirth.toIntOrNull()

                // Use constants for validation parameters
                if (birthYear == null || birthYear < Constants.MIN_BIRTH_YEAR || birthYear > currentYear) {
                    yearError = context.getString(R.string.register_validation_invalid_year)
                    isValid = false
                }
                if (gender.isBlank()) {
                    genderError = context.getString(R.string.register_validation_gender_required)
                    isValid = false
                }
                if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    emailError = context.getString(R.string.validation_invalid_email)
                    isValid = false
                }
                if (password.length < Constants.MIN_PASSWORD_LENGTH) {
                    passwordError = context.getString(R.string.register_validation_password_length, Constants.MIN_PASSWORD_LENGTH) // Pass length to string
                    isValid = false
                }
                if (confirmPassword != password) {
                    confirmPasswordError = context.getString(R.string.register_validation_password_mismatch)
                    isValid = false
                }

                if (isValid) {
                    isLoading = true
                    Log.d(TAG, "Client validation passed. Attempting Firebase registration for: $email")

                    // 3. Firebase Authentication Attempt
                    auth.createUserWithEmailAndPassword(email, password)
                        .addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                val user = auth.currentUser
                                val userId = user?.uid
                                Log.i(TAG, "Firebase createUser successful for User ID: $userId")

                                if (userId != null) {
                                    // 4. Initialize User Profile and State via Repository
                                    coroutineScope.launch { // Launch coroutine for suspend function call
                                        val registrationTime = System.currentTimeMillis()
                                        // Randomly assign condition
                                        val assignedCondition = listOf(
                                            StudyPhase.INTERVENTION_CONTROL,
                                            StudyPhase.INTERVENTION_DEPOSIT,
                                            StudyPhase.INTERVENTION_FLEXIBLE
                                        ).random(Random(registrationTime)) // Seed random for some determinism if needed

                                        // Prepare data map for Firestore using Constants
                                        val userProfileData = hashMapOf<String, Any?>(
                                            Constants.FIRESTORE_FIELD_EMAIL to email,
                                            Constants.FIRESTORE_FIELD_YOB to birthYear, // Use validated Int
                                            Constants.FIRESTORE_FIELD_GENDER to gender, // Use selected String
                                            Constants.FIRESTORE_FIELD_REG_TIMESTAMP to registrationTime,
                                            Constants.FIRESTORE_FIELD_STUDY_PHASE to StudyPhase.BASELINE.name, // Start in Baseline
                                            Constants.FIRESTORE_FIELD_STUDY_CONDITION to assignedCondition.name,
                                            Constants.FIRESTORE_FIELD_STUDY_START_TIMESTAMP to registrationTime, // Baseline starts now
                                            Constants.FIRESTORE_FIELD_POINTS_BALANCE to Constants.INITIAL_POINTS.toLong(),
                                            Constants.FIRESTORE_FIELD_TARGET_APP to null,
                                            Constants.FIRESTORE_FIELD_DAILY_GOAL to null,
                                            // Initialize flex fields even if not in flex condition yet
                                            Constants.FIRESTORE_FIELD_FLEX_EARN to Constants.FLEX_STAKES_MIN_EARN.toLong(),
                                            Constants.FIRESTORE_FIELD_FLEX_LOSE to Constants.FLEX_STAKES_MIN_LOSE.toLong()
                                        )

                                        // Call repository to initialize (saves to Firestore & local state)
                                        val initResult = repository.initializeNewUser(
                                            userId = userId,
                                            userProfileData = userProfileData,
                                            initialPhase = StudyPhase.BASELINE,
                                            assignedCondition = assignedCondition,
                                            initialPoints = Constants.INITIAL_POINTS,
                                            registrationTimestamp = registrationTime
                                        )

                                        isLoading = false // Stop loading indicator *after* repository call attempt
                                        if (initResult.isSuccess) {
                                            Log.i(TAG, "User $userId initialized successfully via Repository. Navigating to Home.")
                                            // Navigate to Home
                                            navController.navigate(Screen.Home.route) {
                                                popUpTo(Screen.AuthDecision.route) { inclusive = true }
                                                launchSingleTop = true
                                            }
                                        } else {
                                            // Initialization failed (Firestore or Local save)
                                            Log.e(TAG, "User $userId: Repository initialization failed after auth success.", initResult.exceptionOrNull())
                                            generalError = context.getString(R.string.register_error_profile_save)
                                            // CRITICAL: Delete the created Firebase Auth user if profile/state init fails
                                            user.delete().addOnCompleteListener { deleteTask ->
                                                if (deleteTask.isSuccessful) {
                                                    Log.i(TAG, "Successfully deleted orphaned Firebase Auth user $userId.")
                                                } else {
                                                    Log.e(TAG, "Failed to delete orphaned Firebase Auth user $userId.", deleteTask.exception)
                                                }
                                            }
                                        }
                                    } // End coroutineScope.launch
                                } else {
                                    // Should not happen if task.isSuccessful, but handle defensively
                                    Log.e(TAG, "Firebase createUser successful but user or UID is null.")
                                    generalError = context.getString(R.string.register_error_user_id)
                                    isLoading = false
                                }
                            } else {
                                // Handle Firebase Create User Failure
                                Log.w(TAG, "Firebase createUser failed for email: $email", task.exception)
                                generalError = when (task.exception) {
                                    is FirebaseAuthUserCollisionException ->
                                        context.getString(R.string.register_error_email_collision)
                                    // Add other specific Firebase exceptions (e.g., weak password) if needed
                                    else -> context.getString(R.string.register_error_generic) // Use specific register error
                                }
                                isLoading = false
                            }
                        } // End addOnCompleteListener
                } else {
                    Log.w(TAG, "Client validation failed for registration.")
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
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(R.string.register_button))
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