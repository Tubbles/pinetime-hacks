#!/usr/bin/env python3
"""Pull one file off an InfiniTime watch over the BLE file-transfer service.

Reads the file through the Adafruit-style protocol the watch speaks on
adaf0200-4669-6c65-5472-616e73666572 (InfiniTime doc/BLEFS.md): a READ
header (0x10) with the path, then READ_PACING (0x12) requests until the
whole file has arrived in READ_DATA (0x11) notifications.

Needs a venv with bleak, e.g.
    uv venv tmp/venv && uv pip install --python tmp/venv/bin/python bleak
and an idle watch: disconnect the phone (Gadgetbridge, nRF Connect) first,
the watch accepts one central at a time.

Usage: pull_watch_file.py MAC REMOTE_PATH LOCAL_PATH
   e.g. pull_watch_file.py DB:8F:A2:1A:A1:DB /trace.bin tmp/trace.bin
"""
import asyncio
import struct
import sys

from bleak import BleakClient

TRANSFER_UUID = "adaf0200-4669-6c65-5472-616e73666572"
VERSION_UUID = "adaf0100-4669-6c65-5472-616e73666572"
COMMAND_READ = 0x10
COMMAND_READ_DATA = 0x11
COMMAND_READ_PACING = 0x12
RESPONSE_HEADER = struct.Struct("<BbxxIII")  # command, status, pad, offset, total, chunk length
# The firmware caps a chunk by the file size but not by the MTU, and a
# notification longer than MTU - 3 is cut off, so stay well under the
# watch's 256-byte ATT MTU (16 header bytes plus the chunk).
CHUNK_SIZE = 200


def read_header(path: bytes, offset: int, chunk_size: int) -> bytes:
    return struct.pack("<BxHII", COMMAND_READ, len(path), offset, chunk_size) + path


def read_pacing(offset: int, chunk_size: int) -> bytes:
    return struct.pack("<BBxxII", COMMAND_READ_PACING, 0x01, offset, chunk_size)


async def pull(address: str, remote_path: str, local_path: str) -> None:
    responses: asyncio.Queue[bytes] = asyncio.Queue()

    def on_notify(_characteristic, data: bytearray) -> None:
        responses.put_nowait(bytes(data))

    async with BleakClient(address, timeout=30.0) as client:
        version = int.from_bytes(await client.read_gatt_char(VERSION_UUID), "little")
        print(f"connected, file-transfer protocol version {version}")
        await client.start_notify(TRANSFER_UUID, on_notify)
        await client.write_gatt_char(TRANSFER_UUID, read_header(remote_path.encode(), 0, CHUNK_SIZE), response=True)

        content = bytearray()
        while True:
            response = await asyncio.wait_for(responses.get(), timeout=10.0)
            command, status, offset, total, chunk_length = RESPONSE_HEADER.unpack_from(response)
            if command != COMMAND_READ_DATA:
                sys.exit(f"unexpected response command 0x{command:02x}")
            if status != 0x01:
                sys.exit(f"watch refused the read, status {status} (a negative value is a littlefs error, -2 is no such file)")
            chunk = response[RESPONSE_HEADER.size:RESPONSE_HEADER.size + chunk_length]
            if len(chunk) != chunk_length:
                sys.exit(f"chunk truncated: got {len(chunk)} of {chunk_length} bytes, lower CHUNK_SIZE")
            content += chunk
            print(f"{len(content)}/{total} bytes")
            if len(content) >= total or chunk_length == 0:
                break
            await client.write_gatt_char(TRANSFER_UUID, read_pacing(len(content), CHUNK_SIZE), response=True)

        await client.stop_notify(TRANSFER_UUID)

    with open(local_path, "wb") as output:
        output.write(content)
    print(f"wrote {len(content)} bytes to {local_path}")


def main() -> None:
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    asyncio.run(pull(sys.argv[1], sys.argv[2], sys.argv[3]))


if __name__ == "__main__":
    main()
