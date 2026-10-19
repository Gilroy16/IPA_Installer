package app.ipainstaller;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Test-only binary plist encoder (dict, string, integer, bool, data) so the fake device can answer in bplist00. */
final class BinaryPlistWriter {
    private final List<byte[]> objects = new ArrayList<byte[]>();

    static byte[] write(Object root) {
        BinaryPlistWriter w = new BinaryPlistWriter();
        int top = w.add(root);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('b'); out.write('p'); out.write('l'); out.write('i'); out.write('s'); out.write('t'); out.write('0'); out.write('0');
        int[] offsets = new int[w.objects.size()];
        for (int i = 0; i < w.objects.size(); i++) {
            offsets[i] = out.size();
            byte[] o = w.objects.get(i);
            out.write(o, 0, o.length);
        }
        int tableOffset = out.size();
        for (int off : offsets) { out.write(off >> 24); out.write(off >> 16); out.write(off >> 8); out.write(off); }
        byte[] trailer = new byte[32];
        trailer[6] = 4;   // offset size
        trailer[7] = 2;   // ref size
        putLong(trailer, 8, w.objects.size());
        putLong(trailer, 16, top);
        putLong(trailer, 24, tableOffset);
        out.write(trailer, 0, 32);
        return out.toByteArray();
    }

    private static void putLong(byte[] b, int o, long v) {
        for (int i = 0; i < 8; i++) b[o + i] = (byte) (v >>> (56 - 8 * i));
    }

    private int add(Object v) {
        int idx = objects.size();
        objects.add(null);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (v instanceof Boolean) {
            o.write(((Boolean) v) ? 0x09 : 0x08);
        } else if (v instanceof Long || v instanceof Integer) {
            long n = ((Number) v).longValue();
            o.write(0x13);
            for (int i = 7; i >= 0; i--) o.write((int) (n >>> (8 * i)));
        } else if (v instanceof String) {
            byte[] s = ((String) v).getBytes(java.nio.charset.Charset.forName("US-ASCII"));
            header(o, 0x5, s.length);
            o.write(s, 0, s.length);
        } else if (v instanceof byte[]) {
            byte[] d = (byte[]) v;
            header(o, 0x4, d.length);
            o.write(d, 0, d.length);
        } else if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            List<Integer> keys = new ArrayList<Integer>(), vals = new ArrayList<Integer>();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                keys.add(add(e.getKey()));
                vals.add(add(e.getValue()));
            }
            header(o, 0xD, m.size());
            for (int k : keys) { o.write(k >> 8); o.write(k); }
            for (int k : vals) { o.write(k >> 8); o.write(k); }
        } else {
            throw new IllegalArgumentException("unsupported " + v);
        }
        objects.set(idx, o.toByteArray());
        return idx;
    }

    private static void header(ByteArrayOutputStream o, int type, int len) {
        if (len < 15) {
            o.write((type << 4) | len);
        } else {
            o.write((type << 4) | 0xF);
            o.write(0x12);   // int, 4 bytes
            o.write(len >> 24); o.write(len >> 16); o.write(len >> 8); o.write(len);
        }
    }
}
