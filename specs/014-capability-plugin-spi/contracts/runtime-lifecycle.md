# Contract: Immutable Capability Snapshot and Runtime Lifecycle

Implements FR-005–011 and FR-016; amends `specs/001-ircv3-server/contracts/server-configuration.md` “Path equivalence”, its CAP sections and `data-model.md` “Extension”. Implementation updates canonical documents in #97 and completes their failure policy in #102. No older spec is edited in this design-only revision.

## State and capture

`CapabilityRuntime` is the capability-state authority behind ExtensionRegistry. An immutable RuntimeSnapshot contains the provider/offer tables, compiled hook indexes, dependency graph and validated capability-related configuration. Each ClientSession has an immutable explicit negotiated set and highest supported CAP version. The existing administrative control mutex protects snapshot publication, physical session pruning, CAP mutations and ConnectionHandler.liveSessions insertion/removal/attachment.

Capture a RuntimeSnapshot reference and the relevant immutable session negotiation together under that mutex, then release it before hook evaluation. An output operation uses that captured view throughout preparation. It may finish and be queued after a later disable/DEL. Work prepared after a change captures the new view. The captured view remains valid data; ordinary output does not recheck it at admission or restart preparation because the current view changed.

All accepted live sessions, including unnamed/pre-registration sessions and those awaiting writer attachment, are tracked before CAP processing. Reuse ConnectionHandler.liveSessions, not NicknameRegistry.all(). Insertion/removal and writer attachment use the same mutex, so no session can escape pruning. A session without notification eligibility needs no historical NEW/DEL replay. CAP LIST and CAP REQ capture current state under administrative control rather than combining independently read pointers.

Provider states remain DISABLED, ENABLED or FAILED. Desired YAML state and actual state are distinct: a valid desired enable may result in FAILED if provider start code fails. No callback lifetime/version tracking is added.

## Publication and notification sequence

For disable, enable or failure, one serialized administrative operation does the following:

1. Stage any new immutable registrations/resources outside core locks; validate candidate configuration, dependencies, slot/key ownership and reply budgets before live mutation. Invalid configuration leaves all active state untouched.
2. Under the existing administrative control mutex, calculate offered-set changes and any hard-dependent withdrawal; construct the new RuntimeSnapshot and physically remove withdrawn names from every session in ConnectionHandler.liveSessions. Swap the snapshot in that same critical section. A repeated no-op toggle changes nothing and queues no notification. Enable does not add negotiated names back.
3. After this state publication, queue CAP DEL/NEW for eligible live writers, in administrative transition order. The same control operation may retain the mutex while appending these already validated core frames; notification enqueue is a separately measured stage after publication. No socket I/O or plugin callback occurs under that mutex. Queue errors return dispositions for cleanup after unlocking.
4. Release all core locks before provider stop, registration cleanup, overflow/disconnect callbacks or other plugin callbacks. Stop failures keep the provider withdrawn and use FAILED reporting; no rollback or negotiation restoration occurs.

The state publication and all-session prune are atomic to participating readers. Notification delivery is asynchronous and not atomic across sockets. A stalled/full writer cannot delay publication waiting for network I/O. Notification enqueue must be nonblocking and SendQ-bounded; overflow closes only that recipient through existing cleanup.

Already captured/preparing work may appear after DEL, including after stop. There is no flush or promise of a strict output cutoff. New captures exclude withdrawn hooks/offers and pruned names. A late hook fault is attributed to its provider identity; if that provider has since been enabled again, the failure may withdraw its current offering. Providers must be thread-safe for overlapping captured callbacks and start/stop, without core waiting for active calls. No forceful termination of a hung trusted hook is provided.

## Output and group admission

Ordinary output is prepared outside core locks from its captured view, validated/materialized and appended under only the existing recipient queue lock. There is no lifecycle admission barrier or stale-work test. No provider callback or mutable map is stored in the writer queue, and a captured message retains its logical id/time.

Batch is the narrow exception that needs a current prerequisite choice at group admission:

- Prepare validated complete framed and plain alternatives outside locks, along with the shaper's generic owner/session prerequisites. The core never branches on batch/message-tags names.
- Under the administrative mutex, select framed only if its owner and declared prerequisites are currently effective for the recipient, then append the chosen complete sequence in one queue operation. Lock order for this short group-only path is administrative mutex → one writer enqueue lock. Do not invoke a shaper or serialize unbounded data while holding these locks.
- If batch or message-tags went ineffective before this group is admitted, select plain. No hook rerun, retry loop or extra deferred queue is needed.
- A group already admitted as one operation retains its close. An already-open batch therefore completes unless the connection terminates. DEL need not be ordered after the group's close; test well-formed framing rather than a general output cutoff.
- Normal per-session FIFO still applies to already queued frames. Reserve/validate all group bytes including close before append; overflow admits no partial group and closes only that connection.

Never take administrative control from inside a writer enqueue lock or while holding channel/ChannelRegistry monitors. No two writer queue locks are held together. Plugin callbacks, stop and socket I/O run outside core locks.

## Enable, dependencies and reload

Hard provider dependencies are a validated DAG. Missing prerequisites reject an operator enable with an actionable error. Disabling/failing a hard prerequisite withdraws transitive hard dependents in the same publication/prune step. Enable never silently recovers those dependents or restores their negotiations. An explicit desired enable set, including a later valid REHASH, may retry them. Batch's message-tags prerequisite is session-level behavior only: batch remains offered/held, but new groups are plain until both are effective.

