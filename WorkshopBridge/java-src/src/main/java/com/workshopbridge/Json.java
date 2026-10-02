package com.workshopbridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser and writer. Handles objects, arrays, strings (with
 * escapes, including unicode escapes and surrogate pairs), numbers,
 * booleans and null.
 * Only what WorkshopBridge needs - not a general-purpose library.
 */
public final class Json {
    private Json() {}

    // ---------- parsing ----------

    public static Object parse(String s) {
        Parser p = new Parser(s);
        Object v = p.parseValue();
        p.skipWs();
        if (p.pos != s.length()) {
            throw new IllegalArgumentException("trailing data at pos " + p.pos);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object o) {
        return (List<Object>) o;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        IllegalArgumentException err(String m) {
            return new IllegalArgumentException(m + " at pos " + pos);
        }

        boolean peek(char c) {
            return pos < s.length() && s.charAt(pos) == c;
        }

        Object parseValue() {
            skipWs();
            if (pos >= s.length()) {
                throw err("unexpected end of input");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw err("unexpected char '" + c + "'");
            }
        }

        void expect(String lit) {
            if (!s.startsWith(lit, pos)) {
                throw err("expected '" + lit + "'");
            }
            pos += lit.length();
        }

        Map<String, Object> parseObject() {
            pos++; // {
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek('}')) {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw err("expected string key");
                }
                String k = parseString();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw err("expected ':'");
                }
                pos++;
                m.put(k, parseValue());
                skipWs();
                if (pos >= s.length()) {
                    throw err("unterminated object");
                }
                char ch = s.charAt(pos++);
                if (ch == '}') {
                    return m;
                }
                if (ch != ',') {
                    throw err("expected ',' or '}'");
                }
            }
        }

        List<Object> parseArray() {
            pos++; // [
            List<Object> l = new ArrayList<>();
            skipWs();
            if (peek(']')) {
                pos++;
                return l;
            }
            while (true) {
                l.add(parseValue());
                skipWs();
                if (pos >= s.length()) {
                    throw err("unterminated array");
                }
                char ch = s.charAt(pos++);
                if (ch == ']') {
                    return l;
                }
                if (ch != ',') {
                    throw err("expected ',' or ']'");
                }
            }
        }

        String parseString() {
            pos++; // "
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw err("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw err("bad escape");
                    }
                    char e = s.charAt(pos++);
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
                            if (pos + 4 > s.length()) {
                                throw err("bad \\u escape");
                            }
                            int hi = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            pos += 4;
                            if (Character.isHighSurrogate((char) hi)
                                    && pos + 6 <= s.length()
                                    && s.charAt(pos) == '\\' && s.charAt(pos + 1) == 'u') {
                                int lo = Integer.parseInt(s.substring(pos + 2, pos + 6), 16);
                                pos += 6;
                                sb.append(Character.toChars(
                                        Character.toCodePoint((char) hi, (char) lo)));
                            } else {
                                sb.append((char) hi);
                            }
                            break;
                        default:
                            throw err("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Number parseNumber() {
            int start = pos;
            if (peek('-')) {
                pos++;
            }
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
            boolean frac = false;
            if (peek('.')) {
                frac = true;
                pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (peek('e') || peek('E')) {
                frac = true;
                pos++;
                if (peek('+') || peek('-')) {
                    pos++;
                }
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            String num = s.substring(start, pos);
            try {
                if (!frac) {
                    try {
                        return Long.parseLong(num);
                    } catch (NumberFormatException ignored) {
                        // fall through to double
                    }
                }
                return Double.parseDouble(num);
            } catch (NumberFormatException ex) {
                throw err("bad number");
            }
        }
    }

    // ---------- writing ----------

    public static String stringify(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof String) {
            return quote((String) o);
        }
        if (o instanceof Number || o instanceof Boolean) {
            return o.toString();
        }
        if (o instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(quote(String.valueOf(e.getKey()))).append(':')
                        .append(stringify(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (o instanceof Iterable) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object v : (Iterable<?>) o) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(stringify(v));
            }
            return sb.append(']').toString();
        }
        return quote(o.toString());
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
