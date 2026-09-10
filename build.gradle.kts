import com.diffplug.gradle.spotless.SpotlessExtension
import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import info.solidsoft.gradle.pitest.PitestPluginExtension
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import org.gradle.api.GradleException
import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.PathSensitivity
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    base
    jacoco
    alias(libs.plugins.spotless)
    alias(libs.plugins.spotbugs) apply false
    alias(libs.plugins.pitest) apply false
}

group = "io.github.aalsanie"
version = "0.1.0-SNAPSHOT"

val coverageMinimum = BigDecimal("0.91")
val jacocoVersion = libs.versions.jacoco.get()
val googleJavaFormatVersion = libs.versions.google.java.format.get()
val spotbugsVersion = libs.versions.spotbugs.get()
val junitJupiterDependency = libs.junit.jupiter
val junitPlatformLauncherDependency = libs.junit.platform.launcher
val pitestToolVersion = libs.versions.pitest.get()
val pitestJunit5Version = libs.versions.pitestJunit5.get()
val apiSnapshotModules = setOf(
    "bounded-origin-api",
    "bounded-origin-core",
    "bounded-origin-store-fs",
    "bounded-origin-proxy",
    "bounded-origin-cli",
)
val lockedConfigurations = setOf(
    "compileClasspath",
    "runtimeClasspath",
    "testCompileClasspath",
    "testRuntimeClasspath",
)

val verificationMetadataFile =
    layout.projectDirectory.file("gradle/verification-metadata.xml")

val verifyDependencyVerification = tasks.register("verifyDependencyVerification") {
    group = "verification"
    description = "Verifies that dependency verification metadata contains SHA-256 checksums."

    inputs.files(verificationMetadataFile)
        .withPathSensitivity(PathSensitivity.RELATIVE)

    doLast {
        val file = verificationMetadataFile.asFile

        if (!file.isFile) {
            throw GradleException(
                "Missing gradle/verification-metadata.xml"
            )
        }

        val content = file.readText(Charsets.UTF_8)

        if (!content.contains("<verify-metadata>true</verify-metadata>")) {
            throw GradleException(
                "Dependency metadata verification is not enabled"
            )
        }

        val sha256 =
            Regex("""<sha256\b[^>]*\bvalue="[0-9a-fA-F]{64}"[^>]*/>""")

        if (!sha256.containsMatchIn(content)) {
            throw GradleException(
                "Dependency verification metadata contains no SHA-256 checksums"
            )
        }
    }
}

