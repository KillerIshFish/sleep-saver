plugins {
    alias(libs.plugins.sleepsaver.android.library)
    alias(libs.plugins.sleepsaver.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.sleepsaver.core.data"
}

// MigrationTest (MigrationTestHelper) reads the exported Room schemas as test assets. Configured through the new DSL
// type: the `android` accessor's legacy source-set type can't be cast under AGP 9.
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    sourceSets.getByName("test").assets.directories.add("$projectDir/schemas")
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
