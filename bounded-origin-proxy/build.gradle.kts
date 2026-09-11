import info.solidsoft.gradle.pitest.PitestPluginExtension

plugins {
    alias(libs.plugins.pitest)
}

description = "Standalone gateway runtime."

dependencies {
    api(project(":bounded-origin-core"))
    implementation(libs.netty.codec.http)
    implementation(libs.netty.handler)
    implementation(libs.netty.resolver)
    implementation(libs.netty.transport)
}

extensions.configure<PitestPluginExtension> {
    pitestVersion.set(libs.versions.pitest.get())
    junit5PluginVersion.set(libs.versions.pitestJunit5.get())
    targetClasses.set(setOf("io.github.aalsanie.boundedorigin.proxy.*"))
    mutationThreshold.set(90)
    coverageThreshold.set(91)
    testStrengthThreshold.set(90)
    threads.set(1)
    outputFormats.set(setOf("XML", "HTML"))
    timestampedReports.set(false)
}

tasks.named("pitest") {
    mustRunAfter(":bounded-origin-core:pitest", ":bounded-origin-store-fs:pitest")
}

tasks.named("check") {
    dependsOn("pitest")
}
