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
/**
 * The capability plugin SPI: how a capability provider declares typed hooks ({@link
 * net.jircd.core.extension.capability.CapabilityRegistrar}), how they are published as an immutable
 * {@link net.jircd.core.extension.capability.RuntimeSnapshot}, and how core runs them defensively
 * ({@link net.jircd.core.extension.capability.HookDispatcher}).
 *
 * <p>Every hook result is untrusted and is validated against the bounds declared at registration
 * before it can affect output. A faulty result is dropped, reported by provider id, hook kind and
 * category only, and replaced by the documented default. The extension registry then withdraws the
 * provider as {@code FAILED}, with every provider that requires it, through the same publication a
 * disable uses, and stops them without waiting for callbacks still running against an earlier
 * snapshot; nothing recovers it but an explicit enable. Providers must therefore keep their own
 * resources safe for captured callbacks that overlap {@code stop}. All six bundled capabilities
 * register their behavior here; core keeps only {@code CAP} negotiation and the generic mechanisms
 * the hooks plug into.
 */
package net.jircd.core.extension.capability;
