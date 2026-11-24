---
title: Configuration reference
description: Every key of the jircd.yaml server configuration with its type, default, limits and reload behaviour.
sidebar:
  order: 3
---

The server reads one YAML file at startup: the first command-line argument, or `jircd.yaml` in the working directory (`/etc/jircd/jircd.yaml` in the container image). An empty file is valid and every key has a default. An invalid value stops the server (`Refusing to start:` and the key name, exit status 1) or, on a reload, is rejected and the running configuration stays.

Top-level keys the loader does not know are ignored, except inside `logging`, where an unknown key is an error. The annotated schema is in [`contracts/server-configuration.md`](https://github.com/jircd/jircd-server/blob/main/specs/001-ircv3-server/contracts/server-configuration.md); a sample for containers is [`deploy/container/jircd.yaml`](https://github.com/jircd/jircd-server/blob/main/deploy/container/jircd.yaml).

**Reload** says what `SIGHUP` or `REHASH` does with a changed value (see [Reload and stop](reload-stop.md)): *applies* means the running server uses it; *new connections* means connections already open keep the old value; *restart* means the new value is validated but only a restart applies it.

## Identity and limits

| Key | Type, range | Default | Reload | Effect |
| --- | --- | --- | --- | --- |
| `serverName` | string containing a `.` | the machine's host name, with `.local` appended if it has no dot (`localhost.local` if unknown) | restart | Name the server uses in replies. |
| `nicknameMaxLength` | integer 1–400 | 9 | applies | Longest nickname accepted. |
| `channelNameMaxLength` | integer 1–400 | 50 | applies | Longest channel name accepted. |
| `topicMaxLength` | integer 1–400 | 390 | applies | Longest topic accepted. |
| `maxModesPerCommand` | integer 1–20 | 6 | applies | Mode changes accepted in one `MODE` command. |
| `whoMaskEnabled` | boolean | `true` | applies | When `false`, `WHO` with a wildcard mask or no argument returns an empty result for non-operators. |
| `whowasHistorySize` | integer 1–10000 | 100 | applies | Entries kept for `WHOWAS`. |
| `keepAliveFrequencySeconds` | integer 1–3600 | 120 | new connections | Idle time before the server sends a `PING`. |

`serverName`, `nicknameMaxLength` and `channelNameMaxLength` must leave every reply within 512 bytes. A combination that does not is rejected with the byte counts, at startup and on reload.

## Rate limit

```yaml
rateLimit:
  bucketSize: 20             # positive integer
  refillRatePerSecond: 10.0  # positive number
```

| Key | Default | Reload |
| --- | --- | --- |
| `rateLimit.bucketSize` | 20 | new connections |
| `rateLimit.refillRatePerSecond` | 10.0 | new connections |

Each connection gets a token bucket with this size and refill rate (tokens per second).

## Listeners

```yaml
listeners:
  - port: 6667
    tls: false
  - port: 6697
    tls: true
    certPath: /etc/jircd/cert.pem
    keyPath: /etc/jircd/key.pem
```

With no `listeners` (absent or empty) the server opens one plaintext listener on port 6667. Listeners need a restart to change; `REHASH` and `SIGHUP` validate them but do not rebind sockets.

| Key | Type | Default | Effect |
| --- | --- | --- | --- |
| `port` | integer 0–65535 | required | Port to bind. `0` asks the OS for a free port. Two listeners may not share a port on the same address (port 0 is exempt). |
| `tls` | boolean | `false` | Whether the listener speaks TLS. |
| `bindAddress` | IPv4 or IPv6 literal | all local addresses | A host name is rejected. |
| `backlog` | positive integer | 1024 | Accept queue size; see [Listener accept backlog](listener-backlog.md). |
| `certPath`, `keyPath` | strings | none | PEM certificate (a single X.509 certificate; only the first is loaded, so intermediates are not sent) and unencrypted PKCS#8 PEM private key. Both or neither. |
| `keystorePath`, `keystorePassword` | strings | none | A PKCS12 keystore and its password; the password is required with the path. |

A `tls: true` listener needs exactly one certificate source: the `certPath`/`keyPath` pair or `keystorePath`. Both, or neither, is an error. The certificate is loaded when the configuration is read, so a bad certificate stops startup and rejects a reload. On a `tls: false` listener the certificate keys are ignored.

## Capabilities and extensions

```yaml
capabilities:
  message-tags: enabled
  server-time: enabled
server-extensions:
  admin: enabled
  cloak: disabled
```

Each entry is `enabled` or `disabled`; anything else is an error, and so is an unknown id. Nothing is on unless listed.

| Section | Ids |
| --- | --- |
| `capabilities` | `message-tags`, `server-time`, `echo-message`, `away-notify`, `batch`, `multi-prefix`, `userhost-in-names` |
| `server-extensions` | `cloak`, `admin` |

An id under the wrong section is an error. A reload enables and disables providers to match the file. The `admin` extension provides `OPER` and `REHASH`; see [Generating an administrator credential](admin-credentials.md). A user-facing list of what each capability does is in the User Guide.

## Operators

| Key | Type, range | Default | Reload | Page |
| --- | --- | --- | --- | --- |
| `administratorCredentials` | list of `username` and `hashedPassword` | none | applies | [Admin credentials](admin-credentials.md) |
| `operFailureThreshold` | integer 1–20 | 5 | applies | [OPER protection](oper-protection.md) |
| `operLockoutSeconds` | integer 1–86400 | 300 | applies to new failure windows; existing windows keep their original deadline | [OPER protection](oper-protection.md) |
| `operRequireTls` | boolean | `false` | applies | [OPER protection](oper-protection.md) |

## Connection limits and timeouts

| Key | Type, range | Default | Reload | Page |
| --- | --- | --- | --- | --- |
| `maxConnections` | positive integer | 10000 | new connections | [Connection limits](connection-limits.md) |
| `maxConnectionsPerIp` | positive integer | 20 | new connections | [Connection limits](connection-limits.md) |
| `ipv6ConnectionLimitPrefix` | integer 1–128 | 64 | new connections | [Connection limits](connection-limits.md) |
| `connectionLimitOverrides` | list of `cidr` and `maxConnectionsPerIp` | none | new connections | [Connection limits](connection-limits.md) |
| `registrationTimeoutSeconds` | integer 1–3600 | 60 | new connections | [Connection limits](connection-limits.md#registration-timeout) |
| `sendqLimitBytes` | integer 16384–2097152 | 524288 | new connections | [SendQ policy](sendq.md) |

## Message of the day and contact

| Key | Type | Default | Reload | Page |
| --- | --- | --- | --- | --- |
| `motdPath` | string, optional | none | applies | [MOTD and ADMIN](motd-admin-contact.md) |
| `adminName`, `adminLocation`, `adminEmail` | string, optional | none | applies | [MOTD and ADMIN](motd-admin-contact.md) |

A blank value counts as not set.

## Logging

```yaml
logging:
  console: true
  file: /var/log/jircd/jircd.log
```

`console` defaults to `true`; `file` has no default. See [Logging](logging.md). The section applies on reload. When `-Dlogback.configurationFile` is set, that file controls logging and the YAML `logging` section is neither applied nor validated.

## Value errors

For the integer keys above, a value that is not an integer, is outside its allowed range (positive for most keys; `port` accepts 0 to 65535), or is above its limit is rejected with a message naming the key, such as `maxModesPerCommand must be a positive integer no greater than 20, got: 50`. Secrets in a rejected value (`keystorePassword`, `hashedPassword`) are shown as `[redacted]`.
