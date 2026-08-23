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
package net.jircd.server;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import net.jircd.core.capability.CapabilityNegotiator;
import net.jircd.core.config.ConfigurationException;
import net.jircd.core.config.ConfigurationLoader;
import net.jircd.core.config.ConfigurationReloader;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ConnectionHandler;
import net.jircd.core.session.DisconnectCleanup;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.PlaintextListener;
import net.jircd.core.session.TlsListener;
import net.jircd.core.session.WhowasHistory;
import net.jircd.core.session.command.AwayCommandHandler;
import net.jircd.core.session.command.CapCommandHandler;
import net.jircd.core.session.command.JoinCommandHandler;
import net.jircd.core.session.command.ListCommandHandler;
import net.jircd.core.session.command.LusersCommandHandler;
import net.jircd.core.session.command.MessageCommandHandler;
import net.jircd.core.session.command.NamesCommandHandler;
import net.jircd.core.session.command.NickCommandHandler;
import net.jircd.core.session.command.PartCommandHandler;
import net.jircd.core.session.command.PingPongCommandHandler;
import net.jircd.core.session.command.QuitCommandHandler;
import net.jircd.core.session.command.TagmsgCommandHandler;
import net.jircd.core.session.command.TimeCommandHandler;
import net.jircd.core.session.command.TopicCommandHandler;
import net.jircd.core.session.command.UserCommandHandler;
import net.jircd.core.session.command.UserModeCommandHandler;
import net.jircd.core.session.command.VersionCommandHandler;
import net.jircd.core.session.command.WhoCommandHandler;
import net.jircd.core.session.command.WhoisCommandHandler;
import net.jircd.core.session.command.WhowasCommandHandler;
import net.jircd.protocol.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The application entry point (composition root): loads {@link ServerConfiguration}, starts {@link
 * ExtensionRegistry}, starts the plaintext and TLS listeners, refuses to start with a specific
 * error on invalid configuration (FR-012).
 */
public final class JircdServerApplication {

  private static final Logger LOG = LoggerFactory.getLogger(JircdServerApplication.class);

  private final NicknameRegistry nicknameRegistry = new NicknameRegistry();
  private final ChannelRegistry channelRegistry = new ChannelRegistry();
  private final ExtensionRegistry extensionRegistry = new ExtensionRegistry();
  private final WhowasHistory whowasHistory;
  private final DisconnectCleanup disconnectCleanup;
  private final ConnectionHandler connectionHandler;
  private final Instant startedAt = Instant.now();

  private volatile ServerConfiguration configuration;
  private volatile String serverName;
  private final String serverVersion;
  private final ConfigurationReloader reloader;

  private PlaintextListener plaintextListener;
  private TlsListener tlsListener;

  public JircdServerApplication(Path configPath) throws ConfigurationException, IOException {
    extensionRegistry.discover(Thread.currentThread().getContextClassLoader());

    Set<String> knownCapabilityIds =
        extensionRegistry.all().stream()
            .filter(net.jircd.core.extension.CapabilityExtension.class::isInstance)
            .map(net.jircd.core.extension.Extension::id)
            .collect(java.util.stream.Collectors.toSet());
    Set<String> knownServerExtensionIds =
        extensionRegistry.all().stream()
            .filter(e -> !(e instanceof net.jircd.core.extension.CapabilityExtension))
            .map(net.jircd.core.extension.Extension::id)
            .collect(java.util.stream.Collectors.toSet());
    ConfigurationLoader loader =
        new ConfigurationLoader(knownCapabilityIds, knownServerExtensionIds);

    try (InputStream in = new FileInputStream(configPath.toFile())) {
      this.configuration = loader.load(in);
    }

    this.serverVersion = resolveServerVersion();
    this.serverName = resolveServerName(configuration.serverName());
    extensionRegistry.updateConfiguredLengths(
        configuration.nicknameMaxLength(),
        configuration.channelNameMaxLength(),
        configuration.topicMaxLength(),
        configuration.maxModesPerCommand());

    this.reloader = new ConfigurationReloader(configPath, loader, extensionRegistry, configuration);
    this.whowasHistory = new WhowasHistory(() -> reloader.current().whowasHistorySize());
    this.disconnectCleanup =
        new DisconnectCleanup(nicknameRegistry, channelRegistry, whowasHistory, extensionRegistry);

    this.connectionHandler =
        new ConnectionHandler(
            extensionRegistry,
            disconnectCleanup,
            () -> serverName,
            () -> reloader.current().rateLimit(),
            () -> reloader.current().keepAliveFrequencySeconds());
    registerStory1Handlers();

    extensionRegistry.attachContext(
        new net.jircd.core.extension.ServerContext(
            nicknameRegistry,
            channelRegistry,
            extensionRegistry,
            () -> serverName,
            reloader,
            disconnectCleanup,
            connectionHandler::registerHandler));

    enableConfiguredExtensions();
  }

