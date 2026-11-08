# jircd container image (spec 016, contracts/container-contract.md).
#
#   docker build -t jircd:jvm .                    # JVM variant (default target)
#   docker build --target native -t jircd:native . # experimental native-executable variant
#   docker build --build-arg IMAGE_SOURCE=... --build-arg IMAGE_REVISION=... --build-arg IMAGE_VERSION=... .
#                                                  # fills the OCI labels (CI does this)
#   docker build --build-arg JIRCD_VERSION=X.Y.Z .
#                                                  # sets the version the app reports (releases do
#                                                  # this); unset keeps the gradle.properties version
#
# Every base image is pinned by tag@digest, so Dependabot can propose updates for the right tag.

# ---------------------------------------------------------------------------------------------
# build: compiles the server distribution once, on the build machine's own platform. Jars are
# platform-independent, so a multi-arch image never runs Gradle under emulation.
# ---------------------------------------------------------------------------------------------
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk@sha256:83f4a7fc27df94dcc7b8de04d14cbbba8da14406f921e966969c3b09000aa507 AS build
WORKDIR /src
COPY . .
ARG JIRCD_VERSION=
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain ${JIRCD_VERSION:+-Pversion=$JIRCD_VERSION} :jircd-server:installDist

# ---------------------------------------------------------------------------------------------
# jvm: the supported variant. Distroless Java 25 (Temurin) with no shell, running as nonroot.
# ---------------------------------------------------------------------------------------------
FROM gcr.io/distroless/java25-debian13:nonroot@sha256:a90a5fadd78dc37b643da72dca2f744ef67eba625eca7227a5b375b548e6b1c2 AS jvm
# OCI labels (contracts/container-contract.md). CI passes the build args; a local build leaves them
# at "unknown". The variant-specific description is the only label that differs between variants.
ARG IMAGE_SOURCE=unknown
ARG IMAGE_REVISION=unknown
ARG IMAGE_VERSION=unknown
LABEL org.opencontainers.image.source="${IMAGE_SOURCE}" \
      org.opencontainers.image.revision="${IMAGE_REVISION}" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.title="jircd" \
      org.opencontainers.image.description="jircd IRCv3 server, JVM variant (Java 25, distroless)"
COPY --from=build /src/jircd-server/build/install/jircd-server/lib/ /opt/jircd/lib/
WORKDIR /opt/jircd
USER 65532:65532
EXPOSE 6667 6697
# Exec form, so the server is PID 1 and receives HUP (reload) and TERM (graceful shutdown).
# MaxRAMPercentage sizes the heap from the container's memory limit; -UsePerfData keeps the JVM
# from writing /tmp/hsperfdata_*, so the image runs with a read-only root filesystem.
ENTRYPOINT ["/usr/bin/java", "-XX:MaxRAMPercentage=75", "-XX:-UsePerfData", "-cp", "/opt/jircd/lib/*", "net.jircd.server.JircdServerApplication"]
CMD ["/etc/jircd/jircd.yaml"]

# ---------------------------------------------------------------------------------------------
# native-build: compiles the native executable with GraalVM Community. It runs on the target
# platform (no --platform), because the executable is machine code for that platform: an amd64 or
# arm64 image needs a build on that architecture, and a multi-arch build would run it emulated.
# ---------------------------------------------------------------------------------------------
FROM ghcr.io/graalvm/native-image-community:25i5@sha256:2800b5bd11cbb9bf11c92591d05ea37d9a735522bdc0e86827302d998843ad79 AS native-build
WORKDIR /src
COPY . .
ARG JIRCD_VERSION=
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain ${JIRCD_VERSION:+-Pversion=$JIRCD_VERSION} :jircd-server:nativeCompile

# ---------------------------------------------------------------------------------------------
# native: the experimental variant. The executable links glibc dynamically, so it runs on
# distroless base (glibc, CA certificates, tzdata; no shell), as nonroot.
# ---------------------------------------------------------------------------------------------
FROM gcr.io/distroless/base-debian13:nonroot@sha256:9039a88709bcc2bacf5a2faac331d65dd6840f763f225ad9594cc0c67bcd0fba AS native
ARG IMAGE_SOURCE=unknown
ARG IMAGE_REVISION=unknown
ARG IMAGE_VERSION=unknown
LABEL org.opencontainers.image.source="${IMAGE_SOURCE}" \
      org.opencontainers.image.revision="${IMAGE_REVISION}" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.title="jircd" \
      org.opencontainers.image.description="jircd IRCv3 server, experimental native-executable variant (GraalVM, distroless)"
COPY --from=native-build /src/jircd-server/build/native/nativeCompile/jircd /opt/jircd/jircd
WORKDIR /opt/jircd
USER 65532:65532
EXPOSE 6667 6697
# Exec form, so the server is PID 1 and receives HUP (reload) and TERM (graceful shutdown).
ENTRYPOINT ["/opt/jircd/jircd"]
CMD ["/etc/jircd/jircd.yaml"]
