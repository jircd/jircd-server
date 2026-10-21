# Quickstart: Validating Core Protocol Exclusion Completeness

Validation guide for this feature's 15 requirements. Same prerequisites as
`specs/001-ircv3-server/quickstart.md` — JDK 25, a raw TCP client, a running
`./gradlew :jircd-server:run` instance.

## Story 1 — Channel capacity and key access modes (FR-001 through FR-006)

1. `chanop` joins `#chan` (becomes operator via first-join), sends `MODE #chan +l 2`.
   - **Expected**: `MODE #chan +l 2` echoed to channel members.
2. A second client joins `#chan`.
   - **Expected**: join succeeds (membership now at the limit).
3. A third client attempts to join `#chan`.
   - **Expected**: `471 ERR_CHANNELISFULL`, no `JOIN` delivered.
4. `chanop` sends `MODE #chan -l`.
   - **Expected**: `MODE #chan -l` echoed with no parameter.
5. The third client joins `#chan` again.
   - **Expected**: join succeeds.
6. `chanop` sends `MODE #chan +k secret`.
   - **Expected**: `MODE #chan +k secret` echoed.
7. A client sends `JOIN #chan` with no key, then `JOIN #chan wrongkey`.
   - **Expected**: both attempts get `475 ERR_BADCHANNELKEY`, no `JOIN` delivered either time.
8. A client sends `JOIN #chan secret`.
   - **Expected**: join succeeds.
9. `chanop` sets `#chan` to both `+l 1` (already at 1 member) and `+k secret`; `chanop` sends
   `INVITE otheruser #chan`; `otheruser` sends `JOIN #chan secret2` (wrong key).
   - **Expected**: `otheruser`'s join still succeeds — the pending invitation exempts them from
     both the limit and the key check at once, not just whichever is checked first.

## Story 2 — Topic-lock privilege (FR-007 through FR-009)

1. `foo` and `bar` both join `#chan` (`foo` is operator via first-join).
2. `foo` sends `MODE #chan +t`.
   - **Expected**: echoed, no error.
3. `bar` (non-operator) sends `TOPIC #chan :new topic`.
   - **Expected**: `482 ERR_CHANOPRIVSNEEDED`, no `TOPIC` broadcast.
4. `foo` sends `MODE #chan -t`.
   - **Expected**: echoed, no error.
5. `bar` sends `TOPIC #chan :new topic` again.
   - **Expected**: succeeds — `TOPIC` broadcast to the channel.
6. With `+t` back on, `foo` (operator) sends `TOPIC #chan :ops always can`.
   - **Expected**: succeeds regardless of the lock.

## Story 3 — Bare membership query (FR-010)

1. `alice` joins `#pub` (no `+p`/`+s`); a second client joins `#priv` and sets `+s` on it, without
   `alice` joining `#priv`.
2. `alice` sends bare `NAMES` (no channel argument).
   - **Expected**: the response includes `#pub`'s membership (`353 RPL_NAMREPLY`), does NOT
     include `#priv` (secret, `alice` not a member), and ends with exactly one `366
     RPL_ENDOFNAMES` targeted at `*`.

## Story 4 — Server statistics completeness (FR-011, FR-012)

1. A client with no operator connected sends `LUSERS`.
   - **Expected**: reply includes `252 RPL_LUSEROP` reporting `0`, and ends with `255
     RPL_LUSERME`.
2. A client successfully `OPER`s, then another client sends `LUSERS`.
   - **Expected**: `252 RPL_LUSEROP` now reports `1`; `255 RPL_LUSERME` still present.

## Story 5 — Invitation to a not-yet-existing channel (FR-013)

1. With no channel named `#brandnew` existing anywhere on the server, `alice` sends `INVITE bob
   #brandnew`.
   - **Expected**: `alice` receives `341 RPL_INVITING`; `bob` receives an `INVITE #brandnew bob`
     notification.
2. `alice` (who is NOT a member of an existing channel `#other`) sends `INVITE bob #other`.
   - **Expected**: `442 ERR_NOTONCHANNEL` — this case is unchanged.

## Story 6 — Former-nickname lookup count (FR-014, FR-015)

1. A nickname `erin` disconnects and reconnects under the same nickname three separate times
   (three retained `WhowasEntry` records).
2. A client sends `WHOWAS erin 2`.
   - **Expected**: two `314 RPL_WHOWASUSER` lines, most recent first, then `369
     RPL_ENDOFWHOWAS`.
3. A client sends `WHOWAS erin 0` (or a negative count).
   - **Expected**: all three retained `314 RPL_WHOWASUSER` lines, then `369 RPL_ENDOFWHOWAS`.
