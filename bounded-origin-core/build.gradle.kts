description = "Core policy and execution engine."

dependencies {
    api(project(":bounded-origin-api"))
}

tasks.named("check") {
    dependsOn("pitest")
}
