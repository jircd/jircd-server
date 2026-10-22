dependencies {
    implementation(project(":jircd-protocol"))
    implementation(rootProject.libs.slf4j.api)
    runtimeOnly(rootProject.libs.logback.classic)
    implementation(rootProject.libs.snakeyaml)
    testImplementation(rootProject.libs.logback.classic)
}
