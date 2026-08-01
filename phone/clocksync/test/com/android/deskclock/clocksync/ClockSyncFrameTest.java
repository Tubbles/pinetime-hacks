/*
 * Plain-JVM test for ClockSyncFrame (no JUnit, no Android). Runs with just a
 * JDK: `javac` the codec + this test, then `java ...ClockSyncFrameTest`.
 * Verifies the 16-byte wire layout matches the firmware ClockSyncService.
 */
package com.android.deskclock.clocksync;

import java.util.Arrays;

public final class ClockSyncFrameTest {

    private static int checks = 0;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        // Round-trip a representative timer frame.
        ClockSyncFrame timer = new ClockSyncFrame(
                ClockSyncFrame.DOMAIN_TIMER, ClockSyncFrame.TIMER_RUNNING, 90000L, 1730000000000L);
        check(timer.encode().length == ClockSyncFrame.FRAME_SIZE, "frame is 16 bytes");
        check(timer.equals(ClockSyncFrame.decode(timer.encode())), "encode/decode round-trip");

        // Exact byte layout: stopwatch running, value_ms = 0, reference = 1.
        byte[] wire = new ClockSyncFrame(
                ClockSyncFrame.DOMAIN_STOPWATCH, ClockSyncFrame.STOPWATCH_RUNNING, 0L, 1L).encode();
        check(wire[0] == 1, "[0] version = 1");
        check(wire[1] == 0, "[1] domain = stopwatch");
        check(wire[2] == 1, "[2] state = running");
        check(wire[3] == 0, "[3] reserved = 0");
        check(wire[4] == 0 && wire[5] == 0 && wire[6] == 0 && wire[7] == 0, "[4..7] value_ms LE = 0");
        check(wire[8] == 1 && wire[9] == 0 && wire[15] == 0, "[8..15] reference_epoch_ms LE = 1");

        // uint32 and int64 edge values survive the round-trip.
        ClockSyncFrame edges = ClockSyncFrame.decode(
                new ClockSyncFrame(0, 2, 0xFFFFFFFFL, -1L).encode());
        check(edges.valueMs == 0xFFFFFFFFL, "uint32 max preserved");
        check(edges.referenceEpochMs == -1L, "int64 negative preserved");

        // Hex helpers are inverse and lowercase, no separators.
        byte[] raw = timer.encode();
        check(Arrays.equals(raw, ClockSyncFrame.hexToBytes(ClockSyncFrame.bytesToHex(raw))), "hex round-trip");
        check(ClockSyncFrame.bytesToHex(new byte[] {(byte) 0xAB, 0x01}).equals("ab01"), "hex is lowercase");
        check(Arrays.equals(ClockSyncFrame.hexToBytes("AB01"), new byte[] {(byte) 0xAB, 0x01}), "hex parse is case-insensitive");

        System.out.println("ClockSyncFrame: all " + checks + " checks passed");
    }
}
