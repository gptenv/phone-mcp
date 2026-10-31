package dev.phonemcp.core;
import java.util.HashSet;
import java.util.Set;

/** Rejects org.json extensions and bounds nesting before object allocation. */
final class StrictJson {
    private final String s; private int p;
    private StrictJson(String s) { this.s = s; }
    static void validate(String s) { StrictJson parser = new StrictJson(s); parser.value(0); parser.ws(); if (parser.p != s.length()) parser.fail(); }
    private void fail() { throw new IllegalArgumentException("Invalid JSON"); }
    private void ws() { while (p < s.length() && " \t\r\n".indexOf(s.charAt(p)) >= 0) p++; }
    private boolean take(char c) { ws(); if (p < s.length() && s.charAt(p) == c) { p++; return true; } return false; }
    private void need(char c) { if (!take(c)) fail(); }
    private void value(int depth) {
        if (depth > 32) fail(); ws(); if (p == s.length()) fail();
        char c = s.charAt(p);
        if (c == '{') {
            p++; Set<String> keys = new HashSet<>(); if (take('}')) return;
            do { ws(); String key = string(); if (!keys.add(key)) fail(); need(':'); value(depth + 1); } while (take(',')); need('}');
        } else if (c == '[') { p++; if (take(']')) return; do { value(depth + 1); } while (take(',')); need(']'); }
        else if (c == '"') string();
        else if (c == 't') literal("true"); else if (c == 'f') literal("false"); else if (c == 'n') literal("null");
        else number();
    }
    private void literal(String text) { if (!s.startsWith(text, p)) fail(); p += text.length(); }
    private String string() {
        if (p >= s.length() || s.charAt(p++) != '"') { fail(); }
        StringBuilder result = new StringBuilder();
        while (p < s.length()) {
            char c = s.charAt(p++); if (c == '"') return result.toString(); if (c < 32) fail();
            if (c == '\\') {
                if (p == s.length()) fail(); char escaped = s.charAt(p++);
                switch (escaped) {
                    case '"': case '\\': case '/': c = escaped; break;
                    case 'b': c = '\b'; break; case 'f': c = '\f'; break; case 'n': c = '\n'; break; case 'r': c = '\r'; break; case 't': c = '\t'; break;
                    case 'u':
                        if (p + 4 > s.length()) fail(); int code = 0;
                        for (int i = 0; i < 4; i++) { int digit = Character.digit(s.charAt(p++), 16); if (digit < 0) fail(); code = code * 16 + digit; }
                        c = (char) code; break;
                    default: fail();
                }
            }
            result.append(c);
        }
        fail(); return "";
    }
    private void number() {
        int start = p; if (p < s.length() && s.charAt(p) == '-') p++;
        if (p == s.length()) fail();
        if (s.charAt(p) == '0') p++;
        else { if (s.charAt(p) < '1' || s.charAt(p) > '9') fail(); digits(); }
        if (p < s.length() && s.charAt(p) == '.') { p++; int begin = p; digits(); if (begin == p) fail(); }
        if (p < s.length() && (s.charAt(p) == 'e' || s.charAt(p) == 'E')) { p++; if (p < s.length() && (s.charAt(p) == '+' || s.charAt(p) == '-')) p++; int begin = p; digits(); if (begin == p) fail(); }
        if (start == p) fail();
    }
    private void digits() { while (p < s.length() && s.charAt(p) >= '0' && s.charAt(p) <= '9') p++; }
}
