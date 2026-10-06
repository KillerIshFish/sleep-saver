import com.sleepsaver.buildlogic.configureKotlinCompile
import com.sleepsaver.buildlogic.lib
import com.sleepsaver.buildlogic.libs
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/** Pure Kotlin/JVM module with JUnit 5. No Android allowed. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        configureKotlinCompile()
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
        dependencies {
            val junitBom = platform(libs.lib("junit5-bom"))
            add("testImplementation", junitBom)
            add("testImplementation", libs.lib("junit5-jupiter"))
            add("testRuntimeOnly", libs.lib("junit5-platform-launcher"))
            add("testImplementation", libs.lib("truth"))
        }
    }
}
