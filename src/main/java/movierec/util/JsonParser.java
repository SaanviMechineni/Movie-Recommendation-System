package movierec.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader (Java has none built in). Returns Map, List, String, Double, Boolean or null.
 * Use the static helpers (obj, list, str, num) to read values safely.
 */
public final class JsonParser {
    private final String s;
    private int i = 0;

    private JsonParser(String s) { this.s = s; }

    public static Object parse(String text) {
        JsonParser p = new JsonParser(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.fail("Unexpected trailing data");
        return v;
    }

    // ---------- safe accessors ----------
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Collections.<String, Object>emptyMap();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : Collections.<Object>emptyList();
    }

    public static String str(Object o, String def) { return o instanceof String ? (String) o : def; }
    public static double num(Object o, double def) { return o instanceof Double ? (Double) o : def; }

    // ---------- parser ----------
    private Object value() {
        if (i >= s.length()) throw fail("Unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            String key = string();
            ws();
            if (next() != ':') throw fail("Expected ':'");
            ws();
            m.put(key, value());
            ws();
            char c = next();
            if (c == '}') return m;
            if (c != ',') throw fail("Expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (peek() == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = next();
            if (c == ']') return l;
            if (c != ',') throw fail("Expected ',' or ']'");
        }
    }

    private String string() {
        if (next() != '"') throw fail("Expected string");
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw fail("Unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = next();
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw fail("Bad unicode escape");
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: throw fail("Bad escape");
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw fail("Unexpected character '" + s.charAt(i) + "'");
        try { return Double.valueOf(s.substring(start, i)); }
        catch (NumberFormatException ex) { throw fail("Bad number"); }
    }

    private void expect(String word) {
        if (!s.startsWith(word, i)) throw fail("Expected " + word);
        i += word.length();
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }
    private char next() { if (i >= s.length()) throw fail("Unexpected end"); return s.charAt(i++); }
    private IllegalArgumentException fail(String msg) {
        return new IllegalArgumentException("Invalid JSON at position " + i + ": " + msg);
    }
}
