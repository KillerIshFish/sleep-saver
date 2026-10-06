plugins {
    alias(libs.plugins.sleepsaver.android.feature)
}

android {
    namespace = "com.sleepsaver.feature.widget"
}

dependencies {
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime.ktx)
}
