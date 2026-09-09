import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "bounded-origin"

include(
    "bounded-origin-api",
    "bounded-origin-core",
    "bounded-origin-store-fs",
    "bounded-origin-proxy",
    "bounded-origin-cli",
    "bounded-origin-benchmarks",
    "test-infra",
)
