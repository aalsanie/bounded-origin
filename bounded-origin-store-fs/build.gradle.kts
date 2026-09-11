description = "Filesystem-backed artifact store."

dependencies {
    api(project(":bounded-origin-api"))
}

tasks.named("check") {
    dependsOn("pitest")
}
