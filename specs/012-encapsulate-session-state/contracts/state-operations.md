# Contract: Channel and Session State Operations (internal API)

The IRC wire protocol, the externally visible contract, does **not** change. This document
specifies the internal Java surface that handlers in `net.jircd.core.session.command` and the
`jircd-server-extensions/admin` module use after this feature. Signatures are indicative.
Javadoc at the point of definition is authoritative (Constitution I).

## ChannelRegistry (`net.jircd.core.session`)

```java
/** Guard: this monitor, then the target Channel's monitor. See class Javadoc for order. */
Optional<JoinResult> join(String name, ClientSession session, Predicate<Channel> admission);
Optional<Departure> part(Channel channel, ClientSession session);
KickOutcome kick(Channel channel, ClientSession kicker, String targetNickname);
Set<ClientSession> leaveAll(ClientSession session);   // disconnect; self excluded

Optional<Channel> lookup(String name);                 // unchanged
Collection<Channel> all();                             // unchanged (snapshot)
Channel getOrCreate(String name);                      // package-private (tests in package)
void removeIfEmpty(Channel channel);                   // package-private; transitions call it
```

Contract:
- `join` returns empty if the session is `CLOSING`, is already a member (the issue #16 no-op),
  or `admission` refuses. `admission` runs under both monitors and must not block.
- `SAJOIN` passes an always-true admission. The closing and already-member checks still apply.
  Today SAJOIN on an existing membership re-broadcasts JOIN; see the note below.
- `part`/`kick` return the recipients captured **before** removal. Callers deliver after the
  call returns.
- `kick` checks operator status and resolves the target under the lock, so the outcome is
  serial (spec FR-020, confirmed 2026-10-16).

> **SAJOIN note**: `channelRegistry.join(name, session, ignored -> true)` on a channel the admin
> already belongs to currently re-adds (a set no-op) and re-broadcasts JOIN plus NAMES. To keep
> behavior identical, `join` exposes the already-member case through
> `JoinResult.alreadyMember()` instead of returning empty, and only `JoinCommandHandler` treats
> it as a silent no-op (issue #16). SAJOIN keeps broadcasting. Tasks include a characterization
> test that pins today's SAJOIN behavior before the change.

## Channel

```java
Set<ClientSession> members();      // unmodifiable view
Set<ClientSession> operators();    // unmodifiable view
Set<ClientSession> voiced();       // unmodifiable view
Set<ChannelMode> activeModes();    // unmodifiable view
List<BanEntry> bans();             // unmodifiable view
boolean isInvited(String nickname);          // replaces invited().contains(fold(nick))

boolean grantOperator(ClientSession member); // false if not a member
boolean revokeOperator(ClientSession member);
boolean grantVoice(ClientSession member);
boolean revokeVoice(ClientSession member);
void setMode(ChannelMode mode);              // enforces +s/+p exclusion
void clearMode(ChannelMode mode);
void invite(String nickname);                // folds internally
boolean consumeInvitation(String nickname);  // used by JOIN admission
boolean addBan(BanEntry entry);              // unchanged
void removeBan(String mask);                 // unchanged; now synchronized
List<String> memberListing();                // "@nick"/"+nick"/"nick" tokens

boolean addMember(ClientSession s);          // package-private
void removeMember(ClientSession s);          // package-private, synchronized
```

## ClientSession

```java
Set<Channel> channelMemberships();   // unmodifiable view
Set<UserMode> userModes();           // unmodifiable view
void setUserMode(UserMode mode);
void clearUserMode(UserMode mode);
void addMembership(Channel c);       // package-private
void removeMembership(Channel c);    // package-private
```

## Recipients (new, `net.jircd.core.session`)

```java
static Set<ClientSession> sharedChannelNeighbors(
    ClientSession session, boolean includeSelf, Predicate<ClientSession> filter);
static void deliver(Collection<ClientSession> recipients, Message message);
```

- Each session appears at most once. Self comes first when included and is otherwise excluded.
- `deliver` skips sessions whose writer is null and never holds a channel/registry monitor.

## Replies (`net.jircd.core.session.command`)

```java
static void sendListed(ClientSession session, String serverName, NumericReply numeric,
    List<String> fixedParams, List<String> items);
```

- Each emitted line's command section is at most `MessageSerializer.MAX_COMMAND_BYTES`, counted
  in UTF-8 bytes with the trailing colon reserved.
- Items are never split. They are joined by a single space, in the given order.
- At least one line is emitted, possibly with an empty final parameter.

## Result records

```java
record JoinResult(Channel channel, Set<ClientSession> recipients, boolean grantedOperator,
                  boolean alreadyMember, List<String> memberListing) {}
record Departure(Channel channel, ClientSession departed, Set<ClientSession> recipients) {}
sealed interface KickOutcome permits NotOperator, NoSuchMember, Kicked {}
```
