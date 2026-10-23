# Research: Capability Plugin SPI

All decisions below are proposals for implementation after review, not descriptions of changes already made.

## Evidence and scope

- Read `gh issue view 98` and `gh issue view 97`; #98 requires actual behavior ownership and unchanged integration/irctest results; #97 requires atomic pruning, DEL/NEW and safe negative REQ. The later user decision intentionally updates exact CAP LS expectations in #97.
- Ran CodeGraph on CapabilityExtension, AbstractCapabilityExtension, ExtensionRegistry, CapabilityNegotiator, CapabilityTagRenderer, SessionWriter batching, AWAY fan-out, Channel.memberListing/statusPrefixes, WhoCommandHandler, ServerExtension, CommandRegistrar, ServerContext and HostmaskReplyBudget. CodeGraph warned its index belongs to the main checkout. Used targeted current-worktree source reads/searches to verify findings; did not rebuild the index. Read-only inventory assistance was independently verified, including correcting its claim that negotiated sets were already immutable.
- Worktree HEAD is `5912b68`; `ClientSession` swaps an AtomicReference but exposes mutable concurrent sets. Current API therefore does not enforce a lifecycle-wide atomic transition.
- Reviewed constitution I–IV and gates, `.github/REVIEW_CHECKLIST.md`, original configuration “Path equivalence”, CAP contract and Extension model, plus feature 011's plan and away/batch contract. Original feature documents have historical omissions; this feature's contracts explicitly amend them and tasks update canonical documentation at implementation time.

## Complete current core behavior inventory

Paths are relative to `jircd-core/src/main/java/net/jircd/core/` unless specified. A scoped search covered both `CapabilityName` references and literal existing capability names, plus generic hooks and bootstrap wiring.

| Location | Existing behavior to extract | Destination/seam |
| --- | --- | --- |
| `session/CapabilityTagRenderer.java:35–75` | MESSAGE_TAGS forwards client tags; scans enabled providers and captures deferred tag callbacks | message-tags owns forwarding/msgid; generic compiled tag dispatcher |
| `session/command/TagmsgCommandHandler.java:97` | MESSAGE_TAGS recipient filter; tag-only command and fan-out | message-tags owns command; shared core delivery service retains send gates |
| `session/command/AwayCommandHandler.java:75–91` | AWAY_NOTIFY filters deduplicated shared-channel peers and builds AWAY | away-notify committed-event handler |
| `session/SessionWriter.java:174–207` | BATCH and MESSAGE_TAGS gates, ref generation, BATCH frames and member tag | batch output shaper; writer handles generic validated frames |
| `session/ChannelRegistry.java:110–112` | MULTI_PREFIX in JOIN result listing | raw member snapshots, render through reply service after locks |
| `session/command/JoinCommandHandler.java:244–247` | MULTI_PREFIX in NAMES; used by JOIN/NAMES/SAJOIN | generic member-field renderer |
| `session/command/WhoCommandHandler.java:149–152` | MULTI_PREFIX in channel WHO status flags | same member-status slot |
| `session/Channel.java:117–158` | Boolean multiPrefix overloads implement highest versus all statuses without a CapabilityName reference | core exposes consistent ordered privileges, keeps ordinary highest-prefix default; multi-prefix selects all |
| `session/command/MessageCommandHandler.java:134–199` | Generic sender-inclusion scan and echo-specific assumptions; shared TAGMSG routing helpers package-private | generic recipient policy and public narrow authorized-delivery service |
| `session/OutboundMessage.java:111–115` | Memoized contribution bytes call providers; renderer later calls them again | immutable validated provider contribution cache by envelope/hook identity |
| `jircd-server/.../JircdServerApplication.java:310` | Hard-wires core TAGMSG handler independently of provider | message-tags stages its own command registration |

Core `capability/CapabilityNegotiator` and `session/command/CapCommandHandler` legitimately handle CAP. They currently validate removal against offers, ignore LS version, expose a validate/apply race and have no NEW/DEL. Protocol `CapabilityName` constants and BATCH/TAGMSG token recognition may remain wire vocabulary; no core behavior may branch on these six names or aliases. `server-time` already owns its tag; echo-message owns its include decision but core scans every enabled extension and the plugin reads a live session.

