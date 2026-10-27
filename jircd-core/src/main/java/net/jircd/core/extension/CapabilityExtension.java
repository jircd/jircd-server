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

import net.jircd.core.capability.Capability;
import net.jircd.core.extension.capability.CapabilityRegistrar;

/**
 * An {@link Extension} that also provides exactly one client-negotiable {@link Capability}
 * (FR-025). Lives under {@code jircd-capabilities/}.
 */
public interface CapabilityExtension extends Extension {

    Capability providedCapability();

    /**
     * Stages this provider's typed hooks. Called once per enable after {@link #start}, and also
     * before any enable to discover the reply decorations the provider could apply, so it must have
     * no side effects and acquire no resources; those belong in {@code start}. Nothing staged is
     * visible until the descriptor is published, and an exception here fails the provider with
     * nothing published.
     *
     * <p>A provider that stages nothing still offers its capability; core simply has no behavior to
     * run for it.
     *
     * <p>Runs while the registry's lifecycle lock is held, so it must not call {@code
     * ExtensionRegistry.enable/disable} or {@code ConfigurationReloader.reload} (they throw {@link
     * IllegalStateException}).
     */
    default void register(CapabilityRegistrar registrar) {}
}
