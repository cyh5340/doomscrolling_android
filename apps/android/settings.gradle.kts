pluginManagement {
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

rootProject.name = "doomscrolling"
include(
    ":app",
    ":core:usage", ":core:data", ":core:network", ":core:ui",
    ":feature:onboarding", ":feature:groups", ":feature:leaderboard", ":feature:home",
)
