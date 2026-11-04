# Contract: `logging` configuration section

**Feature**: 016-container-deployment. This is a new optional top-level section of `jircd.yaml`. It is reloadable with SIGHUP or `REHASH`.

```yaml
logging:
  console: true                    # optional; default true
  file: /var/log/jircd/jircd.log   # optional; no default; absent = no log file
```

## Behaviour

| `console` | `file` | Result |
|---|---|---|
| absent or `true` | absent | Standard output only. This is the default, including in the container image |
| absent or `true` | set | Standard output and the file |
| `false` | set | The file only |
| `false` | absent | **Rejected**: "logging: console is off and no file is set; the server would produce no log output" |

- The file is rotated daily and by size: 50 MB per file, 30 days of history, 1 GB in total, gzip-compressed. Archives are named `<file>.<yyyy-MM-dd>.<n>.gz`, in the same directory.
- A relative `file` is resolved against the server's working directory. The path must not contain `%` (logback reads it as a token in the rolled file name and has no escape for it), must not be a filesystem root, and must not be the configuration file itself (including through a symlink); each is rejected with a message naming the path.
- The file's directory must already exist and be writable. If the file exists, it must be a regular writable file. Otherwise the configuration is rejected and the message names the path and the reason.
- On reload, an unchanged destination keeps logging without interruption. A changed `file` opens the new file before the old one is closed. The switch is not atomic: a few lines logged around it can be lost or duplicated. If the new file cannot be opened after validation, the previous appenders stay, a WARN is logged and the new configuration stays published (an intentional partial apply). A rejected `logging` section rejects the whole reload, and the previous configuration, logging included, stays in force.
- If the JVM is started with `-Dlogback.configurationFile=<file>`, this section is neither applied nor validated (the loader skips the section entirely), so an invalid section is not rejected. One INFO line at startup says so, and logback is configured entirely by that file.

## Migration from earlier snapshots

Earlier builds always wrote `logs/jircd.log` under the working directory. That file is no longer written unless the configuration asks for it. To keep the old behaviour, add:

```yaml
logging:
  file: logs/jircd.log
```

The project is unreleased (0.1.0-SNAPSHOT). The README (in the logging change) and the sample configuration carry this note.
