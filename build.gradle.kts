import com.diffplug.gradle.spotless.SpotlessExtension
import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import info.solidsoft.gradle.pitest.PitestPluginExtension
import java.io.File
import java.io.InputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.GradleException
import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Exec
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension
import org.w3c.dom.Element

plugins {
    base
    jacoco
    alias(libs.plugins.spotless)
    alias(libs.plugins.spotbugs) apply false
    alias(libs.plugins.pitest) apply false
}

group = "io.github.aalsanie"

val releaseVersion = "0.1.0"
val releaseTag = "v0.1.0"
val releaseArtifactBaseName = "bounded-origin-$releaseVersion"
version = releaseVersion

val releaseGroupId = group.toString()

val mavenPublicationModules =
    setOf(
        "bounded-origin-api",
        "bounded-origin-core",
        "bounded-origin-store-fs",
        "bounded-origin-proxy",
    )
val githubReleaseDistributionModules = setOf("bounded-origin-cli")
val internalModules = setOf("bounded-origin-benchmarks", "test-infra")

val mavenPublicationNames =
    mapOf(
        "bounded-origin-api" to "Bounded Origin API",
        "bounded-origin-core" to "Bounded Origin Core",
        "bounded-origin-store-fs" to "Bounded Origin Filesystem Store",
        "bounded-origin-proxy" to "Bounded Origin Proxy",
    )
val mavenPublicationDescriptions =
    mapOf(
        "bounded-origin-api" to "Framework-independent public contracts for Bounded Origin.",
        "bounded-origin-core" to "Core policy and bounded origin-execution engine.",
        "bounded-origin-store-fs" to "Filesystem-backed artifact store for Bounded Origin.",
        "bounded-origin-proxy" to "Standalone HTTP gateway runtime for Bounded Origin.",
    )
val apacheLicensedPublicationModules = setOf("bounded-origin-api")

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

fun publicApiSnapshot(
    projectName: String,
    classesDir: File,
    javaHome: File,
): String {
    val header = "# $projectName public API\n"
    if (!classesDir.exists()) return header

    val classNames = classesDir
        .walkTopDown()
        .filter { it.isFile && it.extension == "class" && it.name != "module-info.class" }
        .map { it.relativeTo(classesDir).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.') }
        .sorted()
        .toList()

    if (classNames.isEmpty()) return header

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
            val normalizedOutput =
                output
                    .lineSequence()
                    .filterNot { it.startsWith("Compiled from ") }
                    .joinToString("\n")
                    .trimEnd('\r', '\n')

            append(normalizedOutput)
            append("\n")
        }
    }
    return header + body.trimEnd() + "\n"
}

