# Single toolchain image for building the Wazeology Installer app (which builds Waze with Wazeology on the phone).
# Everything the scripts need runs inside this image; the host only needs Docker.
#
#   apktool  baksmali/smali (host-side patch asset generation)
#   apkeep   (fetch the pinned Waze bundle)
#   javac  d8  aapt2  zipalign  apksigner  zip  python3
#   apksig  (pinned jar: dexed into the installer app so it signs the patched Waze on the phone,
#            and put on the host-JVM test classpath)
#
# All versions are pinned for reproducible builds.
FROM eclipse-temurin:17-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        wget unzip zip python3 git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# --- Android SDK (cmdline-tools -> build-tools + platform) ---
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/build-tools/34.0.0

ARG CMDLINE_TOOLS=commandlinetools-linux-11076708_latest.zip
ARG ANDROID_PLATFORM=android-34
ARG ANDROID_BUILD_TOOLS=34.0.0
RUN mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools \
    && wget -q https://dl.google.com/android/repository/${CMDLINE_TOOLS} -O /tmp/cmdtools.zip \
    && unzip -q /tmp/cmdtools.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools \
    && mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest \
    && rm /tmp/cmdtools.zip \
    && yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install \
        "platforms;${ANDROID_PLATFORM}" \
        "build-tools;${ANDROID_BUILD_TOOLS}" > /dev/null

# --- Apktool (pinned jar + wrapper): host-side only ---
# Decompiles the pinned base.apk for smali + manifest editing and reassembles it once, at installer
# build time, to produce the patched classes5.dex/classes6.dex and the binary AndroidManifest.xml
# that ship inside the installer app as assets. Never runs on the phone.
ARG APKTOOL_VERSION=2.10.0
RUN wget -q https://github.com/iBotPeaches/Apktool/releases/download/v${APKTOOL_VERSION}/apktool_${APKTOOL_VERSION}.jar \
        -O /opt/apktool.jar \
    && printf '#!/bin/sh\nexec java -jar /opt/apktool.jar "$@"\n' > /usr/local/bin/apktool \
    && chmod +x /usr/local/bin/apktool

# --- apkeep (pinned prebuilt EFF binary): fetches the pinned Waze bundle (apk-pure default) ---
ARG APKEEP_VERSION=1.0.0
RUN wget -q https://github.com/EFForg/apkeep/releases/download/${APKEEP_VERSION}/apkeep-x86_64-unknown-linux-gnu \
        -O /usr/local/bin/apkeep \
    && chmod +x /usr/local/bin/apkeep

# --- apksig (pinned jar): APK signing ---
# Runs in two places: dexed into the installer app (signs the patched Waze on the phone) and on the
# host-JVM test classpath (signs the test graft before apksigner verifies it).
ARG APKSIG_VERSION=8.7.3
ARG APKSIG_SHA256=c070ed1394629d74641aa0906f60b2ffa1ee77e6366a1f93437f59717b1aeb89
RUN wget -q https://dl.google.com/android/maven2/com/android/tools/build/apksig/${APKSIG_VERSION}/apksig-${APKSIG_VERSION}.jar \
        -O /opt/apksig.jar \
    && echo "${APKSIG_SHA256}  /opt/apksig.jar" | sha256sum -c -

WORKDIR /work