## Decision 1 — Typed, staged registrations

**Decision**: CapabilityExtension registers typed hooks through CapabilityRegistrar alongside the existing Extension lifecycle. Publish immutable descriptor/hook tables. ServerContext supplies narrow event/reply/delivery services; command registrations are provider-owned. Retain admin/cloak's ServerExtension/CommandRegistrar behavior.

**Rationale**: Actual behavior ownership like admin, with central negotiation prerequisites, composition, validation and failure containment. No reflection/discovery on message paths.

**Alternatives**: More unrelated default methods would leave registry scans and implicit composition. A general event bus or separate public API artifact adds machinery without a present consumer. Keep public SPI under core's extension package.

## Decision 2 — Snapshot capture and simple administrative publication

**Decision**: Use the existing administrative control mutex to publish one immutable RuntimeSnapshot and physically prune every live session in ConnectionHandler.liveSessions in one step, then queue DEL/NEW. Capture runtime + relevant immutable session negotiation under that mutex, release it and evaluate hooks outside core locks. Ordinary prepared output uses its captured view without lifecycle admission checks. Already captured/preparing work may follow DEL; later captures use new state. Stop runs outside core locks and may overlap captured callbacks.

**Rationale**: This is the user's chosen lifecycle rule. It meets atomic state/prune requirements without promising a strict output cutoff or tracking the lifetime of each callback. Provider start/stop/callbacks must be thread-safe; late valid results are allowed, malformed ones are not. A late fault is attributed to the provider identity and may withdraw a provider that was enabled again in the meantime.

**Batch exception**: A finite group is a complete validated queue operation with a close. Prepare framed/plain alternatives outside locks; at group admission make a current prerequisite choice under the existing administrative mutex and append under the writer queue lock. If batch or message-tags is ineffective then, choose plain. This narrow selection has no hook call/retry and does not synchronize ordinary output admission. An admitted group keeps its close even if it overlaps DEL.

**Alternatives**: A stricter guarantee suppressing captured output after DEL was rejected by the user as unnecessary complexity. Waiting for socket drain lets a slow client block administrative changes. Eventual background pruning does not satisfy atomic all-session state changes.

## Decision 3 — Advertise core cap-notify

**Decision**: CAP LS advertises core-owned cap-notify, including when no capability provider is installed. Explicit positive REQ uses the ordinary offered-name rule. >=302 implies sticky notification support; explicit negotiation appears in LIST, implicit-only support need not. >=302 clients cannot remove support, older explicit clients may. Enable never restores pruned memberships.

**Compatibility**: #100/#101 keep existing integration tests unchanged. #97 changes exact CAP LS expectations (including Story2CapabilityListTest) and provider-absence expectations to retain cap-notify. No unrelated assertion weakening. Do not retain a special acceptance rule for unadvertised positive requests.

Negative REQ accepts a held name regardless of current offers and a discovered already-pruned name as an acknowledged no-op. Unknown unheld removal remains NAK. Preserve raw token order/repeats and check reply size before mutation under administrative control.

