package app.ipainstaller.usbmux;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import app.ipainstaller.util.DuplexStream;

/** One TCP-like stream over usbmux, with the same sequencing and windowing rules usbmuxd uses. */
public final class MuxConnection extends DuplexStream {
    static final int TX_WINDOW = 131072;   // window we advertise (sent as >> 8)

    private static final int CONNECTING = 0, CONNECTED = 1, CLOSED = 2;

    final UsbMux mux;
    final int localPort, remotePort;

    private final Object lock = new Object();
    private int state = CONNECTING;
    private int txSeq, txAck;
    private int rxAck, rxWin;
    private IOException closeReason;

    private byte[] inBuf = new byte[65536];
    private int inHead, inLen;

    private final InputStream in = new InputStream() {
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return MuxConnection.this.read(b, off, len);
        }

        @Override
        public int available() {
            synchronized (lock) {
                return inLen;
            }
        }
    };

    private final OutputStream out = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            MuxConnection.this.write(b, off, len);
        }
    };

    MuxConnection(UsbMux mux, int localPort, int remotePort) {
        this.mux = mux;
        this.localPort = localPort;
        this.remotePort = remotePort;
    }

    @Override
    public InputStream in() {
        return in;
    }

    @Override
    public OutputStream out() {
        return out;
    }

    void sendSyn() throws IOException {
        synchronized (lock) {
            txSeq = 0;
            txAck = 0;
            mux.sendTcp(localPort, remotePort, txSeq, txAck, UsbMux.TH_SYN, null, 0, 0);
        }
    }

    void awaitConnected(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (state == CONNECTING) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) throw new IOException("Timed out connecting to iPhone port " + remotePort);
                waitOn(left);
            }
            if (state != CONNECTED) {
                throw closeReason != null ? closeReason : new IOException("iPhone refused connection to port " + remotePort);
            }
        }
    }

    void onPacket(int seq, int ack, int flags, int win, byte[] b, int off, int len) throws IOException {
        synchronized (lock) {
            rxAck = ack;
            rxWin = win;
            if (state == CONNECTING) {
                if (flags == (UsbMux.TH_SYN | UsbMux.TH_ACK)) {
                    txSeq++;
                    txAck++;   // usbmuxd does the same: device sequence numbers start at 0
                    mux.sendTcp(localPort, remotePort, txSeq, txAck, UsbMux.TH_ACK, null, 0, 0);
                    state = CONNECTED;
                } else {
                    state = CLOSED;
                    closeReason = new IOException("iPhone refused connection to port " + remotePort);
                    mux.remove(this);
                }
            } else if (state == CONNECTED) {
                if ((flags & (UsbMux.TH_RST | UsbMux.TH_FIN | UsbMux.TH_SYN)) != 0) {
                    state = CLOSED;
                    String reason = len > 0 ? new String(b, off, len, "US-ASCII").trim() : "";
                    closeReason = new IOException("Connection reset by iPhone" + (reason.isEmpty() ? "" : ": " + reason));
                    mux.remove(this);
                } else if (len > 0) {
                    append(b, off, len);
                    txAck += len;
                    // the device likes prompt ACKs (see usbmuxd)
                    mux.sendTcp(localPort, remotePort, txSeq, txAck, UsbMux.TH_ACK, null, 0, 0);
                }
            }
            lock.notifyAll();
        }
    }

    void onMuxClosed(IOException reason) {
        synchronized (lock) {
            if (state != CLOSED) {
                state = CLOSED;
                closeReason = reason != null ? reason : new IOException("USB connection closed");
            }
            lock.notifyAll();
        }
    }

    private void append(byte[] b, int off, int len) {
        if (inLen + len > inBuf.length - inHead) {
            if (inLen + len > inBuf.length) {
                byte[] bigger = new byte[Math.max(inBuf.length * 2, inLen + len)];
                System.arraycopy(inBuf, inHead, bigger, 0, inLen);
                inBuf = bigger;
            } else {
                System.arraycopy(inBuf, inHead, inBuf, 0, inLen);
            }
            inHead = 0;
        }
        System.arraycopy(b, off, inBuf, inHead + inLen, len);
        inLen += len;
    }

    private int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        synchronized (lock) {
            while (inLen == 0) {
                if (state == CLOSED) {
                    if (closeReason != null && !(closeReason.getMessage() != null && closeReason.getMessage().startsWith("Connection reset"))) {
                        throw closeReason;
                    }
                    return -1;
                }
                waitOn(0);
            }
            int n = Math.min(len, inLen);
            System.arraycopy(inBuf, inHead, b, off, n);
            inHead += n;
            inLen -= n;
            if (inLen == 0) inHead = 0;
            return n;
        }
    }

    private void write(byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            synchronized (lock) {
                long deadline = System.currentTimeMillis() + 60000;
                int sendable;
                while (true) {
                    if (state != CONNECTED) {
                        throw closeReason != null ? closeReason : new IOException("Connection closed");
                    }
                    sendable = rxWin - (txSeq - rxAck);
                    if (sendable > 0) break;
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) throw new IOException("iPhone stopped accepting data");
                    waitOn(left);
                }
                int n = Math.min(len, Math.min(sendable, UsbMux.MAX_PAYLOAD));
                n = mux.clampPayload(n);
                mux.sendTcp(localPort, remotePort, txSeq, txAck, UsbMux.TH_ACK, b, off, n);
                txSeq += n;
                off += n;
                len -= n;
            }
        }
    }

    private void waitOn(long ms) throws IOException {
        try {
            lock.wait(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            if (state == CLOSED) return;
            boolean wasConnected = state == CONNECTED;
            state = CLOSED;
            closeReason = new IOException("Connection closed");
            lock.notifyAll();
            mux.remove(this);
            if (wasConnected) {
                try {
                    mux.sendTcp(localPort, remotePort, txSeq, txAck, UsbMux.TH_RST, null, 0, 0);
                } catch (IOException ignored) {
                    // mux already gone
                }
            }
        }
    }
}
