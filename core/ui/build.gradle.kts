plugins {
    alias(libs.plugins.sleepsaver.android.library.compose)
}

android {
    namespace = "com.sleepsaver.core.ui"
    // Screenshot helpers (captureScreen/captureComponent) shared with every Compose module's tests.
    testFixtures { enable = true }
}

dependencies {
    api(project(":core:domain"))
    implementation(libs.androidx.core.ktx)

    testFixturesImplementation(platform(libs.androidx.compose.bom))
    testFixturesImplementation(libs.androidx.compose.foundation)
    testFixturesImplementation(libs.androidx.compose.material3)
    testFixturesApi(libs.junit4)
    testFixturesApi(libs.robolectric)
    testFixturesApi(libs.roborazzi)
    testFixturesApi(libs.roborazzi.compose)
}
