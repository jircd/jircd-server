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
package net.jircd.capabilities.batch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.FramingPlan;
import net.jircd.core.extension.capability.OutputGroup;
import net.jircd.core.extension.capability.RequestContext;
import net.jircd.protocol.CapabilityName;
import net.jircd.protocol.Message;

/**
 * The {@code batch} capability (011-away-notify-batch FR-007 through FR-011): a general-purpose
 * message-grouping mechanism. It owns the group shaper: for a recipient that negotiated both {@code
 * batch} and {@code message-tags} it opens the group with {@code BATCH +ref type params}, merges a
 * {@code batch=ref} tag onto every member and closes it with {@code BATCH -ref}. For any other
 * recipient core sends the members plain, which is exactly the shaper not being asked: {@code
 * message-tags} is a session prerequisite of this registration, not a hard dependency, so batch
 * stays offered and negotiable without it but inert.
 *
 * <p>Core validates the plan, reserves all of its frames at once and queues them as one entry, so
 * the closer is admitted with the opener. The open and close {@code BATCH} lines carry no prefix,
 * the same precedent {@code KillCommandHandler}'s {@code ERROR} line establishes for a
 * server-framing line with no natural sender identity.
 */
public final class BatchExtension extends AbstractCapabilityExtension {

    public static final String ID = CapabilityName.BATCH;

    private static final String BATCH = "BATCH";
    private static final String BATCH_TAG_KEY = "batch";

    public BatchExtension() {
        super(ID);
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
        registrar.onOutputGroup(Set.of(CapabilityName.MESSAGE_TAGS), BatchExtension::frame);
    }

    /**
     * {@code ref} is a fresh {@link UUID} per recipient and group — a unique ASCII token, collision
     * safe without any shared state (research.md Decision 3). Members keep their own tags and gain
     * the {@code batch} tag unless they already carry one, which marks them as members of a nested
     * group the caller built.
     */
    static FramingPlan frame(OutputGroup group, RequestContext context) {
        String ref = UUID.randomUUID().toString();
        List<String> openParams = new ArrayList<>();
        openParams.add("+" + ref);
        openParams.add(group.type());
        openParams.addAll(group.typeParams());
        List<Message> frames = new ArrayList<>(group.members().size() + 2);
        frames.add(new Message(Map.of(), null, null, BATCH, List.copyOf(openParams)));
        for (Message member : group.members()) {
            Map<String, String> tags = new LinkedHashMap<>(member.tags());
            // A member that already belongs to a nested group the caller built keeps that group.
            tags.putIfAbsent(BATCH_TAG_KEY, ref);
            frames.add(
                    new Message(
                            tags,
                            member.prefix(),
                            member.command(),
                            member.rawCommand(),
                            member.params()));
        }
        frames.add(new Message(Map.of(), null, null, BATCH, List.of("-" + ref)));
        return new FramingPlan(frames);
    }
}
