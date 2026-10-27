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

/**
 * A place in a core reply schema that a provider may decorate. Only slots a {@link ReplyLayout}
 * declares can be registered; a provider cannot add parameters or change a numeric's arity.
 */
public record ReplySlot(String name) {
    /** The channel status symbols in a NAMES member token and a channel WHO flags field. */
    public static final ReplySlot STATUS = new ReplySlot("STATUS");

    /** The NAMES member identity token ({@code nick} by default). */
    public static final ReplySlot IDENTITY = new ReplySlot("IDENTITY");

    public ReplySlot {
        Tokens.require(name, "reply slot name");
    }
}
