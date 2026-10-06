import com.android.build.api.dsl.ApplicationExtension
import com.sleepsaver.buildlogic.TARGET_SDK
import com.sleepsaver.buildlogic.addAndroidUnitTestDependencies
import com.sleepsaver.buildlogic.configureAndroidCommon
import com.sleepsaver.buildlogic.configureCompose
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            configureAndroidCommon(this)
            configureCompose(this)
            defaultConfig.targetSdk = TARGET_SDK
        }
        addAndroidUnitTestDependencies()
    }
}
