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

/** Why a hook result was dropped; deliberately carries no exception text or client content. */
public enum FaultCategory {
    EXCEPTION,
    NULL_RESULT,
    INVALID_RESULT,

    /**
     * Valid, individually bounded contributions that together exceed a documented protocol limit.
     * The latecomer is dropped for its default, but no single provider is at fault, so none fails.
     */
    COMPOSITION_LIMIT;

    /** Whether the provider that produced the result is at fault, and is withdrawn as FAILED. */
    public boolean providerFault() {
        return this != COMPOSITION_LIMIT;
    }
}
