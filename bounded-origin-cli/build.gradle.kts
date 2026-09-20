import info.solidsoft.gradle.pitest.PitestPluginExtension
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
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
    excludedTestClasses.set(
        setOf("io.github.aalsanie.boundedorigin.cli.ZeroCodeEndToEndTest")
    )
    mutationThreshold.set(90)
    coverageThreshold.set(91)
    testStrengthThreshold.set(90)
    threads.set(1)
    outputFormats.set(setOf("XML", "HTML"))
    timestampedReports.set(false)
}

val preparePackagedDistributionTest = tasks.register<Sync>("preparePackagedDistributionTest") {
    val windows = System.getProperty("os.name").startsWith("Windows")
    if (windows) {
        val archive = tasks.named<Zip>("distZip")
        dependsOn(archive)
        from(archive.map { zipTree(it.archiveFile.get().asFile) })
    } else {
        val archive = tasks.named<Tar>("distTar")
        dependsOn(archive)
        from(archive.map { tarTree(it.archiveFile.get().asFile) })
    }
    into(layout.buildDirectory.dir("packaged-distribution-test"))
}

tasks.named<Test>("test") {
    dependsOn(preparePackagedDistributionTest)
    systemProperty(
        "boundedOrigin.launcherDir",
        layout.buildDirectory
            .dir("packaged-distribution-test/bounded-origin/bin")
            .get()
            .asFile
            .absolutePath,
    )
}

tasks.named("pitest") {
    dependsOn("installDist")
    mustRunAfter(":bounded-origin-proxy:pitest")
}

tasks.named("check") {
    dependsOn("pitest")
}
