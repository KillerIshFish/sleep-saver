plugins {
    alias(libs.plugins.sleepsaver.android.feature)
}

android {
    namespace = "com.sleepsaver.feature.onboarding"
}

tasks.withType<Test>().configureEach {
    // ~30 Roborazzi screens on native graphics plus Compose UI tests share one Robolectric JVM: Gradle's default
    // 512 MB test heap runs out part-way through.
    maxHeapSize = "1g"
}
