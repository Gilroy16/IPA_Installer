package app.ipainstaller;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import app.ipainstaller.lockdown.PairingCrypto;
import app.ipainstaller.lockdown.PlistChannel;
import app.ipainstaller.usbmux.UsbPipe;
import app.ipainstaller.util.Base64;
import app.ipainstaller.util.DuplexStream;
import app.ipainstaller.util.Plist;
import app.ipainstaller.util.TlsStream;

/**
 * Simulated iPhone on the other end of the USB cable: the device side of usbmux,
 * lockdownd (pairing with a Trust prompt, TLS session with client-certificate check),
 * AFC and installation_proxy. Used to exercise the whole host flow on a JVM.
 */
final class FakeIphone implements UsbPipe {
    static final int MPS = 512;

    final LinkedBlockingQueue<byte[]> toHost = new LinkedBlockingQueue<byte[]>();
    final Map<String, byte[]> files = new HashMap<String, byte[]>();
    final List<String> installed = java.util.Collections.synchronizedList(new ArrayList<String>());
    final List<String> events = java.util.Collections.synchronizedList(new ArrayList<String>());
    volatile boolean closed;
    volatile int zlpViolations, packetsFromHost, maxHostPacket;
    int pairPendingReplies = 2;   // Pair answers PairingDialogResponsePending this many times
    boolean binaryReplies = true;

    private int version;
    private int expectedHostMuxSeq = -1;
    final KeyPair deviceKey;
    Map<String, Object> pairRecord;   // what the host sent in Pair
    private final Map<Integer, DevConn> conns = new HashMap<Integer, DevConn>();
    private final Map<Integer, String> services = new HashMap<Integer, String>();
    private int nextServicePort = 49152;
    private byte[] pendingRead;
    private int pendingOff;

    FakeIphone() throws Exception {
        this(newKey());
    }

    /** Same phone (same device key) as a previous FakeIphone, e.g. reconnecting the cable. */
    FakeIphone(KeyPair deviceKey) {
        this.deviceKey = deviceKey;
    }

    private static KeyPair newKey() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    String devicePublicKeyPem() throws Exception {
        RSAPublicKey k = (RSAPublicKey) deviceKey.getPublic();
        byte[] pkcs1 = seq(integer(k.getModulus()), integer(k.getPublicExponent()));
        return Base64.toPem("RSA PUBLIC KEY", pkcs1);
    }

    // ------------------------------------------------------------------ UsbPipe (host side view)

    @Override
    public int read(byte[] buf, int len) throws IOException {
        try {
            while (pendingRead == null) {
                if (closed) return -1;
                byte[] p = toHost.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (p != null) {
                    pendingRead = p;
                    pendingOff = 0;
                }
            }
        } catch (InterruptedException e) {
            return -1;
        }
        // like a USB transfer of at most len bytes; long packets arrive in several transfers
        int n = Math.min(len, pendingRead.length - pendingOff);
        System.arraycopy(pendingRead, pendingOff, buf, 0, n);
        pendingOff += n;
        if (pendingOff == pendingRead.length) pendingRead = null;
        return n;
    }

    @Override
    public synchronized void write(byte[] buf, int len) throws IOException {
        packetsFromHost++;
        maxHostPacket = Math.max(maxHostPacket, len);
        if (len % MPS == 0) zlpViolations++;
        int plen = getInt(buf, 4);
        if (plen != len) throw new IOException("fake: packet length mismatch " + plen + " vs " + len);
        int proto = getInt(buf, 0);
        int hdr = version >= 2 ? 16 : 8;
        if (version >= 2) {
            if (getInt(buf, 8) != 0xfeedface) throw new IOException("fake: bad magic");
            int txSeq = getShort(buf, 12);
            if (proto == 2) expectedHostMuxSeq = 0;
            if (expectedHostMuxSeq >= 0 && txSeq != expectedHostMuxSeq) throw new IOException("fake: mux seq " + txSeq + " != " + expectedHostMuxSeq);
            expectedHostMuxSeq = (txSeq + 1) & 0xffff;
        }
        switch (proto) {
            case 0: {
                int major = getInt(buf, hdr);
                events.add("version " + major);
                byte[] reply = new byte[8 + 12];
                putInt(reply, 0, 0);
                putInt(reply, 4, reply.length);
                putInt(reply, 8, 2);
                putInt(reply, 12, 0);
                toHost.add(reply);
                version = 2;
                break;
            }
            case 2:
                events.add("setup " + (buf[hdr] & 0xff));
                break;
            case 6:
                tcpIn(buf, hdr, len);
                break;
            default:
                throw new IOException("fake: unknown proto " + proto);
        }
    }

