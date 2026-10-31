package dev.phonemcp.core;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;

public class TextInputPolicyTest {
    private static String repeat(String text, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(text);
        return result.toString();
    }
    @Test public void asciiEmojiAndMixedBoundariesMatchCodePoints() {
        assertTrue(TextInputPolicy.accepts("")); assertFalse(TextInputPolicy.accepts(null));
        assertTrue(TextInputPolicy.accepts(repeat("a",1000)));
        assertFalse(TextInputPolicy.accepts(repeat("a",1001)));
        assertTrue(TextInputPolicy.accepts(repeat("😀",1000)));
        assertFalse(TextInputPolicy.accepts(repeat("😀",1001)));
        String mixed = repeat("😀",500) + repeat("a",500);
        assertTrue(TextInputPolicy.accepts(mixed)); assertFalse(TextInputPolicy.accepts(mixed+"a"));
        assertTrue(TextInputPolicy.accepts(repeat("a",999)+"😀")); // Pair spans UTF-16 indices 999/1000, one point.
        assertFalse(TextInputPolicy.accepts(repeat("a",1000)+"😀"));
        assertTrue(TextInputPolicy.accepts(repeat("\u0301",1000))); // Code points, not grapheme count.
        assertFalse(TextInputPolicy.accepts(repeat("\u0301",1001)));
        assertTrue(TextInputPolicy.accepts(repeat("a\u0301",500))); // 500 graphemes, 1000 code points.
        assertFalse(TextInputPolicy.accepts(repeat("a",2001))); // Immediate UTF-16 preflight bound.
    }
    @Test public void rejectsUnpairedSurrogatesWithoutNormalizing() {
        for (String text : new String[]{"\ud800", "\udc00", "x\ud800", "\ud800x", "x\ud800y", "\udc00x", "x\udc00", "x\udc00y", "\ud800\ud800", "\udc00\ud800"})
            assertFalse(TextInputPolicy.accepts(text));
        assertTrue(TextInputPolicy.accepts("\ud800\udc00"));
        assertTrue(TextInputPolicy.accepts("é")); assertTrue(TextInputPolicy.accepts("e\u0301"));
    }
    @Test public void dispatcherUsesSamePolicyAndRejectsWithoutPayloadEcho() {
        AtomicInteger executed = new AtomicInteger();
        McpDispatcher dispatcher = new McpDispatcher((name,args)->{executed.incrementAndGet();return new JSONObject().put("accepted",true);});
        for (String invalid : new String[]{"DO_NOT_ECHO\ud800", "DO_NOT_ECHO\udc00", "DO_NOT_ECHO" + repeat("😀",1000)}) {
            JSONObject request = new JSONObject().put("jsonrpc","2.0").put("id",1).put("method","tools/call")
                .put("params",new JSONObject().put("name","node_set_text").put("arguments",new JSONObject().put("nodeId","1:1").put("text",invalid)));
            JSONObject response = dispatcher.dispatch(request);
            assertEquals(-32602,response.getJSONObject("error").getInt("code"));
            assertFalse(response.toString().contains("DO_NOT_ECHO"));
        }
        assertEquals(0,executed.get());
        assertTrue(McpDispatcher.validArgs("node_set_text",new JSONObject().put("nodeId","1:1").put("text",repeat("😀",1000))));
    }
}
