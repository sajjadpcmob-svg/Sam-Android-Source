plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "ir.elviran.sam"
    compileSdk = 36
    defaultConfig { applicationId = "ir.elviran.sam"; minSdk = 29; targetSdk = 36; versionCode = 6; versionName = "3.3"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }; debug { applicationIdSuffix = ".debug"; isDebuggable = true } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(bom); androidTestImplementation(bom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.compose.ui:ui"); implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3"); implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.room:room-runtime:2.8.2"); implementation("androidx.room:room-ktx:2.8.2"); ksp("androidx.room:room-compiler:2.8.2")
    implementation("androidx.datastore:datastore-preferences:1.1.7"); implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.security:security-crypto:1.1.0"); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.1.0"); implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Offline on-device Persian speech recognition + wake-word (Apache-2.0). The model is downloaded at runtime.
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    testImplementation("junit:junit:4.13.2"); testImplementation("org.json:json:20231013"); androidTestImplementation("androidx.test.ext:junit:1.3.0"); androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0"); androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling"); debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// ---------------------------------------------------------------------------
// OPTIONAL on-device llama.cpp native build (SAM offline LLM).
// Disabled by default so the normal `assembleDebug` keeps working unchanged.
// Enable on CI / locally with:   ./gradlew assembleDebug -PsamLlama
// It builds app/src/main/cpp/CMakeLists.txt into libsamllama.so for arm64-v8a.
// When OFF, LlamaNative.available stays false and SAM falls back to the
// online / command engines (no crash, no behaviour change).
// ---------------------------------------------------------------------------
if (project.hasProperty("samLlama")) {
    android {
        defaultConfig {
            ndk { abiFilters += listOf("arm64-v8a") }
        }
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}
