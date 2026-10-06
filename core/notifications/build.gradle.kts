plugins {
    alias(libs.plugins.sleepsaver.android.library)
    alias(libs.plugins.sleepsaver.hilt)
}

android {
    namespace = "com.sleepsaver.core.notifications"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
