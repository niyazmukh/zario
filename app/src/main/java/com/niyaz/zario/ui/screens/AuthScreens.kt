package com.niyaz.zario.ui.screens


// import com.example.studyuiapp.data.state.StudyStateManager // No longer directly needed here
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
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.niyaz.zario.R
import com.niyaz.zario.StudyPhase
import com.niyaz.zario.data.local.AppDatabase
import com.niyaz.zario.data.repository.StudyRepository
import com.niyaz.zario.data.repository.StudyRepositoryImpl
import com.niyaz.zario.navigation.Screen
import com.niyaz.zario.utils.Constants
import com.niyaz.zario.utils.StudyStateManager
import kotlinx.coroutines.launch
import java.util.Calendar




@Composable
fun AuthDecisionScreen(navController: NavController) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Correct: stringResource called within Composable context
        Text(stringResource(R.string.auth_decision_welcome))
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = { navController.navigate(Screen.Login.route) }) {
            // Correct: stringResource called within Composable context (Button content lambda)
            Text(stringResource(R.string.auth_decision_login_button))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { navController.navigate(Screen.Register.route) }) {
            // Correct: stringResource called within Composable context
            Text(stringResource(R.string.auth_decision_register_button))
        }
    }
}


@Composable
fun LoginScreen(navController: NavController) {
    // --- State ---
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var generalError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    // --- NEW State for Reset Dialog ---
    var showResetDialog by remember { mutableStateOf(false) }
    var resetEmail by rememberSaveable { mutableStateOf("") } // Pre-fill with login email if available
    var resetEmailError by remember { mutableStateOf<String?>(null) }
    var isResetting by remember { mutableStateOf(false) }
    // --- End Reset Dialog State ---


    val context = LocalContext.current // Get context for non-composable string access
    val auth: FirebaseAuth = Firebase.auth
    val coroutineScope = rememberCoroutineScope()


    // --- NEW: Reset Password Dialog ---
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { if (!isResetting) showResetDialog = false },
            // Correct: stringResource called within Composable context (title lambda)
            title = { Text(stringResource(R.string.login_reset_dialog_title)) },
            text = {
                Column {
                    // Correct: stringResource called within Composable context
                    Text(stringResource(R.string.login_reset_dialog_message))
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = resetEmail,
                        onValueChange = { resetEmail = it; resetEmailError = null },
                        // Correct: stringResource called within Composable context (label lambda)
                        label = { Text(stringResource(R.string.email_address_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        isError = resetEmailError != null,
                        enabled = !isResetting
                    )
                    if (resetEmailError != null) {
                        // Error message state is just a String, display it directly
                        Text(resetEmailError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        // Correct: Use context.getString in non-composable onClick lambda
                        if (resetEmail.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(resetEmail).matches()) {
                            resetEmailError = context.getString(R.string.validation_invalid_email)
                        } else {
                            resetEmailError = null
                            isResetting = true
                            auth.sendPasswordResetEmail(resetEmail)
                                .addOnCompleteListener { task ->
                                    isResetting = false
                                    if (task.isSuccessful) {
                                        Log.d("LoginScreen", "Password reset email sent to $resetEmail")
                                        // Correct: Use context.getString for Toast message
                                        Toast.makeText(context, context.getString(R.string.login_reset_dialog_toast_success), Toast.LENGTH_LONG).show()
                                        showResetDialog = false
                                    } else {
                                        Log.w("LoginScreen", "sendPasswordResetEmail failure", task.exception)
                                        // Correct: Use context.getString in non-composable listener lambda
                                        resetEmailError = context.getString(R.string.login_reset_dialog_toast_failure)
                                    }
                                }
                        }
                    },
                    enabled = !isResetting
                ) {
                    if(isResetting) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        // Correct: stringResource called within Composable context (Button content lambda)
                        Text(stringResource(R.string.login_reset_dialog_send_button))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!isResetting) showResetDialog = false }, enabled = !isResetting) {
                    // Correct: stringResource called within Composable context
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    // --- End Reset Password Dialog ---


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Correct: stringResource called within Composable context
        Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))


        // --- Email Field ---
        OutlinedTextField(
            value = email,
            onValueChange = { email = it.trim(); emailError = null; generalError = null },
            // Correct: stringResource called within Composable context (label lambda)
            label = { Text(stringResource(R.string.email_address_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            isError = emailError != null || generalError != null,
            modifier = Modifier.fillMaxWidth()
        )
        if (emailError != null) {
            // Error message state is just a String, display it directly
            Text(emailError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(16.dp))


        // --- Password Field ---
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; passwordError = null; generalError = null },
            // Correct: stringResource called within Composable context (label lambda)
            label = { Text(stringResource(R.string.password_label)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            isError = passwordError != null || generalError != null,
            modifier = Modifier.fillMaxWidth()
        )
        if (passwordError != null) {
            // Error message state is just a String, display it directly
            Text(passwordError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(8.dp))


        // --- Forgot Password Button ---
        TextButton(
            onClick = {
                resetEmail = if (android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) email else ""
                resetEmailError = null
                showResetDialog = true
            },
            modifier = Modifier.align(Alignment.End),
            enabled = !isLoading && !isResetting
        ) {
            // Correct: stringResource called within Composable context
            Text(stringResource(R.string.login_forgot_password_button))
        }
        Spacer(modifier = Modifier.height(16.dp))


        // --- Display General Login Error ---
        if (generalError != null) {
            // Error message state is just a String, display it directly
            Text(generalError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(8.dp))
        }


        // --- Login Button ---
        Button(
            onClick = {
                generalError = null // Clear previous errors
                // --- Basic Validation ---
                var isValid = true
                // Correct: Use context.getString in non-composable onClick lambda
                if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) { emailError = context.getString(R.string.validation_invalid_email); isValid = false } else { emailError = null }
                if (password.isBlank()) { passwordError = context.getString(R.string.validation_password_empty); isValid = false } else { passwordError = null }
                // --- End Validation ---


                if (isValid) {
                    isLoading = true
                    Log.d("LoginScreen", "Client validation passed. Attempting Firebase login for: $email")


                    // --- Firebase Login Logic ---
                    auth.signInWithEmailAndPassword(email, password)
                        .addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                Log.d("LoginScreen", "signInWithEmail:success")
                                val user = auth.currentUser
                                val userId = user?.uid


                                if (userId != null) {
                                    // --- Fetch State From Firestore ---
                                    coroutineScope.launch { // Launch fetch in coroutine scope
                                        val fetchSuccess = StudyStateManager.fetchAndSaveStateFromFirestore(context, userId)
                                        isLoading = false // Hide loading after fetch attempt


                                        if (fetchSuccess) {
                                            // Navigate to home only after state is fetched and saved
                                            navController.navigate(Screen.Home.route) {
                                                popUpTo(Screen.AuthDecision.route) { inclusive = true }
                                                launchSingleTop = true
                                            }
                                        } else {
                                            // Handle case where Firestore data couldn't be fetched/found
                                            // Correct: Use context.getString in non-composable coroutine scope
                                            generalError = context.getString(R.string.login_error_load_study_data)
                                            // Log out the user since their state isn't usable
                                            auth.signOut()
                                        }
                                    }
                                    // Note: isLoading = false is now inside the coroutine scope after fetch


                                } else {
                                    // Should not happen if task successful, but handle defensively
                                    Log.w("LoginScreen", "Sign in successful but user or UID is null.")
                                    // Correct: Use context.getString in non-composable listener lambda
                                    generalError = context.getString(R.string.login_error_session_verification)
                                    isLoading = false
                                }


                            } else {
                                // If sign in fails
                                Log.w("LoginScreen", "signInWithEmail:failure", task.exception)
                                // Correct: Use context.getString in non-composable listener lambda
                                generalError = when (task.exception) {
                                    is FirebaseAuthInvalidUserException, // User doesn't exist
                                    is FirebaseAuthInvalidCredentialsException -> // Wrong password
                                        context.getString(R.string.login_error_invalid_credentials)
                                    else -> context.getString(R.string.error_generic_message) // Generic fallback
                                }
                                isLoading = false
                            }
                        }
                    // --- End Firebase Login Logic ---


                } else {
                    Log.w("LoginScreen", "Client validation failed.")
                    isLoading = false // Ensure loading is off
                }
            },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                // Correct: stringResource called within Composable context
                Text(stringResource(R.string.login_button))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))


        // --- Back Button ---
        TextButton(onClick = { if (!isLoading) navController.popBackStack() }, enabled = !isLoading) {
            // Correct: stringResource called within Composable context
            Text(stringResource(R.string.back))
        }
    }
}




@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(navController: NavController) {
    // State variables
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
    var generalError by remember { mutableStateOf<String?>(null) } // Added for error display


    val context = LocalContext.current // Get context
    val auth: FirebaseAuth = Firebase.auth
    // Removed direct firestore instance: val firestore: FirebaseFirestore = Firebase.firestore
    val coroutineScope = rememberCoroutineScope()


    // --- FIX: Instantiate Repository directly within the Composable ---
    val repository: StudyRepository = remember { // Use remember to avoid recreating on recomposition
        StudyRepositoryImpl(
            context = context.applicationContext, // Use application context
            // Assuming AppDatabase has a static getDatabase method
            usageStatDao = AppDatabase.getDatabase(context.applicationContext).usageStatDao(),
            firestore = Firebase.firestore // Pass Firestore instance to Repository
        )
    }
    // --- End Repository Instantiation ---




    // Define gender options list using resources
    // Correct: stringResource called within Composable context during initialization
    val genderOptions = listOf(
        stringResource(R.string.register_gender_male),
        stringResource(R.string.register_gender_female),
        stringResource(R.string.register_gender_other),
        stringResource(R.string.register_gender_prefer_not_say)
    )




    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()), // Make column scrollable
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Correct: stringResource called within Composable context
        Text(stringResource(R.string.register_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))


        // --- Year of Birth Field ---
        OutlinedTextField(
            value = yearOfBirth,
            onValueChange = {
                if (it.all { char -> char.isDigit() } && it.length <= 4) {
                    yearOfBirth = it
                    yearError = null // Clear error state
                }
            },
            // Correct: stringResource called within Composable context (label lambda)
            label = { Text(stringResource(R.string.register_yob_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = yearError != null,
            modifier = Modifier.fillMaxWidth()
        )
        if (yearError != null) {
            // Error message state is just a String, display it directly
            Text(yearError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(16.dp))


        // --- Gender Dropdown ---
        ExposedDropdownMenuBox(
            expanded = genderExpanded,
            onExpandedChange = { genderExpanded = !genderExpanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = gender, // Display the selected gender
                onValueChange = { }, // Not directly changeable
                readOnly = true,
                // Correct: stringResource called within Composable context (label lambda)
                label = { Text(stringResource(R.string.register_gender_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = genderExpanded) },
                modifier = Modifier
                    .menuAnchor() // Important for positioning the dropdown
                    .fillMaxWidth(),
                isError = genderError != null
            )
            ExposedDropdownMenu(
                expanded = genderExpanded,
                onDismissRequest = { genderExpanded = false }
            ) {
                // genderOptions already contains localized strings
                genderOptions.forEach { selectionOption ->
                    DropdownMenuItem(
                        text = { Text(selectionOption) },
                        onClick = {
                            gender = selectionOption
                            genderExpanded = false
                            genderError = null // Clear error state
                        }
                    )
                }
            }
        }
        if (genderError != null) {
            // Error message state is just a String, display it directly
            Text(genderError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(16.dp))


        // --- Email Field ---
        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it.trim() // Trim input
                emailError = null // Clear error state
            },
            // Correct: stringResource called within Composable context (label lambda)
            label = { Text(stringResource(R.string.email_address_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            isError = emailError != null,
            modifier = Modifier.fillMaxWidth()
        )
        if (emailError != null) {
            // Error message state is just a String, display it directly
            Text(emailError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(16.dp))


        // --- Password Field ---
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                passwordError = null // Clear error state
            },
            // Correct: stringResource called within Composable context (label lambda)
            label = { Text(stringResource(R.string.password_label)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            isError = passwordError != null,
            modifier = Modifier.fillMaxWidth()
        )
        if (passwordError != null) {
            // Error message state is just a String, display it directly
            Text(passwordError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(16.dp))


        // --- Confirm Password Field ---
        OutlinedTextField(
            value = confirmPassword,
            onValueChange = {
                confirmPassword = it
                confirmPasswordError = null // Clear error state
            },
            // Correct: stringResource called within Composable context (label lambda)
            label = { Text(stringResource(R.string.register_confirm_password_label)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            isError = confirmPasswordError != null,
            modifier = Modifier.fillMaxWidth()
        )
        if (confirmPasswordError != null) {
            // Error message state is just a String, display it directly
            Text(confirmPasswordError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(16.dp))


        // Display general Firebase error message
        if (generalError != null) {
            // Error message state is just a String, display it directly
            Text(generalError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(8.dp))
        }


        // --- Register Button ---
        Button(
            onClick = {
                generalError = null // Reset general error on new attempt
                // --- Basic Validation ---
                var isValid = true // Reset validity check
                val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                val birthYear = yearOfBirth.toIntOrNull()
                // Correct: Use context.getString in non-composable onClick lambda
                // Use constant for min birth year
                if (yearOfBirth.isBlank() || birthYear == null || birthYear < Constants.MIN_BIRTH_YEAR || birthYear > currentYear) { yearError = context.getString(R.string.register_validation_invalid_year); isValid = false } else { yearError = null }
                if (gender.isBlank()) { genderError = context.getString(R.string.register_validation_gender_required); isValid = false } else { genderError = null }
                if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) { emailError = context.getString(R.string.validation_invalid_email); isValid = false } else { emailError = null }
                // Use constant for min password length
                if (password.length < Constants.MIN_PASSWORD_LENGTH) { passwordError = context.getString(R.string.register_validation_password_length); isValid = false } else { passwordError = null }
                if (confirmPassword != password) { confirmPasswordError = context.getString(R.string.register_validation_password_mismatch); isValid = false } else { confirmPasswordError = null }
                // --- End Basic Validation ---


                if (isValid) {
                    isLoading = true
                    Log.i("RegisterScreen", "Client validation passed. Attempting Firebase registration for: $email")


                    // --- Firebase Registration Logic ---
                    auth.createUserWithEmailAndPassword(email, password)
                        .addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                // Sign in success
                                Log.d("RegisterScreen", "createUserWithEmail:success")
                                val user = auth.currentUser
                                val userId = user?.uid


                                if (userId != null) {
                                    // --- Call Repository Method ---
                                    val studyStartTime = System.currentTimeMillis() // Defined earlier in original code
                                    val assignedCondition = listOf(StudyPhase.INTERVENTION_CONTROL, StudyPhase.INTERVENTION_DEPOSIT, StudyPhase.INTERVENTION_FLEXIBLE).random() // Defined earlier


                                    coroutineScope.launch { // Keep coroutineScope for suspend function call
                                        try {
                                            // Prepare the profile data map (using constants) - Defined ONCE here
                                            val userData = hashMapOf<String, Any?>(
                                                Constants.FIRESTORE_FIELD_EMAIL to email,
                                                Constants.FIRESTORE_FIELD_YOB to yearOfBirth.toIntOrNull(),
                                                Constants.FIRESTORE_FIELD_GENDER to gender, // Keep localized string value
                                                Constants.FIRESTORE_FIELD_REG_TIMESTAMP to studyStartTime,
                                                Constants.FIRESTORE_FIELD_STUDY_PHASE to StudyPhase.BASELINE.name, // Value is enum name
                                                Constants.FIRESTORE_FIELD_STUDY_CONDITION to assignedCondition.name, // Value is enum name
                                                Constants.FIRESTORE_FIELD_STUDY_START_TIMESTAMP to studyStartTime,
                                                Constants.FIRESTORE_FIELD_POINTS_BALANCE to Constants.INITIAL_POINTS.toLong(), // Value uses Constant
                                                Constants.FIRESTORE_FIELD_TARGET_APP to null, // Key uses Constant
                                                Constants.FIRESTORE_FIELD_DAILY_GOAL to null, // Key uses Constant
                                                Constants.FIRESTORE_FIELD_FLEX_EARN to null, // Key uses Constant
                                                Constants.FIRESTORE_FIELD_FLEX_LOSE to null // Key uses Constant
                                            )


                                            // *** USE THE INSTANTIATED REPOSITORY ***
                                            // No direct Firestore write here anymore
                                            val initResult = repository.initializeNewUser(
                                                userId = userId,
                                                userProfileData = userData,            // Map for Firestore
                                                initialPhase = StudyPhase.BASELINE,    // Enum for local state
                                                assignedCondition = assignedCondition, // Enum for local state
                                                initialPoints = Constants.INITIAL_POINTS, // Int for local state
                                                registrationTimestamp = studyStartTime // Long for local state
                                            )


                                            if (initResult.isSuccess) {
                                                Log.d("RegisterScreen", "New user initialized successfully via Repository.")
                                                isLoading = false
                                                // Navigate to Home
                                                navController.navigate(Screen.Home.route) {
                                                    popUpTo(Screen.AuthDecision.route) { inclusive = true }
                                                    launchSingleTop = true
                                                }
                                            } else {
                                                // Repository method failed (Firestore or Local save)
                                                Log.w("RegisterScreen", "Error initializing user via Repository", initResult.exceptionOrNull())
                                                generalError = context.getString(R.string.register_error_profile_save)
                                                // Attempt to delete the partially created Auth user for cleanup
                                                user?.delete()?.addOnCompleteListener { deleteTask -> // Use non-null user reference
                                                    Log.d("RegisterScreen", "Attempted to delete auth user after repository init failure. Success: ${deleteTask.isSuccessful}")
                                                }
                                                isLoading = false
                                            }


                                        } catch (e: Exception) {
                                            // Catch potential exceptions from repository access itself or other logic
                                            Log.e("RegisterScreen", "Error during registration repository interaction", e)
                                            generalError = context.getString(R.string.register_error_profile_save)
                                            user?.delete()?.addOnCompleteListener { deleteTask -> // Use non-null user reference
                                                Log.d("RegisterScreen", "Attempted to delete auth user after general exception. Success: ${deleteTask.isSuccessful}")
                                            }
                                            isLoading = false
                                        }
                                    } // End coroutineScope.launch


                                } else { // Handle userId == null
                                    Log.w("RegisterScreen", "User creation task successful but user or UID is null.")
                                    // Correct: Use context.getString in non-composable listener lambda
                                    generalError = context.getString(R.string.register_error_user_id)
                                    isLoading = false
                                    // Consider attempting user deletion here too if needed, though auth task succeeded.
                                }


                            } else { // Handle task failure (Firebase Auth createUser failed)
                                // If sign in fails
                                Log.w("RegisterScreen", "createUserWithEmail:failure", task.exception)
                                // Correct: Use context.getString in non-composable listener lambda
                                generalError = when (task.exception) {
                                    is FirebaseAuthUserCollisionException -> context.getString(R.string.register_error_email_collision)
                                    // Add more specific FirebaseAuthException checks if needed
                                    else -> context.getString(R.string.error_generic_message) // Generic fallback
                                }
                                isLoading = false // Set loading false on auth failure
                            }
                        } // End addOnCompleteListener
                    // --- End Firebase Registration Logic ---


                } else { // Handle isValid == false
                    Log.w("RegisterScreen", "Client validation failed.")
                    // Ensure loading indicator stops if validation fails before attempting registration
                    isLoading = false
                }
            }, // End onClick lambda
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                // Correct: stringResource called within Composable context
                Text(stringResource(R.string.register_button))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))


        // --- Back Button ---
        TextButton(onClick = { if (!isLoading) navController.popBackStack() }, enabled = !isLoading) {
            // Correct: stringResource called within Composable context
            Text(stringResource(R.string.back))
        }
    } // End Column
}

