dependencies {
    testImplementation(project(":jircd-server"))
    testImplementation(project(":jircd-core"))
    testImplementation(project(":jircd-protocol"))
    // Only to hash a real test password into administratorCredentials YAML — never to reference
    // jircd-server-extensions:admin directly, which stays runtime-only/ServiceLoader-discovered.
    testImplementation(rootProject.libs.password4j)
    // Only for ListAppender-based log-capture in tests (009-connection-monitoring-log) —
    // runtimeOnly everywhere else in this build, but tests need it on the compile classpath too.
    testImplementation(rootProject.libs.logback.classic)
}

// The 2,000-reader single-wave T003 probe exhausts 512 MiB and completes with a 2 GiB heap.
tasks.named<Test>("loadTest") {
    maxHeapSize = "2g"
}
