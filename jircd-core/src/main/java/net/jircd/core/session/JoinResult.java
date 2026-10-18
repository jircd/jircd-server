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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A completed join. The NAMES tokens and then the recipients are read from the live channel in two
 * separate weakly consistent traversals after the membership monitors are released, so neither is a
 * snapshot of one instant and the two need not agree. The joiner is always among the recipients;
 * with a concurrent departure it may be missing from the listing, which is never patched.
 */
public record JoinResult(
    Channel channel,
    Set<ClientSession> recipients,
    boolean grantedOperator,
    boolean alreadyMember,
    List<String> memberListing) {
  public JoinResult {
    recipients = Collections.unmodifiableSet(new LinkedHashSet<>(recipients));
    memberListing = List.copyOf(memberListing);
  }
}
