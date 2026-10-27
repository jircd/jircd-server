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
package net.jircd.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConnectionLimitPolicyTest {
    private static ConnectionLimitPolicy.AddressOverride override(String cidr, int limit) {
        return new ConnectionLimitPolicy.AddressOverride(
                ConnectionLimitPolicy.Cidr.parse(cidr), limit);
    }

    private static ConnectionLimitPolicy.AddressLimit resolve(
            String address, int prefix, List<ConnectionLimitPolicy.AddressOverride> overrides) {
        return ConnectionLimitPolicy.resolve(
                InetAddress.ofLiteral(address),
                new ServerConfiguration.ConnectionLimits(10000, 20, prefix, overrides));
    }

    @Test
    void ipv6SharesOnlyTheConfiguredPrefixAndIpv4RemainsPerAddress() {
        assertThat(resolve("2001:db8:1::1", 64, List.of()).bucket())
                .isEqualTo(resolve("2001:db8:1::ffff", 64, List.of()).bucket())
                .isNotEqualTo(resolve("2001:db8:2::1", 64, List.of()).bucket());
        assertThat(resolve("2001:db8:1::1", 128, List.of()).bucket())
                .isNotEqualTo(resolve("2001:db8:1::2", 128, List.of()).bucket());
        assertThat(resolve("203.0.113.1", 64, List.of()).bucket())
                .isEqualTo("203.0.113.1/32")
                .isNotEqualTo(resolve("203.0.113.2", 64, List.of()).bucket());
        // Non-byte-aligned grouping must mask only the host bits.
        assertThat(resolve("2001:db8::1", 65, List.of()).bucket())
                .isNotEqualTo(resolve("2001:db8:0:0:8000::1", 65, List.of()).bucket());
    }

    @Test
    void mostSpecificRangeWinsRegardlessOfListOrderAndDoesNotChangeBucket() {
        var broad = override("203.0.0.0/16", 200);
        var narrow = override("203.0.113.0/24", 5);
        for (var overrides : List.of(List.of(broad, narrow), List.of(narrow, broad))) {
            assertThat(resolve("203.0.113.1", 64, overrides).maxConnectionsPerIp()).isEqualTo(5);
            assertThat(resolve("203.0.114.1", 64, overrides).maxConnectionsPerIp()).isEqualTo(200);
            assertThat(resolve("198.51.100.1", 64, overrides).maxConnectionsPerIp()).isEqualTo(20);
            assertThat(resolve("203.0.113.1", 64, overrides).bucket())
                    .isNotEqualTo(resolve("203.0.113.2", 64, overrides).bucket());
        }
        var overrides = List.of(override("2001:db8::/32", 200), override("2001:db8::1/128", 1));
        assertThat(resolve("2001:db8::1", 64, overrides).maxConnectionsPerIp()).isEqualTo(1);
        assertThat(resolve("2001:db8::2", 64, overrides).maxConnectionsPerIp()).isEqualTo(200);
        assertThat(resolve("2001:db8::1", 64, overrides).bucket())
                .isEqualTo(resolve("2001:db8::2", 64, overrides).bucket());
    }

    @Test
    void ipv4MappedIpv6IsNormalizedBeforeMatchingAndBucketing() throws Exception {
        var mapped =
                Inet6Address.getByAddress(
                        null, HexFormat.of().parseHex("00000000000000000000ffffcb007105"), -1);
        assertThat(mapped.getAddress()).hasSize(16);
        var limits =
                new ServerConfiguration.ConnectionLimits(
                        10000,
                        20,
                        64,
                        List.of(override("203.0.113.0/24", 200), override("::/0", 1)));
        assertThat(ConnectionLimitPolicy.resolve(mapped, limits))
                .isEqualTo(
                        ConnectionLimitPolicy.resolve(
                                InetAddress.ofLiteral("203.0.113.5"), limits));
        assertThat(ConnectionLimitPolicy.resolve(mapped, limits).maxConnectionsPerIp())
                .isEqualTo(200);
        assertThat(ConnectionLimitPolicy.Cidr.parse("::ffff:203.0.113.0/120"))
                .isEqualTo(ConnectionLimitPolicy.Cidr.parse("203.0.113.0/24"));
    }

    @Test
    void partialByteOverridesMatchOnlyTheirNetworkAndCompatibleIpv6StaysIpv6() {
        var overrides =
                List.of(override("203.0.113.128/25", 50), override("2001:db8:0:0:8000::/65", 100));
        assertThat(resolve("203.0.113.128", 64, overrides).maxConnectionsPerIp()).isEqualTo(50);
        assertThat(resolve("203.0.113.127", 64, overrides).maxConnectionsPerIp()).isEqualTo(20);
        assertThat(resolve("2001:db8:0:0:ffff::1", 64, overrides).maxConnectionsPerIp())
                .isEqualTo(100);
        assertThat(resolve("2001:db8:0:0:7fff::1", 64, overrides).maxConnectionsPerIp())
                .isEqualTo(20);
        assertThat(resolve("2001:db8::ffff:cb00:7105", 64, List.of()).bucket())
                .isEqualTo(resolve("2001:db8::1", 64, List.of()).bucket());
    }

    @Test
    void directRangeAndOverrideConstructionPreservesValidation() {
        var address = InetAddress.ofLiteral("203.0.113.0");
        assertThatThrownBy(() -> new ConnectionLimitPolicy.Cidr(address, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConnectionLimitPolicy.Cidr(address, 33))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> override("203.0.113.0/24", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroLengthRangesMatchOnlyTheirAddressFamily() {
        var overrides = List.of(override("0.0.0.0/0", 100), override("::/0", 200));
        assertThat(resolve("203.0.113.1", 1, overrides).maxConnectionsPerIp()).isEqualTo(100);
        assertThat(resolve("2001:db8::1", 1, overrides).maxConnectionsPerIp()).isEqualTo(200);
        assertThat(resolve("2001:db8::1", 1, overrides).bucket())
                .isEqualTo(resolve("7fff::1", 1, overrides).bucket())
                .isNotEqualTo(resolve("8000::1", 1, overrides).bucket());
    }
}