fun digestHex(input: InputStream, algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

fun digestHex(file: File, algorithm: String): String =
    file.inputStream().use { digestHex(it, algorithm) }

fun sha256(file: File): String = digestHex(file, "SHA-256")

fun parseXml(file: File): Element {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    factory.isExpandEntityReferences = false
    factory.isXIncludeAware = false
    return factory.newDocumentBuilder().parse(file).documentElement
}

fun directChildren(parent: Element, name: String): List<Element> =
    (0 until parent.childNodes.length)
        .mapNotNull { parent.childNodes.item(it) as? Element }
        .filter { it.tagName == name }

fun directChild(parent: Element, name: String): Element? =
    directChildren(parent, name).singleOrNull()

fun requiredChildText(parent: Element, name: String, context: String): String =
    directChild(parent, name)
        ?.textContent
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: throw GradleException("Missing $name in $context")

fun validateMavenPom(
    file: File,
    module: String,
    version: String,
    publicationName: String,
    publicationDescription: String,
    apacheLicensed: Boolean,
    publishedModules: Set<String>,
) {
    if (!file.isFile || file.length() == 0L) {
        throw GradleException("Missing generated POM for $module: $file")
    }

    val root = parseXml(file)
    val context = file.path
    if (requiredChildText(root, "modelVersion", context) != "4.0.0") {
        throw GradleException("Unexpected Maven model version in $context")
    }
    if (requiredChildText(root, "groupId", context) != "io.github.aalsanie") {
        throw GradleException("Unexpected groupId in $context")
    }
    if (requiredChildText(root, "artifactId", context) != module) {
        throw GradleException("Unexpected artifactId in $context")
    }
    if (requiredChildText(root, "version", context) != version) {
        throw GradleException("Unexpected version in $context")
    }
    if (requiredChildText(root, "name", context) != publicationName) {
        throw GradleException("Unexpected project name in $context")
    }
    if (requiredChildText(root, "description", context) != publicationDescription) {
        throw GradleException("Unexpected project description in $context")
    }
    if (requiredChildText(root, "url", context) != "https://github.com/aalsanie/bounded-origin") {
        throw GradleException("Unexpected project URL in $context")
    }

    val licenses = directChild(root, "licenses")
        ?: throw GradleException("Missing licenses in $context")
    val license = directChildren(licenses, "license").singleOrNull()
        ?: throw GradleException("Expected exactly one license in $context")
    val expectedLicenseName =
        if (apacheLicensed) "Apache License, Version 2.0"
        else "GNU Affero General Public License v3.0 only"
    val expectedLicenseUrl =
        if (apacheLicensed) "https://www.apache.org/licenses/LICENSE-2.0.txt"
        else "https://www.gnu.org/licenses/agpl-3.0.txt"
    if (requiredChildText(license, "name", context) != expectedLicenseName ||
        requiredChildText(license, "url", context) != expectedLicenseUrl ||
        requiredChildText(license, "distribution", context) != "repo"
    ) {
        throw GradleException("Unexpected license metadata in $context")
    }

    val developers = directChild(root, "developers")
        ?: throw GradleException("Missing developers in $context")
    val developer = directChildren(developers, "developer").singleOrNull()
        ?: throw GradleException("Expected exactly one developer in $context")
    if (requiredChildText(developer, "id", context) != "aalsanie" ||
        requiredChildText(developer, "name", context) != "Ahmad Al-Sanie" ||
        requiredChildText(developer, "url", context) != "https://github.com/aalsanie"
    ) {
        throw GradleException("Unexpected developer metadata in $context")
    }

    val scm = directChild(root, "scm")
        ?: throw GradleException("Missing SCM metadata in $context")
    if (requiredChildText(scm, "connection", context) !=
            "scm:git:https://github.com/aalsanie/bounded-origin.git" ||
        requiredChildText(scm, "developerConnection", context) !=
            "scm:git:ssh://git@github.com/aalsanie/bounded-origin.git" ||
        requiredChildText(scm, "url", context) != "https://github.com/aalsanie/bounded-origin"
    ) {
        throw GradleException("Unexpected SCM metadata in $context")
    }

    directChild(root, "dependencies")
        ?.let { dependencies ->
            directChildren(dependencies, "dependency").forEach { dependency ->
                val dependencyGroup = requiredChildText(dependency, "groupId", context)
                val dependencyArtifact = requiredChildText(dependency, "artifactId", context)
                val dependencyVersion = requiredChildText(dependency, "version", context)
                val lowerVersion = dependencyVersion.lowercase()
                if (dependencyVersion.contains("+") ||
                    dependencyVersion.startsWith("[") ||
                    dependencyVersion.startsWith("(") ||
                    "snapshot" in lowerVersion ||
                    lowerVersion.startsWith("latest.")
                ) {
                    throw GradleException(
                        "Dynamic or snapshot dependency $dependencyGroup:$dependencyArtifact:$dependencyVersion in $context"
                    )
                }
                if (dependencyGroup == "io.github.aalsanie" &&
                    (dependencyArtifact !in publishedModules || dependencyVersion != version)
                ) {
                    throw GradleException(
                        "POM exposes unpublished or mismatched project dependency " +
                            "$dependencyGroup:$dependencyArtifact:$dependencyVersion in $context"
                    )
                }
            }
        }

    if ("published-with-gradle-metadata" in file.readText(Charsets.UTF_8)) {
        throw GradleException(
            "POM advertises Gradle Module Metadata that is not in the Central bundle: $context"
        )
    }
}

fun requireArchiveEntry(
    file: File,
    description: String,
    predicate: (String) -> Boolean,
) {
    if (!file.isFile || file.length() == 0L) {
        throw GradleException("Missing $description archive: $file")
    }
    ZipFile(file).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.isDirectory && predicate(entry.name)) {
                return
            }
        }
    }
    throw GradleException("$description archive has no expected content: $file")
}

