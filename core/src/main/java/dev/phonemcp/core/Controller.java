package dev.phonemcp.core;
import org.json.JSONObject;
/** Android implementation re-checks activation, lock state and scopes on EVERY call. */
public interface Controller {
    JSONObject execute(String name, JSONObject args) throws Exception;
}
