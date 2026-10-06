package com.sleepsaver.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

private const val UI_PROJECT = ":core:ui"

/**
 * Roborazzi screenshot tests on Robolectric native graphics for every Compose library module.
 *
 * Debug unit-test runs always record: each `captureScreen`/`captureComponent` call (core:ui test fixtures)
 * writes `build/outputs/roborazzi/<name>.png`. The folder is a declared task output, so a test run restored
 * from the build cache restores the PNGs too.
 */
internal fun Project.configureScreenshotTests() {
    dependencies {
        add("testImplementation", libs.lib("roborazzi"))
        add("testImplementation", libs.lib("roborazzi-compose"))
        add("testImplementation", libs.lib("roborazzi-junit-rule"))
        if (path != UI_PROJECT) add("testImplementation", testFixtures(project(UI_PROJECT)))
    }
    val screenshotDir = layout.buildDirectory.dir("outputs/roborazzi")
    tasks.withType<Test>().configureEach {
        // Robolectric's SDK 36 runtime reaches into JDK internals that newer JDKs (25+) no longer export.
        jvmArgs(
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--enable-native-access=ALL-UNNAMED",
        )
        systemProperty("robolectric.graphicsMode", "NATIVE")
        if (name.endsWith("DebugUnitTest")) {
            systemProperty("roborazzi.test.record", "true")
            outputs.dir(screenshotDir)
        }
    }
}
