package app.ipainstaller.util;

/** Thin wrapper over java.util.Base64 (API 26+) that tolerates whitespace when decoding. */
public final class Base64 {
    private Base64() {}

    public static String encode(byte[] data) {
        return java.util.Base64.getEncoder().encodeToString(data);
    }

    public static byte[] decode(String text) {
        return java.util.Base64.getMimeDecoder().decode(text);
    }

    public static String toPem(String type, byte[] der) {
        String b64 = java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }

    /** Extracts the DER bytes from the first PEM block in the text. */
    public static byte[] fromPem(String pem) {
        int begin = pem.indexOf("-----BEGIN");
        if (begin < 0) throw new IllegalArgumentException("Not a PEM block");
        int bodyStart = pem.indexOf('\n', pem.indexOf("-----", begin + 10) + 5);
        int end = pem.indexOf("-----END", bodyStart);
        if (bodyStart < 0 || end < 0) throw new IllegalArgumentException("Malformed PEM block");
        return decode(pem.substring(bodyStart, end));
    }
}
