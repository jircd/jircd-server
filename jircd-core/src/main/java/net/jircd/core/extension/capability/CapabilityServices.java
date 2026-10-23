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

import java.util.Objects;
import java.util.function.Supplier;

/**
 * What a capability provider receives through {@code ServerContext}: read access to the current
 * published hook table. A provider cannot publish, withdraw or negotiate on anyone's behalf through
 * it; commands and hooks are registered at staging only.
 */
public record CapabilityServices(Supplier<RuntimeSnapshot> source) {

  public CapabilityServices {
    Objects.requireNonNull(source);
  }

  /** The currently published providers and their hooks. */
  public RuntimeSnapshot snapshot() {
    return source.get();
  }
}
