# jircd container image (spec 016, contracts/container-contract.md).
#
#   docker build -t jircd:jvm .                    # JVM variant (default target)
#
# Every base image is pinned by digest; the tag is in the comment. Dependabot proposes updates.

# ---------------------------------------------------------------------------------------------
# build: compiles the server distribution once, on the build machine's own platform. Jars are
# platform-independent, so a multi-arch image never runs Gradle under emulation.
# ---------------------------------------------------------------------------------------------
# eclipse-temurin:25-jdk
FROM --platform=$BUILDPLATFORM eclipse-temurin@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain :jircd-server:installDist

# ---------------------------------------------------------------------------------------------
# jvm: the supported variant. Distroless Java 25 (Temurin) with no shell, running as nonroot.
# ---------------------------------------------------------------------------------------------
# gcr.io/distroless/java25-debian13:nonroot
FROM gcr.io/distroless/java25-debian13@sha256:a90a5fadd78dc37b643da72dca2f744ef67eba625eca7227a5b375b548e6b1c2 AS jvm
COPY --from=build /src/jircd-server/build/install/jircd-server/lib/ /opt/jircd/lib/
WORKDIR /opt/jircd
USER 65532:65532
EXPOSE 6667 6697
# Exec form, so the server is PID 1 and receives HUP (reload) and TERM (graceful shutdown).
# MaxRAMPercentage sizes the heap from the container's memory limit; -UsePerfData keeps the JVM
# from writing /tmp/hsperfdata_*, so the image runs with a read-only root filesystem.
ENTRYPOINT ["/usr/bin/java", "-XX:MaxRAMPercentage=75", "-XX:-UsePerfData", "-cp", "/opt/jircd/lib/*", "net.jircd.server.JircdServerApplication"]
CMD ["/etc/jircd/jircd.yaml"]
