package com.sleepsaver.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

internal const val COMPILE_SDK = 37
internal const val TARGET_SDK = 37
internal const val MIN_SDK = 28

internal fun Project.configureAndroidCommon(extension: CommonExtension) {
    extension.compileSdk = COMPILE_SDK
    extension.defaultConfig.minSdk = MIN_SDK
    extension.defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    extension.compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    extension.compileOptions.targetCompatibility = JavaVersion.VERSION_17
    extension.testOptions.unitTests.isIncludeAndroidResources = true
    extension.testOptions.unitTests.isReturnDefaultValues = true
    extension.lint.abortOnError = false
    configureKotlinCompile()
}

internal fun Project.configureKotlinCompile(extraOptIns: List<String> = emptyList()) {
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            optIn.addAll(extraOptIns)
        }
    }
}

internal val COMPOSE_OPT_INS = listOf(
    "androidx.compose.material3.ExperimentalMaterial3Api",
    "androidx.compose.foundation.layout.ExperimentalLayoutApi",
    "androidx.compose.animation.ExperimentalAnimationApi",
    "kotlinx.coroutines.ExperimentalCoroutinesApi",
    "kotlinx.coroutines.FlowPreview",
)
