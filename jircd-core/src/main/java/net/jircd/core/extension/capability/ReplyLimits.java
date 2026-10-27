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
package net.jircd.core.extension.capability;

import net.jircd.core.config.ServerConfiguration;

/**
 * The validated configured limits a reply bound is evaluated from: server name, nickname and
 * channel-name byte limits plus the fixed ident and presented-host maxima.
 */
public record ReplyLimits(
        int serverBytes, int nicknameMax, int channelMax, int identMax, int hostMax) {

    /** Representative limits used only to sanity-check a declared bound at registration. */
    public static final ReplyLimits REFERENCE = of(253, 30, 50);

    public ReplyLimits {
        if (serverBytes < 0 || nicknameMax < 0 || channelMax < 0 || identMax < 0 || hostMax < 0) {
            throw new IllegalArgumentException("reply limits must not be negative");
        }
    }

    public static ReplyLimits of(int serverBytes, int nicknameMax, int channelMax) {
        return new ReplyLimits(
                serverBytes,
                nicknameMax,
                channelMax,
                ServerConfiguration.IDENT_MAX_BYTES,
                ServerConfiguration.PRESENTED_HOST_MAX_BYTES);
    }
}
