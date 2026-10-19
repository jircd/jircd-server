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
package net.jircd.core.session;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;
import net.jircd.protocol.Message;

/**
 * Shared-channel recipient selection and delivery.
 *
 * <p>Must never be called while holding a registry or channel monitor.
 */
public final class Recipients {
  private Recipients() {}

  /** Returns shared-channel neighbors, optionally including self and filtering recipients. */
  public static Set<ClientSession> sharedChannelNeighbors(
      ClientSession session, boolean includeSelf, Predicate<ClientSession> filter) {
    Set<ClientSession> recipients = new LinkedHashSet<>();
    if (includeSelf && filter.test(session)) {
      recipients.add(session);
    }
    for (Channel channel : session.channelMemberships()) {
      for (ClientSession member : channel.members()) {
        if (member != session && filter.test(member)) {
          recipients.add(member);
        }
      }
    }
    return recipients;
  }

  /** Delivers to recipients with an attached writer. */
  public static void deliver(Collection<ClientSession> recipients, Message message) {
    if (recipients.isEmpty()) return;
    // Raw recipients share one immutable wire message and therefore one byte charge.
    long bytes = WireBytes.raw(message);
    for (ClientSession recipient : recipients) {
      // Borrowed reference to the recipient's own writer, closed by its connection lifecycle.
      @SuppressWarnings("PMD.CloseResource")
      SessionWriter writer = recipient.writer();
      if (writer != null) {
        writer.enqueueRaw(message, bytes);
      }
    }
  }
}
