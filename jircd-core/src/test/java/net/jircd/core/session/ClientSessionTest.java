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
package net.jircd.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ClientSessionTest {
    @Test
    void collectionsRejectExternalAdditions() {
        ClientSession session = session();
        Set<UserMode> userModes = session.userModes();
        Set<Channel> memberships = session.channelMemberships();
        Channel channel = new Channel("#c");
        assertThatThrownBy(() -> userModes.add(UserMode.INVISIBLE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> memberships.add(channel))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void administratorGrantAndRevokeStillToggleOperatorMode() {
        ClientSession session = session();
        session.grantAdministratorPrivilege();
        assertThat(session.isAdministrator()).isTrue();
        assertThat(session.userModes()).contains(UserMode.OPERATOR);
        session.revokeAdministratorPrivilege();
        assertThat(session.isAdministrator()).isFalse();
        assertThat(session.userModes()).doesNotContain(UserMode.OPERATOR);
    }

    @Test
    void namedUserModeMutatorsUpdateTheUnmodifiableLiveView() {
        ClientSession session = session();
        var modes = session.userModes();
        session.setUserMode(UserMode.INVISIBLE);
        session.setUserMode(UserMode.WALLOPS);
        session.setUserMode(UserMode.INVISIBLE);
        assertThat(modes).containsExactlyInAnyOrder(UserMode.INVISIBLE, UserMode.WALLOPS);
        session.clearUserMode(UserMode.INVISIBLE);
        session.clearUserMode(UserMode.INVISIBLE);
        assertThat(modes).containsExactly(UserMode.WALLOPS);
        session.clearUserMode(UserMode.WALLOPS);
        assertThat(modes).isEmpty();
    }

    @Test
    void genericMutatorsCannotSetOrClearOperatorMode() {
        ClientSession session = session();
        UserMode operatorCopy = new UserMode("operator", 'o', UserMode.CORE, false);
        assertThatThrownBy(() -> session.setUserMode(operatorCopy))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(session.isAdministrator()).isFalse();
        assertThat(session.userModes()).doesNotContain(UserMode.OPERATOR);
        session.grantAdministratorPrivilege();
        assertThatThrownBy(() -> session.clearUserMode(operatorCopy))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(session.isAdministrator()).isTrue();
        assertThat(session.userModes()).contains(UserMode.OPERATOR);
        session.revokeAdministratorPrivilege();
        assertThat(session.userModes()).doesNotContain(UserMode.OPERATOR);
    }

    private static ClientSession session() {
        return new ClientSession("session", "host.example", RateLimitBucket.withDefaults());
    }
}
