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
package net.jircd.capabilities.awaynotify;

import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.protocol.CapabilityName;

/**
 * The {@code away-notify} capability (011-away-notify-batch FR-001 through FR-006): unlike {@code
 * message-tags}/{@code server-time}/{@code echo-message}, this doesn't decorate an existing fan-out
 * — it gates a wholly new one. {@link net.jircd.core.session.command.AwayCommandHandler} checks
 * this capability's id directly against each channel-mate's negotiated set when broadcasting an
 * away-status change, via the shared {@link CapabilityName#AWAY_NOTIFY} constant rather than
 * importing this class (jircd-core can't depend on jircd-capabilities).
 */
public final class AwayNotifyExtension extends AbstractCapabilityExtension {

  public static final String ID = CapabilityName.AWAY_NOTIFY;

  public AwayNotifyExtension() {
    super(ID);
  }
}
