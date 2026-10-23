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

/**
 * Base contract shared by every optional, independently enable/disable-able unit of server
 * functionality (FR-011), whether a {@link CapabilityExtension} or a {@link ServerExtension}.
 */
public interface Extension {

  enum State {
    ENABLED,
    DISABLED,
    FAILED
  }

  String id();

  /**
   * Prepares resources. If this throws, {@link #stop()} is still called so whatever was acquired
   * before the failure is released; {@code stop} must therefore tolerate a partial start.
   */
  void start(ServerContext context);

  void stop();
}
