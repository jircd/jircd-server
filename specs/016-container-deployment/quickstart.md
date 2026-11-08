# Quickstart & Validation: Container Deployment

**Feature**: 016-container-deployment

These scenarios prove the feature end to end. Steps 1–4 need only a container runtime, Docker or Podman (FR-017). Steps 5–7 are maintainer tasks.

## 1. Build both variants

```bash
docker build --target jvm    -t jircd:jvm    .
docker build --target native -t jircd:native .   # amd64; takes several minutes and about 8 GB RAM
```

Expected: both builds succeed from a clean checkout, with no Java or GraalVM installed on the host.

On Apple Silicon (Docker Desktop's engine is linux/arm64), both targets build for arm64 natively. The local `native` image is an arm64 executable. That's fine for running the contract suite and checking native metadata, but the published native image and the performance comparison are amd64 builds from CI. Don't build `--platform linux/amd64 --target native` locally: under emulation it takes far longer. Give Docker Desktop at least 8 GB of memory for the native build.

## 2. Run with a mounted configuration

Use the sample at `deploy/container/jircd.yaml` (TLS listener disabled) or your own:

```bash
docker run -d --read-only -p 6667:6667 -p 6697:6697 \
  -v "$PWD/deploy/container:/etc/jircd:ro" --name jircd jircd:jvm
```

Expected:

- `docker logs jircd` shows `jircd started as <serverName>`.
- An IRC client connecting to `localhost:6667` registers, joins a channel and exchanges messages (spec Story 1, scenario 1).
- `--read-only` causes no write errors (SC-007).

For TLS: put `cert.pem` and `key.pem` in the mounted directory (both must be readable by uid 65532, for example in a temporary directory: `chmod 644 cert.pem; chmod 640 key.pem; sudo chgrp 65532 key.pem`, and the directory and its parents traversable by that uid), point the TLS listener at `/etc/jircd/cert.pem` and `/etc/jircd/key.pem`, and connect a TLS client to `localhost:6697` (Story 1, scenario 2).

## 3. Reload and stop

```bash
# edit deploy/container/jircd.yaml (e.g. change the MOTD path or a capability toggle)
docker kill -s HUP jircd     # logs "Configuration reloaded via SIGHUP"; clients stay connected
docker stop jircd            # clients receive ERROR :Closing Link: … (Server shutting down)
docker inspect jircd --format '{{.State.ExitCode}}'   # 0 (the container was started without --rm)
docker rm jircd
```

Expected: the outcomes of Story 2, scenarios 1–2. Making the file invalid before sending HUP leaves the server running on the old configuration, with a WARN explaining why (scenario 3).

## 4. Failure messages

```bash
docker run --rm jircd:jvm                                # no mount
# -> "Refusing to start: Configuration file not found: /etc/jircd/jircd.yaml", exit status 1
chmod 600 deploy/container/jircd.yaml && docker run --rm -v "$PWD/deploy/container:/etc/jircd:ro" jircd:jvm
# -> "Refusing to start: Configuration file not readable by this user: /etc/jircd/jircd.yaml" (uid 65532)
```

## 5. Regenerate native-image metadata (maintainers)

Do this after changing SnakeYAML, password4j, logback or resources:

```bash
./gradlew :jircd-server:collectNativeMetadata   # needs a GraalVM JDK 25: runs the installed server
                                                # under the tracing agent while the
                                                # NativeMetadataScenariosTest drives it, then filters
                                                # and writes into
                                                # jircd-server/src/main/resources/META-INF/native-image/
git diff jircd-server/src/main/resources/META-INF/native-image/
```

Without a local GraalVM, run the same task in the GraalVM image on a scratch copy of the checkout (never in the worktree, so its `build/` stays yours) and copy the file back; the exact command is in [validation/native.md](validation/native.md). Then rebuild the native image (step 1) and run step 6 against it.

## 6. Automated container smoke tests

```bash
./gradlew :jircd-integration-tests:containerTest -Djircd.image=jircd:jvm
./gradlew :jircd-integration-tests:containerTest -Djircd.image=jircd:native
```

Expected: the same test classes pass against both images. They cover registration over plaintext and TLS, messaging, `OPER` with an Argon2 credential, HUP reload, TERM shutdown with 100 clients in under 10 s with every client that can still receive output before the deadline receiving `ERROR` (SC-003, FR-009 exceptions), the startup failure messages, and a read-only root filesystem.

## 7. Native-versus-JVM comparison (weekly CI, or on demand)

```bash
./gradlew :jircd-integration-tests:containerLoadTest -Djircd.image=jircd:jvm
./gradlew :jircd-integration-tests:containerLoadTest -Djircd.image=jircd:native
```

Run locally, this is a quick check on the developer's architecture. The recorded comparison comes from the weekly amd64 CI run.

Expected: both meet the 1-second delivery budget at 1,000 connections (SC-004). Each run prints p50/p99/max latency, throughput, time from container start to the server listening (SC-005, reported, not asserted) and resident memory with 100 idle clients (SC-006). The weekly workflow writes both runs side by side in the job summary. Record a summary, not raw data, in `validation/` (repo rule: no raw validation data).
