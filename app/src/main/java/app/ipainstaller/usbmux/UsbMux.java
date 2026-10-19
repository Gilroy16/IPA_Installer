package app.ipainstaller.usbmux;

import static app.ipainstaller.util.I18n.t;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Host side of Apple's usbmux protocol, spoken directly over the USB bulk endpoints
 * (the job usbmuxd does on a desktop). Follows usbmuxd's src/device.c: a version
 * handshake, then a minimal TCP-like protocol multiplexing connections to device ports.
 */
public final class UsbMux {
    static final int PROTO_VERSION = 0;
    static final int PROTO_CONTROL = 1;
    static final int PROTO_SETUP = 2;
    static final int PROTO_TCP = 6;

    static final int TH_FIN = 0x01, TH_SYN = 0x02, TH_RST = 0x04, TH_ACK = 0x10;

    static final int MUX_HEADER_V2 = 16;
    static final int TCP_HEADER = 20;
    static final int USB_MTU = 3 * 16384;
    static final int USB_MRU = 16384;
    static final int MAX_PAYLOAD = USB_MTU - MUX_HEADER_V2 - TCP_HEADER;

    private final UsbPipe pipe;
    private final Map<Integer, MuxConnection> connections = new HashMap<Integer, MuxConnection>();
    private final LinkedBlockingQueue<Packet> outQueue = new LinkedBlockingQueue<Packet>();
    private final Object versionLock = new Object();
    private volatile int version = 0;   // 0 until the device answers the version packet
    private volatile boolean closed;
    private volatile IOException failure;
    private int nextPort = 1;
    private int muxTxSeq, muxRxSeq;
    private Thread reader, writer;
    private Logger logger;

    public interface Logger {
        void log(String msg);
    }

    private static final class Packet {
        final int proto;
        final byte[] header;
        final byte[] data;
        final int off, len;

        Packet(int proto, byte[] header, byte[] data, int off, int len) {
            this.proto = proto;
            this.header = header;
            this.data = data;
            this.off = off;
            this.len = len;
        }
    }

    private static final Packet POISON = new Packet(-1, null, null, 0, 0);

    public UsbMux(UsbPipe pipe) {
        this.pipe = pipe;
    }

    public void setLogger(Logger l) {
        logger = l;
    }

    void log(String s) {
        Logger l = logger;
        if (l != null) l.log(s);
    }

    public int deviceVersion() {
        return version;
    }

