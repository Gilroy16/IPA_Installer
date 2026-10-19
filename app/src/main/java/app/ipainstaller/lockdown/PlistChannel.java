package app.ipainstaller.lockdown;

import java.io.IOException;
import java.util.Map;

import app.ipainstaller.util.DuplexStream;
import app.ipainstaller.util.Plist;

/** Length-prefixed (32-bit big-endian) property list messages, as lockdownd and installation_proxy use. */
public class PlistChannel {
    protected DuplexStream stream;

    public PlistChannel(DuplexStream stream) {
        this.stream = stream;
    }

    public DuplexStream stream() {
        return stream;
    }

    public void send(Map<String, Object> msg) throws IOException {
        byte[] body = Plist.toXml(msg);
        byte[] frame = new byte[4 + body.length];
        frame[0] = (byte) (body.length >>> 24);
        frame[1] = (byte) (body.length >>> 16);
        frame[2] = (byte) (body.length >>> 8);
        frame[3] = (byte) body.length;
        System.arraycopy(body, 0, frame, 4, body.length);
        stream.out().write(frame, 0, frame.length);
        stream.out().flush();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> receive() throws IOException {
        byte[] h = stream.readFully(4);
        int len = ((h[0] & 0xff) << 24) | ((h[1] & 0xff) << 16) | ((h[2] & 0xff) << 8) | (h[3] & 0xff);
        if (len < 0 || len > 64 * 1024 * 1024) throw new IOException("Bad plist message length " + len);
        Object o = Plist.parse(stream.readFully(len));
        if (!(o instanceof Map)) throw new IOException("Unexpected reply from iPhone");
        return (Map<String, Object>) o;
    }

    public void close() {
        try {
            stream.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
