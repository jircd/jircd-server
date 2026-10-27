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
registrationTimeoutSeconds: 60  # optional — positive integer 1-3600, seconds a connection has to register
motdPath: /etc/jircd/motd.txt   # optional — message-of-the-day file (UTF-8, at most 64 KiB); unset: 422
adminName: Jane Admin           # optional — ADMIN contact lines; with none set, ADMIN answers 423
adminLocation: Example Org, Earth
adminEmail: jane@example.org
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
  multi-prefix: enabled   # 353/352 list every status prefix a member holds

server-extensions:      # ServerExtension states only (jircd-server-extensions/) —
  cloak: disabled        # never CAP-negotiated, administrator-only. A
  admin: enabled         # CapabilityExtension id listed here is a configuration
                          # error. cloak (FR-031) is off by default; admin
                          # (Story 6) MUST be enabled for OPER etc. to work at all.

rateLimit:
  bucketSize: 20        # tokens
  refillRatePerSecond: 5

operFailureThreshold: 5  # 1–20 failures per source bucket, across connections/usernames
operLockoutSeconds: 300  # 1–86400 seconds from first failure; fixed deadline
operRequireTls: false   # true rejects plaintext OPER with 491 ERR_NOOPERHOST

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
- **OPER protection (#74)**: `operFailureThreshold` is an integer `1`–`20`
  (default `5`); `operLockoutSeconds` is an integer `1`–`86400` (default `300`);
  `operRequireTls` is a boolean (default `false`). Invalid types/ranges reject
  startup/reload. Failure windows start at the first failure, span reconnects and
  usernames, and use the same normalized IPv4 /32 / configured IPv6 prefix buckets
  as connection admission. Connection-limit overrides do not exempt OPER failures.
  At threshold, or during lockout, OPER returns `464` and disconnects without
  checking another password. A successful OPER below threshold clears the bucket.
  Plaintext OPER with TLS required returns `491 ERR_NOOPERHOST` with
  `OPER requires TLS; reconnect using TLS`, without checking credentials or counting
  a failure; an already locked source still receives `464` and disconnects.
  `REHASH`/`SIGHUP` apply the TLS rule and threshold to subsequent OPER commands,
  including existing sessions. Duration changes affect new windows only; existing
  deadlines remain fixed. Changing `ipv6ConnectionLimitPrefix` selects new buckets
  for subsequent OPER commands; old buckets expire normally. Reclamation is lazy
  on subsequent attempts. At most 10,000 source buckets are retained; when full,
  new sources fall back to per-session failure counting, still verifying passwords
  and disconnecting at the threshold. Success clears the session counter. Existing
  lockouts are preserved. The source address is captured from the accepted socket,
  separately from the display hostname; if absent, a per-connection key is used.
  Skipped verification during lockout logs `OPER source locked out`; verified
  failures log `invalid OPER credentials`, including the threshold-reaching failure.
  Server restart clears the in-memory tracker.
- **Connection admission limits (#72)**: `maxConnections` defaults to `10000` and
  `maxConnectionsPerIp` to `20`; both MUST be positive YAML integers no greater
  than `2147483647`. Registered and unregistered connections count across all
  plaintext and TLS listeners. IPv4 clients count per /32; IPv6 clients share a
  bucket selected by `ipv6ConnectionLimitPrefix` (default `64`, integer `1`-`128`).
  A value of `128` counts each IPv6 address separately. IPv4-mapped IPv6 clients
  normalize to IPv4 before both bucketing and override matching.
- **MOTD and admin contact (#77)**: `motdPath`, `adminName`, `adminLocation` and
  `adminEmail` are optional strings; a blank value counts as unset and any other type
  is rejected naming the key. With `motdPath` set to a readable regular file of at most
  64 KiB, registration and `MOTD` send `375`, one `372` per line, `376`; otherwise (unset,
  missing, unreadable, a directory, empty, too large) they send `422`, and the problem
  is logged as a warning, at most once a minute per path and reason. The file is
  re-read when its modification time or size changes, so edits need no reload; `REHASH`/`SIGHUP`
  applies a changed `motdPath`. Over-long text is cut to fit the 512-byte command
  section, and only the first 200 lines are served (a warning is logged), keeping
  a registration burst far below the default SendQ. `ADMIN`
  sends `256`-`259` from the three admin settings, `-` standing in for an unset one, and
  `423` when all three are unset; the lines are cut to fit too, and reload applies to the next `ADMIN`.
- **Registration timeout (#73)**: `registrationTimeoutSeconds` defaults to `60` and
  MUST be a positive YAML integer no greater than `3600`; anything else is rejected
  naming the key. A connection that has not completed registration (`NICK`/`USER`
  and, if capability negotiation was started, `CAP END`) within that many seconds of
  being accepted receives `ERROR :Registration timeout` and is closed. `PING`/`PONG`
  traffic does not extend the deadline. Registered connections are unaffected.
  Reload applies to connections accepted afterwards only.
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
- **Reply byte budgets** measure the command section: prefix, command, parameters,
  and CRLF, limited to 512 UTF-8 bytes. IRCv3 message tags use a separate section:
  at most 8191 wire bytes total, with up to 4094 escaped data bytes from each
  client/server origin. Tags never consume the command-section budget.
- **Coupled NAMES byte bounds (issue #54)** also apply at startup and reload.
  Let `S` be the resolved server name's UTF-8 byte length, `N` be
  `nicknameMaxLength`, and `Cmax` be `channelNameMaxLength` used as the channel
  UTF-8 byte cap. Both `S + 2N + Cmax + 15 <= 512` (353 RPL_NAMREPLY) and
  `S + N + Cmax + 29 <= 512` (366 RPL_ENDOFNAMES) MUST hold. The first reserves
  a trailing colon and two member status characters (an operator who is also
  voiced, listed to a `multi-prefix` client); the two nickname lengths
  cover the recipient and listed member. The second includes the closing
  numeric's fixed text. An error names `serverName`, `nicknameMaxLength`, and
  `channelNameMaxLength`, both computed reply sizes, and the channel byte budget
  `min(512 - S - 2N - 15, 512 - S - N - 29)`. The limits are rejected, never
  silently clamped. For example, `test.jircd.local` is 16 bytes: `N=215, Cmax=51`
  exactly fills the conservative 353 budget; `N=216, Cmax=51` requires 514 bytes
  and is rejected. The omitted server name is resolved before validation.
  `serverName` remains fixed for the lifetime of the running application;
  reload validates against both the proposed configuration and the running
  prefix before applying extension states or limits. A failed reload keeps
  the previous configuration and ISUPPORT lengths.
- **Hostmask reply bounds (issue #85)** also apply at startup and reload, after
  the NAMES bounds. Replies that embed a nick!user@host (or an ident and host)
  must fit the 512-byte command section, or `SessionWriter` drops them. The terms
  are fixed by the server: a username (ident) is at most 9 UTF-16 units kept
  from `USER`, so `U = 27` UTF-8 bytes; a presented host is at most `H = 63`
  bytes (RFC 2812 §2.3.1's host limit; the longest built-in value is a 45-byte
  IPv6 literal). With `S`, `N` and `Cmax` as above, the worst cases, excluding
  tags and with a real name counted as empty, are:

  | Reply | Command-section bytes |
  | --- | --- |
  | 352 RPL_WHOREPLY (server name twice, flags `G*@+`) | `2S + 2N + Cmax + U + H + 22` |
  | 333 RPL_TOPICWHOTIME (10-digit timestamp) | `S + 2N + Cmax + U + H + 23` |
  | 311 / 314 WHOIS / WHOWAS user | `S + 2N + U + H + 15` |
  | 302 RPL_USERHOST (one entry; several entries split across 302 lines) | `S + 2N + U + H + 13` |
  | `:nick!user@host INVITE nick #chan` | `2N + Cmax + U + H + 14` |

  Every command-section size MUST be `<= 512` bytes. An error names `serverName`,
  `nicknameMaxLength` and `channelNameMaxLength`, the `U` and `H` terms, and each computed size. The
  limits are rejected, never clamped; reload validates against the running
  server name. Because 352 repeats the server name, it is normally the binding
  one: at the defaults (`N=9`, `Cmax=50`) the server name may be at most 166
  bytes. A real name that would overflow 311, 314 or 352 is cut on a code-point
  boundary instead of dropping the line.
  `INVITE` to a channel that does not exist applies JOIN's name check
  (`channelNameMaxLength`, channel prefix) and answers `476` before sending
  anything, so the echoed name is bounded.
- **A `hostname-display` extension's host is capped at 63 bytes.** The value
  `PresentedIdentity` gets from the extension is truncated to 63 UTF-8 bytes on a
  code-point boundary. The truncated value is the fallback, not the real
  hostname, which a cloak exists to hide. A WARN names the extension, at most
  once a minute; nothing is thrown into the command path.
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
  actually empty list still produces an empty 353. A header that cannot fit the
  512-byte command section is diagnosed separately. JOIN, SAJOIN, and targeted NAMES
  still attempt their channel 366; bare NAMES attempts its `*` 366. A closer
  whose fixed fields alone exceed the 512-byte command section is logged and
  skipped by the shared reply guard; otherwise its free closing text is fitted.
  The same policy applies to hostmask replies and nick/channel pairs, including
  WHO 352, USERHOST 302, WHOIS 311/312/301/313/318, WHOWAS 314/369,
  topic metadata 333 (both TOPIC and JOIN), INVITE and 341, and channel
  notifications such as JOIN, PART, KICK, MODE and TOPIC. Free trailing text
  in numeric replies is fitted on a Unicode code-point boundary, including
  unknown-target errors and closers, and away replies on PRIVMSG/NOTICE.
  Nicknames, channels, idents, hosts and timestamps remain intact;
  if those fixed fields cannot fit, the reply is skipped. USERHOST uses the
  same indivisible-item policy as NAMES and continues with later entries.
  A shared writer fallback covers other notifications, while PRIVMSG/NOTICE
  retain their existing text-fitting policy. A failed text fit distinguishes fixed
  fields exceeding the command-section budget from too little room for one complete
  body code point, reporting the actual fixed-field byte count. Skips name the
  numeric or command and the command-section budget in a WARN. Warnings for
  indivisible replies are limited
  to one per command per minute across recipients, using at most 256 keys and
  reporting suppressed counts on the next warning. List warnings retain their
  existing per-numeric/channel throttle. Command processing continues and
  attempts its terminating numeric (315, 318, 369, etc.), which is independently
  fitted or checked by the same policy.
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
- **Runtime capability changes** (014-capability-plugin-spi, #97; amends
  "Path equivalence" above): `EXTENSION`, `REHASH` and `SIGHUP` all change a
  capability through the same lifecycle. Under the capability runtime's
  administrative mutex, one step publishes the new immutable runtime
  snapshot and removes every withdrawn name from the negotiated set of every
  connected session, registered or not; disabling a provider also withdraws,
  in that same step, every provider that declares it as a hard dependency.
  `CAP DEL` (for removals) and then `CAP NEW` (for additions) are queued
  afterwards to each notification-eligible client — one that sent
  `CAP LS 302` or later, or negotiated `cap-notify` — whether or not it held
  the name; other clients get neither. Provider `start`/`stop` run outside
  that mutex. Enabling a capability never restores anyone's earlier
  negotiation: behavior resumes for a client only after it requests the
  capability again. A repeated toggle changes nothing and sends nothing.
  Output that was already prepared from the earlier state may still be
  delivered after the `DEL`; there is no output cutoff. Anything prepared
  after the change uses the new state. `REHASH` validates the whole file
  first, including the whole candidate provider graph (every hard
  prerequisite of an enabled provider enabled, no dependency cycle, no
  duplicate offer or owned slot, no provider command that core or a server
  extension already handles); an invalid file changes no configuration,
  negotiation or notification, and starts or stops no provider. A valid reload is one lifecycle operation
  that no `EXTENSION` toggle can interleave with: providers to enable start
  first, prerequisites before their dependents, then one publication swaps
  in every capability change together with the new reply limits and
  configuration (so one `CAP DEL`/`NEW` per reload), and only then are
  withdrawn providers stopped, each before what it requires. A command a
  withdrawn provider owned may be taken over by a provider the same reload
  enables; it moves to the new owner in that publication. A provider
  whose start fails ends `FAILED`, together with any provider in the same
  reload that requires it, and is reported without undoing the rest: to the
  `REHASH` sender, or in the server log for `SIGHUP`. `EXTENSION` reports the
  state the toggle actually left; a disable whose withdrawn dependent fails
  to stop reports that failure even though the named provider itself is
  disabled.
  A nickname change and the client's own `NICK` line are serialized with
  publication, so a notification always addresses the nickname the client
  knows at that point.
- **Runtime capability faults** (014-capability-plugin-spi, #102): a
  capability hook that throws, returns `null`, or returns an illegal,
  unauthorized or out-of-bound result (beyond its own declared bound, even
  if the line would still fit 512 bytes) has that one result replaced by
  its documented default, so the operation that ran it completes and no
  committed core state (a JOIN, AWAY, NICK, PART or QUIT) is undone. The
  provider is then withdrawn as `FAILED` through the same publication a
  disable uses, together with every provider that requires it: one
  snapshot swap, the prune from every connected session and one
  `CAP DEL`. The withdrawal runs off the faulting thread, ordered with
  `EXTENSION` and reloads; the provider and its dependents are then
  stopped, dependents first, without waiting for callbacks still running
  against an earlier snapshot. A stop that throws leaves the provider
  withdrawn and `FAILED` with the cleanup owed to a later disable; the other
  stops still run. Nothing recovers automatically. `EXTENSION ENABLE`
  always retries a `FAILED` provider. A `REHASH` or `SIGHUP` retries one
  only if the provider's own entry under `capabilities` differs from the
  configuration last applied: its value changed, or the entry was added or
  removed. A reload that changes anything else leaves it `FAILED`, even if
  the file still says `enabled`. The same rule applies to each hard
  dependent, by its own entry, and to a provider whose start failed. A
  retry starts the provider afresh and restores no negotiation. A
  dependent whose entry changed while its prerequisite is still `FAILED`
  makes the reload invalid (missing prerequisite), so nothing changes. A fault from work captured before a
  re-enable is still the provider's, and withdraws it again. Valid
  contributions that only exceed a protocol limit together (two tag
  providers whose tags do not fit one origin) drop the later one, logged
  as `COMPOSITION_LIMIT`, and fault no one. The server log gets one warning per failure transition, naming
  the provider, the hook kind, the reason category and the withdrawn
  dependents, and never an exception message, stack trace, client content
  or configuration value; further reports and cleanup warnings for that
  provider are limited to one per 10 seconds, counting what was
  suppressed.
- **Lifecycle cost** (#102 measurements, `specs/014-capability-plugin-spi/validation/pr-102/performance.md`):
  at 1,000 connected sessions with output and groups flowing, waiting for
  the administrative mutex plus the snapshot swap and all-session prune
  stays within a 100 ms p99 budget; `DEL`/`NEW` enqueue, file I/O,
  provider callbacks and socket drain are measured separately and are not
  part of it.
