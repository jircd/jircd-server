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
package net.jircd.server;

import net.jircd.core.session.ConnectionHandler;

/** Test-only bridge to package-private application lifecycle seams. */
public final class JircdServerTestAccess {
  private JircdServerTestAccess() {}

  /** Returns the handler for controlled acceptance and drain lifecycle tests. */
  public static ConnectionHandler connectionHandler(JircdServerApplication application) {
    return application.connectionHandlerForTesting();
  }
}
