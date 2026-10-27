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
package net.jircd.capabilities.userhostinnames;

import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.MemberSnapshot;
import net.jircd.protocol.CapabilityName;

/**
 * The {@code userhost-in-names} capability expands the NAMES identity slot to the member's
 * nickname, ident and already-permitted display host. Core gates the hook by requester negotiation,
 * applies visibility and packs complete tokens; status decoration remains independent.
 *
 * <p>The identity slot reserves {@code I=U+H+2=92} bytes beyond the nickname with the bundled
 * limits ({@code U=27} ident bytes, {@code H=63} display-host bytes).
 */
public final class UserhostInNamesExtension extends AbstractCapabilityExtension {
    /** Provider id and negotiated capability name for expanded NAMES identities. */
    public static final String CAPABILITY_ID = CapabilityName.USERHOST_IN_NAMES;

    public UserhostInNamesExtension() {
        super(CAPABILITY_ID);
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
        // The identity slot bound counts only bytes beyond the nickname: !ident@displayHost.
        registrar.onNamesIdentity(
                limits ->
                        Math.addExact(
                                Math.addExact((long) limits.identMax(), limits.hostMax()), 2L),
                UserhostInNamesExtension::identity);
    }

    private static String identity(MemberSnapshot member) {
        if (member.ident().isEmpty() || member.displayHost().isEmpty()) {
            return member.nickname();
        }
        return member.nickname() + "!" + member.ident() + "@" + member.displayHost();
    }
}
