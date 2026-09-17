FROM debian:bookworm-slim

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        adb bash ca-certificates curl python3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/pico-onebot
COPY linux/picoctl.sh linux/picoqr.py ./
COPY docker/entrypoint.sh docker/dns_proxy.py docker/qimei-hosts ./
RUN chmod +x picoctl.sh picoqr.py entrypoint.sh dns_proxy.py

ENV PICO_BACKEND=adb \
    PICO_SERIAL=android:5555 \
    PICO_IP=android

ENTRYPOINT ["/opt/pico-onebot/entrypoint.sh"]
CMD ["watch"]
