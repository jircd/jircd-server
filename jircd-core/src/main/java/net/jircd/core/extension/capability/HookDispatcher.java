/*
 * Copyright 2026 Guillermo Castro
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package net.jircd.core.extension.capability;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.FrameSequence;
import net.jircd.core.session.OutboundMessage;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageSerializer;

/**
 * Runs registered hooks against a captured {@link RuntimeSnapshot}. Every result is untrusted: an
 * exception, a {@code null}, or a result outside its declared bounds is dropped whole, reported
 * (provider id, hook kind and category only) and replaced by the documented default, so a faulty
 * provider can never damage a core outcome or another provider. Ordinary runtime and linkage faults
 * are contained; {@link VirtualMachineError}s are not. The dispatcher holds no locks and keeps no
 * state, so no hook runs under a domain, admin or queue lock taken here.
 *
 * <p>Withdrawing a faulting provider is a lifecycle concern delivered by a later change; until then
 * each dropped result is reported and the next call is attempted afresh.
 */
public final class HookDispatcher {
  /** Most delivery intents one event hook may return. */
  public static final int MAX_EVENT_INTENTS = 8;

  /** Most opener/closer frames a framing plan may add around the group's members. */
  public static final int MAX_FRAMING_OVERHEAD_FRAMES = 8;

  private static final Set<DeliveryRole> RECIPIENT_HOOK_ROLES =
      EnumSet.of(DeliveryRole.SENDER_COPY);

  private final FaultReporter reporter;

  public HookDispatcher(FaultReporter reporter) {
    this.reporter = Objects.requireNonNull(reporter);
  }

  /** Intents from every hook for the event's kind, narrowed to recipients that negotiated it. */
  public List<EventIntent> event(RuntimeSnapshot snapshot, CommittedEvent event) {
    List<EventIntent> admitted = new ArrayList<>();
    for (HookRegistration registration : snapshot.hooks(HookKind.EVENT)) {
      if (registration.eventKind() != event.kind()) continue;
      Optional<List<EventIntent>> result =
          validated(
              registration,
              () -> registration.hook(Hooks.EventHook.class).apply(event),
              ArrayList::new,
              intents -> validEventIntents(intents, event));
      for (EventIntent intent : result.orElse(List.of())) {
        List<ClientSession> negotiated =
            intent.recipients().stream().filter(r -> gated(registration, r)).toList();
        if (!negotiated.isEmpty()) admitted.add(new EventIntent(intent.message(), negotiated));
      }
    }
    return List.copyOf(admitted);
  }

  private static boolean gated(HookRegistration registration, ClientSession recipient) {
    Set<String> negotiated = recipient.negotiatedCapabilities();
    return registration.ownerNegotiated(negotiated) && registration.prerequisitesMet(negotiated);
  }

  private static boolean validEventIntents(List<EventIntent> intents, CommittedEvent event) {
    if (intents.size() > MAX_EVENT_INTENTS) return false;
    if (intents.isEmpty()) return true;
    Set<ClientSession> audience = Set.copyOf(event.audience().peers());
    for (EventIntent intent : intents) {
      if (intent == null || !audience.containsAll(intent.recipients())) return false;
    }
    return true;
  }

  /**
   * The merged inbound-tag decisions together with which capability selected each forwarded tag, so
   * a tag is later shown only to recipients of the capability that chose to forward it.
   *
   * @param ownersByKey forwarded tag key to the names of every capability whose hook selected it; a
   *     recipient of any one of them may see the tag
   */
  public record InboundSelection(InboundTagResult merged, Map<String, Set<String>> ownersByKey) {
    public InboundSelection {
      Map<String, Set<String>> copy = new LinkedHashMap<>();
      ownersByKey.forEach((key, owners) -> copy.put(key, Set.copyOf(owners)));
      ownersByKey = Map.copyOf(copy);
    }
  }

  /** Merged inbound-tag decisions from hooks whose sender prerequisites are met. */
  public InboundTagResult inboundTags(
      RuntimeSnapshot snapshot,
      Set<String> senderNegotiated,
      InboundTags input,
      RequestContext context) {
    return inboundSelection(snapshot, senderNegotiated, input, context).merged();
  }

