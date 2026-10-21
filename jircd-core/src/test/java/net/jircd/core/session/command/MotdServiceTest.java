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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MotdServiceTest {

  @TempDir Path dir;

  private final ClientSession alice = new HandlerHarness().register("alice");
  private final AtomicReference<String> path = new AtomicReference<>();
  private final MotdService motd = new MotdService(() -> "server.example", path::get);
  private final MotdCommandHandler handler = new MotdCommandHandler(motd, () -> "server.example");

  private Path write(String name, byte[] content) throws Exception {
    Path file = dir.resolve(name);
    Files.write(file, content);
    return file;
  }

  @Test
  void aReadableFileIsSent375Then372PerLineThen376() throws Exception {
    path.set(
        write("motd.txt", "Welcome\n\nSecond line\n".getBytes(StandardCharsets.UTF_8)).toString());

    List<Message> replies = HandlerHarness.run(handler, alice, "MOTD");

    assertThat(HandlerHarness.codes(replies)).containsExactly("375", "372", "372", "372", "376");
    assertThat(replies.get(0).params())
        .containsExactly("alice", "- server.example Message of the day - ");
    assertThat(replies.get(1).params()).containsExactly("alice", "- Welcome");
    assertThat(replies.get(2).params()).containsExactly("alice", "- ");
    assertThat(replies.get(3).params()).containsExactly("alice", "- Second line");
    assertThat(replies.get(4).params()).containsExactly("alice", "End of /MOTD command");
  }

  @Test
  void onlyTheFinalLineTerminatorIsDroppedNotTrailingSpaces() throws Exception {
    path.set(write("motd.txt", "a  \n  \n".getBytes(StandardCharsets.UTF_8)).toString());

    List<Message> replies = HandlerHarness.run(handler, alice, "MOTD");

    assertThat(replies.stream().map(m -> m.params().getLast()))
        .containsExactly(
            "- server.example Message of the day - ", "- a  ", "-   ", "End of /MOTD command");
  }

  @Test
  void moreLinesThanTheCapAreTruncated() throws Exception {
    path.set(
        write("long.txt", "x\n".repeat(MotdService.MAX_LINES + 50).getBytes(StandardCharsets.UTF_8))
            .toString());

    List<Message> replies = HandlerHarness.run(handler, alice, "MOTD");

    assertThat(replies).hasSize(MotdService.MAX_LINES + 2);
  }

  @Test
  void aServerNameOtherThanThisOneIsNoSuchServer() throws Exception {
    path.set(write("motd.txt", "hi".getBytes(StandardCharsets.UTF_8)).toString());

    assertThat(HandlerHarness.codes(HandlerHarness.run(handler, alice, "MOTD other.example")))
        .containsExactly("402");
  }

  @Test
  void crlfLineEndingsAreHandled() throws Exception {
    path.set(write("motd.txt", "a\r\nb\r\n".getBytes(StandardCharsets.UTF_8)).toString());

    List<Message> replies = HandlerHarness.run(handler, alice, "MOTD");

    assertThat(replies.stream().map(m -> m.params().getLast()))
        .containsExactly(
            "- server.example Message of the day - ", "- a", "- b", "End of /MOTD command");
  }

  @Test
  void anUnsetPathIsNoMotd() throws Exception {
    List<Message> replies = HandlerHarness.run(handler, alice, "MOTD");

    assertThat(HandlerHarness.codes(replies)).containsExactly("422");
    assertThat(replies.getFirst().params()).containsExactly("alice", "MOTD File is missing");
  }

  @Test
  void aMissingFileIsNoMotdAndDoesNotThrow() throws Exception {
    path.set(dir.resolve("absent.txt").toString());

    assertThat(HandlerHarness.codes(HandlerHarness.run(handler, alice, "MOTD")))
        .containsExactly("422");
  }

  @Test
  void aDirectoryIsNoMotd() throws Exception {
    path.set(dir.toString());

    assertThat(HandlerHarness.codes(HandlerHarness.run(handler, alice, "MOTD")))
        .containsExactly("422");
  }

  @Test
  void anEmptyFileIsNoMotd() throws Exception {
    path.set(write("empty.txt", new byte[0]).toString());

    assertThat(HandlerHarness.codes(HandlerHarness.run(handler, alice, "MOTD")))
        .containsExactly("422");
  }

  @Test
  void anOversizedFileIsNoMotd() throws Exception {
    path.set(write("big.txt", new byte[MotdService.MAX_FILE_BYTES + 1]).toString());

    assertThat(HandlerHarness.codes(HandlerHarness.run(handler, alice, "MOTD")))
        .containsExactly("422");
  }

  @Test
  void editsAreSeenWithoutRestartAndAPathChangeIsFollowed() throws Exception {
    Path first = write("one.txt", "one".getBytes(StandardCharsets.UTF_8));
    path.set(first.toString());
    assertThat(HandlerHarness.run(handler, alice, "MOTD").get(1).params().getLast())
        .isEqualTo("- one");

    Files.writeString(first, "uno and more");
    Files.setLastModifiedTime(
        first, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
    assertThat(HandlerHarness.run(handler, alice, "MOTD").get(1).params().getLast())
        .isEqualTo("- uno and more");

    path.set(write("two.txt", "two".getBytes(StandardCharsets.UTF_8)).toString());
    assertThat(HandlerHarness.run(handler, alice, "MOTD").get(1).params().getLast())
        .isEqualTo("- two");
  }

  @Test
  void invalidUtf8AndLongLinesStillProduceFittingReplies() throws Exception {
    byte[] bad = {(byte) 0xff, 'o', 'k', '\n'};
    byte[] longLine = ("é".repeat(1000) + "\n").getBytes(StandardCharsets.UTF_8);
    byte[] both = new byte[bad.length + longLine.length];
    System.arraycopy(bad, 0, both, 0, bad.length);
    System.arraycopy(longLine, 0, both, bad.length, longLine.length);
    path.set(write("odd.txt", both).toString());

    List<Message> replies = HandlerHarness.run(handler, alice, "MOTD");

    assertThat(HandlerHarness.codes(replies)).containsExactly("375", "372", "372", "376");
  }

  @Test
  void sendDuringRegistrationUsesTheSameReplies() throws Exception {
    path.set(write("motd.txt", "hi".getBytes(StandardCharsets.UTF_8)).toString());
    ClientSession fresh = HandlerHarness.unregistered("c1");
    fresh.setNickname("zed");

    List<Message> replies = HandlerHarness.run((s, m) -> motd.send(s), fresh, "MOTD");

    assertThat(HandlerHarness.codes(replies)).containsExactly("375", "372", "376");
    assertThat(replies.getFirst().params().getFirst()).isEqualTo("zed");
  }
}
