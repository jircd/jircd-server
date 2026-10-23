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

/**
 * The {@code id}/{@code providedCapability} boilerplate every {@link CapabilityExtension} shares,
 * with documented no-op lifecycle defaults: {@link #start} and {@link #stop} do nothing, because a
 * provider that holds no resources has nothing to prepare or release. A migrated provider overrides
 * {@link #register} to stage its typed hooks; a provider still on the temporary bridge overrides
 * {@link #contributeTags}/{@link #includeSenderInFanOut} instead.
 */
public abstract class AbstractCapabilityExtension implements CapabilityExtension {

  private final String id;
  private final Capability capability;

  protected AbstractCapabilityExtension(String id) {
    this.id = id;
    this.capability = new Capability(id);
  }

  @Override
  public final String id() {
    return id;
  }

  @Override
  public final Capability providedCapability() {
    return capability;
  }

  /** No-op: nothing to prepare. Acquire resources here, never in {@link #register}. */
  @Override
  public void start(ServerContext context) {}

  /** No-op: nothing to release. Runs after the provider's hooks are withdrawn. */
  @Override
  public void stop() {}
}