  /** As {@link #inboundTags}, also recording the capability that selected each forwarded tag. */
  public InboundSelection inboundSelection(
      RuntimeSnapshot snapshot,
      Set<String> senderNegotiated,
      InboundTags input,
      RequestContext context) {
    Map<String, Set<String>> owners = new LinkedHashMap<>();
    Map<String, String> forwarded = new LinkedHashMap<>();
    Map<String, String> attributes = new LinkedHashMap<>();
    for (HookRegistration registration : snapshot.hooks(HookKind.INBOUND_TAGS)) {
      if (!registration.prerequisitesMet(senderNegotiated)) continue;
      Optional<InboundTagResult> result =
          validated(
              registration,
              () -> registration.hook(Hooks.InboundTagHook.class).apply(input, context),
              r -> validInbound(r, registration, input));
      result.ifPresent(
          r -> {
            forwarded.putAll(r.forwarded());
            r.forwarded()
                .keySet()
                .forEach(
                    key ->
                        owners
                            .computeIfAbsent(key, ignored -> new java.util.LinkedHashSet<>())
                            .add(registration.capabilityName()));
            attributes.putAll(r.context());
          });
    }
    InboundTagResult merged =
        forwarded.isEmpty() && attributes.isEmpty()
            ? InboundTagResult.NONE
            : new InboundTagResult(forwarded, attributes);
    return new InboundSelection(merged, owners);
  }

  private static boolean validInbound(
      InboundTagResult result, HookRegistration registration, InboundTags input) {
    for (var entry : result.forwarded().entrySet()) {
      if (!entry.getKey().startsWith("+")
          || !entry.getValue().equals(input.tags().get(entry.getKey()))) {
        return false;
      }
    }
    String namespace = registration.providerId() + "/";
    for (String key : result.context().keySet()) {
      if (!key.startsWith(namespace)) return false;
    }
    return RequestContext.validAttributes(result.context());
  }

  /** Additional delivery roles from hooks whose owner the sender negotiated. */
  public Set<DeliveryRole> recipientRoles(
      RuntimeSnapshot snapshot,
      Set<String> senderNegotiated,
      DeliveryIntent intent,
      RequestContext context) {
    Set<DeliveryRole> roles = EnumSet.noneOf(DeliveryRole.class);
    for (HookRegistration registration : snapshot.hooksFor(HookKind.RECIPIENTS, senderNegotiated)) {
      validated(
              registration,
              () -> registration.hook(Hooks.RecipientHook.class).apply(intent, context),
              HashSet::new,
              RECIPIENT_HOOK_ROLES::containsAll)
          .ifPresent(roles::addAll);
    }
    return Set.copyOf(roles);
  }

  /**
   * One tag hook's contribution, measured with the serializer's escaping against its declared bound
   * and limited to its owned keys. Empty on any fault. Callers memoize per envelope and hook.
   */
  public Map<String, String> outboundTags(HookRegistration registration, OutboundMessage message) {
    return validated(
            registration,
            () -> registration.hook(Hooks.OutboundTagHook.class).apply(message),
            LinkedHashMap::new,
            tags -> validTags(tags, registration))
        .map(Map::copyOf)
        .orElse(Map.of());
  }

  private static boolean validTags(Map<String, String> tags, HookRegistration registration) {
    for (var entry : tags.entrySet()) {
      String value = entry.getValue();
      if (!registration.ownedKeys().contains(entry.getKey())
          || value == null
          || value.indexOf('\0') >= 0) {
        return false;
      }
    }
    return MessageSerializer.tagDataBytesAtMost(tags, registration.maxBytes());
  }

  /** The requester's status string: the owner's replacement, else core's highest status. */
  public String memberStatus(
      RuntimeSnapshot snapshot, Set<String> requesterNegotiated, MemberSnapshot member) {
    for (HookRegistration registration :
        snapshot.hooksFor(HookKind.MEMBER_STATUS, requesterNegotiated)) {
      long bound = slotBytes(snapshot, registration);
      Optional<String> status =
          bound < 0
              ? Optional.empty()
              : validated(
                  registration,
                  () -> registration.hook(Hooks.MemberStatusHook.class).apply(member),
                  s -> validStatus(s, member, bound));
      if (status.isPresent()) return status.get();
    }
    return member.highestStatus();
  }

