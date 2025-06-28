        plugins {
            alias(libs.plugins.android.application)
            alias(libs.plugins.kotlin.android)
            alias(libs.plugins.compose.compiler) // <<< ADD THIS LINE
            alias(libs.plugins.kotlin.devtools.ksp) // <<< ADD THIS LINE
            alias(libs.plugins.google.services) // <<< ADD THIS LINE




        }


android {
    namespace = "com.niyaz.zario"
    compileSdk = 35 // Keep this as 35


    defaultConfig {
        applicationId = "com.niyaz.zario"
        minSdk = 26 // Minimum SDK required
        targetSdk = 35 // Target SDK should match compileSdk
        versionCode = 1
        versionName = "1.0"


        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Vector Drawables are generally preferred
        vectorDrawables {
            useSupportLibrary = true
        }
    }


    buildTypes {
        release {
            isMinifyEnabled = false // Keep false for now during development
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    // Enable Compose features
    buildFeatures {
        compose = true
    }


    // Packaging options often needed for Compose
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}


dependencies {


    implementation(libs.androidx.core.ktx)
    // Lifecycle dependency needed by Compose Activity
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("com.google.android.material:material:1.12.0")


    // --- Jetpack Compose Dependencies ---
    // Integration with Activities
    implementation(libs.androidx.activity.compose)
    // Core Compose UI elements
    implementation(platform(libs.androidx.compose.bom)) // Import the Compose BOM
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    // Compose Tooling support (Previews)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling) // Tooling needed for inspection in debug builds
    // Compose Material 3 Design
    implementation(libs.androidx.compose.material3)
    // Compose Navigation (for moving between screens)
    implementation(libs.androidx.navigation.compose)
    // Compose ViewModel integration (for state management)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // --- End Jetpack Compose Dependencies ---


    // ViewModel Compose Integration
    implementation(libs.androidx.lifecycle.viewmodel.compose) // Already added earlier, ensure it's correct alias


    implementation(libs.androidx.work.runtime.ktx) // Add WorkManager
    implementation(libs.coil.compose)


    // --- Firebase ---
    // Import the BoM for version management
    implementation(platform(libs.firebase.bom))
    // Add Analytics (Recommended by Firebase, useful for basic event tracking)
    implementation("com.google.firebase:firebase-analytics-ktx") // Or use alias if defined
    // Add Firebase Authentication library
    implementation(libs.firebase.auth.ktx)
    // Add Cloud Firestore library
    implementation(libs.firebase.firestore.ktx)
    // --- End Firebase ---


    // --- Room Persistence ---
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx) // Include Coroutines support
    // Annotation processor using KSP
    ksp(libs.androidx.room.compiler) // Use ksp() instead of annotationProcessor()
    // --- End Room Persistence ---


    // Remove AppCompat if you are fully migrating to Compose for UI
    // implementation(libs.androidx.appcompat) // Commented out/Removed
    // Remove old Material Components if using Material 3 Compose
    // implementation(libs.material) // Commented out/Removed


    // --- Testing Dependencies ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // AndroidJUnit4 Runner is needed for instrumented tests with Compose
    androidTestImplementation(libs.androidx.runner)
    // Compose UI Testing specific dependencies
    androidTestImplementation(platform(libs.androidx.compose.bom)) // Use BOM for testing too
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest) // Manifest for testing






}