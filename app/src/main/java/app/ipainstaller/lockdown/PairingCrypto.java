package app.ipainstaller.lockdown;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import app.ipainstaller.util.Base64;

/**
 * Generates the certificates lockdownd expects in a pair record, mirroring
 * libimobiledevice's pair_record_generate_keys_and_certs(): an RSA-2048 root CA,
 * a host certificate and a device certificate (for the iPhone's own public key),
 * all signed with SHA-256 by the root key, with empty subject/issuer names.
 */
public final class PairingCrypto {
    private PairingCrypto() {}

    public static final class Generated {
        public String rootCertPem, rootKeyPem, hostCertPem, hostKeyPem, deviceCertPem;
    }

    private static final byte[] OID_RSA_ENCRYPTION = oid(1, 2, 840, 113549, 1, 1, 1);
    private static final byte[] OID_SHA256_WITH_RSA = oid(1, 2, 840, 113549, 1, 1, 11);
    private static final byte[] OID_BASIC_CONSTRAINTS = oid(2, 5, 29, 19);
    private static final byte[] OID_KEY_USAGE = oid(2, 5, 29, 15);
    private static final byte[] OID_SUBJECT_KEY_ID = oid(2, 5, 29, 14);

    /** @param devicePublicKeyPem the iPhone's DevicePublicKey ("BEGIN RSA PUBLIC KEY", PKCS#1). */
    public static Generated generate(byte[] devicePublicKeyPem) throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair root = gen.generateKeyPair();
        KeyPair host = gen.generateKeyPair();

        byte[] devicePkcs1 = Base64.fromPem(new String(devicePublicKeyPem, java.nio.charset.Charset.forName("US-ASCII")));
        byte[] deviceSpki = spkiFromPkcs1(devicePkcs1);

        byte[] rootSpki = root.getPublic().getEncoded();
        byte[] hostSpki = host.getPublic().getEncoded();

        byte[] rootCert = buildCert(rootSpki, root.getPrivate(), seq(ext(OID_BASIC_CONSTRAINTS, true, seq(bool(true)))));
        byte[] hostCert = buildCert(hostSpki, root.getPrivate(), seq(
                ext(OID_BASIC_CONSTRAINTS, true, seq()),
                ext(OID_KEY_USAGE, true, keyUsageDigitalSignatureKeyEncipherment())));
        byte[] deviceCert = buildCert(deviceSpki, root.getPrivate(), seq(
                ext(OID_BASIC_CONSTRAINTS, true, seq()),
                ext(OID_SUBJECT_KEY_ID, false, octets(sha1(pkcs1FromSpki(deviceSpki)))),
                ext(OID_KEY_USAGE, true, keyUsageDigitalSignatureKeyEncipherment())));

