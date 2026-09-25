plugins {
    `java-library`
}

description = "Reproducible benchmark harnesses."

val jmh = sourceSets.register("jmh").get()

configurations[jmh.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[jmh.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
jmh.compileClasspath += sourceSets.main.get().output
jmh.runtimeClasspath += sourceSets.main.get().output

listOf(
    jmh.compileClasspathConfigurationName,
    jmh.runtimeClasspathConfigurationName,
    jmh.annotationProcessorConfigurationName,
).forEach { name ->
    configurations[name].resolutionStrategy.activateDependencyLocking()
}

dependencies {
    implementation(project(":bounded-origin-api"))
    implementation(project(":bounded-origin-core"))
    implementation(project(":bounded-origin-proxy"))
    implementation(project(":bounded-origin-cli"))
    implementation(libs.snakeyaml.engine)
    add(jmh.implementationConfigurationName, libs.jmh.core)
    add(jmh.annotationProcessorConfigurationName, libs.jmh.generator.annprocess)
    testImplementation(jmh.output)
    testImplementation(libs.jmh.core)
}

tasks.named<JavaCompile>(jmh.compileJavaTaskName) {
    options.compilerArgs.add("-proc:none")
}

val generatedSources = layout.buildDirectory.dir("generated/jmh/sources")
val generatedResources = layout.buildDirectory.dir("generated/jmh/resources")
val generateJmhHarness = tasks.register<JavaCompile>("generateJmhHarness") {
    source(jmh.allJava)
    classpath = jmh.compileClasspath
    options.annotationProcessorPath = configurations[jmh.annotationProcessorConfigurationName]
    options.generatedSourceOutputDirectory.set(generatedSources)
    destinationDirectory.set(generatedResources)
    options.compilerArgs.add("-proc:only")
}

val compileJmhHarness = tasks.register<JavaCompile>("compileJmhHarness") {
    dependsOn(generateJmhHarness, jmh.classesTaskName)
    source(generatedSources)
    classpath = jmh.runtimeClasspath
    destinationDirectory.set(layout.buildDirectory.dir("classes/jmh-harness"))
    options.compilerArgs.add("-proc:none")
}

val benchmarkJar = tasks.register<Jar>("benchmarkJar") {
    archiveClassifier.set("benchmarks")
    from(sourceSets.main.get().output, jmh.output)
    from(compileJmhHarness, generatedResources)
}

val installBenchmarks = tasks.register<Sync>("installBenchmarks") {
    group = "benchmark"
    description = "Installs the standalone JMH harness and its locked dependencies."
    into(layout.buildDirectory.dir("benchmark/lib"))
    from(benchmarkJar, configurations[jmh.runtimeClasspathConfigurationName])
}

tasks.named("assemble") {
    dependsOn(benchmarkJar)
}

tasks.named<Test>("test") {
    dependsOn(installBenchmarks)
    val benchmarkJava = javaToolchains.launcherFor(java.toolchain)
    doFirst {
        systemProperty("benchmark.java", benchmarkJava.get().executablePath.asFile.absolutePath)
        systemProperty("benchmark.lib", layout.buildDirectory.dir("benchmark/lib").get().asFile.absolutePath)
    }
}
