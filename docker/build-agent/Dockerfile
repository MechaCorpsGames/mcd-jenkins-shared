# MechaCorps CI Build Agent
# Single image with all build tools for server, client, and crash-reporting pipelines.
# Intended for use with Jenkins Pipeline `agent { docker { image ... } }`.

FROM debian:bookworm

# ── Layer 1: System packages, JDK 17, MinGW ──────────────────────────────────
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential gcc g++ cmake make \
        git openssh-client curl wget zip unzip zstd ca-certificates gnupg lsb-release \
        openjdk-17-jdk-headless \
        mingw-w64 \
        python3 python3-pytest python-is-python3 rsync sudo \
        zlib1g-dev libcurl4-openssl-dev \
    && rm -rf /var/lib/apt/lists/*
# python-is-python3: several MCDClient Makefile test recipes invoke bare
# `python` (make test-tutorial, test-smoke, test-e2e). Debian's python3
# package ships no such binary, so those targets die with "python: command
# not found" without this shim package.

# Pre-create Crypt32 symlink for MinGW OpenSSL builds (avoids sudo at build time)
RUN if [ -f /usr/x86_64-w64-mingw32/lib/libcrypt32.a ] && \
       [ ! -f /usr/x86_64-w64-mingw32/lib/libCrypt32.a ]; then \
        ln -sf libcrypt32.a /usr/x86_64-w64-mingw32/lib/libCrypt32.a; \
    fi

# ── Layer 2: Android SDK + NDK ────────────────────────────────────────────────
ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk

RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools && \
    cd /tmp && \
    curl -fsSL -o cmdline-tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" && \
    unzip -q cmdline-tools.zip && \
    mv cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest && \
    rm cmdline-tools.zip

RUN yes | ${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null 2>&1 && \
    ${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager \
        "platform-tools" \
        "platforms;android-34" \
        "platforms;android-35" \
        "build-tools;34.0.0" \
        "build-tools;35.0.1" \
        "ndk;26.1.10909125"
# android-35 + build-tools 35.0.1: the Godot 4.6 Android gradle build template
# targets SDK 35; gradle cannot auto-install them at runtime (/opt/android-sdk is
# not writable by the build user), so they must be present in the image.

ENV ANDROID_NDK_HOME=${ANDROID_SDK_ROOT}/ndk/26.1.10909125

# ── Layer 3: Go 1.24 ─────────────────────────────────────────────────────────
RUN curl -fsSL "https://go.dev/dl/go1.24.0.linux-amd64.tar.gz" | tar -C /usr/local -xz

ENV GOROOT=/usr/local/go

# ── Layer 4: Godot 4.6-stable headless ────────────────────────────────────────
ARG GODOT_VERSION=4.6-stable
RUN curl -fsSL -o /tmp/godot.zip \
        "https://github.com/godotengine/godot/releases/download/${GODOT_VERSION}/Godot_v${GODOT_VERSION}_linux.x86_64.zip" && \
    unzip -q /tmp/godot.zip -d /tmp && \
    mv /tmp/Godot_v${GODOT_VERSION}_linux.x86_64 /usr/local/bin/godot && \
    chmod +x /usr/local/bin/godot && \
    rm /tmp/godot.zip

# Godot needs a home directory for editor data even in headless mode
RUN mkdir -p /home/jenkins/.local/share/godot /home/jenkins/.config/godot

# ── Layer 5: Docker CLI + docker compose v2 + docker-compose v1 + sentry-cli ──
RUN install -m 0755 -d /etc/apt/keyrings && \
    curl -fsSL https://download.docker.com/linux/debian/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg && \
    echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian bookworm stable" \
        > /etc/apt/sources.list.d/docker.list && \
    apt-get update && apt-get install -y --no-install-recommends \
        docker-ce-cli \
        docker-buildx-plugin \
        docker-compose-plugin \
    && rm -rf /var/lib/apt/lists/*

# docker-compose v1 standalone binary kept for the legacy
# `docker-compose -p ...` invocations in mcdAppServicesPipeline's existing
# Deploy Auth/AccountService/AuctionHouse stages. New code should call
# `docker compose ...` (v2 plugin installed above) — required by
# scripts/docker_dev.py and the docker-smoke pytest suite.
RUN curl -fsSL "https://github.com/docker/compose/releases/download/1.29.2/docker-compose-linux-x86_64" \
        -o /usr/local/bin/docker-compose && \
    chmod +x /usr/local/bin/docker-compose

RUN curl -fsSL https://sentry.io/get-cli/ | bash || true

# ── Layer 5b: SteamCMD ───────────────────────────────────────────────────────
RUN dpkg --add-architecture i386 && \
    apt-get update && apt-get install -y --no-install-recommends \
        lib32gcc-s1 \
    && rm -rf /var/lib/apt/lists/* && \
    mkdir -p /opt/steamcmd && \
    curl -fsSL "https://steamcdn-a.akamaihd.net/client/installer/steamcmd_linux.tar.gz" \
        | tar -xz -C /opt/steamcmd && \
    /opt/steamcmd/steamcmd.sh +quit || true

# ── Layer 5c: fastlane (Google Play upload via `supply`) ──────────────────────
# Used by mcdPlayUploadPipeline to push AABs to Play tracks through the Android
# Publisher API. build-essential (layer 1) lets the native gems compile.
# fastlane aborts at startup unless the locale is UTF-8.
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8
RUN apt-get update && apt-get install -y --no-install-recommends \
        ruby ruby-dev libffi-dev \
    && rm -rf /var/lib/apt/lists/* \
    && gem install --no-document fastlane \
    && fastlane --version
# NOTE: fastlane is intentionally unpinned for the first image build. Once a build
# is green, pin it here (e.g. `gem install fastlane -v 2.x.y`) for reproducibility.

# ── Layer 6: Jenkins user (UID/GID matching host) ─────────────────────────────
RUN groupadd -g 112 jenkins && \
    useradd -u 103 -g 112 -m -d /home/jenkins -s /bin/bash jenkins && \
    echo 'jenkins ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/jenkins && \
    chown -R jenkins:jenkins /home/jenkins

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
    PATH=/opt/steamcmd:/usr/local/go/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:${PATH}

USER jenkins
WORKDIR /home/jenkins