  /**
   * The slot bound evaluated against the snapshot's configured limits, the same values startup and
   * enable reserved. An invalid declaration is a provider fault: -1 and the hook is skipped.
   */
  private long slotBytes(RuntimeSnapshot snapshot, HookRegistration registration) {
    try {
      long bytes = registration.replyBound().maxBytes(snapshot.limits());
      if (bytes >= 0 && bytes <= MessageSerializer.MAX_COMMAND_BYTES) return bytes;
    } catch (RuntimeException | LinkageError _) {
      // Reported below as an invalid result.
    }
    fault(registration, FaultCategory.INVALID_RESULT);
    return -1;
  }

  private static boolean validStatus(String status, MemberSnapshot member, long bound) {
    if (MessageSerializer.utf8Bytes(status) > bound) return false;
    int next = 0;
    for (int codePoint : status.codePoints().toArray()) {
      String symbol = new String(Character.toChars(codePoint));
      while (next < member.heldStatuses().size()
          && !member.heldStatuses().get(next).equals(symbol)) {
        next++;
      }
      if (next == member.heldStatuses().size()) return false;
      next++;
    }
    return true;
  }

  /** The NAMES identity token: the owner's replacement, else the nickname. */
  public String namesIdentity(
      RuntimeSnapshot snapshot, Set<String> requesterNegotiated, MemberSnapshot member) {
    for (HookRegistration registration :
        snapshot.hooksFor(HookKind.NAMES_IDENTITY, requesterNegotiated)) {
      long bound = slotBytes(snapshot, registration);
      Optional<String> identity =
          bound < 0
              ? Optional.empty()
              : validated(
                  registration,
                  () -> registration.hook(Hooks.NamesIdentityHook.class).apply(member),
                  token -> validIdentity(token, member, bound));
      if (identity.isPresent()) return identity.get();
    }
    return member.nickname();
  }

