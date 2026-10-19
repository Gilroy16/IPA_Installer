package app.ipainstaller;

import static app.ipainstaller.util.I18n.t;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import app.ipainstaller.lockdown.LockdownClient;
import app.ipainstaller.lockdown.LockdownClient.LockdownException;
import app.ipainstaller.lockdown.PairRecord;
import app.ipainstaller.lockdown.PairingCrypto;
import app.ipainstaller.services.AfcClient;
import app.ipainstaller.services.InstallationProxy;
import app.ipainstaller.usbmux.MuxConnection;
import app.ipainstaller.usbmux.UsbMux;
import app.ipainstaller.usbmux.UsbPipe;
import app.ipainstaller.util.DuplexStream;

/**
 * The whole flow, platform independent: usbmux handshake, pairing (Trust dialog),
 * lockdown session, AFC upload to PublicStaging and installation_proxy Install,
 * the same steps as `ideviceinstaller install app.ipa`.
 */
public final class IpaInstaller {
    public static final String STAGING_DIR = "PublicStaging";
    private static final int CHUNK = 256 * 1024;
    private static final long PAIR_TIMEOUT_MS = 3 * 60 * 1000;

    public interface IpaSource {
        String name();

        long size();

        InputStream open() throws IOException;
    }

    public interface Listener {
        void log(String line);

        /** @param percent 0..100, or -1 when unknown */
        void status(String text, int percent);
    }

    public static final class DeviceInfo {
        public String udid, name, productVersion, productType;
    }

    private final PairRecord.Store store;
    private final Listener listener;
    private volatile boolean cancelled;

    public IpaInstaller(PairRecord.Store store, Listener listener) {
        this.store = store;
        this.listener = listener;
    }

    public void cancel() {
        cancelled = true;
    }

    /** @return number of successfully installed packages */
    public int installAll(UsbPipe pipe, List<IpaSource> sources) throws IOException {
        UsbMux mux = new UsbMux(pipe);
        mux.setLogger(new UsbMux.Logger() {
            @Override
            public void log(String msg) {
                listener.log(msg);
            }
        });
        try {
            listener.status(t("connecting"), -1);
            mux.start();
            DeviceInfo info = readDeviceInfo(mux);
            listener.log("iPhone: " + info.name + ", " + info.productType + ", iOS " + info.productVersion + ", UDID " + info.udid);
            PairRecord record = ensurePaired(mux, info.udid);

            int ok = 0;
            for (int i = 0; i < sources.size(); i++) {
                if (cancelled) throw new IOException(t("cancelled"));
                IpaSource src = sources.get(i);
                String prefix = sources.size() > 1 ? "[" + (i + 1) + "/" + sources.size() + "] " : "";
                try {
                    installOne(mux, record, src, prefix);
                    ok++;
                    listener.log(prefix + t("installed_ok", src.name()));
                } catch (IOException e) {
                    listener.log(prefix + t("install_error", src.name(), e.getMessage()));
                    mux.checkAliveOrThrow();
                }
            }
            return ok;
        } finally {
            mux.close();
        }
    }

    private DeviceInfo readDeviceInfo(UsbMux mux) throws IOException {
        LockdownClient ld = LockdownClient.connect(mux);
        try {
            String type = ld.queryType();
            if (!"com.apple.mobile.lockdown".equals(type)) throw new IOException("Unexpected lockdown type: " + type);
            DeviceInfo d = new DeviceInfo();
            d.udid = String.valueOf(ld.getValue(null, "UniqueDeviceID"));
            d.name = optValue(ld, "DeviceName");
            d.productVersion = optValue(ld, "ProductVersion");
            d.productType = optValue(ld, "ProductType");
            return d;
        } finally {
            ld.close();
        }
    }

