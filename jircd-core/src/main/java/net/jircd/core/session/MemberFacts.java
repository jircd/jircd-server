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

import java.util.List;
import java.util.Objects;

/**
 * What core knows about one channel member, unformatted: the session and every status symbol it
 * holds, highest first in {@code PREFIX} order. Reply rendering turns this into a listing token or
 * a WHO flag for the requester, applying whichever capability providers that requester negotiated.
 */
public record MemberFacts(ClientSession member, List<String> heldStatuses) {
  public MemberFacts {
    Objects.requireNonNull(member);
    heldStatuses = List.copyOf(heldStatuses);
  }
}