  private void enableConfiguredExtensions() {
    configuration
        .capabilityStates()
        .forEach(
            (id, enabled) -> {
              if (enabled) {
                extensionRegistry.enable(id);
              }
            });
    configuration
        .serverExtensionStates()
        .forEach(
            (id, enabled) -> {
              if (enabled) {
                extensionRegistry.enable(id);
              }
            });
  }

  private void registerStory1Handlers() {
    var registrationCompletion =
        new net.jircd.core.session.command.RegistrationCompletion(
            () -> serverName, serverVersion, startedAt, extensionRegistry);
    connectionHandler.registerHandler(
        Command.NICK,
        new NickCommandHandler(
            nicknameRegistry,
            () -> serverName,
            () -> reloader.current().nicknameMaxLength(),
            registrationCompletion,
            extensionRegistry));
    connectionHandler.registerHandler(
        Command.USER, new UserCommandHandler(() -> serverName, registrationCompletion));
    connectionHandler.registerHandler(
        Command.JOIN,
        new JoinCommandHandler(
            channelRegistry,
            extensionRegistry,
            () -> serverName,
            () -> reloader.current().channelNameMaxLength()));
    connectionHandler.registerHandler(
        Command.PART, new PartCommandHandler(channelRegistry, extensionRegistry, () -> serverName));
    connectionHandler.registerHandler(
        Command.PRIVMSG,
        new MessageCommandHandler(
            channelRegistry, nicknameRegistry, extensionRegistry, () -> serverName, false));
    connectionHandler.registerHandler(
        Command.NOTICE,
        new MessageCommandHandler(
            channelRegistry, nicknameRegistry, extensionRegistry, () -> serverName, true));
    connectionHandler.registerHandler(Command.QUIT, new QuitCommandHandler(disconnectCleanup));
    connectionHandler.registerHandler(
        Command.TOPIC,
        new TopicCommandHandler(
            channelRegistry,
            extensionRegistry,
            () -> serverName,
            () -> reloader.current().topicMaxLength()));
    connectionHandler.registerHandler(
        Command.NAMES,
        new NamesCommandHandler(channelRegistry, extensionRegistry, () -> serverName));
    connectionHandler.registerHandler(
        Command.LIST, new ListCommandHandler(channelRegistry, extensionRegistry, () -> serverName));
    connectionHandler.registerHandler(
        Command.WHOIS,
        new WhoisCommandHandler(nicknameRegistry, extensionRegistry, () -> serverName));
    connectionHandler.registerHandler(
        Command.WHO,
        new WhoCommandHandler(
            channelRegistry,
            nicknameRegistry,
            extensionRegistry,
            () -> serverName,
            () -> reloader.current().whoMaskEnabled()));
    connectionHandler.registerHandler(Command.PING, PingPongCommandHandler.ping(() -> serverName));
    connectionHandler.registerHandler(Command.PONG, PingPongCommandHandler.pong());
    connectionHandler.registerHandler(
        Command.CAP,
        new CapCommandHandler(
            new CapabilityNegotiator(extensionRegistry), () -> serverName, registrationCompletion));
    registerModerationHandlers();
    registerExtendedCommandHandlers();
  }

  /** VERSION/TIME/LUSERS/AWAY/WHOWAS/TAGMSG (002-extended-irc-commands). */
  private void registerExtendedCommandHandlers() {
    connectionHandler.registerHandler(
        Command.VERSION,
        new VersionCommandHandler(() -> serverName, serverVersion, extensionRegistry));
    connectionHandler.registerHandler(Command.TIME, new TimeCommandHandler(() -> serverName));
    connectionHandler.registerHandler(
        Command.USERHOST,
        new net.jircd.core.session.command.UserhostCommandHandler(
            nicknameRegistry, extensionRegistry, () -> serverName));
    connectionHandler.registerHandler(
        Command.INFO,
        new net.jircd.core.session.command.InfoCommandHandler(() -> serverName, serverVersion));
    connectionHandler.registerHandler(
        Command.LUSERS,
        new LusersCommandHandler(nicknameRegistry, channelRegistry, () -> serverName));
    connectionHandler.registerHandler(
        Command.AWAY, new AwayCommandHandler(() -> serverName, extensionRegistry));
    connectionHandler.registerHandler(
        Command.WHOWAS, new WhowasCommandHandler(whowasHistory, () -> serverName));
    connectionHandler.registerHandler(
        Command.TAGMSG,
        new TagmsgCommandHandler(
            channelRegistry, nicknameRegistry, extensionRegistry, () -> serverName));
  }

