---
title: Modules
description: How JIRCD's Gradle modules depend on one another.
sidebar:
  order: 2
---

The protocol module supplies wire-format types to core. Core provides the server mechanisms used by optional capabilities and server extensions. The server application assembles them; integration tests exercise the running server over real sockets.

```text
jircd-protocol
      ↓
jircd-core
  ┌───┴────────────────────┐
  ↓                        ↓
jircd-capabilities/*   jircd-server-extensions/*
  └──────────┬─────────────┘
             ↓
       jircd-server

jircd-integration-tests → exercises server over sockets
```

| Gradle project | Role |
| --- | --- |
| `jircd-protocol` | IRC wire-format types. |
| `jircd-core` | Server core and extension APIs. |
| `jircd-capabilities:message-tags`, `server-time`, `echo-message`, `away-notify`, `batch`, `multi-prefix`, `userhost-in-names` | Separately packaged IRCv3 capability providers. |
| `jircd-server-extensions:cloak`, `admin` | Server extensions for operational behavior. |
| `jircd-server` | Application and `JircdServerApplication` entry point. |
| `jircd-integration-tests` | End-to-end tests against the server over real sockets. |

The project names and included subprojects are declared in [`settings.gradle.kts`](https://github.com/jircd/jircd-server/blob/main/settings.gradle.kts); the module responsibilities are summarized in [`AGENTS.md`](https://github.com/jircd/jircd-server/blob/main/AGENTS.md#modules).
