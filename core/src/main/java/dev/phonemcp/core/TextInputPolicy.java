package dev.phonemcp.core;

/** Shared wire/direct-Android limit: valid Unicode, at most 1000 code points.
 * Does not normalize, truncate, retain or log supplied text. Empty text clears.
 */
public final class TextInputPolicy {
    public static final int MAX_CODE_POINTS = 1000;
    public static final int MAX_UTF16_CODE_UNITS = MAX_CODE_POINTS * 2;
    private TextInputPolicy() { }
    public static boolean accepts(String text) {
        if (text == null || text.length() > MAX_UTF16_CODE_UNITS) return false;
        int points = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
            if (++points > MAX_CODE_POINTS) return false;
        }
        return true;
    }
}
