package dev.phonemcp.core;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class McpTest {
    private final String token = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ";
    private JSONObject request(String method) { return new JSONObject().put("jsonrpc","2.0").put("id",1).put("method",method); }
    @Test public void stalledResponseWriterIsClosedAndWorkerRecovers() throws Exception {
        System.out.println("Bounded response regression JVM max heap bytes=" + Runtime.getRuntime().maxMemory());
        CountDownLatch entered = new CountDownLatch(1);
        // Bounded production-sized response; tiny test socket buffers force a stalled writer.
        char[] payload = new char[480 * 1024];
        java.util.Arrays.fill(payload, 'x');
        String large = new String(payload);
        try (LoopbackMcpServer s = new LoopbackMcpServer((n,a)->{
            entered.countDown(); return new JSONObject().put("payload",large);
        },token,0,()->{},1024)) {
            s.start();
            try (Socket blocked = new Socket()) {
                blocked.setReceiveBufferSize(1024);
                blocked.connect(new InetSocketAddress("127.0.0.1",s.localPort()));
                String body = request("tools/call").put("params",new JSONObject().put("name","phone_status")).toString();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                String head = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:"+s.localPort()+"\r\nAuthorization: Bearer "+token+"\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\nContent-Length: "+bytes.length+"\r\n\r\n";
                blocked.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
                blocked.getOutputStream().write(bytes); blocked.getOutputStream().flush();
                assertTrue("Controller should receive the request",entered.await(3, TimeUnit.SECONDS));
                // Confirm the unread response is still occupying the worker before its deadline.
                Thread.sleep(1000);
                assertEquals("Response must be stalled to exercise the watchdog",1,s.trackedConnections());
                long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(9);
                while (s.trackedConnections() != 0 && System.nanoTime() < limit) Thread.sleep(20);
                assertEquals("Stalled response socket should be closed",0,s.trackedConnections());
                assertTrue("Worker must serve a fresh request",call(s,"POST","",token,request("ping").toString()).startsWith("HTTP/1.1 200"));
            }
        }
    }
    @Test public void unsolicitedResponseInputsAreHttpErrorsWithoutEffects() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (LoopbackMcpServer s = new LoopbackMcpServer((n,a)->{ calls.incrementAndGet(); return new JSONObject(); },token,0)) {
            s.start();
            String success = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}";
            String error = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-1,\"message\":\"x\"}}";
            String tool = request("tools/call").put("params",new JSONObject().put("name","phone_status")).toString();
            for (String body : new String[]{success,error,"{\"jsonrpc\":\"2.0\",\"id\":1}","["+success+"]","["+tool+","+error+"]"}) {
                assertTrue(call(s,"POST","MCP-Protocol-Version: 2025-03-26\r\n",token,body).startsWith("HTTP/1.1 400"));
            }
            assertTrue(call(s,"POST","MCP-Protocol-Version: 2025-06-18\r\n",token,success).startsWith("HTTP/1.1 400"));
            assertTrue(call(s,"POST","",token,"{\"jsonrpc\":\"2.0\",\"result\":}").contains("-32700"));
            assertEquals("Mixed batch must be rejected before any tool side effect",0,calls.get());
        }
    }
    @Test public void unexpectedListenerFailureNotifiesOnceAfterCleanupAndDoesNotAffectReplacement() throws Exception {
        AtomicInteger failures = new AtomicInteger(); CountDownLatch failed = new CountDownLatch(1);
        LoopbackMcpServer old = new LoopbackMcpServer((n,a)->new JSONObject(),token,0,()->{failures.incrementAndGet();failed.countDown();});
        old.start(); int oldPort = old.localPort();
        try (Socket held = new Socket("127.0.0.1",oldPort)) {
            long acceptedLimit = System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while (old.trackedConnections() == 0 && System.nanoTime() < acceptedLimit) Thread.sleep(5);
            assertEquals(1,old.trackedConnections());
            java.lang.reflect.Field listener = LoopbackMcpServer.class.getDeclaredField("listener"); listener.setAccessible(true);
            ((ServerSocket)listener.get(old)).close(); // Inject only the listener failure; not normal server close.
            assertTrue(failed.await(2,TimeUnit.SECONDS));
            assertEquals(0,old.trackedConnections()); assertEquals(0,old.pendingWriteDeadlines());
            old.close(); old.close(); assertEquals(1,failures.get());
            try (LoopbackMcpServer fresh = new LoopbackMcpServer((n,a)->new JSONObject(),token,oldPort)) {
                fresh.start(); old.close();
                assertTrue(call(fresh,"POST","",token,request("ping").toString()).startsWith("HTTP/1.1 200"));
                assertEquals(1,failures.get());
            }
        } finally { old.close(); }
    }
    @Test public void completedWritesCancelDeadlinesAndIntentionalCloseNeverNotifies() throws Exception {
        AtomicInteger failures = new AtomicInteger();
        LoopbackMcpServer s = new LoopbackMcpServer((n,a)->new JSONObject(),token,0,failures::incrementAndGet);
        try {
            s.start();
            for (int i=0;i<30;i++) assertTrue(call(s,"POST","",token,request("ping").toString()).startsWith("HTTP/1.1 200"));
            assertEquals("Canceled watchdogs must not accumulate",0,s.pendingWriteDeadlines());
        } finally { s.close(); }
        assertEquals(0,s.pendingWriteDeadlines()); assertEquals(0,s.trackedConnections());
        Thread.sleep(100); assertEquals(0,failures.get());
    }
    @Test public void boundedJsonHandlesUtf8EscapesDepthCyclesAndNodeCount() {
        String text = "quote\" slash\\ newline\n é 界 😀 " + '\ud800';
        JSONObject value = new JSONObject().put("text",text).put("number",1.25).put("nil",JSONObject.NULL);
        String encoded = BoundedJson.encode(value,1024);
        assertEquals(text,new JSONObject(encoded).getString("text"));
        String emoji = BoundedJson.encode("😀",6);
        assertEquals(6,emoji.getBytes(StandardCharsets.UTF_8).length);
        try { BoundedJson.encode("😀",5); fail("UTF-8 budget"); } catch (BoundedJson.LimitException expected) { }
        JSONObject cycle = new JSONObject(); cycle.put("cycle",cycle);
        try { BoundedJson.encode(cycle,1024); fail("Cycle/depth budget"); } catch (BoundedJson.LimitException expected) { }
        org.json.JSONArray many = new org.json.JSONArray(); for(int i=0;i<10001;i++) many.put(0);
        try { BoundedJson.encode(many,1024*1024); fail("Node budget"); } catch (BoundedJson.LimitException expected) { }
    }
    @Test public void oversizedToolResultsAreWithheldWithoutLargeSerializedCopy() {
        char[] large = new char[600*1024]; java.util.Arrays.fill(large,'x');
        AtomicInteger executions = new AtomicInteger();
        McpDispatcher d = new McpDispatcher((n,a)->{executions.incrementAndGet();return new JSONObject().put("text",new String(large));});
        JSONObject response = d.dispatch(request("tools/call").put("params",new JSONObject().put("name","phone_status")));
        assertEquals(1,executions.get());
        assertTrue(response.getJSONObject("result").getBoolean("isError"));
        assertTrue(response.toString().contains("RESULT_TOO_LARGE"));
        assertTrue(response.toString().contains("prior action may have occurred"));
        assertTrue(response.toString().length()<1000);
    }
    @Test public void multipleLegacyToolCallsAreRejectedAtomicallyButSingleCallBatchWorks() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (LoopbackMcpServer s = new LoopbackMcpServer((n,a)->{calls.incrementAndGet();return new JSONObject();},token,0)) {
            s.start();
            String tool = request("tools/call").put("params",new JSONObject().put("name","phone_status")).toString();
            assertTrue(call(s,"POST","MCP-Protocol-Version: 2025-03-26\r\n",token,"["+tool+","+tool+"]").startsWith("HTTP/1.1 400"));
            assertEquals(0,calls.get());
            org.json.JSONArray tooMany = new org.json.JSONArray(); for (int i=0;i<33;i++) tooMany.put(request("ping"));
            assertTrue(call(s,"POST","MCP-Protocol-Version: 2025-03-26\r\n",token,tooMany.toString()).startsWith("HTTP/1.1 400"));
            assertTrue(call(s,"POST","MCP-Protocol-Version: 2025-03-26\r\n",token,"["+tool+","+request("ping")+"]").startsWith("HTTP/1.1 200"));
            assertEquals(1,calls.get());
        }
    }
    @Test public void scrollAndTextValidateStrictArgumentsWithoutEchoOrEffects() {
        AtomicInteger executions = new AtomicInteger();
        McpDispatcher d = new McpDispatcher((n,a)->{executions.incrementAndGet();return new JSONObject().put("accepted",true);});
        String marker = "PRIVATE_INPUT_MUST_NOT_ECHO";
        JSONObject[] invalidText = {
            new JSONObject().put("nodeId","1:1").put("text",new String(new char[1001]).replace('\0','x')),
            new JSONObject().put("nodeId","1:1").put("text",marker).put("submit",true),
            new JSONObject().put("nodeId","").put("text",marker),
            new JSONObject().put("nodeId",new String(new char[81]).replace('\0','n')).put("text",marker),
            new JSONObject().put("nodeId","1:1").put("text",JSONObject.NULL),
            new JSONObject().put("nodeId","1:1").put("text",false),
            new JSONObject().put("nodeId","1:1")
        };
        for(JSONObject args:invalidText) {
            JSONObject reply=d.dispatch(request("tools/call").put("params",new JSONObject().put("name","node_set_text").put("arguments",args)));
            assertEquals(-32602,reply.getJSONObject("error").getInt("code")); assertFalse(reply.toString().contains(marker));
        }
        for(JSONObject args:new JSONObject[]{new JSONObject().put("nodeId","1:1").put("direction","up"),
                new JSONObject().put("nodeId","1:1").put("direction","forward").put("x",100),
                new JSONObject().put("nodeId","1:1").put("direction",1), new JSONObject().put("direction","backward")}) {
            assertEquals(-32602,d.dispatch(request("tools/call").put("params",new JSONObject().put("name","node_scroll").put("arguments",args))).getJSONObject("error").getInt("code"));
        }
        assertEquals(0,executions.get());
        for(String direction:new String[]{"forward","backward"}) {
            assertFalse(d.dispatch(request("tools/call").put("params",new JSONObject().put("name","node_scroll").put("arguments",new JSONObject().put("nodeId","1:1").put("direction",direction)))).getJSONObject("result").getBoolean("isError"));
        }
        for(String text:new String[]{"",marker,new String(new char[1000]).replace('\0','x')}) {
            JSONObject reply=d.dispatch(request("tools/call").put("params",new JSONObject().put("name","node_set_text").put("arguments",new JSONObject().put("nodeId","1:1").put("text",text))));
            assertFalse(reply.getJSONObject("result").getBoolean("isError")); assertFalse(reply.toString().contains(marker));
        }
        assertEquals(5,executions.get());
        McpDispatcher throwing = new McpDispatcher((n,a)->{throw new IllegalStateException(marker);});
        JSONObject hidden=throwing.dispatch(request("tools/call").put("params",new JSONObject().put("name","node_set_text").put("arguments",new JSONObject().put("nodeId","1:1").put("text",marker))));
        assertTrue(hidden.getJSONObject("result").getBoolean("isError")); assertFalse(hidden.toString().contains(marker));
    }
    @Test public void textLimitCountsUnicodeCodePointsAndNewSchemasAreClosed() {
        StringBuilder astral = new StringBuilder(); for(int i=0;i<1000;i++) astral.append("😀");
        JSONObject args=new JSONObject().put("nodeId","1:1").put("text",astral.toString());
        assertTrue(McpDispatcher.validArgs("node_set_text",args));
        assertFalse(McpDispatcher.validArgs("node_set_text",args.put("text",astral+"x")));
        org.json.JSONArray inventory=new McpDispatcher((n,a)->new JSONObject()).dispatch(request("tools/list")).getJSONObject("result").getJSONArray("tools");
        int count=0;
        for(int i=0;i<inventory.length();i++) {
            JSONObject tool=inventory.getJSONObject(i);
            if(tool.getString("name").equals("node_set_text") || tool.getString("name").equals("node_scroll")) {
                count++; JSONObject schema=tool.getJSONObject("inputSchema"); assertFalse(schema.getBoolean("additionalProperties"));
                assertEquals(2,schema.getJSONArray("required").length()); assertFalse(tool.getJSONObject("annotations").getBoolean("readOnlyHint"));
            }
        }
        assertEquals(2,count);
    }
    @Test public void acceptedSocketConfigurationFailureAndCloseRaceNeverLeak() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Socket> accepted = new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger failures = new AtomicInteger(); CountDownLatch failed = new CountDownLatch(1);
        try (LoopbackMcpServer s = new LoopbackMcpServer((n,a)->new JSONObject(),token,0,
                ()->{failures.incrementAndGet();failed.countDown();}, socket->{accepted.set(socket);throw new IOException("injected setup failure");})) {
            s.start();
            try (Socket peer = new Socket("127.0.0.1",s.localPort())) {
                assertTrue(failed.await(2,TimeUnit.SECONDS));
                assertTrue(accepted.get().isClosed()); assertEquals(0,s.trackedConnections()); assertEquals(1,failures.get());
            }
        }
        CountDownLatch configuring=new CountDownLatch(1), release=new CountDownLatch(1), finished=new CountDownLatch(1);
        AtomicInteger unexpected=new AtomicInteger();
        LoopbackMcpServer raced=new LoopbackMcpServer((n,a)->new JSONObject(),token,0,unexpected::incrementAndGet,socket->{
            accepted.set(socket); configuring.countDown();
            try { if(!release.await(2,TimeUnit.SECONDS)) throw new IOException("test release timeout"); }
            catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IOException(e); }
            finally { finished.countDown(); }
            socket.setSoTimeout(3000); // Closed by intentional close while setup was paused.
        });
        try {
            raced.start();
            try(Socket peer=new Socket("127.0.0.1",raced.localPort())) {
                assertTrue(configuring.await(2,TimeUnit.SECONDS)); raced.close(); release.countDown();
                assertTrue(finished.await(2,TimeUnit.SECONDS));
                assertTrue(accepted.get().isClosed()); assertEquals(0,raced.trackedConnections());
                Thread.sleep(50); assertEquals(0,unexpected.get());
            }
        } finally { release.countDown(); raced.close(); }
    }
    @Test public void fixedPortRapidRestartRotatesTokensAndNeverSharesActiveListener() throws Exception {
        String previous=null;
        for(int rotation=0;rotation<5;rotation++) {
            String current=token+rotation;
            try(LoopbackMcpServer server=new LoopbackMcpServer((n,a)->new JSONObject(),current)) {
                server.start();
                assertEquals(8765,server.localPort());
                if(previous!=null) assertTrue(call(server,"POST","",previous,request("ping").toString()).startsWith("HTTP/1.1 401"));
                assertTrue(call(server,"POST","",current,request("ping").toString()).startsWith("HTTP/1.1 200"));
                try(LoopbackMcpServer competing=new LoopbackMcpServer((n,a)->new JSONObject(),token+"competing")) {
                    try { competing.start(); fail("Active fixed port must remain exclusive"); } catch(java.net.BindException expected) { }
                }
                previous=current;
            }
        }
        System.out.println("Fixed-port rapid restart: 5 rotations, old token401/new token200, competing listener refused");
    }
    @Test public void initializeAndTools() {
        McpDispatcher d = new McpDispatcher((n,a) -> new JSONObject());
        JSONObject init = request("initialize").put("params",new JSONObject().put("protocolVersion","2025-06-18").put("capabilities",new JSONObject()).put("clientInfo",new JSONObject().put("name","test").put("version","1")));
        assertEquals("2025-06-18",d.dispatch(init).getJSONObject("result").getString("protocolVersion"));
        assertEquals(6,d.dispatch(request("tools/list")).getJSONObject("result").getJSONArray("tools").length());
    }
    @Test public void unknownMethodsAndInvalidArgumentsNeverReachController() {
        AtomicInteger count = new AtomicInteger(); McpDispatcher d = new McpDispatcher((n,a)->{count.incrementAndGet();return new JSONObject();});
        assertEquals(-32601,d.dispatch(request("not/a/method")).getJSONObject("error").getInt("code"));
        JSONObject r = request("tools/call").put("params",new JSONObject().put("name","global_action").put("arguments",new JSONObject().put("action","unlock")));
        assertEquals(-32602,d.dispatch(r).getJSONObject("error").getInt("code")); assertEquals(0,count.get());
    }
    @Test public void toolErrorsAreNotProtocolErrors() {
        McpDispatcher d = new McpDispatcher((n,a)->new JSONObject().put("error","LOCKED"));
        JSONObject r = request("tools/call").put("params",new JSONObject().put("name","phone_status"));
        assertTrue(d.dispatch(r).getJSONObject("result").getBoolean("isError"));
    }
    @Test public void notificationsDoNotExecuteTools() {
        McpDispatcher d = new McpDispatcher((n,a)->{throw new AssertionError();});
        JSONObject r = request("tools/call"); r.remove("id"); assertNull(d.dispatch(r));
    }
    @Test public void strictJsonRejectsExtensionsDuplicatesAndDeepInput() {
        for (String invalid : new String[]{"{'a':1}","{a:1}","{\"a\":1,}","{\"a\":1,\"\\u0061\":2}","[01]","true false","[NaN]","[1;2]"}) {
            try { StrictJson.validate(invalid); fail(invalid); } catch (IllegalArgumentException expected) { }
        }
        String deep = "0";
        for (int i = 0; i < 40; i++) deep = "[" + deep + "]";
        try { StrictJson.validate(deep); fail("Deep nesting must be bounded"); } catch (IllegalArgumentException expected) { }
        StrictJson.validate("{\"a\":[true,false,null,1.2e-3,\"safe\\ntext\"]}");
    }
    private String call(LoopbackMcpServer s, String method, String extra, String auth, String body) throws Exception {
        try (Socket socket = new Socket("127.0.0.1",s.localPort())) {
            socket.setSoTimeout(4000);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String head = method + " /mcp HTTP/1.1\r\nHost: 127.0.0.1:"+s.localPort()+"\r\nAuthorization: Bearer "+auth+"\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\nContent-Length: "+bytes.length+"\r\n"+extra+"\r\n";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().write(bytes);
            ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n; while((n=socket.getInputStream().read(buf))!=-1) result.write(buf,0,n);
            return result.toString("UTF-8");
        }
    }
    @Test public void transportAuthenticationOriginAndVersion() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (LoopbackMcpServer s = new LoopbackMcpServer((n,a)->{calls.incrementAndGet();return new JSONObject();},token,0)) {
            s.start(); String body = request("ping").toString();
            assertTrue(call(s,"POST","","wrong",body).startsWith("HTTP/1.1 401"));
            assertTrue(call(s,"POST","Origin: https://evil.example\r\n",token,body).startsWith("HTTP/1.1 403"));
            assertTrue(call(s,"POST","MCP-Protocol-Version: unsupported\r\n",token,body).startsWith("HTTP/1.1 400"));
            assertTrue(call(s,"GET","",token,"").startsWith("HTTP/1.1 405"));
            assertTrue(call(s,"POST","",token,body).contains("\"result\":{}")); assertEquals(0,calls.get());
        }
    }
    @Test public void transportRejectsAmbiguousFramingAndOversize() throws Exception {
        try (LoopbackMcpServer s = new LoopbackMcpServer((n,a)->new JSONObject(),token,0)) {
            s.start();
            assertTrue(call(s,"POST","Content-Length: 0\r\n",token,"{}").startsWith("HTTP/1.1 400"));
            assertTrue(call(s,"POST","Transfer-Encoding: chunked\r\n",token,"{}").startsWith("HTTP/1.1 400"));
            assertTrue(call(s,"POST","Host: evil.example\r\n",token,"{}").startsWith("HTTP/1.1 400"));
            String large = new String(new char[65537]).replace('\0',' ');
            assertTrue(call(s,"POST","",token,large).startsWith("HTTP/1.1 413"));
        }
    }
    @Test public void oldProtocolBatchesAndNewProtocolRejection() throws Exception {
        try (LoopbackMcpServer s = new LoopbackMcpServer((n,a)->new JSONObject(),token,0)) {
            s.start(); String batch = "[" + request("ping") + "," + request("tools/list") + "]";
            assertTrue(call(s,"POST","MCP-Protocol-Version: 2025-03-26\r\n",token,batch).contains("\r\n\r\n["));
            assertTrue(call(s,"POST","MCP-Protocol-Version: 2025-06-18\r\n",token,batch).contains("-32600"));
        }
    }
    @Test public void trickleHeadersHaveAbsoluteDeadline() throws Exception {
        try (LoopbackMcpServer s = new LoopbackMcpServer((n,a)->new JSONObject(),token,0)) {
            s.start();
            try (Socket socket = new Socket("127.0.0.1",s.localPort())) {
                socket.setSoTimeout(7000);
                Thread trickle = new Thread(() -> {
                    try { for (int i = 0; i < 30; i++) { socket.getOutputStream().write('X'); socket.getOutputStream().flush(); Thread.sleep(250); } }
                    catch (Exception expected) { }
                });
                long start = System.nanoTime(); trickle.start();
                String response = new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)).readLine();
                trickle.interrupt(); trickle.join(1000);
                assertTrue(response.startsWith("HTTP/1.1 408"));
                assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start) < 6500);
            }
            assertTrue(call(s,"POST","",token,request("ping").toString()).startsWith("HTTP/1.1 200"));
        }
    }
    @Test public void queuedConnectionsDoNotReceiveAFreshReadDeadline() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (LoopbackMcpServer s = new LoopbackMcpServer((n,a)->{calls.incrementAndGet(); return new JSONObject();},token,0)) {
            s.start();
            try (Socket first = new Socket("127.0.0.1",s.localPort()); Socket queued = new Socket("127.0.0.1",s.localPort())) {
                queued.setSoTimeout(7000);
                Thread trickle = new Thread(() -> {
                    try { while (!Thread.currentThread().isInterrupted()) { first.getOutputStream().write('X'); first.getOutputStream().flush(); Thread.sleep(250); } }
                    catch (Exception expected) { }
                });
                trickle.start();
                try {
                    long waitUntil = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                    while (s.trackedConnections() < 2 && System.nanoTime() < waitUntil) Thread.sleep(10);
                    assertEquals("Both connections must be accepted",2,s.trackedConnections());
                    long start = System.nanoTime();
                    try {
                        String response = new BufferedReader(new InputStreamReader(queued.getInputStream(),StandardCharsets.US_ASCII)).readLine();
                        assertTrue(response == null || response.startsWith("HTTP/1.1 408"));
                    } catch (SocketException reset) { /* A reset also closes an expired socket. */ }
                    assertTrue("Queue wait must count against the deadline", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start) < 6500);
                    assertEquals(0,calls.get());
                } finally { trickle.interrupt(); trickle.join(1000); }
            }
            assertTrue(call(s,"POST","",token,request("ping").toString()).startsWith("HTTP/1.1 200"));
        }
    }
    private void writeTool(Socket socket, LoopbackMcpServer server, String id) throws Exception {
        byte[] body = request("tools/call").put("params", new JSONObject().put("name","node_click")
            .put("arguments",new JSONObject().put("nodeId",id))).toString().getBytes(StandardCharsets.UTF_8);
        String header = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:" + server.localPort()
            + "\r\nAuthorization: Bearer " + token + "\r\nContent-Type: application/json"
            + "\r\nAccept: application/json, text/event-stream\r\nContent-Length: " + body.length + "\r\n\r\n";
        socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(body); socket.getOutputStream().flush();
    }
    private String readResponse(Socket socket) throws Exception {
        socket.setSoTimeout(4000);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = socket.getInputStream().read(buffer)) != -1) result.write(buffer,0,count);
        return result.toString("UTF-8");
    }
    private void assertQueuedToolBehavior(boolean expire) throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (LoopbackMcpServer server = new LoopbackMcpServer((name,args) -> {
            String id = args.getString("nodeId"); seen.add(id);
            if (id.equals("A")) {
                entered.countDown();
                if (!release.await(15,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Controller latch timed out");
            }
            return new JSONObject().put("accepted",id);
        }, token, 0)) {
            server.start();
            try (Socket first = new Socket("127.0.0.1",server.localPort())) {
                writeTool(first,server,"A");
                assertTrue("A must occupy the serial worker",entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
                try (Socket queued = new Socket("127.0.0.1",server.localPort())) {
                    writeTool(queued,server,"B"); // Complete, valid, authenticated command, not an idle socket.
                    long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                    while (server.trackedConnections() < 2 && System.nanoTime() < until) Thread.sleep(10);
                    assertEquals("B must be accepted before measuring queue age",2,server.trackedConnections());
                    long releaseAt = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(expire ? 5300 : 100);
                    while (System.nanoTime() < releaseAt) Thread.sleep(10);
                    release.countDown();
                    assertTrue(readResponse(first).startsWith("HTTP/1.1 200"));
                    String queuedResponse;
                    try { queuedResponse = readResponse(queued); }
                    catch (SocketException reset) { queuedResponse = ""; }
                    // Observe B before creating C, so later work cannot explain B's outcome.
                    boolean bClosed = queuedResponse.isEmpty();
                    try (Socket fresh = new Socket("127.0.0.1",server.localPort())) {
                        writeTool(fresh,server,"C");
                        assertTrue("Fresh authenticated tool must recover",readResponse(fresh).startsWith("HTTP/1.1 200"));
                    }
                    System.out.println("Queued tool expire=" + expire + " calls=" + seen + " B=" + (bClosed ? "closed" : queuedResponse.split("\r\n",2)[0]));
                    if (expire) {
                        assertEquals("Expired queued command must never reach controller",java.util.Arrays.asList("A","C"),seen);
                        assertTrue("Expired B must close without processing",bClosed);
                    } else {
                        assertEquals(java.util.Arrays.asList("A","B","C"),seen);
                        assertTrue("Unexpired queued command must work",queuedResponse.startsWith("HTTP/1.1 200"));
                    }
                }
            } finally { release.countDown(); }
        } finally { release.countDown(); }
    }
    @Test public void expiredQueuedAuthenticatedToolNeverExecutesAndFreshToolRecovers() throws Exception { assertQueuedToolBehavior(true); }
    @Test public void queuedAuthenticatedToolBeforeDeadlineExecutes() throws Exception { assertQueuedToolBehavior(false); }
    @Test public void transportNotificationsParseErrorsAndToolCalls() throws Exception {
        try (LoopbackMcpServer s = new LoopbackMcpServer((n,a)->new JSONObject().put("ok",true),token,0)) {
            s.start();
            assertTrue(call(s,"POST","",token,"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").startsWith("HTTP/1.1 202"));
            assertTrue(call(s,"POST","",token,"{bad}").contains("-32700"));
            assertTrue(call(s,"POST","",token,request("tools/call").put("params",new JSONObject().put("name","phone_status")).toString()).contains("\"isError\":false"));
        }
    }
}
