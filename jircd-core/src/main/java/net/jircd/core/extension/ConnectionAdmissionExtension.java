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
 * Optional capability a {@link ServerExtension} claiming the {@code connection-admission} extension
 * point (FR-066) may implement to decide whether an incoming connection may proceed toward
 * registration — the "G-line"-style network-mask block this release prepares core for but does not
 * itself implement (research.md "Connection-admission extension point"). No extension implements
 * this in this release; the connection- acceptance path consults it anyway, always permitting the
 * connection through when nothing claims the point.
 */
public interface ConnectionAdmissionExtension {

    boolean admit(String remoteAddress);
}
