dependencies {
    testImplementation(project(":jircd-server"))
    testImplementation(project(":jircd-core"))
    testImplementation(testFixtures(project(":jircd-core")))
    testImplementation(project(":jircd-protocol"))
    // Only to hash a real test password into administratorCredentials YAML — never to reference
    // jircd-server-extensions:admin directly, which stays runtime-only/ServiceLoader-discovered.
    testImplementation(rootProject.libs.password4j)
    // Only for ListAppender-based log-capture in tests (009-connection-monitoring-log) —
    // runtimeOnly everywhere else in this build, but tests need it on the compile classpath too.
    testImplementation(rootProject.libs.logback.classic)
    // Drives built container images in the "container" and "container-load" suites (spec 016).
    testImplementation(rootProject.libs.testcontainers)
}

// The 2,000-reader single-wave T003 probe exhausts 512 MiB and completes with a 2 GiB heap.
tasks.named<Test>("loadTest") {
    maxHeapSize = "2g"
}

// Spec 016: suites that run against a built image named by -Djircd.image=<image>. Neither runs
// as part of `test` or `build`, so the normal build never needs a container runtime.
val jircdImage = providers.systemProperty("jircd.image")
val testSourceSet = sourceSets.named("test")

fun registerContainerSuite(name: String, tag: String, heap: String) =
    tasks.register<Test>(name) {
        group = "verification"
        description = "Runs tests tagged \"$tag\" against the image given by -Djircd.image."
        useJUnitPlatform { includeTags(tag) }
        testClassesDirs = testSourceSet.get().output.classesDirs
        classpath = testSourceSet.get().runtimeClasspath
        maxHeapSize = heap
        // Every run starts fresh containers; never reuse results from an earlier image.
        outputs.upToDateWhen { false }
        val image = jircdImage.orNull
        inputs.property("jircd.image", image ?: "")
        if (image != null) systemProperty("jircd.image", image)
        // A tag with no tests behind it must not report success (it would hide a missing suite).
        var selected = 0L
        afterSuite(
            KotlinClosure2<TestDescriptor, TestResult, Unit>({ suite, result ->
                if (suite.parent == null) selected = result.testCount
            })
        )
        doLast {
            if (selected == 0L) {
                throw GradleException("$name selected no tests tagged \"$tag\"")
            }
        }
        doFirst {
            if (image.isNullOrBlank()) {
                throw GradleException("set -Djircd.image=<image>, e.g. -Djircd.image=jircd:jvm")
            }
        }
    }

registerContainerSuite("containerTest", "container", "1g")
registerContainerSuite("containerLoadTest", "container-load", "2g")
