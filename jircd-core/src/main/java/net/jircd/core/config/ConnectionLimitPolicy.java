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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;

/** Pure address normalization, CIDR matching and bucket selection for connection admission. */
public final class ConnectionLimitPolicy {
  private ConnectionLimitPolicy() {}

  /** The immutable bucket key and per-bucket threshold selected for an arriving client. */
  public record AddressLimit(String bucket, int maxConnectionsPerIp) {}

  /** A replacement per-bucket threshold for clients within this network. */
  public record AddressOverride(Cidr cidr, int maxConnectionsPerIp) {
    public AddressOverride {
      Objects.requireNonNull(cidr);
      if (maxConnectionsPerIp <= 0) throw new IllegalArgumentException("limit must be positive");
    }
  }

  /** A canonical IP network; host bits must be zero. Mapped IPv6 networks normalize to IPv4. */
  public record Cidr(InetAddress network, int prefixLength) {
    public Cidr {
      network = normalize(Objects.requireNonNull(network));
      if (prefixLength < 0 || prefixLength > network.getAddress().length * 8) {
        throw new IllegalArgumentException("CIDR prefix is outside the address family's range");
      }
      if (!Arrays.equals(network.getAddress(), masked(network, prefixLength))) {
        throw new IllegalArgumentException(
            "CIDR must have zero host bits; use the network address");
      }
    }

    /** Parses an unscoped IP literal/prefix without DNS; mapped IPv6 requires /96 through /128. */
    public static Cidr parse(String value) {
      String[] parts = value.split("/", -1);
      if (parts.length != 2 || !parts[1].matches("\\d{1,3}")) {
        throw new IllegalArgumentException("CIDR must be an IP literal followed by /prefix");
      }
      String literal = parts[0];
      boolean ipv6 = literal.contains(":");
      if (literal.contains("%")
          || literal.contains("[")
          || literal.contains("]")
          || (!ipv6 && !literal.matches("(\\d{1,3}\\.){3}\\d{1,3}"))) {
        throw new IllegalArgumentException("CIDR must use an unscoped IPv4 or IPv6 literal");
      }
      InetAddress address = normalize(InetAddress.ofLiteral(literal));
      int prefix = Integer.parseInt(parts[1]);
      if (ipv6 && address.getAddress().length == 4) {
        if (prefix < 96 || prefix > 128) {
          throw new IllegalArgumentException("IPv4-mapped CIDR prefix must be between 96 and 128");
        }
        prefix -= 96;
      }
      return new Cidr(address, prefix);
    }

    /** Matches the normalized client address, independently of its counting bucket. */
    public boolean contains(InetAddress address) {
      byte[] candidate = normalize(address).getAddress();
      byte[] base = network.getAddress();
      if (candidate.length != base.length) return false;
      int wholeBytes = prefixLength / 8;
      for (int i = 0; i < wholeBytes; i++) {
        if (candidate[i] != base[i]) return false;
      }
      int remaining = prefixLength % 8;
      int mask = (0xff << (8 - remaining)) & 0xff;
      return remaining == 0 || ((candidate[wholeBytes] ^ base[wholeBytes]) & mask) == 0;
    }

    @Override
    public String toString() {
      return network.getHostAddress() + "/" + prefixLength;
    }
  }

  /** Selects the most specific matching override; IPv4 always counts per /32. */
  public static AddressLimit resolve(
      InetAddress address, ServerConfiguration.ConnectionLimits limits) {
    InetAddress normalized = normalize(address);
    int prefix = normalized.getAddress().length == 4 ? 32 : limits.ipv6ConnectionLimitPrefix();
    String bucket = fromBytes(masked(normalized, prefix)).getHostAddress() + "/" + prefix;
    int selectedLimit = limits.maxConnectionsPerIp();
    int selectedPrefix = -1;
    for (AddressOverride override : limits.overrides()) {
      if (override.cidr().prefixLength() > selectedPrefix && override.cidr().contains(normalized)) {
        selectedPrefix = override.cidr().prefixLength();
        selectedLimit = override.maxConnectionsPerIp();
      }
    }
    return new AddressLimit(bucket, selectedLimit);
  }

  private static InetAddress normalize(InetAddress address) {
    byte[] bytes = address.getAddress();
    if (bytes.length == 16 && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff) {
      boolean mapped = true;
      for (int i = 0; i < 10; i++) mapped &= bytes[i] == 0;
      if (mapped) return fromBytes(Arrays.copyOfRange(bytes, 12, 16));
    }
    return address;
  }

  private static byte[] masked(InetAddress address, int prefix) {
    byte[] bytes = address.getAddress();
    for (int i = 0; i < bytes.length; i++) {
      int bits = prefix - i * 8;
      if (bits <= 0) bytes[i] = 0;
      else if (bits < 8) bytes[i] &= (byte) (0xff << (8 - bits));
    }
    return bytes;
  }

  private static InetAddress fromBytes(byte[] bytes) {
    try {
      return InetAddress.getByAddress(bytes);
    } catch (UnknownHostException impossible) {
      throw new AssertionError("IP addresses must contain 4 or 16 bytes", impossible);
    }
  }
}