        Generated g = new Generated();
        g.rootCertPem = Base64.toPem("CERTIFICATE", rootCert);
        g.hostCertPem = Base64.toPem("CERTIFICATE", hostCert);
        g.deviceCertPem = Base64.toPem("CERTIFICATE", deviceCert);
        g.rootKeyPem = Base64.toPem("PRIVATE KEY", root.getPrivate().getEncoded());
        g.hostKeyPem = Base64.toPem("PRIVATE KEY", host.getPrivate().getEncoded());
        return g;
    }

    public static PrivateKey loadPrivateKey(String pem) throws GeneralSecurityException {
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.fromPem(pem)));
    }

    public static X509Certificate loadCertificate(String pem) throws GeneralSecurityException {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(Base64.fromPem(pem)));
    }

    // ------------------------------------------------------------------ certificate

    private static byte[] buildCert(byte[] subjectSpki, PrivateKey signer, byte[] extensions) throws GeneralSecurityException {
        long now = System.currentTimeMillis();
        byte[] algId = seq(OID_SHA256_WITH_RSA, nul());
        byte[] emptyName = seq();
        byte[] tbs = seq(
                tagged(0, integer(BigInteger.valueOf(2))),          // version v3
                integer(BigInteger.ONE),                             // serial
                algId,
                emptyName,                                           // issuer
                seq(utcTime(new Date(now - 60000L)), utcTime(new Date(now + 10L * 365 * 24 * 3600 * 1000))),
                emptyName,                                           // subject
                subjectSpki,
                tagged(3, extensions));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(signer);
        s.update(tbs);
        return seq(tbs, algId, bitString(s.sign()));
    }

    static byte[] spkiFromPkcs1(byte[] pkcs1) {
        return seq(seq(OID_RSA_ENCRYPTION, nul()), bitString(pkcs1));
    }

    /** Returns the contents of the subjectPublicKey BIT STRING (the PKCS#1 key) of an SPKI. */
    static byte[] pkcs1FromSpki(byte[] spki) {
        int[] outer = header(spki, 0);                 // SEQUENCE
        int p = outer[0];
        int[] alg = header(spki, p);                   // AlgorithmIdentifier
        p = alg[0] + alg[1];
        int[] bits = header(spki, p);                  // BIT STRING
        int start = bits[0] + 1;                       // skip unused-bits byte
        byte[] out = new byte[bits[1] - 1];
        System.arraycopy(spki, start, out, 0, out.length);
        return out;
    }

    /** @return {contentStart, contentLength} of the TLV at pos. */
    private static int[] header(byte[] d, int pos) {
        int len = d[pos + 1] & 0xff;
        int start = pos + 2;
        if ((len & 0x80) != 0) {
            int n = len & 0x7f;
            len = 0;
            for (int i = 0; i < n; i++) len = (len << 8) | (d[start + i] & 0xff);
            start += n;
        }
        return new int[]{start, len};
    }

    private static byte[] keyUsageDigitalSignatureKeyEncipherment() {
        // bits: digitalSignature(0), keyEncipherment(2) -> 1010 0000, 5 unused bits
        return tlv(0x03, new byte[]{0x05, (byte) 0xA0});
    }

    private static byte[] sha1(byte[] data) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-1").digest(data);
    }

    // ------------------------------------------------------------------ DER helpers

    private static byte[] ext(byte[] oid, boolean critical, byte[] value) {
        return critical ? seq(oid, bool(true), octets(value)) : seq(oid, octets(value));
    }

    static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(content.length + 6);
        o.write(tag);
        int len = content.length;
        if (len < 0x80) {
            o.write(len);
        } else if (len < 0x100) {
            o.write(0x81);
            o.write(len);
        } else if (len < 0x10000) {
            o.write(0x82);
            o.write(len >> 8);
            o.write(len);
        } else {
            o.write(0x83);
            o.write(len >> 16);
            o.write(len >> 8);
            o.write(len);
        }
        o.write(content, 0, content.length);
        return o.toByteArray();
    }

    static byte[] seq(byte[]... items) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] i : items) o.write(i, 0, i.length);
        return tlv(0x30, o.toByteArray());
    }

    private static byte[] tagged(int n, byte[] inner) {
        return tlv(0xA0 | n, inner);
    }

    private static byte[] integer(BigInteger v) {
        return tlv(0x02, v.toByteArray());
    }

    private static byte[] bool(boolean b) {
        return tlv(0x01, new byte[]{(byte) (b ? 0xff : 0x00)});
    }

    private static byte[] nul() {
        return new byte[]{0x05, 0x00};
    }

    private static byte[] octets(byte[] b) {
        return tlv(0x04, b);
    }

    private static byte[] bitString(byte[] b) {
        byte[] c = new byte[b.length + 1];
        System.arraycopy(b, 0, c, 1, b.length);
        return tlv(0x03, c);
    }

    private static byte[] utcTime(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes(java.nio.charset.Charset.forName("US-ASCII")));
    }

    private static byte[] oid(int... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(parts[0] * 40 + parts[1]);
        for (int i = 2; i < parts.length; i++) {
            long v = parts[i];
            byte[] tmp = new byte[10];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7f);
                v >>= 7;
            } while (v != 0);
            for (int j = n - 1; j >= 0; j--) o.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, o.toByteArray());
    }
}
