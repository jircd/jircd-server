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
jshell> /set feedback concise
jshell> char[] password = System.console().readPassword("Password to hash: ");
jshell> String argon2Hash = com.password4j.Password.hash(new String(password)).withArgon2().getResult();
jshell> String bcryptHash = com.password4j.Password.hash(new String(password)).withBcrypt().getResult();
jshell> java.util.Arrays.fill(password, '\0');
jshell> System.out.println(argon2Hash);
$argon2id$v=19$m=15360,t=2,p=1$...
jshell> System.out.println(bcryptHash);
$2b$10$...
jshell> /exit
```

`readPassword` prompts without echoing the password or adding it to JShell's command history. Concise feedback prevents JShell from displaying variable values; the two explicit print calls show only the hashes. Run this in an interactive terminal where `System.console()` is available. Paste either printed value into that credential's `hashedPassword` field. Verification uses Password4j's default parameters, so only hashes generated with the defaults shown here verify: the default Argon2id parameters or bcrypt cost 10. ([#175](https://github.com/jircd/jircd-server/issues/175)) Only the `$argon2id$` Argon2 variant is accepted: `$argon2i$` and `$argon2d$` hashes are rejected at startup, not silently ignored.

Password4j may print WARN lines about missing `psw4j.properties`; they are harmless for these default hash calls.

Also read [OPER protection](oper-protection.md): it explains the lockout and the `operRequireTls` setting.