    private static String optValue(LockdownClient ld, String key) throws IOException {
        try {
            return String.valueOf(ld.getValue(null, key));
        } catch (LockdownException e) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ pairing

    private PairRecord ensurePaired(UsbMux mux, String udid) throws IOException {
        PairRecord existing = store.load(udid);
        if (existing != null) {
            LockdownClient ld = LockdownClient.connect(mux);
            try {
                ld.startSession(existing);
                listener.log(t("paired_already"));
                return existing;
            } catch (LockdownException e) {
                listener.log(t("pair_rejected", e.error));
                store.delete(udid);
            } finally {
                ld.close();
            }
        }
        return pair(mux, udid);
    }

    private PairRecord pair(UsbMux mux, String udid) throws IOException {
        listener.status(t("generating_keys"), -1);
        PairRecord r = new PairRecord();
        LockdownClient ld = LockdownClient.connect(mux);
        try {
            Object pub = ld.getValue(null, "DevicePublicKey");
            if (!(pub instanceof byte[])) throw new IOException("iPhone did not return DevicePublicKey");
            PairingCrypto.Generated g = PairingCrypto.generate((byte[]) pub);
            r.rootCertPem = g.rootCertPem;
            r.rootKeyPem = g.rootKeyPem;
            r.hostCertPem = g.hostCertPem;
            r.hostKeyPem = g.hostKeyPem;
            r.deviceCertPem = g.deviceCertPem;
            r.hostId = UUID.randomUUID().toString().toUpperCase(Locale.US);
            r.systemBuid = store.systemBuid();
        } catch (GeneralSecurityException e) {
            throw new IOException("Key generation failed: " + e.getMessage(), e);
        } finally {
            ld.close();
        }

        long deadline = System.currentTimeMillis() + PAIR_TIMEOUT_MS;
        while (true) {
            if (cancelled) throw new IOException(t("cancelled"));
            ld = LockdownClient.connect(mux);
            try {
                Map<String, Object> reply = ld.pair(r);
                Object bag = reply.get("EscrowBag");
                if (bag instanceof byte[]) r.escrowBag = (byte[]) bag;
                store.save(udid, r);
                listener.log(t("paired"));
                return r;
            } catch (LockdownException e) {
                if ("PairingDialogResponsePending".equals(e.error)) {
                    listener.status(t("tap_trust"), -1);
                } else if ("PasswordProtected".equals(e.error)) {
                    listener.status(t("unlock"), -1);
                } else if ("UserDeniedPairing".equals(e.error)) {
                    throw new IOException(t("denied"));
                } else {
                    throw e;
                }
            } finally {
                ld.close();
            }
            if (System.currentTimeMillis() > deadline) throw new IOException(t("trust_timeout"));
            sleep(1500);
        }
    }

    // ------------------------------------------------------------------ services

    private DuplexStream startService(UsbMux mux, PairRecord record, String service) throws IOException {
        LockdownClient ld = LockdownClient.connect(mux);
        LockdownClient.ServiceInfo si;
        try {
            ld.startSession(record);
            si = ld.startService(service, null);
        } finally {
            ld.close();
        }
        MuxConnection c = mux.connect(si.port);
        return si.ssl ? LockdownClient.wrapTls(c, record) : c;
    }

    private void installOne(UsbMux mux, PairRecord record, IpaSource src, String prefix) throws IOException {
        String remote = STAGING_DIR + "/" + safeName(src.name());

        DuplexStream afcStream = startService(mux, record, AfcClient.SERVICE);
        try {
            AfcClient afc = new AfcClient(afcStream);
            if (!afc.exists(STAGING_DIR)) afc.makeDirectory(STAGING_DIR);
            long total = src.size();
            listener.status(prefix + t("copying", src.name()), 0);
            long handle = afc.open(remote, AfcClient.MODE_WRONLY);
            InputStream in = src.open();
            try {
                byte[] buf = new byte[CHUNK];
                long done = 0;
                long started = System.currentTimeMillis();
                int lastPct = -1;
                while (true) {
                    if (cancelled) throw new IOException(t("cancelled"));
                    int n = readUpTo(in, buf);
                    if (n <= 0) break;
                    afc.write(handle, buf, 0, n);
                    done += n;
                    int pct = total > 0 ? (int) (done * 100 / total) : -1;
                    if (pct != lastPct) {
                        lastPct = pct;
                        listener.status(prefix + (total > 0 ? t("copying_progress", src.name(), human(done), human(total))
                                : t("copying_progress_nototal", src.name(), human(done))), pct);
                    }
                }
                long ms = Math.max(1, System.currentTimeMillis() - started);
                listener.log(prefix + t("copied", human(done), ms / 1000.0, human(done * 1000 / ms)));
            } finally {
                in.close();
                afc.close(handle);
            }
        } finally {
            afcStream.close();
        }

        final String p = prefix;
        final String name = src.name();
        DuplexStream ipStream = startService(mux, record, InstallationProxy.SERVICE);
        InstallationProxy ip = new InstallationProxy(ipStream);
        try {
            listener.status(p + t("installing", name), 0);
            ip.install(remote, new InstallationProxy.Progress() {
                @Override
                public void onStatus(String status, int percent) {
                    listener.status(p + t("installing_status", name, status), percent);
                }
            });
        } finally {
            ip.close();
        }
    }

    private static int readUpTo(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    static String safeName(String name) {
        String base = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isEmpty() || base.startsWith(".")) base = "app" + base;
        if (!base.toLowerCase(Locale.US).endsWith(".ipa")) base += ".ipa";
        return base;
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " " + t("unit_b");
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f ", bytes / 1024.0) + t("unit_kb");
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f ", bytes / (1024.0 * 1024)) + t("unit_mb");
        return String.format(Locale.US, "%.2f ", bytes / (1024.0 * 1024 * 1024)) + t("unit_gb");
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }
}
