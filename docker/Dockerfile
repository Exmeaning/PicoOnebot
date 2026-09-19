# Global ARGs used across FROM instructions must be declared before the first FROM
ARG REDROID_IMAGE=redroid/redroid:13.0.0-latest

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
COPY docker/qimei-hosts /stage/system/etc/pico-qimei-hosts
RUN mkdir -p /stage/system/etc \
    && printf "127.0.0.1 localhost\n::1 ip6-localhost\n" > /stage/system/etc/hosts \
    && cat /tmp/qimei-hosts >> /stage/system/etc/hosts

# 3. Prepare entrypoint, boot script, diagnostics & init service
COPY docker/pico-entrypoint.sh docker/pico-boot.sh docker/pico-doctor.sh docker/pico-health.sh /stage/system/bin/
COPY docker/pico.rc /stage/system/etc/init/pico.rc
COPY docker/pico-container /stage/system/etc/pico-container
COPY docker/pico-auto-accept-privacy /stage/system/etc/pico-auto-accept-privacy
COPY docker/pico-restart.sh /stage/system/bin/pico-restart.sh
RUN chmod 755 /stage/system/bin/pico-entrypoint.sh \
        /stage/system/bin/pico-boot.sh \
        /stage/system/bin/pico-doctor.sh \
        /stage/system/bin/pico-health.sh \
    && chmod 644 /stage/system/etc/init/pico.rc

# 4. Prepare APK if present in build context
RUN mkdir -p /stage/opt/pico-onebot /stage/tmp
COPY docker/placeholder.txt docker/PicoOnebot.ap[k]* /stage/tmp/
RUN if [ -f /stage/tmp/PicoOnebot.apk ]; then \
        mv /stage/tmp/PicoOnebot.apk /stage/opt/pico-onebot/PicoOnebot.apk; \
    fi && rm -rf /stage/tmp

# Stage 1b: static BusyBox for the TARGET arch (no --platform override => built under the target platform).
# Android's /system/bin/sh (and every other /system/bin tool) is dynamically linked against
# /system/bin/linker64 -> /apex/com.android.runtime/bin/linker64, and /apex is only populated by /init.
# The ENTRYPOINT runs *before* /init, so it must use a fully static shell or exec fails with
# "exec /system/bin/sh: no such file or directory".
FROM alpine:3.20 AS bbox
RUN apk add --no-cache busybox-static \
    && mkdir -p /stage/pico-bb \
    && cp /bin/busybox.static /stage/pico-bb/busybox \
    && for a in $(/stage/pico-bb/busybox --list); do \
        [ -e "/stage/pico-bb/$a" ] || ln -s busybox "/stage/pico-bb/$a"; \
    done \
    && /stage/pico-bb/sh -c 'echo "busybox ok: $(/stage/pico-bb/busybox | head -1)"'

# Stage 2: Final All-in-One Image
FROM ${REDROID_IMAGE}

# Copy prepared Android system layer & PicoOnebot runtime
COPY --from=builder /stage/system/ /system/
COPY --from=bbox /stage/pico-bb/ /system/bin/pico-bb/
COPY docker/pico-network.sh /system/bin/pico-network.sh
COPY --from=builder /stage/opt/ /opt/

# Expose WebUI (6099), OneBot v11 (3001), ADB (5555)
EXPOSE 6099 3001 5555

# Entrypoint: Binder checks, kmsg relay to stdout, and exec /init (PID 1).
# Must run on the static BusyBox: Android's own sh cannot start until /init has mounted /apex.
ENTRYPOINT ["/system/bin/pico-bb/busybox", "sh", "/system/bin/pico-entrypoint.sh"]

# Health check: verifies Android boot completion and WebUI port (6099) readiness
HEALTHCHECK --start-period=360s --interval=30s --timeout=15s --retries=3 \
    CMD ["/system/bin/pico-bb/busybox", "sh", "/system/bin/pico-health.sh"]

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
