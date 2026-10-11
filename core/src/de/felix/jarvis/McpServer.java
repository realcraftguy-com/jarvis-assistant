package de.felix.jarvis;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Jarvis' tools as an MCP server (streamable HTTP, JSON responses) on 127.0.0.1 only, protected by a
 * bearer secret. Claude Code (running in Termux on this phone) connects to it and can use exactly these
 * tools – its own shell/file/web tools are switched off by the bridge.
 */
public final class McpServer {
    public static final int PORT = 8790;
    private static McpServer instance;
    private final Context ctx;
    private volatile boolean running;

    private McpServer(Context c) { ctx = c.getApplicationContext(); }

    public static synchronized void start(Context c) {
        if (instance != null) return;
        instance = new McpServer(c);
        instance.running = true;
        new Thread(instance::serve, "jarvis-mcp").start();
    }

    private void serve() {
        try (ServerSocket ss = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))) {
            while (running) {
                Socket s = ss.accept();
                new Thread(() -> handle(s), "jarvis-mcp-req").start();
            }
        } catch (Exception e) {
            Db.get(ctx).log("E", "claude", "Werkzeug-Server gestoppt: " + e.getMessage());
            instance = null;
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(60000);
            InputStream in = sock.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String requestLine = r.readLine();
            if (requestLine == null) return;
            int len = 0;
            String auth = "";
            String line;
            while ((line = r.readLine()) != null && !line.isEmpty()) {
                String l = line.toLowerCase(java.util.Locale.ROOT);
                if (l.startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
                if (l.startsWith("authorization:")) auth = line.substring(14).trim();
            }
            OutputStream out = sock.getOutputStream();
            String secret = Secrets.get(ctx, Secrets.MCP);
            if (secret == null || !constantEquals(auth, "Bearer " + secret)) { reply(out, 401, null); return; }
            if (!requestLine.startsWith("POST")) { reply(out, 405, null); return; }
            char[] buf = new char[len];
            int got = 0;
            while (got < len) { int n = r.read(buf, got, len - got); if (n < 0) break; got += n; }
            JSONObject req = new JSONObject(new String(buf, 0, got));
            JSONObject res = dispatch(req);
            if (res == null) reply(out, 202, null); else reply(out, 200, res.toString());
        } catch (Exception e) {
            Db.get(ctx).log("W", "claude", "Werkzeug-Anfrage fehlerhaft: " + e.getMessage());
        }
    }

    private JSONObject dispatch(JSONObject req) throws Exception {
        String method = req.optString("method");
        Object id = req.opt("id");
        if (id == null) return null; // notification (e.g. notifications/initialized)
        JSONObject result;
        switch (method) {
            case "initialize":
                result = new JSONObject().put("protocolVersion", req.optJSONObject("params") != null
                                ? req.getJSONObject("params").optString("protocolVersion", "2025-06-18") : "2025-06-18")
                        .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                        .put("serverInfo", new JSONObject().put("name", "jarvis").put("version", "1.0"));
                break;
            case "ping":
                result = new JSONObject();
                break;
            case "tools/list": {
                JSONArray tools = new JSONArray();
                JSONArray defs = Brain.get(ctx).agent.toolsFor("claude");
                for (int i = 0; i < defs.length(); i++) {
                    JSONObject f = defs.getJSONObject(i).getJSONObject("function");
                    tools.put(new JSONObject().put("name", f.getString("name")).put("description", f.optString("description"))
                            .put("inputSchema", f.optJSONObject("parameters") == null ? new JSONObject().put("type", "object") : f.getJSONObject("parameters")));
                }
                result = new JSONObject().put("tools", tools);
                break;
            }
            case "tools/call": {
                JSONObject p = req.getJSONObject("params");
                String name = p.getString("name");
                JSONObject args = p.optJSONObject("arguments") == null ? new JSONObject() : p.getJSONObject("arguments");
                String text;
                boolean err = false;
                try {
                    text = Brain.get(ctx).agent.run(name, args);
                } catch (Exception e) {
                    text = "Fehler: " + e.getMessage();
                    err = true;
                }
                Db.get(ctx).log("I", "werkzeug", "(Claude) " + name + " → " + SchoolPipeline.shorten(text, 100));
                result = new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", text))).put("isError", err);
                break;
            }
            default:
                return new JSONObject().put("jsonrpc", "2.0").put("id", id)
                        .put("error", new JSONObject().put("code", -32601).put("message", "unbekannte Methode " + method));
        }
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result);
    }

    private static void reply(OutputStream out, int code, String body) throws Exception {
        byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 202 ? "Accepted" : code == 401 ? "Unauthorized" : "Method Not Allowed";
        String head = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: application/json\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private static boolean constantEquals(String a, String b) {
        try {
            return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
