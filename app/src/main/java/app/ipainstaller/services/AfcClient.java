package app.ipainstaller.services;

import static app.ipainstaller.util.I18n.t;

import java.io.IOException;
import java.nio.charset.Charset;

import app.ipainstaller.util.DuplexStream;

/** Apple File Conduit (com.apple.afc) client: just enough to upload a file (see libimobiledevice afc.c). */
public final class AfcClient {
    public static final String SERVICE = "com.apple.afc";

    private static final byte[] MAGIC = "CFA6LPAA".getBytes(Charset.forName("US-ASCII"));
    private static final int HEADER = 40;

    static final int OP_STATUS = 0x01;
    static final int OP_DATA = 0x02;
    static final int OP_REMOVE_PATH = 0x08;
    static final int OP_MAKE_DIR = 0x09;
    static final int OP_GET_FILE_INFO = 0x0A;
    static final int OP_FILE_OPEN = 0x0D;
    static final int OP_FILE_OPEN_RES = 0x0E;
    static final int OP_FILE_WRITE = 0x10;
    static final int OP_FILE_CLOSE = 0x14;

    public static final long MODE_WRONLY = 3;   // O_WRONLY | O_CREAT | O_TRUNC

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class AfcException extends IOException {
        public final long code;

        AfcException(String op, long code) {
            super("AFC " + op + ": " + describe(code));
            this.code = code;
        }
    }

    private final DuplexStream s;
    private long packetNum;

    public AfcClient(DuplexStream s) {
        this.s = s;
    }

    private static final class Reply {
        long op;
        byte[] headerData;
        byte[] payload;
    }

    private void sendPacket(long op, byte[] headerData, byte[] payload, int pOff, int pLen) throws IOException {
        packetNum++;
        int thisLen = HEADER + headerData.length;
        byte[] buf = new byte[thisLen];
        System.arraycopy(MAGIC, 0, buf, 0, 8);
        putLe(buf, 8, thisLen + (long) pLen);
        putLe(buf, 16, thisLen);
        putLe(buf, 24, packetNum);
        putLe(buf, 32, op);
        System.arraycopy(headerData, 0, buf, HEADER, headerData.length);
        s.out().write(buf, 0, buf.length);
        if (pLen > 0) s.out().write(payload, pOff, pLen);
        s.out().flush();
    }

    private Reply receive() throws IOException {
        byte[] h = s.readFully(HEADER);
        for (int i = 0; i < 8; i++) {
            if (h[i] != MAGIC[i]) throw new IOException("Invalid AFC reply");
        }
        long entire = getLe(h, 8), thisLen = getLe(h, 16), num = getLe(h, 24);
        if (num != packetNum) throw new IOException("AFC packet number mismatch");
        if (thisLen < HEADER || entire < thisLen || entire > 64L * 1024 * 1024) throw new IOException("Invalid AFC reply header");
        Reply r = new Reply();
        r.op = getLe(h, 32);
        r.headerData = s.readFully((int) (thisLen - HEADER));
        r.payload = s.readFully((int) (entire - thisLen));
        return r;
    }

    private Reply call(String what, long op, byte[] headerData, byte[] payload, int pOff, int pLen) throws IOException {
        sendPacket(op, headerData, payload, pOff, pLen);
        Reply r = receive();
        if (r.op == OP_STATUS) {
            long code = r.headerData.length >= 8 ? getLe(r.headerData, 0) : -1;
            if (code != 0) throw new AfcException(what, code);
        }
        return r;
    }

    private static byte[] path(String p) {
        byte[] b = p.getBytes(UTF8);
        byte[] out = new byte[b.length + 1];
        System.arraycopy(b, 0, out, 0, b.length);
        return out;
    }

    public void makeDirectory(String dir) throws IOException {
        call("MakeDir " + dir, OP_MAKE_DIR, path(dir), null, 0, 0);
    }

    public boolean exists(String p) throws IOException {
        try {
            call("GetFileInfo", OP_GET_FILE_INFO, path(p), null, 0, 0);
            return true;
        } catch (AfcException e) {
            if (e.code == 8) return false;   // object not found
            throw e;
        }
    }

    public void remove(String p) throws IOException {
        call("Remove " + p, OP_REMOVE_PATH, path(p), null, 0, 0);
    }

    public long open(String p, long mode) throws IOException {
        byte[] name = path(p);
        byte[] hd = new byte[8 + name.length];
        putLe(hd, 0, mode);
        System.arraycopy(name, 0, hd, 8, name.length);
        Reply r = call("Open " + p, OP_FILE_OPEN, hd, null, 0, 0);
        if (r.op != OP_FILE_OPEN_RES || r.headerData.length < 8) throw new IOException("AFC Open: unexpected reply");
        return getLe(r.headerData, 0);
    }

    public void write(long handle, byte[] data, int off, int len) throws IOException {
        byte[] hd = new byte[8];
        putLe(hd, 0, handle);
        call("Write", OP_FILE_WRITE, hd, data, off, len);
    }

    public void close(long handle) throws IOException {
        byte[] hd = new byte[8];
        putLe(hd, 0, handle);
        call("Close", OP_FILE_CLOSE, hd, null, 0, 0);
    }

    static void putLe(byte[] b, int o, long v) {
        for (int i = 0; i < 8; i++) b[o + i] = (byte) (v >>> (8 * i));
    }

    static long getLe(byte[] b, int o) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[o + i] & 0xff);
        return v;
    }

    static String describe(long code) {
        switch ((int) code) {
            case 1: case 7: case 8: case 9: case 10: case 15: case 16: case 18:
                return t("afc_" + code);
            default:
                return t("afc_code", code);
        }
    }
}
