package dev.phonemcp.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Strict Accept-header admission check for MCP Streamable HTTP POST requests.
 *
 * <p>{@link #acceptsMcp(String)} returns true only when the header is well formed and contains
 * the exact, parameterless media ranges {@code application/json} and {@code text/event-stream}
 * (type and subtype case-insensitive), each with a quality value greater than zero. It never
 * throws and never logs; every invalid input returns false.
 *
 * <p>Policy (ours, not an RFC requirement where noted):
 * <ul>
 *   <li>Size: the raw header may be at most {@value #MAX_HEADER_BYTES} bytes. Characters above
 *       U+00FF cannot be header octets and are rejected, so the character count equals the byte
 *       count.</li>
 *   <li>Wildcards ({@code *}{@code /*}, {@code application/*}, {@code text/*}) and lookalikes such
 *       as {@code application/json-seq} or {@code text/event-streams} are valid syntax but never
 *       satisfy the requirement, and cannot override an explicit {@code q=0}.</li>
 *   <li>Parameterized ranges such as {@code application/json;charset=utf-8} are distinct ranges.
 *       They are allowed but do not satisfy the requirement. As a conservative policy, any range
 *       whose type/subtype is a required one and whose q is 0 rejects the header, with or
 *       without other parameters.</li>
 *   <li>Duplicates: ranges are normalized (lower-case type, subtype and parameter names;
 *       parameters sorted by name; quoted and token values compared by content). An exact
 *       duplicate with the same q is harmless. The same normalized range with a different q
 *       rejects the header. RFC 9110 does not define this case; rejecting it is our choice.</li>
 *   <li>Weights follow RFC 9110: {@code 0} to {@code 1} with at most three decimal places.
 *       A repeated q parameter, or any repeated parameter name in one range, is malformed.</li>
 *   <li>Bounds: at most {@value #MAX_RANGES} non-empty ranges and {@value #MAX_PARAMETERS}
 *       parameters per range including q. MCP clients send two ranges with few parameters; the
 *       bounds keep work and memory small and fixed, well inside the byte limit.</li>
 *   <li>Commas and semicolons inside quoted strings are not delimiters. SP and HTAB are optional
 *       whitespace around delimiters only; no whitespace is allowed around {@code /} or
 *       {@code =}. Empty list elements are ignored.</li>
 * </ul>
 * This is not a general content-negotiation implementation.
 */
public final class AcceptHeader {
    public static final int MAX_HEADER_BYTES = 8192;
    public static final int MAX_RANGES = 32;
    public static final int MAX_PARAMETERS = 8;

    private static final String JSON = "application/json";
    private static final String SSE = "text/event-stream";

    private AcceptHeader() { }

    /** True only for a well-formed header that explicitly accepts both MCP response types. */
    public static boolean acceptsMcp(String header) {
        if (header == null || header.length() > MAX_HEADER_BYTES) return false;
        for (int i = 0; i < header.length(); i++) {
            if (header.charAt(i) > 0xff) return false;
        }
        Map<String, Integer> seen = new HashMap<>(); // normalized range -> q in thousandths
        boolean json = false, sse = false;
        int ranges = 0;
        Scanner s = new Scanner(header);
        while (true) {
            s.skipOws();
            if (s.atEnd()) break;
            if (s.peek() == ',') { s.pos++; continue; }
            if (++ranges > MAX_RANGES) return false;
            String type = s.token();
            if (type == null || !s.consume('/')) return false;
            String subtype = s.token();
            if (subtype == null) return false;
            String base = type.toLowerCase(Locale.ROOT) + "/" + subtype.toLowerCase(Locale.ROOT);
            TreeMap<String, String> params = new TreeMap<>();
            int q = 1000;
            boolean sawQ = false;
            int count = 0;
            while (true) {
                s.skipOws();
                if (s.atEnd() || s.peek() == ',') break;
                if (!s.consume(';')) return false;
                s.skipOws();
                String name = s.token();
                if (name == null || !s.consume('=')) return false;
                if (++count > MAX_PARAMETERS) return false;
                name = name.toLowerCase(Locale.ROOT);
                String value = !s.atEnd() && s.peek() == '"' ? s.quotedString() : s.token();
                if (value == null) return false;
                if (name.equals("q")) {
                    if (sawQ || s.lastWasQuoted) return false;
                    sawQ = true;
                    q = parseWeight(value);
                    if (q < 0) return false;
                } else if (params.put(name, value) != null) {
                    return false;
                }
            }
            if ((base.equals(JSON) || base.equals(SSE)) && q == 0) return false;
            String key = normalize(base, params);
            Integer previous = seen.put(key, q);
            if (previous != null && previous != q) return false;
            if (params.isEmpty()) {
                if (base.equals(JSON)) json = true;
                else if (base.equals(SSE)) sse = true;
            }
        }
        return json && sse;
    }

    private static String normalize(String base, TreeMap<String, String> params) {
        StringBuilder b = new StringBuilder(base);
        for (Map.Entry<String, String> e : params.entrySet()) {
            // Length-prefix the value so content containing ';' or '=' cannot collide.
            b.append(';').append(e.getKey()).append('=').append(e.getValue().length()).append(':').append(e.getValue());
        }
        return b.toString();
    }

    /** RFC 9110 weight: thousandths in 0..1000, or -1 if malformed. */
    static int parseWeight(String v) {
        int n = v.length();
        if (n == 0 || n > 5) return -1;
        char first = v.charAt(0);
        if (first != '0' && first != '1') return -1;
        if (n == 1) return first == '1' ? 1000 : 0;
        if (v.charAt(1) != '.') return -1;
        int frac = 0;
        for (int i = 2; i < n; i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') return -1;
            frac = frac * 10 + (c - '0');
        }
        for (int i = n - 2; i < 3; i++) frac *= 10;
        if (first == '1') return frac == 0 ? 1000 : -1;
        return frac;
    }

    private static final class Scanner {
        final String s;
        int pos;
        boolean lastWasQuoted;
        Scanner(String s) { this.s = s; }
        boolean atEnd() { return pos >= s.length(); }
        char peek() { return s.charAt(pos); }
        void skipOws() { while (!atEnd() && (peek() == ' ' || peek() == '\t')) pos++; }
        boolean consume(char c) {
            if (!atEnd() && peek() == c) { pos++; return true; }
            return false;
        }
        String token() {
            lastWasQuoted = false;
            int start = pos;
            while (!atEnd() && isTchar(peek())) pos++;
            return pos > start ? s.substring(start, pos) : null;
        }
        /** Returns the unescaped content, or null if unterminated or containing invalid octets. */
        String quotedString() {
            lastWasQuoted = true;
            StringBuilder out = new StringBuilder();
            pos++;
            while (!atEnd()) {
                char c = s.charAt(pos++);
                if (c == '"') return out.toString();
                if (c == '\\') {
                    if (atEnd()) return null;
                    c = s.charAt(pos++);
                    if (c != '\t' && (c < 0x20 || c == 0x7f)) return null;
                } else if (c != '\t' && (c < 0x20 || c == 0x7f)) {
                    return null;
                }
                out.append(c);
            }
            return null;
        }
        static boolean isTchar(char c) {
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') return true;
            return "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
        }
    }
}
