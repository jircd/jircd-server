# Contract: outbound accounting and capability contributions

## Contribution purity and memoization

`CapabilityExtension.contributeTags(message)` MUST be a pure function of the message. Equal
message inputs produce equal maps, without recipient, wall-clock, random or mutable-state inputs.
The existing message-tags and server-time implementations use `message.messageId()` and
`message.sentAt()` respectively. Document this at the interface and test both implementations.

Compute each enabled capability's contributed escaped byte metadata once per message, memoized
by capability identity and shared safely across concurrent recipients. Metadata includes entry
counts needed for delimiters. The cache lives with the message, not in an unbounded global map.
Recipient rendering remains at drain time. Only negotiated enabled capabilities contribute to
that recipient's charge; forwarded client tags require message-tags negotiation and `+` keys.
Accounting and rendering selections must remain consistent across queued capability/reload
changes: a later render cannot add bytes absent from the reservation.

## Conservative upper bound

For each recipient, compute without serializing its wire line at enqueue:

1. Component bytes: UTF-8 prefix, command and parameters, including prefix `:`, separating spaces,
   trailing-parameter `:` when required, and CRLF.
2. Selected tag entries: forwarded client `+` entries only if message-tags is negotiated, plus
   the entries contributed by each negotiated enabled capability. Each entry contributes UTF-8
   key bytes and, for a nonempty value, `=` plus escaped UTF-8 value bytes. Account for the extra
   byte when semicolon, space, backslash, CR or LF is escaped.
3. Tag framing: for a nonempty selected-entry set, `@`, one space and one `;` between entries;
   zero for an empty set. Summing per-capability metadata includes separators between groups.

Sum all selected entries before merging: duplicate/overridden keys may overcount. Count original
component bytes before text truncation. The result is a **conservative upper bound**, exact when
there are no collisions or truncation. It must never be smaller than the emitted wire line,
including CRLF. There is no fixed tag allowance, and no new restriction on legal contributions.
The raw path uses its supplied tags with the same byte rules. The maximum-size legal line must
fit an empty queue at the 16 KiB floor even with conservative accounting.

Reserve atomically before enqueue; overflow if `queuedBytes + charge > limitBytes`, undo the
failed reservation, and release an accepted entry's charge when the writer takes it. The byte
budget describes queued protocol data; JVM objects and memoized metadata have additional overhead.

## Required tests (written before implementation)

- Compare reserved bytes with rendered wire bytes for no capabilities, each capability and their
  combinations, with/without message-tags and forwarded client `+` tags; non-`+` tags are filtered.
- Include multibyte UTF-8, all tag escapes, empty values, parameter syntax, duplicate keys/merge
  precedence, truncated text and a maximum-size legal line. Verify exact charges in ordinary
  cases and `charge >= wire bytes` in every case.
- Include a legal server tag `s=` plus 4,092 ASCII value bytes: 4,094 escaped data bytes and
  4,096 bytes with framing. It must not be charged as a small constant.
- Verify contribution metadata computation once per message/capability under concurrent fan-out,
  separate metadata for new messages/capability identities, and purity of both built-ins.
- Cover capability negotiation/reload while an entry waits for drain; no uncharged decoration.
