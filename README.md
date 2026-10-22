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

### OPER protection

Failed `OPER` attempts are counted per source across reconnects and usernames.
IPv4 uses /32; IPv6 uses `ipv6ConnectionLimitPrefix` (default /64), with mapped
IPv4 addresses normalized. After the threshold, `OPER` is refused without
checking credentials and the connection is closed, even for a correct password.
A successful `OPER` before lockout clears that source's failures.

```yaml
operFailureThreshold: 5  # integer 1–20
operLockoutSeconds: 300  # integer 1–86400, fixed window from the first failure
operRequireTls: false   # set true to reject plaintext OPER with 491 ERR_NOOPERHOST
```

These settings reload on `REHASH`/`SIGHUP`. New window durations apply to new
windows; existing deadlines remain unchanged. Expired entries are reclaimed on
subsequent OPER attempts. Tracking is capped at 10,000 sources; while full,
new sources use per-session failure counting: credentials are still checked and
failure at the threshold disconnects that session. Existing source lockouts remain
intact. Source addresses come from the accepted socket; if unavailable, OPER uses
a per-connection key. Restarting the server clears this in-memory state.

### Connection limits

```yaml
maxConnections: 10000       # positive integer; total across plaintext and TLS listeners
maxConnectionsPerIp: 20     # positive integer; total per client bucket across all listeners
ipv6ConnectionLimitPrefix: 64  # 1-128; 128 counts each IPv6 address separately
connectionLimitOverrides: []   # optional list; defaults to no overrides
```

IPv4 clients count per /32; IPv6 clients share a bucket for the configured prefix
(default /64). IPv4-mapped IPv6 clients (`::ffff:a.b.c.d`) normalize to IPv4 before
bucketing and override matching.

Overrides replace `maxConnectionsPerIp` for clients whose address matches a CIDR;
the most specific matching prefix wins, regardless of list order. Counts remain
per client bucket, **not per override range**. Matching uses the client's address,
even when the CIDR is more specific than the counting prefix. For example:

```yaml
connectionLimitOverrides:
  - cidr: 203.0.113.0/24
    maxConnectionsPerIp: 200
  - cidr: 203.0.113.128/25
    maxConnectionsPerIp: 50    # wins over /24 for this half of the subnet
  - cidr: "2001:db8:1234::/48"
    maxConnectionsPerIp: 1000000  # practical per-bucket exemption; global limit still applies
```

CIDRs must use IP literals with **zero host bits**: use `203.0.113.0/24`, not
`203.0.113.1/24`. IPv4 prefixes range from 0-32 and IPv6 prefixes from 0-128.
Scoped/bracketed addresses and duplicate ranges (including equivalent IPv6 spellings)
are rejected. Mapped IPv6 CIDRs with /96-/128 normalize to IPv4 /0-/32, so they
cannot duplicate an equivalent IPv4 range. Each override requires a positive integer
`maxConnectionsPerIp`; a large value provides an exemption without a separate list.

Both limits count registered and unregistered connections. Connections exceeding either
limit receive `ERROR :Too many connections` and close; rejections are logged as
`security-event=connection-limit` with the source IP, exceeded setting, `bucket`, and
`limitApplied` (the threshold of the exceeded setting). Rejection
I/O runs on virtual threads with a one-second deadline and a shared cap of 64
in-flight rejections across all listeners. When that cap is full, new rejected sockets
close immediately without an error write or individual log entry. Dropped rejections
are counted and logged as `security-event=connection-limit-suppressed count=N`, at
most once per second. Logging runs off the accept thread. Peers that cannot finish the TLS handshake
or receive the error within the deadline are closed.
Counts are released when connection teardown completes. `REHASH`/`SIGHUP` applies
new limits, overrides, and IPv6 grouping to subsequent accepts without disconnecting
existing clients. Each connection releases its original bucket when it ends, even if
the prefix has changed. Old and new buckets coexist during that transition; existing
connections are not moved into the new buckets. Lowering a limit below a bucket's
current usage blocks new connections to that bucket until usage falls below it. Raise the
per-IP limit for deployments with many clients behind one NAT and for localhost load tests.

### Registration timeout

```yaml
registrationTimeoutSeconds: 60   # positive integer, 1-3600
```

A connection that has not completed registration within this many seconds of being
accepted receives `ERROR :Registration timeout` and is closed. Registration means
`NICK` and `USER` plus, if the client started capability negotiation, `CAP END`: a
client parked after `CAP LS` counts as unregistered. Answering `PING`s does not extend
the deadline. A registered client is never affected. A reload applies to connections
accepted afterwards only.

### Message of the day and admin contact

```yaml
motdPath: /etc/jircd/motd.txt   # optional; UTF-8 text, at most 64 KiB
adminName: Jane Admin           # optional ADMIN lines
adminLocation: Example Org, Earth
adminEmail: jane@example.org
```

With `motdPath` set to a readable file, clients get it (`375`/`372`/`376`) when they
register and on `MOTD`; otherwise they get `422`, and an unusable file is logged as a
warning. Only the first 200 lines are served. Edits to the file are picked up without a reload; `REHASH`/`SIGHUP` applies a
changed `motdPath`. `ADMIN` answers from the three admin settings (`-` for an unset one),
or `423` when none is set.

### Listener accept backlog

Each plaintext or TLS listener accepts an optional positive integer `backlog`,
defaulting to `1024` (also used by the zero-config listener):

```yaml
listeners:
  - port: 6667
    tls: false
    backlog: 1024
```

The effective accept queue limit is `min(backlog, OS somaxconn)`; the OS may
silently cap the request. A larger queue helps absorb bursts while connections
wait to be accepted. Listener settings, including `backlog`, `port`, and
`bindAddress`, are validated on `REHASH`/`SIGHUP` but require a server restart
to take effect because reload does not rebind the listening sockets.

Check the OS limit before raising it. For example, to raise a lower limit to
1024 on macOS:

```bash
sysctl kern.ipc.somaxconn
sudo sysctl -w kern.ipc.somaxconn=1024
```

On Linux:

```bash
sysctl net.core.somaxconn
sudo sysctl -w net.core.somaxconn=1024
```

These commands change the running system; use your OS's sysctl configuration
mechanism to persist the value across reboots. Set the OS limit before starting
jircd. See the [Linux kernel socket limit documentation](https://docs.kernel.org/networking/ip-sysctl.html#somaxconn-integer).

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