4. A client sends `WHOWAS erin` (no count).
   - **Expected**: exactly one `314 RPL_WHOWASUSER` line (the most recent), then `369
     RPL_ENDOFWHOWAS` — unchanged from today.

## Automated cross-check

Run the irctest suite (`github.com/jircd/irctest`'s `irctest.controllers.jircd` controller,
`--timeout=60 --timeout-method=signal`) and confirm these tests now pass, with no regression in
any previously-passing test: `chmodes/limit.py` (`testLimitMode`/`testLimitRemoval`/
`testLimitChange`/`testLimitDecrease`/`testLimitAfterPart`/`testLimitMultipleChannels`/
`testLimitWithInvite`/`testLimitInvalidValues`), `chmodes/key.py`
(`testKeyNormal`/`testKeyValidation[spaces,long,empty,only-space]`), `topic.py::testTopicMode`,
`names.py` (`testNames2812`/`testNamesModern`/`testNames2812Secret`/
`testNamesMultipleChannels2812`/`testNamesInvalidChannel`/`testNamesNonexistingChannel`/
`testNamesNoArgumentPublic2812`/`testNamesNoArgumentPrivate2812`), `lusers.py`
(`BasicLusersTestCase::testLusers`/`LuserOpersTestCase::testLuserOpers`, allowing for the known-out-of-scope
`265`/`266` gap noted below), `invite.py::testInvite`, `whowas.py`
(`testWhowasMultiple`/`testWhowasCount1`/`testWhowasCount2`/`testWhowasCountNegative`/
`testWhowasCountZero`).

Verified during the T026 re-run (2026-08-21): all of the above pass. Two real bugs were found and
fixed along the way, beyond the FRs' original scope of "add the mode/numeric/parameter" —
both squarely within FR-004's/FR-014's own behavior, not new scope: (1) `+k` accepted an empty or
space-containing key literally, making the channel permanently unjoinable — now silently ignored
per `testKeyValidation`'s own accepted outcomes; (2) an omitted `WHOWAS` count was assumed to mean
"return one entry" (preserving pre-006 behavior) but `testWhowasMultiple` (non-deprecated) proved
it must mean "full search" instead — FR-015 was corrected accordingly. A pre-existing, adjacent
bug in the single-channel `NAMES` form (sent `403 ERR_NOSUCHCHANNEL` for an invalid/nonexistent
channel, when RFC1459/RFC2812 both say there's no error reply for this) was also fixed while
already in `NamesCommandHandler`, plus `ChannelRegistry` was changed to iterate channels in
deterministic creation order (needed for a repeatable bare-`NAMES` response shape).

**Topic metadata implemented (#75)**: `333 RPL_TOPICWHOTIME` now follows `332 RPL_TOPIC`
on JOIN and TOPIC queries, with the stored presented setter and Unix timestamp. The missing-333
exclusion for `topic.py::TopicPrivilegesTestCase::testTopicPrivileges` no longer applies; this
follow-up was verified with unit and real-socket integration tests, without rerunning irctest.

**Confirmed out of scope, not expected to pass** (each traced to a cause outside this feature's
FRs, not a regression): `names.py::testNames1459`/
`testNamesNoArgumentPublic1459`/`testNamesNoArgumentPrivate1459` (all `deprecated=True` — the
obsolete RFC1459-only no-`=`-symbol reply shape, superseded by the `2812`/`Modern` variants above,
which pass); `lusers.py`'s `testLusersFull`-suffixed tests and any assertion requiring `265
RPL_LOCALUSERS`/`266 RPL_GLOBALUSERS` (Modern-only numerics, explicitly not RFC 2812, never named
by FR-011/FR-012 — a separate, pre-existing gap surfaced by this being the first time `lusers.py`
was run against jircd, not a regression); `invite.py::testInviteNonExistingChannelTransmitted`/
`testInviteNonExistingChannelEchoed` (both `deprecated=True` — and, on inspection, both send
`INVITE #chan bar` with what appears to be reversed argument order vs. every other test in the
same file's own `INVITE <nickname> <channel>` convention; FR-013's actual, correct behavior is
independently verified by `InviteNotYetExistingChannelTest.java` and by `testInvite`'s own passing
non-deprecated coverage); `invite.py::testInviteExemptsFromBan` (`@mark_specifications("Ergo")` —
the already-documented, deliberately-excluded Ergo-specific "INVITE exempts from ban" behavior);
`whowas.py::testWhowasWildcard`/`testWhowasNoParamRfc` (both `deprecated=True` — glob-style
nickname matching and an obsolete bare-`WHOWAS` reply shape, neither ever implemented or in
scope).
