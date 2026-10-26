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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Test setup for a session's negotiated capabilities. The negotiated set is an immutable snapshot
 * replaced as a whole, so fixtures add or clear names through this rather than mutating it.
 */
public final class TestNegotiation {

  private TestNegotiation() {}

  /** Adds {@code names} to what {@code session} has negotiated. */
  public static void add(ClientSession session, String... names) {
    addAll(session, List.of(names));
  }

  /** Adds {@code names} to what {@code session} has negotiated. */
  public static void addAll(ClientSession session, Collection<String> names) {
    Set<String> grown = new HashSet<>(session.negotiatedCapabilities());
    grown.addAll(names);
    session.replaceNegotiatedCapabilities(grown);
  }

  /** Clears everything {@code session} has negotiated. */
  public static void clear(ClientSession session) {
    session.replaceNegotiatedCapabilities(List.of());
  }
}
