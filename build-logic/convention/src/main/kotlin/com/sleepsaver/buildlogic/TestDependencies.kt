package com.sleepsaver.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/** JUnit4 + Robolectric for Android modules (Robolectric needs JUnit4). */
internal fun Project.addAndroidUnitTestDependencies() {
    dependencies {
        add("testImplementation", libs.lib("junit4"))
        add("testImplementation", libs.lib("truth"))
        add("testImplementation", libs.lib("turbine"))
        add("testImplementation", libs.lib("kotlinx-coroutines-test"))
        add("testImplementation", libs.lib("robolectric"))
        add("testImplementation", libs.lib("androidx-test-core"))
        add("testImplementation", libs.lib("androidx-test-ext-junit"))
    }
    tasks.withType<Test>().configureEach {
        // Robolectric's SDK 36 runtime reaches into JDK internals that newer JDKs (25+) no longer export.
        // Robolectric 4.17 can't run SDK 37, so each module pins sdk=36 in src/test/resources/robolectric.properties.
        jvmArgs(
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--enable-native-access=ALL-UNNAMED",
        )
        // Robolectric + native-graphics screenshots outgrow Gradle's default 512 MB test heap.
        maxHeapSize = "1536m"
        // Modules may exist before their first test does.
        failOnNoDiscoveredTests.set(false)
    }
}
