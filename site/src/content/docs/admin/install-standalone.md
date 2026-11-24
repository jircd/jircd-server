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

The server takes the configuration path as its first argument and defaults to `jircd.yaml` in the working directory. An empty file is valid and loads every default, including a server name based on the machine's hostname. To use a public hostname, set `serverName` in the configuration. For a first run:

```bash
cd jircd-server/build/install/jircd-server
touch jircd.yaml
bin/jircd-server >jircd.log 2>&1 &
echo $! > jircd.pid
tail -f jircd.log
```

With no `listeners` entry the server listens on port 6667, plaintext. The PID is saved in `jircd.pid`; logs are written to `jircd.log`, and the startup log should say `jircd started as <serverName>` and report the listener. `tail -f` keeps showing new log lines; use a second terminal in this directory for the connection, reload and stop commands below. To use another file: `bin/jircd-server /path/to/jircd.yaml`. A missing or unreadable file makes the server exit with status 1 and a `Refusing to start:` line.

Then connect with any IRC client, or a raw TCP tool:

```bash
nc localhost 6667
```

Type these IRC lines into the `nc` session (or paste them there):

```text
NICK alice
USER alice 0 * :Alice
JOIN #lobby
```

For a one-command check that prints the welcome reply, run `printf 'NICK alice\r\nUSER alice 0 * :Alice\r\n' | nc -w 2 localhost 6667`.

The empty configuration enables no optional capabilities or extensions, only core protocol behaviour. To turn on capabilities, hostname cloaking or in-band administration (`OPER`, `REHASH`), see [Configuration](configuration.md).

Reload the configuration with `kill -HUP "$(cat jircd.pid)"` and confirm `Configuration reloaded via SIGHUP` appears in `jircd.log`. Stop with `kill -TERM "$(cat jircd.pid)"` (or Ctrl-C if running in the foreground); see [Reload and stop](reload-stop.md).

## Archives

`distZip` and `distTar` produce a distributable archive in `jircd-server/build/distributions/`. Prebuilt archives are on the [Releases page](https://github.com/jircd/jircd-server/releases): pushing a `vX.Y.Z` tag builds and publishes them with the [release workflow](https://github.com/jircd/jircd-server/blob/main/.github/workflows/release.yml).

To run in a container instead, see [Run jircd in a container](install-container.md).
