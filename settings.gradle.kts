pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SleepSaver"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":core:domain")
include(":core:data")
include(":core:ui")
include(":core:notifications")
include(":core:testing")
include(":feature:sleep")
include(":feature:energy")
include(":feature:progress")
include(":feature:review")
include(":feature:habits")
include(":feature:onboarding")
include(":feature:settings")
include(":feature:learn")
include(":feature:widget")

// Debug-only tool (not shipped): seeds Health Connect on an emulator.
include(":tools:hcseeder")
