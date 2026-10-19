package app.ipainstaller.util;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** A bidirectional byte stream (a mux TCP connection, optionally wrapped in TLS). */
public abstract class DuplexStream implements Closeable {
    public abstract InputStream in();

    public abstract OutputStream out();

    public void readFully(byte[] buf, int off, int len) throws IOException {
        InputStream in = in();
        while (len > 0) {
            int n = in.read(buf, off, len);
            if (n < 0) throw new EOFException("Connection closed by iPhone");
            off += n;
            len -= n;
        }
    }

    public byte[] readFully(int len) throws IOException {
        byte[] b = new byte[len];
        readFully(b, 0, len);
        return b;
    }
}
