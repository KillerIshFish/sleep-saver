plugins {
    alias(libs.plugins.sleepsaver.android.application)
    alias(libs.plugins.sleepsaver.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.sleepsaver.app"

    defaultConfig {
        applicationId = "com.sleepsaver.app"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so a release build can be sideloaded. Use a real key before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(project(":core:notifications"))
    implementation(project(":core:alarm"))
    implementation(project(":feature:sleep"))
    implementation(project(":feature:energy"))
    implementation(project(":feature:progress"))
    implementation(project(":feature:review"))
    implementation(project(":feature:habits"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:learn"))
    implementation(project(":feature:widget"))
    implementation(project(":feature:tonight"))
    implementation(project(":feature:winddown"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)
}
