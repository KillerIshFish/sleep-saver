// Fakes and sample data shared by feature-module tests and screenshot tests.
plugins {
    alias(libs.plugins.sleepsaver.android.library)
}

android {
    namespace = "com.sleepsaver.core.testing"
}

dependencies {
    api(project(":core:domain"))
    api(project(":core:data"))
    api(libs.kotlinx.coroutines.test)
    implementation(libs.androidx.core.ktx)
}
