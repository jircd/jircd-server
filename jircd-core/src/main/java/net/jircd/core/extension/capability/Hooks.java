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
import java.util.Map;
import java.util.Set;
import net.jircd.core.session.OutboundMessage;

/** The functional shapes of the typed hooks {@link CapabilityRegistrar} accepts. */
public interface Hooks {

    /** Reacts to a committed event with delivery intents for permitted peers. */
    @FunctionalInterface
    interface EventHook {
        List<EventIntent> apply(CommittedEvent event);
    }

    /** Selects forwarded client tags and provider-scoped request context. */
    @FunctionalInterface
    interface InboundTagHook {
        InboundTagResult apply(InboundTags tags, RequestContext context);
    }

    /** Adds delivery roles (sender copy) for an authorized delivery. */
    @FunctionalInterface
    interface RecipientHook {
        Set<DeliveryRole> apply(DeliveryIntent intent, RequestContext context);
    }

    /** Contributes owned server tags; pure in the immutable message. */
    @FunctionalInterface
    interface OutboundTagHook {
        Map<String, String> apply(OutboundMessage message);
    }

    /** Replaces a member's status string with held symbols in PREFIX order. */
    @FunctionalInterface
    interface MemberStatusHook {
        String apply(MemberSnapshot member);
    }

    /** Replaces a NAMES member's identity token. */
    @FunctionalInterface
    interface NamesIdentityHook {
        String apply(MemberSnapshot member);
    }

    /** Contributes bounded text to a schema-declared reply slot. */
    @FunctionalInterface
    interface ReplyFieldHook {
        String apply(MemberSnapshot member);
    }

    /** Frames a finite output group. */
    @FunctionalInterface
    interface OutputGroupHook {
        FramingPlan apply(OutputGroup group, RequestContext context);
    }
}
