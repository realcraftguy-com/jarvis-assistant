package de.felix.jarvis.shared;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Minimal RFC 6455 WebSocket client (TLS, text/binary, fragmentation, ping/pong). Android has none built in. */
public final class WsClient {
    public interface Listener {
        void onMessage(String text);
        void onClosed(int code, String reason);
    }

    private final SSLSocket socket;
    private final OutputStream out;
    private final DataInputStream in;
    private final SecureRandom rng = new SecureRandom();
    private volatile boolean open = true;

    private WsClient(SSLSocket s) throws IOException {
        socket = s;
        out = s.getOutputStream();
        in = new DataInputStream(s.getInputStream());
    }

    public static WsClient connect(String url, Listener l) throws IOException {
        return connect(url, null, l);
    }

    public static WsClient connect(String url, java.util.Map<String, String> headers, Listener l) throws IOException {
        URI u = URI.create(url);
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : 443;
        SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
        s.connect(new InetSocketAddress(host, port), 10000);
        s.setSoTimeout(0);
        s.setTcpNoDelay(true);
        s.startHandshake();
        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String path = u.getRawPath() + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
        String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n";
        if (headers != null) for (java.util.Map.Entry<String, String> h : headers.entrySet()) req += h.getKey() + ": " + h.getValue() + "\r\n";
        req += "\r\n";
        s.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
        s.getOutputStream().flush();
        // read the HTTP response head
        InputStream raw = s.getInputStream();
        StringBuilder head = new StringBuilder();
        int c, nl = 0;
        while ((c = raw.read()) >= 0) {
            head.append((char) c);
            if (c == '\n') { if (++nl == 2) break; } else if (c != '\r') nl = 0;
        }
        if (!head.toString().startsWith("HTTP/1.1 101")) {
            String first = head.toString().split("\r\n", 2)[0];
            // read a short error body for diagnostics
            StringBuilder body = new StringBuilder();
            try { s.setSoTimeout(1500); int ch; while (body.length() < 400 && (ch = raw.read()) >= 0) body.append((char) ch); } catch (IOException ignored) {}
            s.close();
            throw new IOException("WebSocket abgelehnt: " + first + " " + body.toString().replaceAll("\\s+", " ").trim());
        }
        WsClient ws = new WsClient(s);
        Thread t = new Thread(() -> ws.readLoop(l), "ws-read");
        t.setDaemon(true);
        t.start();
        return ws;
    }

    public boolean isOpen() { return open; }

    public synchronized void send(String text) throws IOException {
        if (!open) throw new IOException("WebSocket geschlossen");
        frame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void frame(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(payload.length + 14);
        b.write(0x80 | opcode);
        int n = payload.length;
        if (n < 126) b.write(0x80 | n);
        else if (n < 65536) { b.write(0x80 | 126); b.write(n >> 8); b.write(n); }
        else { b.write(0x80 | 127); for (int i = 7; i >= 0; i--) b.write((int) ((long) n >> (8 * i))); }
        byte[] mask = new byte[4];
        rng.nextBytes(mask);
        b.write(mask, 0, 4);
        byte[] m = new byte[n];
        for (int i = 0; i < n; i++) m[i] = (byte) (payload[i] ^ mask[i & 3]);
        b.write(m, 0, n);
        out.write(b.toByteArray());
        out.flush();
    }

    private void readLoop(Listener l) {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        int code = 1006;
        String reason = "Verbindung getrennt";
        try {
            while (open) {
                int b0 = in.readUnsignedByte(), b1 = in.readUnsignedByte();
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0f;
                long len = b1 & 0x7f;
                if (len == 126) len = in.readUnsignedShort();
                else if (len == 127) len = in.readLong();
                byte[] mask = null;
                if ((b1 & 0x80) != 0) { mask = new byte[4]; in.readFully(mask); }
                byte[] p = new byte[(int) len];
                in.readFully(p);
                if (mask != null) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
                if (op == 0x8) {
                    code = p.length >= 2 ? ((p[0] & 0xff) << 8) | (p[1] & 0xff) : 1000;
                    reason = p.length > 2 ? new String(p, 2, p.length - 2, StandardCharsets.UTF_8) : "";
                    try { frame(0x8, new byte[0]); } catch (IOException ignored) {}
                    break;
                } else if (op == 0x9) {
                    frame(0xA, p);
                } else if (op == 0x1 || op == 0x2 || op == 0x0) {
                    msg.write(p, 0, p.length);
                    if (fin) {
                        // Gemini sends its JSON in text or binary frames – both are UTF-8 JSON
                        l.onMessage(new String(msg.toByteArray(), StandardCharsets.UTF_8));
                        msg.reset();
                    }
                }
            }
        } catch (IOException e) {
            if (open) reason = e.getMessage() == null ? reason : e.getMessage();
        } finally {
            open = false;
            try { socket.close(); } catch (IOException ignored) {}
            l.onClosed(code, reason);
        }
    }

    /** Non-blocking: the close handshake is network I/O, which Android forbids on the main thread (crash on hang-up). */
    public void close() {
        if (!open) return;
        open = false;
        Thread t = new Thread(() -> {
            try { frame(0x8, new byte[]{0x03, (byte) 0xe8}); } catch (Throwable ignored) {}
            try { socket.close(); } catch (Throwable ignored) {}
        }, "ws-close");
        t.setDaemon(true);
        t.start();
    }
}
