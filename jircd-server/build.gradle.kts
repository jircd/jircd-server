plugins {
    application
}

application {
    mainClass.set("net.jircd.server.JircdServerApplication")
}

dependencies {
    implementation(project(":jircd-protocol"))
    implementation(project(":jircd-core"))
    implementation(rootProject.libs.slf4j.api)
    runtimeOnly(rootProject.libs.logback.classic)

    // Runtime-only: these are discovered via ServiceLoader (net.jircd.core.extension
    // .CapabilityExtension / .ServerExtension), never referenced by jircd-server's own
    // source code — research.md "Extension system".
    runtimeOnly(project(":jircd-capabilities:message-tags"))
    runtimeOnly(project(":jircd-capabilities:server-time"))
    runtimeOnly(project(":jircd-capabilities:echo-message"))
    runtimeOnly(project(":jircd-capabilities:away-notify"))
    runtimeOnly(project(":jircd-capabilities:batch"))
    runtimeOnly(project(":jircd-server-extensions:cloak"))
    runtimeOnly(project(":jircd-server-extensions:admin"))
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
