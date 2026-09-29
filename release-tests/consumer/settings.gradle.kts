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
        maven {
            name = "boundedOriginRelease"
            url = uri(providers.gradleProperty("boundedOriginRepository").get())
            metadataSources {
                mavenPom()
                artifact()
            }
        }
        mavenCentral()
    }
}

rootProject.name = "bounded-origin-release-consumer"
