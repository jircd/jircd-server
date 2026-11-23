---
title: Listener accept backlog
description: Set the accept queue size of a listener and raise the operating system limit that caps it.
sidebar:
  order: 9
---

Each plaintext or TLS listener accepts an optional positive integer `backlog`, defaulting to `1024` (also used by the zero-config listener on port 6667):

```yaml
listeners:
  - port: 6667
    tls: false
    backlog: 1024
```

The effective accept queue limit is `min(backlog, OS somaxconn)`; the OS may silently cap the request. A larger queue helps absorb bursts while connections wait to be accepted.

Listener settings, including `backlog`, `port` and `bindAddress`, are validated on `REHASH`/`SIGHUP` but need a server restart to take effect, because reload does not rebind the listening sockets. See [Reload and stop](reload-stop.md).

## Check the OS limit first

Set the OS limit before starting jircd. To raise a lower limit to 1024 on macOS:

```bash
sysctl kern.ipc.somaxconn
sudo sysctl -w kern.ipc.somaxconn=1024
```

On Linux:

```bash
sysctl net.core.somaxconn
sudo sysctl -w net.core.somaxconn=1024
```

These commands change the running system; use your OS's sysctl configuration to keep the value across reboots. See the [Linux kernel socket limit documentation](https://docs.kernel.org/networking/ip-sysctl.html#somaxconn-integer).
