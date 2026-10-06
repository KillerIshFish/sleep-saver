package com.sleepsaver.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

internal fun Project.configureCompose(extension: CommonExtension) {
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
    extension.buildFeatures.compose = true
    configureKotlinCompile(COMPOSE_OPT_INS)
    dependencies {
        val bom = platform(libs.lib("androidx-compose-bom"))
        add("implementation", bom)
        add("testImplementation", bom)
        add("implementation", libs.lib("androidx-compose-ui"))
        add("implementation", libs.lib("androidx-compose-ui-graphics"))
        add("implementation", libs.lib("androidx-compose-foundation"))
        add("implementation", libs.lib("androidx-compose-animation"))
        add("implementation", libs.lib("androidx-compose-material3"))
        add("implementation", libs.lib("androidx-compose-material-icons-extended"))
        add("implementation", libs.lib("androidx-compose-ui-tooling-preview"))
        add("debugImplementation", libs.lib("androidx-compose-ui-tooling"))
        add("debugImplementation", libs.lib("androidx-compose-ui-test-manifest"))
        add("testImplementation", libs.lib("androidx-compose-ui-test-junit4"))
    }
}
