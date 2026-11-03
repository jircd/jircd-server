# Contract: jircd container image

**Feature**: 016-container-deployment. This contract applies to both variants (`jvm`, `native`). Changing any item is a user-facing change under constitution Principle III.

## Image reference

`ghcr.io/jircd/jircd-server:<tag>`

| Tag | Points at | Mutable |
|---|---|---|
| `jvm-sha-<7 hex>` / `native-sha-<7 hex>` | the build of that `main` commit | no |
| `jvm` / `native` | the latest `main` build that passed CI | yes |
| `latest` | same as `jvm` | yes |
| `X.Y.Z-jvm` / `X.Y.Z-native` / `X.Y.Z` (= jvm) | release `vX.Y.Z` | no |

The `jvm` tags are published for linux/amd64 and linux/arm64. The `native` tags are published for linux/amd64 only, and the variant is experimental.

## Runtime contract

| Item | Value |
|---|---|
| Process | The server is PID 1. There is no init or shell wrapper |
| User | uid 65532, gid 65532 (`nonroot`) |
| Default argument (configuration path) | `/etc/jircd/jircd.yaml`; any other path can be passed as the container command |
| Ports declared | `6667/tcp` (plaintext), `6697/tcp` (TLS). These are only declarations: the configured listeners decide what is bound |
| Writable paths needed by default | none. The image runs with `--read-only` |
| Logs | standard output, unless the configuration sets `logging.file` ([logging-config.md](logging-config.md)) |
| Recommended mounts | `/etc/jircd/` read-only (configuration, TLS certificate and key, MOTD). A writable volume only if `logging.file` is used, for example `/var/log/jircd` |
| Working directory | `/opt/jircd`; relative paths in the configuration resolve here (read-only) |

## Signals

| Signal | Effect |
|---|---|
| `HUP` (`docker kill -s HUP`) | Reload the configuration, the same as on a host install. An invalid configuration is rejected and the running one is kept |
| `TERM` (`docker stop`) / `INT` | Stop accepting connections, then send every client `ERROR :Closing Link: <host> (Server shutting down)` and close the connections, all within 8 s. Exit status 0 |

## Exit status

| Status | Cause |
|---|---|
| 0 | Normal shutdown on TERM or INT |
| 1 | Startup refused: configuration missing, unreadable or invalid, a listener could not bind, or a TLS certificate could not be loaded. The single log line before exit starts with `Refusing to start:` and names the path or setting |

## Startup error messages (stable wording)

- `Refusing to start: Configuration file not found: <path>`
- `Refusing to start: Configuration file not readable by this user: <path>`
- `Refusing to start: <setting>: <reason>`: the existing validation messages, unchanged

## Image metadata (OCI labels)

`org.opencontainers.image.source`, `org.opencontainers.image.revision`, `org.opencontainers.image.version`, `org.opencontainers.image.licenses=Apache-2.0`, `org.opencontainers.image.title=jircd`, and `org.opencontainers.image.description`, which names the variant.

## Native variant differences (documented, not contract breaks)

- Capabilities and server extensions are fixed when the executable is built.
- It uses the GraalVM Community Edition Serial garbage collector.
- Its support status is experimental (spec FR-016).
