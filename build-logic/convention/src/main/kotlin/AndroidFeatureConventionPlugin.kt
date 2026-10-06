import com.sleepsaver.buildlogic.lib
import com.sleepsaver.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** A screen module: Compose + Hilt + navigation, depends on the core modules. */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("sleepsaver.android.library.compose")
        pluginManager.apply("sleepsaver.hilt")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        dependencies {
            add("implementation", project(":core:domain"))
            add("implementation", project(":core:data"))
            add("implementation", project(":core:ui"))
            add("implementation", libs.lib("androidx-core-ktx"))
            add("implementation", libs.lib("androidx-navigation-compose"))
            add("implementation", libs.lib("androidx-hilt-navigation-compose"))
            add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("kotlinx-serialization-json"))
            add("implementation", libs.lib("kotlinx-coroutines-android"))
            add("testImplementation", project(":core:testing"))
        }
    }
}
