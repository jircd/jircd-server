# Contract: Server Configuration (Administrator-Facing)

This project has two administrator-facing interfaces, not an HTTP API: this
configuration file (Story 4) and the in-band IRC administrative commands
documented in [irc-protocol-commands.md](./irc-protocol-commands.md#administration-story-6)
(Story 6). This contract documents the configuration file's schema and the
validation behavior it MUST provide (FR-011, FR-012, FR-034).

## Schema (YAML, see research.md "Configuration format")

```yaml
serverName: irc.example.net  # optional (FR-050) — source prefix on every server-originated
                              # message; if omitted, falls back to the deployment host's own
                              # network hostname (research.md "Server identity") — not a value
                              # the server refuses to start without. MUST contain a "." if
                              # explicitly set (nicknames never can, FR-002/FR-050) — a
                              # dot-free value here is a validation error, not accepted as-is

nicknameMaxLength: 9    # optional (FR-056) — defaults to 9 (RFC 2812 baseline). Positive
                          # integer, at most 400. Enforced by NICK (432 ERR_ERRONEUSNICKNAME)
                          # and advertised as NICKLEN.
channelNameMaxLength: 50 # optional (FR-056) — defaults to 50, including the leading "#".
                          # Positive integer, at most 400. Enforced by JOIN
                          # (476 ERR_BADCHANMASK) and advertised as CHANNELLEN.
topicMaxLength: 390      # optional (FR-056) — defaults to 390 (a widely-used real-world IRC
                          # default; RFC 2812 defines no topic length). Positive integer, at
                          # most 400. Enforced by TOPIC-set (417 ERR_INPUTTOOLONG) and
                          # advertised as TOPICLEN.

whoMaskEnabled: true     # optional (FR-061) — defaults to true. Gates WHO's wildcard-mask and
                          # no-argument forms for non-administrator sessions only; false makes
                          # both return an empty result (bare 315 RPL_ENDOFWHO), indistinguishable
                          # from a real zero-match search. Administrators are always exempt.
                          # WHO's channel-name and exact-nickname forms are unaffected either way.

maxModesPerCommand: 6    # optional (FR-064) — defaults to 6, a long-standing convention among
                          # deployed IRC servers. Positive integer, at most 20. The maximum
                          # number of parameter-consuming channel-mode flags (MEMBER/LIST-kind)
                          # a single MODE command applies; flags beyond it are silently not
                          # applied (no error — the MODE echo reflects only what was applied).
                          # Advertised as MODES.

listeners:
  - port: 6667
    tls: false
  - port: 6697
    tls: true          # optional TLS listener (FR-018); plaintext MUST remain available unless the admin removes it
    certPath: /etc/letsencrypt/live/example.org/fullchain.pem  # PEM cert/chain (004-fix-tls-certificate FR-001/FR-002)
    keyPath: /etc/letsencrypt/live/example.org/privkey.pem     # PEM private key — required together with certPath
  # Alternative certificate form for the same listener (mutually exclusive with certPath/keyPath):
  #   keystorePath: /etc/jircd/keystore.p12       # PKCS12 keystore (FR-005)
  #   keystorePassword: <required — no default>   # REQUIRED if keystorePath is set; a well-known
  #                                                # default like "changeit" would defeat PKCS12's
  #                                                # own password-based encryption of the key entry
  # A `tls: true` listener with neither form configured refuses to start (FR-003/FR-004) — no
  # certificate is ever generated automatically, unlike releases before 004-fix-tls-certificate.
  # A passphrase-encrypted or legacy PKCS#1 PEM private key is rejected with a specific message
  # naming the problem, not a generic parse failure — jircd only reads an unencrypted PKCS#8 key
  # (Let's Encrypt/certbot's own default output).

# channel moderation and capability negotiation are core (FR-035, FR-036)
# and never appear in either section below — see data-model.md "Extension".

capabilities:            # CapabilityExtension states only (jircd-capabilities/) —
  message-tags: enabled  # client-negotiable via CAP LS. A ServerExtension id
  server-time: enabled   # (e.g., cloak, admin) listed here is a configuration error.
  echo-message: enabled
  away-notify: enabled   # 011-away-notify-batch
  batch: enabled          # 011-away-notify-batch

server-extensions:      # ServerExtension states only (jircd-server-extensions/) —
  cloak: disabled        # never CAP-negotiated, administrator-only. A
  admin: enabled         # CapabilityExtension id listed here is a configuration
                          # error. cloak (FR-031) is off by default; admin
                          # (Story 6) MUST be enabled for OPER etc. to work at all.

rateLimit:
  bucketSize: 20        # tokens
  refillRatePerSecond: 5

administratorCredentials:
  - username: root-admin
    hashedPassword: "<bcrypt/Argon2id hash — never plain text, see research.md 'Administrator credential storage' and 008-argon2-admin-verification research.md>"
```

## Behavioral Contract

- **Load-time validation**: an unknown key in either `capabilities` or
  `server-extensions` — including a `moderation` or
  `capability-negotiation` entry, since neither is a toggleable extension
  (FR-035, FR-036) — a `listeners` entry missing `port`, or a non-positive
  `rateLimit` value MUST cause the server to refuse to start with an error
  naming the exact offending key/value (FR-012, SC-008) — not a stack
  trace, not a silent default substitution.
- **A `tls: true` listener's certificate IS part of the "refuse to start" validation set**
  (004-fix-tls-certificate FR-003, FR-004, FR-006): it MUST have exactly one of
  `certPath`+`keyPath` (both required together — an incomplete pair is rejected) or
  `keystorePath` configured; both forms present, or neither, is rejected the same way a
  malformed listener or rate-limit value is. `keystorePath` additionally requires
  `keystorePassword` — no default is substituted (a well-known default like `"changeit"` would
  defeat PKCS12's own password-based encryption of the key entry), so a `keystorePath` with no
  `keystorePassword` is rejected the same way. Whichever form is present is actually loaded and
  parsed at this same startup pass — an unreadable file, invalid PEM, a key that doesn't match
  the certificate, a wrong keystore password, or a passphrase-encrypted/legacy-PKCS#1 PEM key
  (both rejected with a specific message naming the problem) all fail startup immediately, never
  only on a client's first TLS handshake attempt. No certificate is ever generated automatically;
  a `tls: false` listener's certificate fields, if present, are ignored, not validated. Omitting
  `listeners` entirely uses the zero-config default of a single plaintext listener on `6667` —
  no TLS entry, since one with no certificate configured would otherwise fail this same
  validation.
- **`nicknameMaxLength`/`channelNameMaxLength`/`topicMaxLength` ARE part
  of the "refuse to start" validation set** (FR-056): each, if set, MUST
  be a positive integer not exceeding `400`; a non-positive value, a
  non-integer value, or a value above `400` is rejected the same way a
  malformed listener or rate-limit value is. Omitting any of the three
  is valid — the RFC/convention-matching default (`9`/`50`/`390`) is
  used instead. A successful reload changes the value `SupportedFeatures`
  advertises (`NICKLEN`/`CHANNELLEN`/`TOPICLEN`) and the value newly
  processed `NICK`/`JOIN`/`TOPIC` commands are checked against
  immediately; sessions already holding a nickname or channel membership
  that would now violate a newly-lowered limit are NOT retroactively
  disconnected or evicted — the limit only gates new claims, the same
  "no retroactive enforcement" posture extension-state changes already
  have (FR-011).
- **`whoMaskEnabled`** is a plain boolean with no numeric bound to
  validate (FR-061) — omitting it is valid (defaults to `true`); a
  non-boolean value is rejected the same way any other malformed
  configuration value is (FR-012). A successful reload takes effect for
  `WHO` commands processed after the reload completes, the same
  immediacy every other reloadable setting has (FR-011) — an
  in-progress `WHO` isn't retroactively affected, there being nothing
  in-progress for a single-line command/reply exchange to retroactively
  affect.
- **`maxModesPerCommand` IS part of the "refuse to start" validation
  set** (FR-064): if set, MUST be a positive integer not exceeding
  `20`; a non-positive value, a non-integer value, or a value above
  `20` is rejected the same way a malformed listener value is. Omitting
  it is valid — the conventional default (`6`) is used instead. A
  successful reload changes the value `SupportedFeatures` advertises
  (`MODES`) and the value newly processed `MODE` commands are checked
  against immediately, the same immediacy `nicknameMaxLength`/etc.
  already have (FR-011).
- **`serverName` IS part of the "refuse to start" validation set** if
  explicitly set: it MUST contain at least one `.` (FR-050, research.md
  "Server identity" — nicknames, FR-002's grammar, never can, so this is
  what keeps a server-originated prefix unambiguous); a dot-free value is
  rejected the same way a malformed listener or rate-limit value is.
  Omitting `serverName` entirely is valid — the deployment host's own
  network hostname is used instead, itself guaranteed to satisfy the dot
  requirement (a synthetic suffix is appended if the host's own hostname
  lacks one).
- **Section/kind mismatch is also a load-time validation error**: an id
  MUST appear in the section matching its actual kind — a `ServerExtension`
  id (e.g., `cloak`, `admin`) listed under `capabilities`, or a
  `CapabilityExtension` id (e.g., `message-tags`) listed under
  `server-extensions`, MUST be rejected with an error naming the id and
  which section it belongs in instead — not silently accepted or moved.
  This is the config-file expression of the `CapabilityExtension`/
  `ServerExtension` distinction (data-model.md "Extension").
- **Live reload is manually triggered, never automatic** (research.md
  "Configuration reload mechanism"): editing this file alone does nothing
  until the administrator explicitly triggers a reload — either by
  sending the process a `SIGHUP` signal (no IRC connection required,
  keeping this file-based path independent of the in-band administration
  path) or, once available, by issuing the in-band `REHASH` command
  (contracts/irc-protocol-commands.md, Story 6). Either trigger re-reads
  and re-validates the file the same way startup does, then reconciles
  the result against live state. A successful reload MUST take effect for
  already-connected and new clients within the SC-005 budget (1 minute),
  without restarting the process (FR-011); a failed reload (invalid file)
  MUST leave the previously-active configuration untouched and report the
  same specific, actionable error startup validation would (FR-012,
  SC-008) — never a partially-applied state. Changes to `listeners` or
  `rateLimit` MAY require a listener-level restart (rebinding a port)
  without violating FR-011, since FR-011 scopes "no restart" to
  *extension* state, not listener reconfiguration — this distinction
  should be revisited if a future clarification narrows it further.
- **Partial-failure isolation**: if one extension (from either section)
  fails to start after being set to `enabled` (e.g., a coding error in
  that extension), the server MUST still start and serve all other
  configured extensions (FR-020), and MUST report the failed extension's
  `state` as `FAILED` (see data-model.md) rather than silently treating it
  as disabled.
- **Credential storage**: `administratorCredentials[].hashedPassword` MUST
  NOT be accepted or stored as plain text — load-time validation MUST
  reject a value that isn't a recognized hash format (FR-034).
- **Path equivalence**: an extension state change applied via the
  `EXTENSION` in-band command (Story 6) MUST be observably identical to
  one applied by editing this file's `capabilities` or `server-extensions`
  section and reloading it (Story 4), whichever the id belongs to — same
  effect, same no-restart guarantee (FR-011) — so an administrator can
  freely mix both access paths. The in-band `EXTENSION` command addresses
  both sections through one shared id space (data-model.md's
  `Extension.id`); it does not need a client to know or state which
  section an id lives in, and — unlike `REHASH` — it does not read this
  file at all, so it has nothing to reload. `REHASH` is the in-band
  equivalent of a `SIGHUP`-triggered reload of this whole file (including
  `rateLimit`, `listeners`, and `administratorCredentials`, none of which
  `EXTENSION` touches); `EXTENSION` is the narrower, file-independent
  equivalent scoped to a single extension's state.
