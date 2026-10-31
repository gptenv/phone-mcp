package dev.phonemcp.core;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;

/** Serializes without first allocating an unbounded intermediate JSON string.
 * UTF-8 accounting handles surrogate pairs; lone surrogates are JSON-escaped.
 * Input values still belong to the controller; this bounds serialization, not arbitrary controller allocation.
 */
final class BoundedJson {
    static final class LimitException extends IllegalArgumentException { LimitException() { super("JSON output exceeds safety limit"); } }
    private final StringBuilder out = new StringBuilder(1024);
    private int remaining;
    private int visited;
    private BoundedJson(int maxBytes) { remaining = maxBytes; }
    static String encode(Object value, int maxBytes) { BoundedJson writer = new BoundedJson(maxBytes); writer.value(value, 0); return writer.out.toString(); }
    private void append(char c) {
        remaining -= c <= 0x7f ? 1 : c <= 0x7ff ? 2 : 3;
        if (remaining < 0) throw new LimitException();
        out.append(c);
    }
    private void literal(String s) { for (int i=0; i<s.length(); i++) append(s.charAt(i)); }
    private void string(String s) {
        append('"');
        for (int i=0; i<s.length(); i++) {
            char c=s.charAt(i);
            switch(c) {
                case '"': literal("\\\""); break;
                case '\\': literal("\\\\"); break;
                case '\b': literal("\\b"); break;
                case '\f': literal("\\f"); break;
                case '\n': literal("\\n"); break;
                case '\r': literal("\\r"); break;
                case '\t': literal("\\t"); break;
                default:
                    if(c<32) { literal("\\u00"); append("0123456789abcdef".charAt(c >> 4)); append("0123456789abcdef".charAt(c & 15)); }
                    else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        if ((remaining -= 4) < 0) throw new LimitException();
                        out.append(c).append(s.charAt(++i));
                    } else if (Character.isSurrogate(c)) {
                        literal("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4) append("0123456789abcdef".charAt((c >> shift) & 15));
                    } else append(c);
            }
        }
        append('"');
    }
    private void value(Object value,int depth) {
        if(depth>32 || ++visited > 10000) throw new LimitException();
        if(value==null || value==JSONObject.NULL) literal("null");
        else if(value instanceof JSONObject) {
            append('{'); Iterator<String> keys=((JSONObject)value).keys(); boolean comma=false;
            while(keys.hasNext()) { if(comma) append(','); String key=keys.next(); string(key); append(':'); value(((JSONObject)value).get(key),depth+1); comma=true; }
            append('}');
        } else if(value instanceof JSONArray) {
            append('['); JSONArray array=(JSONArray)value;
            for(int i=0;i<array.length();i++) { if(i>0) append(','); value(array.get(i),depth+1); }
            append(']');
        } else if(value instanceof String) string((String)value);
        else if(value instanceof Boolean) literal(value.toString());
        else if(value instanceof Number) literal(JSONObject.numberToString((Number)value));
        else throw new IllegalArgumentException("Unsupported JSON value");
    }
}
