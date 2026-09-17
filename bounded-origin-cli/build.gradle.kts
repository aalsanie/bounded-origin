import info.solidsoft.gradle.pitest.PitestPluginExtension

plugins {
    alias(libs.plugins.pitest)
}

description = "Command-line entry points."

dependencies {
    implementation(project(":bounded-origin-api"))
    implementation(project(":bounded-origin-core"))
    implementation(libs.snakeyaml.engine)
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

tasks.named("pitest") {
    mustRunAfter(":bounded-origin-proxy:pitest")
}

tasks.named("check") {
    dependsOn("pitest")
}