fun validatePublicationArchives(
    mainJar: File,
    sourcesJar: File,
    javadocJar: File,
) {
    requireArchiveEntry(mainJar, "main") { it.endsWith(".class") }
    requireArchiveEntry(sourcesJar, "sources") { it.endsWith(".java") }
    requireArchiveEntry(javadocJar, "Javadoc") { it == "index.html" }

    listOf(mainJar, sourcesJar, javadocJar).forEach { archive ->
        requireArchiveEntry(archive, archive.name) { it == "META-INF/LICENSE" }
    }
}

val centralStagingDirectory = layout.buildDirectory.dir("central-staging")
val consumerStagingDirectory = layout.buildDirectory.dir("consumer-repository")

val cleanConsumerStaging = tasks.register("cleanConsumerStaging") {
    group = "release"
    description = "Removes the isolated external-consumer Maven repository."
    doLast {
        delete(consumerStagingDirectory.get().asFile)
    }
}

val cleanCentralStaging = tasks.register("cleanCentralStaging") {
    group = "release"
    description = "Removes staged Maven Central bundle contents."
    doLast {
        delete(centralStagingDirectory.get().asFile)
    }
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

    val javaExtension = extensions.getByType<JavaPluginExtension>()

    javaExtension.apply {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        withSourcesJar()
    }

    if (name in mavenPublicationModules) {
        val moduleName = name

        apply(plugin = "maven-publish")
        apply(plugin = "signing")

        javaExtension.withJavadocJar()

        val publication =
            extensions
                .getByType<PublishingExtension>()
                .publications
                .create("mavenJava", MavenPublication::class.java) {
                    from(components.getByName("java"))
                    artifactId = moduleName

                    pom {
                        name.set(mavenPublicationNames.getValue(moduleName))
                        description.set(mavenPublicationDescriptions.getValue(moduleName))
                        url.set("https://github.com/aalsanie/bounded-origin")

                        licenses {
                            license {
                                if (moduleName in apacheLicensedPublicationModules) {
                                    name.set("Apache License, Version 2.0")
                                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                                } else {
                                    name.set("GNU Affero General Public License v3.0 only")
                                    url.set("https://www.gnu.org/licenses/agpl-3.0.txt")
                                }
                                distribution.set("repo")
                            }
                        }

                        developers {
                            developer {
                                id.set("aalsanie")
                                name.set("Ahmad Al-Sanie")
                                url.set("https://github.com/aalsanie")
                            }
                        }

                        scm {
                            connection.set("scm:git:https://github.com/aalsanie/bounded-origin.git")
                            developerConnection.set(
                                "scm:git:ssh://git@github.com/aalsanie/bounded-origin.git"
                            )
                            url.set("https://github.com/aalsanie/bounded-origin")
                        }
                    }
                }

        tasks.withType<GenerateModuleMetadata>().configureEach {
            enabled = false
        }

        val publicationLicense =
            if (name in apacheLicensedPublicationModules) {
                layout.projectDirectory.file("LICENSE")
            } else {
                rootProject.layout.projectDirectory.file("LICENSE")
            }

        tasks.withType<Jar>().configureEach {
            from(publicationLicense) {
                into("META-INF")
                rename { "LICENSE" }
            }
        }

        extensions.configure<SigningExtension> {
            val signingKey = project.findProperty("signingKey") as String?
            val signingPassword = project.findProperty("signingPassword") as String?
            val signingKeyId = project.findProperty("signingKeyId") as String?

            if (!signingKey.isNullOrBlank()) {
                if (signingKeyId.isNullOrBlank()) {
                    useInMemoryPgpKeys(signingKey, signingPassword)
                } else {
                    useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
                }
            }

            setRequired(true)
            sign(publication)
        }

        val mainJar = tasks.named<Jar>("jar")
        val sourcesJar = tasks.named<Jar>("sourcesJar")
        val javadocJar = tasks.named<Jar>("javadocJar")
        val generatedPom =
            tasks.named<GenerateMavenPom>("generatePomFileForMavenJavaPublication")
        val signPublication = tasks.named<Sign>("signMavenJavaPublication")

        val verifyMavenPublication = tasks.register("verifyMavenPublication") {
            group = "verification"
            description = "Verifies Maven Central metadata and unsigned publication artifacts."
            dependsOn(mainJar, sourcesJar, javadocJar, generatedPom)

            doLast {
                validatePublicationArchives(
                    mainJar.get().archiveFile.get().asFile,
                    sourcesJar.get().archiveFile.get().asFile,
                    javadocJar.get().archiveFile.get().asFile,
                )
                validateMavenPom(
                    generatedPom.get().destination,
                    moduleName,
                    releaseVersion,
                    mavenPublicationNames.getValue(moduleName),
                    mavenPublicationDescriptions.getValue(moduleName),
                    moduleName in apacheLicensedPublicationModules,
                    mavenPublicationModules,
                )
            }
        }

        val verifyCentralSigningCredentials = tasks.register("verifyCentralSigningCredentials") {
            group = "release"
            description = "Requires an ASCII-armored private OpenPGP key for Central publication."
            doLast {
                val signingKey = providers.gradleProperty("signingKey").orNull
                if (signingKey.isNullOrBlank() ||
                    !signingKey.contains("-----BEGIN PGP PRIVATE KEY BLOCK-----")
                ) {
                    throw GradleException(
                        "Central bundle signing requires ORG_GRADLE_PROJECT_signingKey " +
                            "with an ASCII-armored OpenPGP private key"
                    )
                }
            }
        }

        tasks.register("stageConsumerPublication") {
            group = "release"
            description = "Stages the unsigned $moduleName publication for isolated consumer testing."
            dependsOn(
                cleanConsumerStaging,
                verifyMavenPublication,
            )

            val modulePath =
                releaseGroupId.replace('.', '/') +
                    "/$moduleName/$releaseVersion"
            val destinationDirectory =
                rootProject.layout.buildDirectory.dir("consumer-repository/$modulePath")
            outputs.dir(destinationDirectory)

            doLast {
                val destination = destinationDirectory.get().asFile
                delete(destination)
                if (!destination.mkdirs() && !destination.isDirectory) {
                    throw GradleException("Could not create consumer staging directory: $destination")
                }

                val sourceFiles =
                    linkedMapOf(
                        "$moduleName-$releaseVersion.jar" to mainJar.get().archiveFile.get().asFile,
                        "$moduleName-$releaseVersion-sources.jar" to
                            sourcesJar.get().archiveFile.get().asFile,
                        "$moduleName-$releaseVersion-javadoc.jar" to
                            javadocJar.get().archiveFile.get().asFile,
                        "$moduleName-$releaseVersion.pom" to generatedPom.get().destination,
                    )

                sourceFiles.forEach { (targetName, source) ->
                    if (!source.isFile || source.length() == 0L) {
                        throw GradleException("Missing consumer publication input: $source")
                    }
                    source.copyTo(File(destination, targetName), overwrite = true)
                }
            }
        }

        tasks.register("stageCentralPublication") {
            group = "release"
            description = "Stages the signed $moduleName publication in Maven Repository Layout."
            dependsOn(
                cleanCentralStaging,
                verifyMavenPublication,
                verifyCentralSigningCredentials,
                signPublication,
            )

            val modulePath =
                releaseGroupId.replace('.', '/') +
                    "/$moduleName/$releaseVersion"
            val destinationDirectory =
                rootProject.layout.buildDirectory.dir("central-staging/$modulePath")
            outputs.dir(destinationDirectory)

            doLast {
                val destination = destinationDirectory.get().asFile
                delete(destination)
                if (!destination.mkdirs() && !destination.isDirectory) {
                    throw GradleException("Could not create Central staging directory: $destination")
                }

                val sourceFiles =
                    linkedMapOf(
                        "$moduleName-$releaseVersion.jar" to mainJar.get().archiveFile.get().asFile,
                        "$moduleName-$releaseVersion-sources.jar" to
                            sourcesJar.get().archiveFile.get().asFile,
                        "$moduleName-$releaseVersion-javadoc.jar" to
                            javadocJar.get().archiveFile.get().asFile,
                        "$moduleName-$releaseVersion.pom" to generatedPom.get().destination,
                    )

                sourceFiles.forEach { (targetName, source) ->
                    if (!source.isFile || source.length() == 0L) {
                        throw GradleException("Missing Central publication input: $source")
                    }
                    source.copyTo(File(destination, targetName), overwrite = true)

                    val signature = File(source.parentFile, source.name + ".asc")
                    if (!signature.isFile || signature.length() == 0L) {
                        throw GradleException("Missing OpenPGP signature for $source")
                    }
                    signature.copyTo(
                        File(destination, "$targetName.asc"),
                        overwrite = true,
                    )
                }

                val checksums =
                    linkedMapOf(
                        "md5" to "MD5",
                        "sha1" to "SHA-1",
                        "sha256" to "SHA-256",
                        "sha512" to "SHA-512",
                    )
                sourceFiles.keys.forEach { targetName ->
                    val target = File(destination, targetName)
                    checksums.forEach { (extension, algorithm) ->
                        File(destination, "$targetName.$extension")
                            .writeText(
                                digestHex(target, algorithm) + "\n",
                                Charsets.US_ASCII,
                            )
                    }
                }
            }
        }

        tasks.named("check") {
            dependsOn(verifyMavenPublication)
        }
    }

    val apiSnapshotLauncher =
        extensions
            .getByType<JavaToolchainService>()
            .launcherFor(javaExtension.toolchain)

    val apiSnapshotRuntimeVersion =
        apiSnapshotLauncher.map { it.metadata.javaRuntimeVersion }

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
        testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
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
            inputs.property(
                "apiSnapshotRuntimeVersion",
                apiSnapshotRuntimeVersion,
            )

            inputs.files(classesDir)
                .withPathSensitivity(PathSensitivity.RELATIVE)
            outputs.file(snapshotFile)

            doLast {
                val target = snapshotFile.asFile
                target.parentFile.mkdirs()
                target.writeText(
                    publicApiSnapshot(
                        moduleName,
                        classesDir.get().asFile,
                        apiSnapshotLauncher
                            .get()
                            .metadata
                            .installationPath
                            .asFile,
                    ),
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
            inputs.property(
                "apiSnapshotRuntimeVersion",
                apiSnapshotRuntimeVersion,
            )

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
                        apiSnapshotLauncher
                            .get()
                            .metadata
                            .installationPath
                            .asFile,
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

val stageConsumerPublications = tasks.register("stageConsumerPublications") {
    group = "release"
    description = "Stages every 0.1.0 Maven publication for isolated consumer testing."
    dependsOn(
        mavenPublicationModules
            .sorted()
            .map { ":$it:stageConsumerPublication" }
    )
}

val verifyExternalMavenConsumer = tasks.register<Exec>("verifyExternalMavenConsumer") {
    group = "verification"
    description = "Resolves and runs Bounded Origin from an isolated Maven consumer project."
    dependsOn(stageConsumerPublications)

    val consumerProject = layout.projectDirectory.dir("release-tests/consumer")
    val repositoryUri = consumerStagingDirectory.map { it.asFile.toURI().toString() }
    val wrapper =
        layout.projectDirectory.file(
            if (System.getProperty("os.name").startsWith("Windows")) {
                "gradlew.bat"
            } else {
                "gradlew"
            }
        )

    inputs.dir(consumerProject)
    inputs.dir(consumerStagingDirectory)

    workingDir(rootDir)
    executable(wrapper.asFile.absolutePath)
    args(
        "--no-daemon",
        "-p",
        consumerProject.asFile.absolutePath,
        "clean",
        "run",
        "-PboundedOriginRepository=${repositoryUri.get()}",
        "-PboundedOriginVersion=$releaseVersion",
        "--stacktrace",
    )
}

val stageCentralPublications = tasks.register("stageCentralPublications") {
    group = "release"
    description = "Stages every signed 0.1.0 Maven publication for Central."
    dependsOn(
        mavenPublicationModules
            .sorted()
            .map { ":$it:stageCentralPublication" }
    )
}

fun expectedCentralFiles(
    modules: Set<String>,
    groupId: String,
    version: String,
): Set<String> {
    val groupPath = groupId.replace('.', '/')
    val checksumExtensions = listOf("md5", "sha1", "sha256", "sha512")
    return buildSet {
        modules.sorted().forEach { module ->
            val prefix = "$groupPath/$module/$version/$module-$version"
            val primaries =
                listOf(
                    "$prefix.jar",
                    "$prefix-sources.jar",
                    "$prefix-javadoc.jar",
                    "$prefix.pom",
                )
            primaries.forEach { primary ->
                add(primary)
                add("$primary.asc")
                checksumExtensions.forEach { extension ->
                    add("$primary.$extension")
                }
            }
        }
    }
}

val verifyCentralStaging = tasks.register("verifyCentralStaging") {
    group = "verification"
    description = "Verifies the exact signed Maven Repository Layout accepted by Central."
    dependsOn(stageCentralPublications)
    inputs.dir(centralStagingDirectory)

    doLast {
        val staging = centralStagingDirectory.get().asFile
        val expected =
            expectedCentralFiles(
                mavenPublicationModules,
                releaseGroupId,
                releaseVersion,
            )
        val files =
            staging
                .walkTopDown()
                .filter(File::isFile)
                .toList()

        val symbolicLinks =
            files.filter { java.nio.file.Files.isSymbolicLink(it.toPath()) }
        if (symbolicLinks.isNotEmpty()) {
            throw GradleException(
                "Central staging contains symbolic links: " +
                    symbolicLinks.joinToString { it.relativeTo(staging).path }
            )
        }

        val actual =
            files
                .map { it.relativeTo(staging).invariantSeparatorsPath }
                .toSet()
        if (actual != expected) {
            throw GradleException(
                "Central staging file set mismatch; " +
                    "missing=${(expected - actual).sorted()}, " +
                    "unexpected=${(actual - expected).sorted()}"
            )
        }

        val checksumAlgorithms =
            linkedMapOf(
                "md5" to "MD5",
                "sha1" to "SHA-1",
                "sha256" to "SHA-256",
                "sha512" to "SHA-512",
            )
        val groupPath = releaseGroupId.replace('.', '/')

        mavenPublicationModules.sorted().forEach { module ->
            val directory = File(staging, "$groupPath/$module/$releaseVersion")
            val base = "$module-$releaseVersion"
            val primaryNames =
                listOf(
                    "$base.jar",
                    "$base-sources.jar",
                    "$base-javadoc.jar",
                    "$base.pom",
                )

            primaryNames.forEach { primaryName ->
                val primary = File(directory, primaryName)
                if (primary.length() == 0L) {
                    throw GradleException("Empty Central publication file: $primary")
                }

                val signature = File(directory, "$primaryName.asc")
                val signatureText = signature.readText(Charsets.US_ASCII)
                if (!signatureText.contains("-----BEGIN PGP SIGNATURE-----") ||
                    !signatureText.contains("-----END PGP SIGNATURE-----")
                ) {
                    throw GradleException("Malformed armored OpenPGP signature: $signature")
                }

                checksumAlgorithms.forEach { (extension, algorithm) ->
                    val checksumFile = File(directory, "$primaryName.$extension")
                    val expectedChecksum = digestHex(primary, algorithm)
                    val actualChecksum = checksumFile.readText(Charsets.US_ASCII).trim()
                    if (actualChecksum != expectedChecksum) {
                        throw GradleException(
                            "Checksum mismatch for $primaryName.$extension"
                        )
                    }
                }
            }

            validatePublicationArchives(
                File(directory, "$base.jar"),
                File(directory, "$base-sources.jar"),
                File(directory, "$base-javadoc.jar"),
            )
            validateMavenPom(
                File(directory, "$base.pom"),
                module,
                releaseVersion,
                mavenPublicationNames.getValue(module),
                mavenPublicationDescriptions.getValue(module),
                module in apacheLicensedPublicationModules,
                mavenPublicationModules,
            )
        }
    }
}

val centralBundleZip = tasks.register<Zip>("centralBundleZip") {
    group = "release"
    description = "Creates the verified Maven Central upload bundle."
    dependsOn(verifyCentralStaging)
    archiveFileName.set("bounded-origin-$releaseVersion-central-bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(centralStagingDirectory)
    includeEmptyDirs = false
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val verifyCentralBundle = tasks.register("verifyCentralBundle") {
    group = "verification"
    description = "Verifies the Central ZIP contains exactly the staged Maven files."
    dependsOn(centralBundleZip)

    val archive = centralBundleZip.flatMap { it.archiveFile }
    inputs.file(archive)
    inputs.dir(centralStagingDirectory)

    doLast {
        val staging = centralStagingDirectory.get().asFile
        val expected =
            expectedCentralFiles(
                mavenPublicationModules,
                releaseGroupId,
                releaseVersion,
            )

        ZipFile(archive.get().asFile).use { zip ->
            val entries = zip.entries()
            val actual = linkedSetOf<String>()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) {
                    actual += entry.name
                }
            }
            if (actual != expected) {
                throw GradleException(
                    "Central bundle ZIP file set mismatch; " +
                        "missing=${(expected - actual).sorted()}, " +
                        "unexpected=${(actual - expected).sorted()}"
                )
            }

            expected.forEach { path ->
                val entry = zip.getEntry(path)
                    ?: throw GradleException("Missing ZIP entry $path")
                val archivedHash =
                    zip.getInputStream(entry).use { digestHex(it, "SHA-256") }
                val stagedHash = sha256(File(staging, path))
                if (archivedHash != stagedHash) {
                    throw GradleException("Central ZIP content differs from staging for $path")
                }
            }
        }
    }
}

tasks.register("centralBundle") {
    group = "release"
    description = "Builds and verifies the signed Maven Central 0.1.0 upload bundle."
    dependsOn(verifyCentralBundle)
}

val everyCleanTask =
    allprojects.map { project ->
        project.tasks.named("clean")
    }

allprojects {
    tasks
        .matching { it.name.startsWith("spotless") }
        .configureEach {
            mustRunAfter(everyCleanTask)
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

val verifyReleasePublicationSurface = tasks.register("verifyReleasePublicationSurface") {
    group = "verification"
    description = "Verifies that every module has exactly one 0.1.0 release classification."

    doLast {
        val classifications =
            listOf(
                mavenPublicationModules,
                githubReleaseDistributionModules,
                internalModules,
            )
        val duplicateClassifications =
            classifications
                .flatten()
                .groupingBy { it }
                .eachCount()
                .filterValues { it != 1 }
                .keys
        if (duplicateClassifications.isNotEmpty()) {
            throw GradleException(
                "Modules have multiple release classifications: " +
                    duplicateClassifications.sorted().joinToString()
            )
        }

        val publicationMetadataSets =
            listOf(
                mavenPublicationNames.keys,
                mavenPublicationDescriptions.keys,
            )
        if (publicationMetadataSets.any { it != mavenPublicationModules }) {
            throw GradleException(
                "Maven publication metadata must exactly match the frozen publication surface"
            )
        }
        if (!apacheLicensedPublicationModules.all { it in mavenPublicationModules }) {
            throw GradleException("Publication license classification names an unpublished module")
        }

        val actualModules = subprojects.map { it.name }.toSet()
        val classifiedModules = classifications.flatten().toSet()
        val missing = actualModules - classifiedModules
        val unknown = classifiedModules - actualModules
        if (missing.isNotEmpty() || unknown.isNotEmpty()) {
            throw GradleException(
                "Release publication surface does not match repository modules; " +
                    "unclassified=${missing.sorted()}, unknown=${unknown.sorted()}"
            )
        }
    }
}

val verifyReleaseRevision = tasks.register("verifyReleaseRevision") {
    group = "verification"
    description = "Verifies the source tree is the exact 0.1.0 release revision."

    val changelog = layout.projectDirectory.file("CHANGELOG.md")
    inputs.property("releaseVersion", releaseVersion)
    inputs.file(changelog)

    doLast {
        if (rootProject.version.toString() != releaseVersion || releaseVersion.endsWith("-SNAPSHOT")) {
            throw GradleException("Root project must be release version $releaseVersion")
        }

        if (releaseTag != "v$releaseVersion") {
            throw GradleException(
                "Release tag $releaseTag does not match release version $releaseVersion"
            )
        }
        if (releaseArtifactBaseName != "bounded-origin-$releaseVersion") {
            throw GradleException(
                "Release artifact base name $releaseArtifactBaseName does not match release version $releaseVersion"
            )
        }

        val mismatchedModules =
            subprojects
                .filter { it.version.toString() != releaseVersion }
                .map { "${it.name}=${it.version}" }
        if (mismatchedModules.isNotEmpty()) {
            throw GradleException(
                "All modules must use release version $releaseVersion: " +
                    mismatchedModules.joinToString()
            )
        }

        val headings = changelog.asFile.readLines(Charsets.UTF_8).map(String::trim)
        if ("## $releaseVersion" !in headings) {
            throw GradleException("CHANGELOG.md must contain a $releaseVersion release heading")
        }
        if ("## Unreleased" in headings) {
            throw GradleException("Release revision must not retain an Unreleased heading")
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
        verifyReleasePublicationSurface,
        verifyReleaseRevision,
        "spotlessCheck",
    )
}