    /** Performs the version handshake. */
    public void start() throws IOException {
        writer = new Thread(new Runnable() {
            @Override
            public void run() {
                writerLoop();
            }
        }, "usbmux-writer");
        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readerLoop();
            }
        }, "usbmux-reader");
        writer.setDaemon(true);
        reader.setDaemon(true);
        writer.start();
        reader.start();

        byte[] vh = new byte[12];
        putInt(vh, 0, 2);   // major
        putInt(vh, 4, 0);   // minor
        putInt(vh, 8, 0);   // padding
        enqueue(new Packet(PROTO_VERSION, vh, null, 0, 0));

        long deadline = System.currentTimeMillis() + 5000;
        synchronized (versionLock) {
            while (version == 0 && failure == null && !closed) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    versionLock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted");
                }
            }
        }
        if (failure != null) throw failure;
        if (version == 0) throw new IOException("iPhone did not answer the usbmux version request");
        if (version >= 2) {
            enqueue(new Packet(PROTO_SETUP, new byte[0], new byte[]{0x07}, 0, 1));
        }
        log(t("mux_version", version));
    }

    /** Opens a TCP-like connection to a port on the device (e.g. 62078 for lockdownd). */
    public MuxConnection connect(int devicePort) throws IOException {
        checkAlive();
        MuxConnection c;
        synchronized (connections) {
            int port = 0;
            for (int i = 0; i < 0xFFFF; i++) {
                int p = nextPort;
                nextPort = nextPort >= 0xFFFF ? 1 : nextPort + 1;
                if (!connections.containsKey(p)) {
                    port = p;
                    break;
                }
            }
            if (port == 0) throw new IOException("No free usbmux ports");
            c = new MuxConnection(this, port, devicePort);
            connections.put(port, c);
        }
        c.sendSyn();
        try {
            c.awaitConnected(10000);
        } catch (IOException e) {
            remove(c);
            throw e;
        }
        return c;
    }

    void remove(MuxConnection c) {
        synchronized (connections) {
            connections.remove(c.localPort);
        }
    }

    public void checkAliveOrThrow() throws IOException {
        checkAlive();
    }

    void checkAlive() throws IOException {
        if (failure != null) throw failure;
        if (closed) throw new IOException("USB connection closed");
    }

    void sendTcp(int sport, int dport, int seq, int ack, int flags, byte[] data, int off, int len) throws IOException {
        checkAlive();
        byte[] th = new byte[TCP_HEADER];
        putShort(th, 0, sport);
        putShort(th, 2, dport);
        putInt(th, 4, seq);
        putInt(th, 8, ack);
        th[12] = (byte) ((TCP_HEADER / 4) << 4);
        th[13] = (byte) flags;
        putShort(th, 14, MuxConnection.TX_WINDOW >> 8);
        byte[] copy = null;
        if (len > 0) {
            copy = new byte[len];
            System.arraycopy(data, off, copy, 0, len);
        }
        enqueue(new Packet(PROTO_TCP, th, copy, 0, len));
    }

    /** Payload size that never produces a USB transfer that is a multiple of wMaxPacketSize (avoids ZLPs). */
    int clampPayload(int n) {
        int mps = pipe.maxPacketSize();
        if (mps > 0 && (MUX_HEADER_V2 + TCP_HEADER + n) % mps == 0) n--;
        return n;
    }

    private void enqueue(Packet p) {
        outQueue.add(p);
    }

    private void writerLoop() {
        try {
            while (true) {
                Packet p = outQueue.poll(1, TimeUnit.SECONDS);
                if (closed && (p == null || p == POISON)) return;
                if (p == null) continue;
                if (p == POISON) return;
                int muxHeader = version >= 2 ? MUX_HEADER_V2 : 8;
                int total = muxHeader + p.header.length + p.len;
                byte[] buf = new byte[total];
                putInt(buf, 0, p.proto);
                putInt(buf, 4, total);
                if (version >= 2) {
                    putInt(buf, 8, 0xfeedface);
                    if (p.proto == PROTO_SETUP) {
                        muxTxSeq = 0;
                        muxRxSeq = 0xFFFF;
                    }
                    putShort(buf, 12, muxTxSeq);
                    putShort(buf, 14, muxRxSeq);
                    muxTxSeq = (muxTxSeq + 1) & 0xFFFF;
                }
                System.arraycopy(p.header, 0, buf, muxHeader, p.header.length);
                if (p.len > 0) System.arraycopy(p.data, p.off, buf, muxHeader + p.header.length, p.len);
                pipe.write(buf, total);
            }
        } catch (IOException e) {
            fail(e);
        } catch (InterruptedException e) {
            // shutting down
        }
    }

    private void readerLoop() {
        byte[] chunk = new byte[USB_MRU];
        byte[] acc = new byte[2 * 65536];
        int accLen = 0;
        try {
            while (!closed) {
                int n = pipe.read(chunk, chunk.length);
                if (n < 0) throw new IOException("USB read failed (cable unplugged?)");
                if (n == 0) continue;
                if (accLen + n > acc.length) {
                    byte[] bigger = new byte[Math.max(acc.length * 2, accLen + n)];
                    System.arraycopy(acc, 0, bigger, 0, accLen);
                    acc = bigger;
                }
                System.arraycopy(chunk, 0, acc, accLen, n);
                accLen += n;
                int pos = 0;
                while (accLen - pos >= 8) {
                    int len = getInt(acc, pos + 4);
                    if (len < 8 || len > 4 * 65536) throw new IOException("Corrupt usbmux packet (length " + len + ")");
                    if (accLen - pos < len) break;
                    handlePacket(acc, pos, len);
                    pos += len;
                }
                if (pos > 0) {
                    System.arraycopy(acc, pos, acc, 0, accLen - pos);
                    accLen -= pos;
                }
            }
        } catch (IOException e) {
            if (!closed) fail(e);
        }
    }

    private void handlePacket(byte[] b, int pos, int len) throws IOException {
        int proto = getInt(b, pos);
        int hdr = version >= 2 ? MUX_HEADER_V2 : 8;
        if (version >= 2) muxRxSeq = getShort(b, pos + 14);   // as usbmuxd does
        switch (proto) {
            case PROTO_VERSION: {
                if (len < hdr + 12) throw new IOException("Short usbmux version packet");
                int major = getInt(b, pos + hdr);
                synchronized (versionLock) {
                    version = major;
                    versionLock.notifyAll();
                }
                break;
            }
            case PROTO_CONTROL:
                break;   // device log messages; ignored
            case PROTO_TCP: {
                if (len < hdr + TCP_HEADER) throw new IOException("Short usbmux TCP packet");
                int th = pos + hdr;
                int devPort = getShort(b, th);
                int ourPort = getShort(b, th + 2);
                int seq = getInt(b, th + 4);
                int ack = getInt(b, th + 8);
                int off = ((b[th + 12] & 0xff) >> 4) * 4;
                if (off < TCP_HEADER) off = TCP_HEADER;
                int flags = b[th + 13] & 0xff;
                int win = getShort(b, th + 14) << 8;
                int payloadStart = th + off;
                int payloadLen = pos + len - payloadStart;
                MuxConnection c;
                synchronized (connections) {
                    c = connections.get(ourPort);
                }
                if (c == null || c.remotePort != devPort) {
                    if ((flags & TH_RST) == 0) sendTcp(ourPort, devPort, ack, seq, TH_RST, null, 0, 0);
                    return;
                }
                c.onPacket(seq, ack, flags, win, b, payloadStart, Math.max(0, payloadLen));
                break;
            }
            default:
                log(t("mux_unknown", proto));
        }
    }

    private void fail(IOException e) {
        if (failure == null) failure = e;
        closeInternal();
    }

    public void close() {
        if (closed) return;
        MuxConnection[] all;
        synchronized (connections) {
            all = connections.values().toArray(new MuxConnection[0]);
        }
        for (MuxConnection c : all) {
            try {
                c.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
        // give the writer a moment to flush the RSTs
        long deadline = System.currentTimeMillis() + 300;
        while (!outQueue.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                break;
            }
        }
        closeInternal();
    }

    private void closeInternal() {
        closed = true;
        outQueue.add(POISON);
        MuxConnection[] all;
        synchronized (connections) {
            all = connections.values().toArray(new MuxConnection[0]);
            connections.clear();
        }
        for (MuxConnection c : all) c.onMuxClosed(failure);
        synchronized (versionLock) {
            versionLock.notifyAll();
        }
        pipe.close();
    }

    static void putInt(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }

    static void putShort(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 8);
        b[o + 1] = (byte) v;
    }

    static int getInt(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    static int getShort(byte[] b, int o) {
        return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff);
    }
}
