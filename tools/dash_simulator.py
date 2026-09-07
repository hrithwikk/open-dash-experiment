#!/usr/bin/env python3
"""
dash_simulator.py — a software stand-in for the Royal Enfield Tripper Dash,
for testing OpenDash's control-plane/auth handshake and H.264/RTP video
pipeline without the physical bike.

WHY THIS EXISTS
---------------
OpenDash's dash-streaming core assumes it's talking to real Tripper dash
hardware on a specific subnet (192.168.1.0/24, dash at .1) after joining
the dash's own WiFi hotspot (SSID prefix "RE_"). This script plays the
"dash" side of that conversation well enough to validate:
  1. The RSA/AES auth handshake (K1G protocol, ported from better-dash).
  2. That the phone is emitting well-formed, decodable H.264/RTP frames.

It does NOT reproduce the dash's on-screen rendering, joystick input, or
firmware quirks — it is a protocol/plumbing test, not a hardware replica.
Reaching "AUTH CONFIRMED" here is a real signal the handshake works; it is
not proof firmware 11.63 on the actual bike will behave identically.

NETWORK SETUP (do this BEFORE running the script)
---------------------------------------------------
OpenDash hardcodes the dash's IP as 192.168.1.1 and broadcasts control
packets to 192.168.1.255 — this is frozen protocol behavior, not
configurable from Settings. So whatever machine runs this script MUST
have the IP 192.168.1.1 on the WiFi network your phone joins, and that
network's SSID must start with "RE_" (the app discovers dashes by prefix)
with WPA2 password "12345678" (OpenDash's default) unless you've changed
DashConfig's defaults in Settings.

Two ways to get there:

  A) Spare home WiFi router (easiest, no Linux needed):
     - Rename its WiFi network to something starting with "RE_"
       (e.g. "RE_SIMULATOR"), password "12345678".
     - In the router's LAN settings, change the ROUTER'S OWN IP away
       from .1 (e.g. to 192.168.1.254) — keep its DHCP pool elsewhere
       (e.g. 192.168.1.100-200) so it never hands out .1 to a client.
     - On the machine running this script, set a STATIC IP of
       192.168.1.1/24 on the adapter connected to that router,
       gateway 192.168.1.254.
     - Join your phone to the router's WiFi normally.

  B) Linux box / Raspberry Pi as its own access point (hostapd + dnsmasq):
     - Configure the AP interface itself with static IP 192.168.1.1/24.
     - hostapd SSID starting with "RE_", WPA2 passphrase "12345678".
     - dnsmasq DHCP range e.g. 192.168.1.50-192.168.1.150.
     - Run this script on the same Pi/box.

Once your phone joins that network and OpenDash's Dash screen shows
"Connect", it should discover the SSID by prefix and proceed.

USAGE
-----
    pip install cryptography
    python dash_simulator.py                          # logs only (stderr)
    python dash_simulator.py --dump-video out.h264     # also save raw H.264
    python dash_simulator.py --pipe-video | ffplay -f h264 -i -   # live view

All logging goes to stderr so --pipe-video can push clean H.264 bytes to
stdout for piping straight into ffplay/ffmpeg.
"""

import argparse
import select
import socket
import struct
import sys

from cryptography.hazmat.primitives.asymmetric import padding, rsa

CTRL_PORT = 2000     # app's control TX target — we bind here to receive it
APP_RX_PORT = 2002   # app's control RX — we send our replies here
RTP_PORT = 5000      # app sends H.264/RTP here

MAGIC = b"K1G "


def log(msg):
    print(f"[dash-sim] {msg}", file=sys.stderr, flush=True)


def build_dash_packet(tlvs):
    """Build a dash->app packet: outer_len(2) seg_count(2) ignored(4) TLVs.

    Mirrors K1GPacket's INCOMING format (what DashSession.parseIncoming
    expects from the real dash)."""
    body = bytearray()
    for (t, s, val) in tlvs:
        body += bytes([t & 0xFF, s & 0xFF])
        body += struct.pack(">H", len(val))
        body += val
    seg_count = 1 + len(tlvs)
    pkt = bytearray(struct.pack(">HH", 0, seg_count)) + b"\x00" * 4 + body
    pkt[0:2] = struct.pack(">H", len(pkt))
    return bytes(pkt)


def parse_app_packet(data):
    """Parse an app->dash packet (K1GPacket.build format) into a list of
    (type, sub, value) TLVs. Header is 17 bytes: outer_len(2) seg_count(2)
    zeros(4) flags(2) const(2) magic(4) seq(1), then TLVs."""
    if len(data) < 17 or data[12:16] != MAGIC:
        return []
    tlvs = []
    i = 17
    while i + 4 <= len(data):
        t, s = data[i], data[i + 1]
        ln = struct.unpack(">H", data[i + 2:i + 4])[0]
        i += 4
        val = data[i:i + ln]
        i += ln
        tlvs.append((t, s, val))
    return tlvs


class ClientState:
    def __init__(self):
        self.pubkey_sent = False
        self.authenticated = False
        self.aes_key = None


