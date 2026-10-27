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
 * A group's two complete, validated alternatives for one recipient, prepared outside every core
 * lock from a captured view: the framing a provider chose ({@code owner}'s) and the plain members.
 * At admission core picks one with a current, generic check of {@code owner}'s effectiveness for
 * the recipient and appends it whole; no provider is called then. With no applicable framing,
 * {@code owner} is {@code null} and both alternatives are the plain members.
 */
public record PreparedGroup(FramingPlan framed, FramingPlan plain, HookRegistration owner) {

    /** The members with no framing, whatever the current state. */
    public static PreparedGroup plain(OutputGroup group) {
        FramingPlan plain = FramingPlan.plain(group);
        return new PreparedGroup(plain, plain, null);
    }
}
