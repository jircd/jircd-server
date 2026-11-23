# JIRCD

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Quality gate status](https://sonarcloud.io/api/project_badges/measure?project=jircd_jircd-server&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=jircd_jircd-server)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=jircd_jircd-server&metric=bugs)](https://sonarcloud.io/summary/new_code?id=jircd_jircd-server)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=jircd_jircd-server&metric=code_smells)](https://sonarcloud.io/summary/new_code?id=jircd_jircd-server)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=jircd_jircd-server&metric=coverage)](https://sonarcloud.io/summary/new_code?id=jircd_jircd-server)
[![Duplicated Lines (%)](https://sonarcloud.io/api/project_badges/measure?project=jircd_jircd-server&metric=duplicated_lines_density)](https://sonarcloud.io/summary/new_code?id=jircd_jircd-server)

A modular IRCv3 chat server, written in Java, designed to be extended without touching its core.

JIRCD speaks classic IRC (RFC 1459/2812) and a defined set of IRCv3 capabilities. Core protocol
behavior is always present; capabilities, hostname cloaking and in-band administration are
independently loadable extensions. Authentication, accounts and server-to-server federation are
outside the initial release scope.

The initial release's six mandatory user stories are implemented and covered by integration tests.
The latest release is the pre-release [v0.1.0-alpha](https://github.com/jircd/jircd-server/releases/tag/v0.1.0-alpha);
`main` is a snapshot. See the [project site](https://jircd.org/) for an overview and current guides.

## Quick start: container

Images are published to GitHub Container Registry as `ghcr.io/jircd/jircd-server`. The `jvm` image
supports `linux/amd64` and `linux/arm64`; `latest` points to the JVM image. Start with the
[sample configuration](https://github.com/jircd/jircd-server/blob/main/deploy/container/jircd.yaml)
and put it, certificates and keys in `/srv/jircd`:

```bash
docker pull ghcr.io/jircd/jircd-server:jvm
docker run -d --read-only -p 6667:6667 -p 6697:6697 \
  -v /srv/jircd:/etc/jircd:ro --name jircd ghcr.io/jircd/jircd-server:jvm
```

The default configuration listens on port 6667 without TLS. Port 6697 is available only when you
configure a TLS listener. The container runs as uid/gid 65532; mounted configuration and key files
must be readable by that user. See [container installation](https://jircd.org/admin/install-container/)
for image tags, permissions, native images, Compose and logging.

## Quick start: standalone

Building and running require JDK 25. From a checkout:

```bash
./gradlew :jircd-server:installDist
cd jircd-server/build/install/jircd-server
touch jircd.yaml
bin/jircd-server
```

The standalone distribution includes runtime dependencies; Gradle is not needed to run it. The
empty configuration uses defaults and listens on port 6667 without TLS. To develop with Gradle,
use `./gradlew :jircd-server:run` (it needs `jircd-server/jircd.yaml`; an empty file is valid). See [standalone installation](https://jircd.org/admin/install-standalone/)
for archives and releases.

## Connect

In an IRC client, connect to the server address on port 6667 and choose a nickname. For a local
server, use `localhost`. A simple plaintext check with `nc` is:

```text
NICK alice
USER alice 0 * :Alice
JOIN #lobby
PRIVMSG #lobby :hello
```

For TLS, ask the server administrator for the TLS address and port, then enable TLS in the client.
See [Connect to jircd](https://jircd.org/user/connecting/) and [TLS connections](https://jircd.org/user/tls/).

## Guides

- [User Guide](https://jircd.org/user/connecting/): [commands](https://jircd.org/user/commands/),
  [capabilities](https://jircd.org/user/capabilities/), and [channel modes](https://jircd.org/user/channel-modes/)
- [Administrator Guide](https://jircd.org/admin/configuration/): [container](https://jircd.org/admin/install-container/),
  [standalone](https://jircd.org/admin/install-standalone/), [logging](https://jircd.org/admin/logging/),
  [reload and stop](https://jircd.org/admin/reload-stop/), [OPER protection](https://jircd.org/admin/oper-protection/),
  [connection limits](https://jircd.org/admin/connection-limits/), [MOTD and admin contact](https://jircd.org/admin/motd-admin-contact/),
  [listener backlog](https://jircd.org/admin/listener-backlog/), [administrator credentials](https://jircd.org/admin/admin-credentials/),
  and [SendQ](https://jircd.org/admin/sendq/)
- [Developer Guide](https://jircd.org/developer/build-test/): [modules](https://jircd.org/developer/modules/),
  [capability providers](https://jircd.org/developer/capability-provider/), and [contributing](https://jircd.org/developer/contributing/)
- [Extension API reference](https://jircd.org/api/)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the spec-driven workflow and [Code of Conduct](CODE_OF_CONDUCT.md).
Report security issues privately using the instructions in [SECURITY.md](SECURITY.md).

## License

Apache License 2.0; see [LICENSE](LICENSE).