def rsa_pub_bytes(pub_numbers):
    n, e = pub_numbers.n, pub_numbers.e
    n_bytes = n.to_bytes((n.bit_length() + 7) // 8, "big")
    e_bytes = e.to_bytes((e.bit_length() + 7) // 8, "big")
    return n_bytes, e_bytes


class NalReassembler:
    """Turns incoming RTP/H.264 (FU-A + single-NAL, per RtpPacketizer) back
    into Annex-B access units, so we can confirm the stream is well-formed
    and optionally hand it to ffplay."""

    def __init__(self, on_access_unit):
        self.on_access_unit = on_access_unit
        self.fu_buf = None
        self.au = bytearray()

    def feed(self, rtp_packet):
        if len(rtp_packet) < 12:
            return
        marker = bool(rtp_packet[1] & 0x80)
        payload = rtp_packet[12:]
        if not payload:
            return
        nal_hdr = payload[0]
        nal_type = nal_hdr & 0x1F
        if nal_type == 28:  # FU-A fragment
            fu_header = payload[1]
            start, end = bool(fu_header & 0x80), bool(fu_header & 0x40)
            orig_type = fu_header & 0x1F
            frag = payload[2:]
            if start:
                fu_ind = (nal_hdr & 0xE0) | orig_type
                self.fu_buf = bytearray([fu_ind]) + bytearray(frag)
            elif self.fu_buf is not None:
                self.fu_buf += frag
            if end and self.fu_buf is not None:
                self.au += b"\x00\x00\x00\x01" + bytes(self.fu_buf)
                self.fu_buf = None
        else:
            self.au += b"\x00\x00\x00\x01" + bytes(payload)
        if marker and self.au:
            self.on_access_unit(bytes(self.au))
            self.au = bytearray()


def handle_tlv(ctrl_sock, ip, st, t, s, val, n_bytes, e_bytes, priv):
    # 08 04: "request auth" (q3c.e) — send our RSA pubkey.
    if t == 0x08 and s == 0x04 and not st.pubkey_sent:
        st.pubkey_sent = True
        log(f"{ip}: auth request seen — sending RSA pubkey (modulus/exponent)")
        ctrl_sock.sendto(build_dash_packet([(0x07, 0x00, n_bytes)]), (ip, APP_RX_PORT))
        ctrl_sock.sendto(build_dash_packet([(0x07, 0x03, e_bytes)]), (ip, APP_RX_PORT))
        return

    # 08 00, len 128: q3c.d — RSA(ssid || AES-256 key).
    if t == 0x08 and s == 0x00 and len(val) == 128:
        try:
            plain = priv.decrypt(bytes(val), padding.PKCS1v15())
        except Exception as e:
            log(f"{ip}: FAILED to decrypt session key packet: {e}")
            return
        aes_key, ssid = plain[-32:], plain[:-32]
        st.aes_key = aes_key
        ssid_text = ssid.decode("utf-8", errors="replace")
        log(f"{ip}: decrypted session key OK — app thinks SSID='{ssid_text}', AES key {len(aes_key)}B")
        st.authenticated = True
        ctrl_sock.sendto(build_dash_packet([(0x07, 0x01, bytes([0x01]))]), (ip, APP_RX_PORT))
        log(f"{ip}: sent 07 01 01 — AUTH CONFIRMED. App should reach READY shortly.")
        return

    # Catch-all: log everything else so you can watch the conversation,
    # same spirit as DashSession's own logging on the phone side.
    log(f"{ip}: TLV type=0x{t:02X} sub=0x{s:02X} ({len(val)}B) = {val.hex()}")


def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("--dump-video", metavar="FILE", help="Append reconstructed Annex-B H.264 to this file")
    ap.add_argument("--pipe-video", action="store_true", help="Write Annex-B H.264 to stdout (pipe into ffplay)")
    args = ap.parse_args()

    log("Generating RSA-1024 keypair (stands in for the dash's factory key)...")
    priv = rsa.generate_private_key(public_exponent=65537, key_size=1024)
    n_bytes, e_bytes = rsa_pub_bytes(priv.public_key().public_numbers())

    ctrl_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    ctrl_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    ctrl_sock.bind(("0.0.0.0", CTRL_PORT))

    rtp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    rtp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    rtp_sock.bind(("0.0.0.0", RTP_PORT))

    log(f"Listening for control packets on UDP :{CTRL_PORT} and RTP on :{RTP_PORT}")
    log("Make sure THIS machine's IP is 192.168.1.1 on the network your phone joined.")

    video_file = open(args.dump_video, "ab") if args.dump_video else None
    frame_count = 0

    def on_access_unit(au_bytes):
        nonlocal frame_count
        frame_count += 1
        if frame_count % 10 == 1:
            log(f"Reassembled access unit #{frame_count} ({len(au_bytes)} bytes)")
        if video_file:
            video_file.write(au_bytes)
            video_file.flush()
        if args.pipe_video:
            sys.stdout.buffer.write(au_bytes)
            sys.stdout.buffer.flush()

    reassembler = NalReassembler(on_access_unit)
    clients = {}

    try:
        while True:
            readable, _, _ = select.select([ctrl_sock, rtp_sock], [], [])
            for sock in readable:
                if sock is ctrl_sock:
                    data, addr = ctrl_sock.recvfrom(65535)
                    ip = addr[0]
                    st = clients.setdefault(ip, ClientState())
                    for (t, s, val) in parse_app_packet(data):
                        handle_tlv(ctrl_sock, ip, st, t, s, val, n_bytes, e_bytes, priv)
                elif sock is rtp_sock:
                    data, _addr = rtp_sock.recvfrom(65535)
                    reassembler.feed(data)
    except KeyboardInterrupt:
        log("Stopping.")
    finally:
        if video_file:
            video_file.close()


if __name__ == "__main__":
    main()
