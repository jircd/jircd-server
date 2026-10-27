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
package net.jircd.core.session.command;

import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.capability.CapabilityRegistrar;

/**
 * Stand-in for the multi-prefix module, which core cannot depend on: lists every held status in the
 * status slot of a requester that negotiated it.
 */
final class MultiPrefixStub extends AbstractCapabilityExtension {
    MultiPrefixStub() {
        super("multi-prefix");
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
        registrar.onMemberStatus(limits -> 2, member -> String.join("", member.heldStatuses()));
    }

    static void enableIn(ExtensionRegistry registry) {
        registry.register(new MultiPrefixStub());
        registry.enable("multi-prefix");
    }
}