**Sources**: [IRCv3 CAP](https://ircv3.github.io/specs/extensions/capability-negotiation.html) defines version persistence, notifications and explicit cap-notify negotiation. [IRCv3 batch](https://ircv3.net/specs/extensions/batch.html) requires closing started batches. The repository's stricter batch/message-tags fallback is preserved. Allowing captured output after DEL is an explicit user-selected conformance tradeoff; record any observed test consequence instead of concealing it.

**Alternatives**: Hiding cap-notify to preserve exact old LS tests was rejected by the user. Making it a toggleable plugin incorrectly couples CAP infrastructure to a capability module.

## Decision 4 — Availability dependencies and behavioral prerequisites

**Decision**: Hard provider dependencies form a validated DAG. Missing prerequisites reject enable; withdrawal of a prerequisite removes transitive hard dependents in the same publication. Explicit desired enable is needed to restore them. Session-level prerequisites suppress a hook with declared default, without withdrawing the owner's offer or negotiated name. Batch uses a session message-tags prerequisite: batch-only negotiation ACKs, members are plain, and a later message-tags request can make still-held batch effective. server-time remains independent.

**Rationale**: Preserves feature 011's semantics and permits real future dependencies. Validate the entire candidate reload before mutation; start-code failure is an isolated FAILED outcome, not a configuration validation rollback. Optional interactions use request context/delivery roles rather than cyclic plugin calls.

## Decision 5 — Contain first, complete FAILED withdrawal in #102

**Decision**: #100 validates registrations/results and catches ordinary hook RuntimeException/LinkageError so partial or malformed contributions cannot damage core state or other providers, retaining sanitized operator-visible reporting through the existing diagnostic path. #102 completes provider FAILED withdrawal through #97's state/prune/DEL path and bounded, sanitized diagnostics. Fallback applies only to the failed contribution; valid captured work is not restarted. Committed AWAY/JOIN/PART/QUIT/NICK state is never rolled back. Stop failure cannot republish state; no automatic recovery.

**Rationale**: Each PR remains usable, while fault-to-lifecycle integration lands only after that lifecycle exists. This matches #100's basic exception containment acceptance and the user's allocation of full fault isolation to #102.

**Alternatives**: Silent faults or propagation through fan-out would break existing isolation. Core does not forcibly terminate blocked trusted code or wait for callbacks; providers own synchronization of resources needed by captured work. VM-fatal errors remain outside ordinary plugin fault handling.

## Decision 6 — Typed fields and deterministic byte bounds

**Decision**: Separate status and NAMES identity slots; one replacement owner per slot, independent slots compose in schema order. Tags have owned keys/namespaces and distinct client/server origins. Grouping is outermost after field/tag composition. No arbitrary rewriting of numeric arity.

Current safe NAMES bound is `S+2N+C+15` (two status symbols); end `S+N+C+29`; WHO reserves four flags. Generic ReplyLayout + installed-provider bounds retain those safe configurations. Reserve all discovered compatible decorations so later enable does not invalidate accepted lengths. Validate active composition at enable and guard actual bytes at runtime. Full formulas, tag limits and SendQ rules remain in the budget contract.

## Decision 7 — Four PRs with independent green builds

**Decision**: Deliver #100 foundation/server-time/docs, then #101 five migrations/legacy removal, then #97 runtime lifecycle/cap-notify advertisement, then #102 fault withdrawal/diagnostics/dedicated load measurement. Every task has one PR owner; each PR ends in `./gradlew build` green and a documented checkpoint. Run scoped tests after each capability migration. #101 runs the frozen-harness irctest comparison; #102 reruns after final lifecycle/fault behavior.

**Rationale**: Smaller reviewable increments with clear issue ownership and no intermediate claim that all final-feature criteria are complete. Public migration docs accompany changes, and exact-LS changes are localized to #97.

## Decision 8 — Measure cost under the simpler rule

**Decision**: Compile hook tables on changes, reuse immutable session selections and memoize validated pure tags by envelope/hook identity solely for computation sharing. Ordinary per-recipient work is O(applicable hooks + output bytes); shared-channel event audiences are collected once. Publication is O(live sessions × withdrawn names); capture and group selection use short existing-mutex sections.

Dedicated #102 loads compare against the recorded main baseline (`5912b68` for this design; record any later main comparison used). Preserve delivery <1 s and JOIN-storm quiet p99 <500 ms/max <1 s/storm <15 s. Require <=10% throughput regression unless justified, and review >10% latency/CPU/allocation increases. At 1,000 sessions, mutex wait + snapshot/prune publication p99 <=100 ms; measure notification enqueue, provider callbacks/config I/O and socket drain separately. Do not measure completion of old captured output as part of state publication.

## Resolved decisions and exclusions

The user's three decisions are incorporated without unresolved design questions. No implementation, build, SonarCloud upload or irctest run was performed during this revision. The local irctest harness was already dirty; reproducible comparisons must preserve and hash that state. Dynamic JAR unload, untrusted sandboxing, persistent history/account features and unbounded output streaming remain outside scope.
