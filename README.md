# JIRCD

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Quality gate status](https://sonarcloud.io/api/project_badges/measure?project=jircd_jircd-server&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=jircd_jircd-server)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=jircd_jircd-server&metric=bugs)](https://sonarcloud.io/summary/new_code?id=jircd_jircd-server)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=jircd_jircd-server&metric=code_smells)](https://sonarcloud.io/summary/new_code?id=jircd_jircd-server)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=jircd_jircd-server&metric=coverage)](https://sonarcloud.io/summary/new_code?id=jircd_jircd-server)
[![Duplicated Lines (%)](https://sonarcloud.io/api/project_badges/measure?project=jircd_jircd-server&metric=duplicated_lines_density)](https://sonarcloud.io/summary/new_code?id=jircd_jircd-server)

A modular IRCv3 chat server, written in Java, designed to be extended
without touching its core.

> **Status: implemented.** All six mandatory user stories from the initial
> release plan (connect/chat, capability negotiation, extension toggling,
> channel moderation, in-band administration, and user lookup) are built
> and covered by integration tests. See [Project status](#project-status)
> below.

## What is JIRCD?

JIRCD is a standalone IRC server that speaks both classic IRC
(RFC 1459/2812) and a defined set of [IRCv3](https://ircv3.net/) capability
extensions. Its defining goal is **modularity with a clear boundary**:
core protocol behavior (connection handling, channel moderation, capability
negotiation) is always present and never optional, while enhancements —
individual IRCv3 capabilities, hostname cloaking, in-band server
administration — are independently loadable extensions an administrator can
enable or disable at runtime, without a restart.

Planned for the first release:

- Real-time connection registration, channel messaging, and moderation
  (classic first-join-gets-operator model)
- IRCv3 capability negotiation, with `message-tags`, `server-time`, and
  `echo-message` as the initial capability set
- Runtime-toggleable extensions, configurable via file or in-band IRC
  administrative commands
- Standard `nickname!ident@hostname` identity presentation, with an
  optional hostname-cloaking extension

Authentication/accounts and server-to-server federation are deliberately
out of scope for the first release — see the spec for the reasoning.

Outgoing command sections are limited to 512 UTF-8 bytes including CRLF.
NAMES replies split between complete nicknames. Oversized PRIVMSG and NOTICE
text is shortened at a Unicode character boundary after reserving room for
the sender prefix and target. Other oversized messages are logged and dropped;
subsequent messages continue to be delivered. Tags have a separate 8191-byte
wire allowance, with up to 4094 escaped data bytes from each client/server
origin. Tags are never truncated.

`nicknameMaxLength` (default 9) bounds ASCII nicknames. `channelNameMaxLength`
(default 50, including `#`) bounds both UTF-16 code units and UTF-8 bytes at
JOIN and SAJOIN admission; ISUPPORT still advertises that value as `CHANNELLEN`.
Multibyte names that previously fit only the character limit now receive
`476 ERR_BADCHANMASK`; shorten the name or increase the configured limit within
the reply budget. Existing nicknames and memberships survive a limit-lowering
reload.

Startup and reload reject unsafe combinations of the resolved server-name byte
length `S`, nickname limit `N`, and channel byte limit `Cmax`: both
`S + 2N + Cmax + 14 <= 512` (353) and `S + N + Cmax + 29 <= 512` (366)
must hold. Validation names the keys, computed reply sizes, and available channel
byte budget. Reduce the configured limits or use a shorter server name to migrate
an incompatible configuration; a failed reload keeps the previous settings.
NAMES never splits or truncates member identities. A grandfathered item that
cannot fit is skipped with a WARN giving the channel, item bytes and singleton
budget; fitting neighbors continue. An oversized fixed header is logged
separately. The closing 366 is still attempted, and the writer logs and drops it
if its own size exceeds 512 bytes. See the
[configuration contract](specs/001-ircv3-server/contracts/server-configuration.md)
for the limits and reload behavior.

## Project status

This project is being built with a spec-driven workflow
([GitHub Spec Kit](https://github.com/github/spec-kit)): every feature is
fully specified and planned *before* implementation begins. The complete
specification, clarifications, technical plan, data model, and API
contracts for the initial server feature live under
[`specs/001-ircv3-server/`](specs/001-ircv3-server/):

- [`spec.md`](specs/001-ircv3-server/spec.md) — what the server must do, and why
- [`plan.md`](specs/001-ircv3-server/plan.md) — the technical approach and domain model
- [`research.md`](specs/001-ircv3-server/research.md) — key technical decisions and their rationale
- [`data-model.md`](specs/001-ircv3-server/data-model.md) — entities and their relationships
- [`contracts/`](specs/001-ircv3-server/contracts/) — the wire protocol and configuration contracts

The project's governing principles (code quality, testing, UX consistency,
performance) are recorded in the
[constitution](.specify/memory/constitution.md).

## Getting started

Requires JDK 25.

```bash
./gradlew build                 # build + unit/integration tests + static analysis, all subprojects
touch jircd-server/jircd.yaml   # a Server Configuration file must exist at this path; an empty
                                 # file is valid and loads every default
./gradlew :jircd-server:run     # starts the server on 6667 (plaintext) and 6697 (TLS)
```

Then connect with any IRC client, or a raw TCP tool for a quick check:

```bash
nc localhost 6667
NICK alice
USER alice 0 * :Alice
JOIN #lobby
PRIVMSG #lobby :hello
```

The empty config above enables no optional capabilities or extensions —
only core protocol behavior (connecting, messaging, moderation, `WHOIS`/
`WHO`). To turn on IRCv3 capabilities, hostname cloaking, or in-band
administration (`OPER`, `REHASH`, etc.), see the full annotated schema in
[`contracts/server-configuration.md`](specs/001-ircv3-server/contracts/server-configuration.md).
For a guided, story-by-story walkthrough of every feature, see
[`quickstart.md`](specs/001-ircv3-server/quickstart.md).

### Generating an administrator credential

`administratorCredentials` entries (see the config schema link above) store a
bcrypt or Argon2id password hash — never a plaintext password. Generate one
with `jshell` against the jars already bundled in a standalone build:

```bash
./gradlew :jircd-server:installDist
cd jircd-server/build/install/jircd-server
jshell --class-path "lib/*"
```

```java
jshell> com.password4j.Password.hash("your-password-here").withArgon2().getResult()
$1 ==> "$argon2id$v=19$m=15360,t=2,p=1$..."

jshell> com.password4j.Password.hash("your-password-here").withBcrypt().getResult()
$2 ==> "$2b$10$..."

jshell> /exit
```

Paste the printed value into that credential's `hashedPassword` field. Only
the `$argon2id$` Argon2 variant is accepted — `$argon2i$`/`$argon2d$` hashes
are rejected at startup, not silently ignored.

### Running a standalone build

`./gradlew :jircd-server:run` is for development only — it stays attached
to Gradle. For a standalone install (no Gradle needed to run it), build a
distribution instead:

```bash
./gradlew :jircd-server:installDist
```

This produces `jircd-server/build/install/jircd-server/`, containing a
`bin/jircd-server` (and `.bat`) launcher plus every runtime dependency —
including the bundled capability/extension jars, which are discovered via
`ServiceLoader` at startup — in `lib/`. Run it from that directory (it
looks for `jircd.yaml` in the working directory, same as `:run`):

```bash
cd jircd-server/build/install/jircd-server
touch jircd.yaml
bin/jircd-server
```

To produce a distributable archive instead (e.g. for attaching to a
release), use `distZip` or `distTar`, which land in
`jircd-server/build/distributions/`. See
[Releases](https://github.com/jircd/jircd-server/releases) for prebuilt
archives — pushing a `vX.Y.Z` tag builds and publishes these automatically
(see [`.github/workflows/release.yml`](.github/workflows/release.yml)).

### Outbound queue (SendQ) policy

Each connection has a bounded outbound queue, measured in bytes of queued wire data (escaped
tags and the trailing CRLF included). A client that does not read fast enough to stay under the
limit is disconnected instead of letting the queue grow.

| Setting | Value |
| --- | --- |
| Key | `sendqLimitBytes` in `jircd.yaml` |
| Unit | bytes |
| Default | 524288 (512 KiB) |
| Minimum | 16384 (16 KiB) |
| Ceiling | 2097152 (2 MiB) |

When the limit is exceeded, the client is sent exactly
`ERROR :Closing Link: <host> (SendQ exceeded)` (delivery is best effort if the client is not
reading), other users see `QUIT :SendQ exceeded`, and the log records
`connection-event=sendq-overflow ... limitBytes=<n> queuedBytes=<n> overflowTotal=<n>` followed by
`connection-event=disconnected ... reason=SendQ exceeded`. `overflowTotal` counts overflows since
the process started.

A configuration reload applies a new `sendqLimitBytes` to new connections only; existing
connections keep the limit they were accepted with.

Worst-case queued wire data is `connections x limit`: 10,000 connections x 512 KiB = 5 GiB at the
default, and 10,000 x 2 MiB = 20 GiB at the ceiling. The limit budgets wire bytes only. JVM object
headers, queue nodes, per-message objects and caches add further heap on top of that figure, so
size the heap with headroom. Raise the limit only if legitimate clients are being cut off, for
example during large channel JOIN bursts, and re-check the worst-case figure for your connection
count first.

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md) for the
workflow and expectations. Please also review our
[Code of Conduct](CODE_OF_CONDUCT.md).

## Security

See [SECURITY.md](SECURITY.md) for how to report a vulnerability.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
