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
package net.jircd.core.extension;

import java.util.function.Supplier;
import net.jircd.core.config.ConfigurationReloader;
import net.jircd.core.extension.capability.CapabilityServices;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.DisconnectCleanup;
import net.jircd.core.session.NicknameRegistry;

/**
 * What an {@link Extension} receives at {@code start(ServerContext)} — its handle onto the core
 * registries it may need, without depending on the rest of {@code jircd-core}/{@code
 * jircd-server}'s wiring directly. {@code capabilityServices} is the capability-specific handle.
 * {@code serverName} is a live supplier, not a snapshot, since it (like the rest of {@code
 * configurationReloader}'s live state) can change across a reload.
 */
public record ServerContext(
    NicknameRegistry nicknameRegistry,
    ChannelRegistry channelRegistry,
    ExtensionRegistry extensionRegistry,
    Supplier<String> serverName,
    ConfigurationReloader configurationReloader,
    DisconnectCleanup disconnectCleanup,
    CommandRegistrar commandRegistrar,
    CapabilityServices capabilityServices) {}
