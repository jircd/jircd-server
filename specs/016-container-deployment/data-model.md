# Data Model: Container Deployment

**Feature**: 016-container-deployment | **Date**: 2026-11-03

## LoggingSettings (configuration)

A new immutable part of `ServerConfiguration`, parsed from the optional top-level `logging` YAML section. The full schema is in [contracts/logging-config.md](contracts/logging-config.md).

| Field | Type | Default | Rules |
|---|---|---|---|
| `console` | boolean | `true` | Must be a YAML boolean. Any other type is rejected and the message names `logging.console`. |
| `file` | path, optional | absent (no log file) | A non-blank string. A relative path is resolved against the process working directory. |

**Validation**, at startup and on every reload, before anything is published:

1. `logging` is absent or a mapping. Unknown keys are rejected, the same as other sections.
2. `console: false` with no `file` is rejected: "logging: console is off and no file is set; the server would produce no log output".
3. When `file` is set:
   - Its parent directory must exist, or the message is "logging.file directory does not exist: <dir>".
   - The directory must be writable, or the message is "logging.file directory is not writable: <dir>".
   - If the file already exists, it must be a regular, writable file.
4. A rejected `logging` section rejects the whole configuration: startup refuses to run, and a reload leaves everything in place (FR-006a, FR-008).

**Fixed, not configurable** (spec Assumptions):

- The pattern `%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n`
- Root level INFO
- File rotation: daily plus size, 50 MB per file, 30 days, 1 GB total, gzip, archive name `<file>.%d{yyyy-MM-dd}.%i.gz`

## Logging runtime state

| State | Meaning | Changes when |
|---|---|---|
| `BOOTSTRAP` | The console appender installed by the logback `Configurator` before the YAML is read | Process start |
| `APPLIED(settings)` | The root appenders match `settings` | After startup config load, and after each successful reload whose `logging` section differs from the current one |
| `EXTERNAL` | `-Dlogback.configurationFile` was set, so jircd never touches appenders | Process start. It never changes, and the YAML section is ignored and logged once |

Transition rules:

- **From `APPLIED(old)` to `APPLIED(new)`**:
  - The console appender is kept unchanged if `console` is unchanged.
  - The file appender is kept if `file` is unchanged. Otherwise a new one is started first, then attached, then the old one is detached and stopped.
  - If starting the new appender fails (for example, the directory was removed between validation and apply), the old appenders stay and a WARN is logged.
- **From `BOOTSTRAP` to `APPLIED`**: the same procedure, with the bootstrap console appender treated as `console: true`.

## Shutdown state (application)

| State | Accepts connections | Sends to sessions | Next |
|---|---|---|---|
| `RUNNING` | yes | normal traffic | TERM or INT moves to `DRAINING` |
| `DRAINING` | no (listeners closed) | `ERROR :Closing Link: <host> (Server shutting down)` enqueued once per live session; each writer is drained and closed | All sessions closed, or the 8 s budget runs out: `EXITED(0)` |
| `EXITED(0)` | — | — | Process exits with status 0. If the budget ran out, a WARN names how many connections were force-closed |

- A TERM or INT before `start()` has bound the listeners also leads straight to `EXITED(0)`.
- A further signal while `DRAINING` is ignored.

## Image variant (build output)

| Attribute | JVM variant | Native variant |
|---|---|---|
| Dockerfile target | `jvm` (default) | `native` |
| Architectures | linux/amd64, linux/arm64 | linux/amd64 |
| Runtime base | distroless Java 25, nonroot | distroless base (glibc), nonroot |
| Entrypoint | `java … net.jircd.server.JircdServerApplication` | `/opt/jircd/jircd` |
| Default argument | `/etc/jircd/jircd.yaml` | `/etc/jircd/jircd.yaml` |
| Tags | `jvm`, `jvm-sha-<7>`, `latest`, `X.Y.Z-jvm`, `X.Y.Z` | `native`, `native-sha-<7>`, `X.Y.Z-native` |
| Support status | supported | experimental (FR-016) |

Both variants share the container contract in [contracts/container-contract.md](contracts/container-contract.md).
