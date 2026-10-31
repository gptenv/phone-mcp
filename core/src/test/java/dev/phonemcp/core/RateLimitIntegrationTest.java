package dev.phonemcp.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class RateLimitIntegrationTest {
    private static final String TOKEN="abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ";
    private static final long PERIOD=200_000_000L;
    private static JSONObject call(String name,JSONObject args) {
        return new JSONObject().put("jsonrpc","2.0").put("id",1).put("method","tools/call")
            .put("params",new JSONObject().put("name",name).put("arguments",args));
    }
    private static JSONObject status() { return call("phone_status",new JSONObject()); }
    private static LoopbackMcpServer server(String token,int port,SessionRateLimiter limiter,AtomicInteger calls) {
        return new LoopbackMcpServer((name,args)->{calls.incrementAndGet();return new JSONObject().put("accepted",true);},
            token,port,()->{},socket->socket.setSoTimeout(3000),limiter);
    }
    @Test public void authenticationValidationStatusAndExactRefillShareOneBucket() throws Exception {
        AtomicLong clock=new AtomicLong(); AtomicInteger calls=new AtomicInteger();
        SessionRateLimiter limiter=new SessionRateLimiter(2,PERIOD,clock::get);
        try(LoopbackMcpServer server=server(TOKEN,0,limiter,calls)) {
            server.start();
            assertTrue(send(server,"wrong",status().toString()).startsWith("HTTP/1.1 401"));
            assertEquals(2,limiter.availableTokens()); assertEquals(0,calls.get());
            JSONObject malformed=call("node_set_text",new JSONObject().put("nodeId","1:1").put("text","PRIVATE_MARKER").put("extra",true));
            String bad=send(server,TOKEN,malformed.toString());
            assertEquals(-32602,json(bad).getJSONObject("error").getInt("code"));assertFalse(bad.contains("PRIVATE_MARKER"));
            assertEquals(2,limiter.availableTokens()); assertEquals(0,calls.get());
            assertFalse(json(send(server,TOKEN,status().toString())).getJSONObject("result").getBoolean("isError"));
            assertEquals(1,limiter.availableTokens()); assertEquals(1,calls.get());
            assertFalse(json(send(server,TOKEN,call("global_action",new JSONObject().put("action","back")).toString())).getJSONObject("result").getBoolean("isError"));
            assertEquals(0,limiter.availableTokens()); assertEquals(2,calls.get());
            String denied=send(server,TOKEN,call("node_set_text",new JSONObject().put("nodeId","1:1").put("text","PRIVATE_MARKER")).toString());
            assertDenied(denied,200);assertFalse(denied.contains("PRIVATE_MARKER"));
            assertEquals(0,limiter.availableTokens());assertEquals(2,calls.get());
            assertDenied(send(server,TOKEN,status().toString()),200); // Status cannot bypass the bucket.
            clock.set(PERIOD-1);assertDenied(send(server,TOKEN,status().toString()),1);
            assertEquals(2,calls.get());assertEquals(0,limiter.availableTokens());
            clock.set(PERIOD);assertFalse(json(send(server,TOKEN,status().toString())).getJSONObject("result").getBoolean("isError"));
            assertEquals(3,calls.get());assertEquals(0,limiter.availableTokens());
        }
    }
    @Test public void unsafeLegacyBatchConsumesNothingAndAuthorizationDenialsStayDistinct() throws Exception {
        for(String denial:new String[]{"SCOPE_DENIED","SESSION_STOPPED"}) {
            AtomicLong clock=new AtomicLong();AtomicInteger calls=new AtomicInteger();
            SessionRateLimiter limiter=new SessionRateLimiter(1,PERIOD,clock::get);
            try(LoopbackMcpServer server=new LoopbackMcpServer((name,args)->{calls.incrementAndGet();return new JSONObject().put("error",denial);},
                    TOKEN,0,()->{},socket->socket.setSoTimeout(3000),limiter)) {
                server.start();
                JSONArray unsafe=new JSONArray().put(status()).put(status());
                assertTrue(send(server,TOKEN,unsafe.toString()).startsWith("HTTP/1.1 400"));
                assertEquals(1,limiter.availableTokens());assertEquals(0,calls.get());
                JSONObject permission=json(send(server,TOKEN,call("screen_inspect",new JSONObject()).toString()));
                JSONObject result=permission.getJSONObject("result");assertTrue(result.getBoolean("isError"));
                assertEquals(denial,new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text")).getString("error"));
                assertEquals(1,calls.get());assertEquals(0,limiter.availableTokens());
                assertDenied(send(server,TOKEN,status().toString()),200);assertEquals(1,calls.get());
            }
        }
    }
    @Test public void newServerGetsFreshBucketOldTokenCannotSpendItAndStopNeverWaits() throws Exception {
        AtomicLong clock=new AtomicLong();AtomicInteger calls=new AtomicInteger();
        SessionRateLimiter oldBucket=new SessionRateLimiter(1,PERIOD,clock::get);
        LoopbackMcpServer old=server(TOKEN,0,oldBucket,calls);old.start();int port=old.localPort();
        try {send(old,TOKEN,status().toString());assertDenied(send(old,TOKEN,status().toString()),200);}
        finally {
            java.util.concurrent.ExecutorService closer=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {closer.submit(old::close).get(2,TimeUnit.SECONDS);} finally {closer.shutdownNow();old.close();}
        }
        SessionRateLimiter freshBucket=new SessionRateLimiter(1,PERIOD,clock::get);
        String rotated=TOKEN+"new";
        try(LoopbackMcpServer fresh=server(rotated,port,freshBucket,calls)) {
            fresh.start();
            assertTrue(send(fresh,TOKEN,status().toString()).startsWith("HTTP/1.1 401"));
            assertEquals(1,freshBucket.availableTokens());assertEquals(0,oldBucket.availableTokens());
            assertFalse(json(send(fresh,rotated,status().toString())).getJSONObject("result").getBoolean("isError"));
            assertEquals(2,calls.get());assertEquals(0,freshBucket.availableTokens());
        }
    }
    private static void assertDenied(String raw,long milliseconds) {
        JSONObject result=json(raw).getJSONObject("result");assertTrue(result.getBoolean("isError"));
        JSONObject error=new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"));
        assertEquals("RATE_LIMITED",error.getString("error"));assertFalse(error.getBoolean("executed"));
        assertEquals(milliseconds,error.getLong("retryAfterMs"));
    }
    private static JSONObject json(String raw) {return new JSONObject(raw.substring(raw.indexOf("\r\n\r\n")+4));}
    private static String send(LoopbackMcpServer server,String token,String json) throws Exception {
        byte[] body=json.getBytes(StandardCharsets.UTF_8);
        try(Socket socket=new Socket("127.0.0.1",server.localPort())) {
            socket.setSoTimeout(4000);
            String head="POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:"+server.localPort()+"\r\nAuthorization: Bearer "+token+"\r\nAccept: application/json, text/event-stream\r\nContent-Type: application/json\r\nMCP-Protocol-Version: 2025-03-26\r\nContent-Length: "+body.length+"\r\n\r\n";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            while((n=socket.getInputStream().read(buffer))!=-1)out.write(buffer,0,n);
            return out.toString("UTF-8");
        }
    }
}
