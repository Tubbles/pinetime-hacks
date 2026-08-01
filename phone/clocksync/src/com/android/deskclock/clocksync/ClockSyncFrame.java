/*
 * ClockSync wire-protocol codec.
 *
 * Pure, side-effect-free encode/decode for the fixed 16-byte little-endian
 * frame shared with the InfiniTime ClockSync BLE service. The layout must match
 * the firmware byte-for-byte; see InfiniTime
 * src/components/ble/ClockSyncService.cpp (ReadUInt32/ReadInt64/BuildStopWatchFrame/
 * BuildTimerFrame) and doc/DESIGN-clock-sync.md.
 *
 * Frame layout (all multi-byte fields little-endian):
 *   [0]      version = 1
 *   [1]      domain: 0 = stopwatch, 1 = timer
 *   [2]      state:  stopwatch {0 cleared, 1 running, 2 paused}
 *                    timer     {0 stopped, 1 running, 2 expired}
 *   [3]      reserved = 0
 *   [4..7]   uint32 value_ms
 *   [8..15]  int64  reference_epoch_ms (UTC milliseconds)
 */
package com.android.deskclock.clocksync;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Immutable value type for one ClockSync frame plus its codec. No Android
 * dependencies so it can be unit-tested on a plain JVM.
 */
public final class ClockSyncFrame {

    public static final int FRAME_SIZE = 16;
    public static final int VERSION = 1;

    public static final int DOMAIN_STOPWATCH = 0;
    public static final int DOMAIN_TIMER = 1;

    public static final int STOPWATCH_CLEARED = 0;
    public static final int STOPWATCH_RUNNING = 1;
    public static final int STOPWATCH_PAUSED = 2;

    public static final int TIMER_STOPPED = 0;
    public static final int TIMER_RUNNING = 1;
    public static final int TIMER_EXPIRED = 2;

    private static final long UINT32_MASK = 0xFFFFFFFFL;

    public final int version;
    public final int domain;
    public final int state;
    /** uint32 payload, held as a long in the range 0..2^32-1. */
    public final long valueMs;
    /** int64 UTC-epoch reference in milliseconds. */
    public final long referenceEpochMs;

    public ClockSyncFrame(int domain, int state, long valueMs, long referenceEpochMs) {
        this(VERSION, domain, state, valueMs, referenceEpochMs);
    }

    public ClockSyncFrame(int version, int domain, int state, long valueMs,
            long referenceEpochMs) {
        this.version = version;
        this.domain = domain;
        this.state = state;
        this.valueMs = valueMs & UINT32_MASK;
        this.referenceEpochMs = referenceEpochMs;
    }

    /**
     * @return the 16-byte little-endian wire representation of this frame
     */
    public byte[] encode() {
        final ByteBuffer buffer = ByteBuffer.allocate(FRAME_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) version);
        buffer.put((byte) domain);
        buffer.put((byte) state);
        buffer.put((byte) 0); // reserved
        buffer.putInt((int) valueMs);
        buffer.putLong(referenceEpochMs);
        return buffer.array();
    }

    /**
     * @param bytes a wire frame of at least {@link #FRAME_SIZE} bytes
     * @return the decoded frame
     * @throws IllegalArgumentException if the buffer is too short
     */
    public static ClockSyncFrame decode(byte[] bytes) {
        if (bytes == null || bytes.length < FRAME_SIZE) {
            throw new IllegalArgumentException("ClockSync frame must be at least "
                    + FRAME_SIZE + " bytes, got " + (bytes == null ? 0 : bytes.length));
        }
        final ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        final int version = buffer.get() & 0xFF;
        final int domain = buffer.get() & 0xFF;
        final int state = buffer.get() & 0xFF;
        buffer.get(); // reserved
        final long valueMs = buffer.getInt() & UINT32_MASK;
        final long referenceEpochMs = buffer.getLong();
        return new ClockSyncFrame(version, domain, state, valueMs, referenceEpochMs);
    }

    /**
     * @return lowercase hex, no separators; the format Gadgetbridge's
     *      StringUtils.hexToBytes parses for EXTRA_PAYLOAD (case-insensitive)
     */
    public static String bytesToHex(byte[] bytes) {
        final StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    /**
     * @param hex an even-length hex string (any case); the format Gadgetbridge
     *      emits in EXTRA_PAYLOAD via StringUtils.bytesToHex
     * @return the decoded bytes
     * @throws IllegalArgumentException if the string has odd length
     */
    public static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("hex string has odd length: " + hex.length());
        }
        final byte[] bytes = new byte[hex.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            final int high = Character.digit(hex.charAt(index * 2), 16);
            final int low = Character.digit(hex.charAt(index * 2 + 1), 16);
            bytes[index] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ClockSyncFrame)) {
            return false;
        }
        final ClockSyncFrame that = (ClockSyncFrame) other;
        return version == that.version && domain == that.domain && state == that.state
                && valueMs == that.valueMs && referenceEpochMs == that.referenceEpochMs;
    }

    @Override
    public int hashCode() {
        int result = version;
        result = 31 * result + domain;
        result = 31 * result + state;
        result = 31 * result + (int) (valueMs ^ (valueMs >>> 32));
        result = 31 * result + (int) (referenceEpochMs ^ (referenceEpochMs >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "ClockSyncFrame{version=" + version + ", domain=" + domain + ", state=" + state
                + ", valueMs=" + valueMs + ", referenceEpochMs=" + referenceEpochMs + '}';
    }
}
