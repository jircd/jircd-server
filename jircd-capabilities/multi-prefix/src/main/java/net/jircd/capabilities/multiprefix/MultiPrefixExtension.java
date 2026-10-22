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
import net.jircd.protocol.CapabilityName;

/**
 * The {@code multi-prefix} capability: like {@code away-notify}, it gates behaviour that lives in
 * core. {@code Channel.memberListing}/{@code statusPrefixes}, reached from {@code JOIN}, {@code
 * NAMES} and {@code WHO}, list every status prefix a member holds when the requester's negotiated
 * set contains {@link CapabilityName#MULTI_PREFIX}. The prefix logic itself stays in core beside
 * {@code ISUPPORT PREFIX}, which defines the order; this module only registers the capability so it
 * is advertised, ACKed and configurable.
 */
public final class MultiPrefixExtension extends AbstractCapabilityExtension {

  public static final String CAPABILITY_ID = CapabilityName.MULTI_PREFIX;

  public MultiPrefixExtension() {
    super(CAPABILITY_ID);
  }
}
