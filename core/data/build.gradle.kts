plugins {
    alias(libs.plugins.sleepsaver.android.library)
    alias(libs.plugins.sleepsaver.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.sleepsaver.core.data"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    api(project(":core:domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    api(libs.androidx.health.connect)
    api(libs.androidx.activity.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
}
