---
title: Logging
description: Send logs to standard output, a rotating file, or both, and reload the setting without a restart.
sidebar:
  order: 4
---

Logs go to standard output by default. The optional top-level `logging` section adds a rotating log file or turns the console off. It reloads with `SIGHUP` or `REHASH`, with no restart.

```yaml
logging:
  console: true                    # optional; default true
  file: /var/log/jircd/jircd.log   # optional; no default; absent = no log file
```

| Key | Type | Default | Effect |
| --- | --- | --- | --- |
| `console` | boolean | `true` | Write log lines to standard output. |
| `file` | path | none | Also write them to this file. |

- With `file` set and `console` left on, lines go to both. `console: false` needs a `file`; without one the configuration is rejected, because the server would produce no output.
- The file rotates daily and at 50 MB, retaining up to 30 days and 1 GB in total, gzip-compressed. The size cap can shorten retention below 30 days. The line format and the INFO level are fixed.
- The file's directory must exist and be writable. The path must not contain `%`, must not be a filesystem root and must not be the configuration file. A relative path resolves against the working directory.
- Any other key inside `logging` is rejected.
- A rejected `logging` section rejects the whole reload and keeps the previous configuration. Switching files on a reload is not atomic: a few lines around the switch can be lost or duplicated.
- With `-Dlogback.configurationFile=<file>`, that file configures logging and the section is neither applied nor validated.

In a container, mount a log directory writable by uid 65532; see [Run jircd in a container](install-container.md). Earlier builds always wrote `logs/jircd.log`: add `logging: {file: logs/jircd.log}` to keep that file.

The format of connection and security log lines is described in [Connection limits](connection-limits.md) and [SendQ policy](sendq.md). The `logging` contract is in [`specs/016-container-deployment/contracts/logging-config.md`](https://github.com/jircd/jircd-server/blob/main/specs/016-container-deployment/contracts/logging-config.md).
