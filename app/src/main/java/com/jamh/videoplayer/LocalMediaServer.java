package com.jamh.videoplayer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 1.7.0: tiny HTTP/1.1 server that streams videos from this phone to a Chromecast on the same
 * Wi-Fi (the receiver fetches media by URL; it can't read content:// or our encrypted vault).
 *
 * <ul>
 *   <li>Bound to the phone's Wi-Fi address only (never 0.0.0.0, so not on mobile data or VPN
 *       interfaces), on a random free port.</li>
 *   <li>Every URL carries a 256-bit random session token: {@code http://ip:port/<token>/<id>/name}.
 *       Anything else gets 404. Connections from outside the local network are refused.</li>
 *   <li>GET / HEAD with single byte ranges (206 / 416), keep-alive, CORS headers (the Cast
 *       receiver fetches subtitles with XHR).</li>
 *   <li>Private videos are decrypted on the fly from the vault (random access through Tink's
 *       seekable channel); nothing is written to disk.</li>
 * </ul>
 * Started for a Cast session and stopped when the session ends.
 */
final class LocalMediaServer {

    /** Something the server can stream. */
    interface Source {
        long length() throws IOException;

        String mimeType();

        /** A stream positioned at {@code offset}. */
        InputStream open(long offset) throws IOException;
    }

    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_WORKERS = 8;
    private final java.util.Set<Socket> clients = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final int IDLE_TIMEOUT_MS = 30_000;

    private final ServerSocket socket;
    private final String token;
    private final String host;
    private final Map<String, Source> media = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final ExecutorService workers;
    private final boolean allowAnyClient;
    private volatile boolean running = true;
    /** Requests served (tests / diagnostics). */
    final AtomicInteger requests = new AtomicInteger();

    private LocalMediaServer(ServerSocket socket, boolean allowAnyClient) {
        this.socket = socket;
        this.allowAnyClient = allowAnyClient;
        byte[] t = new byte[32];
        new SecureRandom().nextBytes(t);
        this.token = android.util.Base64.encodeToString(t,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
        InetAddress a = socket.getInetAddress();
        String h = a.getHostAddress();
        this.host = h != null && h.contains(":") ? "[" + h + "]" : h;
        this.workers = new ThreadPoolExecutor(0, MAX_WORKERS, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
                r -> {
                    Thread th = new Thread(r, "cast-http");
                    th.setDaemon(true);
                    return th;
                }, new ThreadPoolExecutor.AbortPolicy());
        Thread acceptor = new Thread(this::acceptLoop, "cast-http-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** Binds to {@code address} (the Wi-Fi address) on a free port. */
    static LocalMediaServer start(InetAddress address) throws IOException {
        return start(address, false);
    }

    @VisibleForTesting
    static LocalMediaServer start(InetAddress address, boolean allowAnyClient) throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(address, 0), 16);
        return new LocalMediaServer(ss, allowAnyClient);
    }

    int port() {
        return socket.getLocalPort();
    }

    InetAddress address() {
        return socket.getInetAddress();
    }

    boolean isRunning() {
        return running && !socket.isClosed();
    }

    @VisibleForTesting
    String token() {
        return token;
    }

    /** Registers a source; returns its URL. {@code name} only makes the URL readable (e.g. "video.mp4"). */
    String register(Source s, String name) {
        String id = Integer.toString(nextId.getAndIncrement(), 36);
        media.put(id, s);
        String safe = name == null ? "media" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isEmpty()) safe = "media";
        return "http://" + host + ":" + port() + "/" + token + "/" + id + "/" + safe;
    }

    void unregisterAll() {
        media.clear();
    }

    int registeredCount() {
        return media.size();
    }

    void stop() {
        running = false;
        media.clear();
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        // Kept-alive connections (the TV) are cut too: nothing is served after casting ends.
        for (Socket s : clients) closeQuietly(s);
        clients.clear();
        workers.shutdownNow();
    }

    // ------------------------------------------------------------------ connections

    private void acceptLoop() {
        while (running) {
            Socket s;
            try {
                s = socket.accept();
            } catch (IOException e) {
                if (!running || socket.isClosed()) return;
                continue;
            }
            if (!allowAnyClient && !isLocalClient(s.getInetAddress())) {
                closeQuietly(s);
                continue;
            }
            try {
                workers.execute(() -> serve(s));
            } catch (RuntimeException busy) {
                closeQuietly(s);
            }
        }
    }

    /** Only devices on the local network (the TV) may connect. */
    static boolean isLocalClient(InetAddress a) {
        return a != null && (a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isLoopbackAddress()
                || isUniqueLocalV6(a));
    }

    private static boolean isUniqueLocalV6(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;
    }

    private void serve(Socket s) {
        clients.add(s);
        if (!running) closeQuietly(s);
        try (Socket sock = s) {
            sock.setSoTimeout(IDLE_TIMEOUT_MS);
            sock.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), 64 * 1024);
            while (running) {
                Request r = readRequest(in);
                if (r == null) return;
                requests.incrementAndGet();
                boolean keep = handle(r, out);
                out.flush();
                if (!keep) return;
            }
        } catch (IOException ignored) {
            // client went away (seek, stop): normal for media streaming
        } finally {
            clients.remove(s);
        }
    }

    static final class Request {
        String method;
        String path;
        String range;
        boolean close;
    }

    @Nullable
    static Request readRequest(InputStream in) throws IOException {
        String line = readLine(in);
        while (line != null && line.isEmpty()) line = readLine(in); // tolerate stray CRLFs
        if (line == null) return null;
        String[] parts = line.split(" ");
        if (parts.length < 2) throw new IOException("bad request line");
        Request r = new Request();
        r.method = parts[0].toUpperCase(Locale.ROOT);
        r.path = parts[1];
        boolean http10 = parts.length > 2 && parts[2].equals("HTTP/1.0");
        r.close = http10;
        int total = line.length();
        String h;
        while ((h = readLine(in)) != null && !h.isEmpty()) {
            total += h.length();
            if (total > MAX_HEADER_BYTES) throw new IOException("headers too large");
            int c = h.indexOf(':');
            if (c <= 0) continue;
            String name = h.substring(0, c).trim().toLowerCase(Locale.ROOT);
            String value = h.substring(c + 1).trim();
            if (name.equals("range")) r.range = value;
            else if (name.equals("connection")) {
                String v = value.toLowerCase(Locale.ROOT);
                if (v.contains("close")) r.close = true;
                else if (v.contains("keep-alive")) r.close = false;
            }
        }
        return r;
    }

    @Nullable
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
                return sb.toString();
            }
            if (sb.length() > MAX_HEADER_BYTES) throw new IOException("line too long");
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Returns false when the connection should close. */
    private boolean handle(Request r, OutputStream out) throws IOException {
        if (r.method.equals("OPTIONS")) {
            writeHead(out, 204, "No Content", null, 0, null, r.close);
            return !r.close;
        }
        if (!r.method.equals("GET") && !r.method.equals("HEAD")) {
            writeHead(out, 405, "Method Not Allowed", "text/plain", 0, null, true);
            return false;
        }
        Source src = resolve(r.path);
        if (src == null) {
            writeHead(out, 404, "Not Found", "text/plain", 0, null, r.close);
            return !r.close;
        }
        long len;
        try {
            len = src.length();
        } catch (IOException e) {
            writeHead(out, 500, "Internal Server Error", "text/plain", 0, null, true);
            return false;
        }
        long[] range = parseRange(r.range, len);
        if (range != null && range[0] < 0) {
            writeHead(out, 416, "Range Not Satisfiable", null, 0, "bytes */" + len, r.close);
            return !r.close;
        }
        long start = range == null ? 0 : range[0];
        long end = range == null ? len - 1 : range[1];
        long count = Math.max(0, end - start + 1);
        if (range != null) {
            writeHead(out, 206, "Partial Content", src.mimeType(), count,
                    "bytes " + start + "-" + end + "/" + len, r.close);
        } else {
            writeHead(out, 200, "OK", src.mimeType(), count, null, r.close);
        }
        if (r.method.equals("HEAD") || count == 0) return !r.close;
        try (InputStream body = src.open(start)) {
            byte[] buf = new byte[64 * 1024];
            long left = count;
            while (left > 0 && running) {
                int n = body.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("source ended early");
                out.write(buf, 0, n);
                left -= n;
            }
            if (left > 0) return false;
        } catch (SocketException gone) {
            return false;
        }
        return !r.close;
    }

    @Nullable
    private Source resolve(String path) {
        if (path == null) return null;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        String[] seg = path.split("/");
        // "", token, id[, name]
        if (seg.length < 3) return null;
        if (!MessageDigest.isEqual(seg[1].getBytes(StandardCharsets.US_ASCII), token.getBytes(StandardCharsets.US_ASCII))) {
            return null;
        }
        return media.get(seg[2]);
    }

    /**
     * Single byte range per RFC 7233: null = whole file (no / unsupported header),
     * {-1,-1} = unsatisfiable (416), else {first, last} inclusive.
     */
    @Nullable
    static long[] parseRange(@Nullable String header, long length) {
        if (header == null) return null;
        String h = header.trim();
        if (!h.toLowerCase(Locale.ROOT).startsWith("bytes=")) return null;
        String spec = h.substring(6).trim();
        int comma = spec.indexOf(',');
        if (comma >= 0) spec = spec.substring(0, comma).trim(); // multi-range: serve the first
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        String a = spec.substring(0, dash).trim(), b = spec.substring(dash + 1).trim();
        try {
            if (a.isEmpty()) {
                if (b.isEmpty()) return null;
                long suffix = Long.parseLong(b);
                if (suffix <= 0 || length == 0) return new long[]{-1, -1};
                return new long[]{Math.max(0, length - suffix), length - 1};
            }
            long first = Long.parseLong(a);
            long last = b.isEmpty() ? length - 1 : Math.min(Long.parseLong(b), length - 1);
            if (first >= length || first > last || first < 0) return new long[]{-1, -1};
            return new long[]{first, last};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void writeHead(OutputStream out, int code, String reason, @Nullable String type,
                                  long length, @Nullable String contentRange, boolean close) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        if (type != null) sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(length).append("\r\n");
        sb.append("Accept-Ranges: bytes\r\n");
        if (contentRange != null) sb.append("Content-Range: ").append(contentRange).append("\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n");
        sb.append("Access-Control-Allow-Headers: Range, Content-Type\r\n");
        sb.append("Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("Connection: ").append(close ? "close" : "keep-alive").append("\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ sources

    static final class FileSource implements Source {
        private final File file;
        private final String mime;

        FileSource(File file, String mime) {
            this.file = file;
            this.mime = mime;
        }

        @Override public long length() {
            return file.length();
        }

        @Override public String mimeType() {
            return mime;
        }

        @Override public InputStream open(long offset) throws IOException {
            FileInputStream in = new FileInputStream(file);
            in.getChannel().position(offset);
            return in;
        }
    }

    /** A gallery video (content:// from MediaStore, or file://). */
    static final class ContentSource implements Source {
        private final Context ctx;
        private final Uri uri;
        private final String mime;
        private volatile long length = -1;

        ContentSource(Context ctx, Uri uri, String mime) {
            this.ctx = ctx.getApplicationContext();
            this.uri = uri;
            this.mime = mime;
        }

        @Override public long length() throws IOException {
            if (length >= 0) return length;
            try (AssetFileDescriptor afd = openAfd()) {
                long l = afd.getLength();
                if (l == AssetFileDescriptor.UNKNOWN_LENGTH) l = afd.getParcelFileDescriptor().getStatSize();
                if (l < 0) throw new IOException("unknown length");
                length = l;
                return l;
            }
        }

        private AssetFileDescriptor openAfd() throws IOException {
            ContentResolver cr = ctx.getContentResolver();
            AssetFileDescriptor afd = cr.openAssetFileDescriptor(uri, "r");
            if (afd == null) throw new IOException("can't open " + uri);
            return afd;
        }

        @Override public String mimeType() {
            return mime;
        }

        @Override public InputStream open(long offset) throws IOException {
            AssetFileDescriptor afd = openAfd();
            FileInputStream in = afd.createInputStream(); // closing it closes the descriptor
            try {
                long start = afd.getStartOffset() == AssetFileDescriptor.UNKNOWN_LENGTH ? 0 : afd.getStartOffset();
                in.getChannel().position(start + offset);
            } catch (IOException notSeekable) {
                long left = offset;
                while (left > 0) {
                    long n = in.skip(left);
                    if (n <= 0) throw notSeekable;
                    left -= n;
                }
            }
            return in;
        }
    }

    /** A Private video: decrypted on the fly from the encrypted vault file. */
    static final class VaultSource implements Source {
        private final Context ctx;
        private final Vault.Entry entry;
        private volatile long length = -1;

        VaultSource(Context ctx, Vault.Entry entry) {
            this.ctx = ctx.getApplicationContext();
            this.entry = entry;
        }

        @Override public long length() throws IOException {
            if (length >= 0) return length;
            try (SeekableByteChannel ch = Vault.openSeekable(ctx, entry)) {
                length = ch.size();
                return length;
            }
        }

        @Override public String mimeType() {
            return entry.mime == null || entry.mime.isEmpty() ? "video/mp4" : entry.mime;
        }

        @Override public InputStream open(long offset) throws IOException {
            SeekableByteChannel ch = Vault.openSeekable(ctx, entry);
            try {
                ch.position(offset);
            } catch (IOException e) {
                ch.close();
                throw e;
            }
            return Channels.newInputStream(ch);
        }
    }

    /** Small in-memory payloads (WebVTT subtitles, poster JPEGs), produced on first request. */
    static final class BytesSource implements Source {
        interface Producer {
            @Nullable byte[] produce();
        }

        private final Producer producer;
        private final String mime;
        private volatile byte[] bytes;

        BytesSource(byte[] bytes, String mime) {
            this(() -> bytes, mime);
        }

        BytesSource(Producer producer, String mime) {
            this.producer = producer;
            this.mime = mime;
        }

        private byte[] bytes() throws IOException {
            byte[] b = bytes;
            if (b == null) {
                b = producer.produce();
                if (b == null) throw new IOException("not available");
                bytes = b;
            }
            return b;
        }

        @Override public long length() throws IOException {
            return bytes().length;
        }

        @Override public String mimeType() {
            return mime;
        }

        @Override public InputStream open(long offset) throws IOException {
            byte[] b = bytes();
            int off = (int) Math.min(offset, b.length);
            return new ByteArrayInputStream(b, off, b.length - off);
        }
    }
}
