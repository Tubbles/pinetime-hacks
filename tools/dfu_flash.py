#!/usr/bin/env python3
"""Flash an InfiniTime DFU zip over BLE from a laptop with a Bluetooth adapter.

Speaks the Nordic legacy DFU protocol the way the watch's DfuService.cpp
implements it, on service 00001530-1212-efde-1523-785feabcd123:

  0. wake the watch with a one-byte read through the file-transfer service:
     the DFU start request writes the event log from the Bluetooth task
     before it wakes a sleeping watch, whose SPI flash is powered down then,
     so on a sleeping watch that write never returns, the request is never
     answered (BlueZ reports ATT error 0x0e) and the watchdog resets the
     watch. The file-transfer service wakes the watch before it touches the
     flash, and once the DFU has started it keeps the watch awake by itself.
  1. subscribe to control point (1531) notifications
  2. control: StartDFU, application; packet: the three image sizes
     (softdevice 0, bootloader 0, application N) -> the watch erases the
     secondary slot and answers 10 01 01
  3. control: InitDFU 02 00; packet: the init packet (.dat, 14 bytes, ends
     with the CRC-16 of the image); control: 02 01 -> 10 02 01
  4. control: 08 <prn> (a receipt notification every <prn> packets), then 03
  5. packet: the image in 20-byte writes without response (the watch asserts
     on anything longer), pausing for 11 <bytes received> every <prn>
     packets -> 10 03 01 once every byte is in
  6. control: 04 validate -> 10 04 01 (10 04 05 is a CRC mismatch)
  7. control: 05 activate -> the watch reboots into the new image, which
     stays unvalidated until Settings -> Firmware -> Validate on the watch

The laptop never pairs, and NimBLE persists CCCD subscriptions for bonded
peers only, so the full bond store that makes the phone's flashes fail
(TODO.md, DFU reliability) is never touched from here.

Needs a venv with bleak, e.g.
    uv venv tmp/venv && uv pip install --python tmp/venv/bin/python bleak
and an idle watch: disconnect the phone (Gadgetbridge, nRF Connect) first,
the watch accepts one central at a time.

Usage: dfu_flash.py MAC DFU_ZIP [--prn N] [--check]
   e.g. dfu_flash.py DB:8F:A2:1A:A1:DB tmp/pinetime-mcuboot-app-dfu-1.16.1.zip
--check parses the zip, verifies its CRC and exits without touching Bluetooth.
"""
import argparse
import asyncio
import json
import struct
import sys
import time
import zipfile

CONTROL_POINT_UUID = "00001531-1212-efde-1523-785feabcd123"
PACKET_UUID = "00001532-1212-efde-1523-785feabcd123"
REVISION_UUID = "00001534-1212-efde-1523-785feabcd123"
TRANSFER_UUID = "adaf0200-4669-6c65-5472-616e73666572"
COMMAND_FILE_READ = 0x10
# Any path wakes the watch: a missing file is answered with an error status.
WAKE_PATH = b"/events.log"

OPCODE_START_DFU = 0x01
OPCODE_INIT_DFU_PARAMETERS = 0x02
OPCODE_RECEIVE_FIRMWARE_IMAGE = 0x03
OPCODE_VALIDATE_FIRMWARE = 0x04
OPCODE_ACTIVATE_IMAGE_AND_RESET = 0x05
OPCODE_PACKET_RECEIPT_NOTIFICATION_REQUEST = 0x08
OPCODE_RESPONSE = 0x10
OPCODE_PACKET_RECEIPT_NOTIFICATION = 0x11
IMAGE_TYPE_APPLICATION = 0x04
ERROR_NO_ERROR = 0x01
ERROR_NAMES = {0x02: "invalid state", 0x03: "not supported", 0x04: "data size exceeds limits", 0x05: "CRC error", 0x06: "operation failed"}

# DfuService::DfuImage::Init refuses any other chunk size and Append asserts on a longer write.
PACKET_SIZE = 20


