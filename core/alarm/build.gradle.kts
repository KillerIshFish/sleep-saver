// Alarm machinery: AlarmManager arming, receivers, the ring and night services, tone/noise synthesis and the
// device-protected mirror. No Compose and no core:ui; it reaches the app only through core:data's hooks.
plugins {
    alias(libs.plugins.sleepsaver.android.library)
    alias(libs.plugins.sleepsaver.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.sleepsaver.core.alarm"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:testing"))
}
