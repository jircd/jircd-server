---
title: Generating an administrator credential
description: Create a bcrypt or Argon2id password hash for administratorCredentials and enable OPER.
sidebar:
  order: 11
---

`administratorCredentials` entries store a bcrypt or Argon2id password hash, never a plaintext password. They are used by `OPER`, which needs the `admin` extension enabled:

```yaml
server-extensions:
  admin: enabled
administratorCredentials:
  - username: admin
    hashedPassword: "$argon2id$v=19$m=15360,t=2,p=1$..."
```

Each entry needs a string `username` and `hashedPassword`. A reload applies changes to credentials. A hash that does not start with `$2a$`, `$2b$`, `$2y$` or `$argon2id$` is rejected, at startup and on reload.

## Generate a hash

Use `jshell` against the jars already bundled in a standalone build ([Run a standalone build](install-standalone.md)):

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

Paste the printed value into that credential's `hashedPassword` field. Only the `$argon2id$` Argon2 variant is accepted: `$argon2i$` and `$argon2d$` hashes are rejected at startup, not silently ignored.

Also read [OPER protection](oper-protection.md): it explains the lockout and the `operRequireTls` setting.
