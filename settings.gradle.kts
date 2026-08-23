rootProject.name = "jircd"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "jircd-protocol",
    "jircd-core",
    "jircd-capabilities",
    "jircd-capabilities:message-tags",
    "jircd-capabilities:server-time",
    "jircd-capabilities:echo-message",
    "jircd-capabilities:away-notify",
    "jircd-capabilities:batch",
    "jircd-server-extensions",
    "jircd-server-extensions:cloak",
    "jircd-server-extensions:admin",
    "jircd-server",
    "jircd-integration-tests",
)
