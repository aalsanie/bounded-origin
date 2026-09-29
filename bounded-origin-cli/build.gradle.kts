import info.solidsoft.gradle.pitest.PitestPluginExtension
import java.io.File
import java.security.MessageDigest
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

val releaseVersion = project.version.toString()
val releaseTag = "v$releaseVersion"
val releaseArchiveBaseName = "bounded-origin-$releaseVersion"
val releaseSourceNotice =
    layout.buildDirectory.file("generated/release/SOURCE.txt")

val generateReleaseSourceNotice = tasks.register("generateReleaseSourceNotice") {
    group = "release"
    description = "Generates source provenance bundled with the CLI release."

    outputs.file(releaseSourceNotice)

    doLast {
        releaseSourceNotice.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                """
                Bounded Origin $releaseVersion
                Source repository: https://github.com/aalsanie/bounded-origin
                Release tag: $releaseTag
                Corresponding source: https://github.com/aalsanie/bounded-origin/tree/$releaseTag
                """.trimIndent() + "\n",
                Charsets.UTF_8,
            )
        }
    }
}

distributions {
    main {
        distributionBaseName = "bounded-origin"
        contents {
            from(rootProject.layout.projectDirectory.file("LICENSE"))
            from(rootProject.layout.projectDirectory.file("LICENSING.md"))
            from(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
            from(rootProject.layout.projectDirectory.file("LICENSES/AGPL-3.0-only.txt")) {
                into("LICENSES")
            }
            from(rootProject.layout.projectDirectory.file("LICENSES/Apache-2.0.txt")) {
                into("LICENSES")
            }
            from(releaseSourceNotice)
        }
    }
}

fun cliSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

fun archivePaths(tree: FileTree): Set<String> {
    val paths = linkedSetOf<String>()
    tree.visit {
        if (!isDirectory) {
            paths += relativePath.pathString
        }
    }
    return paths
}

val releaseZip = tasks.named<Zip>("distZip") {
    dependsOn(generateReleaseSourceNotice)
}

val releaseTar = tasks.named<Tar>("distTar") {
    dependsOn(generateReleaseSourceNotice)
}

val verifyCliReleaseArchives = tasks.register("verifyCliReleaseArchives") {
    group = "verification"
    description = "Verifies the exact 0.1.0 CLI release archive names and required contents."
    dependsOn(releaseZip, releaseTar)

    doLast {
        val expectedZip = "$releaseArchiveBaseName.zip"
        val expectedTar = "$releaseArchiveBaseName.tar"
        if (releaseZip.get().archiveFileName.get() != expectedZip ||
            releaseTar.get().archiveFileName.get() != expectedTar
        ) {
            throw GradleException(
                "Unexpected CLI archive names: " +
                    "${releaseZip.get().archiveFileName.get()}, " +
                    releaseTar.get().archiveFileName.get()
            )
        }

        val requiredPaths =
            setOf(
                "$releaseArchiveBaseName/LICENSE",
                "$releaseArchiveBaseName/LICENSING.md",
                "$releaseArchiveBaseName/CHANGELOG.md",
                "$releaseArchiveBaseName/SOURCE.txt",
                "$releaseArchiveBaseName/LICENSES/AGPL-3.0-only.txt",
                "$releaseArchiveBaseName/LICENSES/Apache-2.0.txt",
                "$releaseArchiveBaseName/bin/bounded-origin",
                "$releaseArchiveBaseName/bin/bounded-origin.bat",
            )

        val archives =
            listOf(
                expectedZip to
                    archivePaths(zipTree(releaseZip.get().archiveFile.get().asFile)),
                expectedTar to
                    archivePaths(tarTree(releaseTar.get().archiveFile.get().asFile)),
            )
        archives.forEach { (name, paths) ->
            val missing = requiredPaths - paths
            if (missing.isNotEmpty()) {
                throw GradleException("$name is missing required files: ${missing.sorted()}")
            }
            if (paths.none {
                    it.startsWith("$releaseArchiveBaseName/lib/") &&
                        it.endsWith(".jar")
                }
            ) {
                throw GradleException("$name contains no runtime JARs")
            }
            if (paths.any { !it.startsWith("$releaseArchiveBaseName/") }) {
                throw GradleException("$name contains files outside $releaseArchiveBaseName/")
            }
            if (paths.any {
                    ".codex-context" in it ||
                        "/.git/" in it ||
                        "/build/" in it
                }
            ) {
                throw GradleException("$name contains private or build-time files")
            }
        }

        val sourceNotice =
            """
            Bounded Origin $releaseVersion
            Source repository: https://github.com/aalsanie/bounded-origin
            Release tag: $releaseTag
            Corresponding source: https://github.com/aalsanie/bounded-origin/tree/$releaseTag
            """.trimIndent() + "\n"
        val generatedNotice = releaseSourceNotice.get().asFile.readText(Charsets.UTF_8)
        if (generatedNotice != sourceNotice) {
            throw GradleException("Generated SOURCE.txt does not match release identity")
        }
    }
}

val cliReleaseChecksums =
    layout.buildDirectory.file("distributions/SHA256SUMS")

val generateCliReleaseChecksums = tasks.register("generateCliReleaseChecksums") {
    group = "release"
    description = "Generates SHA-256 checksums for the CLI release archives."
    dependsOn(verifyCliReleaseArchives)
    outputs.file(cliReleaseChecksums)

    doLast {
        val archives =
            listOf(
                releaseTar.get().archiveFile.get().asFile,
                releaseZip.get().archiveFile.get().asFile,
            ).sortedBy(File::getName)

        cliReleaseChecksums.get().asFile.writeText(
            archives.joinToString(separator = "\n", postfix = "\n") { archive ->
                "${cliSha256(archive)}  ${archive.name}"
            },
            Charsets.US_ASCII,
        )
    }
}

val verifyCliReleaseChecksums = tasks.register("verifyCliReleaseChecksums") {
    group = "verification"
    description = "Verifies the CLI release checksum manifest."
    dependsOn(generateCliReleaseChecksums)

    doLast {
        val archives =
            listOf(
                releaseTar.get().archiveFile.get().asFile,
                releaseZip.get().archiveFile.get().asFile,
            ).sortedBy(File::getName)
        val expected =
            archives.joinToString(separator = "\n", postfix = "\n") { archive ->
                "${cliSha256(archive)}  ${archive.name}"
            }
        val actual = cliReleaseChecksums.get().asFile.readText(Charsets.US_ASCII)
        if (actual != expected) {
            throw GradleException("CLI release SHA256SUMS does not match the archives")
        }
    }
}

tasks.register("cliReleaseArtifacts") {
    group = "release"
    description = "Builds and verifies the CLI 0.1.0 ZIP, TAR and SHA256SUMS."
    dependsOn(verifyCliReleaseChecksums)
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

val windows = System.getProperty("os.name").startsWith("Windows")
val distributionArchive =
    if (windows) {
        tasks.named<Zip>("distZip")
    } else {
        tasks.named<Tar>("distTar")
    }

val preparePackagedDistributionTest = tasks.register<Sync>("preparePackagedDistributionTest") {
    dependsOn(distributionArchive)
    if (windows) {
        from(distributionArchive.map { zipTree(it.archiveFile.get().asFile) })
    } else {
        from(distributionArchive.map { tarTree(it.archiveFile.get().asFile) })
    }
    into(layout.buildDirectory.dir("packaged-distribution-test"))
}

tasks.named<Test>("test") {
    dependsOn(preparePackagedDistributionTest)
    doFirst {
        val archive = distributionArchive.get()
        val root = archive.archiveFileName.get().substringBeforeLast('.')
        systemProperty(
            "boundedOrigin.launcherDir",
            layout.buildDirectory
                .dir("packaged-distribution-test/$root/bin")
                .get()
                .asFile
                .absolutePath,
        )
    }
}

tasks.named("pitest") {
    dependsOn("installDist")
    mustRunAfter(":bounded-origin-proxy:pitest")
}

tasks.named("check") {
    dependsOn("pitest", verifyCliReleaseArchives)
}