    @Override
    public int maxPacketSize() {
        return MPS;
    }

    @Override
    public void close() {
        closed = true;
    }

    // ------------------------------------------------------------------ device-side TCP

    private final class DevConn extends DuplexStream {
        final int hostPort, devPort;
        int seq = 0, ack;           // device's seq; ack = next host byte expected
        int hostAck, hostWin;       // what the host acknowledged / advertised
        boolean open = true;
        final Object lock = new Object();
        final ByteArrayOutputStream inBuf = new ByteArrayOutputStream();
        byte[] pending = new byte[0];
        int pendingPos;

        DevConn(int hostPort, int devPort) {
            this.hostPort = hostPort;
            this.devPort = devPort;
        }

        final InputStream in = new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] b = new byte[1];
                return read(b, 0, 1) < 0 ? -1 : b[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                synchronized (lock) {
                    while (pendingPos >= pending.length) {
                        if (inBuf.size() > 0) {
                            pending = inBuf.toByteArray();
                            pendingPos = 0;
                            inBuf.reset();
                            break;
                        }
                        if (!open) return -1;
                        try {
                            lock.wait(100);
                        } catch (InterruptedException e) {
                            return -1;
                        }
                    }
                    int n = Math.min(len, pending.length - pendingPos);
                    System.arraycopy(pending, pendingPos, b, off, n);
                    pendingPos += n;
                    return n;
                }
            }
        };

        final OutputStream out = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                while (len > 0) {
                    synchronized (lock) {
                        while (open && hostWin - (seq - hostAck) <= 0) {
                            try {
                                lock.wait(100);
                            } catch (InterruptedException e) {
                                throw new IOException("interrupted");
                            }
                        }
                        if (!open) throw new IOException("fake: connection closed");
                        int n = Math.min(len, Math.min(8000, hostWin - (seq - hostAck)));
                        sendTcp(DevConn.this, 0x10, b, off, n);
                        seq += n;
                        off += n;
                        len -= n;
                    }
                }
            }
        };

        @Override
        public InputStream in() {
            return in;
        }

        @Override
        public OutputStream out() {
            return out;
        }

        @Override
        public void close() {
            synchronized (lock) {
                if (!open) return;
                open = false;
                lock.notifyAll();
            }
            sendTcp(this, 0x04, null, 0, 0);
        }
    }

    private void sendTcp(DevConn c, int flags, byte[] data, int off, int len) {
        byte[] p = new byte[16 + 20 + len];
        putInt(p, 0, 6);
        putInt(p, 4, p.length);
        putInt(p, 8, 0xfeedface);
        putShort(p, 16, c.devPort);
        putShort(p, 18, c.hostPort);
        putInt(p, 20, c.seq);
        putInt(p, 24, c.ack);
        p[28] = 0x50;
        p[29] = (byte) flags;
        putShort(p, 30, 131072 >> 8);
        if (len > 0) System.arraycopy(data, off, p, 36, len);
        toHost.add(p);
    }

    private void tcpIn(byte[] b, int hdr, int len) throws IOException {
        int th = hdr;
        int sport = getShort(b, th), dport = getShort(b, th + 2);
        int seq = getInt(b, th + 4), ack = getInt(b, th + 8);
        int flags = b[th + 13] & 0xff;
        int win = getShort(b, th + 14) << 8;
        int payload = th + 20;
        int plen = len - payload;
        if ((flags & 0x02) != 0) {
            final DevConn c = new DevConn(sport, dport);
            c.ack = seq + 1;
            c.hostWin = win;
            String service = dport == 62078 ? "lockdown" : services.get(dport);
            if (service == null) {
                c.seq = 0;
                sendTcp(c, 0x04 | 0x10, null, 0, 0);
                return;
            }
            conns.put(sport, c);
            sendTcp(c, 0x02 | 0x10, null, 0, 0);
            c.seq = 1;
            final String svc = service;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        serve(svc, c);
                    } catch (Exception e) {
                        if (c.open) events.add("server " + svc + " error: " + e);
                    } finally {
                        c.close();
                    }
                }
            }, "fake-" + service);
            t.setDaemon(true);
            t.start();
            return;
        }
        DevConn c = conns.get(sport);
        if (c == null) return;
        synchronized (c.lock) {
            c.hostAck = ack;
            c.hostWin = win;
            if ((flags & 0x04) != 0) {
                c.open = false;
                conns.remove(sport);
            } else if (plen > 0) {
                if (seq != c.ack) throw new IOException("fake: host seq " + seq + " != expected " + c.ack);
                c.inBuf.write(b, payload, plen);
                c.ack += plen;
                sendTcp(c, 0x10, null, 0, 0);
            }
            c.lock.notifyAll();
        }
    }

    // ------------------------------------------------------------------ services

    private void serve(String service, DevConn c) throws Exception {
        if ("lockdown".equals(service)) serveLockdown(c);
        else if ("afc".equals(service)) serveAfc(c);
        else if ("instproxy".equals(service)) serveInstproxy(c);
    }

    private final class Channel extends PlistChannel {
        Channel(DuplexStream s) {
            super(s);
        }

        void setStream(DuplexStream s) {
            stream = s;
        }

        void reply(Map<String, Object> m) throws IOException {
            if (!binaryReplies) {
                send(m);
                return;
            }
            byte[] body = BinaryPlistWriter.write(m);
            byte[] frame = new byte[4 + body.length];
            putInt(frame, 0, body.length);
            System.arraycopy(body, 0, frame, 4, body.length);
            stream.out().write(frame);
        }
    }

    private void serveLockdown(DevConn c) throws Exception {
        Channel ch = new Channel(c);
        while (true) {
            Map<String, Object> req;
            try {
                req = ch.receive();
            } catch (IOException e) {
                return;
            }
            String r = (String) req.get("Request");
            events.add("lockdown " + r + (req.get("Key") != null ? " " + req.get("Key") : "") + (req.get("Service") != null ? " " + req.get("Service") : ""));
            Map<String, Object> rep = new LinkedHashMap<String, Object>();
            rep.put("Request", r);
            if ("QueryType".equals(r)) {
                rep.put("Type", "com.apple.mobile.lockdown");
            } else if ("GetValue".equals(r)) {
                String key = (String) req.get("Key");
                if ("UniqueDeviceID".equals(key)) rep.put("Value", "00008030-001A2D3C0E91802E");
                else if ("DeviceName".equals(key)) rep.put("Value", "Fake iPhone");
                else if ("ProductVersion".equals(key)) rep.put("Value", "18.1");
                else if ("ProductType".equals(key)) rep.put("Value", "iPhone12,1");
                else if ("DevicePublicKey".equals(key)) rep.put("Value", devicePublicKeyPem().getBytes("US-ASCII"));
                else rep.put("Error", "MissingValue");
            } else if ("Pair".equals(r)) {
                if (pairPendingReplies > 0) {
                    pairPendingReplies--;
                    rep.put("Error", "PairingDialogResponsePending");
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> pr = (Map<String, Object>) req.get("PairRecord");
                    checkPairRecord(pr);
                    if (!"2".equals(req.get("ProtocolVersion"))) throw new IOException("fake: ProtocolVersion");
                    pairRecord = pr;
                    rep.put("EscrowBag", new byte[]{1, 2, 3, 4});
                }
            } else if ("StartSession".equals(r)) {
                if (pairRecord == null || !pairRecord.get("HostID").equals(req.get("HostID"))) {
                    rep.put("Error", "InvalidHostID");
                } else {
                    rep.put("SessionID", "FAKE-SESSION");
                    rep.put("EnableSessionSSL", Boolean.TRUE);
                    ch.reply(rep);
                    ch.setStream(serverTls(c));
                    events.add("lockdown TLS " + ((TlsStream) ch.stream()).engine().getSession().getProtocol());
                    continue;
                }
            } else if ("StartService".equals(r)) {
                if (!(ch.stream() instanceof TlsStream)) throw new IOException("fake: StartService without session");
                String svc = (String) req.get("Service");
                int port = nextServicePort++;
                if ("com.apple.afc".equals(svc)) services.put(port, "afc");
                else if ("com.apple.mobile.installation_proxy".equals(svc)) services.put(port, "instproxy");
                else {
                    rep.put("Error", "InvalidService");
                    ch.reply(rep);
                    continue;
                }
                rep.put("Port", (long) port);
                rep.put("Service", svc);
            } else {
                rep.put("Error", "InvalidRequest");
            }
            ch.reply(rep);
        }
    }

    private void checkPairRecord(Map<String, Object> pr) throws Exception {
        for (String k : new String[]{"DeviceCertificate", "HostCertificate", "RootCertificate", "HostID", "SystemBUID"}) {
            if (pr.get(k) == null) throw new IOException("fake: pair record missing " + k);
        }
        X509Certificate root = PairingCrypto.loadCertificate(new String((byte[]) pr.get("RootCertificate"), "US-ASCII"));
        X509Certificate host = PairingCrypto.loadCertificate(new String((byte[]) pr.get("HostCertificate"), "US-ASCII"));
        X509Certificate dev = PairingCrypto.loadCertificate(new String((byte[]) pr.get("DeviceCertificate"), "US-ASCII"));
        root.verify(root.getPublicKey());
        host.verify(root.getPublicKey());
        dev.verify(root.getPublicKey());
        if (!dev.getPublicKey().equals(deviceKey.getPublic())) throw new IOException("fake: device cert has wrong public key");
        if (root.getBasicConstraints() < 0) throw new IOException("fake: root is not a CA");
    }

    /** Device acts as TLS server with its DeviceCertificate and requires the host certificate signed by RootCertificate. */
    private DuplexStream serverTls(DuplexStream raw) throws Exception {
        final X509Certificate root = PairingCrypto.loadCertificate(new String((byte[]) pairRecord.get("RootCertificate"), "US-ASCII"));
        X509Certificate dev = PairingCrypto.loadCertificate(new String((byte[]) pairRecord.get("DeviceCertificate"), "US-ASCII"));
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("dev", KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(deviceKey.getPrivate().getEncoded())),
                "x".toCharArray(), new X509Certificate[]{dev});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "x".toCharArray());
        // Extended, so SunJSSE does not re-parse the chain with the JDK parser (which rejects empty names)
        TrustManager tm = new X509ExtendedTrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
                try {
                    chain[0].verify(root.getPublicKey());
                } catch (Exception e) {
                    throw new java.security.cert.CertificateException("host cert not signed by pair-record root", e);
                }
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, java.net.Socket s) throws java.security.cert.CertificateException {
                checkClientTrusted(chain, authType);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine e) throws java.security.cert.CertificateException {
                checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, java.net.Socket s) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine e) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[]{root};
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), new TrustManager[]{tm}, null);
        SSLEngine e = ctx.createSSLEngine();
        e.setUseClientMode(false);
        e.setNeedClientAuth(true);
        return new TlsStream(raw, e);
    }

    private void serveAfc(DevConn c) throws Exception {
        Map<Long, String> handles = new HashMap<Long, String>();
        Map<Long, ByteArrayOutputStream> data = new HashMap<Long, ByteArrayOutputStream>();
        long nextHandle = 1;
        while (true) {
            byte[] h;
            try {
                h = c.readFully(40);
            } catch (IOException e) {
                return;
            }
            if (!new String(h, 0, 8, "US-ASCII").equals("CFA6LPAA")) throw new IOException("fake afc: magic");
            long entire = le(h, 8), thisLen = le(h, 16), num = le(h, 24), op = le(h, 32);
            byte[] hd = c.readFully((int) (thisLen - 40));
            byte[] payload = c.readFully((int) (entire - thisLen));
            long status = 0;
            byte[] replyHd = null;
            long replyOp = 1;
            if (op == 0x0A) {   // GetFileInfo
                String p = cstr(hd, 0);
                status = files.containsKey(p) || files.containsKey(p + "/") ? 0 : 8;
            } else if (op == 0x09) {   // MakeDir
                files.put(cstr(hd, 0) + "/", new byte[0]);
                events.add("afc mkdir " + cstr(hd, 0));
            } else if (op == 0x0D) {   // Open
                long mode = le(hd, 0);
                if (mode != 3) throw new IOException("fake afc: mode " + mode);
                String p = cstr(hd, 8);
                long hnd = nextHandle++;
                handles.put(hnd, p);
                data.put(hnd, new ByteArrayOutputStream());
                replyOp = 0x0E;
                replyHd = new byte[8];
                putLe(replyHd, 0, hnd);
            } else if (op == 0x10) {   // Write
                long hnd = le(hd, 0);
                if (!data.containsKey(hnd)) status = 7;
                else data.get(hnd).write(payload);
            } else if (op == 0x14) {   // Close
                long hnd = le(hd, 0);
                files.put(handles.get(hnd), data.get(hnd).toByteArray());
                events.add("afc wrote " + handles.get(hnd) + " " + data.get(hnd).size());
            } else {
                status = 1;
            }
            if (replyHd == null) {
                replyHd = new byte[8];
                putLe(replyHd, 0, status);
            }
            byte[] rep = new byte[40 + replyHd.length];
            System.arraycopy("CFA6LPAA".getBytes("US-ASCII"), 0, rep, 0, 8);
            putLe(rep, 8, rep.length);
            putLe(rep, 16, rep.length);
            putLe(rep, 24, num);
            putLe(rep, 32, replyOp);
            System.arraycopy(replyHd, 0, rep, 40, replyHd.length);
            c.out().write(rep);
        }
    }

    private void serveInstproxy(DevConn c) throws Exception {
        Channel ch = new Channel(c);
        Map<String, Object> req = ch.receive();
        if (!"Install".equals(req.get("Command"))) throw new IOException("fake instproxy: command");
        String path = (String) req.get("PackagePath");
        byte[] ipa = files.get(path);
        if (ipa == null) {
            ch.reply(Plist.dict("Error", "PackageInspectionFailed", "ErrorDescription", "Failed to unpack " + path));
            return;
        }
        String[] steps = {"CreatingStagingDirectory", "ExtractingPackage", "InspectingPackage", "VerifyingApplication", "InstallingApplication", "GeneratingApplicationMap"};
        for (int i = 0; i < steps.length; i++) {
            ch.reply(Plist.dict("Status", steps[i], "PercentComplete", (long) (5 + i * 15)));
        }
        installed.add(path + " sha1=" + hex(MessageDigest.getInstance("SHA-1").digest(ipa)));
        ch.reply(Plist.dict("Status", "Complete"));
    }

    // ------------------------------------------------------------------ helpers

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String cstr(byte[] b, int off) throws IOException {
        int end = off;
        while (end < b.length && b[end] != 0) end++;
        return new String(b, off, end - off, "UTF-8");
    }

    static long le(byte[] b, int o) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[o + i] & 0xff);
        return v;
    }

    static void putLe(byte[] b, int o, long v) {
        for (int i = 0; i < 8; i++) b[o + i] = (byte) (v >>> (8 * i));
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

    private static byte[] seq(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return tlv(0x30, o.toByteArray());
    }

    private static byte[] integer(BigInteger v) {
        return tlv(0x02, v.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] c) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        if (c.length < 128) o.write(c.length);
        else if (c.length < 256) {
            o.write(0x81);
            o.write(c.length);
        } else {
            o.write(0x82);
            o.write(c.length >> 8);
            o.write(c.length);
        }
        o.write(c, 0, c.length);
        return o.toByteArray();
    }
}
