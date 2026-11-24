---
title: Run jircd in a container
description: Pull or build the jircd image, mount a configuration, and reload or stop the server.
sidebar:
  order: 1
---

Images are published to the GitHub Container Registry as `ghcr.io/jircd/jircd-server` in two variants. The JVM variant is the supported one (linux/amd64 and linux/arm64). The [native variant](#the-native-variant) is experimental (linux/amd64 only).

## Image tags

| Tag | Points at |
| --- | --- |
| `jvm`, `latest` | the latest `main` build of the JVM variant that passed CI (both move together) |
| `jvm-sha-<7 hex>` | the JVM build of that `main` commit; never moves |
| `X.Y.Z-jvm`, `X.Y.Z` | JVM image of release `vX.Y.Z`; never moves |
| `native` | the latest `main` build of the native variant that passed CI |
| `native-sha-<7 hex>` | the native build of that `main` commit; never moves |
| `X.Y.Z-native` | native image of release `vX.Y.Z`; never moves |

`latest` is always the JVM variant. `jvm`, `latest` and `native` can stay on an older build: a commit skips moving them when a newer commit is already on `main`, so if that newer commit then fails CI, the tags stay put until a later `main` build passes. A release's native image is published only if its own smoke test passes, independently of the JVM image.

## Start it

Ensure Docker Engine is running before pulling or starting the image.

Create `/srv/jircd` and download the sample as a raw YAML file. Edit `serverName` to a hostname that identifies your server, then keep the configuration, certificates and keys outside the repository:

```bash
sudo mkdir -p /srv/jircd
sudo curl -fsSLo /srv/jircd/jircd.yaml https://raw.githubusercontent.com/jircd/jircd-server/main/deploy/container/jircd.yaml
sudoedit /srv/jircd/jircd.yaml
```

The sample sets `serverName: irc.example.org`; replace it with your server's hostname.

```bash
docker pull ghcr.io/jircd/jircd-server:jvm
docker run -d --read-only -p 6667:6667 -p 6697:6697 \
  -v /srv/jircd:/etc/jircd:ro --name jircd ghcr.io/jircd/jircd-server:jvm
docker logs --tail 50 jircd
```

The logs should include `jircd started as <serverName>` and a listener-started line. To check a plaintext listener, run `printf 'NICK alice\r\nUSER alice 0 * :Alice\r\n' | nc -w 2 localhost 6667`; a running server replies with a `001` welcome. Logs go to standard output and are available with `docker logs jircd`.

## Build the image yourself

From a clean checkout you need only a container runtime. A plain `docker build .` without `--target` builds the same JVM image:

```bash
docker build --target jvm -t jircd:jvm .
docker run -d --read-only -p 6667:6667 -p 6697:6697 \
  -v /srv/jircd:/etc/jircd:ro --name jircd jircd:jvm
```

`--target native` builds the native variant instead (`docker build --target native -t jircd:native .`). It compiles for your machine's architecture and needs several GB of memory and a few minutes.

## What to know

- **Configuration path.** The server reads `/etc/jircd/jircd.yaml`. Pass another path as the container command to change that. See [Configuration](configuration.md) for every key.
- **User.** The server is PID 1 and runs as uid/gid 65532. Mounted files must be readable by that user (a private key: mode 640, group 65532), and the directory and all its ancestors must be traversable by it.
- **Ports.** 6667 (plaintext) and 6697 (TLS) are declared. The configured listeners decide what is bound, so publish what you configured. Port 6697 answers only once a TLS listener is configured.
- **Read-only root.** Nothing is written to the container's filesystem by default, so `--read-only` works. The working directory `/opt/jircd` is read-only: use absolute paths for every path in the configuration.
- **Logs.** They go to standard output (`docker logs jircd`). For a log file, mount a volume writable by uid 65532 and set `logging.file`, as in [Logging](logging.md): `mkdir -p /srv/jircd-logs && sudo chown 65532:65532 /srv/jircd-logs`, then `-v /srv/jircd-logs:/var/log/jircd` with `file: /var/log/jircd/jircd.log`. A new Docker named volume is root-owned, so the server refuses to start with it.
- **Reload and stop.** After editing the mounted file, reload with `docker kill -s HUP jircd` and confirm with `docker logs --tail 50 jircd` (a clean reload logs `Configuration reloaded via SIGHUP`). Stop with `docker stop jircd` (see [Reload and stop](reload-stop.md)). A missing or unreadable configuration exits with status 1 and a `Refusing to start:` line. On Docker Desktop, wait a few seconds after saving the configuration before sending HUP: bind mounts can show the old file, or none, just after a change.
- **OPER.** Passwords must not cross the plaintext listener. The sample sets `operRequireTls: true`, so enable a TLS listener before using `OPER` ([OPER protection](oper-protection.md)).

## Compose

A minimal Compose file for the same setup (optional; the `docker run` above is enough):

```yaml
services:
  jircd:
    image: ghcr.io/jircd/jircd-server:jvm
    container_name: jircd   # so `docker kill -s HUP jircd` finds it
    read_only: true
    ports: ["6667:6667", "6697:6697"]
    volumes: ["/srv/jircd:/etc/jircd:ro"]
    stop_grace_period: 10s   # the default; the server drains within 8 s
```

## The native variant

`ghcr.io/jircd/jircd-server:native` runs the same server as a GraalVM native executable on distroless `base`, with the same configuration file, ports, user, signals and exit statuses as the JVM image: the container test suite runs unchanged against both. It is **experimental**:

- **Extensions are fixed at build time.** The nine built-in capabilities and extensions are compiled in and configured with `capabilities` and `server-extensions` as usual, but you cannot add or swap extension jars afterwards. Use the JVM image for that.
- **Serial garbage collector.** The executable uses GraalVM's default Serial GC and a heap that GraalVM sizes itself. Latency and throughput can differ from the JVM image under heavy load; CI records both side by side in the weekly load-test summary.
- **linux/amd64 only** for the published image. Build it yourself for another architecture.
- A native build or smoke-test failure fails CI, but a performance shortfall does not.

To run without a container, see [Run a standalone build](install-standalone.md).
