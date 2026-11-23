---
title: Run a standalone build
description: Build a distribution with Gradle and run jircd without Gradle or a container.
sidebar:
  order: 2
---

You need JDK 25 to build and to run the distribution.

`./gradlew :jircd-server:run` is for development only: it stays attached to Gradle and reads its configuration from `jircd-server/jircd.yaml` (create it first; an empty file is valid). For a standalone install, build a distribution:

```bash
./gradlew :jircd-server:installDist
```

This produces `jircd-server/build/install/jircd-server/`. It holds a `bin/jircd-server` launcher (and a `.bat` file) and every runtime dependency in `lib/`. The bundled capability and extension jars are in `lib/` too; the server finds them with `ServiceLoader` at startup.

The server takes the configuration path as its first argument and defaults to `jircd.yaml` in the working directory. An empty file is valid and loads every default, so for a first run:

```bash
cd jircd-server/build/install/jircd-server
touch jircd.yaml
bin/jircd-server
```

With no `listeners` entry the server listens on port 6667, plaintext. To use another file: `bin/jircd-server /path/to/jircd.yaml`. A missing or unreadable file makes the server exit with status 1 and a `Refusing to start:` line.

Then connect with any IRC client, or a raw TCP tool:

```bash
nc localhost 6667
NICK alice
USER alice 0 * :Alice
JOIN #lobby
```

The empty configuration enables no optional capabilities or extensions, only core protocol behaviour. To turn on capabilities, hostname cloaking or in-band administration (`OPER`, `REHASH`), see [Configuration](configuration.md).

Send `SIGHUP` to reload the configuration and `SIGTERM` or Ctrl-C to stop; see [Reload and stop](reload-stop.md).

## Archives

`distZip` and `distTar` produce a distributable archive in `jircd-server/build/distributions/`. Prebuilt archives are on the [Releases page](https://github.com/jircd/jircd-server/releases): pushing a `vX.Y.Z` tag builds and publishes them with the [release workflow](https://github.com/jircd/jircd-server/blob/main/.github/workflows/release.yml).

To run in a container instead, see [Run jircd in a container](install-container.md).