  /** {@code KICK}/channel-{@code MODE}/{@code INVITE} (Story 5, FR-013/FR-014/FR-064/FR-065). */
  private void registerModerationHandlers() {
    connectionHandler.registerHandler(
        Command.KICK,
        new net.jircd.core.session.command.KickCommandHandler(
            channelRegistry, extensionRegistry, () -> serverName));
    connectionHandler.registerHandler(
        Command.INVITE,
        new net.jircd.core.session.command.InviteCommandHandler(
            channelRegistry, nicknameRegistry, extensionRegistry, () -> serverName));

    var userModeHandler = new UserModeCommandHandler(() -> serverName);
    var channelModeHandler =
        new net.jircd.core.session.command.ModeCommandHandler(
            channelRegistry,
            nicknameRegistry,
            extensionRegistry,
            () -> serverName,
            () -> reloader.current().maxModesPerCommand());
    connectionHandler.registerHandler(
        Command.MODE,
        (session, message) -> {
          if (!message.params().isEmpty() && message.params().getFirst().startsWith("#")) {
            channelModeHandler.handle(session, message);
          } else {
            userModeHandler.handle(session, message);
          }
        });
  }

  public void start() throws IOException, GeneralSecurityException {
    // No TLS entry in the zero-config default (004-fix-tls-certificate, research.md "The
    // zero-config default listener list") — a TLS listener with no certificate configured now
    // refuses to start, so defaulting to one here would break the simplest possible quickstart.
    List<ServerConfiguration.Listener> listeners =
        configuration.listeners().isEmpty()
            ? List.of(new ServerConfiguration.Listener(6667, false))
            : configuration.listeners();
    for (ServerConfiguration.Listener listener : listeners) {
      if (listener.tls()) {
        tlsListener = new TlsListener(listener, connectionHandler);
        tlsListener.start();
        LOG.info("TLS listener started on port {}", listener.port());
      } else {
        plaintextListener = new PlaintextListener(listener.port(), connectionHandler);
        plaintextListener.start();
        LOG.info("Plaintext listener started on port {}", listener.port());
      }
    }
  }

  public void stop() throws IOException {
    if (plaintextListener != null) {
      plaintextListener.close();
    }
    if (tlsListener != null) {
      tlsListener.close();
    }
  }

  public ConfigurationReloader reloader() {
    return reloader;
  }

  public int plaintextPort() {
    return plaintextListener.boundPort();
  }

  public int tlsPort() {
    return tlsListener.boundPort();
  }

  public ExtensionRegistry extensionRegistry() {
    return extensionRegistry;
  }

  public NicknameRegistry nicknameRegistry() {
    return nicknameRegistry;
  }

  public ChannelRegistry channelRegistry() {
    return channelRegistry;
  }

  public String serverName() {
    return serverName;
  }

  private String resolveServerVersion() throws IOException {
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("net/jircd/server/version.properties")) {
      if (in == null) {
        throw new IOException("net/jircd/server/version.properties resource is missing");
      }
      Properties properties = new Properties();
      properties.load(in);
      String version = properties.getProperty("version");
      if (version == null || version.isBlank()) {
        throw new IOException("net/jircd/server/version.properties has no 'version' entry");
      }
      return version;
    }
  }

  private static String resolveServerName(String configured) {
    if (configured != null) {
      return configured;
    }
    String hostname;
    try {
      hostname = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      hostname = "localhost";
    }
    return hostname.contains(".") ? hostname : hostname + ".local";
  }

  public static void main(String[] args) throws InterruptedException {
    Path configPath = Path.of(args.length > 0 ? args[0] : "jircd.yaml");
    JircdServerApplication application;
    try {
      application = new JircdServerApplication(configPath);
      application.start();
    } catch (ConfigurationException e) {
      LOG.error("Refusing to start: {}", e.getMessage());
      System.exit(1);
      return;
    } catch (IOException | GeneralSecurityException e) {
      LOG.error("Refusing to start: {}", e.getMessage());
      System.exit(1);
      return;
    }
    SighupReloadHandler.install(application);
    LOG.info("jircd started as {}", application.serverName());
    Thread.currentThread().join();
  }
}
