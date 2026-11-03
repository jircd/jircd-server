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
package net.jircd.server.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.classic.spi.ConfiguratorRank;
import ch.qos.logback.core.spi.ContextAwareBase;

/**
 * Logback's first configurator in this process (registered in {@code META-INF/services}): it sends
 * everything at INFO and above to standard output with jircd's fixed pattern, before the YAML
 * configuration has been read, so even a "configuration invalid" error is visible. {@link
 * LoggingSettingsApplier} then applies the {@code logging} section.
 *
 * <p>When {@code -Dlogback.configurationFile} names an operator's own logback file, this steps
 * aside and logback configures itself from that file instead (spec 016,
 * contracts/logging-config.md).
 */
@ConfiguratorRank(ConfiguratorRank.CUSTOM_LOW_PRIORITY)
public final class BootstrapLogbackConfigurator extends ContextAwareBase implements Configurator {

    @Override
    public ExecutionStatus configure(LoggerContext context) {
        if (System.getProperty("logback.configurationFile") != null) {
            return ExecutionStatus.INVOKE_NEXT_IF_ANY;
        }
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.INFO);
        root.addAppender(LoggingSettingsApplier.consoleAppender(context));
        context.putObject(LoggingSettingsApplier.MANAGED, Boolean.TRUE);
        return ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
    }
}
