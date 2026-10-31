package dev.phonemcp.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Boundary and quoted-string regressions for {@link AcceptHeader#acceptsMcp(String)}. */
public class AcceptHeaderRegressionTest {
    private static final String REQUIRED = "application/json, text/event-stream";

    private static void accepted(String h) { assertTrue(h, AcceptHeader.acceptsMcp(h)); }
    private static void rejected(String h) { assertFalse(h, AcceptHeader.acceptsMcp(h)); }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    @Test public void escapedQuoteAndBackslashDoNotEndQuotedValue() {
        // Value is: a"b\c ; the escaped quote must not close the string early.
        accepted(REQUIRED + ", text/html;v=\"a\\\"b\\\\c\"");
        // If the escaped quote ended the string, "b..." would follow and parsing would fail.
        accepted("text/html;v=\"x\\\", application/json\", " + REQUIRED);
    }

    @Test public void escapesAreDecodedBeforeComparison() {
        // "\x" unescapes to x, so these are the same range: conflicting q must reject.
        rejected(REQUIRED + ", text/html;v=\"\\x\";q=0.5, text/html;v=x;q=0.6");
        // Same decoded value and same q is an idempotent duplicate.
        accepted(REQUIRED + ", text/html;v=\"\\x\";q=0.5, text/html;v=x;q=0.5");
        // a\"b and a\\b decode differently, so they are distinct ranges and may differ in q.
        accepted(REQUIRED + ", text/html;v=\"a\\\"b\";q=0.5, text/html;v=\"a\\\\b\";q=0.6");
    }

    @Test public void commaInsideQuotedValueDoesNotSplitRanges() {
        // The only application/json here is inside a quoted value, so it does not count.
        rejected("text/html;v=\"x, application/json\", text/event-stream");
        rejected("text/event-stream;v=\"a,b\", application/json;v=\"c, text/event-stream\"");
        accepted("text/html;v=\"a, b, c\", " + REQUIRED);
    }

    @Test public void unterminatedQuotedValueReturnsFalse() {
        rejected(REQUIRED + ", text/html;v=\"abc");
        rejected(REQUIRED + ", text/html;v=\"");
        rejected(REQUIRED + ", text/html;v=\"abc\\\"");
        rejected(REQUIRED + ", text/html;v=\"abc\\");
        rejected("text/html;v=\"" + REQUIRED);
    }

    @Test public void exactSizeBoundary() {
        String exact = REQUIRED + repeat(" ", 8192 - REQUIRED.length());
        assertEquals(8192, exact.length());
        accepted(exact);
        String over = exact + " ";
        assertEquals(8193, over.length());
        rejected(over);
    }

    @Test public void exactRangeCountBoundary() {
        String thirtyTwo = REQUIRED + repeat(", text/plain", 30);
        accepted(thirtyTwo);
        rejected(thirtyTwo + ", text/plain");
    }

    @Test public void exactParameterCountBoundary() {
        StringBuilder eight = new StringBuilder("text/html");
        for (int i = 1; i <= 8; i++) eight.append(";p").append(i).append("=v");
        accepted(REQUIRED + ", " + eight);
        rejected(REQUIRED + ", " + eight + ";p9=v");
    }
}