  private static boolean validIdentity(String token, MemberSnapshot member, long bound) {
    if (token.isEmpty()) return false;
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      if (c <= ' ' || c == 0x7f) return false;
    }
    return MessageSerializer.utf8Bytes(token)
        <= MessageSerializer.utf8Bytes(member.nickname()) + bound;
  }

  /** A schema-declared slot's bounded text, or empty for the schema default. */
  public Optional<String> replyField(
      RuntimeSnapshot snapshot,
      Set<String> requesterNegotiated,
      ReplySlot slot,
      MemberSnapshot member) {
    for (HookRegistration registration :
        snapshot.hooksFor(HookKind.REPLY_FIELD, requesterNegotiated)) {
      long bound = slot.equals(registration.slot()) ? slotBytes(snapshot, registration) : -1;
      Optional<String> field =
          bound < 0
              ? Optional.empty()
              : validated(
                  registration,
                  () -> registration.hook(Hooks.ReplyFieldHook.class).apply(member),
                  text -> validField(text, bound));
      if (field.isPresent()) return field;
    }
    return Optional.empty();
  }

  private static boolean validField(String text, long bound) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\r' || c == '\n' || c == 0) return false;
    }
    return MessageSerializer.utf8Bytes(text) <= bound;
  }

  /** The group's framing for this recipient: the owner's valid plan, else plain members. */
  public FramingPlan outputGroup(
      RuntimeSnapshot snapshot,
      Set<String> recipientNegotiated,
      OutputGroup group,
      RequestContext context) {
    return prepareOutputGroup(snapshot, recipientNegotiated, group, context).framed();
  }

  /**
   * As {@link #outputGroup}, keeping the plain alternative and the registration whose plan was
   * chosen, so admission can still choose plain if that owner is no longer effective then.
   */
  public PreparedGroup prepareOutputGroup(
      RuntimeSnapshot snapshot,
      Set<String> recipientNegotiated,
      OutputGroup group,
      RequestContext context) {
    for (HookRegistration registration :
        snapshot.hooksFor(HookKind.OUTPUT_GROUP, recipientNegotiated)) {
      Optional<FramingPlan> plan =
          validated(
              registration,
              () -> registration.hook(Hooks.OutputGroupHook.class).apply(group, context),
              p -> validPlan(p, group));
      if (plan.isPresent()) {
        return new PreparedGroup(plan.get(), FramingPlan.plain(group), registration);
      }
    }
    return PreparedGroup.plain(group);
  }

  /**
   * A plan is valid when every member appears exactly once and in order (a frame may add tags to
   * its member), the other frames are openers before the first member and the same number of
   * closers after the last, each closer pairs with its opener from the inside out (same command,
   * opener parameter {@code +ref}, closer {@code -ref}), and the exact bytes fit the group's bound.
   */
  private static boolean validPlan(FramingPlan plan, OutputGroup group) {
    List<Message> members = group.members();
    List<Message> frames = plan.frames();
    if (frames.isEmpty() || frames.size() > members.size() + MAX_FRAMING_OVERHEAD_FRAMES) {
      return false;
    }
    List<Message> openers = new ArrayList<>();
    List<Message> closers = new ArrayList<>();
    if (!split(frames, members, openers, closers)) return false;
    if (openers.size() != closers.size()) return false;
    for (int i = 0; i < openers.size(); i++) {
      if (!paired(openers.get(i), closers.get(closers.size() - 1 - i))) return false;
    }
    return FrameSequence.of(frames).bytes() <= group.byteBound();
  }

  /** Splits {@code frames} into openers, the members in order, then closers; false if it cannot. */
  private static boolean split(
      List<Message> frames, List<Message> members, List<Message> openers, List<Message> closers) {
    int matched = 0;
    for (Message frame : frames) {
      if (matched < members.size() && isMember(frame, members.get(matched))) {
        matched++;
      } else if (matched == 0 && !members.isEmpty()) {
        openers.add(frame);
      } else if (matched == members.size() && !members.isEmpty()) {
        closers.add(frame);
      } else if (members.isEmpty()) {
        openers.add(frame);
      } else {
        return false; // framing between members
      }
    }
    if (members.isEmpty()) {
      int half = openers.size() / 2;
      closers.addAll(openers.subList(half, openers.size()));
      openers.subList(half, openers.size()).clear();
    }
    return matched == members.size();
  }

  private static boolean paired(Message opener, Message closer) {
    if (!Objects.equals(opener.rawCommand(), closer.rawCommand())) return false;
    if (opener.params().isEmpty() || closer.params().isEmpty()) return false;
    String open = opener.params().getFirst();
    String close = closer.params().getFirst();
    return open.length() > 1
        && open.startsWith("+")
        && close.startsWith("-")
        && open.substring(1).equals(close.substring(1));
  }

  private static boolean isMember(Message frame, Message member) {
    return Objects.equals(frame.rawCommand(), member.rawCommand())
        && Objects.equals(frame.prefix(), member.prefix())
        && frame.params().equals(member.params())
        && frame.tags().entrySet().containsAll(member.tags().entrySet());
  }

  /** Reports a tag hook whose contribution was dropped because the aggregate would overflow. */
  public void reportAggregateOverflow(HookRegistration registration) {
    fault(registration, FaultCategory.INVALID_RESULT);
  }

  /** Wraps a provider command handler so its fault is contained and reported. */
  public CommandHandler guardedCommand(HookRegistration registration) {
    CommandHandler handler = registration.commandHandler();
    return (session, message) -> {
      try {
        handler.handle(session, message);
      } catch (RuntimeException | LinkageError _) {
        fault(registration, FaultCategory.EXCEPTION);
      }
    };
  }

  /** For results that are already immutable values. */
  private <T> Optional<T> validated(
      HookRegistration registration, Supplier<T> call, Predicate<T> valid) {
    return validated(registration, call, UnaryOperator.identity(), valid);
  }

  /**
   * Runs the hook, snapshots its result with {@code copier} and validates and returns only that
   * copy, so a container the provider keeps changing cannot differ from what was checked. A failure
   * while copying is a hook fault like any other.
   */
  private <T> Optional<T> validated(
      HookRegistration registration,
      Supplier<T> call,
      UnaryOperator<T> copier,
      Predicate<T> valid) {
    T result;
    try {
      T raw = call.get();
      result = raw == null ? null : copier.apply(raw);
    } catch (RuntimeException | LinkageError _) {
      fault(registration, FaultCategory.EXCEPTION);
      return Optional.empty();
    }
    if (result == null) {
      fault(registration, FaultCategory.NULL_RESULT);
      return Optional.empty();
    }
    boolean ok;
    try {
      ok = valid.test(result);
    } catch (RuntimeException | LinkageError _) {
      ok = false;
    }
    if (!ok) {
      fault(registration, FaultCategory.INVALID_RESULT);
      return Optional.empty();
    }
    return Optional.of(result);
  }

  private void fault(HookRegistration registration, FaultCategory category) {
    reporter.report(registration.providerId(), registration.kind(), category);
  }
}
