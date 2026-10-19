package app.ipainstaller.util;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Minimal property list codec.
 * Values: String, Long, Double, Boolean, byte[], Date, List&lt;Object&gt;, Map&lt;String,Object&gt;.
 * Writes XML; reads both XML and binary ("bplist00") formats.
 */
public final class Plist {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long APPLE_EPOCH_MS = 978307200000L;

    private Plist() {}

    public static Map<String, Object> dict(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---------------------------------------------------------------- XML writer

    public static byte[] toXml(Object root) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n");
        sb.append("<plist version=\"1.0\">\n");
        writeXml(sb, root, 0);
        sb.append("</plist>\n");
        return sb.toString().getBytes(UTF8);
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) sb.append('\t');
    }

    @SuppressWarnings("unchecked")
    private static void writeXml(StringBuilder sb, Object v, int level) {
        indent(sb, level);
        if (v instanceof String) {
            sb.append("<string>").append(escape((String) v)).append("</string>\n");
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "<true/>\n" : "<false/>\n");
        } else if (v instanceof Integer || v instanceof Long || v instanceof Short) {
            sb.append("<integer>").append(((Number) v).longValue()).append("</integer>\n");
        } else if (v instanceof Double || v instanceof Float) {
            sb.append("<real>").append(((Number) v).doubleValue()).append("</real>\n");
        } else if (v instanceof byte[]) {
            sb.append("<data>").append(Base64.encode((byte[]) v)).append("</data>\n");
        } else if (v instanceof Date) {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            sb.append("<date>").append(f.format((Date) v)).append("</date>\n");
        } else if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) {
                sb.append("<dict/>\n");
                return;
            }
            sb.append("<dict>\n");
            for (Map.Entry<String, Object> e : m.entrySet()) {
                indent(sb, level + 1);
                sb.append("<key>").append(escape(e.getKey())).append("</key>\n");
                writeXml(sb, e.getValue(), level + 1);
            }
            indent(sb, level);
            sb.append("</dict>\n");
        } else if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            if (l.isEmpty()) {
                sb.append("<array/>\n");
                return;
            }
            sb.append("<array>\n");
            for (Object o : l) writeXml(sb, o, level + 1);
            indent(sb, level);
            sb.append("</array>\n");
        } else {
            throw new IllegalArgumentException("Unsupported plist value: " + v);
        }
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- parsing

    public static Object parse(byte[] data) throws IOException {
        if (data.length >= 8 && data[0] == 'b' && data[1] == 'p' && data[2] == 'l' && data[3] == 'i'
                && data[4] == 's' && data[5] == 't' && data[6] == '0' && data[7] == '0') {
            return new BinaryReader(data).read();
        }
        return parseXml(data);
    }

    private static Object parseXml(byte[] data) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setValidating(false);
            f.setExpandEntityReferences(false);
            try {
                f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            } catch (Exception ignored) {
                // not supported by every parser implementation
            }
            DocumentBuilder b = f.newDocumentBuilder();
            b.setEntityResolver(new org.xml.sax.EntityResolver() {
                @Override
                public org.xml.sax.InputSource resolveEntity(String publicId, String systemId) {
                    return new org.xml.sax.InputSource(new java.io.StringReader(""));
                }
            });
            Document doc = b.parse(new ByteArrayInputStream(data));
            Element root = doc.getDocumentElement();
            Element first = firstElement(root);
            if (first == null) return null;
            return parseElement(first);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid XML plist: " + e.getMessage(), e);
        }
    }

    private static Element firstElement(Node n) {
        NodeList children = n.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) return (Element) children.item(i);
        }
        return null;
    }

    private static Object parseElement(Element e) throws IOException {
        String tag = e.getTagName();
        String text = e.getTextContent();
        if ("string".equals(tag)) return text;
        if ("integer".equals(tag)) return Long.parseLong(text.trim());
        if ("real".equals(tag)) return Double.parseDouble(text.trim());
        if ("true".equals(tag)) return Boolean.TRUE;
        if ("false".equals(tag)) return Boolean.FALSE;
        if ("data".equals(tag)) return Base64.decode(text);
        if ("date".equals(tag)) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return f.parse(text.trim());
            } catch (java.text.ParseException ex) {
                throw new IOException("Bad date: " + text);
            }
        }
        if ("array".equals(tag)) {
            List<Object> l = new ArrayList<Object>();
            NodeList children = e.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element) l.add(parseElement((Element) children.item(i)));
            }
            return l;
        }
        if ("dict".equals(tag)) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            NodeList children = e.getChildNodes();
            String key = null;
            for (int i = 0; i < children.getLength(); i++) {
                if (!(children.item(i) instanceof Element)) continue;
                Element c = (Element) children.item(i);
                if ("key".equals(c.getTagName())) {
                    key = c.getTextContent();
                } else {
                    if (key == null) throw new IOException("dict value without key");
                    m.put(key, parseElement(c));
                    key = null;
                }
            }
            return m;
        }
        throw new IOException("Unknown plist element <" + tag + ">");
    }

    private static final class BinaryReader {
        private final byte[] d;
        private int offsetSize, refSize;
        private long[] offsets;

        BinaryReader(byte[] d) {
            this.d = d;
        }

        Object read() throws IOException {
            if (d.length < 40) throw new IOException("Binary plist too short");
            int t = d.length - 32;
            offsetSize = d[t + 6] & 0xff;
            refSize = d[t + 7] & 0xff;
            long numObjects = be(t + 8, 8);
            long topObject = be(t + 16, 8);
            long tableOffset = be(t + 24, 8);
            if (numObjects > Integer.MAX_VALUE / 8) throw new IOException("Binary plist too large");
            offsets = new long[(int) numObjects];
            for (int i = 0; i < numObjects; i++) {
                offsets[i] = be((int) tableOffset + i * offsetSize, offsetSize);
            }
            return obj((int) topObject, 0);
        }

        private long be(int pos, int len) throws IOException {
            if (pos < 0 || pos + len > d.length) throw new IOException("Binary plist out of bounds");
            long v = 0;
            for (int i = 0; i < len; i++) v = (v << 8) | (d[pos + i] & 0xff);
            return v;
        }

        private int[] lengthAt(int pos, int info) throws IOException {
            // returns {length, dataStart}
            if (info != 0x0f) return new int[]{info, pos + 1};
            int marker = d[pos + 1] & 0xff;
            int n = 1 << (marker & 0x0f);
            long len = be(pos + 2, n);
            return new int[]{(int) len, pos + 2 + n};
        }

        private Object obj(int ref, int depth) throws IOException {
            if (depth > 64) throw new IOException("Binary plist nesting too deep");
            if (ref < 0 || ref >= offsets.length) throw new IOException("Bad object ref");
            int pos = (int) offsets[ref];
            int marker = d[pos] & 0xff;
            int type = marker >> 4, info = marker & 0x0f;
            switch (type) {
                case 0x0:
                    if (info == 0x8) return Boolean.FALSE;
                    if (info == 0x9) return Boolean.TRUE;
                    return null;
                case 0x1: {
                    int n = 1 << info;
                    long v = be(pos + 1, n);
                    if (n == 1) v &= 0xff;
                    else if (n == 2) v &= 0xffff;
                    else if (n == 4) v &= 0xffffffffL;
                    return v;
                }
                case 0x2: {
                    int n = 1 << info;
                    long bits = be(pos + 1, n);
                    return n == 4 ? (double) Float.intBitsToFloat((int) bits) : Double.longBitsToDouble(bits);
                }
                case 0x3: {
                    double secs = Double.longBitsToDouble(be(pos + 1, 8));
                    return new Date(APPLE_EPOCH_MS + (long) (secs * 1000));
                }
                case 0x4: {
                    int[] l = lengthAt(pos, info);
                    byte[] out = new byte[l[0]];
                    System.arraycopy(d, l[1], out, 0, l[0]);
                    return out;
                }
                case 0x5: {
                    int[] l = lengthAt(pos, info);
                    return new String(d, l[1], l[0], Charset.forName("US-ASCII"));
                }
                case 0x6: {
                    int[] l = lengthAt(pos, info);
                    return new String(d, l[1], l[0] * 2, Charset.forName("UTF-16BE"));
                }
                case 0x8:
                    return be(pos + 1, info + 1);
                case 0xA: {
                    int[] l = lengthAt(pos, info);
                    List<Object> list = new ArrayList<Object>(l[0]);
                    for (int i = 0; i < l[0]; i++) {
                        list.add(obj((int) be(l[1] + i * refSize, refSize), depth + 1));
                    }
                    return list;
                }
                case 0xD: {
                    int[] l = lengthAt(pos, info);
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    int n = l[0];
                    for (int i = 0; i < n; i++) {
                        Object k = obj((int) be(l[1] + i * refSize, refSize), depth + 1);
                        Object v = obj((int) be(l[1] + (n + i) * refSize, refSize), depth + 1);
                        m.put(String.valueOf(k), v);
                    }
                    return m;
                }
                default:
                    throw new IOException("Unsupported binary plist type 0x" + Integer.toHexString(type));
            }
        }
    }
}
