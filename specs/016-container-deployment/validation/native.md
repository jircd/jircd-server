# Native variant validation (spec 016, User Story 3)

Summaries only. Local runs on Docker Desktop (engine `linux/arm64`, 8 GB, 14 CPUs), so the native image here is **arm64**. The authoritative amd64 numbers come from the `container-load` job (T044). Base: `origin/main` `e56b2aa`.

## Build

| Item | Result |
|---|---|
| Command | `docker buildx build --target native --load -t jircd:native .` |
| Builder image | `ghcr.io/graalvm/native-image-community:25i5@sha256:2800b5bd…` (multi-arch: amd64 and arm64 verified with `imagetools inspect`) |
| Runtime image | `gcr.io/distroless/base-debian13:nonroot@sha256:9039a887…` (multi-arch, same check) |
| Build time | about 1 min (Gradle cache mount warm); no out-of-memory kill |
| Image size | native **93.4 MB**, JVM 318 MB (`docker image ls`) |
| OCI labels | present, description names the experimental native variant |

## Reachability metadata (T037)

- **Tool**: `./gradlew :jircd-server:collectNativeMetadata`, run inside the GraalVM container image so nothing is installed locally: `docker run --rm --entrypoint ./gradlew -v <scratch copy>:/src -w /src -e GRADLE_USER_HOME=/tmp/gh ghcr.io/graalvm/native-image-community:25i5@sha256:… --no-daemon :jircd-server:collectNativeMetadata`.
- **How it works**: the `native-metadata` scenarios (`NativeMetadataScenariosTest`) start the installed server under `-agentlib:native-image-agent=config-merge-dir=…` and drive it over sockets: registration on plaintext, PEM TLS and PKCS12 TLS listeners, every capability, Argon2 and bcrypt `OPER` (right and wrong password), channel commands, a valid and a rejected `SIGHUP` reload, a logging file, and `SIGTERM` shutdown with a client connected. The task then keeps only entries for `org.yaml`, `com.password4j`, `ch.qos.logback` and `net.jircd` (plus the `psw4j.properties` resource password4j reads).
- **Result**: the agent's raw output had 79 reflection entries (mostly JDK security providers, which native-image configures itself) and 13 resources. The committed file keeps 11 reflection entries (the nine extension classes, the logging configurator, logback's service provider) and 8 resources (service files, version properties, `psw4j.properties`). Size: **1.8 KB**.
- SnakeYAML needed no reflection entry for this configuration schema.
- Before the metadata existed the native image refused to start (`net/jircd/server/version.properties resource is missing`); that failure is what the resource entries fix.

## Container suite (T039)

| Image | `containerTest` | 
|---|---|
| `jircd:native` (arm64) | **16 tests, 0 failed** (contract 7, failure 4, signal 5) |
| `jircd:jvm` (arm64) | **16 tests, 0 failed** |

- **TERM reaches `ShutdownSignalHandler`** on the native executable without `--install-exit-handlers`: the signal tests (graceful stop with 100 clients, exit status 0, `ERROR :Closing Link` to every client) pass. The flag is not added. This replaces the open check in research R2.
- The capability list and ISUPPORT tokens equal the JVM baseline (`container/jvm-baseline.txt`), with all seven capabilities and both server extensions enabled.
- Argon2 `OPER` and PKCS12 TLS registration pass on the native image.

## Start-up and load (T040, arm64)

Start-up (SC-005) is the Docker `State.StartedAt` of the container to the `Plaintext listener started` log line (`docker logs -t`, same daemon clock), when the port is bound. `ContainerDeliveryLoadTest` reports it as `startupMs` and prints whether it meets the 1,000 ms budget, but never fails on it (FR-016). An earlier `docker logs -t` check to the `jircd started as` line gave JVM about 181 ms and native about 43 ms.

`ContainerDeliveryLoadTest`: 1,000 clients, 30 messages paced at 110 ms, each delivered to 999 clients (29,970 deliveries). Throughput is bounded by that pacing, so compare it only between variants.

| Metric | JVM | Native |
|---|---:|---:|
| p50 latency | 14.2 ms | 15.9 ms |
| p99 latency | 29.9 ms | 27.3 ms |
| max latency | 31.5 ms | 29.1 ms |
| throughput | 9,314 msg/s | 9,300 msg/s |
| start-up, container start to listening (SC-005) | 236 ms | 66 ms |
| memory, 100 idle clients | 124.2 MiB | 17.7 MiB |

Every delivery is within the 1 s budget (SC-004) on both, and both meet SC-005 (the old figure of about 1.1 to 1.3 s included container creation and registering a client, so it did not measure SC-005). These are single local runs; CI gives the amd64 comparison.
