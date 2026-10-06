import com.android.build.api.dsl.LibraryExtension
import com.sleepsaver.buildlogic.configureCompose
import com.sleepsaver.buildlogic.configureScreenshotTests
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("sleepsaver.android.library")
        extensions.configure<LibraryExtension> {
            configureCompose(this)
        }
        configureScreenshotTests()
    }
}
