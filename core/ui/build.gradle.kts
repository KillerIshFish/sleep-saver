plugins {
    alias(libs.plugins.sleepsaver.android.library.compose)
}

android {
    namespace = "com.sleepsaver.core.ui"
}

dependencies {
    api(project(":core:domain"))
    implementation(libs.androidx.core.ktx)
}
