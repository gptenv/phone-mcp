package dev.phonemcp.core;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONTokener;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Deliberately small HTTP/1.1 transport: one request per connection, no browser access. */
public final class LoopbackMcpServer implements AutoCloseable {
    public static final int PORT = 8765;
    private static final int MAX_BODY = 65536, MAX_HEADERS = 8192, WRITE_TIMEOUT_MS = 5000;
    private final McpDispatcher dispatcher;
    private final byte[] authorization;
    private final int port;
    private final Runnable onUnexpectedFailure;
    @FunctionalInterface interface SocketConfiguration { void configure(Socket socket) throws IOException; }
    private final SocketConfiguration socketConfiguration;
    private volatile ServerSocket listener;
    private volatile boolean closed;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(4), r -> new Thread(r, "phone-mcp-request"), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor writeWatchdog = newWatchdog();
    private static ScheduledThreadPoolExecutor newWatchdog() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "phone-mcp-write-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }
    public LoopbackMcpServer(Controller controller, String token) { this(controller, token, PORT, () -> { }); }
    /** Callback is instance-specific, once-only, after cleanup; never called for deliberate close. */
    public LoopbackMcpServer(Controller controller, String token, Runnable onUnexpectedFailure) {
        this(controller, token, PORT, onUnexpectedFailure);
    }
    // Package-visible ephemeral port for host tests, never configured remotely.
    LoopbackMcpServer(Controller controller, String token, int port) { this(controller, token, port, () -> { }); }
    LoopbackMcpServer(Controller controller, String token, int port, Runnable onUnexpectedFailure) { this(controller, token, port, onUnexpectedFailure, 0); }
    // Small send buffer makes stalled-output regression deterministic without oversized responses.
    LoopbackMcpServer(Controller controller, String token, int port, Runnable onUnexpectedFailure, int sendBufferBytes) {
        this(controller, token, port, onUnexpectedFailure, socket -> {
            socket.setSoTimeout(3000);
            if (sendBufferBytes > 0) socket.setSendBufferSize(sendBufferBytes);
        });
    }
    // Injectable only within this package, to prove setup-failure cleanup without OS-dependent faults.
    LoopbackMcpServer(Controller controller, String token, int port, Runnable onUnexpectedFailure, SocketConfiguration socketConfiguration) {
        this(controller, token, port, onUnexpectedFailure, socketConfiguration, SessionRateLimiter.withDefaults(System::nanoTime));
    }
    // Fake-clock bucket injection for integration tests; production always creates a fresh per-server bucket.
    LoopbackMcpServer(Controller controller, String token, int port, Runnable onUnexpectedFailure, SocketConfiguration socketConfiguration, SessionRateLimiter rateLimiter) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43,128}")) throw new IllegalArgumentException("Strong base64url token required");
        this.dispatcher = new McpDispatcher(controller, rateLimiter);
        this.authorization = ("Bearer " + token).getBytes(StandardCharsets.US_ASCII);
        this.port = port;
        this.onUnexpectedFailure = Objects.requireNonNull(onUnexpectedFailure);
        this.socketConfiguration = Objects.requireNonNull(socketConfiguration);
    }
    public synchronized void start() throws IOException {
        if (closed || listener != null) throw new IllegalStateException("Server already started or closed");
        ServerSocket bound = new ServerSocket();
        try { bound.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4); }
        catch (IOException e) { bound.close(); throw e; }
        listener = bound;
        new Thread(this::accept, "phone-mcp-listener").start();
    }
    int localPort() { return listener.getLocalPort(); }
    // Package-visible for deterministic host tests of queued connections.
    int trackedConnections() { return sockets.size(); }
    int pendingWriteDeadlines() { return writeWatchdog.getQueue().size(); }
    private void accept() {
        while (!closed) {
            try {
                Socket socket = listener.accept();
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(5000);
                synchronized (this) {
                    if (closed) { socket.close(); return; }
                    // Track before configuration: its IOException must not orphan an accepted socket.
                    sockets.add(socket);
                }
                socketConfiguration.configure(socket);
                try { worker.execute(() -> serve(socket, deadline)); }
                catch (RejectedExecutionException e) { sockets.remove(socket); socket.close(); }
            } catch (IOException e) { unexpectedFailure(); return; }
        }
    }
    private void unexpectedFailure() {
        synchronized (this) {
            if (closed) return;
            close();
        }
        try { onUnexpectedFailure.run(); } catch (RuntimeException ignored) { /* Owner callback cannot strand cleanup. */ }
    }
    private void serve(Socket socket, long deadline) {
        try (Socket connection = socket) {
            // A queued connection can have spent its entire input budget waiting.
            if (deadline - System.nanoTime() <= 0) return;
            InputStream in = new BufferedInputStream(new DeadlineInputStream(connection, deadline));
            OutputStream out = connection.getOutputStream();
            try {
                int[] budget = {MAX_HEADERS};
                String[] request = line(in, budget).split(" ", -1);
                if (request.length != 3 || !request[2].equals("HTTP/1.1")) throw new HttpError(400);
                Map<String,String> headers = new HashMap<>();
                for (;;) {
                    String header = line(in, budget);
                    if (header.isEmpty()) break;
                    int colon = header.indexOf(':');
                    if (colon <= 0) throw new HttpError(400);
                    String key = header.substring(0, colon).toLowerCase(Locale.ROOT);
                    if (!key.matches("[a-z0-9-]+") || headers.containsKey(key)) throw new HttpError(400);
                    headers.put(key, header.substring(colon + 1).trim());
                }
                if (headers.containsKey("origin")) throw new HttpError(403);
                String host = headers.get("host");
                if (!("127.0.0.1:" + localPort()).equals(host) && !("localhost:" + localPort()).equals(host)) throw new HttpError(403);
                if (!MessageDigest.isEqual(authorization, headers.getOrDefault("authorization", "").getBytes(StandardCharsets.US_ASCII))) throw new HttpError(401);
                if (!request[1].equals("/mcp")) throw new HttpError(404);
                if (!request[0].equals("POST")) throw new HttpError(405);
                if (headers.containsKey("transfer-encoding") || headers.containsKey("expect")) throw new HttpError(400);
                String version = headers.getOrDefault("mcp-protocol-version", "2025-03-26");
                if (!McpDispatcher.supports(version)) throw new HttpError(400);
                String contentType = headers.getOrDefault("content-type", "").toLowerCase(Locale.ROOT);
                if (!contentType.split(";", 2)[0].trim().equals("application/json")) throw new HttpError(415);
                String accept = headers.getOrDefault("accept", "");
                if (!AcceptHeader.acceptsMcp(accept)) throw new HttpError(406);
                String length = headers.get("content-length");
                if (length == null || !length.matches("[0-9]{1,8}")) throw new HttpError(411);
                int size = Integer.parseInt(length);
                if (size > MAX_BODY) throw new HttpError(413);
                byte[] body = new byte[size];
                int read = 0;
                while (read < size) { int n = in.read(body, read, size - read); if (n < 0) throw new HttpError(400); read += n; }
                Object response;
                try {
                    String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
                    // org.json is lenient; reject non-JSON syntax before parsing.
                    StrictJson.validate(json);
                    JSONTokener parser = new JSONTokener(json);
                    Object value = parser.nextValue();
                    rejectUnsolicitedResponses(value);
                    if (closed) throw new HttpError(503);
                    if (value instanceof JSONArray && version.equals("2025-03-26")) {
                        JSONArray input = (JSONArray) value;
                        if (input.length() == 0 || input.length() > 32) response = McpDispatcher.error(null, -32600, "Batch must contain 1 to 32 messages");
                        else {
                            JSONArray replies = new JSONArray();
                            for (int i = 0; i < input.length(); i++) {
                                if (closed) throw new HttpError(503);
                                Object item = input.get(i);
                                JSONObject reply = item instanceof JSONObject ? dispatcher.dispatch((JSONObject) item) : McpDispatcher.error(null, -32600, "Invalid Request");
                                if (reply != null) replies.put(reply);
                            }
                            response = replies.length() == 0 ? null : replies;
                        }
                    } else if (!(value instanceof JSONObject)) response = McpDispatcher.error(null, -32600, "Invalid Request");
                    else response = dispatcher.dispatch((JSONObject) value);
                } catch (HttpError e) { throw e; }
                catch (Exception e) { response = McpDispatcher.error(null, -32700, "Parse error"); }
                String serialized;
                try { serialized = response == null ? "" : BoundedJson.encode(response, 2 * 1024 * 1024); }
                catch (BoundedJson.LimitException tooLarge) {
                    sendWithDeadline(connection, out, 500, McpDispatcher.error(null, -32603,
                        "Response withheld: safety limit. Prior actions may have occurred; do not retry blindly.").toString());
                    return;
                }
                sendWithDeadline(connection, out, response == null ? 202 : 200, serialized);
            } catch (HttpError e) { sendWithDeadline(connection, out, e.status, ""); }
            catch (SocketTimeoutException e) { sendWithDeadline(connection, out, 408, ""); }
        } catch (IOException ignored) { /* No request bodies, tokens or screen data are logged. */ }
        finally { sockets.remove(socket); }
    }
    /** No server-originated requests exist, so responses are unsolicited and rejected atomically. */
    private static void rejectUnsolicitedResponses(Object value) throws HttpError {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (array.length() > 32) throw new HttpError(400);
            int toolCalls = 0;
            for (int i = 0; i < array.length(); i++) {
                Object item = array.get(i);
                if (item instanceof JSONObject && "tools/call".equals(((JSONObject)item).optString("method"))) toolCalls++;
                rejectUnsolicitedResponses(item);
            }
            // Atomic preflight: never execute an early action and then reject a later one for budget.
            if (toolCalls > 1) throw new HttpError(400);
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.has("result") || object.has("error") || (!object.has("method") && object.has("id"))) throw new HttpError(400);
        }
    }
    /** SO_TIMEOUT alone resets on each read; an absolute deadline also stops trickle clients. */
    private static final class DeadlineInputStream extends FilterInputStream {
        private final Socket socket;
        private final long deadline;
        DeadlineInputStream(Socket socket, long deadline) throws IOException {
            super(socket.getInputStream()); this.socket = socket;
            this.deadline = deadline;
        }
        private void remaining() throws IOException {
            long left = deadline - System.nanoTime();
            if (left <= 0) throw new SocketTimeoutException("Request deadline");
            socket.setSoTimeout((int) Math.max(1, Math.min(3000, TimeUnit.NANOSECONDS.toMillis(left))));
        }
        @Override public int read() throws IOException { remaining(); return in.read(); }
        @Override public int read(byte[] bytes, int off, int len) throws IOException { remaining(); return in.read(bytes, off, len); }
    }
    private static String line(InputStream in, int[] budget) throws IOException, HttpError {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            if (--budget[0] < 0) throw new HttpError(431);
            int b = in.read();
            if (b < 0) throw new HttpError(400);
            if (b == '\r') {
                if (--budget[0] < 0 || in.read() != '\n') throw new HttpError(400);
                return buffer.toString("US-ASCII");
            }
            if (b < 32 || b > 126) throw new HttpError(400);
            buffer.write(b);
        }
    }
    private static void send(OutputStream out, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : status == 202 ? "Accepted" : "Request Rejected") + "\r\n"
            + "Content-Type: application/json\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n"
            + (status == 405 ? "Allow: POST\r\n" : "") + (status == 401 ? "WWW-Authenticate: Bearer\r\n" : "") + "\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII)); out.write(bytes); out.flush();
    }
    /** Socket SO_TIMEOUT applies only to reads. Closing the socket interrupts a stalled write. */
    private void sendWithDeadline(Socket socket, OutputStream out, int status, String body) throws IOException {
        final ScheduledFuture<?> closeTask;
        try {
            closeTask = writeWatchdog.schedule(() -> {
                try { socket.close(); } catch (IOException ignored) { }
            }, WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
            throw new IOException("Server stopped", stopped);
        }
        try { send(out, status, body); }
        finally { closeTask.cancel(false); }
    }
    @Override public synchronized void close() {
        closed = true;
        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
        sockets.clear();
        worker.shutdownNow();
        writeWatchdog.shutdownNow();
        Arrays.fill(authorization, (byte) 0);
    }
    private static final class HttpError extends Exception { final int status; HttpError(int status) { this.status = status; } }
}