Start/register stages resources outside core locks. On start failure, close staged registrations, leave no offer/NEW, mark the provider and unavailable hard dependents FAILED, and preserve unrelated working providers. Start/stop must be thread-safe with older captured callbacks; this SPI does not support dynamic classloader unload.

REHASH/SIGHUP parse and validate the entire desired configuration before mutation, including bounds/dependencies. A validation failure changes no configuration, session membership or notification. A valid reload reconciles its capability set in one publication, using dependency order. Actual provider execution failures are isolated FAILED outcomes, not disguised as validation errors; successful unrelated changes may take effect. Publish validated capability configuration with the snapshot so readers capture coherent lengths/hook tables. Preserve unrelated reload behavior.

EXTENSION uses the same publication path for a single desired state and reads/persists no YAML. REHASH reads the file. The observable lifecycle is equivalent for both paths. #97 implements disable/enable/negotiation and start-failure handling already supported by the registry; #102 connects all runtime hook faults and bounded diagnostics to this path.

## CAP protocol amendments

CAP notification semantics derive from [IRCv3 CAP](https://ircv3.github.io/specs/extensions/capability-negotiation.html); framing from [IRCv3 batch](https://ircv3.net/specs/extensions/batch.html). This design deliberately permits captured work after DEL as specified above; it does not claim the stronger output-cutoff guarantee. Record this known conformance tradeoff in the implementation/irctest report instead of hiding a newly exposed failure.

| Operation | Required behavior |
| --- | --- |
| `CAP LS [version]` | Advertise core-owned cap-notify plus currently enabled provider capabilities. Remember highest supported version numerically; later lower/omitted version cannot clear >=302 notification support. Preserve registration pause rules |
| `CAP REQ :cap-notify` | Accept through the ordinary offered-name rule. It is always offered core infrastructure, not a toggleable Extension. Explicit membership appears in LIST |
| `CAP REQ :-cap-notify` | NAK whole request when highest version >=302. Older clients may remove it explicitly; already queued/captured notification work may finish |
| `CAP LIST` | Current explicit negotiated set after pruning. Omit implicit-only notification support; include explicitly negotiated cap-notify |
| Positive REQ token | Must be offered; no special acceptance for an unadvertised name. Idempotent request of an offered held name succeeds |
| Negative REQ token | Accept held name regardless of offers, known discovered name already pruned/inactive, or removable explicit cap-notify. Unknown unheld name NAKs |
| Mixed/repeated REQ | Validate candidate in token order, preserve raw echo/order/repeats, check reply size before mutation, commit one explicit set or NAK unchanged. Empty request NAKs; oversize echo returns existing 417 without mutation |
| Disable/failure | Queue `:<server> CAP <nick-or-*> DEL :<removed names>` after publication to all eligible clients, even those that never held removed names. Names only |
| Enable | Queue `:<server> CAP <nick-or-*> NEW :<added names>` after publication. No automatic positive negotiation; future values only in LS/NEW |
| No offered-set change | No NEW/DEL for a no-op toggle or a session-only REQ change |
| No capability plugins | LS still advertises cap-notify; provider omission removes only the omitted provider's offer/behavior |

CAP state operations, eligibility changes and their reply enqueue use administrative control so REQ/LS/LIST cannot observe partial pruning or lose a concurrent update. Hook evaluation is absent from this control path. Notification recipients/nickname target are captured as core facts during the operation; ordinary output is not synchronized to these replies.

Parse nonnegative digit-only LS versions without integer overflow, saturating at supported semantics; malformed/negative is treated as absent for version tracking and preserves ordinary LS behavior. Test zero, empty, malformed, very large and lower/repeated versions.

Names in NEW/DEL are deterministic and deduplicated. Split oversize notifications on token boundaries into independently valid lines; no LS continuation marker. Preserve fitting one-line LS/LIST replies; use supported 302 continuation when necessary. Legacy LS/LIST must fit one line: reject a catalog/enable whose mandatory core + provider offers cannot fit, rather than truncate. Validate maximum server/target headers at registration. Never split or alter ACK/NAK echoes. Core notification frames carry no provider tags.

## Required evidence

- Real-socket matrix {EXTENSION, REHASH} × {explicit legacy cap-notify, implicit >=302, legacy no-notify} × {multi-prefix, message-tags}. Check exact LS/DEL/NEW/LIST, current newly prepared NAMES/WHO/tag behavior, unnamed and never-held observers, re-enable without negotiation restoration.
- Update exact-LS expectations to include core cap-notify and prove it remains when any/all providers are absent. Restrict integration changes to that documented behavior; no unrelated assertion weakening.
- Negative REQ: controlled held-but-unoffered case, known already-pruned removal through real disable, unknown removal, unavailable positive/mixed/repeated/empty/oversize requests, sticky 302 semantics. Demonstrate #97 regression tests fail before the fix.
- Verify one snapshot/prune operation across all live sessions and concurrent CAP/session insertion. A captured old view may finish after DEL; the next capture must use new state. Do not write tests demanding suppression of old captured output.
- Group tests prove complete atomic append, retained close on admitted/open groups, and plain choice for a prepared but unadmitted group after a prerequisite becomes ineffective. No assertion that DEL is an output barrier. Use bounded coordination only to establish these retained behaviors, with finally cleanup.
- Stop runs outside locks, can overlap captured callbacks, and cannot roll back core state. Faults/overflow on one recipient do not break unrelated delivery; #102 adds real-socket fault injection for each wire-affecting hook.
