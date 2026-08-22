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
package net.jircd.capabilities.echomessage;

import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.CapabilityName;

/**
 * The {@code echo-message} capability (FR-025): unlike {@code message-tags}/{@code server-time},
 * this affects recipient-set construction, not per-recipient formatting — {@link
 * net.jircd.core.session.command.MessageCommandHandler} calls {@link #includeSenderInFanOut} once,
 * when building a fan-out's recipient list, to decide whether the sender's own session is included.
 */
public final class EchoMessageExtension extends AbstractCapabilityExtension {

  public static final String ID = CapabilityName.ECHO_MESSAGE;

  public EchoMessageExtension() {
    super(ID);
  }

  @Override
  public boolean includeSenderInFanOut(ClientSession sender) {
    return sender.negotiatedCapabilities().contains(ID);
  }
}
