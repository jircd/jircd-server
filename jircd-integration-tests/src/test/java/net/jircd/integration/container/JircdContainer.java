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
package net.jircd.integration.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.TopContainerResponse;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * The image under test (spec 016), named by the {@code jircd.image} system property, started the
 * way the container contract says an operator runs it: configuration mounted read-only at {@code
 * /etc/jircd}, a read-only root filesystem, ports 6667 and 6697. Nothing is retried; every test
 * gets a fresh container.
 */
final class JircdContainer implements AutoCloseable {

    static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(60);

    private final GenericContainer<?> container;

    private JircdContainer(GenericContainer<?> container) {
        this.container = container;
    }

    /** The image to test; the {@code containerTest} task requires it. */
    static String image() {
        String image = System.getProperty("jircd.image");
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("set -Djircd.image=<image>");
        }
        return image;
    }

    /** Starts the image with {@code configDir} at {@code /etc/jircd}; waits for startup. */
    static JircdContainer start(Path configDir) {
        return start(configDir, Map.of());
    }

    /**
     * As {@link #start(Path)}, also binding each host directory in {@code writableBinds} read-write
     * at its container path, the way an operator mounts a log volume.
     */
    @SuppressWarnings("resource") // closed by JircdContainer.close()
    static JircdContainer start(Path configDir, Map<Path, String> writableBinds) {
        GenericContainer<?> container =
                new GenericContainer<>(DockerImageName.parse(image()))
                        .withExposedPorts(6667, 6697)
                        .withFileSystemBind(configDir.toString(), "/etc/jircd", BindMode.READ_ONLY)
                        .withCreateContainerCmdModifier(JircdContainer::readOnlyRoot)
                        .waitingFor(
                                Wait.forLogMessage(".*jircd started as.*\\n", 1)
                                        .withStartupTimeout(STARTUP_TIMEOUT));
        writableBinds.forEach(
                (host, inside) ->
                        container.withFileSystemBind(host.toString(), inside, BindMode.READ_WRITE));
        container.start();
        return new JircdContainer(container);
    }

    /**
     * Starts the image with {@code /etc/jircd} on a Docker volume holding {@code files} (name to
     * content, root-owned, mode 644) instead of a host bind mount, and binds each of {@code
     * writableBinds} read-write. Docker Desktop's host file sharing can serve a just-rewritten file
     * from a stale cache, so tests that change the configuration while the server runs use {@link
     * #replaceConfigFile} on a volume, whose changes the container sees at once.
     */
    @SuppressWarnings("resource") // closed by JircdContainer.close()
    static JircdContainer startWithConfigVolume(
            Map<String, byte[]> files, Map<Path, String> writableBinds) {
        VolumeConfigured container = new VolumeConfigured(files);
        container
                .withExposedPorts(6667, 6697)
                .withCreateContainerCmdModifier(
                        cmd -> {
                            cmd.withVolumes(new Volume("/etc/jircd"));
                            readOnlyRoot(cmd);
                        })
                .waitingFor(
                        Wait.forLogMessage(".*jircd started as.*\\n", 1)
                                .withStartupTimeout(STARTUP_TIMEOUT));
        writableBinds.forEach(
                (host, inside) ->
                        container.withFileSystemBind(host.toString(), inside, BindMode.READ_WRITE));
        container.start();
        return new JircdContainer(container);
    }

    /** Copies the configuration files into the {@code /etc/jircd} volume before start. */
    private static final class VolumeConfigured extends GenericContainer<VolumeConfigured> {
        private final Map<String, byte[]> files;

        VolumeConfigured(Map<String, byte[]> files) {
            super(DockerImageName.parse(image()));
            this.files = Map.copyOf(files);
        }

        // The initial files are not part of a container's identity; keep the superclass's.
        @Override
        public boolean equals(Object other) {
            return super.equals(other);
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }

        @Override
        protected void containerIsCreated(String containerId) {
            // Into the volume: the read-only root rejects any other target path.
            try {
                client().copyArchiveToContainerCmd(containerId)
                        .withRemotePath("/etc/jircd")
                        .withTarInputStream(tar(files, 0644))
                        .exec();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Replaces {@code /etc/jircd/<name>} on the configuration volume, root-owned, mode 644. */
    void replaceConfigFile(String name, String content) throws IOException {
        client().copyArchiveToContainerCmd(container.getContainerId())
                .withRemotePath("/etc/jircd")
                .withTarInputStream(singleFileTar(name, content, 0644))
                .exec();
    }

    int plaintextPort() {
        return container.getMappedPort(6667);
    }

    int tlsPort() {
        return container.getMappedPort(6697);
    }

    /** Everything the server wrote to standard output and standard error so far. */
    String logs() {
        return container.getLogs();
    }

    /** Sends {@code signal} (for example {@code HUP}) to the server process. */
    void signal(String signal) {
        client().killContainerCmd(container.getContainerId()).withSignal(signal).exec();
    }

    /** The user the image runs as, from the container configuration. */
    String configuredUser() {
        return client().inspectContainerCmd(container.getContainerId())
                .exec()
                .getConfig()
                .getUser();
    }

    /** The {@code UID} column of every process running in the container. */
    List<String> processUids() {
        TopContainerResponse top =
                client().topContainerCmd(container.getContainerId())
                        .withPsArgs("-o pid,uid")
                        .exec();
        int column = Arrays.asList(top.getTitles()).indexOf("UID");
        List<String> uids = new ArrayList<>();
        for (String[] process : top.getProcesses()) uids.add(process[column].strip());
        return uids;
    }

    /** What a graceful stop did: the exit status and how long the runtime waited. */
    record Stopped(int exitCode, Duration elapsed, String logs) {}

    /**
     * Stops the container as {@code docker stop -t <grace>} does: TERM, then KILL once {@code
     * grace} has passed.
     */
    Stopped stop(Duration grace) {
        String id = container.getContainerId();
        long start = System.nanoTime();
        client().stopContainerCmd(id).withTimeout((int) grace.toSeconds()).exec();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        Long exit = client().inspectContainerCmd(id).exec().getState().getExitCodeLong();
        return new Stopped(exit == null ? -1 : exit.intValue(), elapsed, container.getLogs());
    }

    /** Waits for the server process to exit on its own (after a signal), then reports it. */
    Stopped awaitExit(Duration timeout) {
        String id = container.getContainerId();
        long start = System.nanoTime();
        Integer status =
                client().waitContainerCmd(id)
                        .start()
                        .awaitStatusCode(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return new Stopped(
                status, Duration.ofNanos(System.nanoTime() - start), container.getLogs());
    }

    @Override
    public void close() {
        container.stop();
    }

    /** A container that was expected to exit by itself, for startup-failure tests. */
    record Exited(int exitCode, String logs) {}

    /**
     * Runs the image to completion with {@code configDir} (or nothing, when {@code null}) mounted
     * at {@code /etc/jircd} and a read-only root, and returns its exit status and output.
     */
    static Exited runToExit(Path configDir, Duration timeout) throws InterruptedException {
        HostConfig hostConfig = HostConfig.newHostConfig().withReadonlyRootfs(true);
        if (configDir != null) {
            hostConfig.withBinds(
                    new Bind(configDir.toString(), new Volume("/etc/jircd"), AccessMode.ro));
        }
        String id =
                client().createContainerCmd(image())
                        .withHostConfig(hostConfig)
                        .withLabels(DockerClientFactory.DEFAULT_LABELS)
                        .exec()
                        .getId();
        return runCreated(id, timeout);
    }

    /**
     * Runs the image to completion with {@code /etc/jircd/jircd.yaml} owned by root with {@code
     * mode} on a fresh volume. Unlike a host bind mount, whose permissions Docker Desktop does not
     * enforce, this makes a file the container user really cannot read on every platform.
     */
    static Exited runToExitWithRootOwnedConfig(String yaml, int mode, Duration timeout)
            throws IOException, InterruptedException {
        String id =
                client().createContainerCmd(image())
                        .withHostConfig(HostConfig.newHostConfig().withReadonlyRootfs(true))
                        .withVolumes(new Volume("/etc/jircd"))
                        .withLabels(DockerClientFactory.DEFAULT_LABELS)
                        .exec()
                        .getId();
        try {
            client().copyArchiveToContainerCmd(id)
                    .withRemotePath("/etc/jircd")
                    .withTarInputStream(singleFileTar("jircd.yaml", yaml, mode))
                    .exec();
        } catch (RuntimeException e) {
            client().removeContainerCmd(id).withForce(true).withRemoveVolumes(true).exec();
            throw e;
        }
        return runCreated(id, timeout);
    }

    private static Exited runCreated(String id, Duration timeout) throws InterruptedException {
        DockerClient client = client();
        try {
            client.startContainerCmd(id).exec();
            Integer status =
                    client.waitContainerCmd(id)
                            .start()
                            .awaitStatusCode(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Exited(status, logsOf(client, id));
        } finally {
            client.removeContainerCmd(id).withForce(true).withRemoveVolumes(true).exec();
        }
    }

    /** A tar stream holding one root-owned regular file. */
    private static InputStream singleFileTar(String name, String content, int mode)
            throws IOException {
        return tar(Map.of(name, content.getBytes(StandardCharsets.UTF_8)), mode);
    }

    /** A tar stream holding root-owned regular files with {@code mode}. */
    private static InputStream tar(Map<String, byte[]> files, int mode) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(buffer)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                TarArchiveEntry entry = new TarArchiveEntry(file.getKey());
                entry.setSize(file.getValue().length);
                entry.setMode(mode);
                entry.setIds(0, 0);
                tar.putArchiveEntry(entry);
                tar.write(file.getValue());
                tar.closeArchiveEntry();
            }
        }
        return new ByteArrayInputStream(buffer.toByteArray());
    }

    private static String logsOf(DockerClient client, String id) throws InterruptedException {
        StringBuilder out = new StringBuilder();
        client.logContainerCmd(id)
                .withStdOut(true)
                .withStdErr(true)
                .exec(
                        new ResultCallback.Adapter<Frame>() {
                            @Override
                            public void onNext(Frame frame) {
                                out.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                            }
                        })
                .awaitCompletion(30, TimeUnit.SECONDS);
        return out.toString();
    }

    /** Every container under test runs with a read-only root filesystem (spec 016 FR-005). */
    private static void readOnlyRoot(CreateContainerCmd cmd) {
        HostConfig hostConfig = cmd.getHostConfig();
        if (hostConfig == null) {
            hostConfig = HostConfig.newHostConfig();
            cmd.withHostConfig(hostConfig);
        }
        hostConfig.withReadonlyRootfs(true);
    }

    private static DockerClient client() {
        return DockerClientFactory.instance().client();
    }
}
