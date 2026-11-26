---
title: Message of the day and admin contact
description: Configure the MOTD file and the contact lines that the ADMIN command returns.
sidebar:
  order: 10
---

```yaml
motdPath: /etc/jircd/motd.txt   # optional; UTF-8 text, at most 64 KiB
adminName: Jane Admin           # optional ADMIN lines
adminLocation: Example Org, Earth
adminEmail: jane@example.org
```

## MOTD

With `motdPath` set to a readable file, clients get it (`375`, one `372` per line, `376`) when they register and on `MOTD`. Otherwise they get `422`, and an unusable file (missing, unreadable, empty, not a regular file or over 64 KiB) is logged as a warning. Only the first 200 lines are served; a longer file is logged and cut.

Edits to the file are picked up without a reload. `REHASH`/`SIGHUP` applies a changed `motdPath`. In a container, use an absolute path inside the mounted directory.

## ADMIN

`ADMIN` answers `256` to `259` from the three admin settings (`-` for an unset one), or `423` when none is set. Blank values count as unset.

All four settings are listed in [Configuration](configuration.md#message-of-the-day-and-contact).
