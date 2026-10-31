package dev.phonemcp.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class AcceptHeaderTest {
    private static void yes(String h) { assertTrue(h, AcceptHeader.acceptsMcp(h)); }
    private static void no(String h) { assertFalse(String.valueOf(h), AcceptHeader.acceptsMcp(h)); }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    @Test public void exactRequiredRangesAccepted() {
        yes("application/json, text/event-stream");
        yes("text/event-stream,application/json");
        yes("APPLICATION/Json, Text/EVENT-stream");
        yes("application/json;q=0.001, text/event-stream;Q=1");
        yes("text/html;q=0, application/json, image/png, text/event-stream");
    }

    @Test public void bothRequiredRangesNeeded() {
        no(null);
        no("");
        no("application/json");
        no("text/event-stream");
        no(" , ,");
    }

    @Test public void wildcardsNeverSatisfyOrOverrideZero() {
        no("*/*");
        no("application/*, text/*");
        no("application/json, */*");
        no("application/json;q=0, */*, text/event-stream");
        no("application/json;q=0, application/*;q=1, text/event-stream");
        yes("*/*;q=0, application/json, text/event-stream"); // wildcard q=0 is irrelevant
    }

    @Test public void lookalikesDoNotCount() {
        no("application/json-seq, text/event-stream");
        no("application/jsonx, text/event-stream");
        no("application/vnd.api+json, text/event-stream");
        no("application/json, text/event-streams");
        no("application/json, text/eventstream");
        no("application/json, text/event-stream\u00a0"); // NBSP is not optional whitespace
        no("applicati\u043En/json, text/event-stream"); // Cyrillic o is not a token char
    }

    @Test public void nonpositiveRequiredRangesReject() {
        no("application/json;q=0, text/event-stream");
        no("application/json, text/event-stream;q=0.000");
        no("application/json, text/event-stream, application/json;charset=utf-8;q=0");
        no("application/json, text/event-stream, TEXT/EVENT-STREAM;foo=bar;q=0");
    }

    @Test public void weightSyntax() {
        yes("application/json;q=1.000, text/event-stream;q=1.");
        yes("application/json;q=0.1, text/event-stream;q=0.01");
        String[] bad = {"1.001", "1.1", "2", "-0.5", "0.0001", "1.0000", ".5", "01", "1e0", "0.5a",
            "+1", "0x1", "\"0.5\""};
        for (String q : bad) {
            no("application/json;q=" + q + ", text/event-stream");
            no("application/json, text/event-stream, text/html;q=" + q);
        }
        no("application/json;q=, text/event-stream");
        assertEquals(1000, AcceptHeader.parseWeight("1"));
        assertEquals(1000, AcceptHeader.parseWeight("1.0"));
        assertEquals(0, AcceptHeader.parseWeight("0."));
        assertEquals(1, AcceptHeader.parseWeight("0.001"));
        assertEquals(500, AcceptHeader.parseWeight("0.5"));
        assertEquals(990, AcceptHeader.parseWeight("0.99"));
        assertEquals(-1, AcceptHeader.parseWeight(""));
    }

    @Test public void duplicateQAndDuplicateParameterRejected() {
        no("application/json;q=1;q=1, text/event-stream");
        no("application/json;q=0.5;Q=0.5, text/event-stream");
        no("application/json;charset=a;CHARSET=a, text/event-stream");
    }

    @Test public void exactDuplicateRangeIsIdempotent() {
        yes("application/json, application/json, text/event-stream");
        yes("application/json;q=0.5, APPLICATION/JSON;q=0.500, text/event-stream");
        yes("application/json;q=1, application/json, text/event-stream"); // default q is 1
        yes("text/html;a=1;b=2, text/html;B=2;A=1, application/json, text/event-stream");
        yes("text/html;a=\"x\", text/html;a=x, application/json, text/event-stream");
    }

    @Test public void conflictingQForSameNormalizedRangeRejected() {
        no("application/json;q=0.5, application/json;q=0.6, text/event-stream");
        no("application/json, application/JSON;q=0.9, text/event-stream");
        no("text/html;a=1;b=2;q=0.1, text/html;b=2;a=1;q=0.2, application/json, text/event-stream");
        no("*/*;q=0.1, */*;q=0.2, application/json, text/event-stream");
    }

    @Test public void parameterizedRangesAreDistinct() {
        no("application/json;charset=utf-8, text/event-stream"); // no bare application/json
        yes("application/json;charset=utf-8, application/json, text/event-stream");
        yes("application/json;charset=utf-8;q=0.2, application/json;q=0.9, text/event-stream");
        yes("text/html;a=1;q=0.1, text/html;a=2;q=0.2, application/json, text/event-stream");
        yes("text/html;a=\"x;b=y\";q=0.1, text/html;a=x;b=y;q=0.2, application/json, text/event-stream");
        no("text/html;a=1;q=0.1, text/html;A=1;q=0.2, application/json, text/event-stream");
    }

    @Test public void quotedDelimitersAreNotSeparators() {
        yes("application/json;foo=\"a,b;q=0\", application/json, text/event-stream");
        yes("text/html;foo=\"esc\\\"aped, q=0\", application/json, text/event-stream");
        no("application/json, text/html;foo=\"x, text/event-stream\"");
        no("application/json, text/event-stream, text/html;foo=\"unterminated");
        no("application/json, text/event-stream, text/html;foo=\"bad\u0001\"");
        no("application/json, text/event-stream, text/html;foo=\"trailing\\");
    }

    @Test public void malformedSyntaxRejected() {
        no("application, application/json, text/event-stream");
        no("application/, application/json, text/event-stream");
        no("/json, application/json, text/event-stream");
        no("application/json;q, text/event-stream");
        no("application/json;=1, text/event-stream");
        no("application/json;;q=1, text/event-stream");
        no("application/json q=1, text/event-stream");
        no("application/json;q=1 0, text/event-stream");
        no("application /json, text/event-stream");
        no("application/ json, text/event-stream");
        no("application/json;q =1, text/event-stream");
        no("application/json;q= 1, text/event-stream");
        no("application/json;foo=\"a\"b, text/event-stream");
    }

    @Test public void optionalWhitespaceAroundDelimiters() {
        yes("  application/json ;q=0.5 ,text/event-stream  ");
        yes("application/json\t;\tq=1\t,\ttext/event-stream");
        yes(" ,application/json,, text/event-stream ,");
    }

    @Test public void byteBound() {
        String base = "application/json, text/event-stream";
        String atLimit = base + repeat(" ", AcceptHeader.MAX_HEADER_BYTES - base.length());
        assertEquals(AcceptHeader.MAX_HEADER_BYTES, atLimit.length());
        yes(atLimit);
        no(atLimit + " ");
        no("application/json, text/event-stream;x=\"\u0100\""); // not a header octet
        yes("application/json, text/event-stream, text/html;x=\"caf\u00e9\""); // obs-text octet
    }

    @Test public void rangeAndParameterBounds() {
        String required = "application/json, text/event-stream";
        yes(required + repeat(", text/plain", AcceptHeader.MAX_RANGES - 2));
        no(required + repeat(", text/plain", AcceptHeader.MAX_RANGES - 1));
        StringBuilder eight = new StringBuilder("text/html");
        for (int i = 0; i < AcceptHeader.MAX_PARAMETERS; i++) eight.append(";p").append(i).append("=v");
        yes(required + ", " + eight);
        no(required + ", " + eight + ";p8=v");
        StringBuilder sevenPlusQ = new StringBuilder("text/html");
        for (int i = 0; i < AcceptHeader.MAX_PARAMETERS - 1; i++) sevenPlusQ.append(";p").append(i).append("=v");
        yes(required + ", " + sevenPlusQ + ";q=0.5");
        no(required + ", " + sevenPlusQ + ";p7=v;q=0.5");
    }

    @Test public void trailingEmptyParameterReturnsFalseWithoutThrowing() {
        no("application/json, text/event-stream;foo=");
        no("application/json, text/event-stream;q=");
        no("application/json, text/event-stream;foo");
        no("application/json, text/event-stream;");
        no("application/json, text/event-stream;foo=\"");
        no("text/event-stream;foo=, application/json");
    }

    @Test public void invalidInputNeverThrows() {
        String[] inputs = {"\"", "\\", ";", ",", "/", "=", "a/b;", "a/b;c=", "a/b;c=\"", "a/b;q=1.",
            "\u0000", repeat(";", 9000), repeat("a/b,", 3000)};
        for (String h : inputs) assertFalse(AcceptHeader.acceptsMcp(h));
    }
}
