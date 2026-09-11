import info.solidsoft.gradle.pitest.PitestPluginExtension

plugins {
    alias(libs.plugins.pitest)
}

description = "Filesystem-backed artifact store."

dependencies {
    api(project(":bounded-origin-api"))
}

extensions.configure<PitestPluginExtension> {
    pitestVersion.set(libs.versions.pitest.get())
    junit5PluginVersion.set(libs.versions.pitestJunit5.get())
    targetClasses.set(setOf("io.github.aalsanie.boundedorigin.store.fs.*"))
    mutationThreshold.set(90)
    coverageThreshold.set(91)
    testStrengthThreshold.set(90)
    threads.set(1)
    outputFormats.set(setOf("XML", "HTML"))
    timestampedReports.set(false)
}

tasks.named("pitest") {
    mustRunAfter(":bounded-origin-core:pitest")
}

tasks.named("check") {
    dependsOn("pitest")
}