fun publicApiSnapshot(projectName: String, classesDir: File): String {
    val header = "# $projectName public API\n"
    if (!classesDir.exists()) return header

    val classNames = classesDir
        .walkTopDown()
        .filter { it.isFile && it.extension == "class" && it.name != "module-info.class" }
        .map { it.relativeTo(classesDir).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.') }
        .sorted()
        .toList()

    if (classNames.isEmpty()) return header

    val javaHome = File(System.getProperty("java.home"))
    val javap = File(javaHome, "bin/${if (System.getProperty("os.name").startsWith("Windows")) "javap.exe" else "javap"}")
    if (!javap.isFile) throw GradleException("javap was not found under ${javaHome.absolutePath}")

    val body = buildString {
        for (className in classNames) {
            val process = ProcessBuilder(
                javap.absolutePath,
                "-public",
                "-classpath",
                classesDir.absolutePath,
                className,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val exit = process.waitFor()
            if (exit != 0) throw GradleException("javap failed for $className:\n$output")
            append(output.lineSequence().filterNot { it.startsWith("Compiled from ") }.joinToString("\n"))
            append("\n")
        }
    }
    return header + body.trimEnd() + "\n"
}

fun sha256(file: File): String {
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

configure<SpotlessExtension> {
    format("rootMisc") {
        target(
            "*.gradle.kts",
            "*.md",
            ".editorconfig",
            ".gitattributes",
            ".gitignore",
            ".github/**/*.yml",
            "gradle/**/*.toml",
            "gradle/**/*.properties",
            "scripts/**/*.sh",
            "scripts/**/*.ps1",
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}

subprojects {
    group = rootProject.group
    version = rootProject.version

    apply(plugin = "java-library")
    apply(plugin = "jacoco")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "com.github.spotbugs")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        withSourcesJar()
    }

    extensions.configure<JacocoPluginExtension> {
        toolVersion = jacocoVersion
    }

    extensions.configure<SpotlessExtension> {
        java {
            target("src/**/*.java")
            googleJavaFormat(googleJavaFormatVersion)
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
        format("misc") {
            target("*.gradle.kts", "api/*.api")
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    extensions.configure<SpotBugsExtension> {
        toolVersion.set(spotbugsVersion)
        ignoreFailures.set(false)
        showProgress.set(true)
        effort.set(Effort.MAX)
        reportLevel.set(Confidence.LOW)
    }

    dependencies {
        add("testImplementation", junitJupiterDependency)
        add("testRuntimeOnly", junitPlatformLauncherDependency)
    }

    configurations.configureEach {
        resolutionStrategy.failOnVersionConflict()
        if (name in lockedConfigurations) {
            resolutionStrategy.activateDependencyLocking()
        }
    }

    dependencyLocking {
        lockMode.set(LockMode.STRICT)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        failFast = false
        jvmArgs("-Dfile.encoding=UTF-8")
        reports.junitXml.required.set(true)
        reports.html.required.set(true)
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
    }

    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        dependsOn(tasks.named("test"))
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    minimum = coverageMinimum
                }
                limit {
                    counter = "BRANCH"
                    minimum = coverageMinimum
                }
            }
        }
    }

    if (name in apiSnapshotModules) {
        val moduleName = project.name
        val snapshotFile = layout.projectDirectory.file("api/$moduleName.api")
        val classesDir = layout.buildDirectory.dir("classes/java/main")

        tasks.register("apiDump") {
            group = "verification"
            description = "Writes the bytecode-derived public API snapshot."
            dependsOn(tasks.named("classes"))

            inputs.files(classesDir)
                .withPathSensitivity(PathSensitivity.RELATIVE)
            outputs.file(snapshotFile)

            doLast {
                val target = snapshotFile.asFile
                target.parentFile.mkdirs()
                target.writeText(
                    publicApiSnapshot(moduleName, classesDir.get().asFile),
                    Charsets.UTF_8,
                )
            }
        }

        val apiCheck = tasks.register("apiCheck") {
            group = "verification"
            description = "Fails when the public API differs from the committed snapshot."
            dependsOn(tasks.named("classes"))

            inputs.files(classesDir)
                .withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.file(snapshotFile)
                .withPathSensitivity(PathSensitivity.NONE)

            doLast {
                val expectedFile = snapshotFile.asFile

                if (!expectedFile.isFile) {
                    throw GradleException(
                        "Missing API snapshot ${expectedFile.relativeTo(rootDir)}. Run :$moduleName:apiDump."
                    )
                }

                val expected =
                    expectedFile
                        .readText(Charsets.UTF_8)
                        .replace("\r\n", "\n")

                val actual =
                    publicApiSnapshot(
                        moduleName,
                        classesDir.get().asFile,
                    )

                if (expected != actual) {
                    throw GradleException(
                        "Public API changed in $moduleName. Review it, then run :$moduleName:apiDump if intentional."
                    )
                }
            }
        }

        tasks.named("check") {
            dependsOn(apiCheck)
        }
    }
    tasks.named("check") {
        dependsOn("jacocoTestCoverageVerification", "jacocoTestReport", "spotlessCheck")
    }
}

project(":bounded-origin-core") {
    apply(plugin = "info.solidsoft.pitest")
    extensions.configure<PitestPluginExtension> {
        pitestVersion.set(pitestToolVersion)
        junit5PluginVersion.set(pitestJunit5Version)
        targetClasses.set(setOf("io.github.aalsanie.boundedorigin.core.*"))
        mutationThreshold.set(90)
        coverageThreshold.set(91)
        testStrengthThreshold.set(90)
        threads.set(1)
        outputFormats.set(setOf("XML", "HTML"))
        timestampedReports.set(false)
    }
}

val aggregateJacocoReport = tasks.register<JacocoReport>("aggregateJacocoReport") {
    group = "verification"
    description = "Creates aggregate line and branch coverage reports."
    dependsOn(subprojects.map { it.tasks.named("test") })
    executionData.from(subprojects.map { project -> fileTree(project.layout.buildDirectory) { include("jacoco/test.exec") } })
    classDirectories.from(subprojects.map { it.layout.buildDirectory.dir("classes/java/main") })
    sourceDirectories.from(subprojects.map { it.layout.projectDirectory.dir("src/main/java") })
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

val aggregateJacocoVerification = tasks.register<JacocoCoverageVerification>("aggregateJacocoVerification") {
    group = "verification"
    description = "Enforces aggregate line and branch coverage."
    dependsOn(subprojects.map { it.tasks.named("test") })
    executionData.from(subprojects.map { project -> fileTree(project.layout.buildDirectory) { include("jacoco/test.exec") } })
    classDirectories.from(subprojects.map { it.layout.buildDirectory.dir("classes/java/main") })
    sourceDirectories.from(subprojects.map { it.layout.projectDirectory.dir("src/main/java") })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = coverageMinimum
            }
            limit {
                counter = "BRANCH"
                minimum = coverageMinimum
            }
        }
    }
}

