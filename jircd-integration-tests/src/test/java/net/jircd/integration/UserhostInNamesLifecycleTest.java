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
package net.jircd.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import net.jircd.core.config.ConfigurationException;
import net.jircd.core.config.ConfigurationLoader;
import net.jircd.core.extension.Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Existing generic config/runtime controls applied to the discovered identity provider. */
class UserhostInNamesLifecycleTest {
    private static final String CAP = UserhostInNamesTest.CAPABILITY;
    private static final String CAP_LIST = "CAP LIST";
    private static final String ENABLED = UserhostInNamesTest.CAPS;
    private static final String DISABLED =
            "capabilities:\n  multi-prefix: enabled\n  userhost-in-names: disabled\n";

    @ParameterizedTest
    @ValueSource(strings = {"", "  userhost-in-names: disabled\n"})
    void omittedOrDisabledProviderIsNotOfferedAndMixedReqIsAtomic(String yaml) throws Exception {
        try (TestServer server =
                        TestServer.start("capabilities:\n  multi-prefix: enabled\n" + yaml);
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            // An explicit disabled state is applied through the same startup path as omission.
            if (!yaml.isEmpty()) {
                assertThat(server.application.extensionRegistry().stateOf(CAP))
                        .isEqualTo(Extension.State.DISABLED);
            }
            client.send("CAP LS 302");
            String offer = client.readUntil("CAP * LS", UserhostInNamesTest.WAIT);
            assertThat(offer).contains("multi-prefix").doesNotContain(CAP);
            client.send("CAP REQ :multi-prefix userhost-in-names");
            assertThat(client.readUntil("CAP * NAK", UserhostInNamesTest.WAIT))
                    .endsWith(":multi-prefix userhost-in-names");
            assertThat(
                            UserhostInNamesTest.replies(client, CAP_LIST).stream()
                                    .filter(m -> "CAP".equals(m.rawCommand())))
                    .singleElement()
                    .satisfies(m -> assertThat(m.params().getLast()).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "capabilities:\n  userhost-in-names: maybe\n",
                "server-extensions:\n  userhost-in-names: enabled\n"
            })
    void invalidStateAndWrongSectionAreRejected(String yaml) {
        ConfigurationLoader loader = new ConfigurationLoader(Set.of(CAP), Set.of());
        var input = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> loader.load(input))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining(CAP);
    }

    private static List<String> notifications(RawIrcClient client) throws Exception {
        return client.readThroughPong(UserhostInNamesTest.WAIT).stream()
                .filter(l -> l.contains(" CAP "))
                .toList();
    }

    private static void observer(
            RawIrcClient client, String nick, String notify, boolean explicitCapNotify)
            throws Exception {
        if (!notify.isEmpty()) {
            client.send("CAP LS " + notify);
            client.readUntil("CAP * LS", UserhostInNamesTest.WAIT);
        }
        UserhostInNamesTest.negotiate(client, "*", explicitCapNotify ? "cap-notify " + CAP : CAP);
        client.send("CAP END");
        client.registerAndAwaitWelcome(nick, "ident");
    }

    private static void change(
            TestServer server, RawIrcClient root, boolean reload, boolean enabled)
            throws Exception {
        if (reload) {
            Files.writeString(
                    server.configPath,
                    TestServer.baseYaml()
                            + TestServer.adminEnabledYaml()
                            + (enabled ? ENABLED : DISABLED));
            root.send("REHASH");
            root.readUntil("382", UserhostInNamesTest.WAIT);
        } else {
            root.send("EXTENSION " + (enabled ? "ENABLE " : "DISABLE ") + CAP);
            assertThat(root.readUntil("NOTICE", UserhostInNamesTest.WAIT))
                    .contains(CAP + " is now " + (enabled ? "enabled" : "disabled"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void toggleAndReloadNotifyEligibleClientsAndRequireRenegotiation(boolean reload)
            throws Exception {
        try (TestServer server = TestServer.start(ENABLED + TestServer.adminEnabledYaml());
                RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient explicit = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient implicit = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient legacy = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            root.registerAndAwaitWelcome("root", "root");
            UserhostInNamesTest.oper(root);
            observer(explicit, "explicit", "", true);
            observer(implicit, "implicit", "302", false);
            observer(legacy, "legacy", "", false);
            assertThat(UserhostInNamesTest.names(implicit, "JOIN #room"))
                    .containsExactly("@implicit!ident@127.0.0.1");
            change(server, root, reload, false);
            assertThat(notifications(explicit))
                    .containsExactly(":test.jircd.local CAP explicit DEL " + CAP);
            assertThat(notifications(implicit))
                    .containsExactly(":test.jircd.local CAP implicit DEL " + CAP);
            assertThat(notifications(legacy)).isEmpty();
            assertThat(UserhostInNamesTest.names(implicit, UserhostInNamesTest.NAMES_ROOM))
                    .containsExactly("@implicit");
            assertThat(
                            UserhostInNamesTest.replies(implicit, CAP_LIST).stream()
                                    .filter(m -> "CAP".equals(m.rawCommand())))
                    .singleElement()
                    .satisfies(m -> assertThat(m.params().getLast()).isEmpty());
            change(server, root, reload, true);
            assertThat(notifications(explicit))
                    .containsExactly(":test.jircd.local CAP explicit NEW " + CAP);
            assertThat(notifications(implicit))
                    .containsExactly(":test.jircd.local CAP implicit NEW " + CAP);
            assertThat(notifications(legacy)).isEmpty();
            assertThat(UserhostInNamesTest.names(implicit, UserhostInNamesTest.NAMES_ROOM))
                    .containsExactly("@implicit");
            UserhostInNamesTest.negotiate(implicit, "implicit", CAP);
            assertThat(UserhostInNamesTest.names(implicit, UserhostInNamesTest.NAMES_ROOM))
                    .containsExactly("@implicit!ident@127.0.0.1");
        }
    }

    @Test
    void whoDrivenUnsafeReloadPreservesNegotiationAvailabilityAndIdentityBehavior()
            throws Exception {
        try (TestServer server = TestServer.start(ENABLED + TestServer.adminEnabledYaml());
                RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            root.registerAndAwaitWelcome("root", "root");
            UserhostInNamesTest.oper(root);
            client.registerAndAwaitWelcome("client", "ident");
            client.send("CAP LS 302");
            client.readUntil("CAP client LS", UserhostInNamesTest.WAIT);
            UserhostInNamesTest.negotiate(client, "client", CAP);
            UserhostInNamesTest.names(client, "JOIN #room");
            // WHO rejects C=351 independently of the identity reservation. This proves atomic
            // reload rejection; catalog assertions cover the installed identity-byte reservation.
            Files.writeString(
                    server.configPath,
                    TestServer.baseYaml()
                            + TestServer.adminEnabledYaml()
                            + DISABLED
                            + "channelNameMaxLength: 351\n");
            root.send("REHASH");
            assertThat(root.readUntil("NOTICE", UserhostInNamesTest.WAIT))
                    .contains("Rehash failed");
            assertThat(notifications(client)).isEmpty();
            assertThat(UserhostInNamesTest.names(client, UserhostInNamesTest.NAMES_ROOM))
                    .containsExactly("@client!ident@127.0.0.1");
            assertThat(
                            UserhostInNamesTest.replies(client, CAP_LIST).stream()
                                    .filter(m -> "CAP".equals(m.rawCommand())))
                    .singleElement()
                    .satisfies(m -> assertThat(m.params().getLast()).isEqualTo(CAP));
        }
    }
}
