package com.limelight.strmqt;

import java.nio.ByteBuffer;

/**
 * One datagram of StrmQt's time exchange (StrmQt's remote/TimeService.h): the
 * four times of NTP, in 48 bytes, big-endian.
 *
 * <pre>
 *  0 "STQT"   4 version (1)   5 kind (1 request, 2 reply)   6 flags (0)
 *  8 sequence, echoed   12 reserved   16 t1, echoed   24 t2   32 t3
 * 40 clockId
 * </pre>
 *
 * t1 is the asker's send time, echoed unread; t2 and t3 are when the answerer
 * received the request and sent the reply, on its CLOCK_MONOTONIC in
 * nanoseconds (System.nanoTime here, steady_clock on the host). clockId names
 * the answerer's timeline: it changes when that clock starts over.
 */
public final class TimePacket {
    public static final int SIZE = 48;
    static final byte VERSION = 1;
    static final byte REQUEST = 1;
    static final byte REPLY = 2;
    private static final byte[] MAGIC = {'S', 'T', 'Q', 'T'};

    public final boolean reply;
    public final int sequence;
    public final long t1;
    public final long t2;
    public final long t3;
    public final long clockId;

    private TimePacket(boolean reply, int sequence, long t1, long t2, long t3, long clockId) {
        this.reply = reply;
        this.sequence = sequence;
        this.t1 = t1;
        this.t2 = t2;
        this.t3 = t3;
        this.clockId = clockId;
    }

    public static byte[] request(int sequence, long t1) {
        return encode(REQUEST, sequence, t1, 0, 0, 0);
    }

    /** The reply to `request`, received at t2 and sent at t3. */
    public static byte[] reply(TimePacket request, long t2, long t3, long clockId) {
        return encode(REPLY, request.sequence, request.t1, t2, t3, clockId);
    }

    /** The packet in `data`, or null for anything that is not one. */
    public static TimePacket parse(byte[] data, int offset, int length) {
        if (length != SIZE) {
            return null;
        }
        ByteBuffer in = ByteBuffer.wrap(data, offset, length);
        for (byte b : MAGIC) {
            if (in.get() != b) {
                return null;
            }
        }
        if (in.get() != VERSION) {
            return null;
        }
        byte kind = in.get();
        if (kind != REQUEST && kind != REPLY) {
            return null;
        }
        in.getShort();
        int sequence = in.getInt();
        in.getInt();
        long t1 = in.getLong();
        long t2 = in.getLong();
        long t3 = in.getLong();
        long clockId = in.getLong();
        return new TimePacket(kind == REPLY, sequence, t1, t2, t3, clockId);
    }

    private static byte[] encode(byte kind, int sequence, long t1, long t2, long t3, long clockId) {
        ByteBuffer out = ByteBuffer.allocate(SIZE);
        out.put(MAGIC).put(VERSION).put(kind).putShort((short) 0).putInt(sequence).putInt(0)
                .putLong(t1).putLong(t2).putLong(t3).putLong(clockId);
        return out.array();
    }
}
