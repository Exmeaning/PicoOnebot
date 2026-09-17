#!/usr/bin/env python3
"""Small DNS override for QQ endpoints affected by fake-IP DNS proxies."""

import ipaddress
import os
import socket
import struct


HOSTS = {}
with open(os.environ.get("PICO_DNS_HOSTS", "/opt/pico-onebot/qimei-hosts"), encoding="ascii") as hosts_file:
    for line in hosts_file:
        fields = line.split()
        if len(fields) == 2 and not fields[0].startswith(("127.", "::")):
            HOSTS[fields[1].lower()] = fields[0]

UPSTREAM = os.environ.get("PICO_DNS_UPSTREAM", "223.5.5.5")


def read_name(packet, offset):
    labels = []
    while True:
        length = packet[offset]
        offset += 1
        if length == 0:
            return ".".join(labels), offset
        labels.append(packet[offset:offset + length].decode("ascii"))
        offset += length


def forward(packet):
    upstream = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    upstream.settimeout(3)
    try:
        upstream.sendto(packet, (UPSTREAM, 53))
        return upstream.recvfrom(4096)[0]
    finally:
        upstream.close()


def reply(packet):
    if len(packet) < 12:
        return None
    name, offset = read_name(packet, 12)
    if offset + 4 > len(packet):
        return None
    qtype, qclass = struct.unpack("!HH", packet[offset:offset + 4])
    address = HOSTS.get(name.lower())
    if not address:
        return forward(packet)

    question = packet[12:offset + 4]
    answers = []
    if qclass == 1 and qtype == 1:
        answers.append(ipaddress.ip_address(address).packed)
    header = packet[:2] + struct.pack("!HHHHH", 0x8180, 1, len(answers), 0, 0)
    records = b"".join(
        b"\xc0\x0c" + struct.pack("!HHIH", 1, 1, 300, len(data)) + data
        for data in answers
    )
    return header + question + records


sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("0.0.0.0", 53))
print(f"QQ DNS override listening on UDP :53, upstream={UPSTREAM}", flush=True)
while True:
    packet, peer = sock.recvfrom(4096)
    try:
        response = reply(packet)
        if response:
            sock.sendto(response, peer)
    except (IndexError, UnicodeDecodeError, OSError, struct.error) as error:
        print(f"DNS request failed: {error}", flush=True)