def crc16(data: bytes) -> int:
    """The watch's DfuImage::ComputeCrc (Nordic crc16_compute, seed 0xFFFF)."""
    crc = 0xFFFF
    for byte in data:
        crc = ((crc >> 8) & 0xFF) | ((crc << 8) & 0xFFFF)
        crc ^= byte
        crc ^= (crc & 0xFF) >> 4
        crc ^= (crc << 12) & 0xFFFF
        crc ^= ((crc & 0xFF) << 5) & 0xFFFF
    return crc


def load_package(zip_path: str) -> tuple[bytes, bytes]:
    """The image and its init packet, checked against each other and the manifest."""
    with zipfile.ZipFile(zip_path) as package:
        manifest = json.loads(package.read("manifest.json"))["manifest"]["application"]
        image = package.read(manifest["bin_file"])
        init_packet = package.read(manifest["dat_file"])
    softdevice_count = struct.unpack_from("<H", init_packet, 8)[0]
    packet_crc = struct.unpack_from("<H", init_packet, 10 + 2 * softdevice_count)[0]
    image_crc = crc16(image)
    if packet_crc != image_crc or packet_crc != manifest["init_packet_data"]["firmware_crc16"]:
        sys.exit(f"CRC mismatch: image {image_crc:#06x}, init packet {packet_crc:#06x}, manifest {manifest['init_packet_data']['firmware_crc16']:#06x}")
    print(f"{manifest['bin_file']}: {len(image)} bytes, CRC-16 {image_crc:#06x}, init packet {init_packet.hex()}")
    return image, init_packet


class ControlPoint:
    """The control point's notifications, consumed in order."""

    def __init__(self, client) -> None:
        self.client = client
        self.notifications: asyncio.Queue[bytes] = asyncio.Queue()

    def on_notify(self, _characteristic, data: bytearray) -> None:
        self.notifications.put_nowait(bytes(data))

    async def write(self, *opcode_and_arguments: int) -> None:
        await self.client.write_gatt_char(CONTROL_POINT_UUID, bytes(opcode_and_arguments), response=True)

    async def expect_response(self, opcode: int, timeout: float) -> None:
        try:
            notification = await asyncio.wait_for(self.notifications.get(), timeout=timeout)
        except asyncio.TimeoutError:
            sys.exit(f"no response to opcode {opcode:#04x} within {timeout:.0f} s")
        if len(notification) != 3 or notification[0] != OPCODE_RESPONSE or notification[1] != opcode:
            sys.exit(f"unexpected notification {notification.hex()} while waiting for a response to opcode {opcode:#04x}")
        if notification[2] != ERROR_NO_ERROR:
            sys.exit(f"the watch answered opcode {opcode:#04x} with {ERROR_NAMES.get(notification[2], notification[2])}")

    async def expect_receipt(self, bytes_sent: int, timeout: float) -> None:
        try:
            notification = await asyncio.wait_for(self.notifications.get(), timeout=timeout)
        except asyncio.TimeoutError:
            sys.exit(f"no packet receipt after {bytes_sent} bytes within {timeout:.0f} s")
        if len(notification) != 5 or notification[0] != OPCODE_PACKET_RECEIPT_NOTIFICATION:
            sys.exit(f"unexpected notification {notification.hex()} while waiting for a packet receipt")
        bytes_received = struct.unpack_from("<I", notification, 1)[0]
        if bytes_received != bytes_sent:
            sys.exit(f"the watch has {bytes_received} bytes, {bytes_sent} were sent")


async def wake_watch(client) -> None:
    responses: asyncio.Queue[bytes] = asyncio.Queue()
    await client.start_notify(TRANSFER_UUID, lambda _characteristic, data: responses.put_nowait(bytes(data)))
    read_header = struct.pack("<BxHII", COMMAND_FILE_READ, len(WAKE_PATH), 0, 1) + WAKE_PATH
    await client.write_gatt_char(TRANSFER_UUID, read_header, response=True)
    try:
        await asyncio.wait_for(responses.get(), timeout=10.0)
    except asyncio.TimeoutError:
        sys.exit("the file-transfer read that wakes the watch got no answer")
    await client.stop_notify(TRANSFER_UUID)


