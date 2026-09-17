FROM debian:bookworm-slim

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        adb bash ca-certificates curl python3 python3-qrcode \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/pico-onebot
COPY scripts/picoctl.sh scripts/picoqr.py ./
COPY docker/entrypoint.sh docker/dns_proxy.py docker/qimei-hosts ./
# 若构建上下文中存在 APK（如 CI 构建或本地预置），直接将其内置到容器中
COPY docker/placeholder.txt docker/PicoOnebot.ap[k]* /tmp/
RUN if [ -f /tmp/PicoOnebot.apk ]; then mv /tmp/PicoOnebot.apk /opt/pico-onebot/PicoOnebot.apk; fi \
    && rm -rf /tmp/*
RUN chmod +x picoctl.sh picoqr.py entrypoint.sh dns_proxy.py

ENV PICO_BACKEND=adb \
    PICO_SERIAL=android:5555 \
    PICO_IP=android

ENTRYPOINT ["/opt/pico-onebot/entrypoint.sh"]
CMD ["watch"]
