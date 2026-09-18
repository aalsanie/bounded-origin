import info.solidsoft.gradle.pitest.PitestPluginExtension
import org.gradle.api.tasks.testing.Test

plugins {
    application
    alias(libs.plugins.pitest)
}

description = "Command-line entry points."

dependencies {
    implementation(project(":bounded-origin-api"))
    implementation(project(":bounded-origin-core"))
    implementation(project(":bounded-origin-proxy"))
    implementation(project(":bounded-origin-store-fs"))
    implementation(libs.snakeyaml.engine)
}

application {
    mainClass.set("io.github.aalsanie.boundedorigin.cli.BoundedOriginCli")
    applicationName = "bounded-origin"
}

extensions.configure<PitestPluginExtension> {
    pitestVersion.set(libs.versions.pitest.get())
    junit5PluginVersion.set(libs.versions.pitestJunit5.get())
    targetClasses.set(setOf("io.github.aalsanie.boundedorigin.cli.*"))
    mutationThreshold.set(90)
    coverageThreshold.set(91)
    testStrengthThreshold.set(90)
    threads.set(1)
    outputFormats.set(setOf("XML", "HTML"))
    timestampedReports.set(false)
}

tasks.named<Test>("test") {
    dependsOn("installDist")
    systemProperty(
        "boundedOrigin.launcherDir",
        layout.buildDirectory.dir("install/bounded-origin/bin").get().asFile.absolutePath,
    )
}

tasks.named("pitest") {
    dependsOn("installDist")
    mustRunAfter(":bounded-origin-proxy:pitest")
}

tasks.named("check") {
    dependsOn("pitest")
}
