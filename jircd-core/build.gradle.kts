plugins {
    `java-test-fixtures`
}

dependencies {
    implementation(project(":jircd-protocol"))
    implementation(rootProject.libs.slf4j.api)
    runtimeOnly(rootProject.libs.logback.classic)
    implementation(rootProject.libs.snakeyaml)
    testImplementation(rootProject.libs.logback.classic)
    // LogCapture (thread-safe log capture for tests, issue #124) is shared with the other modules'
    // tests. Its API exposes only slf4j types; logback stays an implementation detail of the
    // fixture.
    testFixturesApi(rootProject.libs.slf4j.api)
    testFixturesImplementation(rootProject.libs.logback.classic)
}
