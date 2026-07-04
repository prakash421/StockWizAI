plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.financestreamai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.financestreamai"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // android.util.Log / Uri / TextUtils are not implemented in
            // the JVM test runtime by default — any call throws
            // "Method X in android.util.Y not mocked". Setting this to
            // true makes those framework methods return default values
            // (0 / null / false) instead of throwing, which is exactly
            // what we need for pure-logic tests that happen to log
            // (e.g. AsyncScanPoller's Log.w on transient errors).
            isReturnDefaultValues = true
        }
    }
}

// Forward proxy system-properties (set in root gradle.properties) into
// the forked test JVM so live-backend tests can reach the internet
// through the Intel corporate proxy. Also forwards RUN_LIVE_BACKEND_TESTS
// env var so AsyncScanPollerLiveTest can gate itself.
tasks.withType<Test>().configureEach {
    listOf(
        "http.proxyHost", "http.proxyPort", "http.nonProxyHosts",
        "https.proxyHost", "https.proxyPort", "https.nonProxyHosts",
    ).forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    System.getenv("RUN_LIVE_BACKEND_TESTS")?.let {
        environment("RUN_LIVE_BACKEND_TESTS", it)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    testImplementation(libs.junit)
    // MockWebServer lets us stand up a real HTTP endpoint on localhost
    // and enqueue canned responses (including forced disconnects that
    // simulate DNS/transient IOException blips). Used by
    // AsyncScanPollerTest to prove the poll-loop retry logic actually
    // recovers from mid-scan network errors instead of trusting
    // hand-crafted JSON strings + hope.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.11.0")
    // runTest {} skips coroutine `delay` calls so the poll-loop tests
    // finish in milliseconds instead of the wall-clock-realistic 30-90s.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("io.coil-kt:coil-compose:2.4.0")

    // Encrypted SharedPreferences for API key storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Google Sign-In via Credential Manager
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
