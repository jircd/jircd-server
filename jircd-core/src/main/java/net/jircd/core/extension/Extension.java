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
     *
     * <p>Runs while the registry's lifecycle lock is held: do not call {@code
     * ExtensionRegistry.enable/disable} or {@code ConfigurationReloader.reload} from here (directly
     * or by waiting on another thread that does); they fail fast with {@link IllegalStateException}
     * on the calling thread and would deadlock from another.
     */
    void start(ServerContext context);

    /**
     * Releases everything {@link #start} acquired. Must tolerate a partial start and being called
     * again after a failure (a failed disable is retried). The same lifecycle-lock rule as {@link
     * #start} applies.
     *
     * <p>For a {@link CapabilityExtension} this also runs after a hook fault withdrew it as {@code
     * FAILED}, on a thread of the registry's, while callbacks captured earlier may still be
     * running: guard anything they share with {@code stop}.
     */
    void stop();
}
