package app.ipainstaller.lockdown;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;

import app.ipainstaller.util.Plist;

/** Pairing credentials for one iPhone, stored as an XML plist in the app's private storage. */
public final class PairRecord {
    private static final Charset ASCII = Charset.forName("US-ASCII");

    public String hostId, systemBuid;
    public String hostCertPem, hostKeyPem, rootCertPem, rootKeyPem, deviceCertPem;
    public byte[] escrowBag;

    /** The subset of the record that is sent to lockdownd in a Pair request. */
    public Map<String, Object> toPairRequest() {
        return Plist.dict(
                "DeviceCertificate", deviceCertPem.getBytes(ASCII),
                "HostCertificate", hostCertPem.getBytes(ASCII),
                "HostID", hostId,
                "RootCertificate", rootCertPem.getBytes(ASCII),
                "SystemBUID", systemBuid);
    }

    public Map<String, Object> toStored() {
        return Plist.dict(
                "DeviceCertificate", deviceCertPem.getBytes(ASCII),
                "HostCertificate", hostCertPem.getBytes(ASCII),
                "HostPrivateKey", hostKeyPem.getBytes(ASCII),
                "RootCertificate", rootCertPem.getBytes(ASCII),
                "RootPrivateKey", rootKeyPem.getBytes(ASCII),
                "HostID", hostId,
                "SystemBUID", systemBuid,
                "EscrowBag", escrowBag);
    }

    public static PairRecord fromStored(Map<String, Object> m) {
        PairRecord r = new PairRecord();
        r.deviceCertPem = str(m.get("DeviceCertificate"));
        r.hostCertPem = str(m.get("HostCertificate"));
        r.hostKeyPem = str(m.get("HostPrivateKey"));
        r.rootCertPem = str(m.get("RootCertificate"));
        r.rootKeyPem = str(m.get("RootPrivateKey"));
        r.hostId = (String) m.get("HostID");
        r.systemBuid = (String) m.get("SystemBUID");
        r.escrowBag = (byte[]) m.get("EscrowBag");
        return r;
    }

    private static String str(Object o) {
        if (o instanceof byte[]) return new String((byte[]) o, ASCII);
        return (String) o;
    }

    /** Directory-backed store, one file per device UDID. */
    public static final class Store {
        private final File dir;

        public Store(File dir) {
            this.dir = dir;
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }

        private File file(String udid) {
            return new File(dir, udid.replaceAll("[^A-Za-z0-9-]", "_") + ".plist");
        }

        @SuppressWarnings("unchecked")
        public PairRecord load(String udid) {
            File f = file(udid);
            if (!f.exists()) return null;
            try {
                byte[] data = new byte[(int) f.length()];
                FileInputStream in = new FileInputStream(f);
                try {
                    int off = 0;
                    while (off < data.length) {
                        int n = in.read(data, off, data.length - off);
                        if (n < 0) break;
                        off += n;
                    }
                } finally {
                    in.close();
                }
                return fromStored((Map<String, Object>) Plist.parse(data));
            } catch (Exception e) {
                return null;
            }
        }

        public void save(String udid, PairRecord r) throws IOException {
            File tmp = new File(dir, "tmp.plist");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(Plist.toXml(r.toStored()));
            } finally {
                out.close();
            }
            if (!tmp.renameTo(file(udid))) throw new IOException("Cannot save pair record");
        }

        public void delete(String udid) {
            //noinspection ResultOfMethodCallIgnored
            file(udid).delete();
        }

        /** Stable per-installation SystemBUID (what usbmuxd keeps in SystemConfiguration.plist). */
        public String systemBuid() throws IOException {
            File f = new File(dir, "SystemBUID");
            if (f.exists()) {
                byte[] b = new byte[(int) f.length()];
                FileInputStream in = new FileInputStream(f);
                try {
                    if (in.read(b) == b.length) return new String(b, ASCII).trim();
                } finally {
                    in.close();
                }
            }
            String id = java.util.UUID.randomUUID().toString().toUpperCase(java.util.Locale.US);
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(id.getBytes(ASCII));
            } finally {
                out.close();
            }
            return id;
        }
    }
}
