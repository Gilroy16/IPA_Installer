package app.ipainstaller;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import app.ipainstaller.lockdown.PairRecord;
import app.ipainstaller.lockdown.PairingCrypto;
import app.ipainstaller.util.Base64;
import app.ipainstaller.util.Plist;

/** Plain-main test runner (no test framework needed): plist codec, certificates and the full flow against FakeIphone. */
public final class SelfTest {
    private static int failures;

    public static void main(String[] args) throws Exception {
        // Android's default X.509 parser (Conscrypt) accepts empty names; on the JDK use BouncyCastle for that
        java.security.Security.insertProviderAt(new org.bouncycastle.jce.provider.BouncyCastleProvider(), 1);
        app.ipainstaller.util.I18n.setLang(args.length > 1 ? args[1] : "ru");
        plistTests();
        cryptoTests(args.length > 0 ? new File(args[0]) : null);
        endToEnd();
        System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    @SuppressWarnings("unchecked")
    private static void plistTests() throws Exception {
        System.out.println("plist");
        Map<String, Object> m = Plist.dict("Label", "IPAInstaller", "Request", "Pair", "N", 42L, "B", Boolean.TRUE,
                "D", new byte[]{1, 2, 3}, "Nested", Plist.dict("ExtendedPairingErrors", Boolean.TRUE, "Esc<&>", "a<b&c"),
                "L", Arrays.<Object>asList("x", 7L), "E", Plist.dict());
        Map<String, Object> back = (Map<String, Object>) Plist.parse(Plist.toXml(m));
        check("Pair".equals(back.get("Request")) && Long.valueOf(42).equals(back.get("N")) && Boolean.TRUE.equals(back.get("B")), "XML round trip scalars");
        check(Arrays.equals(new byte[]{1, 2, 3}, (byte[]) back.get("D")), "XML round trip data");
        check("a<b&c".equals(((Map<String, Object>) back.get("Nested")).get("Esc<&>")), "XML escaping");
        check(((Map<String, Object>) back.get("E")).isEmpty() && ((List<Object>) back.get("L")).size() == 2, "XML empty dict / array");

        // produced by Python's plistlib (FMT_BINARY)
        byte[] bp = Base64.decode("YnBsaXN0MDDaAQIDBAUGBwgJCgsMDQ4UFRYXGBlTQmlnVEJsb2JURmxhZ1RMaXN0U05lZ1NPZmZfEA9QZXJjZW50Q29tcGxldGVUUmVhbFZTdGF0dXNUV2hlbhMAAAEAAAAAAEgAAQJoZWxsbwmjDxARUWEQAdESE1F4UXkT//////////sIECojQAoAAAAAAABuANwAbgDvAGMAbwBkAGUAIARBBEIEQAQ+BDoEMDNBxfRcrIAAAAgdISYrMDQ4Sk9WW2RtbnJ0dnl7fYaHiZKvAAAAAAAAAQEAAAAAAAAAGgAAAAAAAAAAAAAAAAAAALg=");
        Map<String, Object> b = (Map<String, Object>) Plist.parse(bp);
        check("Ünïcode строка".equals(b.get("Status")), "bplist UTF-16 string");
        check(Long.valueOf(42).equals(b.get("PercentComplete")) && Long.valueOf(1L << 40).equals(b.get("Big")) && Long.valueOf(-5).equals(b.get("Neg")), "bplist integers");
        check(Boolean.TRUE.equals(b.get("Flag")) && Boolean.FALSE.equals(b.get("Off")) && Double.valueOf(3.25).equals(b.get("Real")), "bplist bool/real");
        check(new String((byte[]) b.get("Blob"), 3, 5, "US-ASCII").equals("hello"), "bplist data");
        check(((Map<String, Object>) ((List<Object>) b.get("List")).get(2)).get("x").equals("y"), "bplist nested array/dict");
        check(((java.util.Date) b.get("When")).getTime() == 1714979289000L, "bplist date");
    }

    private static void cryptoTests(File outDir) throws Exception {
        System.out.println("pairing certificates");
        FakeIphone dev = new FakeIphone();
        PairingCrypto.Generated g = PairingCrypto.generate(dev.devicePublicKeyPem().getBytes("US-ASCII"));
        X509Certificate root = PairingCrypto.loadCertificate(g.rootCertPem);
        X509Certificate host = PairingCrypto.loadCertificate(g.hostCertPem);
        X509Certificate devCert = PairingCrypto.loadCertificate(g.deviceCertPem);
        root.verify(root.getPublicKey());
        host.verify(root.getPublicKey());
        devCert.verify(root.getPublicKey());
        check(true, "root/host/device certificates signed by root key");
        check(root.getBasicConstraints() >= 0 && host.getBasicConstraints() < 0, "basicConstraints CA:TRUE on root only");
        check(devCert.getPublicKey().equals(dev.deviceKey.getPublic()), "device certificate carries the iPhone's public key");
        check("SHA256withRSA".equalsIgnoreCase(devCert.getSigAlgName()), "SHA-256 signatures");
        check(host.getKeyUsage() != null && host.getKeyUsage()[0] && host.getKeyUsage()[2], "keyUsage digitalSignature+keyEncipherment");
        check(PairingCrypto.loadPrivateKey(g.hostKeyPem) != null, "host private key round trip");
        if (outDir != null) {
            outDir.mkdirs();
            Files.write(new File(outDir, "root.pem").toPath(), g.rootCertPem.getBytes("US-ASCII"));
            Files.write(new File(outDir, "host.pem").toPath(), g.hostCertPem.getBytes("US-ASCII"));
            Files.write(new File(outDir, "device.pem").toPath(), g.deviceCertPem.getBytes("US-ASCII"));
            Files.write(new File(outDir, "device_pub.pem").toPath(), dev.devicePublicKeyPem().getBytes("US-ASCII"));
        }
    }

    private static final class MemSource implements IpaInstaller.IpaSource {
        final String name;
        final byte[] data;

        MemSource(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }

        public String name() {
            return name;
        }

        public long size() {
            return data.length;
        }

        public InputStream open() {
            return new ByteArrayInputStream(data);
        }
    }

    private static String sha1(byte[] d) throws Exception {
        return FakeIphone.hex(MessageDigest.getInstance("SHA-1").digest(d));
    }

    private static void endToEnd() throws Exception {
        System.out.println("end to end against simulated iPhone");
        File dir = Files.createTempDirectory("pairing").toFile();
        PairRecord.Store store = new PairRecord.Store(dir);
        final List<String> log = Collections.synchronizedList(new ArrayList<String>());
        final List<String> statuses = Collections.synchronizedList(new ArrayList<String>());
        IpaInstaller.Listener l = new IpaInstaller.Listener() {
            public void log(String line) {
                log.add(line);
            }

            public void status(String text, int percent) {
                if (statuses.isEmpty() || !statuses.get(statuses.size() - 1).equals(text)) statuses.add(text);
            }
        };

        Random rnd = new Random(1);
        byte[] big = new byte[5 * 1024 * 1024 + 12345];
        rnd.nextBytes(big);
        byte[] small = new byte[3000];
        rnd.nextBytes(small);
        List<IpaInstaller.IpaSource> sources = new ArrayList<IpaInstaller.IpaSource>();
        sources.add(new MemSource("Моё приложение.ipa", big));
        sources.add(new MemSource("small.ipa", small));

        // 1. first connection: pairing with two "tap Trust" prompts, binary replies
        FakeIphone dev = new FakeIphone();
        long t0 = System.currentTimeMillis();
        int ok = new IpaInstaller(store, l).installAll(dev, sources);
        long ms = System.currentTimeMillis() - t0;
        check(ok == 2, "installs both packages (" + ok + "/2, " + ms + " ms)");
        check(dev.installed.size() == 2
                        && dev.installed.get(0).equals("PublicStaging/" + IpaInstaller.safeName("Моё приложение.ipa") + " sha1=" + sha1(big))
                        && dev.installed.get(1).equals("PublicStaging/small.ipa sha1=" + sha1(small)),
                "uploaded bytes match (SHA-1) " + dev.installed);
        check(dev.events.contains("version 2") && dev.events.contains("setup 7"), "usbmux v2 handshake + setup");
        check(statuses.contains(app.ipainstaller.util.I18n.t("tap_trust")), "Trust prompt shown while pairing is pending");
        check(dev.zlpViolations == 0, "no host packet is a multiple of wMaxPacketSize (" + dev.packetsFromHost + " packets, max " + dev.maxHostPacket + " bytes)");
        check(dev.maxHostPacket <= 3 * 16384, "packets within USB_MTU");
        check(store.load("00008030-001A2D3C0E91802E") != null && store.load("00008030-001A2D3C0E91802E").escrowBag != null, "pair record saved with EscrowBag");
        String tls = null;
        for (String e : dev.events) if (e.startsWith("lockdown TLS")) tls = e;
        check(tls != null, "lockdown session upgraded to TLS with client certificate (" + tls + ")");
        dumpOnFailure(log, dev);

        // 2. second connection to the same phone: no new pairing, XML replies
        FakeIphone dev2 = new FakeIphone(dev.deviceKey);
        dev2.binaryReplies = false;
        dev2.pairRecord = toPairRequestMap(store);
        int ok2 = new IpaInstaller(store, l).installAll(dev2, Collections.<IpaInstaller.IpaSource>singletonList(sources.get(1)));
        boolean paired = false;
        for (String e : dev2.events) if (e.startsWith("lockdown Pair")) paired = true;
        check(ok2 == 1 && !paired, "reuses saved pairing (no Pair request), XML replies");
        dumpOnFailure(log, dev2);

        // 3. phone forgot us (e.g. "Reset Location & Privacy"): re-pair automatically
        FakeIphone dev3 = new FakeIphone();
        dev3.pairPendingReplies = 0;
        int ok3 = new IpaInstaller(store, l).installAll(dev3, Collections.<IpaInstaller.IpaSource>singletonList(sources.get(1)));
        check(ok3 == 1 && dev3.events.contains("lockdown Pair"), "re-pairs when saved pairing is rejected (InvalidHostID)");
        dumpOnFailure(log, dev3);

        // 4. install error is reported, not swallowed
        FakeIphone dev4 = new FakeIphone();
        dev4.pairPendingReplies = 0;
        final IpaInstaller.IpaSource broken = new IpaInstaller.IpaSource() {
            public String name() {
                return "broken.ipa";
            }

            public long size() {
                return 10;
            }

            public InputStream open() throws IOException {
                throw new IOException("файл недоступен");
            }
        };
        log.clear();
        int ok4 = new IpaInstaller(new PairRecord.Store(Files.createTempDirectory("p4").toFile()), l)
                .installAll(dev4, Arrays.asList(broken, sources.get(1)));
        boolean reported = false;
        for (String s : log) if (s.contains(app.ipainstaller.util.I18n.t("install_error", "broken.ipa", "файл недоступен"))) reported = true;
        check(ok4 == 1 && reported, "a failing file is reported and the next one still installs");
    }

    private static Map<String, Object> toPairRequestMap(PairRecord.Store store) {
        return store.load("00008030-001A2D3C0E91802E").toPairRequest();
    }

    private static void dumpOnFailure(List<String> log, FakeIphone dev) {
        if (failures == 0) return;
        System.out.println("---- log");
        for (String s : log) System.out.println("  " + s);
        System.out.println("---- device events");
        for (String s : dev.events) System.out.println("  " + s);
    }
}
