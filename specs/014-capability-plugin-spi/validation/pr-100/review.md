# Review record (T015, partial)

**Status:** the implementer's self-check (below), a SonarCloud branch analysis and a Codex review are done. SonarCloud (coordinator run): quality gate green with **0 open issues** after the 20 earlier issues were fixed. Codex findings 1-8 are fixed (table below); finding 9 (baseline/candidate load evidence for T001/T015) is the coordinator's and no `loadTest` was run here.

## Codex review findings and resolution

| # | Finding | Resolution (test first) |
| --- | --- | --- |
| 1 | P1: a provider command could replace a core command; no rollback | `CommandRegistrar` gained `isRegistered`/`unregister` (backed by `ConnectionHandler.hasHandler/removeHandler`). Enable rejects an already-owned command; installation rolls back on any failure, including Errors, and withdraws the publication; disable unregisters exactly what the provider installed (the 421 adapter is gone). Tests: `aProviderCannotReplaceACommandCoreOrAnotherExtensionAlreadyOwns`, `aFailureInstallingLaterCommandsRollsBackTheEarlierOnesAndThePublication`, `providerCommandsAreInstalledOnEnableAndRemovedOnDisable` |
| 2 | P1: `FrameSequence` did not validate IRC syntax or the per-origin tag budget | new `FrameSyntax` (command token, prefix, parameters free of CR/LF/NUL, middle-parameter rules, tag keys, 4094-byte budget for server and client origins separately) runs before materialization. Tests: `rejectsFramesWhoseSyntaxWouldSplitOrCorruptTheWireLine`, `enforcesTheServerOriginTagBudgetNotJustTheCombinedSection` |
| 3 | P2: individually valid tag hooks could overflow together at drain and drop the core message | `CapabilityTagRenderer` composes the hook origin at enqueue; a hook that would overflow it is dropped and reported, the core message goes out. Test: `hooksThatAreIndividuallyValidButOverflowTogetherLoseTheLaterOneNotTheMessage` |
| 4 | P2: a throwing `start()` leaked what it had acquired | `stop()` is now always called after a failed start (documented on `Extension.start`). Test reversed: `aStartFailureStillCleansUpWhateverWasAcquiredBeforeItThrew` |
| 5 | P2: group plans could lose members or leave an opener unmatched | plans must contain every member once and in order (extra tags allowed), openers only before the first and an equal number of closers after the last, within the byte bound; otherwise plain members. Test: `groupPlansMustCoverEveryMemberInOrderWithMatchedFraming` (six malformed plans). Limit: openers and closers are matched by count and position, not by batch reference, which is provider-specific |
| 6 | P2: dispatch used reference limits | `RuntimeSnapshot` carries the configured `ReplyLimits` (refreshed on attach and on reload via `ExtensionRegistry`); slot bounds are evaluated against them at dispatch. Test: `replyBoundsAreEvaluatedAgainstTheConfiguredLimitsNotTheReferenceOnes` |
| 7 | P2: `CapabilityServices` exposed the mutable runtime | it now wraps a `Supplier<RuntimeSnapshot>` and `ExtensionRegistry` exposes `capabilitySnapshot()` instead of `capabilityRuntime()`. Test: `capabilityServicesExposeOnlyAReadOnlySnapshot` |
| 8 | P2: discovery silently dropped duplicate ids | `register` rejects a different instance with an existing id; `discover` logs and skips it and continues. Test (real `ServiceLoader` path via a services file): `duplicateProviderIdsAreRejectedAtRegistrationAndDiscoveryReportsThem` |
| 9 | P2: load/coverage evidence | recorded in [baseline.md](baseline.md) (coordinator's load runs) |

## Codex round 2 and SonarCloud round 2

| # | Finding | Resolution (test first) |
| --- | --- | --- |
| 1 | typed and legacy tags were budgeted separately, so a typed contribution plus message-tags `msgid` could drop the message at drain | legacy tags are now evaluated once per envelope (`OutboundMessage.legacyTags`) and counted first; a typed contribution that would overflow the combined origin is dropped and reported. Test: `aTypedContributionThatOverflowsWithALegacyOneIsDroppedNotTheMessage` (writer level) |
| 2 | unregister removed whatever handler held the command | `unregister(command, installedHandler)` removes only that instance (`ConcurrentHashMap.remove(key, value)`); the registry tracks what it installed. Tests: `ConnectionHandlerCommandTableTest` (real command table, OPER replacement sequence), `disablingAProviderLeavesAReplacementInstalledByAnotherExtensionAlone` |
| 3 | `CommandRegistrar` defaulted `isRegistered`/`unregister` | both are abstract, so a registrar that cannot remove handlers cannot exist; the lambda form no longer compiles. Test: `aRegistrarMustSupportOwnershipAwareRemovalSoNoProviderHandlerOutlivesWithdrawal` |
| 4 | a failing unregister skipped withdrawal or the rest of disable | rollback attempts every removal and always withdraws; disable attempts removals, `stop()`, state and extension-point release, keeps the first failure and suppresses the rest. Tests: `anUnregisterFailureDuringRollbackStillWithdrawsThePublication`, `anUnregisterFailureDuringDisableStillStopsAndFinalizesTheProvider` |
| 5 | group plans accepted members posing as framing and unpaired openers | openers must pair with closers from the inside out (same command, `+ref` against `-ref`). Tests: three more counterexamples in `groupPlansMustCoverEveryMemberInOrderWithMatchedFraming` (`[m2,m1,m2,m1]`, `[BATCH +r,m1,m2,BATCH +s]`, wrong reference) |
| 6 | tag values with NUL passed | `FrameSyntax` and the typed outbound-tag validator reject NUL in tag values. CR and LF are not rejected: the serializer escapes them, they are legal in a tag value, and a client-only `+` tag can carry them. Tests: `PreparedDeliveryTest` (NUL value), `aServerTagValueCarryingNulIsAFault` |
| nit | duplicate-discovery log not asserted | a logback `ListAppender` asserts the warning names the duplicate class and id |
| Sonar | S135, S3776, two S1612, S1130, S5853 | restructured the three slot loops and split the group validator; method reference; removed `throws`; chained assertions |

## Codex round 3

| # | Finding | Resolution (test first) |
| --- | --- | --- |
| 1 | `addSuppressed` on the same instance throws `IllegalArgumentException`, skipping finalization when unregister and `stop()` throw one shared exception | failures are combined by `accumulate`, which never suppresses an exception into itself. Test: `oneSharedExceptionFromUnregisterAndStopStillFinalizesTheProvider` |
| 2 | removal caught only `RuntimeException`, so a `LinkageError` from `unregister` left the state ENABLED, skipped `stop()` and kept bookkeeping | removal, rollback and disable catch `RuntimeException \| LinkageError` and continue every cleanup step. Test: `aLinkageErrorFromUnregisterStillStopsAndFinalizesTheProvider` |

## Self-check against REVIEW_CHECKLIST.md

| Area | Result |
| --- | --- |
| Sonar S1181 | `RuntimeException \| LinkageError` is caught at the extension boundary (the spec requires LinkageError); no `Throwable`/`Error` catch, and `VirtualMachineError` propagates (`linkageErrorsAreContainedButVirtualMachineErrorsPropagate`) |
| S7467 / S1186 / S2445 / S3077 | unused catch parameters are `_`; no empty methods besides commented ones; only `synchronized` methods or private final monitors; the one `volatile` (`CapabilityRuntime.snapshot`) holds an immutable class |
| S107 (more than seven parameters) | the 11-argument `HookRegistration` constructor and the 9-argument registrar staging method became a package-private builder |
| S2925 / S5778 / S5853 (tests) | no `Thread.sleep` (the writer test waits on a semaphore); the registrar test builds its arguments outside the `assertThatThrownBy` lambdas. Some other lambdas still make two calls (for example `Set.of(...)` inside a lambda); I did not exhaustively rewrite them, so expect possible S5778 notes from Sonar |
| Failure and cleanup paths | a failed `register`, budget check, publication conflict or command registration closes the registrar, stops the started provider and leaves nothing published (`ExtensionRegistryCapabilityTest`); a stop failure after withdrawal keeps the provider withdrawn and FAILED and keeps its extension-point claim until a retried `disable` succeeds |
| Concurrency and reload | the snapshot is immutable and replaced atomically (publication is serialized by the runtime; `enable`/`disable` by a private lifecycle lock that no message path takes); the renderer captures one snapshot per preparation; a captured `PreparedTags` renders after disable. Hooks run with no dispatcher lock (`noHookRunsWhileTheCallerHoldsTheDispatcherOrSnapshotMonitor`). The one monitor a hook can run under is the per-envelope `OutboundMessage` memo monitor, which existed before this change |
| Protocol size limits | tag contributions are measured with the serializer's escaping against a declared bound of at most 4094 bytes; frame sequences are serialized and counted exactly before admission; a sequence that does not fit overflows the session whole (`PreparedDeliveryTest`); NAMES/WHO bounds go through one arithmetic (`CapabilityReplyBudgetTest`) |
| Hostile or oversized input | registrations are bounded (64 hooks, 16 dependencies, 8 prerequisites, 16 owned keys, 8 request attributes of 256 bytes, 8 event intents, 8 framing overhead frames); a provider's output is rejected on its declared bound before it is copied into a recipient plan |
| Extension boundaries | an exception, null, forged key, widened audience, unordered/foreign status symbol, unsafe identity token and oversized plan are each dropped whole with a default and a report; unrelated providers and committed state are unaffected (`CapabilityDispatchTest`) |
| Validation edge cases | empty/null/duplicate/oversized ids, names, keys, bounds and dependencies are rejected at staging or snapshot build; `cap-notify` and the `batch` tag are reserved |
| Rejected requests change nothing | a rejected publication leaves the previous snapshot; a refused disable (a published dependent) changes nothing |
| Security | the report carries provider id, hook kind and category only; no exception text, tags or client content is logged by the dispatcher |
| Determinism | no locale-dependent formatting added; the server-time formatter is UTC with a fixed pattern |
| Public API compatibility | `ServerContext` gained a `capabilityServices` component, so every construction site changed (main application and one test fixture); `ConfigurationLoader` and `HostmaskReplyBudget` keep their old signatures. The unit fixture `CapabilityNegotiatorTest.twoExtensionsOfferingTheSameNameDoNotBreakRequests` had to change because duplicate offers are now rejected at enable (see results.md). Migration notes are in the README and the Javadoc; `0.1.0-SNAPSHOT` is unreleased |
| Tests that can pass while the code is wrong | assertions check exact content (tag maps, status strings, byte counts, ordering of written lines), counters prove the legacy method is never called and the hook runs once per envelope, and a migrated provider's disable is asserted both for new captures and for already-captured work |
| Docs and contracts | README section, `package-info`, `CapabilityExtension`/`AbstractCapabilityExtension` Javadoc. They say what is delivered (typed tags, safe dispatch, budgets, whole-frame queue) and what is not (runtime FAILED withdrawal, event publication, the other five migrations) |

## Known limits, for the reviewer

- Event, inbound-tag, recipient, member-status, identity, reply-field and group hooks are registered, validated and dispatched with containment, but no core call site invokes them yet: that wiring belongs to the migration PRs that give them a real caller. Only the outbound-tag path is live (server-time).
- A faulty hook is contained and reported on each call; it is not withdrawn (that is FAILED withdrawal in #102). The report is throttled to one warning per provider/hook/category per 10 s.
- Provider commands are installed on enable and removed on disable; an in-flight call already holding the handler may still finish.

## Sourcery (PR #104)

15 threads, all verified against the code. Tests were written before the fixes; I did not run them against the old code to watch each fail (no git stash/worktree allowed), so "test added" means a regression test exists, not that I saw it red.

| # | Real? | Test | Fix |
| --- | --- | --- | --- |
| S1 | yes | `aLeftoverHandlerRefusesToRunAndIsRemovedByRetryingTheDisable`, `aFailedRollbackKeepsTheLeftoverForRetryAndItsHandlerRefusesToRun` | installed-command records survive a failed removal (only the failed ones); each batch has a `live` switch the guarded handler checks, so a leftover answers 421 instead of running (it runs only while the published snapshot contains its provider); `disable` on a FAILED provider with unfinished cleanup retries removal and `stop`; `enable` first clears leftovers |
| S2 | yes | `hooksAreNeverVisibleBeforeTheCommandsAreInstalledNorAfterAFailedEnable` | commands are installed first, hooks published last in one atomic step; rollback withdraws before removing |
| S3 | yes (the registry monitor was held across `start`/`register`/`stop`) | `providerCallbacksRunOutsideTheRegistryMonitor` | `enable`/`disable` are no longer `synchronized`; a private `ReentrantLock` serializes lifecycle changes and nothing on a message path or registry read takes it |
| S4 | yes (during `disable`, state stayed ENABLED after withdrawal) | `legacySelectionFollowsTheCapturedSnapshotNotTheLiveEnabledState` | legacy providers are picked from the captured snapshot's provider ids (every enabled capability publishes at least an empty descriptor) |
| S5 | yes for event-intent lists, recipient-role sets and tag maps (the records already copy) | `aTagResultThatChangesAfterTheFirstReadIsValidatedAndUsedAsOneCopy`, `aRecipientRoleSetThatCannotBeReadTwiceStillYieldsItsValidatedCopy`, `anEventIntentListThatCannotBeReadTwiceStillYieldsItsValidatedCopy` | `validated` copies first (inside the hook-fault containment), validates the copy and returns only it |
| S6 | yes | `aBoundThatExceedsOrThrowsAtTheConfiguredLimitsIsAValidationErrorNotAnIllegalArgument` | `validateReplyBudgets` turns the bound `IllegalArgumentException` into a `ConfigurationException`, for startup and REHASH |
| S7 | yes | `ConfigurationReloaderLinkageTest` | `reconcileSection` catches `RuntimeException \| LinkageError`; the other extension is enabled and the new configuration published |
| S8 | yes | `aLegacyProviderThatThrowsOrReturnsNullContributesNothingAndTheMessageStillGoesOut`, `aLegacyContributionThatThrowsIsNullOrOversizedIsDroppedAndReported` | `HookDispatcher.legacyOutboundTags` contains throw/null/oversized like a hook and reports it |
| S9 | yes | `tagDataBytesAtMostAgreesWithTheExactCountAndRejectsOversizeFromRawLength` | `MessageSerializer.tagDataBytesAtMost` rejects on the raw character count (a lower bound of the escaped size) before building the section; used by the dispatcher, `FrameSyntax` and the renderer |
| S10 | yes | `anyErrorFromStartOrStagingStillStopsTheProviderAndRecordsItFailed` | `enable` cleans up in a `finally` guarded by an `enabled` flag (no broad `catch Error`) |
| S11 | yes | `oversizedLegacyTagsAreOmittedAtEnqueueNotAtDrainSoTheMessageIsNotDropped`, updated `CapabilityTagRendererBudgetTest` | legacy contributions are budgeted with the typed ones at enqueue; an overflowing one is dropped and reported, and drain renders the already-validated maps (no second provider call). Two existing unit tests asserted the old drop-the-message behaviour and were rewritten |
| S12 | yes | `aRejectedRequiresProvidersCallStagesNoneOfItsIds` | the whole list is validated against a copy and added only if every id passes |
| S13 | yes | `aFailedStopKeepsTheExtensionPointClaimUntilARetriedDisableSucceeds` | the claim is released only after a clean cleanup; documented on `disable` (retry the `disable` to release) |
| S14 | yes (only for handlers with a value-like `equals`) | `unregisterComparesTheInstalledHandlerByIdentityNotEquality` | removal uses `computeIfPresent` with `==` |
| S15 | yes | `tagKeysMustMatchTheIrcv3KeyGrammar` | `FrameSyntax` accepts only `[+][vendor/]name` with name `[A-Za-z0-9-]+` and a hostname-shaped vendor |

Notes: where a legacy and a typed provider return the same key, the typed value now wins at drain (the order is legacy then typed, the same order the budget is checked in); no shipped provider does this. Legacy providers are visited in the snapshot's provider-id order, not registry order.

## Codex (Sourcery fixes)

| # | Real? | Test | Fix |
| --- | --- | --- | --- |
| C1 | yes: REHASH skipped `disable` for a FAILED provider | `rehashRetriesTheCleanupOfAFailedProviderWhoseDesiredStateIsDisabled` | `reconcileSection` calls the idempotent `disable` whenever the desired state is disabled |
| C2 | yes | `aFailedReEnableAfterAFailedStopFinishesThePendingCleanupAndReleasesTheClaim` (the exact sequence) | `enable` finishes a pending cleanup (`cleanUp`, shared with `disable`) before starting, and a failed enable releases the claim whenever its own cleanup is complete |
| C3 | yes | no deterministic test of the instant between `publish` and the flip (there is no seam inside `publish`); covered by the leftover-handler tests, which now depend on the snapshot | a provider command runs only while the current snapshot contains its provider, so hooks and commands flip in the same atomic step; the separate `live` flag is gone |
| C4 | yes | `aProviderCallbackCannotReEnterTheLifecycle`, `aProviderCallbackThatCallsReloadFailsFastInsteadOfDeadlocking` | `enable`, `disable` and `reload()` throw `IllegalStateException` on the thread holding the lifecycle lock (checked before the reload monitor is taken); documented on `Extension.start/stop` and `CapabilityExtension.register` |
| Sonar | yes | n/a | removed two unused `throws Exception`, a text block for the YAML, and the conditional moved inside `Map.of` |