async def send_image(client, control: ControlPoint, image: bytes, prn: int) -> None:
    started = time.monotonic()
    packets_sent = 0
    bytes_sent = 0
    next_report = 0
    for offset in range(0, len(image), PACKET_SIZE):
        chunk = image[offset:offset + PACKET_SIZE]
        await client.write_gatt_char(PACKET_UUID, chunk, response=False)
        packets_sent += 1
        bytes_sent += len(chunk)
        if bytes_sent == len(image):
            # The last packet is answered by the completion response, not a receipt.
            await control.expect_response(OPCODE_RECEIVE_FIRMWARE_IMAGE, timeout=10.0)
        elif packets_sent % prn == 0:
            await control.expect_receipt(bytes_sent, timeout=10.0)
        percent = bytes_sent * 100 // len(image)
        if percent >= next_report:
            elapsed = time.monotonic() - started
            print(f"{percent:3d} %  {bytes_sent} bytes  {elapsed:.0f} s")
            next_report += 10


async def flash(address: str, image: bytes, init_packet: bytes, prn: int) -> None:
    from bleak import BleakClient

    async with BleakClient(address, timeout=30.0) as client:
        revision = struct.unpack("<H", await client.read_gatt_char(REVISION_UUID))[0]
        print(f"connected, DFU revision {revision:#06x}")
        await wake_watch(client)
        print("watch woken through the file-transfer service")
        control = ControlPoint(client)
        await client.start_notify(CONTROL_POINT_UUID, control.on_notify)

        await control.write(OPCODE_START_DFU, IMAGE_TYPE_APPLICATION)
        await client.write_gatt_char(PACKET_UUID, struct.pack("<III", 0, 0, len(image)), response=False)
        print("start sent, the watch is erasing the secondary slot")
        await control.expect_response(OPCODE_START_DFU, timeout=60.0)

        await control.write(OPCODE_INIT_DFU_PARAMETERS, 0x00)
        await client.write_gatt_char(PACKET_UUID, init_packet, response=False)
        await control.write(OPCODE_INIT_DFU_PARAMETERS, 0x01)
        await control.expect_response(OPCODE_INIT_DFU_PARAMETERS, timeout=10.0)

        await control.write(OPCODE_PACKET_RECEIPT_NOTIFICATION_REQUEST, prn)
        await control.write(OPCODE_RECEIVE_FIRMWARE_IMAGE)
        print(f"sending {len(image)} bytes, a receipt every {prn} packets")
        await send_image(client, control, image, prn)

        await control.write(OPCODE_VALIDATE_FIRMWARE)
        await control.expect_response(OPCODE_VALIDATE_FIRMWARE, timeout=30.0)
        print("the watch validated the image CRC")

        try:
            await control.write(OPCODE_ACTIVATE_IMAGE_AND_RESET)
        except Exception as error:  # The watch resets on this write, often before the ATT response.
            print(f"activate write ended with {error.__class__.__name__}, expected when the watch resets at once")
        print("activate sent, the watch reboots into the new image; validate it on the watch once it works")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("address", help="the watch's Bluetooth address")
    parser.add_argument("dfu_zip", help="the pinetime-mcuboot-app-dfu zip from CI")
    parser.add_argument("--prn", type=int, default=10, help="packets between receipt notifications, 1..255 (default 10)")
    parser.add_argument("--check", action="store_true", help="only parse and verify the zip")
    arguments = parser.parse_args()
    if not 1 <= arguments.prn <= 255:
        sys.exit("--prn must be 1..255: the watch keeps it in a byte and divides by it")
    image, init_packet = load_package(arguments.dfu_zip)
    if arguments.check:
        return
    asyncio.run(flash(arguments.address, image, init_packet, arguments.prn))


if __name__ == "__main__":
    main()
