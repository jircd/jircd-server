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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registered-user count and its high-water mark, for {@code LUSERS}' {@code RPL_LOCALUSERS} and
 * {@code RPL_GLOBALUSERS}. A session counts only once it is {@code REGISTERED}: a nickname claimed
 * before {@code USER} arrives, or by a connection that then times out, is not a user yet. The peak
 * is raised at each registration so a spike between two {@code LUSERS} queries is not lost.
 */
public final class UserStatistics {

  private final NicknameRegistry nicknameRegistry;
  private final AtomicInteger peak = new AtomicInteger();

  public UserStatistics(NicknameRegistry nicknameRegistry) {
    this.nicknameRegistry = nicknameRegistry;
  }

  /** Currently registered users. */
  public int registeredCount() {
    return (int) nicknameRegistry.all().stream().filter(s -> s.lifecycle().isRegistered()).count();
  }

  /** Raises the high-water mark to the current registered count; call after a session registers. */
  public void recordRegistration() {
    peak.accumulateAndGet(registeredCount(), Math::max);
  }

  /** The most registered users seen at once; never decreases. */
  public int peak() {
    return peak.accumulateAndGet(registeredCount(), Math::max);
  }
}
