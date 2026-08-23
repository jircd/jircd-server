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
package net.jircd.capabilities.batch;

import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.protocol.CapabilityName;

/**
 * The {@code batch} capability (011-away-notify-batch FR-007 through FR-011): a general-purpose
 * message-grouping mechanism with no hook overrides of its own — {@link
 * net.jircd.core.session.SessionWriter#enqueueBatch} checks this capability's id (and {@code
 * message-tags}) directly against the recipient's negotiated set, via the shared {@link
 * CapabilityName#BATCH}/{@link CapabilityName#MESSAGE_TAGS} constants rather than importing this
 * class (jircd-core can't depend on jircd-capabilities).
 */
public final class BatchExtension extends AbstractCapabilityExtension {

  public static final String ID = CapabilityName.BATCH;

  public BatchExtension() {
    super(ID);
  }
}
