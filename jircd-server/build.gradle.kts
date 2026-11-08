plugins {
    application
    // Experimental native-executable variant (spec 016, User Story 3). Applying the plugin adds the
    // nativeCompile task; `build` never runs it, so building needs no GraalVM.
    alias(libs.plugins.graalvm.native)
}

application {
    mainClass.set("net.jircd.server.JircdServerApplication")
}

dependencies {
    implementation(project(":jircd-protocol"))
    implementation(project(":jircd-core"))
    testImplementation(testFixtures(project(":jircd-core")))
    implementation(rootProject.libs.slf4j.api)
    // Compile-time, not runtimeOnly: jircd-server configures logback itself from the `logging`
    // YAML section (spec 016) instead of shipping a logback.xml.
    implementation(rootProject.libs.logback.classic)

    // Runtime-only: these are discovered via ServiceLoader (net.jircd.core.extension
    // .CapabilityExtension / .ServerExtension), never referenced by jircd-server's own
    // source code — research.md "Extension system".
    runtimeOnly(project(":jircd-capabilities:message-tags"))
    runtimeOnly(project(":jircd-capabilities:server-time"))
    runtimeOnly(project(":jircd-capabilities:echo-message"))
    runtimeOnly(project(":jircd-capabilities:away-notify"))
    runtimeOnly(project(":jircd-capabilities:batch"))
    runtimeOnly(project(":jircd-capabilities:multi-prefix"))
    runtimeOnly(project(":jircd-capabilities:userhost-in-names"))
    runtimeOnly(project(":jircd-server-extensions:cloak"))
    runtimeOnly(project(":jircd-server-extensions:admin"))
}

// Native image (spec 016 research R4). `nativeCompile` needs a GraalVM JDK; the container build
// runs it in the native-build stage. toolchainDetection is off so it uses the JDK it runs on.
graalvmNative {
    toolchainDetection.set(false)
    // Reachability metadata for third-party libraries (logback, slf4j), from the plugin's
    // pinned repository version. The rest lives in src/main/resources/META-INF/native-image.
    metadataRepository { enabled.set(true) }
    binaries {
        named("main") {
            imageName.set("jircd")
            mainClass.set("net.jircd.server.JircdServerApplication")
            buildArgs.addAll(
                "--no-fallback",
                // Runs on any x86-64-v1 host, not only on a CPU like the build machine's.
                "-march=compatibility",
                "-H:+ReportExceptionStackTraces",
            )
        }
    }
}

val versionResourceDir = layout.buildDirectory.dir("generated/version-resource")

val generateVersionResource = tasks.register("generateVersionResource") {
    val versionProperty = project.version.toString()
    val outputDirProvider = versionResourceDir
    inputs.property("version", versionProperty)
    outputs.dir(outputDirProvider)
    doLast {
        val target = outputDirProvider.get().dir("net/jircd/server").asFile
        target.mkdirs()
        target.resolve("version.properties").writeText("version=$versionProperty\n")
    }
}

sourceSets {
    named("main") {
        resources.srcDir(versionResourceDir)
    }
}

tasks.named("processResources") {
    dependsOn(generateVersionResource)
}

// Spec 016 T037 (quickstart section 5): regenerates the native-image reachability metadata that
// the libraries' own repository lacks. Run it on a GraalVM JDK (for example in the GraalVM
// container image, see validation/native.md) and review the result before committing:
//
//   ./gradlew :jircd-server:collectNativeMetadata
//
// It runs the installed server under the tracing agent while the "native-metadata" scenarios in
// jircd-integration-tests drive it over sockets, then keeps only entries for the packages below.
val nativeAgentOutput = layout.buildDirectory.dir("native-agent")
val nativeMetadataFile =
    layout.projectDirectory.file(
        "src/main/resources/META-INF/native-image/net.jircd/jircd-server/reachability-metadata.json"
    )

tasks.register("collectNativeMetadata") {
    group = "build"
    description = "Regenerates reachability-metadata.json by tracing the server (needs GraalVM)."
    dependsOn(":jircd-integration-tests:nativeMetadataScenarios")
    val agentOutput = nativeAgentOutput
    val target = nativeMetadataFile
    doLast {
        val raw = agentOutput.get().file("reachability-metadata.json").asFile
        check(raw.isFile) {
            "the agent wrote no reachability-metadata.json in ${agentOutput.get()}: " +
                "is Gradle running on a GraalVM JDK 25 or newer?"
        }
        val keptPackages = listOf("org.yaml", "com.password4j", "ch.qos.logback", "net.jircd")
        val keptPaths = keptPackages.map { it.replace('.', '/') }
        fun typeName(entry: Map<*, *>): String? = entry["type"]?.toString()
        fun keepType(entry: Map<*, *>): Boolean {
            val name = typeName(entry) ?: return false
            return keptPackages.any { name == it || name.startsWith("$it.") }
        }
        // password4j reads its own settings from a top-level psw4j.properties.
        val keptFiles = listOf("psw4j.properties")
        fun keepResource(entry: Map<*, *>): Boolean {
            val glob = entry["glob"]?.toString()?.removePrefix("/") ?: return false
            if (glob in keptFiles) return true
            val serviceName = glob.removePrefix("META-INF/services/")
            return keptPaths.any { glob.startsWith(it) } ||
                (serviceName != glob && keptPackages.any { serviceName.startsWith("$it.") })
        }

        @Suppress("UNCHECKED_CAST")
        val parsed = groovy.json.JsonSlurper().parse(raw) as Map<String, Any?>
        val filtered = linkedMapOf<String, Any?>()
        for ((section, value) in parsed) {
            val entries = value as? List<*> ?: continue
            val kept =
                when (section) {
                    "resources" -> entries.filterIsInstance<Map<*, *>>().filter(::keepResource)
                    "bundles" -> emptyList()
                    else -> entries.filterIsInstance<Map<*, *>>().filter(::keepType)
                }
            // Stable order, so regenerating produces a reviewable diff.
            filtered[section] =
                kept.sortedBy { (it["type"] ?: it["glob"] ?: it["name"] ?: "").toString() }
        }
        val out = target.asFile
        out.parentFile.mkdirs()
        out.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(filtered)) + "\n")
        logger.lifecycle("Wrote ${out} (${out.length()} bytes)")
    }
}
