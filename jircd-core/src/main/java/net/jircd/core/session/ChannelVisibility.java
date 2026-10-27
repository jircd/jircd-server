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

import net.jircd.core.extension.ExtensionRegistry;

/**
 * The {@code DISCOVER}-gate check point FR-043 requires for {@code TOPIC}-viewing/{@code
 * NAMES}/{@code LIST} (FR-047): a channel with any currently-recognized {@code DISCOVER}-gated flag
 * active (this release: {@code private}/{@code secret}) is invisible to a non-member,
 * non-administrator requester — indistinguishable from a nonexistent channel.
 */
public final class ChannelVisibility {

    private ChannelVisibility() {}

    public static boolean isHiddenFrom(
            Channel channel, ClientSession session, ExtensionRegistry extensionRegistry) {
        if (session.isAdministrator() || channel.members().contains(session)) {
            return false;
        }
        for (ChannelMode mode : extensionRegistry.recognizedChannelModes(CoreChannelModes.ALL)) {
            if (mode.gates().contains(GateAction.DISCOVER)
                    && channel.activeModes().contains(mode)) {
                return true;
            }
        }
        return false;
    }
}
