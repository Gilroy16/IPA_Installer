package app.ipainstaller.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

/** TLS over an arbitrary DuplexStream, driven by an SSLEngine (no real socket is available over usbmux). */
public final class TlsStream extends DuplexStream {
    private final DuplexStream raw;
    private final SSLEngine engine;
    private ByteBuffer netIn, appIn;
    private final ByteBuffer netOut;
    private final byte[] readChunk;
    private boolean eof;

    private final InputStream in = new InputStream() {
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return TlsStream.this.read(b, off, len);
        }
    };

    private final OutputStream out = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            TlsStream.this.write(b, off, len);
        }
    };

    public TlsStream(DuplexStream raw, SSLEngine engine) throws IOException {
        this.raw = raw;
        this.engine = engine;
        int pkt = engine.getSession().getPacketBufferSize();
        int app = engine.getSession().getApplicationBufferSize();
        netIn = ByteBuffer.allocate(pkt);
        netIn.flip();   // empty, in read mode
        netOut = ByteBuffer.allocate(pkt);
        appIn = ByteBuffer.allocate(app);
        appIn.flip();
        readChunk = new byte[pkt];
        engine.beginHandshake();
        handshake();
    }

    @Override
    public InputStream in() {
        return in;
    }

    @Override
    public OutputStream out() {
        return out;
    }

    public SSLEngine engine() {
        return engine;
    }

    private void handshake() throws IOException {
        while (true) {
            SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
            switch (hs) {
                case NEED_WRAP:
                    wrap(ByteBuffer.allocate(0));
                    break;
                case NEED_UNWRAP:
                    if (!unwrapOnce()) throw new SSLException("Connection closed during TLS handshake");
                    break;
                case NEED_TASK:
                    runTasks();
                    break;
                default:
                    return;   // FINISHED or NOT_HANDSHAKING
            }
        }
    }

    private void runTasks() {
        Runnable r;
        while ((r = engine.getDelegatedTask()) != null) r.run();
    }

    private void wrap(ByteBuffer src) throws IOException {
        do {
            netOut.clear();
            SSLEngineResult res = engine.wrap(src, netOut);
            if (res.getStatus() == SSLEngineResult.Status.CLOSED) throw new SSLException("TLS session closed");
            if (res.getStatus() != SSLEngineResult.Status.OK) throw new SSLException("TLS wrap failed: " + res.getStatus());
            netOut.flip();
            if (netOut.hasRemaining()) {
                raw.out().write(netOut.array(), netOut.arrayOffset() + netOut.position(), netOut.remaining());
                raw.out().flush();
            }
            if (res.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks();
        } while (src.hasRemaining());
    }

    /**
     * Unwraps one TLS record into appIn (reading from the network as needed).
     * @return false on end of stream.
     */
    private boolean unwrapOnce() throws IOException {
        while (true) {
            // appIn is in read mode; switch to write mode to append
            appIn.compact();
            SSLEngineResult res;
            try {
                res = engine.unwrap(netIn, appIn);
            } finally {
                appIn.flip();
            }
            switch (res.getStatus()) {
                case OK:
                    if (res.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks();
                    return true;
                case CLOSED:
                    eof = true;
                    return false;
                case BUFFER_OVERFLOW: {
                    ByteBuffer bigger = ByteBuffer.allocate(appIn.capacity() + engine.getSession().getApplicationBufferSize());
                    bigger.put(appIn);
                    bigger.flip();
                    appIn = bigger;
                    break;
                }
                case BUFFER_UNDERFLOW: {
                    if (netIn.capacity() < engine.getSession().getPacketBufferSize()) {
                        ByteBuffer bigger = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
                        bigger.put(netIn);
                        bigger.flip();
                        netIn = bigger;
                    }
                    int n = raw.in().read(readChunk, 0, Math.min(readChunk.length, netIn.capacity() - netIn.remaining()));
                    if (n < 0) {
                        eof = true;
                        return false;
                    }
                    netIn.compact();
                    netIn.put(readChunk, 0, n);
                    netIn.flip();
                    break;
                }
            }
        }
    }

    private int read(byte[] b, int off, int len) throws IOException {
        while (!appIn.hasRemaining()) {
            if (eof) return -1;
            if (!unwrapOnce()) return -1;
            // post-handshake messages (e.g. TLS 1.3 session tickets) may require a reply
            SSLEngineResult.HandshakeStatus hs = engine.getHandshakeStatus();
            if (hs != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING && hs != SSLEngineResult.HandshakeStatus.FINISHED) {
                handshake();
            }
        }
        int n = Math.min(len, appIn.remaining());
        appIn.get(b, off, n);
        return n;
    }

    private void write(byte[] b, int off, int len) throws IOException {
        wrap(ByteBuffer.wrap(b, off, len));
    }

    @Override
    public void close() throws IOException {
        try {
            engine.closeOutbound();
        } catch (Exception ignored) {
            // best effort
        }
        raw.close();
    }
}
