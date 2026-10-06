// Debug tool, not part of Sleep Saver: writes sample sleep into Health Connect on an emulator so the real
// Health Connect read/sync path can be exercised without a Garmin watch. Sleep Saver itself never writes.
plugins {
    alias(libs.plugins.sleepsaver.android.application)
}

android {
    namespace = "com.sleepsaver.hcseeder"
    defaultConfig {
        applicationId = "com.sleepsaver.hcseeder"
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation(project(":core:testing"))
    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
