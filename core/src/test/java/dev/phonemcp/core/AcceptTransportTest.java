package dev.phonemcp.core;
import org.junit.Test;
import org.json.JSONObject;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
/** Integration glue regression; parser itself remains the pristine Grok contribution. */
public class AcceptTransportTest {
 @Test public void malformedOrUnacceptableHeadersHaveNoControllerEffects() throws Exception {
  AtomicInteger calls = new AtomicInteger();
  String token = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ";
  try (LoopbackMcpServer server = new LoopbackMcpServer((name,args)->{calls.incrementAndGet();return new JSONObject().put("active",true);},token,0)) {
   server.start();
   for (String accept : new String[]{"application/json-seq, text/event-stream", "application/json, text/event-stream;q=0", "application/json, text/event-stream;foo=", "application/json;q=0, application/json, text/event-stream", "*/*"}) {
    assertTrue(send(server.localPort(),token,accept).startsWith("HTTP/1.1 406"));
    assertEquals(0,calls.get());
   }
   assertTrue(send(server.localPort(),token,"Application/JSON;q=0.8, text/event-stream").startsWith("HTTP/1.1 200"));
   assertEquals(1,calls.get());
  }
 }
 private static String send(int port,String token,String accept) throws Exception {
  byte[] body="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"phone_status\",\"arguments\":{}}}".getBytes(StandardCharsets.UTF_8);
  try(Socket socket = new Socket("127.0.0.1",port)) {
   socket.setSoTimeout(4000);
   String head="POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nAuthorization: Bearer "+token+"\r\nContent-Type: application/json\r\nAccept: "+accept+"\r\nContent-Length: "+body.length+"\r\n\r\n";
   socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);
   return new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
  }
 }
}
