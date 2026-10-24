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
package net.jircd.capabilities.multiprefix;

import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.MemberSnapshot;
import net.jircd.protocol.CapabilityName;

/**
 * The {@code multi-prefix} capability: replaces the status slot of {@code NAMES} entries and
 * channel {@code WHO} flags with every status symbol a member holds, in {@code PREFIX} order, for a
 * requester that negotiated it. Core keeps the member facts, the {@code PREFIX} order that defines
 * the statuses and the default of the highest status only; this module only chooses how many to
 * show.
 */
public final class MultiPrefixExtension extends AbstractCapabilityExtension {

  public static final String CAPABILITY_ID = CapabilityName.MULTI_PREFIX;

  /**
   * The most statuses a member can hold, one byte each: the operator and voice symbols of {@code
   * PREFIX=(ov)@+}. Core reserves this width in the reply budget of every entry the slot decorates.
   */
  static final long MAX_STATUS_BYTES = 2;

  public MultiPrefixExtension() {
    super(CAPABILITY_ID);
  }

  @Override
  public void register(CapabilityRegistrar registrar) {
    registrar.onMemberStatus(limits -> MAX_STATUS_BYTES, MultiPrefixExtension::allStatuses);
  }

  /** Every held status, highest first, exactly as core ordered them. */
  static String allStatuses(MemberSnapshot member) {
    return String.join("", member.heldStatuses());
  }
}
