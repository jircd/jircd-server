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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.Test;

class ModeBanCaseMappingTest {

    @Test
    void caseVariantsKeepOriginalBanListSpellingAndExistingModeEchoes() throws Exception {
        ChannelRegistry registry = new ChannelRegistry();
        ClientSession op = new ClientSession("op", "host", RateLimitBucket.withDefaults());
        op.setNickname("op");
        op.setIdent("op");
        Channel channel = registry.join("#c", op, ignored -> true).orElseThrow().channel();
        ModeCommandHandler handler =
                new ModeCommandHandler(
                        registry,
                        new NicknameRegistry(),
                        new ExtensionRegistry(),
                        () -> "server",
                        () -> 20);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (SessionWriter writer = new SessionWriter(op, out, TagRenderer.NONE)) {
            op.attachWriter(writer);
            handler.handle(op, MessageParser.parse("MODE #c +b Nick!User@Host"));
            handler.handle(op, MessageParser.parse("MODE #c +b nICK!uSER@hOST"));
            assertThat(channel.bans())
                    .singleElement()
                    .satisfies(ban -> assertThat(ban.mask()).isEqualTo("Nick!User@Host"));
            handler.handle(op, MessageParser.parse("MODE #c +b"));
            handler.handle(op, MessageParser.parse("MODE #c -b NICK!USER@HOST"));
            assertThat(channel.bans()).isEmpty();
            handler.handle(op, MessageParser.parse("MODE #c -b NICK!USER@HOST"));
        }
        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output)
                .contains(" MODE #c +b Nick!User@Host", " MODE #c +b nICK!uSER@hOST")
                .contains(" 367 op #c Nick!User@Host op ")
                .doesNotContain(" 367 op #c nICK!uSER@hOST", " 478 ");
        assertThat(output.split(" MODE #c -b NICK!USER@HOST", -1)).hasSize(3);
    }
}
