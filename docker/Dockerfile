# Multi-stage build: stage 1 prepares Google libndk translation & runtime scripts
FROM --platform=$BUILDPLATFORM alpine:3.20 AS builder
ARG TARGETARCH

RUN apk add --no-cache curl unzip

WORKDIR /tmp

# 1. Download and verify Google libndk translation layer (Android 13) for x86_64 (amd64)
ARG NDK_HASH=68734c52556d3d7a6db34c603dd9276915c29f2f
ARG NDK_MD5=0b2207c490fcb400aa5c87fcf0d52d38
RUN mkdir -p /stage/system \
    && if [ "$TARGETARCH" = "amd64" ] || [ -z "$TARGETARCH" ]; then \
        curl -fL -o ndk.zip "https://github.com/supremegamers/vendor_google_proprietary_ndk_translation-prebuilt/archive/${NDK_HASH}.zip" \
        && echo "${NDK_MD5}  ndk.zip" | md5sum -c - \
        && unzip -q ndk.zip \
        && cp -r vendor_google_proprietary_ndk_translation-prebuilt-*/prebuilts/* /stage/system/; \
    fi

# 2. Prepare /system/etc/hosts with QIMEI overrides
COPY docker/qimei-hosts /tmp/qimei-hosts
RUN mkdir -p /stage/system/etc \
    && printf "127.0.0.1 localhost\n::1 ip6-localhost\n" > /stage/system/etc/hosts \
    && cat /tmp/qimei-hosts >> /stage/system/etc/hosts

# 3. Prepare boot script & init service
COPY docker/pico-boot.sh /stage/system/bin/pico-boot.sh
COPY docker/pico.rc /stage/system/etc/init/pico.rc
RUN chmod 755 /stage/system/bin/pico-boot.sh \
    && chmod 644 /stage/system/etc/init/pico.rc

# 4. Prepare APK if present in build context
RUN mkdir -p /stage/opt/pico-onebot /stage/tmp
COPY docker/placeholder.txt docker/PicoOnebot.ap[k]* /stage/tmp/
RUN if [ -f /stage/tmp/PicoOnebot.apk ]; then \
        mv /stage/tmp/PicoOnebot.apk /stage/opt/pico-onebot/PicoOnebot.apk; \
    fi && rm -rf /stage/tmp

# Stage 2: Final All-in-One Image
ARG REDROID_IMAGE=redroid/redroid:13.0.0-latest
FROM ${REDROID_IMAGE}

# Copy prepared Android system layer & PicoOnebot runtime
COPY --from=builder /stage/system/ /system/
COPY --from=builder /stage/opt/ /opt/

# Expose WebUI (6099), OneBot v11 (3001), ADB (5555)
EXPOSE 6099 3001 5555

# Default Redroid boot arguments for ARM translation and display settings
CMD [ \
    "androidboot.redroid_width=720", \
    "androidboot.redroid_height=1256", \
    "androidboot.redroid_dpi=320", \
    "androidboot.redroid_gpu_mode=guest", \
    "ro.product.cpu.abilist=x86_64,arm64-v8a,x86,armeabi-v7a,armeabi", \
    "ro.product.cpu.abilist32=x86,armeabi-v7a,armeabi", \
    "ro.product.cpu.abilist64=x86_64,arm64-v8a", \
    "ro.dalvik.vm.isa.arm=x86", \
    "ro.dalvik.vm.isa.arm64=x86_64", \
    "ro.enable.native.bridge.exec=1", \
    "ro.dalvik.vm.native.bridge=libndk_translation.so", \
    "ro.ndk_translation.version=0.2.3" \
]
