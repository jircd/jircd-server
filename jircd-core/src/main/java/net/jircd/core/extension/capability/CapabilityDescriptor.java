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

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a capability provider declared at staging: its id, the capability it offers, the providers
 * it hard-depends on, and its ordered hooks. Immutable; obtained from {@link
 * CapabilityRegistrar#build()}.
 */
public record CapabilityDescriptor(
    String providerId,
    String capabilityName,
    Set<String> requiresProviders,
    List<HookRegistration> registrations) {

  public CapabilityDescriptor {
    Tokens.require(providerId, "provider id");
    Tokens.require(capabilityName, "capability name");
    requiresProviders = Set.copyOf(Objects.requireNonNull(requiresProviders));
    registrations = List.copyOf(Objects.requireNonNull(registrations));
  }
}
