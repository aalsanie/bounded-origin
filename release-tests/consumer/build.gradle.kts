plugins {
    application
}

val boundedOriginVersion = providers.gradleProperty("boundedOriginVersion").get()

dependencies {
    implementation("io.github.aalsanie:bounded-origin-api:$boundedOriginVersion")
    implementation("io.github.aalsanie:bounded-origin-core:$boundedOriginVersion")
    implementation("io.github.aalsanie:bounded-origin-store-fs:$boundedOriginVersion")
    implementation("io.github.aalsanie:bounded-origin-proxy:$boundedOriginVersion")
}

configurations.configureEach {
    resolutionStrategy.failOnVersionConflict()
}

application {
    mainClass.set("consumer.ConsumerSmoke")
}
