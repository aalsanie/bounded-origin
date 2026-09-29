import org.gradle.api.credentials.HttpHeaderCredentials
import org.gradle.api.initialization.resolve.RepositoriesMode
import org.gradle.authentication.http.HttpHeaderAuthentication

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

            val authorization =
                providers.environmentVariable("BOUNDED_ORIGIN_REPOSITORY_AUTHORIZATION").orNull
            if (!authorization.isNullOrBlank()) {
                credentials(HttpHeaderCredentials::class) {
                    name = "Authorization"
                    value = authorization
                }
                authentication {
                    create<HttpHeaderAuthentication>("header")
                }
            }
        }
        mavenCentral()
    }
}

rootProject.name = "bounded-origin-release-consumer"
