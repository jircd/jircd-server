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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.jircd.core.config.ConfigurationLoader;
import net.jircd.core.config.ConfigurationReloader;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.ExtensionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionLimitReloadTest {
  @TempDir Path directory;

  @Test
  void ipv6PrefixSharesOneCountWhileOtherPrefixesAndIpv4RemainIndependent() throws Exception {
    try (Fixture fixture =
            new Fixture(directory.resolve("limits.yaml"), "maxConnectionsPerIp: 1\n");
        ControlledSocket first = new ControlledSocket("2001:db8:1::1");
        ControlledSocket same = new ControlledSocket("2001:db8:1::2");
        ControlledSocket other = new ControlledSocket("2001:db8:2::1");
        ControlledSocket ipv4 = new ControlledSocket("203.0.113.5");
        ControlledSocket mapped =
            new ControlledSocket(
                Inet6Address.getByAddress(
                    null, HexFormat.of().parseHex("00000000000000000000ffffcb007105"), -1));
        ControlledSocket otherIpv4 = new ControlledSocket("203.0.113.6")) {
      fixture.handler.accept(first);
      first.assertAdmitted();
      fixture.handler.accept(same);
      same.assertRejected();
      fixture.handler.accept(other);
      other.assertAdmitted();
      fixture.handler.accept(ipv4);
      ipv4.assertAdmitted();
      fixture.handler.accept(mapped);
      mapped.assertRejected();
      fixture.handler.accept(otherIpv4);
      otherIpv4.assertAdmitted();
    }
  }

  @Test
  void reloadKeepsTheOriginalBucketForRelease() throws Exception {
    String base = "maxConnectionsPerIp: 1\nipv6ConnectionLimitPrefix: ";
    try (Fixture fixture = new Fixture(directory.resolve("limits.yaml"), base + "64\n");
        ControlledSocket old64 = new ControlledSocket("2001:db8::1");
        ControlledSocket new128 = new ControlledSocket("2001:db8::1");
        ControlledSocket other128 = new ControlledSocket("2001:db8::2");
        ControlledSocket duplicate128 = new ControlledSocket("2001:db8::1");
        ControlledSocket fresh64 = new ControlledSocket("2001:db8::3");
        ControlledSocket duplicate64 = new ControlledSocket("2001:db8::4")) {
      fixture.handler.accept(old64);
      old64.assertAdmitted();
      fixture.reload(base + "128\n");
      fixture.handler.accept(new128);
      new128.assertAdmitted();
      fixture.handler.accept(other128);
      other128.assertAdmitted();
      old64.closeAndAwait();
      // Releasing the old /64 must neither decrement nor erase the new /128 reservation.
      fixture.handler.accept(duplicate128);
      duplicate128.assertRejected();
      fixture.reload(base + "64\n");
      // Returning to /64 proves that its original reservation was actually removed.
      fixture.handler.accept(fresh64);
      fresh64.assertAdmitted();
      fixture.handler.accept(duplicate64);
      duplicate64.assertRejected();
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final Path path;
    private final ConfigurationReloader reloader;
    private final ConnectionHandler handler;

    Fixture(Path path, String yaml) throws Exception {
      this.path = path;
      String config = "serverName: test.local\n" + yaml;
      Files.writeString(path, config);
      ConfigurationLoader loader = new ConfigurationLoader(Set.of(), Set.of());
      ServerConfiguration initial =
          loader.load(new ByteArrayInputStream(config.getBytes(StandardCharsets.UTF_8)));
      ExtensionRegistry extensions = new ExtensionRegistry();
      reloader = new ConfigurationReloader(path, loader, extensions, initial);
      DisconnectCleanup cleanup =
          new DisconnectCleanup(
              new NicknameRegistry(),
              new ChannelRegistry(),
              new WhowasHistory(() -> 100),
              extensions,
              () -> {});
      handler =
          new ConnectionHandler(
              extensions,
              cleanup,
              () -> "test.local",
              () -> initial.rateLimit(),
              () -> 60,
              () -> initial.sendqLimitBytes(),
              () -> reloader.current().connectionLimits(),
              socket -> {
                ControlledSocket controlled = (ControlledSocket) socket;
                controlled.owner.set(Thread.currentThread());
                controlled.entered.countDown();
              });
    }

    void reload(String yaml) throws Exception {
      Files.writeString(path, "serverName: test.local\n" + yaml);
      reloader.reload();
    }

    @Override
    public void close() throws IOException {
      handler.closeConnections(Duration.ofSeconds(5));
    }
  }

  private static final class ControlledSocket extends Socket {
    private final InetAddress address;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch closed = new CountDownLatch(1);
    private final AtomicReference<Thread> owner = new AtomicReference<>();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    ControlledSocket(String address) {
      this(InetAddress.ofLiteral(address));
    }

    ControlledSocket(InetAddress address) {
      this.address = address;
    }

    void assertAdmitted() throws InterruptedException {
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(isClosed()).isFalse();
    }

    void assertRejected() throws InterruptedException {
      assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(output.toString(StandardCharsets.UTF_8))
          .isEqualTo("ERROR :Too many connections\r\n");
      assertThat(owner.get()).isNull();
    }

    void closeAndAwait() throws Exception {
      close();
      assertThat(owner.get().join(Duration.ofSeconds(5))).isTrue();
    }

    @Override
    public InetAddress getInetAddress() {
      return address;
    }

    @Override
    public OutputStream getOutputStream() {
      return output;
    }

    @Override
    public InputStream getInputStream() {
      return new InputStream() {
        @Override
        public int read() throws IOException {
          try {
            closed.await();
            return -1;
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
          }
        }
      };
    }

    @Override
    public void close() throws IOException {
      try {
        super.close();
      } finally {
        closed.countDown();
      }
    }
  }
}
