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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.capability.ReplyBudgetCatalog;
import net.jircd.core.extension.capability.ReplyContribution;
import net.jircd.core.extension.capability.ReplyLayout;
import net.jircd.core.extension.capability.ReplyLimits;
import net.jircd.core.extension.capability.ReplySlot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Generic reply layouts and slot bounds replace capability-specific width assumptions. */
class CapabilityReplyBudgetTest {
  @TempDir Path directory;

  private static final int S = 16; // "test.jircd.local"
  private static final ReplyLimits LIMITS = ReplyLimits.of(S, 9, 50);

  private static ReplyBudgetCatalog catalog(Object... slotAndBytes) {
    List<ReplyContribution> list = new java.util.ArrayList<>();
    for (int i = 0; i < slotAndBytes.length; i += 3) {
      long bytes = ((Number) slotAndBytes[i + 2]).longValue();
      list.add(
          new ReplyContribution(
              (String) slotAndBytes[i], (ReplySlot) slotAndBytes[i + 1], l -> bytes));
    }
    return new ReplyBudgetCatalog(list);
  }

  @Test
  void coreOnlyHighestStatusReservesOneStatusByte() {
    ReplyBudgetCatalog core = ReplyBudgetCatalog.empty();
    assertThat(core.worstCaseBytes(ReplyLayout.NAMES_MEMBER, LIMITS))
        .isEqualTo(S + 2 * 9 + 50 + 14);
    assertThat(core.worstCaseBytes(ReplyLayout.NAMES_END, LIMITS)).isEqualTo(S + 9 + 50 + 29);
  }

  @Test
  void twoSymbolStatusReproducesTheCurrentSafeEnvelope() {
    ReplyBudgetCatalog legacy = ReplyBudgetCatalog.legacyCompatible();
    assertThat(legacy.worstCaseBytes(ReplyLayout.NAMES_MEMBER, LIMITS))
        .isEqualTo(S + 2 * 9 + 50 + 15);
    int u = ServerConfiguration.IDENT_MAX_BYTES;
    int h = ServerConfiguration.PRESENTED_HOST_MAX_BYTES;
    // WHO with F = 2 + P = 4 is exactly the previously hard-coded assumption.
    assertThat(legacy.worstCaseBytes(ReplyLayout.WHO_ENTRY, LIMITS))
        .isEqualTo(2 * S + 2 * 9 + 50 + u + h + 22);
    assertThat(HostmaskReplyBudget.worstCaseBytes(S, 9, 50))
        .containsEntry("352 (WHO)", 2 * S + 2 * 9 + 50 + u + h + 22);
  }

  @Test
  void replacementSlotsReserveTheirMaximumAndIndependentSlotsSum() {
    ReplyBudgetCatalog catalog =
        catalog("a", ReplySlot.STATUS, 2, "b", ReplySlot.STATUS, 3, "c", ReplySlot.IDENTITY, 40);
    // Only one provider can own STATUS, so the conservative reservation is the larger one (3), not
    // 5.
    assertThat(catalog.slotBytes(LIMITS))
        .containsEntry(ReplySlot.STATUS, 3L)
        .containsEntry(ReplySlot.IDENTITY, 40L);
    assertThat(catalog.worstCaseBytes(ReplyLayout.NAMES_MEMBER, LIMITS))
        .isEqualTo(S + 2 * 9 + 50 + 13 + 3 + 40);
  }

  @Test
  void identityBytesComposeWithTheStatusReservationInsteadOfBeingCheckedAlone() {
    int u = ServerConfiguration.IDENT_MAX_BYTES;
    int h = ServerConfiguration.PRESENTED_HOST_MAX_BYTES;
    ReplyBudgetCatalog both =
        catalog("mp", ReplySlot.STATUS, 2, "uh", ReplySlot.IDENTITY, u + h + 2);
    ReplyBudgetCatalog identityOnly = catalog("uh", ReplySlot.IDENTITY, u + h + 2);
    assertThat(both.worstCaseBytes(ReplyLayout.NAMES_MEMBER, LIMITS))
        .isEqualTo(identityOnly.worstCaseBytes(ReplyLayout.NAMES_MEMBER, LIMITS) + 1);
  }

  @Test
  void invalidDeclaredBoundsAreFaultsNotZeroByteDefaults() {
    for (long bad : new long[] {-1, 513}) {
      ReplyBudgetCatalog catalog = catalog("p", ReplySlot.STATUS, bad);
      assertThatThrownBy(() -> catalog.slotBytes(LIMITS))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'p'");
    }
    ReplyBudgetCatalog throwing =
        new ReplyBudgetCatalog(
            List.of(
                new ReplyContribution(
                    "p",
                    ReplySlot.STATUS,
                    l -> {
                      throw new IllegalStateException("x");
                    })));
    assertThatThrownBy(() -> throwing.slotBytes(LIMITS))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- startup / reload / enable parity

  private static final ReplyBudgetCatalog WIDE = catalog("wide", ReplySlot.IDENTITY, 200);

  private ConfigurationLoader loader() {
    return new ConfigurationLoader(Set.of(), Set.of(), () -> WIDE);
  }

  private static final String YAML =
      "serverName: test.jircd.local\nnicknameMaxLength: 100\nchannelNameMaxLength: 50\n";

  @Test
  void startupReloadAndEnableUseTheSameInstalledCatalogBounds() throws Exception {
    // 353 = 16 + 2*150 + 50 + 15 = 381 fits alone; the provider's 200 identity bytes make it 581.
    String tight =
        "serverName: test.jircd.local\nnicknameMaxLength: 150\nchannelNameMaxLength: 50\n";
    // Without the installed provider the same configuration is accepted ...
    assertThat(
            new ConfigurationLoader(Set.of(), Set.of())
                .load(new ByteArrayInputStream(tight.getBytes(StandardCharsets.UTF_8)))
                .nicknameMaxLength())
        .isEqualTo(150);
    // ... startup rejects it once the provider's identity bytes are reserved ...
    assertThatThrownBy(
            () -> loader().load(new ByteArrayInputStream(tight.getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("353=");
    // ... and so does reload, with the previous configuration untouched.
    ConfigurationLoader plain = new ConfigurationLoader(Set.of(), Set.of());
    ServerConfiguration initial =
        plain.load(
            new ByteArrayInputStream(
                "serverName: test.jircd.local\n".getBytes(StandardCharsets.UTF_8)));
    Path yaml = directory.resolve("tight.yaml");
    Files.writeString(yaml, tight);
    ConfigurationReloader reloader =
        new ConfigurationReloader(yaml, loader(), new ExtensionRegistry(), initial);
    assertThatThrownBy(reloader::reload)
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("353=");
    assertThat(reloader.current()).isSameAs(initial);
  }

  @Test
  void validConfigurationWithTheInstalledCatalogStillLoads() throws Exception {
    assertThat(
            loader()
                .load(new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8)))
                .nicknameMaxLength())
        .isEqualTo(100);
  }

  @Test
  void namesFitComputationReportsTheRemainingChannelBudget() {
    assertThatThrownBy(
            () ->
                ConfigurationLoader.validateNamesBudget(
                    "test.jircd.local", 216, 51, ReplyBudgetCatalog.legacyCompatible()))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("353=514")
        .hasMessageContaining("budget=");
    Map<String, Integer> names =
        HostmaskReplyBudget.namesWorstCaseBytes(16, 215, 51, ReplyBudgetCatalog.legacyCompatible());
    assertThat(names).containsEntry("353", 512);
  }
}
