#!/usr/bin/env python3
"""Decode an InfiniTime BLE trace (components/trace in the InfiniTime fork).

Input: /trace.bin, or a text file of hex strings as read out of characteristic
00080003 with nRF Connect (one or more reads, whitespace/dashes/newlines
ignored), or hex on stdin. The stream is: 'ITRC', uint16 record count, uint16
record size, then 12-byte records {uint32 tick, uint8 type, uint8 a, uint16 b,
uint16 c, uint16 d}, little-endian, oldest first.

Usage: decode_trace.py [file]
"""
import re
import struct
import sys

TYPES = {
    1: ("ATT_ERROR_TX", lambda a, b, c, d: f"opcode=0x{a:02x} handle=0x{b:04x} error=0x{c:02x} conn={d}"),
    2: ("SUBSCRIBE", lambda a, b, c, d: f"reason={a} handle=0x{b:04x} notify={c & 1} indicate={(c >> 1) & 1}"),
    3: ("ANNOUNCE", lambda a, b, c, d: {1: "boot", 2: "subscribe-triggered"}.get(a, str(a))),
    4: ("GAP", lambda a, b, c, d: {1: f"connect status={b} conn={c}", 2: f"disconnect reason={b}", 3: f"enc-change status={b}"}.get(a, f"event={a} b={b}")),
    5: ("BOND", lambda a, b, c, d: {1: f"persist cccds={b}", 2: f"restore cccds={b}"}.get(a, f"a={a} b={b}")),
    6: ("DFU", lambda a, b, c, d: f"gatt-op={a} handle=0x{b:04x}"),
    7: ("REVISION_READ", lambda a, b, c, d: f"served=0x{b:04x}"),
}


def read_bytes(source: str) -> bytes:
    data = open(source, "rb").read() if source else sys.stdin.buffer.read()
    if data[:4] == b"ITRC":
        return data
    text = data.decode(errors="replace")
    cleaned = re.sub(r"[^0-9a-fA-F]", "", text)
    return bytes.fromhex(cleaned)


def main() -> None:
    blob = read_bytes(sys.argv[1] if len(sys.argv) > 1 else "")
    if blob[:4] != b"ITRC":
        sys.exit("no ITRC header found (did the first read get captured?)")
    count, record_size = struct.unpack_from("<HH", blob, 4)
    print(f"{count} records (record size {record_size})")
    previous_tick = None
    for index in range(count):
        offset = 8 + index * record_size
        if offset + record_size > len(blob):
            print(f"-- truncated after {index} records --")
            break
        tick, rtype, a, b, c, d = struct.unpack_from("<IBBHHH", blob, offset)
        delta = "" if previous_tick is None else f" (+{tick - previous_tick})"
        previous_tick = tick
        name, fmt = TYPES.get(rtype, (f"TYPE_{rtype}", lambda a, b, c, d: f"a={a} b={b} c={c} d={d}"))
        print(f"[{tick:>10}{delta:>9}] {name:14} {fmt(a, b, c, d)}")


if __name__ == "__main__":
    main()
