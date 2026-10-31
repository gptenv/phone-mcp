import dev.phonemcp.core.LoopbackMcpServer;
import org.json.JSONObject;
/** Host-only fixture. Never connects to Android and never controls a phone. */
public final class FakeMcpServer {
    public static void main(String[] args) throws Exception {
        String token = System.getenv("PHONE_MCP_INTEROP_TOKEN");
        String nonce = System.getenv("PHONE_MCP_INTEROP_NONCE");
        try (LoopbackMcpServer server = new LoopbackMcpServer((name, arguments) -> {
            if ("phone_status".equals(name)) return new JSONObject().put("fixture", "host-only-fake-controller").put("nonce", nonce);
            return new JSONObject().put("error", "FAKE_PERMISSION_DENIED: Fixture exposes no phone access");
        }, token)) {
            server.start();
            System.out.println("READY host-only-fake-controller"); System.out.flush();
            while (System.in.read() != -1) { }
        }
    }
}
