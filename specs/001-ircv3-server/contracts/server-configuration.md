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
                          # Positive integer, at most 400; bounds both UTF-16 code units and
                          # UTF-8 bytes. Enforced by JOIN and SAJOIN (476 ERR_BADCHANMASK)
                          # and advertised unchanged as CHANNELLEN.
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

maxConnections: 10000    # optional — positive integer, maximum live connections across all listeners
maxConnectionsPerIp: 20  # optional — positive integer, maximum live connections per client bucket
ipv6ConnectionLimitPrefix: 64 # optional — integer 1-128; 128 means per IPv6 address
connectionLimitOverrides: []  # optional — list, defaults to no overrides
# Example replacement for the empty list above:
# connectionLimitOverrides:
#   - cidr: 203.0.113.0/24
#     maxConnectionsPerIp: 200
#   - cidr: "2001:db8::/32"
#     maxConnectionsPerIp: 1000000

listeners:
  - port: 6667
    tls: false
    backlog: 1024     # optional — positive integer; defaults to 1024 for plaintext and TLS
    # bindAddress: 127.0.0.1  # optional — a literal IPv4 or IPv6 address (quote an IPv6 one:
    #                         # '::1'), never a hostname, so startup does no DNS lookup. The
    #                         # listener accepts on that one local address only. If omitted, it
    #                         # binds the wildcard address (every local address), as before. On
    #                         # macOS, a wildcard listener can share its port with another
    #                         # process's 127.0.0.1 listener, which then receives the local
    #                         # clients (#38); binding 127.0.0.1 itself rules that out
  - port: 6697
    tls: true          # optional TLS listener (FR-018); plaintext MUST remain available unless the admin removes it
    certPath: /etc/letsencrypt/live/example.org/fullchain.pem  # PEM cert/chain (004-fix-tls-certificate FR-001/FR-002)
    keyPath: /etc/letsencrypt/live/example.org/privkey.pem     # PEM private key — required together with certPath
  # Alternative certificate form for the same listener (mutually exclusive with certPath/keyPath):
  #   keystorePath: /etc/jircd/keystore.p12       # PKCS12 keystore (FR-005)
  #   keystorePassword: "<required — no default>" # REQUIRED if keystorePath is set; a well-known
  #                                                # default like "changeit" would defeat PKCS12's
  #                                                # own password-based encryption of the key entry.
  #                                                # A YAML string: quote an all-digit password
  #                                                # ("123456"), an unquoted number is rejected
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
- **Connection admission limits (#72)**: `maxConnections` defaults to `10000` and
  `maxConnectionsPerIp` to `20`; both MUST be positive YAML integers no greater
  than `2147483647`. Registered and unregistered connections count across all
  plaintext and TLS listeners. IPv4 clients count per /32; IPv6 clients share a
  bucket selected by `ipv6ConnectionLimitPrefix` (default `64`, integer `1`-`128`).
  A value of `128` counts each IPv6 address separately. IPv4-mapped IPv6 clients
  normalize to IPv4 before both bucketing and override matching.
- **Per-address overrides**: `connectionLimitOverrides` defaults to an empty list.
  Each entry MUST be a mapping with a string `cidr` and positive integer
  `maxConnectionsPerIp` (at most `2147483647`). CIDRs MUST use unscoped, unbracketed
  IPv4/IPv6 literals with zero host bits, and prefix lengths `0`-`32` / `0`-`128`.
  Hostnames, malformed CIDRs, nonzero host bits, wrong YAML types, and duplicate
  ranges MUST be rejected with the offending indexed field path, such as
  `connectionLimitOverrides[1].cidr`. Equivalent IPv6 spellings are duplicates;
  mapped IPv6 CIDRs with /96-/128 normalize to IPv4 /0-/32 before duplicate
  detection. Among matching entries, the most specific prefix wins regardless of
  list order. Matching uses the client's address; the replacement threshold counts
  its client bucket, never the whole override range. A large threshold such as
  `1000000` provides a practical exemption from the per-bucket limit, while the
  global `maxConnections` still applies.
- **Admission enforcement and reload**: an over-limit accept receives
  `ERROR :Too many connections` and closes. Rejection writes and individual
  `security-event=connection-limit` logs run on bounded workers, with a one-second
  socket deadline and at most 64 rejection workers across the listeners. When
  all workers are occupied, sockets close immediately without writing or logging
  each rejection; dropped rejections are counted and summarized off the accept
  thread as `security-event=connection-limit-suppressed count=N`, at most once
  per second. Normal rejection logs include the client address, exceeded setting,
  counting bucket, and applied threshold. `REHASH` and `SIGHUP` validate and publish
  all four admission settings for new accepts. Failed reloads preserve every prior
  setting. Existing connections remain connected and release the same bucket key
  they reserved at accept time. Prefix changes do not migrate existing counts;
  old and new buckets coexist until their reservations end. Lowering a limit
  blocks new accepts at or above the applicable threshold without disconnecting
  existing clients.
- **Every value MUST have its declared YAML type** (issue #25): the root
  and each section MUST be a mapping (`listeners` and
  `administratorCredentials` a list of mappings), and each scalar MUST be
  the type this contract shows — string, integer or boolean. Values are
  never coerced: `tls: "true"` is rejected rather than read as `true`,
  and a string field such as `keystorePassword` whose value looks like a
  number MUST be quoted. A wrong shape or type is rejected the same way as
  any other malformed value, never as an unrelated runtime exception, and
  the error names the full path of the key (`listeners[1].port`,
  `administratorCredentials[0].hashedPassword`).
- **Numeric ranges at the same boundary**: `rateLimit.refillRatePerSecond`
  MUST be finite as well as positive (`.nan` and `.inf` are rejected); a
  listener `port` MUST be an integer from `0` to `65535`; and two listeners
  MUST NOT share a non-zero port when their `bindAddress` values are equal
  or either one binds the wildcard address (`bindAddress` omitted,
  `0.0.0.0` or `::`). The same port on two different specific addresses
  (`127.0.0.1` and `::1`) is allowed. Port `0` may repeat, since each such
  listener binds its own OS-assigned ephemeral port.
- **`bindAddress` MUST be a literal IP address**: a string holding an IPv4
  or IPv6 address. A hostname (`localhost` included), an empty string or a
  non-string value is rejected, and the error names the full key path
  (`listeners[0].bindAddress`).
- **Secrets are never echoed in an error**: the "offending key/value" rule
  above names the key but omits the value for `keystorePassword` and
  `hashedPassword`, including when either has the wrong type. When a
  malformed section's value is shown, any secret key nested inside it is
  shown as `[redacted]`. A YAML syntax error reports only its line and
  column, not the parser's message, since that can quote the offending
  source line, secrets included.
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
- **Coupled NAMES byte bounds (issue #54)** also apply at startup and reload.
  Let `S` be the resolved server name's UTF-8 byte length, `N` be
  `nicknameMaxLength`, and `Cmax` be `channelNameMaxLength` used as the channel
  UTF-8 byte cap. Both `S + 2N + Cmax + 14 <= 512` (353 RPL_NAMREPLY) and
  `S + N + Cmax + 29 <= 512` (366 RPL_ENDOFNAMES) MUST hold. The first reserves
  a trailing colon and one member status character; the two nickname lengths
  cover the recipient and listed member. The second includes the closing
  numeric's fixed text. An error names `serverName`, `nicknameMaxLength`, and
  `channelNameMaxLength`, both computed reply sizes, and the channel byte budget
  `min(512 - S - 2N - 14, 512 - S - N - 29)`. The limits are rejected, never
  silently clamped. For example, `test.jircd.local` is 16 bytes: `N=216, Cmax=50`
  exactly fills the conservative 353 budget; `N=217, Cmax=50` requires 514 bytes
  and is rejected. The omitted server name is resolved before validation.
  `serverName` remains fixed for the lifetime of the running application;
  reload validates against both the proposed configuration and the running
  prefix before applying extension states or limits. A failed reload keeps
  the previous configuration and ISUPPORT lengths.
- **Channel admission counts UTF-8 bytes as well as characters.** JOIN and
  SAJOIN apply `channelNameMaxLength` to both UTF-16 code units and wire bytes,
  including the leading `#`. Both reply with the existing `476 ERR_BADCHANMASK`
  on overflow. ISUPPORT continues to advertise `CHANNELLEN=<channelNameMaxLength>`;
  no new token or configuration key is introduced. This makes the advertised
  maximum enforceable in wire bytes. The [CHANNELLEN specification](https://modern.ircdocs.horse/#channellen-parameter)
  defines the maximum joinable name length without specifying a Unicode unit.
  Compatibility change: a multibyte name that fits the old character-only check
  can now be refused. Shorten it or raise the limit subject to the coupled bounds;
  previously joined channels and claimed nicknames are not renamed, kicked, or
  disconnected on reload. NICK retains its existing ASCII grammar and maximum
  `N` at registration and subsequent nickname changes.
- **Grandfathered state has a defensive output policy.** A reload that lowers
  one limit and raises another can leave retained names outside the new coupled
  bound. NAMES flushes preceding fitting items, measures actual singleton fit,
  skips an unrepresentable item with a specific WARN (channel, item bytes,
  singleton byte budget), and continues with later items. It never truncates or
  splits a nickname. The one-byte slack from an omitted singleton trailing colon
  is usable. If all items are skipped, no synthetic empty 353 is emitted; an
  actually empty list still produces an empty 353. A header that exceeds 512
  bytes including CRLF is diagnosed separately. JOIN, SAJOIN, and targeted NAMES
  still attempt their channel 366; bare NAMES attempts its `*` 366. A closer
  that independently exceeds 512 bytes is logged and dropped by the writer.
  These fallbacks can produce an incomplete listing for retained old state;
  new name claims obey the configured byte limits. Repeating JOIN or SAJOIN on
  an over-limit channel still returns 476, even for an existing member; the
  existing membership remains intact.
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
- **Listener accept backlog (#58)**: each listener's optional `backlog` MUST be
  a positive 32-bit integer (1 through 2147483647); omission defaults to `1024`,
  including the zero-config plaintext listener. Zero, negative, non-integer,
  and out-of-range values fail validation with `listeners[index].backlog` in
  the error. Both plaintext and TLS binds request this capacity. The effective
  limit is `min(backlog, OS somaxconn)`. See the [README tuning instructions](../../../README.md#listener-accept-backlog)
  for checking and raising `kern.ipc.somaxconn` on macOS or `net.core.somaxconn`
  on Linux. Listener settings are validated and published on reload, but the
  application's existing sockets are not rebound: changing `backlog`, `port`,
  `bindAddress`, TLS, or certificate settings requires a server restart.
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