val verifyPinnedVersions = tasks.register("verifyPinnedVersions") {
    group = "verification"
    description = "Rejects dynamic dependency and plugin versions."
    val catalog = layout.projectDirectory.file("gradle/libs.versions.toml")
    inputs.file(catalog)
    doLast {
        val text = catalog.asFile.readText(Charsets.UTF_8)
        val forbidden = listOf(
            Regex("=\\s*\"[^\"]*\\+[^\"]*\""),
            Regex("=\\s*\"(?i:latest\\.[^\"]+)\""),
            Regex("=\\s*\"(?i:release)\""),
        )
        if (forbidden.any { it.containsMatchIn(text) }) {
            throw GradleException("Dynamic versions are forbidden in gradle/libs.versions.toml")
        }
    }
}

val verifyWrapperConfiguration = tasks.register("verifyWrapperConfiguration") {
    group = "verification"
    description = "Verifies pinned Gradle wrapper URLs and published checksums."
    val propertiesFile = layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties")
    inputs.file(propertiesFile)
    doLast {
        val properties = java.util.Properties().apply {
            propertiesFile.asFile.inputStream().use { load(it) }
        }
        val expectedUrl = "https://services.gradle.org/distributions/gradle-9.7.1-bin.zip"
        val expectedDistributionHash = "acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a"
        check(properties.getProperty("distributionUrl") == expectedUrl) { "Unexpected Gradle distribution URL" }
        check(properties.getProperty("distributionSha256Sum") == expectedDistributionHash) { "Unexpected Gradle distribution checksum" }
        check(properties.getProperty("validateDistributionUrl") == "true") { "Wrapper URL validation must remain enabled" }
        val wrapperJar = layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.jar").asFile
        if (wrapperJar.exists()) {
            val expectedWrapperHash = "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"
            check(sha256(wrapperJar) == expectedWrapperHash) { "Gradle wrapper JAR checksum mismatch" }
        }
    }
}

val verifyDependencyLocks = tasks.register("verifyDependencyLocks") {
    group = "verification"
    description = "Ensures every module has a committed dependency lock state."
    inputs.files(subprojects.map { it.layout.projectDirectory.file("gradle.lockfile") })
    doLast {
        val missing = subprojects
            .map { it.layout.projectDirectory.file("gradle.lockfile").asFile }
            .filterNot(File::isFile)
        if (missing.isNotEmpty()) {
            throw GradleException("Missing dependency lock files: ${missing.joinToString { it.relativeTo(rootDir).path }}")
        }
    }
}

val verifyNoProductionPlaceholders = tasks.register("verifyNoProductionPlaceholders") {
    group = "verification"
    description = "Rejects TODO/FIXME markers in production Java source."
    val productionSources = subprojects.map { fileTree(it.layout.projectDirectory.dir("src/main/java")) { include("**/*.java") } }
    inputs.files(productionSources)
    doLast {
        val offenders = productionSources.flatMap { tree ->
            tree.files.filter { file -> Regex("\\b(TODO|FIXME)\\b").containsMatchIn(file.readText(Charsets.UTF_8)) }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("Production placeholders are forbidden: ${offenders.joinToString { it.relativeTo(rootDir).path }}")
        }
    }
}

val verifyNoSecrets = tasks.register("verifyNoSecrets") {
    group = "verification"
    description = "Rejects common committed credential and private-key patterns."
    val candidates = fileTree(rootDir) {
        include("**/*.java", "**/*.kt", "**/*.kts", "**/*.toml", "**/*.yml", "**/*.yaml", "**/*.properties", "**/*.json", "**/*.xml", "**/*.sh", "**/*.ps1")
        exclude("**/build/**", ".gradle/**")
    }
    inputs.files(candidates)
    doLast {
        val patterns = listOf(
            Regex("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            Regex("AKIA[0-9A-Z]{16}"),
            Regex("gh[pousr]_[A-Za-z0-9_]{30,}"),
            Regex("sk-[A-Za-z0-9]{32,}"),
        )
        val offenders = candidates.files.filter { file ->
            val text = file.readText(Charsets.UTF_8)
            patterns.any { it.containsMatchIn(text) }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("Potential committed secret detected: ${offenders.joinToString { it.relativeTo(rootDir).path }}")
        }
    }
}

tasks.named("check") {
    dependsOn(
        subprojects.map { it.tasks.named("check") },
        aggregateJacocoReport,
        aggregateJacocoVerification,
        verifyPinnedVersions,
        verifyWrapperConfiguration,
        verifyDependencyLocks,
        verifyDependencyVerification,
        verifyNoProductionPlaceholders,
        verifyNoSecrets,
        "spotlessCheck",
    )
}
